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
import com.cloud.agent.api.storage.CopyVolumeFileFromHostAnswer;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = CopyVolumeFileFromHostCommand.class)
public final class LibvirtCopyVolumeFileFromHostCommandWrapper extends CommandWrapper<CopyVolumeFileFromHostCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(CopyVolumeFileFromHostCommand command, LibvirtComputingResource resource) {
        if (!resource.isHostSecured()) {
            return new CopyVolumeFileFromHostAnswer(command, LibvirtPrepareVolumeFileCopyCommandWrapper.NOT_SECURED);
        }
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            Path file = VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath());
            Path backing = command.getBackingInstallPath() == null ? null : VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getBackingInstallPath());
            int transferTimeout = command.getTransferTimeout() > 0 ? command.getTransferTimeout() : command.getWait();
            String sha256 = getTransfer().pull(command.getSourceAddress(), command.getSourcePort(), command.getExportName(), file, command.getFormat(),
                    command.getExpectedLength(), command.getExpectedSha256(), backing, command.getBackingFormat(), transferTimeout);
            return new CopyVolumeFileFromHostAnswer(command, command.getExpectedLength(), sha256, allocatedSize(pool, command.getPath(), command.getExpectedLength()));
        } catch (Exception e) {
            logger.warn("Unable to copy volume file {} from {}: {}", command.getPath(), command.getSourceAddress(), e.getMessage());
            return new CopyVolumeFileFromHostAnswer(command, e.getMessage());
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
