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

package org.apache.cloudstack.resourcelimit.request.vo;

import java.lang.reflect.Type;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;
import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.db.GenericDao;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * A request of an account for resource limits and, once approved, the grant made of it. The requested values stay as
 * asked; the grant is the approved values and expiry.
 */
@Entity
@Table(name = "resource_limit_request")
public class ResourceLimitRequestVO implements ControlledEntity, Identity, InternalIdentity {

    public enum State {
        Pending, Approved, Rejected, Cancelled, Expired, Revoked
    }

    private static final Gson GSON = new Gson();
    private static final Type GPU_COUNTS = new TypeToken<TreeMap<String, Long>>() { }.getType();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "cpu")
    private long cpu;

    @Column(name = "memory_mib")
    private long memoryMib;

    @Column(name = "storage_gib")
    private long storageGib;

    @Column(name = "gpus", length = 65535)
    private String gpus;

    @Column(name = "reason", length = 1024)
    private String reason;

    @Column(name = "requested_expiry")
    @Temporal(TemporalType.TIMESTAMP)
    private Date requestedExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private State state;

    @Column(name = "approved_cpu")
    private Long approvedCpu;

    @Column(name = "approved_memory_mib")
    private Long approvedMemoryMib;

    @Column(name = "approved_storage_gib")
    private Long approvedStorageGib;

    @Column(name = "approved_gpus", length = 65535)
    private String approvedGpus;

    @Column(name = "approved_expiry")
    @Temporal(TemporalType.TIMESTAMP)
    private Date approvedExpiry;

    @Column(name = "approver_id")
    private Long approverId;

    @Column(name = "decided_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date decidedAt;

    @Column(name = "comment", length = 1024)
    private String comment;

    @Column(name = "expiry_warning_days")
    private Integer expiryWarningDays;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    @Temporal(TemporalType.TIMESTAMP)
    private Date removed;

    public ResourceLimitRequestVO() {
        uuid = UUID.randomUUID().toString();
    }

    public ResourceLimitRequestVO(long accountId, long domainId, long cpu, long memoryMib, long storageGib, Map<String, Long> gpus, String reason,
            Date requestedExpiry) {
        this();
        this.accountId = accountId;
        this.domainId = domainId;
        this.cpu = cpu;
        this.memoryMib = memoryMib;
        this.storageGib = storageGib;
        this.gpus = toJson(gpus);
        this.reason = reason;
        this.requestedExpiry = requestedExpiry;
        this.state = State.Pending;
    }

    static String toJson(Map<String, Long> counts) {
        return GSON.toJson(counts == null ? new TreeMap<>() : new TreeMap<>(counts), GPU_COUNTS);
    }

    static Map<String, Long> fromJson(String json) {
        if (StringUtils.isBlank(json)) {
            return new TreeMap<>();
        }
        Map<String, Long> counts = GSON.fromJson(json, GPU_COUNTS);
        return counts == null ? new TreeMap<>() : counts;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    @Override
    public Class<?> getEntityType() {
        return ResourceLimitRequestVO.class;
    }

    @Override
    public String getName() {
        return uuid;
    }

    public long getCpu() {
        return cpu;
    }

    public long getMemoryMib() {
        return memoryMib;
    }

    public long getStorageGib() {
        return storageGib;
    }

    /** @return the requested GPUs per GPU type tag, in tag order */
    public Map<String, Long> getGpus() {
        return fromJson(gpus);
    }

    public String getReason() {
        return reason;
    }

    public Date getRequestedExpiry() {
        return requestedExpiry;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Long getApprovedCpu() {
        return approvedCpu;
    }

    public Long getApprovedMemoryMib() {
        return approvedMemoryMib;
    }

    public Long getApprovedStorageGib() {
        return approvedStorageGib;
    }

    /** @return the approved GPUs per GPU type tag, in tag order; empty before approval */
    public Map<String, Long> getApprovedGpus() {
        return fromJson(approvedGpus);
    }

    public Date getApprovedExpiry() {
        return approvedExpiry;
    }

    // A change reaches the database only through a setter: the DAO records the setter calls on the entities it
    // produces, so approve() and decide() go through setters.
    public void approve(long cpu, long memoryMib, long storageGib, Map<String, Long> gpus, Date expiry) {
        setApprovedCpu(cpu);
        setApprovedMemoryMib(memoryMib);
        setApprovedStorageGib(storageGib);
        setApprovedGpus(toJson(gpus));
        setApprovedExpiry(expiry);
        setState(State.Approved);
    }

    protected void setApprovedCpu(Long approvedCpu) {
        this.approvedCpu = approvedCpu;
    }

    protected void setApprovedMemoryMib(Long approvedMemoryMib) {
        this.approvedMemoryMib = approvedMemoryMib;
    }

    protected void setApprovedStorageGib(Long approvedStorageGib) {
        this.approvedStorageGib = approvedStorageGib;
    }

    /** @param approvedGpus the approved GPUs as stored, JSON of counts per GPU type tag */
    protected void setApprovedGpus(String approvedGpus) {
        this.approvedGpus = approvedGpus;
    }

    protected void setApprovedExpiry(Date approvedExpiry) {
        this.approvedExpiry = approvedExpiry;
    }

    public Long getApproverId() {
        return approverId;
    }

    public Date getDecidedAt() {
        return decidedAt;
    }

    public void decide(Long approverId, Date decidedAt, String comment) {
        setApproverId(approverId);
        setDecidedAt(decidedAt);
        setComment(comment);
    }

    protected void setApproverId(Long approverId) {
        this.approverId = approverId;
    }

    protected void setDecidedAt(Date decidedAt) {
        this.decidedAt = decidedAt;
    }

    protected void setComment(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
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

    public Date getRemoved() {
        return removed;
    }

    @Override
    public String toString() {
        return String.format("ResourceLimitRequest [uuid=%s, accountId=%d, state=%s]", uuid, accountId, state);
    }
}
