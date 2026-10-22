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
            return new Answer(command, false, LibvirtPrepareVolumeFileCopyCommandWrapper.NOT_SECURED);
        }
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            getTransfer().startPull(command.getSourceAddress(), command.getSourcePort(), command.getExportName(),
                    VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath()), command.getExpectedLength(), command.getLifetime());
            return new Answer(command);
        } catch (Exception e) {
            logger.warn("Unable to start copying volume file {} from {}: {}", command.getPath(), command.getSourceAddress(), e.getMessage());
            return new Answer(command, false, e.getMessage());
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
