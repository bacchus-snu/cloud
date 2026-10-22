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

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.deploy.PlacementVerdict;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class PlacementResponse extends BaseResponse {

    @SerializedName(ApiConstants.VERDICT)
    @Param(description = "here: the host that holds the Instance's volumes has room; anyhost: a host has room as it is; move: a host has room once " +
            "the listed volumes are moved to it; none: no host has room now")
    private String verdict;

    @SerializedName(ApiConstants.VOLUMES)
    @Param(description = "The volumes a move copies", responseObject = PlacementVolumeResponse.class)
    private List<PlacementVolumeResponse> volumes = new ArrayList<>();

    @SerializedName(ApiConstants.BYTES_TO_MOVE)
    @Param(description = "The bytes a move copies")
    private Long bytesToMove;

    @SerializedName(ApiConstants.ESTIMATED_SECONDS)
    @Param(description = "The estimated duration of the move in seconds")
    private Long estimatedSeconds;

    @SerializedName(ApiConstants.PREVIOUS_COPY_KEPT)
    @Param(description = "Whether a move keeps the copy at the previous location as a volume of the owner, counted in its storage quota")
    private Boolean previousCopyKept;

    @SerializedName(ApiConstants.REASON)
    @Param(description = "For none, the resource that is short")
    private String reason;

    @SerializedName(ApiConstants.PLACEMENT_TOKEN)
    @Param(description = "For move, the approval of exactly that move, valid for placement.token.expiry.seconds; pass it with migratevolumes " +
            "or migratevolume")
    private String placementToken;

    public PlacementResponse() {
        setObjectName("placement");
    }

    public PlacementResponse(PlacementVerdict verdict) {
        this();
        this.verdict = verdict.getVerdict().name();
        for (PlacementVerdict.MovedVolume volume : verdict.getVolumes()) {
            volumes.add(new PlacementVolumeResponse(volume.getUuid(), volume.getName(), volume.getSize(), volume.getPhysicalSize()));
        }
        this.bytesToMove = verdict.getBytesToMove();
        this.estimatedSeconds = verdict.getEstimatedSeconds();
        this.previousCopyKept = verdict.isPreviousCopyKept();
        this.reason = verdict.getReason();
        this.placementToken = verdict.getToken();
    }

    public String getVerdict() {
        return verdict;
    }

    public List<PlacementVolumeResponse> getVolumes() {
        return volumes;
    }

    public Long getBytesToMove() {
        return bytesToMove;
    }

    public Long getEstimatedSeconds() {
        return estimatedSeconds;
    }

    public Boolean getPreviousCopyKept() {
        return previousCopyKept;
    }

    public String getReason() {
        return reason;
    }

    public String getPlacementToken() {
        return placementToken;
    }
}
