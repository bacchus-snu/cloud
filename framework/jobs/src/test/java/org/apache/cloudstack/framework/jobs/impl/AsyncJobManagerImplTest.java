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

package org.apache.cloudstack.framework.jobs.impl;

import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.storage.Volume;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.junit.Assert;
import com.cloud.utils.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AsyncJobManagerImplTest {
    @Spy
    @InjectMocks
    AsyncJobManagerImpl asyncJobManager;
    @Mock
    VolumeDataFactory volFactory;
    @Mock
    VMInstanceDao vmInstanceDao;
    @Mock
    VirtualMachineManager virtualMachineManager;
    @Mock
    NetworkDao networkDao;
    @Mock
    NetworkOrchestrationService networkOrchestrationService;
    @Mock
    ConfigDepot configDepot;

    @Test
    public void testCleanupVolumeResource() {
        AsyncJobVO job = new AsyncJobVO();
        job.setInstanceType(ApiCommandResourceType.Volume.toString());
        job.setInstanceId(1L);
        VolumeInfo volumeInfo = Mockito.mock(VolumeInfo.class);
        when(volFactory.getVolume(Mockito.anyLong())).thenReturn(volumeInfo);
        when(volumeInfo.getState()).thenReturn(Volume.State.Attaching);
        asyncJobManager.cleanupResources(job);
        Mockito.verify(volumeInfo, Mockito.times(1)).stateTransit(Volume.Event.OperationFailed);
    }

    @Test
    public void testCleanupVmResource() throws NoTransitionException {
        AsyncJobVO job = new AsyncJobVO();
        job.setInstanceType(ApiCommandResourceType.VirtualMachine.toString());
        job.setInstanceId(1L);
        VMInstanceVO vmInstanceVO = Mockito.mock(VMInstanceVO.class);
        when(vmInstanceDao.findById(Mockito.anyLong())).thenReturn(vmInstanceVO);
        when(vmInstanceVO.getState()).thenReturn(VirtualMachine.State.Starting);
        when(vmInstanceVO.getHostId()).thenReturn(1L);
        asyncJobManager.cleanupResources(job);
        Mockito.verify(virtualMachineManager, Mockito.times(1)).stateTransitTo(vmInstanceVO, VirtualMachine.Event.OperationFailed, 1L);
    }

    @Test
    public void testCleanupNetworkResource() throws NoTransitionException {
        AsyncJobVO job = new AsyncJobVO();
        job.setInstanceType(ApiCommandResourceType.Network.toString());
        job.setInstanceId(1L);
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        when(networkDao.findById(Mockito.anyLong())).thenReturn(networkVO);
        when(networkVO.getState()).thenReturn(Network.State.Implementing);
        asyncJobManager.cleanupResources(job);
        Mockito.verify(networkOrchestrationService, Mockito.times(1)).stateTransitTo(networkVO,
                Network.Event.OperationFailed);
    }

    private static final String ADD_RESOURCE_DETAIL = "org.apache.cloudstack.api.command.user.volume.AddResourceDetailCmd";

    @Test
    public void valueOfASecureSettingInAJobsParametersIsMaskedWhereTheJobIsLogged() {
        when(configDepot.isEncryptedSetting("resource.request.smtp.password")).thenReturn(true);
        AsyncJobVO job = new AsyncJobVO(null, 2L, 2L, ADD_RESOURCE_DETAIL,
                "{\"details[0].key\":\"resource.request.smtp.password\",\"details[0].value\":\"s3cret\",\"resourcetype\":\"Zone\"}", null, null, null);

        String logged = asyncJobManager.loggable(job);

        Assert.assertFalse(logged, logged.contains("s3cret"));
        Assert.assertTrue(logged, logged.contains("*****"));
        Assert.assertTrue("the rest of the job is logged as before", logged.contains("resourcetype") && logged.contains(ADD_RESOURCE_DETAIL));
        Assert.assertTrue("the job keeps its parameters", job.getCmdInfo().contains("s3cret"));
    }

    @Test
    public void jobWithoutTheValueOfASecureSettingIsLoggedAsBefore() {
        AsyncJobVO plain = new AsyncJobVO(null, 2L, 2L, ADD_RESOURCE_DETAIL, "{\"details[0].key\":\"guest.domain.suffix\",\"details[0].value\":\"example.org\"}",
                null, null, null);
        AsyncJobVO work = new AsyncJobVO(null, 2L, 2L, "com.cloud.vm.VmWorkStart", "{\"vmId\":7,\"params\":{\"a\":\"b\"}}", null, null, null);

        Assert.assertEquals(StringUtils.cleanString(plain.toString()), asyncJobManager.loggable(plain));
        Assert.assertEquals(StringUtils.cleanString(work.toString()), asyncJobManager.loggable(work));
    }
}
