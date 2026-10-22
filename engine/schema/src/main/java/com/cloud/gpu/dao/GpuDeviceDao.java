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
package com.cloud.gpu.dao;

import com.cloud.gpu.GpuDeviceVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GenericDao;
import com.cloud.vm.VirtualMachine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public interface GpuDeviceDao extends GenericDao<GpuDeviceVO, Long> {

    /**
     * The states in which a VM cannot run on a host and so holds no GPU device when GPUs are detached on stop.
     */
    Set<VirtualMachine.State> STATES_WITHOUT_GPUS = Collections.unmodifiableSet(EnumSet.of(VirtualMachine.State.Stopped,
            VirtualMachine.State.Destroyed, VirtualMachine.State.Expunging, VirtualMachine.State.Error));

    /**
     * Frees the device if the VM holds it and is removed, or is in one of {@link #STATES_WITHOUT_GPUS} with no restart
     * within its running session in progress, in one statement that decides by the VM's committed state, so that a
     * release running late never frees a device of a later start. A device in error is given up but stays in error.
     *
     * @return true when the device was freed
     */
    boolean releaseIfVmHoldsNone(long deviceId, long vmId);

    List<GpuDeviceVO> listByIds(List<Long> ids);

    /**
     * Find GPU device by host ID and bus address
     *
     * @param hostId     the host ID
     * @param busAddress the PCI bus address
     * @return GpuDeviceVO
     */
    GpuDeviceVO findByHostIdAndBusAddress(long hostId, String busAddress);

    /**
     * List GPU devices by host ID
     *
     * @param hostId the ID of the host
     * @return a list of GPU devices for the host
     */
    List<GpuDeviceVO> listByHostId(long hostId);

    /**
     * Locks the host's GPU device rows for update until the current transaction ends, so that decisions made from
     * reads of the host's devices after it cannot interleave with another such decision.
     *
     * @return the locked rows
     */
    List<GpuDeviceVO> lockByHostId(long hostId);

    /**
     * List GPU devices by VM ID
     *
     * @param vmId the VM ID
     * @return list of GpuDeviceVO
     */
    List<GpuDeviceVO> listByVmId(long vmId);

    boolean isVgpuProfileInUse(long vgpuProfileId);

    boolean isGpuCardInUse(long cardId);

    List<GpuDeviceVO> listByHostAndVm(Long hostId, long vmId);

    List<GpuDeviceVO> listDevicesForAllocation(Long hostId, Long vgpuProfileId);

    Pair<List<GpuDeviceVO>, Integer> searchAndCountGpuDevices(
            Long id, String keyword, Long hostId, Long vmId, Long gpuCardId, Long vgpuProfileId,
            Long startIndex, Long pageSize);

    List<Long> getDistinctGpuCardIds();

    List<Long> getDistinctVgpuProfileIds();

    List<GpuDeviceVO> listByParentGpuDeviceId(Long parentGpuDeviceId);
}
