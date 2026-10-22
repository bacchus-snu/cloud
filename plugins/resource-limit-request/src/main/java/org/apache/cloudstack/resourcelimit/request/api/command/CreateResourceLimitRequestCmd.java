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
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcelimit.request.ResourceLimitRequestService;
import org.apache.cloudstack.resourcelimit.request.api.response.ResourceLimitRequestResponse;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;

@APICommand(name = "createResourceLimitRequest", description = "Asks for resource limits until an expiry date; the effective limit of each category is the "
        + "largest of the Account defaults and the Account's approved, unexpired grants", responseObject = ResourceLimitRequestResponse.class, entityType = {},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User}, since = "4.23.0.100")
public class CreateResourceLimitRequestCmd extends BaseCmd {

    @Inject
    ResourceLimitRequestService resourceLimitRequestService;

    @Parameter(name = ResourceLimitRequestService.CPU, type = CommandType.LONG, description = "vCPUs of Instances without a GPU")
    private Long cpu;

    @Parameter(name = ResourceLimitRequestService.MEMORY, type = CommandType.LONG, description = "Memory of Instances without a GPU, in MiB")
    private Long memory;

    @Parameter(name = ResourceLimitRequestService.STORAGE, type = CommandType.LONG, description = "Storage of volumes and snapshots together, in GiB")
    private Long storage;

    @Parameter(name = ResourceLimitRequestService.GPU, type = CommandType.MAP,
            description = "GPUs per GPU type, as gpu[0].type=<GPU type tag>&gpu[0].count=<GPUs>; each GPU comes with its type's vCPUs and memory")
    private Map<String, Map<String, String>> gpu;

    @Parameter(name = ResourceLimitRequestService.EXPIRY, type = CommandType.STRING,
            description = "The last day of the grant, as yyyy-MM-dd; it ends at 23:59:59 in the zone of resource.request.expiry.timezone")
    private String expiry;

    @Parameter(name = ResourceLimitRequestService.REASON, type = CommandType.STRING, length = 1024, description = "Why the Account asks")
    private String reason;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "The Account to ask for; must be used with domainid")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "The domain of the Account")
    private Long domainId;

    public Long getCpu() {
        return cpu;
    }

    public Long getMemory() {
        return memory;
    }

    public Long getStorage() {
        return storage;
    }

    /** @return the GPUs per GPU type tag, or null when none were given */
    public Map<String, Long> getGpu() {
        return ResourceLimitRequestService.gpuCounts(gpu);
    }

    public String getExpiry() {
        return expiry;
    }

    public String getReason() {
        return reason;
    }

    public String getAccountName() {
        return accountName;
    }

    public Long getDomainId() {
        return domainId;
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = _accountService.finalizeAccountId(accountName, domainId, null, true);
        return accountId == null ? CallContext.current().getCallingAccount().getId() : accountId;
    }

    @Override
    public void execute() {
        ResourceLimitRequestVO request = resourceLimitRequestService.createRequest(this);
        ResourceLimitRequestResponse response = resourceLimitRequestService.createResponse(request);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
