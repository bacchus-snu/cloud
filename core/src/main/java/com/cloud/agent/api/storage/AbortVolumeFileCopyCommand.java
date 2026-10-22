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
 * Stops a pull started by {@link CopyVolumeFileFromHostCommand} and the hashing of the same name, and removes the
 * pull's partial file and both outcomes. The completed file, if any, is left alone. Succeeds when there is nothing to
 * stop or remove.
 */
public class AbortVolumeFileCopyCommand extends Command {
    private StorageFilerTO pool;
    private String path;
    private String exportName;

    protected AbortVolumeFileCopyCommand() {
    }

    public AbortVolumeFileCopyCommand(StorageFilerTO pool, String path, String exportName) {
        this.pool = pool;
        this.path = path;
        this.exportName = exportName;
        // It touches only the units and the partial file of this transfer, so it reaches a host in maintenance.
        setBypassHostMaintenance(true);
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

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
