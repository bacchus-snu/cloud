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

import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.host.Host;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.VMTemplateVO;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine;

/**
 * Capacity Manager manages the different capacities
 * available within the Cloud Stack.
 *
 */
public interface CapacityManager {

    static final String CpuOverprovisioningFactorCK = "cpu.overprovisioning.factor";
    static final String MemOverprovisioningFactorCK = "mem.overprovisioning.factor";
    static final String StorageCapacityDisableThresholdCK = "pool.storage.capacity.disablethreshold";
    static final String StorageOverprovisioningFactorCK = "storage.overprovisioning.factor";
    static final String StorageAllocatedCapacityDisableThresholdCK = "pool.storage.allocated.capacity.disablethreshold";
    static final String StorageAllocatedCapacityDisableThresholdForVolumeResizeCK = "pool.storage.allocated.resize.capacity.disablethreshold";

    static final ConfigKey<Float> CpuOverprovisioningFactor =
            new ConfigKey<>(
                    Float.class,
                    CpuOverprovisioningFactorCK,
                    ConfigKey.CATEGORY_ADVANCED,
                    "1.0",
                    "Used for CPU overprovisioning calculation; available CPU will be (actualCpuCapacity * cpu.overprovisioning.factor)",
                    true,
                    ConfigKey.Scope.Cluster,
                    null);
    static final ConfigKey<Float> MemOverprovisioningFactor =
            new ConfigKey<>(
                    Float.class,
                    MemOverprovisioningFactorCK,
                    ConfigKey.CATEGORY_ADVANCED,
                    "1.0",
                    "Used for memory overprovisioning calculation",
                    true,
                    ConfigKey.Scope.Cluster,
                    null);
    static final ConfigKey<Double> StorageCapacityDisableThreshold =
            new ConfigKey<>(
                    ConfigKey.CATEGORY_ALERT,
                    Double.class,
                    StorageCapacityDisableThresholdCK,
                    "0.85",
                    "Percentage (as a value between 0 and 1) of storage utilization above which allocators will disable using the pool for low storage available.",
                    true,
                    List.of(ConfigKey.Scope.StoragePool, ConfigKey.Scope.Zone));
    static final ConfigKey<Double> StorageOverprovisioningFactor =
            new ConfigKey<>(
                    "Storage",
                    Double.class,
                    StorageOverprovisioningFactorCK,
                    "2",
                    "Used for storage overprovisioning calculation; available storage will be (actualStorageSize * storage.overprovisioning.factor)",
                    true,
                    ConfigKey.Scope.StoragePool);
    static final ConfigKey<Double> StorageAllocatedCapacityDisableThreshold =
            new ConfigKey<>(
                    ConfigKey.CATEGORY_ALERT,
                    Double.class,
                    StorageAllocatedCapacityDisableThresholdCK,
                    "0.85",
                    "Percentage (as a value between 0 and 1) of allocated storage utilization above which allocators will disable using the pool for low allocated storage available.",
                    true,
                    List.of(ConfigKey.Scope.StoragePool, ConfigKey.Scope.Zone));
    static final ConfigKey<Boolean> StorageOperationsExcludeCluster =
            new ConfigKey<>(
                    Boolean.class,
                    "cluster.storage.operations.exclude",
                    ConfigKey.CATEGORY_ADVANCED,
                    "false",
                    "Exclude cluster from storage operations",
                    true,
                    ConfigKey.Scope.Cluster,
                    null);
    static final ConfigKey<String> ImageStoreNFSVersion =
            new ConfigKey<>(
                    String.class,
                    "secstorage.nfs.version",
                    ConfigKey.CATEGORY_ADVANCED,
                    null,
                    "Enforces specific NFS version when mounting Secondary Storage. If NULL default selection is performed",
                    true,
                    ConfigKey.Scope.ImageStore,
                    null);

    static final ConfigKey<Float> SecondaryStorageCapacityThreshold =
            new ConfigKey<>(
                    ConfigKey.CATEGORY_ADVANCED,
                    Float.class,
                    "secondary.storage.capacity.threshold",
                    "0.90",
                    "Percentage (as a value between 0 and 1) of secondary storage capacity threshold.",
                    true);

