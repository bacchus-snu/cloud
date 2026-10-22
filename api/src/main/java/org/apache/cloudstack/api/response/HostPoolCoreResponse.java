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

public class HostPoolCoreResponse extends BaseResponse {

    @SerializedName(ApiConstants.HOST_ID)
    @Param(description = "The ID of the host")
    private String hostId;

    @SerializedName(ApiConstants.CORE)
    @Param(description = "The core, named by its lowest thread id")
    private Integer core;

    @SerializedName(ApiConstants.NODE)
    @Param(description = "The NUMA node of the core")
    private Integer node;

    @SerializedName(ApiConstants.THREADS)
    @Param(description = "The thread ids of the core, ascending")
    private List<Integer> threads;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "The ID of the Instance the core is assigned to; absent when the core is free")
    private String virtualMachineId;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_NAME)
    @Param(description = "The name of the Instance the core is assigned to")
    private String virtualMachineName;

    @SerializedName(ApiConstants.INSTANCE_NAME)
    @Param(description = "The internal name of the Instance the core is assigned to")
    private String instanceName;

    public HostPoolCoreResponse() {
        setObjectName("hostpoolcore");
    }

    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public Integer getCore() {
        return core;
    }

    public void setCore(Integer core) {
        this.core = core;
    }

    public Integer getNode() {
        return node;
    }

    public void setNode(Integer node) {
        this.node = node;
    }

    public List<Integer> getThreads() {
        return threads;
    }

    public void setThreads(List<Integer> threads) {
        this.threads = threads;
    }

    public String getVirtualMachineId() {
        return virtualMachineId;
    }

    public void setVirtualMachineId(String virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public String getVirtualMachineName() {
        return virtualMachineName;
    }

    public void setVirtualMachineName(String virtualMachineName) {
        this.virtualMachineName = virtualMachineName;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }
}
