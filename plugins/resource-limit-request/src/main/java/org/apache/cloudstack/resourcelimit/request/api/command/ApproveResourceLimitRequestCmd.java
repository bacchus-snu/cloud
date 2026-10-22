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

package org.apache.cloudstack.resourcelimit.request.api.command;

import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.resourcelimit.request.ResourceLimitRequestService;
import org.apache.cloudstack.resourcelimit.request.api.response.ResourceLimitRequestResponse;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;

import com.cloud.user.Account;

@APICommand(name = "approveResourceLimitRequest", description = "Approves a pending resource limit request, with the requested values and expiry or "
        + "adjusted ones, and writes the Account's limits", responseObject = ResourceLimitRequestResponse.class, entityType = {},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin}, since = "4.23.0.100")
public class ApproveResourceLimitRequestCmd extends BaseCmd {

    @Inject
    ResourceLimitRequestService resourceLimitRequestService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ResourceLimitRequestResponse.class, required = true, description = "The ID of the request")
    private Long id;

    @Parameter(name = ResourceLimitRequestService.CPU, type = CommandType.LONG, description = "Granted vCPUs of Instances without a GPU; the requested ones when absent")
    private Long cpu;

    @Parameter(name = ResourceLimitRequestService.MEMORY, type = CommandType.LONG,
            description = "Granted memory of Instances without a GPU, in MiB; the requested memory when absent")
    private Long memory;

    @Parameter(name = ResourceLimitRequestService.STORAGE, type = CommandType.LONG,
            description = "Granted storage of volumes and snapshots together, in GiB; the requested storage when absent")
    private Long storage;

    @Parameter(name = ResourceLimitRequestService.GPU, type = CommandType.MAP,
            description = "Granted GPUs per GPU type, as gpu[0].type=<GPU type tag>&gpu[0].count=<GPUs>, in place of all the requested ones; "
                    + "the requested GPUs when absent")
    private Map<String, Map<String, String>> gpu;

    @Parameter(name = ResourceLimitRequestService.EXPIRY, type = CommandType.STRING,
            description = "The last day of the grant, as yyyy-MM-dd; the requested one when absent")
    private String expiry;

    @Parameter(name = ResourceLimitRequestService.COMMENT, type = CommandType.STRING, length = 1024, description = "A comment the Account is told")
    private String comment;

    public Long getId() {
        return id;
    }

    public Long getCpu() {
        return cpu;
    }

    public Long getMemory() {
        return memory;
    }

    public Long getStorage() {
        return storage;
    }

    /** @return the granted GPUs per GPU type tag, or null to grant the requested ones */
    public Map<String, Long> getGpu() {
        return ResourceLimitRequestService.gpuCounts(gpu);
    }

    public String getExpiry() {
        return expiry;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public long getEntityOwnerId() {
        ResourceLimitRequestVO request = _entityMgr.findById(ResourceLimitRequestVO.class, id);
        return request == null ? Account.ACCOUNT_ID_SYSTEM : request.getAccountId();
    }

    @Override
    public void execute() {
        ResourceLimitRequestVO request = resourceLimitRequestService.approveRequest(this);
        ResourceLimitRequestResponse response = resourceLimitRequestService.createResponse(request);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
