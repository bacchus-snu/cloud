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
 * Pulls the bytes of a volume file that another host exports (see {@link StartVolumeExportCommand}) into
 * a file of a local pool, byte for byte. The file appears under its name only after its length and
 * SHA-256 match the source's; a qcow2 file with a backing file is then pointed at the given base
 * file of the destination pool. The partial file never survives a failure.
 */
public class CopyVolumeFileFromHostCommand extends Command {
    private String sourceAddress;
    private int sourcePort;
    private String exportName;
    private StorageFilerTO pool;
    private String path;
    private String format;
    private long expectedLength;
    private String expectedSha256;
    private String backingInstallPath;
    private String backingFormat;
    private int transferTimeout;

    protected CopyVolumeFileFromHostCommand() {
    }

    /**
     * @param transferTimeout seconds the pull itself may take
     * @param wait seconds the whole command may take: the pull, then reading the file back, rebasing and checking it
     */
    public CopyVolumeFileFromHostCommand(String sourceAddress, int sourcePort, String exportName, StorageFilerTO pool, String path, String format,
            long expectedLength, String expectedSha256, String backingInstallPath, String backingFormat, int transferTimeout, int wait) {
        this.sourceAddress = sourceAddress;
        this.sourcePort = sourcePort;
        this.exportName = exportName;
        this.pool = pool;
        this.path = path;
        this.format = format;
        this.expectedLength = expectedLength;
        this.expectedSha256 = expectedSha256;
        this.backingInstallPath = backingInstallPath;
        this.backingFormat = backingFormat;
        this.transferTimeout = transferTimeout;
        setWait(wait);
    }

    public String getSourceAddress() {
        return sourceAddress;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public String getExportName() {
        return exportName;
    }

    public StorageFilerTO getPool() {
        return pool;
    }

    public String getPath() {
        return path;
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

    public int getTransferTimeout() {
        return transferTimeout;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
