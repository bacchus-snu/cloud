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
package com.cloud.capacity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.persistence.EntityExistsException;

import org.apache.cloudstack.api.command.admin.host.ListHostPoolCoresCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPoolCoresCmd;
import org.apache.cloudstack.api.response.HostPoolCoreResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.capacity.dao.HostCoreAssignmentDao;
import com.cloud.capacity.dao.HostPoolCoreDao;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public class HostPoolCoreManagerImpl extends ManagerBase implements HostPoolCoreManager, PluggableService {

    private static final int HOST_LOCK_TIMEOUT_SECONDS = 60;
    private static final String NODE = "node";
    private static final String THREADS = "threads";

    @Inject
    HostDao hostDao;
    @Inject
    HostPoolCoreDao hostPoolCoreDao;
    @Inject
    HostCoreAssignmentDao hostCoreAssignmentDao;
    @Inject
    VMInstanceDao vmInstanceDao;

    @FunctionalInterface
    protected interface HostLockedOperation<T, E extends Exception> {
        T run() throws E;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(UpdateHostPoolCoresCmd.class);
        cmdList.add(ListHostPoolCoresCmd.class);
        return cmdList;
    }

    /**
     * @return the cores in core order
     * @throws IllegalArgumentException unless the JSON is an array of cores, each a non-negative node and two
     *         distinct ascending thread ids, with no thread in two cores
     */
    static List<PoolCore> parsePoolCores(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the pool cores are not valid JSON");
        }
        if (root == null || !root.isJsonArray()) {
            throw new IllegalArgumentException("the pool cores must be a JSON array");
        }
        List<PoolCore> cores = new ArrayList<>();
        Set<Integer> threads = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(String.format("pool core %s is not an object with a node and threads", element));
            }
            JsonObject core = element.getAsJsonObject();
            int node = nonNegativeInt(core.get(NODE), String.format("the node of pool core %s", core));
            JsonElement pair = core.get(THREADS);
            if (pair == null || !pair.isJsonArray() || pair.getAsJsonArray().size() != 2) {
                throw new IllegalArgumentException(String.format("pool core %s does not have exactly two threads", core));
            }
            int first = nonNegativeInt(pair.getAsJsonArray().get(0), String.format("a thread of pool core %s", core));
            int second = nonNegativeInt(pair.getAsJsonArray().get(1), String.format("a thread of pool core %s", core));
            if (first >= second) {
                throw new IllegalArgumentException(String.format("the threads of pool core %s are not two distinct thread ids in ascending order", core));
            }
            for (int thread : new int[] {first, second}) {
                if (!threads.add(thread)) {
                    throw new IllegalArgumentException(String.format("thread %d is in more than one pool core", thread));
                }
            }
            cores.add(new PoolCore(node, first, second));
        }
        cores.sort(Comparator.comparingInt(PoolCore::getCore));
        return cores;
    }

    private static int nonNegativeInt(JsonElement element, String what) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(what + " is not a non-negative integer");
        }
        try {
            int value = new BigDecimal(element.getAsJsonPrimitive().getAsString()).intValueExact();
            if (value < 0) {
                throw new IllegalArgumentException(what + " is not a non-negative integer");
            }
            return value;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a non-negative integer");
        }
    }

    /**
     * @param free the free cores, in core order
     * @return the cores to assign in vCPU order, or null when there are too few
     */
    static List<PoolCore> choosePoolCores(List<PoolCore> free, int needed) {
        if (free.size() < needed) {
            return null;
        }
        TreeMap<Integer, List<PoolCore>> byNode = free.stream()
                .collect(Collectors.groupingBy(PoolCore::getNode, TreeMap::new, Collectors.toList()));
        List<PoolCore> fittest = null;
        for (List<PoolCore> node : byNode.values()) {
            if (node.size() >= needed && (fittest == null || node.size() < fittest.size())) {
                fittest = node;
            }
        }
        if (fittest != null) {
            return new ArrayList<>(fittest.subList(0, needed));
        }
        return byNode.values().stream().flatMap(List::stream).limit(needed).collect(Collectors.toList());
    }

    /**
     * Runs the operation while holding the host's pool-core lock, which all management servers share; assignments
     * made under it are committed before it is released.
     */
    protected <T, E extends Exception> T withHostLock(long hostId, HostLockedOperation<T, E> operation, Supplier<E> lockTimeout) throws E {
        GlobalLock lock = GlobalLock.getInternLock("host.pool.cores." + hostId);
        try {
            if (!lock.lock(HOST_LOCK_TIMEOUT_SECONDS)) {
                throw lockTimeout.get();
            }
            try {
                return operation.run();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    @Override
    public List<PoolCore> listPoolCores(long hostId) {
        return hostPoolCoreDao.listByHostId(hostId).stream()
                .map(row -> new PoolCore(row.getNode(), row.getCore(), row.getSibling()))
                .sorted(Comparator.comparingInt(PoolCore::getCore))
                .collect(Collectors.toList());
    }

    /**
     * @param forVmId a VM whose own cores count as free, or null
     */
    private List<PoolCore> listFreePoolCores(long hostId, Long forVmId) {
        Set<Integer> held = hostCoreAssignmentDao.listByHostId(hostId).stream()
                .filter(assignment -> !Objects.equals(assignment.getVmId(), forVmId))
                .map(HostCoreAssignmentVO::getCore)
                .collect(Collectors.toSet());
        return listPoolCores(hostId).stream().filter(core -> !held.contains(core.getCore())).collect(Collectors.toList());
    }

    @Override
    public int countFreePoolCores(long hostId) {
        return listFreePoolCores(hostId, null).size();
    }

    @Override
    public List<PoolCore> allocatePoolCores(VirtualMachine vm, Host host, int vcpus) throws InsufficientServerCapacityException {
        int needed = HostPoolCoreManager.coresForVcpus(vcpus);
        return withHostLock(host.getId(), () -> {
            List<PoolCore> cores = choosePoolCores(listFreePoolCores(host.getId(), vm.getId()), needed);
            if (cores == null) {
                throw new InsufficientServerCapacityException(String.format("%s has fewer than %d free pool cores for %s", host, needed, vm),
                        Host.class, host.getId());
            }
            try {
                hostCoreAssignmentDao.assign(host.getId(), vm.getId(), cores.stream().map(PoolCore::getCore).collect(Collectors.toList()));
            } catch (EntityExistsException e) {
                throw new InsufficientServerCapacityException(String.format("A pool core of %s chosen for %s is held by another VM", host, vm),
                        Host.class, host.getId());
            }
            logger.debug("Assigned pool cores {} of {} to {}", cores, host, vm);
            return cores;
        }, () -> new InsufficientServerCapacityException(String.format("Unable to lock the pool cores of %s for %s", host, vm), Host.class, host.getId()));
    }

    @Override
    public void releasePoolCores(long vmId) {
        int released = hostCoreAssignmentDao.releaseIfNotRunning(vmId);
        if (released > 0) {
            logger.debug("Released {} pool cores of VM {}", released, vmId);
        }
    }

    @Override
    public void removeStaleAssignments(long hostId) {
        int removed = hostCoreAssignmentDao.removeStale(hostId);
        if (removed > 0) {
            logger.info("Removed {} pool core assignments of host {} whose VMs no longer run there", removed, hostId);
        }
    }

    @Override
    public void removeHostPoolCores(long hostId) {
        int assignments = hostCoreAssignmentDao.removeByHostId(hostId);
        int cores = hostPoolCoreDao.removeByHostId(hostId);
        logger.debug("Removed {} pool core assignments and {} registered pool cores of removed host {}", assignments, cores, hostId);
    }

    private HostVO getPoolCoreHost(Long hostId) {
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new InvalidParameterValueException("Unable to find a host with ID " + hostId);
        }
        if (host.getType() != Host.Type.Routing || !HostPoolCoreManager.usesPoolCores(host)) {
            throw new InvalidParameterValueException(String.format("%s does not run Instances on pool cores; only KVM hosts do", host));
        }
        return host;
    }

    @Override
    public ListResponse<HostPoolCoreResponse> updateHostPoolCores(UpdateHostPoolCoresCmd cmd) {
        HostVO host = getPoolCoreHost(cmd.getHostId());
        List<PoolCore> cores;
        try {
            cores = parsePoolCores(cmd.getCores());
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid pool cores: " + e.getMessage());
        }
        withHostLock(host.getId(), () -> {
            removeStaleAssignments(host.getId());
            Map<Integer, PoolCore> registered = byCore(listPoolCores(host.getId()));
            Map<Integer, PoolCore> next = byCore(cores);
            for (HostCoreAssignmentVO assignment : hostCoreAssignmentDao.listByHostId(host.getId())) {
                PoolCore now = registered.get(assignment.getCore());
                PoolCore then = next.get(assignment.getCore());
                if (then == null || (now != null && !now.equals(then))) {
                    VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(assignment.getVmId());
                    throw new InvalidParameterValueException(String.format("Core %d of %s is assigned to %s; stop the Instance before removing or changing the core",
                            assignment.getCore(), host, vm == null ? assignment.getVmId() : vm));
                }
            }
            hostPoolCoreDao.replace(host.getId(), cores.stream()
                    .map(core -> new HostPoolCoreVO(host.getId(), core.getCore(), core.getThreads()[1], core.getNode()))
                    .collect(Collectors.toList()));
            return null;
        }, () -> new CloudRuntimeException("Unable to lock the pool cores of " + host));
        logger.info("Registered {} pool cores for {}", cores.size(), host);
        return listHostPoolCores(host, null, null);
    }

    private static Map<Integer, PoolCore> byCore(List<PoolCore> cores) {
        return cores.stream().collect(Collectors.toMap(PoolCore::getCore, Function.identity()));
    }

    @Override
    public ListResponse<HostPoolCoreResponse> listHostPoolCores(ListHostPoolCoresCmd cmd) {
        HostVO host = hostDao.findById(cmd.getHostId());
        if (host == null) {
            throw new InvalidParameterValueException("Unable to find a host with ID " + cmd.getHostId());
        }
        return listHostPoolCores(host, cmd.getStartIndex(), cmd.getPageSizeVal());
    }

    private ListResponse<HostPoolCoreResponse> listHostPoolCores(HostVO host, Long startIndex, Long pageSize) {
        Map<Integer, Long> holders = hostCoreAssignmentDao.listByHostId(host.getId()).stream()
                .collect(Collectors.toMap(HostCoreAssignmentVO::getCore, HostCoreAssignmentVO::getVmId, (a, b) -> a));
        List<HostPoolCoreResponse> responses = new ArrayList<>();
        for (PoolCore core : listPoolCores(host.getId())) {
            HostPoolCoreResponse response = new HostPoolCoreResponse();
            response.setHostId(host.getUuid());
            response.setCore(core.getCore());
            response.setNode(core.getNode());
            List<Integer> threads = new ArrayList<>();
            for (int thread : core.getThreads()) {
                threads.add(thread);
            }
            response.setThreads(threads);
            Long vmId = holders.get(core.getCore());
            if (vmId != null) {
                VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
                if (vm != null) {
                    response.setVirtualMachineId(vm.getUuid());
                    response.setVirtualMachineName(vm.getHostName());
                    response.setInstanceName(vm.getInstanceName());
                }
            }
            responses.add(response);
        }
        List<HostPoolCoreResponse> page = com.cloud.utils.StringUtils.applyPagination(responses, startIndex, pageSize);
        ListResponse<HostPoolCoreResponse> list = new ListResponse<>();
        list.setResponses(page != null ? page : responses, responses.size());
        return list;
    }
}
