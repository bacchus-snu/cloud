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
package com.cloud.exception;

import com.cloud.dc.DataCenter;
import com.cloud.deploy.PlacementVerdict;
import com.cloud.utils.SerialVersionUID;

/**
 * An operation would need local volumes copied to another host, and the owner has not approved exactly that move:
 * the approval is missing, expired, or was given for a placement that no longer holds. Carries the current verdict,
 * which the owner approves anew.
 */
public class VolumeMoveRequiredException extends InsufficientServerCapacityException {

    private static final long serialVersionUID = SerialVersionUID.VolumeMoveRequiredException;

    private final PlacementVerdict verdict;

    public VolumeMoveRequiredException(String msg, long zoneId, PlacementVerdict verdict) {
        super(msg, DataCenter.class, zoneId);
        this.verdict = verdict;
    }

    public PlacementVerdict getVerdict() {
        return verdict;
    }
}
