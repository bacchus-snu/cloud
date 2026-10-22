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
package com.cloud.deploy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a placement is asked for: the start of an Instance (optionally with another offering), the attach of a volume
 * to an Instance, an Instance created from a detached root volume, or a new Instance from a template.
 */
public final class PlacementRequest {

    public enum Kind {
        START, ATTACH, DEPLOY_FROM_VOLUME, DEPLOY
    }

    private final Kind kind;
    private final Long vmId;
    private final Long volumeId;
    private final Long templateId;
    private final Long serviceOfferingId;
    private final Long zoneId;
    private final long ownerId;
    private Map<String, String> details = new HashMap<>();
    private Long rootDiskSizeGiB;
    private List<Long> dataDiskSizesGiB = new ArrayList<>();
    private boolean approvalBindsInstance = true;

    private PlacementRequest(Kind kind, Long vmId, Long volumeId, Long templateId, Long serviceOfferingId, Long zoneId, long ownerId) {
        this.kind = kind;
        this.vmId = vmId;
        this.volumeId = volumeId;
        this.templateId = templateId;
        this.serviceOfferingId = serviceOfferingId;
        this.zoneId = zoneId;
        this.ownerId = ownerId;
    }

    /** @param serviceOfferingId the offering the Instance starts with after a change, or null for its own */
    public static PlacementRequest start(long vmId, Long serviceOfferingId, long ownerId) {
        return new PlacementRequest(Kind.START, vmId, null, null, serviceOfferingId, null, ownerId);
    }

    public static PlacementRequest attach(long vmId, long volumeId, long ownerId) {
        return new PlacementRequest(Kind.ATTACH, vmId, volumeId, null, null, null, ownerId);
    }

    public static PlacementRequest deployFromVolume(long volumeId, long serviceOfferingId, long ownerId) {
        return new PlacementRequest(Kind.DEPLOY_FROM_VOLUME, null, volumeId, null, serviceOfferingId, null, ownerId);
    }

    public static PlacementRequest deploy(long templateId, long serviceOfferingId, long zoneId, long ownerId) {
        return new PlacementRequest(Kind.DEPLOY, null, null, templateId, serviceOfferingId, zoneId, ownerId);
    }

    public PlacementRequest withDetails(Map<String, String> details) {
        this.details = details == null ? new HashMap<>() : new HashMap<>(details);
        return this;
    }

    public PlacementRequest withRootDiskSizeGiB(Long rootDiskSizeGiB) {
        this.rootDiskSizeGiB = rootDiskSizeGiB;
        return this;
    }

    public PlacementRequest withDataDiskSizesGiB(List<Long> dataDiskSizesGiB) {
        this.dataDiskSizesGiB = dataDiskSizesGiB == null ? new ArrayList<>() : new ArrayList<>(dataDiskSizesGiB);
        return this;
    }

    /**
     * For the start of an Instance created from a detached volume: its move was approved before the Instance existed,
     * so the approval binds no Instance.
     */
    public PlacementRequest approvedBeforeTheInstanceExisted() {
        this.approvalBindsInstance = false;
        return this;
    }

    /** @return the Instance an approval of this request binds, or null */
    public Long getApprovalInstanceId() {
        return approvalBindsInstance ? vmId : null;
    }

    public Kind getKind() {
        return kind;
    }

    public Long getVmId() {
        return vmId;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public long getOwnerId() {
        return ownerId;
    }

    /** @return the custom offering values, such as cpuNumber and memory */
    public Map<String, String> getDetails() {
        return details;
    }

    public Long getRootDiskSizeGiB() {
        return rootDiskSizeGiB;
    }

    public List<Long> getDataDiskSizesGiB() {
        return dataDiskSizesGiB;
    }
}
