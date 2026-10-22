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
package org.apache.cloudstack.api.command.admin.gpu;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostGpuGroupsResponse;
import org.apache.cloudstack.api.response.HostResponse;

import com.cloud.user.Account;

@APICommand(name = "updateHostGpuGroups", description = "Registers how the GPUs of a host form the groups that Instances with a vGPU profile " +
        "take whole. A host without a registration offers no GPU group.", responseObject = HostGpuGroupsResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.23.0.100", authorized = {RoleType.Admin})
public class UpdateHostGpuGroupsCmd extends BaseCmd {

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "The ID of the host")
    private Long hostId;

    @Parameter(name = ApiConstants.GROUPS, type = CommandType.STRING, required = true, length = 65535,
            description = "The GPU group rule as JSON: {\"rule\":\"partitions\",\"groups\":[[\"<PCI bus address>\",…],…]} with laminar " +
                    "groups of 1, 2, 4 or 8 GPU positions of the host, each of one card, addresses as 0000:07:00.0 or 07:00.0; a position " +
                    "without a GPU device yet makes its groups unusable until the device appears; {\"rule\":\"any\"}, any GPUs of the " +
                    "host spread evenly over the NUMA nodes they use; or {\"rule\":\"single\"}, one GPU at a time")
    private String groups;

    public Long getHostId() {
        return hostId;
    }

    public String getGroups() {
        return groups;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Host;
    }

    @Override
    public Long getApiResourceId() {
        return hostId;
    }

    @Override
    public void execute() {
        HostGpuGroupsResponse response = gpuService.updateHostGpuGroups(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
