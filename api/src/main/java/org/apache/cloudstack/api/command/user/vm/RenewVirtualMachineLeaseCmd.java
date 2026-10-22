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

package org.apache.cloudstack.api.command.user.vm;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.vm.lease.VMLeaseManager;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;

@APICommand(name = VMLeaseManager.RENEW_LEASE_API, description = "Renews the lease of a running Instance: it then ends the leasehours of the " +
        "Instance's service offering from now. Refused when renewal is turned off (instance.lease.renewal.enabled) and while the owner " +
        "is over its quota for the Instance.", responseObject = UserVmResponse.class, responseView = ResponseView.Restricted,
        entityType = {VirtualMachine.class}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, since = "4.23.0.100",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class RenewVirtualMachineLeaseCmd extends BaseCmd implements UserCmd {

    @Inject
    private VMLeaseManager vmLeaseManager;

    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserVmResponse.class, required = true,
            description = "The ID of the Instance")
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public void execute() throws ResourceAllocationException {
        UserVm vm = vmLeaseManager.renewLease(getId());
        if (vm == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to renew the lease of the Instance");
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
