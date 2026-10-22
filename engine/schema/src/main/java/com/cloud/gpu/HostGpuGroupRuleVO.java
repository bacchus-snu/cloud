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
package com.cloud.gpu;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.db.GenericDao;

/**
 * The GPU group rule registered for a host: partitions, any or single.
 */
@Entity
@Table(name = "host_gpu_group_rule")
public class HostGpuGroupRuleVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "host_id")
    private long hostId;

    @Column(name = "rule")
    private String rule;

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created;

    public HostGpuGroupRuleVO() {
    }

    public HostGpuGroupRuleVO(long hostId, String rule) {
        this.hostId = hostId;
        this.rule = rule;
    }

    @Override
    public long getId() {
        return id;
    }

    public long getHostId() {
        return hostId;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public Date getCreated() {
        return created;
    }

    @Override
    public String toString() {
        return String.format("HostGpuGroupRule {host: %d, rule: %s}", hostId, rule);
    }
}
