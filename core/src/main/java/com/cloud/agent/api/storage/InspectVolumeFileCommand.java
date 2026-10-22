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
 * Describes a volume file of a local pool on the host that owns it: format, sizes, backing chain and
 * the SHA-256 of the file's bytes. The file must not be open for writing.
 */
public class InspectVolumeFileCommand extends Command {
    private StorageFilerTO pool;
    private String path;
    private String format;

    protected InspectVolumeFileCommand() {
    }

    public InspectVolumeFileCommand(StorageFilerTO pool, String path, String format, int wait) {
        this.pool = pool;
        this.path = path;
        this.format = format;
        setWait(wait);
    }

    public StorageFilerTO getPool() {
        return pool;
    }

    public String getPath() {
        return path;
    }

    /**
     * @return the format the file is recorded with (qcow2 or raw); a file of another format is refused
     */
    public String getFormat() {
        return format;
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
