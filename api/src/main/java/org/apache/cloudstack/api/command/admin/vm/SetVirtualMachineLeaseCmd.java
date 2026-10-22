/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cloudstack.api.command.admin.vm;

import java.util.Date;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.vm.lease.VMLeaseManager;

import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;

@APICommand(name = VMLeaseManager.SET_LEASE_API, description = "Sets when the lease of a running Instance ends. It holds for the running " +
        "session; the next start gives the Instance its service offering's lease again.", responseObject = UserVmResponse.class,
        responseView = ResponseView.Full, entityType = {VirtualMachine.class}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.23.0.100", authorized = {RoleType.Admin})
public class SetVirtualMachineLeaseCmd extends BaseCmd implements AdminCmd {

    @Inject
    private VMLeaseManager vmLeaseManager;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserVmResponse.class, required = true,
            description = "The ID of the Instance")
    private Long id;

    @Parameter(name = ApiConstants.INSTANCE_LEASE_EXPIRY, type = CommandType.DATE, required = true,
            description = "When the lease ends, as yyyy-MM-dd'T'HH:mm:ssZ (for example 2026-10-22T09:00:00+0000)")
    private Date expiry;

    public Long getId() {
        return id;
    }

    public Date getExpiry() {
        return expiry;
    }

    @Override
    public void execute() {
        UserVm vm = vmLeaseManager.setLease(getId(), getExpiry());
        if (vm == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to set the lease of the Instance");
        }
        UserVmResponse response = _responseGenerator.createUserVmResponse(getResponseView(), "virtualmachine", vm).get(0);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        UserVm vm = _entityMgr.findById(UserVm.class, getId());
        return vm != null ? vm.getAccountId() : Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public Long getApiResourceId() {
        return getId();
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.VirtualMachine;
    }
}
