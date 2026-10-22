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

import java.util.Arrays;
import java.util.List;

import com.cloud.capacity.dao.HostCoreAssignmentDao;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VirtualMachine;

/**
 * Dedicated whole cores for VMs that are not exempt from host capacity. A host registers its pool cores; a VM with v
 * vCPUs is assigned ceil(v/2) of them from the time its start is placed on the host until it stops there. No core is
 * assigned to two VMs.
 */
public interface HostPoolCoreManager extends HostPoolCoreService {

    /**
     * A whole core: two sibling threads, named by the lower one.
     */
    final class PoolCore {
        private final int node;
        private final int[] threads;

        public PoolCore(int node, int firstThread, int secondThread) {
            this.node = node;
            this.threads = new int[] {firstThread, secondThread};
        }

        public int getCore() {
            return threads[0];
        }

        public int getNode() {
            return node;
        }

        public int[] getThreads() {
            return threads.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PoolCore)) {
                return false;
            }
            PoolCore other = (PoolCore) o;
            return node == other.node && Arrays.equals(threads, other.threads);
        }

        @Override
        public int hashCode() {
            return 31 * node + Arrays.hashCode(threads);
        }

        @Override
        public String toString() {
            return threads[0] + "," + threads[1];
        }
    }

    /**
     * Only the KVM agent places a domain on the cores named in its start command, and only a host that does so
     * runs VMs on pool cores.
     */
    static boolean usesPoolCores(Host host) {
        return host != null && host.getHypervisorType() == HypervisorType.KVM;
    }

    static int coresForVcpus(int vcpus) {
        return (vcpus + 1) / 2;
    }

    /**
     * @return true when a VM in this state may run on a host, so that it keeps the cores assigned to it there
     */
    static boolean holdsCores(VirtualMachine.State state) {
        return !HostCoreAssignmentDao.STATES_WITHOUT_CORES.contains(state);
    }

    /**
     * @return the host's registered pool cores ordered by core; empty when the host has none
     */
    List<PoolCore> listPoolCores(long hostId);

    /**
     * @return the registered pool cores of the host that no VM holds
     */
    int countFreePoolCores(long hostId);

    /**
     * Assigns ceil(vcpus/2) free pool cores of the host to the VM, replacing any cores the VM held before. Cores are
     * taken from one NUMA node when one node can supply them all: the node with the fewest free cores that suffice,
     * the lowest such node on a tie; otherwise from the nodes in order. Within a node the lowest cores are taken.
     *
     * @return the assigned cores in vCPU order
     * @throws InsufficientServerCapacityException scoped to the host when it cannot supply the cores
     */
    List<PoolCore> allocatePoolCores(VirtualMachine vm, Host host, int vcpus) throws InsufficientServerCapacityException;

    /**
     * Releases the VM's cores if, at that moment, it is removed or in a state in which it cannot run; a release that
     * arrives after a later start of the VM leaves that start's cores in place.
     */
    void releasePoolCores(long vmId);

    /**
     * Removes the host's assignments whose VM is gone, on another host, or in a state in which it cannot run.
     */
    void removeStaleAssignments(long hostId);

    /**
     * Removes all of a removed host's assignments, then its registration.
     */
    void removeHostPoolCores(long hostId);
}
