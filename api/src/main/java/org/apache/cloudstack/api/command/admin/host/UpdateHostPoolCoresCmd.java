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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.HostPoolCoreResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.capacity.HostPoolCoreService;
import com.cloud.user.Account;

@APICommand(name = "updateHostPoolCores", description = "Registers the pool cores of a KVM host: the whole cores on which " +
        "Instances without a vGPU profile run, each core dedicated to one Instance at a time. A host without pool cores " +
        "runs only Instances with a vGPU profile.", responseObject = HostPoolCoreResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0.100", authorized = {RoleType.Admin})
public class UpdateHostPoolCoresCmd extends BaseCmd {

    @Inject
    private HostPoolCoreService hostPoolCoreService;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "The ID of the host")
    private Long hostId;

    @Parameter(name = ApiConstants.CORES, type = CommandType.STRING, required = true, length = 65535,
            description = "The pool cores as a JSON array of {\"node\": <NUMA node>, \"threads\": [<thread id>, <sibling thread id>]}, " +
                    "the two thread ids ascending; an empty array removes the registration")
    private String cores;

    public Long getHostId() {
        return hostId;
    }

    public String getCores() {
        return cores;
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
        ListResponse<HostPoolCoreResponse> response = hostPoolCoreService.updateHostPoolCores(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
