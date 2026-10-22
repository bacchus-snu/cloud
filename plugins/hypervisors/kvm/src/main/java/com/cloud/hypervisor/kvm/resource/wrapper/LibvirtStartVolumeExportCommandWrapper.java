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
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = StartVolumeExportCommand.class)
public final class LibvirtStartVolumeExportCommandWrapper extends CommandWrapper<StartVolumeExportCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(StartVolumeExportCommand command, LibvirtComputingResource resource) {
        if (!resource.isHostSecured()) {
            return new StartVolumeExportAnswer(command, LibvirtPrepareVolumeFileCopyCommandWrapper.NOT_SECURED);
        }
        try {
            KVMStoragePool pool = resource.getStoragePoolMgr().getStoragePool(command.getPool().getType(), command.getPool().getUuid());
            Path file = VolumeFileTransfer.fileIn(pool.getLocalPath(), command.getPath());
            String address = resource.getPrivateIp();
            int port = getTransfer().startExport(command.getName(), file, address, command.getClientIdentity(), command.getLifetime());
            return new StartVolumeExportAnswer(command, address, port);
        } catch (Exception e) {
            logger.warn("Unable to export volume file {}: {}", command.getPath(), e.getMessage());
            return new StartVolumeExportAnswer(command, e.getMessage());
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
