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
package com.cloud.storage;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.Resource;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.storage.dao.StoragePoolWorkDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class StoragePoolAutomationImplTest {
    @Mock
    private VirtualMachineManager vmMgr;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private StoragePoolWorkDao storagePoolWorkDao;

    @InjectMocks
    private StoragePoolAutomationImpl automation = new StoragePoolAutomationImpl();

    private StoragePoolWorkVO workFor(long workId, long vmId) {
        StoragePoolWorkVO work = Mockito.mock(StoragePoolWorkVO.class);
        Mockito.lenient().when(work.getId()).thenReturn(workId);
        Mockito.when(work.getVmId()).thenReturn(vmId);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.lenient().when(vm.getId()).thenReturn(vmId);
        Mockito.lenient().when(vm.getUuid()).thenReturn("uuid-" + vmId);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vmDao.findById(vmId)).thenReturn(vm);
        Mockito.when(volumeDao.findByInstanceAndType(vmId, Volume.Type.ROOT)).thenReturn(List.of(Mockito.mock(VolumeVO.class)));
        return work;
    }

    @Test
    public void instanceWhoseOwnerIsOverQuotaStaysStoppedWhenTheMaintenanceEnds() throws Exception {
        StoragePoolWorkVO overQuota = workFor(1L, 11L);
        StoragePoolWorkVO withinQuota = workFor(2L, 12L);
        VMInstanceVO overQuotaVm = vmDao.findById(11L);
        Mockito.doThrow(new ResourceAllocationException("quota expired or insufficient", Resource.ResourceType.gpu)).when(vmMgr)
                .checkQuotaOfSessionStart(overQuotaVm);

        automation.cancelMigrationWorkloads(List.of(overQuota, withinQuota));

        Mockito.verify(vmMgr, Mockito.never()).advanceStart(Mockito.eq("uuid-11"), Mockito.any(), Mockito.any());
        Mockito.verify(storagePoolWorkDao).remove(1L);
        Mockito.verify(vmMgr).advanceStart("uuid-12", null, null);
        Mockito.verify(withinQuota).setStartedAfterMaintenance(true);
    }
}
