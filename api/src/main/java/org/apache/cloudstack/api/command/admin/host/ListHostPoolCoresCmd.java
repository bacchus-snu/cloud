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
package org.apache.cloudstack.api.command.admin.host;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostPoolCoreResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.capacity.HostPoolCoreService;

@APICommand(name = "listHostPoolCores", description = "Lists the registered pool cores of a host, each with the Instance it is assigned to",
        responseObject = HostPoolCoreResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.23.0.100", authorized = {RoleType.Admin})
public class ListHostPoolCoresCmd extends BaseListCmd {

    @Inject
    private HostPoolCoreService hostPoolCoreService;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "The ID of the host")
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
        ListResponse<HostPoolCoreResponse> response = hostPoolCoreService.listHostPoolCores(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
