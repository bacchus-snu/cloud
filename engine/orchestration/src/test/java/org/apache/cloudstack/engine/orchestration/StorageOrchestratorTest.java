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
package org.apache.cloudstack.engine.orchestration;

import java.util.LinkedList;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.ImageStoreService.MigrationPolicy;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.server.StatsCollector;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.StorageService;
import com.cloud.storage.StorageStats;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class StorageOrchestratorTest {

    @Mock
    DataMigrationUtility migrationHelper;
    @Mock
    DataStoreManager dataStoreManager;
    @Mock
    StatsCollector statsCollector;
    @Mock
    StorageService storageService;

    @InjectMocks
    StorageOrchestrator storageOrchestrator = new StorageOrchestrator();

    @Test
    public void completeMigrationLeavesTheSourceWritableWhileACapacityIsUnknown() {
        DataStore source = Mockito.mock(DataStore.class);
        Mockito.when(dataStoreManager.getDataStore(1L, DataStoreRole.Image)).thenReturn(source);
        Mockito.when(migrationHelper.getSortedValidSourcesList(Mockito.eq(source), Mockito.anyMap(), Mockito.anyMap(), Mockito.anyMap()))
                .thenReturn(new LinkedList<>(List.of(Mockito.mock(DataObject.class))));
        StorageStats sourceStats = Mockito.mock(StorageStats.class);
        Mockito.lenient().when(sourceStats.getCapacityBytes()).thenReturn(100L);
        Mockito.lenient().when(statsCollector.getStorageStats(1L)).thenReturn(sourceStats);
        Mockito.when(statsCollector.getStorageStats(2L)).thenReturn(null);

        Assert.assertThrows(CloudRuntimeException.class, () -> storageOrchestrator.migrateData(1L, List.of(2L), MigrationPolicy.COMPLETE));

        Mockito.verify(storageService, Mockito.never()).updateImageStoreStatus(Mockito.anyLong(), Mockito.anyBoolean());
    }
}
