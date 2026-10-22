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

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.PlacementResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.deploy.PlacementService;

@APICommand(name = "checkPlacement", description = "Tells whether an Instance can start, take a volume, or be created right now: on the host that " +
        "holds its volumes, on some host, only after its local volumes are moved to another host (with the volumes, their sizes, the estimated " +
        "time and an approval for exactly that move), or not now (with the resource that is short). It reads only; the operation itself " +
        "decides. Takes virtualmachineid (start, scale, offering change); virtualmachineid and volumeid (attach); volumeid and " +
        "serviceofferingid (an Instance from a detached root volume); or templateid and serviceofferingid (a new Instance).",
        responseObject = PlacementResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.23.0.100",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CheckPlacementCmd extends BaseCmd {

    @Inject
    private PlacementService placementService;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            description = "The Instance to start, or to attach the volume to")
    private Long virtualMachineId;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class,
            description = "The detached volume to attach, or the detached root volume to create an Instance from")
    private Long volumeId;

    @Parameter(name = ApiConstants.TEMPLATE_ID, type = CommandType.UUID, entityType = TemplateResponse.class,
            description = "The Template of a new Instance")
    private Long templateId;

    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class,
            description = "The service offering of a new Instance, or the offering an existing Instance changes to")
    private Long serviceOfferingId;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "The values of a custom service offering: cpuNumber and memory (MiB)")
    private Map details;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "The zone of a new Instance")
    private Long zoneId;

    @Parameter(name = ApiConstants.ROOT_DISK_SIZE, type = CommandType.LONG,
            description = "The root disk size of a new Instance, in GiB")
    private Long rootDiskSize;

    @Parameter(name = ApiConstants.DATA_DISK_SIZES, type = CommandType.LIST, collectionType = CommandType.LONG,
            description = "The sizes of the data disks a new Instance is created with, in GiB")
    private List<Long> dataDiskSizes;

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Map<String, String> getDetails() {
        return convertDetailsToMap(details);
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getRootDiskSize() {
        return rootDiskSize;
    }

    public List<Long> getDataDiskSizes() {
        return dataDiskSizes;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public void execute() {
        PlacementResponse response = placementService.checkPlacement(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
