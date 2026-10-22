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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.StartVolumeFileHashCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = StartVolumeFileHashCommand.class)
public final class LibvirtStartVolumeFileHashCommandWrapper extends CommandWrapper<StartVolumeFileHashCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(StartVolumeFileHashCommand command, LibvirtComputingResource resource) {
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            getTransfer().startHash(command.getName(), VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath()), command.getLifetime());
            return new Answer(command);
        } catch (Exception e) {
            logger.warn("Unable to start hashing volume file {}: {}", command.getPath(), e.getMessage());
            return new Answer(command, false, e.getMessage());
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
