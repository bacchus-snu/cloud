// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.cloudstack.gpu.GpuService.AllocationAlgorithm;
import org.apache.cloudstack.gpu.GpuService.GroupRule;

/**
 * Chooses and counts the whole GPU groups of one host. GPUs are named by their PCI bus addresses and placed on NUMA
 * nodes; a set that spans nodes must hold the same number of GPUs on each, because the guest gets one socket per
 * node with equal cores.
 */
final class GpuGroupSelector {

    static final class Candidate {
        private final List<String> buses;
        private final List<Integer> packing;

        Candidate(List<String> buses, List<Integer> packing) {
            this.buses = buses;
            this.packing = packing;
        }

        List<String> getBuses() {
            return buses;
        }

        /**
         * @return the free GPUs left after this set is taken: in each registered group that encloses it, smallest
         *         group first, or, for rules any and single, on the NUMA nodes it uses
         */
        List<Integer> getPacking() {
            return packing;
        }

        int enclosingFreeAfter() {
            return packing.isEmpty() ? 0 : packing.get(0);
        }
    }

    private static final Comparator<List<String>> BUS_ORDER = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    private static final Comparator<List<Integer>> PACKING_ORDER = (a, b) -> {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int c = Integer.compare(i < a.size() ? a.get(i) : 0, i < b.size() ? b.get(i) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    };

    private final GroupRule rule;
    private final List<List<String>> groups;

    /**
     * @param groups the registered groups of a host with rule partitions, each in bus address order; ignored for
     *        other rules
     */
    GpuGroupSelector(GroupRule rule, List<List<String>> groups) {
        this.rule = rule;
        this.groups = rule == GroupRule.partitions ? groups : Collections.emptyList();
    }

    /**
     * @param free the NUMA node of each usable GPU, by bus address
     * @return every usable set of the given size that the rule allows; for rule any only the sets on the fewest nodes
     */
    List<Candidate> candidates(Map<String, String> free, int count) {
        if (count < 1) {
            return Collections.emptyList();
        }
        switch (rule) {
            case partitions:
                return partitionCandidates(free, count);
            case any:
                return spreadCandidates(free, count, null);
            case single:
                return count == 1 ? spreadCandidates(free, 1, null) : Collections.emptyList();
            default:
                return Collections.emptyList();
        }
    }

    Optional<Candidate> choose(Map<String, String> free, int count, AllocationAlgorithm algorithm, Random random) {
        List<Candidate> candidates = candidates(free, count);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        switch (algorithm) {
            case firstfit:
                return candidates.stream().min(Comparator.comparing(Candidate::getBuses, BUS_ORDER));
            case random:
                if (rule == GroupRule.partitions) {
                    return Optional.of(candidates.get(random.nextInt(candidates.size())));
                }
                return Optional.of(spreadCandidates(free, count, random).get(0));
            default:
                return candidates.stream().min(Comparator.comparing(Candidate::getPacking, PACKING_ORDER)
                        .thenComparing(Candidate::getBuses, BUS_ORDER));
        }
    }

    /**
     * @return the best-fit score of the host for the given free GPUs, or Integer.MAX_VALUE when no set fits
     */
    int bestFitScore(Map<String, String> free, int count) {
        Optional<Candidate> best = choose(free, count, AllocationAlgorithm.bestfit, null);
        if (best.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return (free.size() - count) * 1024 + Math.min(best.get().enclosingFreeAfter(), 1023);
    }

    /**
     * @param gpus the NUMA node of each GPU to count, by bus address
     * @return how many sets of the given size the GPUs can form at once
     */
    int slots(Map<String, String> gpus, int count) {
        if (count < 1) {
            return 0;
        }
        switch (rule) {
            case partitions:
                return partitionCandidates(gpus, count).size();
            case any:
                return disjointSpreads(byNode(gpus).values().stream().mapToInt(List::size).toArray(), count, new HashMap<>());
            case single:
                return count == 1 ? gpus.size() : 0;
            default:
                return 0;
        }
    }

    private List<Candidate> partitionCandidates(Map<String, String> free, int count) {
        List<Candidate> candidates = new ArrayList<>();
        for (List<String> group : groups) {
            if (group.size() != count || !free.keySet().containsAll(group)) {
                continue;
            }
            List<List<String>> enclosing = groups.stream()
                    .filter(other -> other.size() > group.size() && other.containsAll(group))
                    .sorted(Comparator.comparingInt(List::size))
                    .collect(Collectors.toList());
            List<Integer> packing = new ArrayList<>();
            for (List<String> outer : enclosing) {
                packing.add((int) outer.stream().filter(free::containsKey).count() - count);
            }
            candidates.add(new Candidate(group, packing));
        }
        return candidates;
    }

    /**
     * Sets spread evenly over the fewest NUMA nodes that can hold them; with a random source, one random such set,
     * otherwise every choice of nodes, each with its lowest bus addresses.
     */
    private List<Candidate> spreadCandidates(Map<String, String> free, int count, Random random) {
        TreeMap<String, List<String>> byNode = byNode(free);
        List<String> nodes = new ArrayList<>(byNode.keySet());
        for (int k = 1; k <= nodes.size(); k++) {
            if (count % k != 0) {
                continue;
            }
            int perNode = count / k;
            List<List<String>> choices = new ArrayList<>();
            combinations(nodes, k, 0, new ArrayList<>(), choices);
            choices.removeIf(choice -> choice.stream().anyMatch(node -> byNode.get(node).size() < perNode));
            if (choices.isEmpty()) {
                continue;
            }
            if (random != null) {
                choices = Collections.singletonList(choices.get(random.nextInt(choices.size())));
            }
            List<Candidate> candidates = new ArrayList<>();
            for (List<String> choice : choices) {
                List<String> buses = new ArrayList<>();
                int left = 0;
                for (String node : choice) {
                    List<String> onNode = new ArrayList<>(byNode.get(node));
                    if (random != null) {
                        Collections.shuffle(onNode, random);
                    }
                    buses.addAll(onNode.subList(0, perNode));
                    left += onNode.size() - perNode;
                }
                Collections.sort(buses);
                candidates.add(new Candidate(buses, Collections.singletonList(left)));
            }
            return candidates;
        }
        return Collections.emptyList();
    }

    private static TreeMap<String, List<String>> byNode(Map<String, String> gpus) {
        TreeMap<String, List<String>> byNode = new TreeMap<>();
        new TreeMap<>(gpus).forEach((bus, node) -> byNode.computeIfAbsent(node, n -> new ArrayList<>()).add(bus));
        return byNode;
    }

    private static <T> void combinations(List<T> items, int k, int from, List<T> current, List<List<T>> out) {
        if (current.size() == k) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            current.add(items.get(i));
            combinations(items, k, i + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    /**
     * @return the largest number of disjoint sets of the given size, each spread evenly over the nodes it uses, that
     *         nodes holding the given numbers of GPUs can form
     */
    private static int disjointSpreads(int[] perNode, int count, Map<String, Integer> memo) {
        int[] sorted = perNode.clone();
        Arrays.sort(sorted);
        String key = Arrays.toString(sorted);
        Integer known = memo.get(key);
        if (known != null) {
            return known;
        }
        int best = 0;
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < sorted.length; i++) {
            indexes.add(i);
        }
        for (int k = 1; k <= sorted.length; k++) {
            if (count % k != 0) {
                continue;
            }
            int each = count / k;
            List<List<Integer>> choices = new ArrayList<>();
            combinations(indexes, k, 0, new ArrayList<>(), choices);
            for (List<Integer> choice : choices) {
                int[] rest = sorted.clone();
                boolean fits = true;
                for (int i : choice) {
                    rest[i] -= each;
                    fits &= rest[i] >= 0;
                }
                if (fits) {
                    best = Math.max(best, 1 + disjointSpreads(rest, count, memo));
                }
            }
        }
        memo.put(key, best);
        return best;
    }
}
