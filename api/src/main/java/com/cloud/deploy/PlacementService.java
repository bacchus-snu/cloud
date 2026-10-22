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

import org.apache.cloudstack.api.command.admin.host.UpdateHostMoveRateCmd;
import org.apache.cloudstack.api.command.user.vm.CheckPlacementCmd;
import org.apache.cloudstack.api.command.user.vm.ListPlacementCapacityCmd;
import org.apache.cloudstack.api.response.PlacementCapacityResponse;
import org.apache.cloudstack.api.response.PlacementResponse;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.exception.InsufficientCapacityException;

/**
 * Placement verdicts and the owner's approval of volume moves. A verdict is computed with the predicates that admit an
 * Instance on a host when it starts, so a verdict and the admission that follows it agree while nothing changes.
 */
public interface PlacementService {

    /** The host detail holding the rate in megabits per second at which the host copies volumes to or from another host. */
    String HOST_MOVE_RATE_DETAIL = "move.mbps";

    ConfigKey<Long> VolumeMoveEstimateMbps = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Long.class, "volume.move.estimate.mbps", "800",
            "The rate in megabits per second assumed for copying a volume between hosts when a host has no rate of its own set by " +
                    "updateHostMoveRate; a move is estimated at the slower of its two hosts.", true, ConfigKey.Scope.Global);

    ConfigKey<Integer> PlacementTokenExpirySeconds = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "placement.token.expiry.seconds", "900",
            "Seconds for which the approval of a volume move returned by checkPlacement stays valid. A changed value applies to approvals issued " +
                    "after the change.", true, ConfigKey.Scope.Global);

    PlacementResponse checkPlacement(CheckPlacementCmd cmd);

    /**
     * The largest Instance without a vGPU profile that one usable host could take, per resource, and for a given
     * Instance what is free on its host. The hosts are those the host allocators start from for the given offering's
     * host tags; without an offering the result ignores offering host tags. Display only: it names no host and bounds
     * no input.
     */
    PlacementCapacityResponse listPlacementCapacity(ListPlacementCapacityCmd cmd);

    /** Sets, or with 0 removes, the move rate of a host; the estimate of a move uses the slower of its two hosts. */
    void updateHostMoveRate(UpdateHostMoveRateCmd cmd);

    PlacementVerdict check(PlacementRequest request);

    /**
     * Decides whether an operation may go ahead, and with which move.
     *
     * @param migrateVolumes whether the caller allows local volumes to be moved
     * @param token the approval returned with a {@link PlacementVerdict.Verdict#move} verdict
     * @return the approved move, its destination and volumes; or null when the operation needs no move
     * @throws com.cloud.exception.VolumeMoveRequiredException when a move is needed and the caller did not approve exactly
     *         that move, carrying the current verdict
     * @throws InsufficientCapacityException when no host has room now, naming the resource that is short
     */
    PlacementVerdict approvedMove(PlacementRequest request, boolean migrateVolumes, String token) throws InsufficientCapacityException;
}
