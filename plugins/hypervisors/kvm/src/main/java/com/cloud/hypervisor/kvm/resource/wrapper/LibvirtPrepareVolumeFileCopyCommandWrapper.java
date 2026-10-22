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
import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = PrepareVolumeFileCopyCommand.class)
public final class LibvirtPrepareVolumeFileCopyCommandWrapper extends CommandWrapper<PrepareVolumeFileCopyCommand, Answer, LibvirtComputingResource> {
    static final String NOT_SECURED = "The host is not secured; volume files are copied between hosts only over TLS.";

    @Override
    public Answer execute(PrepareVolumeFileCopyCommand command, LibvirtComputingResource resource) {
        if (!resource.isHostSecured()) {
            return new PrepareVolumeFileCopyAnswer(command, NOT_SECURED);
        }
        try {
            return new PrepareVolumeFileCopyAnswer(command, true, getTransfer().clientIdentity());
        } catch (Exception e) {
            logger.warn("Unable to prepare a volume file copy: {}", e.getMessage());
            return new PrepareVolumeFileCopyAnswer(command, e.getMessage());
        }
    }

    VolumeFileTransfer getTransfer() {
        return new VolumeFileTransfer();
    }
}