    static final ConfigKey<Double> StorageAllocatedCapacityDisableThresholdForVolumeSize =
            new ConfigKey<>(
                    ConfigKey.CATEGORY_ALERT,
                    Double.class,
                    StorageAllocatedCapacityDisableThresholdForVolumeResizeCK,
                    "0.90",
                    "Percentage (as a value between 0 and 1) of allocated storage utilization above which allocators will disable using the pool for volume resize. " +
                            "This is applicable only when volume.resize.allowed.beyond.allocation is set to true.",
                    true,
                    List.of(ConfigKey.Scope.StoragePool, ConfigKey.Scope.Zone));

    ConfigKey<Integer> CapacityCalculateWorkers = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class,
            "capacity.calculate.workers", "1",
            "Number of worker threads to be used for capacities calculation", true);

    ConfigKey<Integer> KvmMemoryDynamicScalingCapacity = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED,
            Integer.class, "kvm.memory.dynamic.scaling.capacity", "0",
            "Defines the maximum memory capacity in MiB for which VMs can be dynamically scaled to with KVM. " +
                    "The 'kvm.memory.dynamic.scaling.capacity' setting's value will be used to define the value of the " +
                    "'<maxMemory />' element of domain XMLs. If it is set to a value less than or equal to '0', then the host's memory capacity will be considered.",
            true, ConfigKey.Scope.Cluster);

    ConfigKey<Integer> KvmCpuDynamicScalingCapacity = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED,
            Integer.class, "kvm.cpu.dynamic.scaling.capacity", "0",
            "Defines the maximum vCPU capacity for which VMs can be dynamically scaled to with KVM. " +
                    "The 'kvm.cpu.dynamic.scaling.capacity' setting's value will be used to define the value of the " +
                    "'<vcpu />' element of domain XMLs. If it is set to a value less than or equal to '0', then the host's CPU cores capacity will be considered.",
            true, ConfigKey.Scope.Cluster);

    /**
     * Free compute capacity of a host: total × cluster overprovisioning factor − used − reserved, from the host's
     * CPU and memory capacity entries. The values are negative when used and reserved exceed the total.
     * The free cores are the host's registered pool cores that no VM holds; 0 on a host that does not run VMs on
     * pool cores.
     */
    final class HostFreeCapacity {
        private final long freeCpuMhz;
        private final long freeMemoryBytes;
        private final int cpus;
        private final int speedMhz;
        private final int freeCores;

        public HostFreeCapacity(long freeCpuMhz, long freeMemoryBytes, int cpus, int speedMhz, int freeCores) {
            this.freeCpuMhz = freeCpuMhz;
            this.freeMemoryBytes = freeMemoryBytes;
            this.cpus = cpus;
            this.speedMhz = speedMhz;
            this.freeCores = freeCores;
        }

        public long getFreeCpuMhz() {
            return freeCpuMhz;
        }

        public long getFreeMemoryBytes() {
            return freeMemoryBytes;
        }

        public int getCpus() {
            return cpus;
        }

        public int getSpeedMhz() {
            return speedMhz;
        }

        public int getFreeCores() {
            return freeCores;
        }
    }

    /**
     * A VM whose offering has a vGPU profile runs on CPU and memory that its host sets aside for the GPU devices
     * outside the capacity the host reports, so its CPU and memory are neither counted in nor checked against the
     * host's CPU and memory capacity.
     */
    static boolean isHostCapacityExempt(ServiceOffering offering) {
        if (offering == null) {
            return false;
        }
        Long vgpuProfileId = offering.getVgpuProfileId();
        return vgpuProfileId != null && vgpuProfileId > 0;
    }

    /**
     * @return true when the host reports no CPU or no memory capacity, or runs VMs on pool cores and has none
     * registered; such a host can run only VMs that are exempt from host capacity
     */
    boolean hasNoComputeCapacity(Host host);

    /**
     * @return true when the host can take a VM that is counted against host CPU and memory at all, whatever its size:
     *         it reports compute capacity (see {@link #hasNoComputeCapacity}) and has not reached its guest limit
     */
    boolean canTakeCapacityCountedVm(Host host);

    /**
     * @return true when the host does not run VMs on pool cores, or has at least ceil(vcpus/2) free pool cores
     */
    default boolean checkIfHostHasPoolCores(Host host, int vcpus) {
        return checkIfHostHasPoolCores(host, vcpus, 1);
    }

    /**
     * @return true when the host does not run VMs on pool cores, or has ceil(vcpus/2) free pool cores for each of
     *         the given number of VMs
     */
    boolean checkIfHostHasPoolCores(Host host, int vcpus, int vms);

    /**
     * @return as {@link #checkIfHostHasPoolCores(Host, int)}, with the pool cores the VM holds on the host counted as free
     */
    boolean checkIfHostHasPoolCoresFor(Host host, int vcpus, long vmId);

    /**
     * Gives up the host resources a VM in a state in which it cannot run still holds: its pool cores, its GPU devices
     * when they are detached on stop, and the capacity reserved for it on its last host. Each release decides by the
     * VM's state at that moment, so a VM that has started again keeps what its start took.
     */
    void releaseResourcesOfStoppedVm(VirtualMachine vm);

    /**
     * Free capacity by the same formula the admission checks use, with the cluster's overprovisioning factors and
     * the reserved capacity counted as taken.
     * @return null when the host has no CPU or memory capacity entry; the free capacity is then unknown and the host
     *         admits nothing, as the admission checks treat it, so callers must not read null as free
     */
    HostFreeCapacity getFreeCapacity(Host host);

    public boolean releaseVmCapacity(VirtualMachine vm, boolean moveFromReserved, boolean moveToReservered, Long hostId);

    void allocateVmCapacity(VirtualMachine vm, boolean fromLastHost);

    /**
     * @param hostId Id of the host to check capacity
     * @param cpu required CPU
     * @param ram required RAM
     * @param cpuOverprovisioningFactor factor to apply to the actual host cpu
     */
    boolean checkIfHostHasCapacity(Host host, Integer cpu, long ram, boolean checkFromReservedCapacity, float cpuOverprovisioningFactor, float memoryOvercommitRatio,
        boolean considerReservedCapacity);

    void updateCapacityForHost(Host host);

    /**
     * @param pool storage pool
     * @param templateForVmCreation template that will be used for vm creation
     * @return total allocated capacity for the storage pool
     */
    long getAllocatedPoolCapacity(StoragePoolVO pool, VMTemplateVO templateForVmCreation);

    /**
     * Check if specified host's running VM count has reach hypervisor limit
     * @param host the host to be checked
     * @return true if the count of host's running VMs >= hypervisor limit
     */
    boolean checkIfHostReachMaxGuestLimit(Host host);

    /**
     * Check if specified host has capability to support cpu cores and speed freq
     * @param host the host to be checked
     * @param cpuNum cpu number to check
     * @param cpuSpeed cpu Speed to check
     * @return true if the count of host's running VMs >= hypervisor limit
     */
    boolean checkIfHostHasCpuCapability(Host host, Integer cpuNum, Integer cpuSpeed);

    /**
     * Check if cluster will cross threshold if the cpu/memory requested are accommodated
     * @param clusterId the clusterId to check
     * @param cpuRequested cpu requested
     * @param ramRequested cpu requested
     * @return true if the customer crosses threshold, false otherwise
     */
    boolean checkIfClusterCrossesThreshold(Long clusterId, Integer cpuRequested, long ramRequested);

    float getClusterOverProvisioningFactor(Long clusterId, short capacityType);

    long getUsedBytes(StoragePoolVO pool);

    long getUsedIops(StoragePoolVO pool);

    Pair<Boolean, Boolean> checkIfHostHasCpuCapabilityAndCapacity(Host host, ServiceOffering offering, boolean considerReservedCapacity);

    /**
     * As {@link #checkIfHostHasCpuCapabilityAndCapacity(Host, ServiceOffering, boolean)} for the given VM, whose own pool
     * cores on the host count as free.
     */
    Pair<Boolean, Boolean> checkIfHostHasCpuCapabilityAndCapacity(Host host, ServiceOffering offering, boolean considerReservedCapacity, long vmId);
}
