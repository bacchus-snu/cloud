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
package org.apache.cloudstack.api.command.user.vm;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.PlacementCapacityResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.deploy.PlacementService;

@APICommand(name = "listPlacementCapacity", description = "Shows the largest Instance without a vGPU profile that one usable host could take right now: " +
        "its vCPUs, memory in MiB and local disk in GiB. A usable host takes at least the smallest such Instance now. Each maximum belongs to one " +
        "host and is never a sum, and the three need not fit one host together: checkPlacement answers whether a combination fits. No host is " +
        "named or counted. With serviceofferingid the hosts are those the host allocators start from for that offering's host tags; without it the " +
        "result ignores offering host tags. With virtualmachineid it also shows what is free on the host the Instance runs on, or for a stopped " +
        "Instance on the host of its root volume, and the GPU slots there. It is information for the placement step and never bounds an input: " +
        "inputs are bounded by the remaining quota only.",
        responseObject = PlacementCapacityResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.23.0.100",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListPlacementCapacityCmd extends BaseCmd {

    @Inject
    private PlacementService placementService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true,
            description = "The zone to show the capacity of")
    private Long zoneId;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            description = "An existing Instance, to show what is free on its host")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class,
            description = "The service offering, without a vGPU profile, of the Instance to be created or changed to; its host tags select the hosts")
    private Long serviceOfferingId;

    public Long getZoneId() {
        return zoneId;
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public void execute() {
        PlacementCapacityResponse response = placementService.listPlacementCapacity(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
