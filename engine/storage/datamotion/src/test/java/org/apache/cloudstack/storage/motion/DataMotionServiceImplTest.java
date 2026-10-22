/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageStrategyFactory;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.secret.dao.PassphraseDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.DataObjectType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.class)
public class DataMotionServiceImplTest {

    @Mock
    StorageStrategyFactory storageStrategyFactory;
    @Mock
    VolumeDao volDao;
    @Mock
    PassphraseDao passphraseDao;

    @InjectMocks
    DataMotionServiceImpl dataMotionService = new DataMotionServiceImpl();

    private DataObject source;
    private DataObject destination;
    private VolumeVO sourceVolume;

    private DataObject volume(long id) {
        DataObject volume = Mockito.mock(DataObject.class);
        DataStore store = Mockito.mock(DataStore.class);
        DataStoreDriver driver = Mockito.mock(DataStoreDriver.class);
        Mockito.when(volume.getId()).thenReturn(id);
        Mockito.when(volume.getDataStore()).thenReturn(store);
        Mockito.lenient().when(volume.getType()).thenReturn(DataObjectType.VOLUME);
        Mockito.when(store.getDriver()).thenReturn(driver);
        return volume;
    }

    @Before
    public void setUp() {
        source = volume(1L);
        destination = volume(2L);
        sourceVolume = Mockito.mock(VolumeVO.class);
        VolumeVO destinationVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(destinationVolume.getPassphraseId()).thenReturn(7L);
        Mockito.when(volDao.findById(1L)).thenReturn(sourceVolume);
        Mockito.when(volDao.findById(2L)).thenReturn(destinationVolume);
    }

    @SuppressWarnings("unchecked")
    private void failMigrationWithoutStrategy() {
        dataMotionService.copyAsync(source, destination, null, Mockito.mock(AsyncCompletionCallback.class));
    }

    @Test
    public void aFailedMigrationKeepsThePassphraseItsSourceShares() {
        Mockito.when(volDao.listVolumesByPassphraseId(7L)).thenReturn(List.of(sourceVolume));

        failMigrationWithoutStrategy();

        Mockito.verify(passphraseDao, Mockito.never()).remove(Mockito.anyLong());
    }

    @Test
    public void aFailedMigrationDeletesThePassphraseNoVolumeUsesAnyMore() {
        Mockito.when(volDao.listVolumesByPassphraseId(7L)).thenReturn(List.of());

        failMigrationWithoutStrategy();

        Mockito.verify(passphraseDao).remove(7L);
    }
}
