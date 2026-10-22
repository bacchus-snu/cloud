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
package org.apache.cloudstack.storage.volume;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class VolumeServiceImplEnsureTemplateTest {
    private static final long TEMPLATE_ID = 5L;
    private static final long SRC_POOL = 11L;
    private static final long DEST_POOL = 12L;
    private static final long DEST_REF = 300L;

    private final VMTemplatePoolDao templatePoolDao = mock(VMTemplatePoolDao.class);
    private final TemplateDataFactory templateFactory = mock(TemplateDataFactory.class);
    private final DataMotionService motionService = mock(DataMotionService.class);
    private final ConfigurationDao configDao = mock(ConfigurationDao.class);
    private VolumeServiceImpl volumeService;
    private DataStore srcStore;
    private DataStore destStore;
    private TemplateInfo templateOnSource;
    private DataObject templateOnDestination;

    @Before
    public void setUp() {
        volumeService = Mockito.spy(new VolumeServiceImpl());
        ReflectionTestUtils.setField(volumeService, "_tmpltPoolDao", templatePoolDao);
        ReflectionTestUtils.setField(volumeService, "tmplFactory", templateFactory);
        ReflectionTestUtils.setField(volumeService, "motionSrv", motionService);
        ReflectionTestUtils.setField(volumeService, "configDao", configDao);
        when(configDao.getValue("storage.pool.max.waitseconds")).thenReturn("3600");
        srcStore = store(SRC_POOL);
        destStore = store(DEST_POOL);
        templateOnSource = mock(TemplateInfo.class);
        templateOnDestination = mock(DataObject.class);
        when(templateFactory.getTemplate(TEMPLATE_ID, srcStore)).thenReturn(templateOnSource);
        when(destStore.create(templateOnSource)).thenReturn(templateOnDestination);
        VMTemplateStoragePoolVO sourceBase = ref(100L, ObjectInDataStoreStateMachine.State.Ready, "tmpl-src");
        when(templatePoolDao.findByPoolTemplate(SRC_POOL, TEMPLATE_ID, null)).thenReturn(sourceBase);
    }

    private static DataStore store(long id) {
        DataStore store = mock(DataStore.class);
        when(store.getId()).thenReturn(id);
        when(store.getName()).thenReturn("pool-" + id);
        return store;
    }

    private static VMTemplateStoragePoolVO ref(long id, ObjectInDataStoreStateMachine.State state, String installPath) {
        VMTemplateStoragePoolVO ref = mock(VMTemplateStoragePoolVO.class);
        when(ref.getId()).thenReturn(id);
        when(ref.getState()).thenReturn(state);
        when(ref.getInstallPath()).thenReturn(installPath);
        return ref;
    }

    private void copyCompletes(boolean success) {
        doAnswer(invocation -> {
            AsyncCompletionCallback<CopyCommandResult> callback = invocation.getArgument(2);
            CopyCommandResult result = new CopyCommandResult(null, success ? mock(CopyCmdAnswer.class) : null);
            if (!success) {
                result.setResult("SHA-256 mismatch");
            }
            callback.complete(result);
            return null;
        }).when(motionService).copyAsync(eq(templateOnSource), eq(templateOnDestination), any(AsyncCompletionCallback.class));
    }

    @Test
    public void readyBaseOnTheDestinationIsReused() {
        VMTemplateStoragePoolVO ready = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Ready, "tmpl-dest");
        when(templatePoolDao.findByPoolTemplate(DEST_POOL, TEMPLATE_ID, null)).thenReturn(ready);

        assertEquals("tmpl-dest", volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore));

        verify(motionService, never()).copyAsync(any(DataObject.class), any(DataObject.class), any(AsyncCompletionCallback.class));
        verify(templatePoolDao, never()).acquireInLockTable(anyLong(), anyInt());
    }

    @Test
    public void missingBaseIsCopiedFromTheSourcePoolUnderTheLock() {
        VMTemplateStoragePoolVO allocated = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Allocated, null);
        when(templatePoolDao.findByPoolTemplate(DEST_POOL, TEMPLATE_ID, null)).thenReturn(allocated);
        when(templatePoolDao.acquireInLockTable(DEST_REF, 3600)).thenReturn(allocated);
        VMTemplateStoragePoolVO copied = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Ready, "tmpl-uuid");
        when(templatePoolDao.findById(DEST_REF)).thenReturn(allocated, copied);
        copyCompletes(true);

        assertEquals("tmpl-uuid", volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore));

        InOrder order = inOrder(templatePoolDao, templateOnDestination, motionService);
        order.verify(templatePoolDao).acquireInLockTable(DEST_REF, 3600);
        order.verify(templateOnDestination).processEvent(Event.CreateOnlyRequested);
        order.verify(motionService).copyAsync(eq(templateOnSource), eq(templateOnDestination), any(AsyncCompletionCallback.class));
        order.verify(templateOnDestination).processEvent(eq(Event.OperationSucceeded), any(CopyCmdAnswer.class));
        order.verify(templatePoolDao).releaseFromLockTable(DEST_REF);
    }

    @Test
    public void baseLeftCreatingByAGoneCopierIsCopiedAgain() {
        VMTemplateStoragePoolVO creating = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Creating, null);
        when(templatePoolDao.findByPoolTemplate(DEST_POOL, TEMPLATE_ID, null)).thenReturn(creating);
        when(templatePoolDao.acquireInLockTable(DEST_REF, 3600)).thenReturn(creating);
        VMTemplateStoragePoolVO copied = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Ready, "tmpl-uuid");
        when(templatePoolDao.findById(DEST_REF)).thenReturn(creating, copied);
        copyCompletes(true);

        assertEquals("tmpl-uuid", volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore));

        InOrder order = inOrder(templateOnDestination);
        order.verify(templateOnDestination).processEvent(Event.OperationFailed);
        order.verify(templateOnDestination).processEvent(Event.CreateOnlyRequested);
    }

    @Test
    public void failedCopyFailsTheBaseAndReleasesTheLock() {
        VMTemplateStoragePoolVO allocated = ref(DEST_REF, ObjectInDataStoreStateMachine.State.Allocated, null);
        when(templatePoolDao.findByPoolTemplate(DEST_POOL, TEMPLATE_ID, null)).thenReturn(allocated);
        when(templatePoolDao.acquireInLockTable(DEST_REF, 3600)).thenReturn(allocated);
        when(templatePoolDao.findById(DEST_REF)).thenReturn(allocated);
        copyCompletes(false);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore));

        assertTrue(e.getMessage(), e.getMessage().contains("SHA-256 mismatch"));
        verify(templateOnDestination).processEvent(Event.OperationFailed);
        verify(templateOnDestination, never()).processEvent(eq(Event.OperationSucceeded), any());
        verify(templatePoolDao).releaseFromLockTable(DEST_REF);
    }

    @Test
    public void sourceWithoutAReadyBaseRefused() {
        when(templatePoolDao.findByPoolTemplate(SRC_POOL, TEMPLATE_ID, null)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore));

        verify(destStore, never()).create(any());
    }

    @Test
    public void copyKeepingSourceLeavesBothVolumesAlone() throws Exception {
        VolumeInfo source = mock(VolumeInfo.class);
        VolumeInfo copy = mock(VolumeInfo.class);
        AtomicReference<AsyncCompletionCallback<CopyCommandResult>> callback = new AtomicReference<>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(2));
            return null;
        }).when(motionService).copyAsync(eq(source), eq(copy), any(AsyncCompletionCallback.class));

        org.apache.cloudstack.framework.async.AsyncCallFuture<VolumeApiResult> future = volumeService.copyVolumeKeepingSource(source, copy);
        CopyCommandResult failed = new CopyCommandResult(null, null);
        failed.setResult("SHA-256 mismatch");
        callback.get().complete(failed);

        VolumeApiResult result = future.get();
        assertFalse(result.isSuccess());
        assertEquals("SHA-256 mismatch", result.getResult());
        verify(source, never()).processEvent(any());
        verify(copy, never()).processEvent(any());
    }
}
