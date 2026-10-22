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

import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.offering.ServiceOffering;
import com.cloud.utils.component.Manager;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.UnmanageGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.DiscoverGpuDevicesCmd;
import org.apache.cloudstack.api.command.admin.gpu.ListHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.ManageGpuDeviceCmd;
import org.apache.cloudstack.api.command.user.gpu.ListGpuDevicesCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateVgpuProfileCmd;
import org.apache.cloudstack.api.command.user.gpu.ListGpuCardsCmd;
import org.apache.cloudstack.api.command.user.gpu.ListVgpuProfilesCmd;
import org.apache.cloudstack.api.response.GpuCardResponse;
import org.apache.cloudstack.api.response.GpuDeviceResponse;
import org.apache.cloudstack.api.response.HostGpuGroupsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VgpuProfileResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GpuService extends Manager {

    ConfigKey<Boolean> GpuDetachOnStop = new ConfigKey<>(Boolean.class, "gpu.detach.on.stop", "Advanced", "false",
            "Whether to detach GPU devices from VM on stop or keep them allocated", true, ConfigKey.Scope.Domain, null);

    enum AllocationAlgorithm {
        bestfit, firstfit, random
    }

    /**
     * Whatever its value, an Instance takes only a whole usable group of a host: the value changes the order, never
     * what is usable.
     */
    ConfigKey<String> GpuAllocationAlgorithm = new ValidatedConfigKey<>(String.class, "gpu.allocation.algorithm", "Advanced",
            AllocationAlgorithm.bestfit.name(),
            "Order in which hosts and GPU groups are chosen for Instances with a vGPU profile: 'bestfit' takes the host left with the " +
                    "fewest free GPUs of the type, then the group whose enclosing group is most filled, then the lowest host id; " +
                    "'firstfit' takes the lowest host id and its first usable group; 'random' takes a random usable host and group. " +
                    "It is independent of vm.allocation.algorithm.",
            true, ConfigKey.Scope.Global, ConfigKey.Kind.Select, "bestfit,firstfit,random",
            value -> {
                if (Arrays.stream(AllocationAlgorithm.values()).noneMatch(algorithm -> algorithm.name().equals(value))) {
                    throw new InvalidParameterValueException(String.format("Invalid value %s for gpu.allocation.algorithm; valid values are %s",
                            value, Arrays.toString(AllocationAlgorithm.values())));
                }
            });

    /**
     * How the GPU groups of a host are formed.
     */
    enum GroupRule {
        /** Only the registered groups, which are laminar and each of one card. */
        partitions,
        /** Any set of the host's GPUs, spread evenly over the NUMA nodes it uses; sets on fewer nodes are preferred. */
        any,
        /** One GPU at a time. */
        single
    }

    /**
     * The GPU slots of a service offering: how many Instances of it could start at once, and how many could run on the
     * GPUs present if none were in use.
     */
    final class SlotCount {
        private final int available;
        private final int total;

        public SlotCount(int available, int total) {
            this.available = available;
            this.total = total;
        }

        public int getAvailable() {
            return available;
        }

        public int getTotal() {
            return total;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SlotCount && ((SlotCount) o).available == available && ((SlotCount) o).total == total;
        }

        @Override
        public int hashCode() {
            return 31 * available + total;
        }

        @Override
        public String toString() {
            return available + "/" + total;
        }
    }

    GpuCard createGpuCard(CreateGpuCardCmd cmd);

    GpuCard updateGpuCard(UpdateGpuCardCmd cmd);

    boolean deleteGpuCard(DeleteGpuCardCmd cmd);

    VgpuProfileResponse createVgpuProfile(CreateVgpuProfileCmd cmd);

    VgpuProfileResponse updateVgpuProfile(UpdateVgpuProfileCmd cmd);

    boolean deleteVgpuProfile(DeleteVgpuProfileCmd cmd);

    ListResponse<GpuCardResponse> listGpuCards(ListGpuCardsCmd cmd);

    ListResponse<VgpuProfileResponse> listVgpuProfiles(ListVgpuProfilesCmd cmd);

    GpuDeviceResponse createGpuDevice(CreateGpuDeviceCmd cmd);

    GpuDeviceResponse updateGpuDevice(UpdateGpuDeviceCmd cmd);

    ListResponse<GpuDeviceResponse> listGpuDevices(ListGpuDevicesCmd cmd);

    boolean disableGpuDevice(UnmanageGpuDeviceCmd cmd);

    boolean enableGpuDevice(ManageGpuDeviceCmd cmd);

    /**
     * Deallocate GPU devices for a VM on a host.
     *
     * @param vmId The ID of the VM to deallocate GPU devices for.
     */
    void deallocateAllGpuDevicesForVm(long vmId);


    /**
     * Deallocate GPU devices for a VM on a host.
     *
     * @param vmId The ID of the VM to deallocate GPU devices for.
     */
    void deallocateGpuDevicesForVmOnHost(long vmId, long hostId);

    /**
     * Deallocate existing GPU devices for a VM on a host and allocate new GPU devices to the VM.
     *
     * @param vmId       The ID of the VM to allocate GPU devices to.
     * @param hostId     The ID of the host to allocate GPU devices to.
     * @param gpuDevices The list of GPU devices to allocate to the VM.
     */
    void allocateGpuDevicesToVmOnHost(long vmId, long hostId, List<VgpuTypesInfo> gpuDevices);

    /**
     * Discover GPU devices on a host by using the getGPUStatistics command and updating the GPU details for the host.
     *
     * @param cmd The command to discover GPU devices.
     * @return The list of GPU devices.
     */
    ListResponse<GpuDeviceResponse> discoverGpuDevices(DiscoverGpuDevicesCmd cmd);

    /**
     * Check if GPU devices are available for a VM on a host by checking the number of available GPU devices for the
     * vGPU profile.
     *
     * @param host        The host to check GPU devices for.
     * @param vmId        The ID of the VM to check GPU devices for.
     * @param vgpuProfile The vGPU profile to check GPU devices for.
     * @param gpuCount    The number of GPU devices to check for.
     * @return True if GPU devices are available, false otherwise.
     */
    boolean isGPUDeviceAvailable(Host host, Long vmId, VgpuProfile vgpuProfile, int gpuCount);

    /**
     * Describes the GPU devices of the vGPU profile that the VM holds on the host. It allocates and releases nothing:
     * a VM gets its devices only when it starts, through {@link #allocateGpuGroup}.
     *
     * @param vm          The VM to get GPU devices for.
     * @param vgpuProfile The vGPU profile to get GPU devices for.
     * @param gpuCount    The number of GPU devices the VM takes.
     * @return The GPU devices.
     */
    GPUDeviceTO getGPUDevice(VirtualMachine vm, long hostId, VgpuProfile vgpuProfile, int gpuCount);

    /**
     * Gives a starting VM one whole usable GPU group of the given size on the host, in place of any devices it held,
     * under the row locks of the host's GPU devices. A VM that holds exactly that many devices of the profile on the
     * host keeps them, as the start that ends a restart within its running session does.
     *
     * @throws InsufficientServerCapacityException scoped to the host when it has no usable group; nothing is changed
     */
    GpuGroupPick allocateGpuGroup(VirtualMachine vm, long hostId, long vgpuProfileId, int count) throws InsufficientServerCapacityException;

    /**
     * Gets the GPU group details from the GPU devices on a host.
     * This fetches the GPU devices from the host and prepares the GPU group details for the host.
     * The GPU group details are a map of GPU group name (Card's device name) to a map of vGPU profile name to
     * VgpuTypesInfo.
     * The VgpuTypesInfo contains the information about the GPU device.
     *
     * @param hostId The host ID to get GPU group details for.
     * @return The GPU group details.
     */
    HashMap<String, HashMap<String, VgpuTypesInfo>> getGpuGroupDetailsFromGpuDevicesOnHost(long hostId);

    /**
     * This method is used to add the GPU devices to the host when the host is discovered or when the GPU devices are
     * updated.
     *
     * @param host              The host to add the GPU devices to.
     * @param newGpuDevicesInfo The list of GPU devices to add to the host.
     */
    default void addGpuDevicesToHost(Host host, List<VgpuTypesInfo> newGpuDevicesInfo) {
        addGpuDevicesToHost(host, newGpuDevicesInfo, new Date());
    }

    /**
     * @param reportTime a time no later than the moment the host gathered the report; an allocation to a VM whose
     *                   state changed after it is not judged by the report
     */
    void addGpuDevicesToHost(Host host, List<VgpuTypesInfo> newGpuDevicesInfo, Date reportTime);

    boolean deleteGpuDevices(DeleteGpuDeviceCmd deleteGpuDeviceCmd);

    /**
     * Registers the GPU group rule of a host and, for rule partitions, its groups; replaces any earlier registration.
     */
    HostGpuGroupsResponse updateHostGpuGroups(UpdateHostGpuGroupsCmd cmd);

    ListResponse<HostGpuGroupsResponse> listHostGpuGroups(ListHostGpuGroupsCmd cmd);

    /**
     * @return the GPU group rule registered for the host, or null when it has none; a host without a rule offers no
     *         GPU group
     */
    GroupRule getGpuGroupRule(long hostId);

    /**
     * @return the registered groups of a host with rule partitions, each as its GPUs' PCI bus addresses; empty for any
     *         other host, whose groups follow from its rule
     */
    List<Set<String>> getGpuGroups(long hostId);

    /**
     * Chooses one whole usable GPU group of the given size on the host: every GPU of it present, managed, free and of
     * the vGPU profile. The choice follows gpu.allocation.algorithm; callers that allocate the group hold the row locks
     * of the host's GPU devices.
     *
     * @return the group, or empty when the host has none
     */
    Optional<GpuGroupPick> pickGroup(long hostId, long vgpuProfileId, int count);

    /**
     * Orders hosts for best-fit placement, lower first: the free GPUs of the vGPU profile left on the host after its
     * best-fit group is taken, then the free GPUs left in that group's smallest enclosing group.
     *
     * @return the score, or Integer.MAX_VALUE when the host has no usable group of the given size
     */
    int bestFitScore(long hostId, long vgpuProfileId, int count);

    /**
     * Counts the GPU slots of a service offering with a vGPU profile over the Up, Enabled hosts that have a registered
     * GPU group rule and the offering's host tags. A host whose local storage pool has crossed a disable threshold adds
     * no available slot.
     *
     * @param hostId restricts the count to this host; null counts every such host
     */
    SlotCount slotCount(ServiceOffering serviceOffering, Long hostId);

    /**
     * Frees the GPU devices of a VM that is removed or in a state in which it holds none, each in one statement that
     * decides by the VM's committed state, so that a release running late never frees the devices of a later start.
     */
    void releaseGpuDevicesOfStoppedVm(long vmId);

    /**
     * Removes the GPU group registration of a host that is being removed.
     */
    void removeHostGpuGroups(long hostId);
}
