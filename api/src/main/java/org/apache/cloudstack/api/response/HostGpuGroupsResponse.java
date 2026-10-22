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
package org.apache.cloudstack.api.response;

import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class HostGpuGroupsResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOST_ID)
    @Param(description = "The ID of the host")
    private String hostId;

    @SerializedName(ApiConstants.HOST_NAME)
    @Param(description = "The name of the host")
    private String hostName;

    @SerializedName(ApiConstants.RULE)
    @Param(description = "The GPU group rule of the host: partitions, any or single")
    private String rule;

    @SerializedName(ApiConstants.GROUPS)
    @Param(description = "For rule partitions, the registered GPU groups, each as the PCI bus addresses (dddd:bb:ss.f) of its GPU positions in ascending order")
    private List<List<String>> groups;

    public HostGpuGroupsResponse() {
        setObjectName("hostgpugroups");
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public List<List<String>> getGroups() {
        return groups;
    }

    public void setGroups(List<List<String>> groups) {
        this.groups = groups;
    }
}
