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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Whether an Instance can start, or be created, right now, and on what condition. The destination of a move is kept
 * for the server only; nothing that names or identifies a host is meant for the caller.
 */
public final class PlacementVerdict implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Verdict {
        /** The host that holds the Instance's local volumes has room for it. */
        here,
        /** Some host has room, and no local volume ties the Instance to one. */
        anyhost,
        /** Some host has room only once local volumes are moved to it. */
        move,
        /** No host has room now. */
        none
    }

    public static final class MovedVolume implements Serializable {
        private static final long serialVersionUID = 1L;

        private final long id;
        private final String uuid;
        private final String name;
        private final long size;
        private final long physicalSize;

        public MovedVolume(long id, String uuid, String name, long size, long physicalSize) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.size = size;
            this.physicalSize = physicalSize;
        }

        public long getId() {
            return id;
        }

        public String getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        /** @return the virtual size in bytes */
        public long getSize() {
            return size;
        }

        /** @return the bytes the volume uses, which a move copies */
        public long getPhysicalSize() {
            return physicalSize;
        }
    }

    private final Verdict verdict;
    private final List<MovedVolume> volumes;
    private final long estimatedSeconds;
    private final boolean previousCopyKept;
    private final String reason;
    private final String token;
    private final Long destinationHostId;
    private final Long destinationPoolId;

    private PlacementVerdict(Verdict verdict, List<MovedVolume> volumes, long estimatedSeconds, boolean previousCopyKept, String reason, String token,
            Long destinationHostId, Long destinationPoolId) {
        this.verdict = verdict;
        this.volumes = volumes == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(volumes));
        this.estimatedSeconds = estimatedSeconds;
        this.previousCopyKept = previousCopyKept;
        this.reason = reason;
        this.token = token;
        this.destinationHostId = destinationHostId;
        this.destinationPoolId = destinationPoolId;
    }

    public static PlacementVerdict here(boolean previousCopyKept) {
        return new PlacementVerdict(Verdict.here, null, 0, previousCopyKept, null, null, null, null);
    }

    public static PlacementVerdict anyHost(boolean previousCopyKept) {
        return new PlacementVerdict(Verdict.anyhost, null, 0, previousCopyKept, null, null, null, null);
    }

    public static PlacementVerdict none(String reason, boolean previousCopyKept) {
        return new PlacementVerdict(Verdict.none, null, 0, previousCopyKept, reason, null, null, null);
    }

    public static PlacementVerdict move(List<MovedVolume> volumes, long estimatedSeconds, boolean previousCopyKept, String token, long destinationHostId,
            long destinationPoolId) {
        return new PlacementVerdict(Verdict.move, volumes, estimatedSeconds, previousCopyKept, null, token, destinationHostId, destinationPoolId);
    }

    /**
     * A move is needed where its terms are not known: the volumes only. The owner learns the rest, and approves, through
     * a placement check.
     */
    public static PlacementVerdict moveRequired(List<MovedVolume> volumes) {
        return new PlacementVerdict(Verdict.move, volumes, 0, false, null, null, null, null);
    }

    public Verdict getVerdict() {
        return verdict;
    }

    /** @return the volumes a move copies; empty unless the verdict is {@link Verdict#move} */
    public List<MovedVolume> getVolumes() {
        return volumes;
    }

    public long getBytesToMove() {
        long bytes = 0;
        for (MovedVolume volume : volumes) {
            bytes += volume.getPhysicalSize();
        }
        return bytes;
    }

    public long getEstimatedSeconds() {
        return estimatedSeconds;
    }

    /** @return whether a move keeps the copy at the previous location as a volume of the owner */
    public boolean isPreviousCopyKept() {
        return previousCopyKept;
    }

    /** @return for {@link Verdict#none}, the resource that is short, never naming a host */
    public String getReason() {
        return reason;
    }

    /** @return for {@link Verdict#move}, the approval that binds exactly this move until it expires */
    public String getToken() {
        return token;
    }

    public Long getDestinationHostId() {
        return destinationHostId;
    }

    public Long getDestinationPoolId() {
        return destinationPoolId;
    }

    public List<Long> getVolumeIds() {
        List<Long> ids = new ArrayList<>();
        for (MovedVolume volume : volumes) {
            ids.add(volume.getId());
        }
        return ids;
    }

    /** @return the verdict in words for the owner: sizes and the estimate, never the host */
    public String describe() {
        switch (verdict) {
            case here:
                return "possible on the host that holds its volumes";
            case anyhost:
                return "possible on a host as it is";
            case none:
                return "not possible now: " + reason;
            default:
                StringBuilder text = new StringBuilder("possible only after moving its volumes to another host:");
                for (MovedVolume volume : volumes) {
                    text.append(String.format(" %s (%d bytes, %d bytes used);", volume.getName(), volume.getSize(), volume.getPhysicalSize()));
                }
                text.append(String.format(" about %d seconds", estimatedSeconds));
                return text.toString();
        }
    }
}
