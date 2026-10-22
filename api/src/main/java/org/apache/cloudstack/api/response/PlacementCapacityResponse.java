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

public class PlacementCapacityResponse extends BaseResponse {

    @SerializedName(ApiConstants.MAX_VCPU)
    @Param(description = "The most vCPUs an Instance without a vGPU profile can have on one usable host now, a multiple of vm.pool.vcpu.step")
    private Long maxVcpu;

    @SerializedName(ApiConstants.MAX_MEMORY)
    @Param(description = "The most memory in MiB such an Instance can have on one usable host now")
    private Long maxMemory;

    @SerializedName(ApiConstants.MAX_DISK)
    @Param(description = "The most local disk in GiB such an Instance can have on one usable host now")
    private Long maxDisk;

    @SerializedName(ApiConstants.THIS_HOST)
    @Param(description = "What is free on the host of the given Instance", responseObject = PlacementThisHostResponse.class)
    private PlacementThisHostResponse thisHost;

    public PlacementCapacityResponse() {
        setObjectName("placementcapacity");
    }

    public PlacementCapacityResponse(long maxVcpu, long maxMemory, long maxDisk, PlacementThisHostResponse thisHost) {
        this();
        this.maxVcpu = maxVcpu;
        this.maxMemory = maxMemory;
        this.maxDisk = maxDisk;
        this.thisHost = thisHost;
    }

    public Long getMaxVcpu() {
        return maxVcpu;
    }

    public Long getMaxMemory() {
        return maxMemory;
    }

    public Long getMaxDisk() {
        return maxDisk;
    }

    public PlacementThisHostResponse getThisHost() {
        return thisHost;
    }
}
