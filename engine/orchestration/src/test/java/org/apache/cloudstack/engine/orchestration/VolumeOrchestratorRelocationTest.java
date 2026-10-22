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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.configuration.Resource;
import com.cloud.resource.ResourceState;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmWorkRelocateStoppedVm;
import com.cloud.vm.dao.VMInstanceDao;

@SuppressWarnings("unchecked")
@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeOrchestratorRelocationTest {
    private static final long SRC_HOST = 1L;
    private static final long DEST_HOST = 2L;
    private static final long SRC_POOL = 11L;
    private static final long DEST_POOL = 12L;
    private static final long SRC_POD = 101L;
    private static final long DEST_POD = 102L;
    private static final long ACCOUNT = 5L;
    private static final long KEEPING_DOMAIN = 6L;
    private static final long DISCARDING_DOMAIN = 7L;
    private static final long OFFERING = 3L;
    private static final long TEMPLATE = 9L;
    private static final long VM = 500L;
    private static final long JOB = 77L;
    private static final long SIZE = 10L << 30;

    private final VolumeDao volsDao = mock(VolumeDao.class);
    private final VolumeDetailsDao detailsDao = mock(VolumeDetailsDao.class);
    private final PrimaryDataStoreDao poolDao = mock(PrimaryDataStoreDao.class);
    private final StoragePoolHostDao poolHostDao = mock(StoragePoolHostDao.class);
    private final HostDao hostDao = mock(HostDao.class);
    private final StorageManager storageMgr = mock(StorageManager.class);
    private final DiskOfferingDao diskOfferingDao = mock(DiskOfferingDao.class);
    private final SnapshotHelper snapshotHelper = mock(SnapshotHelper.class);
    private final VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
    private final ResourceLimitService resourceLimitMgr = mock(ResourceLimitService.class);
    private final AccountManager accountMgr = mock(AccountManager.class);
    private final VolumeDataFactory volFactory = mock(VolumeDataFactory.class);
    private final VolumeService volService = mock(VolumeService.class);
    private final AgentManager agentMgr = mock(AgentManager.class);
    private final AsyncJobManager jobMgr = mock(AsyncJobManager.class);
    private final VmWorkJobDao workJobDao = mock(VmWorkJobDao.class);
    private final SnapshotDao snapshotDao = mock(SnapshotDao.class);
    private final SnapshotDataStoreDao snapshotDataStoreDao = mock(SnapshotDataStoreDao.class);
    private final ConfigDepotImpl configDepot = mock(ConfigDepotImpl.class);

    private VolumeOrchestrator orchestrator;

    private final Map<Long, VolumeVO> db = new LinkedHashMap<>();
    private final Map<Long, Map<String, String>> details = new HashMap<>();
    private final List<Object[]> transitions = new ArrayList<>();
    private final List<Long> counted = new ArrayList<>();
    private final List<Long> uncounted = new ArrayList<>();
    private final List<Long> expunged = new ArrayList<>();
    private final Set<Long> failingCopies = new HashSet<>();
    private final List<Command> sent = new ArrayList<>();
    private final Map<Long, Boolean> shownWhileCopying = new HashMap<>();
    private int transactions;
    private int depth;
    private final List<Integer> depthAtCheck = new ArrayList<>();
    private final List<Integer> depthAtCount = new ArrayList<>();
    private final List<Integer> depthAtClose = new ArrayList<>();
    private boolean abortFails;
    private long nextId = 1000;

    @Before
    public void setUp() throws Exception {
        orchestrator = Mockito.spy(new VolumeOrchestrator());
        ReflectionTestUtils.setField(orchestrator, "_volsDao", volsDao);
        ReflectionTestUtils.setField(orchestrator, "_volumeDao", volsDao);
        ReflectionTestUtils.setField(orchestrator, "_volDetailDao", detailsDao);
        ReflectionTestUtils.setField(orchestrator, "_storagePoolDao", poolDao);
        ReflectionTestUtils.setField(orchestrator, "storagePoolHostDao", poolHostDao);
        ReflectionTestUtils.setField(orchestrator, "_hostDao", hostDao);
        ReflectionTestUtils.setField(orchestrator, "storageMgr", storageMgr);
        ReflectionTestUtils.setField(orchestrator, "diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(orchestrator, "snapshotHelper", snapshotHelper);
        ReflectionTestUtils.setField(orchestrator, "vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(orchestrator, "_resourceLimitMgr", resourceLimitMgr);
        ReflectionTestUtils.setField(orchestrator, "_accountMgr", accountMgr);
        ReflectionTestUtils.setField(orchestrator, "volFactory", volFactory);
        ReflectionTestUtils.setField(orchestrator, "volService", volService);
        ReflectionTestUtils.setField(orchestrator, "_agentMgr", agentMgr);
        ReflectionTestUtils.setField(orchestrator, "_jobMgr", jobMgr);
        ReflectionTestUtils.setField(orchestrator, "workJobDao", workJobDao);
        ReflectionTestUtils.setField(orchestrator, "_snapshotDao", snapshotDao);
        ReflectionTestUtils.setField(orchestrator, "_snapshotDataStoreDao", snapshotDataStoreDao);
        ConfigKey.init(configDepot);
        when(configDepot.getConfigStringValue(VolumeOrchestrationService.VolumeMoveKeepSource.key(), ConfigKey.Scope.Domain, DISCARDING_DOMAIN))
                .thenReturn("false");

        doReturn(JOB).when(orchestrator).currentJobId();
        doAnswer(invocation -> {
            transactions++;
            depth++;
            try {
                return ((TransactionCallbackWithException<?, ?>)invocation.getArgument(0)).doInTransaction(null);
            } finally {
                depth--;
            }
        }).when(orchestrator).inTransaction(any());

        when(volsDao.findById(anyLong())).thenAnswer(invocation -> {
            VolumeVO volume = db.get((Long)invocation.getArgument(0));
            return volume == null || volume.getRemoved() != null ? null : volume;
        });
        when(volsDao.findByIdIncludingRemoved(anyLong())).thenAnswer(invocation -> db.get((Long)invocation.getArgument(0)));
        when(volsDao.persist(any(VolumeVO.class))).thenAnswer(invocation -> {
            VolumeVO volume = invocation.getArgument(0);
            ReflectionTestUtils.setField(volume, "id", nextId++);
            db.put(volume.getId(), volume);
            return volume;
        });
        when(volsDao.update(anyLong(), any(VolumeVO.class))).thenReturn(true);
        when(volsDao.updateState(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            VolumeVO volume = invocation.getArgument(3);
            if (volume.getState() != invocation.getArgument(0)) {
                return false;
            }
            volume.setState(invocation.getArgument(2));
            transitions.add(new Object[] {volume.getId(), invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)});
            return true;
        });

        doAnswer(invocation -> details.computeIfAbsent(invocation.getArgument(0), id -> new LinkedHashMap<>())
                .put(invocation.getArgument(1), invocation.getArgument(2))).when(detailsDao).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
        doAnswer(invocation -> details.remove((Long)invocation.getArgument(0))).when(detailsDao).removeDetails(anyLong());
        doAnswer(invocation -> details.getOrDefault((Long)invocation.getArgument(0), new HashMap<>()).remove((String)invocation.getArgument(1)))
                .when(detailsDao).removeDetail(anyLong(), anyString());
        when(detailsDao.listDetailsKeyPairs(anyLong())).thenAnswer(invocation -> new HashMap<>(details.getOrDefault((Long)invocation.getArgument(0), new HashMap<>())));
        when(detailsDao.findDetails(anyString())).thenAnswer(invocation -> {
            List<VolumeDetailVO> found = new ArrayList<>();
            for (Map.Entry<Long, Map<String, String>> entry : details.entrySet()) {
                String value = entry.getValue().get(invocation.getArgument(0));
                if (value != null) {
                    found.add(new VolumeDetailVO(entry.getKey(), invocation.getArgument(0), value, false));
                }
            }
            return found;
        });

        pool(SRC_POOL, SRC_POD, SRC_HOST);
        pool(DEST_POOL, DEST_POD, DEST_HOST);
        host(SRC_HOST);
        host(DEST_HOST);
        when(storageMgr.getStoragePoolTagList(anyLong())).thenReturn(Collections.emptyList());
        when(storageMgr.storagePoolHasEnoughSpace(anyLong(), any())).thenReturn(true);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findByIdIncludingRemoved(OFFERING)).thenReturn(offering);
        when(accountMgr.getAccount(ACCOUNT)).thenReturn(mock(Account.class));
        doAnswer(invocation -> {
            depthAtCount.add(depth);
            return counted.add(invocation.getArgument(2));
        }).when(resourceLimitMgr).incrementVolumeResourceCount(eq(ACCOUNT), anyBoolean(), anyLong(), any());
        doAnswer(invocation -> {
            depthAtCheck.add(depth);
            Reserver reservation = mock(Reserver.class);
            doAnswer(close -> depthAtClose.add(depth)).when(reservation).close();
            ((List<Reserver>)invocation.getArgument(4)).add(reservation);
            return null;
        }).when(resourceLimitMgr).checkVolumeResourceLimit(any(), any(), any(), any(), any());
        doAnswer(invocation -> uncounted.add(invocation.getArgument(2))).when(resourceLimitMgr)
                .decrementVolumeResourceCount(eq(ACCOUNT), anyBoolean(), anyLong(), any(), any());

        when(volFactory.getVolume(anyLong())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            VolumeInfo info = mock(VolumeInfo.class);
            when(info.getId()).thenReturn(id);
            when(info.getUuid()).thenReturn(db.get(id).getUuid());
            return info;
        });
        when(volService.copyVolumeKeepingSource(any(), any())).thenAnswer(invocation -> {
            VolumeInfo copy = invocation.getArgument(1);
            shownWhileCopying.put(((VolumeInfo)invocation.getArgument(0)).getId(), db.get(copy.getId()).isDisplayVolume());
            VolumeApiResult result = new VolumeApiResult(copy);
            if (failingCopies.contains(((VolumeInfo)invocation.getArgument(0)).getId())) {
                result.setResult("SHA-256 mismatch");
            }
            AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<>();
            future.complete(result);
            return future;
        });
        when(volService.expungeVolumeAsync(any())).thenAnswer(invocation -> {
            VolumeInfo info = invocation.getArgument(0);
            VolumeVO volume = db.get(info.getId());
            volume.setState(Volume.State.Expunged);
            volume.setRemoved(new Date());
            expunged.add(info.getId());
            AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<>();
            future.complete(new VolumeApiResult(info));
            return future;
        });
        when(agentMgr.send(anyLong(), any(Command.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(1));
            if (abortFails && invocation.getArgument(1) instanceof AbortVolumeFileCopyCommand) {
                return new Answer(invocation.getArgument(1), false, "host is down");
            }
            return new Answer(invocation.getArgument(1));
        });
    }

    @After
    public void tearDown() {
        ConfigKey.init(null);
    }

    private void pool(long id, long podId, long hostId) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(id);
        when(pool.getPodId()).thenReturn(podId);
        when(pool.getName()).thenReturn("local-" + id);
        when(pool.getPath()).thenReturn("/var/lib/libvirt/images");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.Filesystem);
        when(pool.getScope()).thenReturn(ScopeType.HOST);
        when(poolDao.findById(id)).thenReturn(pool);
        when(poolHostDao.listByPoolId(id)).thenReturn(List.of(new StoragePoolHostVO(id, hostId, "/var/lib/libvirt/images")));
    }

    private void host(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getName()).thenReturn("host-" + id);
        when(hostDao.findById(id)).thenReturn(host);
    }

    private VolumeVO volume(long id, String name, Volume.Type type, Long vmId, long domainId) {
        VolumeVO volume = new VolumeVO(type, name, 1L, domainId, ACCOUNT, OFFERING, Storage.ProvisioningType.THIN, SIZE, null, null, null);
        ReflectionTestUtils.setField(volume, "id", id);
        volume.setPoolId(SRC_POOL);
        volume.setPodId(SRC_POD);
        volume.setPoolType(Storage.StoragePoolType.Filesystem);
        volume.setFolder("/var/lib/libvirt/images");
        volume.setPath("path-" + id);
        volume.setInstanceId(vmId);
        volume.setDeviceId(vmId == null ? null : (type == Volume.Type.ROOT ? 0L : 1L));
        volume.setAttached(vmId == null ? null : new Date());
        volume.setTemplateId(type == Volume.Type.ROOT ? TEMPLATE : null);
        volume.setFormat(Storage.ImageFormat.QCOW2);
        volume.setState(Volume.State.Ready);
        db.put(id, volume);
        return volume;
    }

    private VMInstanceVO vmIn(VirtualMachine.State state) {
        VmWorkJobVO workItem = mock(VmWorkJobVO.class);
        when(workItem.getVmInstanceId()).thenReturn(VM);
        when(workItem.getCmd()).thenReturn(VmWorkRelocateStoppedVm.class.getName());
        when(workJobDao.findById(JOB)).thenReturn(workItem);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getState()).thenReturn(state);
        when(vm.getHostId()).thenReturn(null);
        when(vmInstanceDao.findById(VM)).thenReturn(vm);
        return vm;
    }

    private VolumeVO copyOf(long volumeId) {
        return db.values().stream().filter(v -> v.getLastId() == volumeId).findFirst().orElse(null);
    }

    private StoragePoolVO destPool() {
        return poolDao.findById(DEST_POOL);
    }

    @Test
    public void switchKeepsVolumeIdUuidDevice() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);
        String uuid = root.getUuid();

        orchestrator.relocateVolumes(List.of(root), destPool(), null);

        VolumeVO moved = db.get(100L);
        assertEquals(uuid, moved.getUuid());
        assertEquals(Long.valueOf(0L), moved.getDeviceId());
        assertEquals(Long.valueOf(VM), moved.getInstanceId());
        assertEquals(Volume.Type.ROOT, moved.getVolumeType());
        assertEquals(Volume.State.Ready, moved.getState());
        assertEquals(Long.valueOf(DEST_POOL), moved.getPoolId());
        assertEquals(Long.valueOf(DEST_POD), moved.getPodId());
        assertEquals(Long.valueOf(SRC_POOL), moved.getLastPoolId());
        assertEquals("the volume now uses the copied file", copyOf(100L).getUuid(), moved.getPath());
        assertEquals("copied first, switched once", 1, transactions - 1);
    }

    @Test
    public void previousCopyIsNewDatadiskWithTemplateKept() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);
        String uuid = root.getUuid();

        List<Volume> kept = orchestrator.relocateVolumes(List.of(root), destPool(), null);

        VolumeVO previous = copyOf(100L);
        assertEquals(1, kept.size());
        assertEquals(previous.getId(), kept.get(0).getId());
        assertNotEquals(uuid, previous.getUuid());
        assertEquals(VolumeOrchestrator.previousCopyName("ROOT-500", new Date()), previous.getName());
        assertTrue(previous.getName().matches("ROOT-500-previous-\\d{8}"));
        assertEquals(Volume.Type.DATADISK, previous.getVolumeType());
        assertNull(previous.getInstanceId());
        assertNull(previous.getDeviceId());
        assertNull(previous.getAttached());
        assertEquals(Long.valueOf(TEMPLATE), previous.getTemplateId());
        assertEquals(ACCOUNT, previous.getAccountId());
        assertEquals(KEEPING_DOMAIN, previous.getDomainId());
        assertEquals(Long.valueOf(SIZE), previous.getSize());
        assertEquals("the previous copy is the file left at the source", "path-100", previous.getPath());
        assertEquals(Long.valueOf(SRC_POOL), previous.getPoolId());
        assertEquals(Long.valueOf(SRC_POD), previous.getPodId());
        assertEquals(Volume.State.Ready, previous.getState());
        assertFalse("the marker is gone once the copy is kept", details.containsKey(previous.getId()));
    }

    @Test
    public void moveKeepsSourceCopyAsStandaloneVolume() throws Exception {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(root), destPool(), null);

        VolumeVO moved = db.get(100L);
        VolumeVO previous = copyOf(100L);
        assertEquals("the Instance's volume is on the destination", Long.valueOf(DEST_POOL), moved.getPoolId());
        assertEquals(Long.valueOf(VM), moved.getInstanceId());
        assertTrue(previous.getName().matches("ROOT-500-previous-\\d{8}"));
        assertEquals("the previous copy stays on the source", Long.valueOf(SRC_POOL), previous.getPoolId());
        assertNull("detached", previous.getInstanceId());
        assertEquals("of the same account", ACCOUNT, previous.getAccountId());
        assertEquals(Volume.State.Ready, previous.getState());
        verify(resourceLimitMgr).checkVolumeResourceLimit(any(Account.class), eq(true), eq(SIZE), any(), any());
        assertEquals("counted in the volume count and the storage quota", List.of(SIZE), counted);
    }

    @Test
    public void usageEventOnlyForPrevious() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(root), destPool(), null);

        long previousId = copyOf(100L).getId();
        StateMachine2<Volume.State, Volume.Event, Volume> fsm = Volume.State.getStateMachine();
        List<Long> usage = new ArrayList<>();
        for (Object[] t : transitions) {
            try {
                if (fsm.getTransition((Volume.State)t[1], (Volume.Event)t[2]).isImpacted(StateMachine2.Transition.Impact.USAGE)) {
                    usage.add((Long)t[0]);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        assertEquals("only the previous copy's Creating -> Ready records a volume", List.of(previousId), usage);
        assertTrue(transitions.stream().anyMatch(t -> t[0].equals(100L) && t[1] == Volume.State.Migrating && t[2] == Volume.Event.OperationSucceeded));
    }

    @Test
    public void resourceCountPlusOneVolumeAndSize() throws Exception {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(data), destPool(), null);

        verify(resourceLimitMgr).checkVolumeResourceLimit(any(Account.class), eq(true), eq(SIZE), any(), any());
        assertEquals(List.of(SIZE), counted);
        assertTrue(uncounted.isEmpty());
    }

    @Test
    public void snapshotsUntouched() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(root), destPool(), null);

        verifyNoInteractions(snapshotDao, snapshotDataStoreDao);
        verify(snapshotHelper).checkKvmVolumeSnapshotsOnlyInPrimaryStorage(root, HypervisorType.KVM);
    }

    @Test
    public void keepSourceFalseReservesNoQuotaAndExpungesPreviousCopy() throws Exception {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, DISCARDING_DOMAIN);

        List<Volume> kept = orchestrator.relocateVolumes(List.of(data), destPool(), null);

        assertTrue(kept.isEmpty());
        verify(resourceLimitMgr, never()).checkVolumeResourceLimit(any(), any(), any(), any(), any());
        assertTrue(counted.isEmpty());
        assertTrue(uncounted.isEmpty());
        VolumeVO previous = copyOf(200L);
        assertEquals(List.of(previous.getId()), expunged);
        assertNotNull(previous.getRemoved());
        assertTrue("destroyed only after it became the previous copy", transitions.stream().anyMatch(t -> t[0].equals(previous.getId())
                && t[1] == Volume.State.Ready && t[2] == Volume.Event.DestroyRequested));
        assertFalse(details.containsKey(previous.getId()));
        assertEquals(Long.valueOf(DEST_POOL), db.get(200L).getPoolId());
    }

    @Test
    public void keepSourceIsPerDomain() {
        ConfigKey<Boolean> key = VolumeOrchestrationService.VolumeMoveKeepSource;
        assertEquals("volume.move.keep.source", key.key());
        assertEquals("true", key.defaultValue());
        assertTrue(key.isDynamic());
        assertEquals(ConfigKey.Scope.Domain, key.getScopes().get(0));
        assertTrue(List.of(orchestrator.getConfigKeys()).contains(key));

        VolumeVO keeping = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        VolumeVO discarding = volume(201L, "DATA-2", Volume.Type.DATADISK, null, DISCARDING_DOMAIN);
        List<Volume> kept = orchestrator.relocateVolumes(List.of(keeping, discarding), destPool(), null);

        assertEquals(List.of(copyOf(200L).getId()), kept.stream().map(Volume::getId).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of(copyOf(201L).getId()), expunged);
        assertEquals("only the kept copy counts", List.of(SIZE), counted);
    }

    @Test
    public void failurePathRestoresStates() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);
        VolumeVO data = volume(101L, "DATA-500", Volume.Type.DATADISK, VM, KEEPING_DOMAIN);
        failingCopies.add(101L);
        Runnable inSwitch = mock(Runnable.class);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> orchestrator.relocateVolumes(List.of(root, data), destPool(), inSwitch));

        assertTrue(e.getMessage(), e.getMessage().contains("SHA-256 mismatch"));
        verify(inSwitch, never()).run();
        for (VolumeVO volume : List.of(db.get(100L), db.get(101L))) {
            assertEquals(Volume.State.Ready, volume.getState());
            assertEquals(Long.valueOf(SRC_POOL), volume.getPoolId());
            assertEquals("path-" + volume.getId(), volume.getPath());
        }
        VolumeVO rootCopy = copyOf(100L);
        VolumeVO dataCopy = copyOf(101L);
        assertEquals(List.of(rootCopy.getId(), dataCopy.getId()), expunged);
        assertEquals(List.of(SIZE, SIZE), counted);
        assertEquals("each copy is uncounted once", List.of(SIZE, SIZE), uncounted);
        assertTrue(details.isEmpty());
        assertEquals(2, sent.stream().filter(c -> c instanceof AbortVolumeFileCopyCommand).count());
        assertEquals(2, sent.stream().filter(c -> c instanceof StopVolumeExportCommand).count());
        assertEquals(rootCopy.getUuid(), ((AbortVolumeFileCopyCommand)sent.get(0)).getExportName());
        assertEquals("the partial file named after the copy", rootCopy.getUuid(), ((AbortVolumeFileCopyCommand)sent.get(0)).getPath());
    }

    @Test
    public void sweepRecoversStuckMarker() throws Exception {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        VolumeVO busy = volume(300L, "DATA-2", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        doReturn(55L).when(orchestrator).currentJobId();
        orchestrator.reserveRelocations(List.of(data), destPool(), 55L);
        orchestrator.reserveRelocations(List.of(busy), destPool(), 56L);
        AsyncJobVO failed = mock(AsyncJobVO.class);
        when(failed.getStatus()).thenReturn(JobInfo.Status.FAILED);
        AsyncJobVO running = mock(AsyncJobVO.class);
        when(running.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        when(jobMgr.getAsyncJob(55L)).thenReturn(failed);
        when(jobMgr.getAsyncJob(56L)).thenReturn(running);
        GlobalLock lock = mock(GlobalLock.class);
        when(lock.lock(5)).thenReturn(true);
        doReturn(lock).when(orchestrator).sweepLock();
        VMInstanceVO stuck = mock(VMInstanceVO.class);
        when(stuck.getId()).thenReturn(VM);
        when(stuck.getState()).thenReturn(VirtualMachine.State.Migrating);
        when(stuck.getHostId()).thenReturn(null);
        when(vmInstanceDao.findVMInTransition(any(Date.class), eq(VirtualMachine.State.Migrating))).thenReturn(List.of(stuck));
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM)).thenReturn(Collections.emptyList());
        when(vmInstanceDao.updateState(any(), any(), any(), any(), any())).thenReturn(true);

        orchestrator.sweepInterruptedRelocations();

        assertEquals(Volume.State.Ready, db.get(200L).getState());
        assertEquals(List.of(copyOf(200L).getId()), expunged);
        assertEquals("the stuck copy is uncounted", List.of(SIZE), uncounted);
        assertFalse(details.containsKey(copyOf(200L).getId()));
        assertEquals("a move whose job still runs is left alone", Volume.State.Migrating, db.get(300L).getState());
        assertTrue(details.containsKey(copyOf(300L).getId()));
        verify(vmInstanceDao).updateState(eq(VirtualMachine.State.Migrating), eq(VirtualMachine.Event.AgentReportStopped), eq(VirtualMachine.State.Stopped), eq(stuck), any());
        verify(lock).unlock();
    }

    @Test
    public void relocateVolumesForDetachedVolumeNeedsNoVm() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(data), destPool(), null);

        verifyNoInteractions(vmInstanceDao);
        List<Object> movedTransitions = new ArrayList<>();
        for (Object[] t : transitions) {
            if (t[0].equals(200L)) {
                movedTransitions.add(t[3]);
            }
        }
        assertEquals("Ready -> Migrating -> Ready", List.of(Volume.State.Migrating, Volume.State.Ready), movedTransitions);
        assertEquals(Long.valueOf(DEST_POOL), db.get(200L).getPoolId());
    }

    @Test
    public void runningVmVolumesNeverMove() {
        vmIn(VirtualMachine.State.Running);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class,
                () -> orchestrator.relocateVolumes(List.of(root), destPool(), null));

        assertTrue(e.getMessage(), e.getMessage().contains("never move"));
        assertTrue(transitions.isEmpty());
        verify(volsDao, never()).persist(any(VolumeVO.class));
        verifyNoInteractions(volService);

        vmIn(VirtualMachine.State.Stopped);
        e = assertThrows(InvalidParameterValueException.class, () -> orchestrator.relocateVolumes(List.of(root), destPool(), null));
        assertTrue(e.getMessage(), e.getMessage().contains("own work item"));
        assertTrue(transitions.isEmpty());
    }

    @Test
    public void relocateRefusedWhenStorageQuotaShort() throws Exception {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        Mockito.doThrow(new ResourceAllocationException("Maximum amount of resources of Type = 'primary_storage' for Account has been exceeded",
                Resource.ResourceType.primary_storage)).when(resourceLimitMgr).checkVolumeResourceLimit(any(), any(), any(), any(), any());

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> orchestrator.relocateVolumes(List.of(data), destPool(), null));

        assertTrue(e.getMessage(), e.getMessage().contains("storage quota"));
        assertEquals(Volume.State.Ready, db.get(200L).getState());
        assertEquals(Long.valueOf(SRC_POOL), db.get(200L).getPoolId());
        assertTrue(transitions.isEmpty());
        assertTrue(counted.isEmpty());
        verify(volsDao, never()).persist(any(VolumeVO.class));
        verifyNoInteractions(volService);
    }

    @Test
    public void inSwitchRunsInTheTransactionThatRepointsTheVolumes() {
        vmIn(VirtualMachine.State.Migrating);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);
        List<Integer> seen = new ArrayList<>();
        Runnable inSwitch = () -> {
            assertEquals("the volume already points at its new location", Long.valueOf(DEST_POOL), db.get(100L).getPoolId());
            seen.add(transactions);
        };

        orchestrator.relocateVolumes(List.of(root), destPool(), inSwitch);

        assertEquals("inside the second transaction, the switch", List.of(2), seen);
    }

    @Test
    public void encryptedVolumeRefused() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        data.setPassphraseId(42L);

        assertThrows(InvalidParameterValueException.class, () -> orchestrator.relocateVolumes(List.of(data), destPool(), null));
        assertTrue(transitions.isEmpty());
    }

    @Test
    public void unreachableDestinationStillRestoresTheVolume() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        failingCopies.add(200L);
        abortFails = true;

        assertThrows(CloudRuntimeException.class, () -> orchestrator.relocateVolumes(List.of(data), destPool(), null));

        VolumeVO copy = copyOf(200L);
        assertEquals("the volume is usable again at once", Volume.State.Ready, db.get(200L).getState());
        assertEquals(Volume.State.Destroy, copy.getState());
        assertFalse("an undone copy is no volume of the owner's", copy.isDisplayVolume());
        assertEquals("uncounted once", List.of(SIZE), uncounted);
        assertTrue("the copy waits for the destination", expunged.isEmpty());
        assertTrue("the marker stays for the sweep", details.containsKey(copy.getId()));

        abortFails = false;
        AsyncJobVO failed = mock(AsyncJobVO.class);
        when(failed.getStatus()).thenReturn(JobInfo.Status.FAILED);
        when(jobMgr.getAsyncJob(JOB)).thenReturn(failed);
        orchestrator.recoverInterruptedRelocations();

        assertEquals(List.of(copy.getId()), expunged);
        assertFalse(details.containsKey(copy.getId()));
        assertEquals("still uncounted once", List.of(SIZE), uncounted);
        assertEquals(Volume.State.Ready, db.get(200L).getState());
    }

    @Test
    public void restoreRunsOnceWhileTheDestinationStaysUnreachable() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        failingCopies.add(200L);
        abortFails = true;
        assertThrows(CloudRuntimeException.class, () -> orchestrator.relocateVolumes(List.of(data), destPool(), null));
        VolumeVO copy = copyOf(200L);
        assertEquals(Volume.State.Ready, data.getState());
        AsyncJobVO failed = mock(AsyncJobVO.class);
        when(failed.getStatus()).thenReturn(JobInfo.Status.FAILED);
        when(jobMgr.getAsyncJob(JOB)).thenReturn(failed);
        // the volume is being moved again, by another job, while the undone copy still waits for its destination host
        data.setState(Volume.State.Migrating);
        transitions.clear();

        orchestrator.recoverInterruptedRelocations();

        assertEquals("a restore that ran never touches the volume again", Volume.State.Migrating, data.getState());
        assertTrue(transitions.stream().noneMatch(t -> t[0].equals(200L)));
        assertEquals("uncounted once", List.of(SIZE), uncounted);
        assertTrue("the marker stays for the destination's cleanup", details.containsKey(copy.getId()));

        abortFails = false;
        orchestrator.recoverInterruptedRelocations();

        assertEquals(Volume.State.Migrating, data.getState());
        assertEquals(List.of(copy.getId()), expunged);
        assertFalse(details.containsKey(copy.getId()));
        assertEquals(List.of(SIZE), uncounted);
    }

    @Test
    public void copyIsShownOnlyWhileItCounts() {
        VolumeVO keeping = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        VolumeVO discarding = volume(201L, "DATA-2", Volume.Type.DATADISK, null, DISCARDING_DOMAIN);

        orchestrator.relocateVolumes(List.of(keeping, discarding), destPool(), null);

        assertEquals("a copy that is kept counts, and shows, from its allocation; one that is not neither counts nor shows",
                Map.of(200L, true, 201L, false), shownWhileCopying);
        assertTrue(copyOf(200L).isDisplayVolume());
        assertEquals(List.of(SIZE), counted);
    }

    @Test
    public void reservationsAreTakenBeforeTheTransactionAndClosedAfterItCommits() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);
        VolumeVO other = volume(201L, "DATA-2", Volume.Type.DATADISK, null, KEEPING_DOMAIN);

        orchestrator.relocateVolumes(List.of(data, other), destPool(), null);

        assertEquals("checked outside any transaction, so the reservations are visible to every other reserver", List.of(0, 0), depthAtCheck);
        assertEquals("counted inside the transaction that allocates the copies", List.of(1, 1), depthAtCount);
        assertEquals("released after that transaction committed", List.of(0, 0), depthAtClose);
    }

    @Test
    public void noMarkerNothingToUndo() {
        VolumeVO data = volume(200L, "DATA-1", Volume.Type.DATADISK, null, KEEPING_DOMAIN);

        assertTrue(orchestrator.failRelocation(200L));

        assertTrue(transitions.isEmpty());
        assertTrue(sent.isEmpty());
        assertTrue(expunged.isEmpty());
        assertEquals(Volume.State.Ready, data.getState());
    }

    @Test
    public void migratingInstanceOutsideItsRelocationWorkItemRefused() {
        vmIn(VirtualMachine.State.Migrating);
        VmWorkJobVO otherWork = mock(VmWorkJobVO.class);
        when(otherWork.getVmInstanceId()).thenReturn(VM);
        when(otherWork.getCmd()).thenReturn("com.cloud.vm.VmWorkStorageMigration");
        when(workJobDao.findById(JOB)).thenReturn(otherWork);
        VolumeVO root = volume(100L, "ROOT-500", Volume.Type.ROOT, VM, KEEPING_DOMAIN);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> orchestrator.relocateVolumes(List.of(root), destPool(), null));

        assertTrue(e.getMessage(), e.getMessage().contains("own work item"));
        assertTrue(transitions.isEmpty());
    }
}
