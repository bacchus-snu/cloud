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

import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.StorageFilerTO;

/**
 * Puts a file pulled by {@link CopyVolumeFileFromHostCommand} in place once its pull has ended: only when the length
 * and the SHA-256 its unit read back match the source's does the partial file take the file's name; a qcow2 file with
 * a backing file is then pointed at the given base file of the destination pool and checked. Reads no data itself, so
 * it ends quickly. The partial file never survives a failure.
 */
public class FinishVolumeFileCopyCommand extends Command {
    private StorageFilerTO pool;
    private String path;
    private String exportName;
    private String format;
    private long expectedLength;
    private String expectedSha256;
    private String backingInstallPath;
    private String backingFormat;

    protected FinishVolumeFileCopyCommand() {
    }

    public FinishVolumeFileCopyCommand(StorageFilerTO pool, String path, String exportName, String format, long expectedLength, String expectedSha256,
            String backingInstallPath, String backingFormat) {
        this.pool = pool;
        this.path = path;
        this.exportName = exportName;
        this.format = format;
        this.expectedLength = expectedLength;
        this.expectedSha256 = expectedSha256;
        this.backingInstallPath = backingInstallPath;
        this.backingFormat = backingFormat;
    }

    public StorageFilerTO getPool() {
        return pool;
    }

    public String getPath() {
        return path;
    }

    public String getExportName() {
        return exportName;
    }

    public String getFormat() {
        return format;
    }

    public long getExpectedLength() {
        return expectedLength;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    /**
     * @return the base file, relative to the destination pool, the copied image is rebased onto; null when it has no backing file
     */
    public String getBackingInstallPath() {
        return backingInstallPath;
    }

    /**
     * @return the format of that base file, which the copied image records for it; null when it has no backing file
     */
    public String getBackingFormat() {
        return backingFormat;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
