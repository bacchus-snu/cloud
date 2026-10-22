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
package com.cloud.capacity.dao;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.cloud.capacity.HostCoreAssignmentVO;
import com.cloud.utils.db.GenericDao;
import com.cloud.vm.VirtualMachine;

public interface HostCoreAssignmentDao extends GenericDao<HostCoreAssignmentVO, Long> {

    /**
     * The states in which a VM cannot run on a host, so that it holds no pool cores.
     */
    Set<VirtualMachine.State> STATES_WITHOUT_CORES = Collections.unmodifiableSet(EnumSet.of(VirtualMachine.State.Stopped,
            VirtualMachine.State.Destroyed, VirtualMachine.State.Expunging, VirtualMachine.State.Error));

    List<HostCoreAssignmentVO> listByHostId(long hostId);

    int removeByHostId(long hostId);

    /**
     * Replaces every assignment of the VM by the given cores of the host, in one transaction.
     * @throws javax.persistence.EntityExistsException when a core is already held, and nothing is changed
     */
    void assign(long hostId, long vmId, List<Integer> cores);

    /**
     * Removes the VM's assignments if, when the statement runs, the VM is removed or in one of
     * {@link #STATES_WITHOUT_CORES}, so that a release running late never removes the cores of a later start.
     * @return the number of cores released
     */
    int releaseIfNotRunning(long vmId);

    /**
     * Removes the host's assignments whose VM is removed, on another host or on none, or in one of
     * {@link #STATES_WITHOUT_CORES}.
     * @return the number of assignments removed
     */
    int removeStale(long hostId);
}
