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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostGpuGroupsResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;

@APICommand(name = "listHostGpuGroups", description = "Lists the registered GPU group rules of hosts and, for rule partitions, their groups",
        responseObject = HostGpuGroupsResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.23.0.100", authorized = {RoleType.Admin})
public class ListHostGpuGroupsCmd extends BaseListCmd {

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class,
            description = "The ID of the host; all hosts with a registration when absent")
    private Long hostId;

    public Long getHostId() {
        return hostId;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Host;
    }

    @Override
    public void execute() {
        ListResponse<HostGpuGroupsResponse> response = gpuService.listHostGpuGroups(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
