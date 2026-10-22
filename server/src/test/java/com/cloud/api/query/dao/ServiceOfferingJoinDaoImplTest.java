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
package com.cloud.api.query.dao;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.gpu.GpuService;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.Storage;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;

@RunWith(MockitoJUnitRunner.class)
public class ServiceOfferingJoinDaoImplTest {

    @Mock
    private GpuService gpuService;

    @Mock
    private ServiceOfferingDao serviceOfferingDao;

    @Mock
    private AnnotationDao annotationDao;

    @Mock
    private AccountManager accountManager;

    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @InjectMocks
    private ServiceOfferingJoinDaoImpl serviceOfferingJoinDao;

    private MockedStatic<ApiDBUtils> apiDBUtils;
    private MockedStatic<CallContext> callContext;

    @Before
    public void setUp() {
        apiDBUtils = Mockito.mockStatic(ApiDBUtils.class);
        callContext = Mockito.mockStatic(CallContext.class);
        CallContext context = mock(CallContext.class);
        callContext.when(CallContext::current).thenReturn(context);
        when(context.getCallingAccount()).thenReturn(mock(Account.class));
    }

    @After
    public void tearDown() {
        apiDBUtils.close();
        callContext.close();
    }

    private ServiceOfferingJoinVO offeringView(long id, Long vgpuProfileId) {
        ServiceOfferingJoinVO view = mock(ServiceOfferingJoinVO.class);
        when(view.getId()).thenReturn(id);
        when(view.getState()).thenReturn(ServiceOffering.State.Active);
        when(view.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);
        when(view.getRootDiskSize()).thenReturn(0L);
        when(view.getVgpuProfileId()).thenReturn(vgpuProfileId);
        return view;
    }

    @Test
    public void listServiceOfferingsIncludesSlotCounts() {
        ServiceOfferingJoinVO gpuView = offeringView(40L, 11L);
        ServiceOfferingVO gpuOffering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(40L)).thenReturn(gpuOffering);
        when(gpuService.slotCount(gpuOffering, null)).thenReturn(new GpuService.SlotCount(3, 7));

        ServiceOfferingResponse response = serviceOfferingJoinDao.newServiceOfferingResponse(gpuView);

        Assert.assertEquals(Integer.valueOf(3), response.getGpuSlotsAvailable());
        Assert.assertEquals(Integer.valueOf(7), response.getGpuSlotsTotal());

        ServiceOfferingResponse cpuOnly = serviceOfferingJoinDao.newServiceOfferingResponse(offeringView(41L, null));

        Assert.assertNull("no GPU slots on an offering without a vGPU profile", cpuOnly.getGpuSlotsAvailable());
        Assert.assertNull(cpuOnly.getGpuSlotsTotal());
        verify(serviceOfferingDao, never()).findByIdIncludingRemoved(41L);
    }

    @Test
    public void dayLeaseWithAnActionTheEnumDoesNotKnowStillLists() throws Exception {
        java.lang.reflect.Field defaultValue = org.apache.cloudstack.framework.config.ConfigKey.class.getDeclaredField("_defaultValue");
        defaultValue.setAccessible(true);
        String original = VMLeaseManager.InstanceLeaseEnabled.defaultValue();
        defaultValue.set(VMLeaseManager.InstanceLeaseEnabled, "true");
        try {
            ServiceOfferingJoinVO view = offeringView(43L, null);
            when(view.getLeaseDuration()).thenReturn(3);

            ServiceOfferingResponse response = serviceOfferingJoinDao.newServiceOfferingResponse(view);

            Assert.assertEquals(Integer.valueOf(3), response.getLeaseDuration());
            Assert.assertEquals("STOP", response.getLeaseExpiryAction());
        } finally {
            defaultValue.set(VMLeaseManager.InstanceLeaseEnabled, original);
        }
    }

    @Test
    public void offeringResponseCarriesItsLeaseHours() {
        ServiceOfferingJoinVO destroying = offeringView(40L, null);
        when(destroying.getLeaseHours()).thenReturn("36");
        when(destroying.getLeaseExpiryAction()).thenReturn(VMLeaseManager.ExpiryAction.DESTROY);
        ServiceOfferingJoinVO stopping = offeringView(42L, null);
        when(stopping.getLeaseHours()).thenReturn("48");

        ServiceOfferingResponse gpu = serviceOfferingJoinDao.newServiceOfferingResponse(destroying);
        Assert.assertEquals(Integer.valueOf(36), gpu.getLeaseHours());
        Assert.assertEquals("DESTROY", gpu.getLeaseExpiryAction());

        ServiceOfferingResponse defaultAction = serviceOfferingJoinDao.newServiceOfferingResponse(stopping);
        Assert.assertEquals(Integer.valueOf(48), defaultAction.getLeaseHours());
        Assert.assertEquals("STOP", defaultAction.getLeaseExpiryAction());

        ServiceOfferingResponse cpuOnly = serviceOfferingJoinDao.newServiceOfferingResponse(offeringView(41L, null));
        Assert.assertNull(cpuOnly.getLeaseHours());
        Assert.assertNull(cpuOnly.getLeaseExpiryAction());

        // a malformed stored value lists as no lease
        ServiceOfferingJoinVO malformed = offeringView(44L, null);
        when(malformed.getLeaseHours()).thenReturn("36h");
        ServiceOfferingResponse unreadable = serviceOfferingJoinDao.newServiceOfferingResponse(malformed);
        Assert.assertNull(unreadable.getLeaseHours());
        Assert.assertNull(unreadable.getLeaseExpiryAction());
        // the lease of an offering comes with the offering's row, not with a lookup per offering
        Mockito.verifyNoInteractions(serviceOfferingDetailsDao);
    }
}
