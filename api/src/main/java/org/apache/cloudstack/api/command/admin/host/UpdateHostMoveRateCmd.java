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
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.SuccessResponse;

import com.cloud.deploy.PlacementService;
import com.cloud.user.Account;

@APICommand(name = "updateHostMoveRate", description = "Sets the rate at which a host copies volumes to or from another host, used to estimate " +
        "how long a volume move takes; a move is estimated at the slower of its two hosts, and a host without a rate counts at " +
        "volume.move.estimate.mbps.", responseObject = SuccessResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.23.0.100", authorized = {RoleType.Admin})
public class UpdateHostMoveRateCmd extends BaseCmd {

    @Inject
    private PlacementService placementService;

    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "The ID of the host")
    private Long hostId;

    @Parameter(name = ApiConstants.MBPS, type = CommandType.LONG, required = true,
            description = "The rate in megabits per second; 0 removes the host's rate")
    private Long mbps;

    public Long getHostId() {
        return hostId;
    }

    public Long getMbps() {
        return mbps;
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
        placementService.updateHostMoveRate(this);
        setResponseObject(new SuccessResponse(getCommandName()));
    }
}
