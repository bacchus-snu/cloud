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
import com.cloud.agent.api.storage.FinishVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.FinishVolumeFileCopyCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = FinishVolumeFileCopyCommand.class)
public final class LibvirtFinishVolumeFileCopyCommandWrapper extends CommandWrapper<FinishVolumeFileCopyCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(FinishVolumeFileCopyCommand command, LibvirtComputingResource resource) {
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            Path file = VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath());
            Path backing = command.getBackingInstallPath() == null ? null : VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getBackingInstallPath());
            String sha256 = getTransfer().finishPull(command.getExportName(), file, command.getFormat(), command.getExpectedLength(),
                    command.getExpectedSha256(), backing, command.getBackingFormat());
            return new FinishVolumeFileCopyAnswer(command, command.getExpectedLength(), sha256, allocatedSize(pool, command.getPath(), command.getExpectedLength()));
        } catch (Exception e) {
            logger.warn("Unable to put the copy of volume file {} in place: {}", command.getPath(), e.getMessage());
            return new FinishVolumeFileCopyAnswer(command, e.getMessage());
        }
    }

    /**
     * The bytes the copy occupies, as the pool reports any of its volumes; the copy stands even when the pool cannot say.
     */
    private long allocatedSize(KVMStoragePool pool, String path, long length) {
        try {
            pool.refresh();
            return pool.getPhysicalDisk(path).getSize();
        } catch (RuntimeException e) {
            logger.warn("Unable to read the allocation of {} in pool {}: {}", path, pool.getUuid(), e.getMessage());
            return length;
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
