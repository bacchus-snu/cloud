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
package org.apache.cloudstack.gpu;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One whole GPU group of a host chosen for an Instance: the PCI bus addresses of its GPUs and the ids of their GPU
 * devices, both in bus address order.
 */
public final class GpuGroupPick {

    private final long hostId;
    private final List<String> positions;
    private final List<Long> deviceIds;

    public GpuGroupPick(long hostId, List<String> positions, List<Long> deviceIds) {
        this.hostId = hostId;
        this.positions = Collections.unmodifiableList(positions);
        this.deviceIds = Collections.unmodifiableList(deviceIds);
    }

    public long getHostId() {
        return hostId;
    }

    public List<String> getPositions() {
        return positions;
    }

    public List<Long> getDeviceIds() {
        return deviceIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GpuGroupPick)) {
            return false;
        }
        GpuGroupPick other = (GpuGroupPick) o;
        return hostId == other.hostId && positions.equals(other.positions) && deviceIds.equals(other.deviceIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostId, positions, deviceIds);
    }

    @Override
    public String toString() {
        return String.format("GpuGroupPick {host: %d, positions: %s, devices: %s}", hostId, positions, deviceIds);
    }
}
