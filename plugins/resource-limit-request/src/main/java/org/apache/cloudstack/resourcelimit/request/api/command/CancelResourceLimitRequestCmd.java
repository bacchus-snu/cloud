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

@APICommand(name = "cancelResourceLimitRequest", description = "Withdraws a pending resource limit request, or gives up a grant, after which the "
        + "Account's limits are written again", responseObject = ResourceLimitRequestResponse.class, entityType = {}, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User}, since = "4.23.0.100")
public class CancelResourceLimitRequestCmd extends BaseCmd {

    @Inject
    ResourceLimitRequestService resourceLimitRequestService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ResourceLimitRequestResponse.class, required = true, description = "The ID of the request")
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public long getEntityOwnerId() {
        ResourceLimitRequestVO request = _entityMgr.findById(ResourceLimitRequestVO.class, id);
        return request == null ? Account.ACCOUNT_ID_SYSTEM : request.getAccountId();
    }

    @Override
    public void execute() {
        ResourceLimitRequestVO request = resourceLimitRequestService.cancelRequest(id);
        ResourceLimitRequestResponse response = resourceLimitRequestService.createResponse(request);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
