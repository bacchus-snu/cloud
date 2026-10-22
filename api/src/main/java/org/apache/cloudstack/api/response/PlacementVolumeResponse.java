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

public class PlacementVolumeResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "The ID of the volume")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "The name of the volume")
    private String name;

    @SerializedName(ApiConstants.SIZE)
    @Param(description = "The size of the volume in bytes")
    private Long size;

    @SerializedName(ApiConstants.PHYSICAL_SIZE)
    @Param(description = "The bytes the volume uses, which the move copies")
    private Long physicalSize;

    public PlacementVolumeResponse() {
        setObjectName("volume");
    }

    public PlacementVolumeResponse(String id, String name, Long size, Long physicalSize) {
        this();
        this.id = id;
        this.name = name;
        this.size = size;
        this.physicalSize = physicalSize;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getSize() {
        return size;
    }

    public Long getPhysicalSize() {
        return physicalSize;
    }
}
