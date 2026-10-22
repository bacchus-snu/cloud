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
package org.apache.cloudstack.api.command.admin.systemvm;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SystemVmCertificateResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import com.cloud.user.Account;

@APICommand(name = "listSystemVmCertificates", description = "Lists, for each running console proxy and secondary storage VM, the server certificate it "
        + "serves and the stored custom certificate it is configured to serve, by fingerprint. Admin only, as the response names system VMs.",
        responseObject = SystemVmCertificateResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.23.0.100", authorized = {RoleType.Admin})
public class ListSystemVmCertificatesCmd extends BaseCmd {

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, description = "The ID of the zone of the system VMs")
    private Long zoneId;

    @Parameter(name = ApiConstants.SYSTEM_VM_TYPE, type = CommandType.STRING,
            description = "The system VM type. Possible types are \"consoleproxy\" and \"secondarystoragevm\".")
    private String systemVmType;

    public Long getZoneId() {
        return zoneId;
    }

    public String getSystemVmType() {
        return systemVmType;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        ListResponse<SystemVmCertificateResponse> response = _mgr.listSystemVmCertificates(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
