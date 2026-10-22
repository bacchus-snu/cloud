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
package com.cloud.capacity;

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
 * A pool core of a host held by a VM; the core is named by its lower thread id and is held by one VM at most.
 */
@Entity
@Table(name = "host_core_assignment")
public class HostCoreAssignmentVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "host_id")
    private long hostId;

    @Column(name = "core")
    private int core;

    @Column(name = "vm_id")
    private long vmId;

    @Column(name = GenericDao.CREATED_COLUMN)
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created;

    public HostCoreAssignmentVO() {
    }

    public HostCoreAssignmentVO(long hostId, int core, long vmId) {
        this.hostId = hostId;
        this.core = core;
        this.vmId = vmId;
    }

    @Override
    public long getId() {
        return id;
    }

    public long getHostId() {
        return hostId;
    }

    public int getCore() {
        return core;
    }

    public long getVmId() {
        return vmId;
    }

    public Date getCreated() {
        return created;
    }

    @Override
    public String toString() {
        return String.format("HostCoreAssignment {host: %d, core: %d, vm: %d}", hostId, core, vmId);
    }
}
