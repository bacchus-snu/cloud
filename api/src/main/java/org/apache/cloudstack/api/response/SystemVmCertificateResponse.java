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

public class SystemVmCertificateResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "The ID of the system VM")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "The name of the system VM")
    private String name;

    @SerializedName(ApiConstants.SYSTEM_VM_TYPE)
    @Param(description = "The system VM type: consoleproxy or secondarystoragevm")
    private String systemVmType;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "The ID of the zone of the system VM")
    private String zoneId;

    @SerializedName("agentstate")
    @Param(description = "The state of the system VM's agent. The fingerprint is the one the agent reported last, which is current while the agent is Up")
    private String agentState;

    @SerializedName(ApiConstants.CERTIFICATE_FINGERPRINT)
    @Param(description = "The SHA-256 fingerprint, as colon-separated upper-case hex, of the server certificate the system VM reported serving when its "
            + "agent connected or after it last loaded a certificate; absent when it serves none or has not reported one")
    private String fingerprint;

    @SerializedName(ApiConstants.STORED_FINGERPRINT)
    @Param(description = "The SHA-256 fingerprint of the stored custom certificate (uploadCustomCertificate) that the system VM is configured to serve; "
            + "absent when it serves none")
    private String storedFingerprint;

    public SystemVmCertificateResponse() {
        setObjectName("systemvmcertificate");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSystemVmType() {
        return systemVmType;
    }

    public void setSystemVmType(String systemVmType) {
        this.systemVmType = systemVmType;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getAgentState() {
        return agentState;
    }

    public void setAgentState(String agentState) {
        this.agentState = agentState;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getStoredFingerprint() {
        return storedFingerprint;
    }

    public void setStoredFingerprint(String storedFingerprint) {
        this.storedFingerprint = storedFingerprint;
    }
}
