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

package org.apache.cloudstack.resourcelimit.request.api.response;

import java.util.Date;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = ResourceLimitRequestVO.class)
public class ResourceLimitRequestResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "The ID of the request")
    private String id;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "The Account the request is for")
    private String accountName;

    @SerializedName(ApiConstants.ACCOUNT_ID)
    @Param(description = "The ID of the Account the request is for")
    private String accountId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "The domain of the Account")
    private String domainName;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "The ID of the domain of the Account")
    private String domainId;

    @SerializedName("cpu")
    @Param(description = "The requested vCPUs of Instances without a GPU")
    private Long cpu;

    @SerializedName("memory")
    @Param(description = "The requested memory of Instances without a GPU, in MiB")
    private Long memory;

    @SerializedName("storage")
    @Param(description = "The requested storage of volumes and snapshots together, in GiB")
    private Long storage;

    @SerializedName("gpu")
    @Param(description = "The requested GPUs per GPU type")
    private Map<String, Long> gpu;

    @SerializedName("reason")
    @Param(description = "Why the Account asks")
    private String reason;

    @SerializedName("expiry")
    @Param(description = "The requested end of the grant; absent for none")
    private Date expiry;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "Pending, Approved, Rejected, Cancelled, Expired or Revoked")
    private String state;

    @SerializedName("approvedcpu")
    @Param(description = "The granted vCPUs of Instances without a GPU")
    private Long approvedCpu;

    @SerializedName("approvedmemory")
    @Param(description = "The granted memory of Instances without a GPU, in MiB")
    private Long approvedMemory;

    @SerializedName("approvedstorage")
    @Param(description = "The granted storage of volumes and snapshots together, in GiB")
    private Long approvedStorage;

    @SerializedName("approvedgpu")
    @Param(description = "The granted GPUs per GPU type")
    private Map<String, Long> approvedGpu;

    @SerializedName("approvedexpiry")
    @Param(description = "The end of the grant; absent for none")
    private Date approvedExpiry;

    @SerializedName("approver")
    @Param(description = "The user of the last decision: who approved, rejected or revoked it")
    private String approver;

    @SerializedName("decided")
    @Param(description = "When the last decision was made")
    private Date decided;

    @SerializedName("comment")
    @Param(description = "The comment of the last decision")
    private String comment;

    @SerializedName("expirywarningdays")
    @Param(description = "The fewest days before the end of the grant for which the Account has been warned; absent before the first warning")
    private Integer expiryWarningDays;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "When the request was made")
    private Date created;

    public ResourceLimitRequestResponse() {
        setObjectName("resourcelimitrequest");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public Long getCpu() {
        return cpu;
    }

    public void setCpu(Long cpu) {
        this.cpu = cpu;
    }

    public Long getMemory() {
        return memory;
    }

    public void setMemory(Long memory) {
        this.memory = memory;
    }

    public Long getStorage() {
        return storage;
    }

    public void setStorage(Long storage) {
        this.storage = storage;
    }

    public Map<String, Long> getGpu() {
        return gpu;
    }

    public void setGpu(Map<String, Long> gpu) {
        this.gpu = gpu;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Date getExpiry() {
        return expiry;
    }

    public void setExpiry(Date expiry) {
        this.expiry = expiry;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getApprovedCpu() {
        return approvedCpu;
    }

    public void setApprovedCpu(Long approvedCpu) {
        this.approvedCpu = approvedCpu;
    }

    public Long getApprovedMemory() {
        return approvedMemory;
    }

    public void setApprovedMemory(Long approvedMemory) {
        this.approvedMemory = approvedMemory;
    }

    public Long getApprovedStorage() {
        return approvedStorage;
    }

    public void setApprovedStorage(Long approvedStorage) {
        this.approvedStorage = approvedStorage;
    }

    public Map<String, Long> getApprovedGpu() {
        return approvedGpu;
    }

    public void setApprovedGpu(Map<String, Long> approvedGpu) {
        this.approvedGpu = approvedGpu;
    }

    public Date getApprovedExpiry() {
        return approvedExpiry;
    }

    public void setApprovedExpiry(Date approvedExpiry) {
        this.approvedExpiry = approvedExpiry;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public Date getDecided() {
        return decided;
    }

    public void setDecided(Date decided) {
        this.decided = decided;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Integer getExpiryWarningDays() {
        return expiryWarningDays;
    }

    public void setExpiryWarningDays(Integer expiryWarningDays) {
        this.expiryWarningDays = expiryWarningDays;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
