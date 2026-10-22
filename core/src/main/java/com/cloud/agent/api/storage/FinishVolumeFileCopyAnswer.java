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

public class FinishVolumeFileCopyAnswer extends Answer {
    private long length;
    private String sha256;
    private long allocatedSize;

    protected FinishVolumeFileCopyAnswer() {
    }

    public FinishVolumeFileCopyAnswer(FinishVolumeFileCopyCommand cmd, String details) {
        super(cmd, false, details);
    }

    public FinishVolumeFileCopyAnswer(FinishVolumeFileCopyCommand cmd, long length, String sha256, long allocatedSize) {
        super(cmd, true, null);
        this.length = length;
        this.sha256 = sha256;
        this.allocatedSize = allocatedSize;
    }

    public long getLength() {
        return length;
    }

    /**
     * @return the SHA-256 of the copied bytes, read back from the disk before the file was renamed into place
     */
    public String getSha256() {
        return sha256;
    }

    /**
     * @return the bytes the copied file occupies on the destination pool, as the pool reports them
     */
    public long getAllocatedSize() {
        return allocatedSize;
    }
}
