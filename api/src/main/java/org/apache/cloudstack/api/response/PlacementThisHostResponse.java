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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class PlacementThisHostResponse extends BaseResponse {

    @SerializedName(ApiConstants.FREE_VCPU)
    @Param(description = "The vCPUs of an Instance without a vGPU profile that the host's free cores take")
    private Long freeVcpu;

    @SerializedName(ApiConstants.FREE_MEMORY)
    @Param(description = "The memory in MiB that is free on the host")
    private Long freeMemory;

    @SerializedName(ApiConstants.FREE_DISK)
    @Param(description = "The local disk in GiB that the host's local storage can still take")
    private Long freeDisk;

    @SerializedName(ApiConstants.GPU_SLOTS_AVAILABLE)
    @Param(description = "For an Instance with a vGPU profile, the slots of its service offering that are free on the host")
    private Integer gpuSlotsAvailable;

    public PlacementThisHostResponse() {
        setObjectName(ApiConstants.THIS_HOST);
    }

    public PlacementThisHostResponse(long freeVcpu, long freeMemory, long freeDisk, Integer gpuSlotsAvailable) {
        this();
        this.freeVcpu = freeVcpu;
        this.freeMemory = freeMemory;
        this.freeDisk = freeDisk;
        this.gpuSlotsAvailable = gpuSlotsAvailable;
    }

    public Long getFreeVcpu() {
        return freeVcpu;
    }

    public Long getFreeMemory() {
        return freeMemory;
    }

    public Long getFreeDisk() {
        return freeDisk;
    }

    public Integer getGpuSlotsAvailable() {
        return gpuSlotsAvailable;
    }
}
