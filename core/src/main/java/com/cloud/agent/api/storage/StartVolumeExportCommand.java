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
 * Serves the bytes of a volume file read-only over NBD with TLS, to exactly one client identity,
 * for at most the given number of seconds.
 */
public class StartVolumeExportCommand extends Command {
    private StorageFilerTO pool;
    private String path;
    private String name;
    private String clientIdentity;
    private int lifetime;

    protected StartVolumeExportCommand() {
    }

    public StartVolumeExportCommand(StorageFilerTO pool, String path, String name, String clientIdentity, int lifetime) {
        this.pool = pool;
        this.path = path;
        this.name = name;
        this.clientIdentity = clientIdentity;
        this.lifetime = lifetime;
    }

    public StorageFilerTO getPool() {
        return pool;
    }

    public String getPath() {
        return path;
    }

    /**
     * @return the export name, which also names the transient unit that serves it
     */
    public String getName() {
        return name;
    }

    public String getClientIdentity() {
        return clientIdentity;
    }

    /**
     * @return the seconds after which the export stops even if nobody stops it
     */
    public int getLifetime() {
        return lifetime;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
