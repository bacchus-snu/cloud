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
import org.apache.cloudstack.api.BaseListAccountResourcesCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.resourcelimit.request.ResourceLimitRequestService;
import org.apache.cloudstack.resourcelimit.request.api.response.ResourceLimitRequestResponse;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;

@APICommand(name = "listResourceLimitRequests", description = "Lists resource limit requests and the grants made of them, newest first",
        responseObject = ResourceLimitRequestResponse.class, entityType = {}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User}, since = "4.23.0.100")
public class ListResourceLimitRequestsCmd extends BaseListAccountResourcesCmd {

    @Inject
    ResourceLimitRequestService resourceLimitRequestService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ResourceLimitRequestResponse.class, description = "The ID of the request")
    private Long id;

    @Parameter(name = ApiConstants.STATE, type = CommandType.STRING, description = "Pending, Approved, Rejected, Cancelled, Expired or Revoked")
    private String state;

    public Long getId() {
        return id;
    }

    public ResourceLimitRequestVO.State getState() {
        if (StringUtils.isBlank(state)) {
            return null;
        }
        ResourceLimitRequestVO.State value = EnumUtils.getEnumIgnoreCase(ResourceLimitRequestVO.State.class, state.trim());
        if (value == null) {
            throw new InvalidParameterValueException(String.format("%s is not a state of a resource limit request", state));
        }
        return value;
    }

    @Override
    public void execute() {
        ListResponse<ResourceLimitRequestResponse> response = resourceLimitRequestService.listRequests(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
