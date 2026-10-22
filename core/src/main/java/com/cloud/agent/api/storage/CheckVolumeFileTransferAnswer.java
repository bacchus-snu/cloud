//
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
//

package com.cloud.agent.api.storage;

import com.cloud.agent.api.Answer;

public class CheckVolumeFileTransferAnswer extends Answer {
    public enum State {
        RUNNING, SUCCEEDED, FAILED
    }

    private State state;
    private String sha256;
    private long length;

    protected CheckVolumeFileTransferAnswer() {
    }

    public CheckVolumeFileTransferAnswer(CheckVolumeFileTransferCommand cmd, String details) {
        super(cmd, false, details);
    }

    public CheckVolumeFileTransferAnswer(CheckVolumeFileTransferCommand cmd, State state, String sha256, long length, String details) {
        super(cmd, true, details);
        this.state = state;
        this.sha256 = sha256;
        this.length = length;
    }

    public State getState() {
        return state;
    }

    /**
     * @return once the unit succeeded, the SHA-256 of the bytes it read: for a pull, the bytes read back from the disk,
     *         up to the expected length
     */
    public String getSha256() {
        return sha256;
    }

    /**
     * @return once the unit succeeded, the length of the file it read
     */
    public long getLength() {
        return length;
    }
}
