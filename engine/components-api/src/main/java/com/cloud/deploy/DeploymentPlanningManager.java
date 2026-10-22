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
package com.cloud.deploy;

import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.host.Host;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.StoragePool;
import com.cloud.user.Account;
import com.cloud.vm.DiskProfile;
import com.cloud.utils.component.Manager;
import com.cloud.vm.VirtualMachineProfile;
import org.apache.cloudstack.framework.config.ConfigKey;

import java.util.List;
import java.util.Map;

public interface DeploymentPlanningManager extends Manager {


    static final ConfigKey<Boolean> allowRouterOnDisabledResource = new ConfigKey<Boolean>("Advanced", Boolean.class, "allow.router.on.disabled.resources", "false",
            "Allow deploying VR in disabled Zones, Pods, and Clusters", true);

    static final ConfigKey<Boolean> allowAdminVmOnDisabledResource = new ConfigKey<Boolean>("Advanced", Boolean.class, "allow.admin.vm.on.disabled.resources", "false",
            "Allow deploying VMs owned by the admin account in disabled Clusters, Pods, and Zones", true);

    /**
     * Manages vm deployment stages: First Process Affinity/Anti-affinity - Call
     * the chain of AffinityGroupProcessor adapters to set deploymentplan scope
     * and exclude list Secondly, Call DeploymentPlanner - to use heuristics to
     * find the best spot to place the vm/volume. Planner will drill down to the
     * write set of clusters to look for placement based on various heuristics.
     * Lastly, Call Allocators - Given a cluster, allocators matches the
     * requirements to capabilities of the physical resource (host, storage
     * pool).
     *
     * @throws AffinityConflictException
     *
     *
     *
     */
    DeployDestination planDeployment(VirtualMachineProfile vmProfile, DeploymentPlan plan,
            ExcludeList avoids, DeploymentPlanner planner) throws InsufficientServerCapacityException, AffinityConflictException;

    String finalizeReservation(DeployDestination plannedDestination,
            VirtualMachineProfile vmProfile, DeploymentPlan plan, ExcludeList avoids, DeploymentPlanner planner)
            throws InsufficientServerCapacityException, AffinityConflictException;

    void cleanupVMReservations();

    DeploymentPlanner getDeploymentPlannerByName(String plannerName);

    void checkForNonDedicatedResources(VirtualMachineProfile vmProfile, DataCenter dc, ExcludeList avoids);

    void reorderHostsByPriority(Map<Long, Integer> priorities, List<Host> hosts);

    /**
     * The hosts on which the VM's compute is admitted now, anywhere in the plan's scope, in the order the host
     * allocators take them, after the exclusions planning applies to the VM (dedication, disabled resources, other
     * architectures, affinity groups). It reads only: nothing is reserved and the VM's volumes are not considered.
     */
    List<Host> listHostsAdmittingVm(VirtualMachineProfile vmProfile, DataCenterDeployment plan) throws AffinityConflictException;

    /**
     * The hosts of the zone that can take some Instance without a vGPU profile owned by the account, whatever its size
     * or Template: the hosts the host allocators start from for an Instance with the offering's host tag (up, enabled,
     * and not set aside for HA unless the offering is tagged), after the exclusions planning applies to it (dedication,
     * disabled resources), that report compute capacity and have not reached their guest limit. Without an offering the
     * result ignores offering host tags and is the untagged selection. It reads only.
     */
    List<Host> listHostsUsableByAccount(Account owner, long zoneId, ServiceOffering offering);

    /**
     * The storage pools the storage allocators accept for a disk under the plan, in their order, as planning would
     * choose among them; it reserves nothing.
     */
    List<StoragePool> listPoolsForDisk(DiskProfile disk, VirtualMachineProfile vmProfile, DeploymentPlan plan);
}
