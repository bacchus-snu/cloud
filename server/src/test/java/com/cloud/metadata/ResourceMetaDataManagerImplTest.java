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
package com.cloud.metadata;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.framework.config.ScopedConfigStorage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.server.ResourceManagerUtil;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;

@RunWith(MockitoJUnitRunner.class)
public class ResourceMetaDataManagerImplTest {

    @Mock
    ConfigurationManager configurationManager;
    @Mock
    ResourceManagerUtil resourceManagerUtil;
    @Mock
    VolumeDetailsDao volumeDetailsDao;

    DataCenterDetailsDao zoneDetailsDao = Mockito.mock(DataCenterDetailsDao.class, Mockito.withSettings().extraInterfaces(ScopedConfigStorage.class));

    @InjectMocks
    ResourceMetaDataManagerImpl resourceMetaDataManager = new ResourceMetaDataManagerImpl();

    @Before
    public void setUp() throws ConfigurationException {
        resourceMetaDataManager._dcDetailsDao = zoneDetailsDao;
        resourceMetaDataManager._volumeDetailDao = volumeDetailsDao;
        resourceMetaDataManager.configure(null, null);
    }

    @Test
    public void zoneDetailsCarryAccessControlSettingsOnlyThroughTheSharedCheck() {
        Map<String, String> details = new HashMap<>();
        details.put("some.setting", "value");
        Mockito.doThrow(new PermissionDeniedException("refused")).when(configurationManager).checkCallerMayChangeAccessControlSettings(Set.of("some.setting"));
        Mockito.doThrow(new PermissionDeniedException("refused")).when(configurationManager).checkCallerMayChangeAccessControlSettings(List.of("some.setting"));
        Mockito.when(resourceManagerUtil.getResourceId("zone", ResourceObjectType.Zone)).thenReturn(1L);
        Mockito.when(zoneDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.addResourceMetaData("zone", ResourceObjectType.Zone, details, true));
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone, "some.setting"));
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone, null));

        Mockito.verify(zoneDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(zoneDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(zoneDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void detailsOfOtherResourcesAreNotSettings() {
        Map<String, String> details = new HashMap<>();
        details.put("some.setting", "value");
        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenReturn(true);
            Assert.assertTrue(resourceMetaDataManager.addResourceMetaData("volume", ResourceObjectType.Volume, details, true));
        }
        Mockito.when(resourceManagerUtil.getResourceId("volume", ResourceObjectType.Volume)).thenReturn(2L);

        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("volume", ResourceObjectType.Volume, null));

        Mockito.verify(volumeDetailsDao).removeDetails(2L);
        Mockito.verify(volumeDetailsDao, Mockito.never()).listDetailsKeyPairs(Mockito.anyLong());
        Mockito.verifyNoInteractions(configurationManager);
    }
}
