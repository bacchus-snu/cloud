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

import java.nio.file.Path;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = InspectVolumeFileCommand.class)
public final class LibvirtInspectVolumeFileCommandWrapper extends CommandWrapper<InspectVolumeFileCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(InspectVolumeFileCommand command, LibvirtComputingResource resource) {
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            pool.refresh();
            Path file = VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath());
            VolumeFileTransfer.Inspection inspection = getTransfer().inspect(file, command.getFormat());
            if (inspection.getChainDepth() > 1) {
                return new InspectVolumeFileAnswer(command, String.format("%s has a backing chain of %d images; only a single base image is supported.",
                        file, inspection.getChainDepth()));
            }
            return new InspectVolumeFileAnswer(command, inspection.getFormat(), inspection.getVirtualSize(), inspection.getLength(), inspection.getSha256(),
                    inspection.getBackingFile(), inspection.getBackingFormat(), inspection.getChainDepth());
        } catch (Exception e) {
            logger.warn("Unable to inspect volume file {}: {}", command.getPath(), e.getMessage());
            return new InspectVolumeFileAnswer(command, e.getMessage());
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
