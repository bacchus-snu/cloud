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
 * Starts pulling the bytes of a volume file that another host exports (see {@link StartVolumeExportCommand}) into the
 * partial file of a file of a local pool, byte for byte, and answers at once. The pull runs as a transient unit of the
 * host, bounded by the given lifetime; the same unit syncs the partial file, drops it from the page cache and hashes
 * what it reads back. {@link CheckVolumeFileTransferCommand} follows it and {@link FinishVolumeFileCopyCommand} puts the
 * file in place. A destination file that already exists is not pulled again: its bytes are hashed instead, so that a
 * copy whose completion was not recorded can still be recognised.
 */
public class CopyVolumeFileFromHostCommand extends Command {
    private String sourceAddress;
    private int sourcePort;
    private String exportName;
    private StorageFilerTO pool;
    private String path;
    private long expectedLength;
    private int lifetime;

    protected CopyVolumeFileFromHostCommand() {
    }

    /**
     * @param lifetime seconds the pull, with reading the file back, may take
     */
    public CopyVolumeFileFromHostCommand(String sourceAddress, int sourcePort, String exportName, StorageFilerTO pool, String path, long expectedLength,
            int lifetime) {
        this.sourceAddress = sourceAddress;
        this.sourcePort = sourcePort;
        this.exportName = exportName;
        this.pool = pool;
        this.path = path;
        this.expectedLength = expectedLength;
        this.lifetime = lifetime;
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

    public long getExpectedLength() {
        return expectedLength;
    }

    public int getLifetime() {
        return lifetime;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
