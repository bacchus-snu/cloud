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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.InternalIdentity;

/**
 * One GPU of a registered GPU group of a host with rule partitions; the GPUs of a group share its index.
 */
@Entity
@Table(name = "host_gpu_group_member")
public class HostGpuGroupMemberVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "host_id")
    private long hostId;

    @Column(name = "group_index")
    private int groupIndex;

    @Column(name = "bus_address")
    private String busAddress;

    public HostGpuGroupMemberVO() {
    }

    public HostGpuGroupMemberVO(long hostId, int groupIndex, String busAddress) {
        this.hostId = hostId;
        this.groupIndex = groupIndex;
        this.busAddress = busAddress;
    }

    @Override
    public long getId() {
        return id;
    }

    public long getHostId() {
        return hostId;
    }

    public int getGroupIndex() {
        return groupIndex;
    }

    public String getBusAddress() {
        return busAddress;
    }

    @Override
    public String toString() {
        return String.format("HostGpuGroupMember {host: %d, group: %d, bus address: %s}", hostId, groupIndex, busAddress);
    }
}
