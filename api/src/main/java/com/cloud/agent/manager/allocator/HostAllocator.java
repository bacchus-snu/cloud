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
package com.cloud.agent.manager.allocator;

import java.util.List;

import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.utils.component.Adapter;
import com.cloud.vm.VirtualMachineProfile;

public interface HostAllocator extends Adapter {

    /**
    * Determines which physical hosts are suitable to
    * allocate the guest virtual machines on
    *
    * @param VirtualMachineProfile vmProfile
    * @param DeploymentPlan plan
    * @param GuestType type
    * @param ExcludeList avoid
    * @param int returnUpTo (use -1 to return all possible hosts)
    * @return List<Host> List of hosts that are suitable for VM allocation
    **/

    public List<Host> allocateTo(VirtualMachineProfile vmProfile, DeploymentPlan plan, Type type, ExcludeList avoid, int returnUpTo);

    /**
     * Determines which physical hosts are suitable to allocate the guest
     * virtual machines on
     *
     * Allocators must set any other hosts not considered for allocation in the
     * ExcludeList avoid. Thus the avoid set and the list of hosts suitable,
     * together must cover the entire host set in the cluster.
     *
     *
     * @param VirtualMachineProfile
     *            vmProfile
     * @param DeploymentPlan
     *            plan
     * @param GuestType
     *            type
     * @param ExcludeList
     *            avoid
     * @param List
     *            <HostVO> hosts
     * @param int returnUpTo (use -1 to return all possible hosts)
     * @param boolean considerReservedCapacity (default should be true, set to
     *        false if host capacity calculation should not look at reserved
     *        capacity)
     * @return List<Host> List of hosts that are suitable for VM allocation
     **/
    public List<Host> allocateTo(VirtualMachineProfile vmProfile, DeploymentPlan plan, Type type, ExcludeList avoid, List<? extends Host> hosts, int returnUpTo,
        boolean considerReservedCapacity);

    /**
     * The hosts in the zone that the allocator starts from, before the capacity of any host is checked, for an Instance
     * whose service offering has the given host tag (null for none) and that HA does not restart with a tag of its own:
     * the hosts with that tag for a tagged offering, otherwise the hosts not set aside for HA. It takes no Template into
     * account, so the Template's host tag and the guest OS rules are not applied.
     *
     * @return null when the allocator does not select hosts this way
     */
    default List<? extends Host> listHostsForHostTag(Type type, long dcId, String offeringHostTag) {
        return null;
    }

    public static int RETURN_UPTO_ALL = -1;

}
