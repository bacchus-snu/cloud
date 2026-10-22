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
 * Starts hashing a file of a local pool as a transient unit of the host, bounded by the given lifetime, and answers at
 * once; {@link CheckVolumeFileTransferCommand} reports the SHA-256 and the length the unit read, and
 * {@link StopVolumeExportCommand} of the same name stops it.
 */
public class StartVolumeFileHashCommand extends Command {
    private StorageFilerTO pool;
    private String path;
    private String name;
    private int lifetime;

    protected StartVolumeFileHashCommand() {
    }

    public StartVolumeFileHashCommand(StorageFilerTO pool, String path, String name, int lifetime) {
        this.pool = pool;
        this.path = path;
        this.name = name;
        this.lifetime = lifetime;
    }

    public StorageFilerTO getPool() {
        return pool;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public int getLifetime() {
        return lifetime;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
