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
package org.apache.cloudstack.oidc.api.command;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.oidc.OidcAuthManager;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;

@APICommand(name = "deleteOidcProvider", description = "Deletes an OpenID provider that no user signs in with; disable a provider that still has users",
        responseObject = SuccessResponse.class, entityType = {}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin}, since = "4.23.0.100")
public class DeleteOidcProviderCmd extends BaseCmd {

    @Inject
    OidcAuthManager oidcAuthManager;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = OidcProviderResponse.class, required = true, description = "The provider")
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public void execute() {
        if (!oidcAuthManager.deleteProvider(id)) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to delete the OpenID provider");
        }
        SuccessResponse response = new SuccessResponse(getCommandName());
        setResponseObject(response);
    }
}
