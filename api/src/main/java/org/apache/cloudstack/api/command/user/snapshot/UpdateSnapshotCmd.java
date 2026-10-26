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
package org.apache.cloudstack.api.command.user.snapshot;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.SnapshotResponse;

import com.cloud.storage.Snapshot;
import com.cloud.user.Account;

@APICommand(name = "updateSnapshot", description = "Renames a Snapshot. The name has 1 to 64 characters, each a letter, a digit, '.', '_' or '-', and " +
        "need not be unique.", responseObject = SnapshotResponse.class, entityType = {Snapshot.class}, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0.100", authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateSnapshotCmd extends BaseCmd implements UserCmd {

    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = SnapshotResponse.class, required = true,
            description = "The ID of the Snapshot")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "The new name of the Snapshot")
    private String name;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public long getEntityOwnerId() {
        Snapshot snapshot = _entityMgr.findById(Snapshot.class, getId());
        return snapshot != null ? snapshot.getAccountId() : Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Snapshot;
    }

    @Override
    public Long getApiResourceId() {
        return getId();
    }

    @Override
    public void execute() {
        Snapshot snapshot = _snapshotService.updateSnapshot(this);
        if (snapshot == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update the Snapshot");
        }
        SnapshotResponse response = _responseGenerator.createSnapshotResponse(snapshot);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
