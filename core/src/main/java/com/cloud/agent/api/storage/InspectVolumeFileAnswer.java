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

public class InspectVolumeFileAnswer extends Answer {
    private String format;
    private long virtualSize;
    private long length;
    private String backingFile;
    private String backingFormat;
    private int chainDepth;

    protected InspectVolumeFileAnswer() {
    }

    public InspectVolumeFileAnswer(InspectVolumeFileCommand cmd, String details) {
        super(cmd, false, details);
    }

    public InspectVolumeFileAnswer(InspectVolumeFileCommand cmd, String format, long virtualSize, long length, String backingFile, String backingFormat,
            int chainDepth) {
        super(cmd, true, null);
        this.format = format;
        this.virtualSize = virtualSize;
        this.length = length;
        this.backingFile = backingFile;
        this.backingFormat = backingFormat;
        this.chainDepth = chainDepth;
    }

    public String getFormat() {
        return format;
    }

    public long getVirtualSize() {
        return virtualSize;
    }

    public long getLength() {
        return length;
    }

    /**
     * @return the backing file as recorded in the image, or null when the image has none
     */
    public String getBackingFile() {
        return backingFile;
    }

    /**
     * @return the format the image records for its backing file, or null when it records none
     */
    public String getBackingFormat() {
        return backingFormat;
    }

    /**
     * @return the number of images below this one in its backing chain
     */
    public int getChainDepth() {
        return chainDepth;
    }
}
