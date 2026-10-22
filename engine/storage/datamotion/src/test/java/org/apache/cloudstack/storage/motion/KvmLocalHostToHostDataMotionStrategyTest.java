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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.CheckVolumeFileTransferAnswer;
import com.cloud.agent.api.storage.CheckVolumeFileTransferCommand;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.storage.FinishVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.FinishVolumeFileCopyCommand;
import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.agent.api.storage.StartVolumeFileHashCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class KvmLocalHostToHostDataMotionStrategyTest {
    private static final long SRC_HOST = 1L;
    private static final long DEST_HOST = 2L;
    private static final long SRC_POOL = 11L;
    private static final long DEST_POOL = 12L;
    private static final long TEMPLATE_ID = 5L;
    private static final long JOB_ID = 77L;
    private static final long LENGTH = 25624576L;
    private static final String DN = "CN=dst.example.org,OU=cloudstack,O=cloudstack,C=cloudstack";
    private static final String SHA = "7ab22542e29addb1d6a24721e20b39785a549f023eb32ff4b3b92151fca09288";
    private static final String OTHER_SHA = "f6378ce7b13f6aba7ec187fee8750cc35ee55877e858efda1c27f76e3b8c2df7";
    private static final long ALLOCATED = 24608L * 1024;

    @Mock
    private HostDao hostDao;
    @Mock
    private AgentManager agentManager;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VMTemplatePoolDao templatePoolDao;
    @Mock
    private VolumeService volumeService;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private VolumeDetailsDao volumeDetailsDao;
    @Mock
    private AsyncJobManager jobManager;

    @Spy
    @InjectMocks
    private KvmLocalHostToHostDataMotionStrategy strategy = new KvmLocalHostToHostDataMotionStrategy();

    private final List<Long> sentTo = new ArrayList<>();
    private final List<Command> sent = new ArrayList<>();
    private final Map<Long, HostVO> hosts = new HashMap<>();
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicBoolean baseRecordedBeforeItsPull = new AtomicBoolean();
    private MockedStatic<GlobalLock> globalLock;

    @Before
    public void setUp() throws Exception {
        for (long hostId : new long[] {SRC_HOST, DEST_HOST, 3L}) {
            HostVO host = mock(HostVO.class);
            lenient().when(host.getHypervisorType()).thenReturn(hostId == 3L ? HypervisorType.VMware : HypervisorType.KVM);
            lenient().when(host.getStatus()).thenReturn(Status.Up);
            lenient().when(host.getResourceState()).thenReturn(ResourceState.Enabled);
            lenient().when(hostDao.findById(hostId)).thenReturn(host);
            hosts.put(hostId, host);
        }
        lenient().when(configDao.getValue("copy.volume.wait")).thenReturn("10800");
        lenient().doReturn(JOB_ID).when(strategy).currentJobId();
        lenient().doAnswer(invocation -> clock.get()).when(strategy).now();
        lenient().doAnswer(invocation -> {
            clock.addAndGet(invocation.getArgument(0));
            return null;
        }).when(strategy).pause(anyLong());
        globalLock = Mockito.mockStatic(GlobalLock.class);
        GlobalLock lock = mock(GlobalLock.class);
        globalLock.when(() -> GlobalLock.getInternLock("volume.transfer.slots")).thenReturn(lock);
        lenient().when(lock.lock(anyInt())).thenReturn(true);
    }

    @After
    public void tearDown() {
        globalLock.close();
        ConfigKey.init(null);
    }

    private void agents(Function<Command, Answer> responder) throws Exception {
        when(agentManager.send(anyLong(), any(Command.class))).thenAnswer(invocation -> {
            sentTo.add(invocation.getArgument(0));
            Command command = invocation.getArgument(1);
            sent.add(command);
            return responder.apply(command);
        });
    }

    private static PrimaryDataStore store(long id, StoragePoolType type, Scope scope) {
        PrimaryDataStore store = mock(PrimaryDataStore.class);
        lenient().when(store.getId()).thenReturn(id);
        lenient().when(store.getName()).thenReturn("pool-" + id);
        lenient().when(store.getUuid()).thenReturn("uuid-pool-" + id);
        lenient().when(store.getPoolType()).thenReturn(type);
        lenient().when(store.getRole()).thenReturn(DataStoreRole.Primary);
        lenient().when(store.getScope()).thenReturn(scope);
        return store;
    }

    private static PrimaryDataStore local(long id, long hostId) {
        return store(id, StoragePoolType.Filesystem, new HostScope(hostId, 1L, 1L));
    }

    private static VolumeInfo volume(long id, String uuid, String path, DataStore store) {
        VolumeInfo volume = mock(VolumeInfo.class);
        lenient().when(volume.getId()).thenReturn(id);
        lenient().when(volume.getUuid()).thenReturn(uuid);
        lenient().when(volume.getPath()).thenReturn(path);
        lenient().when(volume.getDataStore()).thenReturn(store);
        lenient().when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        lenient().when(volume.getSize()).thenReturn(4294967296L);
        lenient().when(volume.getFormat()).thenReturn(ImageFormat.QCOW2);
        return volume;
    }

    private static TemplateInfo template(long id, String uuid, String installPath, DataStore store) {
        TemplateInfo template = mock(TemplateInfo.class);
        lenient().when(template.getId()).thenReturn(id);
        lenient().when(template.getUuid()).thenReturn(uuid);
        lenient().when(template.getInstallPath()).thenReturn(installPath);
        lenient().when(template.getDataStore()).thenReturn(store);
        lenient().when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        return template;
    }

    @Test
    public void canHandleOnlyLocalFileSystemPoolsOfDifferentKvmHosts() {
        PrimaryDataStore srcLocal = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destLocal = local(DEST_POOL, DEST_HOST);
        assertEquals(StrategyPriority.HYPERVISOR, strategy.canHandle(volume(1, "a", "a", srcLocal), volume(2, "b", null, destLocal)));
        assertEquals(StrategyPriority.HYPERVISOR, strategy.canHandle(template(TEMPLATE_ID, "t", "t", srcLocal), template(TEMPLATE_ID, "t", null, destLocal)));

        assertEquals("same host", StrategyPriority.CANT_HANDLE,
                strategy.canHandle(volume(1, "a", "a", srcLocal), volume(2, "b", null, local(13L, SRC_HOST))));
        assertEquals("shared source pool", StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volume(1, "a", "a", store(SRC_POOL, StoragePoolType.NetworkFilesystem, new ClusterScope(1L, 1L, 1L))), volume(2, "b", null, destLocal)));
        assertEquals("cluster-wide file system pool", StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volume(1, "a", "a", srcLocal), volume(2, "b", null, store(DEST_POOL, StoragePoolType.Filesystem, new ClusterScope(1L, 1L, 1L)))));
        assertEquals("local pool of another type", StrategyPriority.CANT_HANDLE, strategy.canHandle(
                volume(1, "a", "a", srcLocal), volume(2, "b", null, store(DEST_POOL, StoragePoolType.LVM, new HostScope(DEST_HOST, 1L, 1L)))));
        assertEquals("not a KVM host", StrategyPriority.CANT_HANDLE, strategy.canHandle(volume(1, "a", "a", srcLocal), volume(2, "b", null, local(DEST_POOL, 3L))));
        assertEquals("volume to template", StrategyPriority.CANT_HANDLE,
                strategy.canHandle(volume(1, "a", "a", srcLocal), template(TEMPLATE_ID, "t", null, destLocal)));
        DataStore image = mock(DataStore.class);
        lenient().when(image.getRole()).thenReturn(DataStoreRole.Image);
        assertEquals("image store", StrategyPriority.CANT_HANDLE, strategy.canHandle(template(TEMPLATE_ID, "t", "t", image), template(TEMPLATE_ID, "t", null, destLocal)));
        assertEquals("live migration with the Instance", StrategyPriority.CANT_HANDLE, strategy.canHandle(new HashMap<>(), mock(HostVO.class), mock(HostVO.class)));
    }

    private CopyCommandResult copy(DataObject src, DataObject dest) {
        AtomicReference<CopyCommandResult> result = new AtomicReference<>();
        strategy.copyAsync(src, dest, null, result::set);
        return result.get();
    }

    private static CheckVolumeFileTransferAnswer succeeded(CheckVolumeFileTransferCommand command, String sha256, long length) {
        return new CheckVolumeFileTransferAnswer(command, CheckVolumeFileTransferAnswer.State.SUCCEEDED, sha256, length, null);
    }

    private Answer happyPath(Command command, int chainDepth) {
        if (command instanceof InspectVolumeFileCommand) {
            return new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, LENGTH,
                    chainDepth == 0 ? null : "/var/lib/libvirt/images/tmpl-src", chainDepth == 0 ? null : "qcow2", chainDepth);
        }
        if (command instanceof PrepareVolumeFileCopyCommand) {
            return new PrepareVolumeFileCopyAnswer((PrepareVolumeFileCopyCommand)command, true, DN);
        }
        if (command instanceof StartVolumeExportCommand) {
            return new StartVolumeExportAnswer((StartVolumeExportCommand)command, "10.0.0.11", 10810);
        }
        if (command instanceof CheckVolumeFileTransferCommand) {
            return succeeded((CheckVolumeFileTransferCommand)command, SHA, LENGTH);
        }
        if (command instanceof FinishVolumeFileCopyCommand) {
            return new FinishVolumeFileCopyAnswer((FinishVolumeFileCopyCommand)command, LENGTH, SHA, ALLOCATED);
        }
        return new Answer(command);
    }

    private void registeredTemplate(boolean directDownload, ImageFormat format) {
        VMTemplateVO template = mock(VMTemplateVO.class);
        lenient().when(template.isDirectDownload()).thenReturn(directDownload);
        lenient().when(template.getFormat()).thenReturn(format);
        lenient().when(template.getUuid()).thenReturn("tmpl-uuid");
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
    }

    private void sourceBase(String installPath) {
        VMTemplateStoragePoolVO base = mock(VMTemplateStoragePoolVO.class);
        lenient().when(base.getInstallPath()).thenReturn(installPath);
        when(templatePoolDao.findByPoolTemplate(SRC_POOL, TEMPLATE_ID, null)).thenReturn(base);
    }

    private List<Class<?>> sentClasses() {
        List<Class<?>> classes = new ArrayList<>();
        for (Command command : sent) {
            classes.add(command.getClass());
        }
        return classes;
    }

    private <C extends Command> C sentOf(Class<C> type) {
        for (Command command : sent) {
            if (type.isInstance(command)) {
                return type.cast(command);
            }
        }
        return null;
    }

    @Test
    public void volumeCopyRunsOnUnitsOfTheHostsThatTheManagementServerFollows() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        VolumeVO destRow = mock(VolumeVO.class);
        when(destRow.getId()).thenReturn(20L);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        registeredTemplate(false, ImageFormat.RAW);
        AtomicInteger pullChecks = new AtomicInteger();
        agents(command -> command instanceof CheckVolumeFileTransferCommand && ((CheckVolumeFileTransferCommand)command).getKind()
                == CheckVolumeFileTransferCommand.Kind.PULL && pullChecks.incrementAndGet() == 1
                ? new CheckVolumeFileTransferAnswer((CheckVolumeFileTransferCommand)command, CheckVolumeFileTransferAnswer.State.RUNNING, null, 0, null)
                : happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        verify(destRow).setPath("copy-uuid");
        verify(volumeDao).update(20L, destRow);
        assertEquals(List.of(InspectVolumeFileCommand.class, PrepareVolumeFileCopyCommand.class, StartVolumeFileHashCommand.class,
                StartVolumeExportCommand.class, CopyVolumeFileFromHostCommand.class, CheckVolumeFileTransferCommand.class,
                CheckVolumeFileTransferCommand.class, CheckVolumeFileTransferCommand.class, FinishVolumeFileCopyCommand.class,
                StopVolumeExportCommand.class), sentClasses());
        assertEquals(List.of(SRC_HOST, DEST_HOST, SRC_HOST, SRC_HOST, DEST_HOST, DEST_HOST, DEST_HOST, SRC_HOST, DEST_HOST, SRC_HOST), sentTo);
        verify(strategy).pause(KvmLocalHostToHostDataMotionStrategy.POLL_MILLIS);

        InspectVolumeFileCommand inspect = (InspectVolumeFileCommand)sent.get(0);
        assertEquals("src-path", inspect.getPath());
        assertEquals("the recorded format", "qcow2", inspect.getFormat());
        assertEquals("an inspection reads no data", KvmLocalHostToHostDataMotionStrategy.INSPECT_WAIT_SECONDS, inspect.getWait());
        assertTrue("a check is given up quickly", sent.stream().filter(c -> c instanceof CheckVolumeFileTransferCommand)
                .allMatch(c -> c.getWait() == KvmLocalHostToHostDataMotionStrategy.CHECK_WAIT_SECONDS));
        int lifetime = 10800 + KvmLocalHostToHostDataMotionStrategy.verifySeconds(LENGTH);
        assertEquals(lifetime, KvmLocalHostToHostDataMotionStrategy.unitSeconds(10800, LENGTH));
        assertEquals(600 + 1024, KvmLocalHostToHostDataMotionStrategy.verifySeconds(100L << 30));
        StartVolumeFileHashCommand hash = sentOf(StartVolumeFileHashCommand.class);
        assertEquals("the source hashes what it exports", "src-path", hash.getPath());
        assertEquals("copy-uuid", hash.getName());
        assertEquals(lifetime, hash.getLifetime());
        StartVolumeExportCommand export = sentOf(StartVolumeExportCommand.class);
        assertEquals("src-path", export.getPath());
        assertEquals("copy-uuid", export.getName());
        assertEquals("the export admits exactly the destination's identity", DN, export.getClientIdentity());
        assertEquals("the export outlives the pull", lifetime + KvmLocalHostToHostDataMotionStrategy.EXPORT_GRACE_SECONDS, export.getLifetime());
        CopyVolumeFileFromHostCommand pull = sentOf(CopyVolumeFileFromHostCommand.class);
        assertEquals("10.0.0.11", pull.getSourceAddress());
        assertEquals(10810, pull.getSourcePort());
        assertEquals("copy-uuid", pull.getExportName());
        assertEquals("copy-uuid", pull.getPath());
        assertEquals(LENGTH, pull.getExpectedLength());
        assertEquals("the pull with its read-back is bounded by copy.volume.wait and the time to read the file back", lifetime, pull.getLifetime());
        List<CheckVolumeFileTransferCommand.Kind> checks = new ArrayList<>();
        for (Command command : sent) {
            if (command instanceof CheckVolumeFileTransferCommand) {
                checks.add(((CheckVolumeFileTransferCommand)command).getKind());
            }
        }
        assertEquals(List.of(CheckVolumeFileTransferCommand.Kind.PULL, CheckVolumeFileTransferCommand.Kind.PULL, CheckVolumeFileTransferCommand.Kind.HASH),
                checks);
        FinishVolumeFileCopyCommand finish = sentOf(FinishVolumeFileCopyCommand.class);
        assertEquals("copy-uuid", finish.getPath());
        assertEquals("copy-uuid", finish.getExportName());
        assertEquals(LENGTH, finish.getExpectedLength());
        assertEquals("the source's own hash is what the copy must match", SHA, finish.getExpectedSha256());
        assertEquals("the overlay is pointed at the destination's base", "tmpl-dest", finish.getBackingInstallPath());
        assertEquals("a template copied from secondary storage is stored as qcow2 on a file system pool, whatever its registered format", "qcow2",
                finish.getBackingFormat());
        assertEquals("copy-uuid", sentOf(StopVolumeExportCommand.class).getName());
        assertNull("a copy in place is not aborted", sentOf(AbortVolumeFileCopyCommand.class));

        VolumeObjectTO copied = (VolumeObjectTO)((CopyCmdAnswer)result.getAnswer()).getNewData();
        assertEquals("copy-uuid", copied.getPath());
        assertEquals(ImageFormat.QCOW2, copied.getFormat());
        assertEquals(Long.valueOf(4294967296L), copied.getSize());
        verify(volumeDetailsDao).addDetail(20L, VolumeOrchestrationService.TRANSFER_SLOT, SRC_HOST + "," + DEST_HOST + "," + JOB_ID, false);
        verify(volumeDetailsDao).removeDetail(20L, VolumeOrchestrationService.TRANSFER_SLOT);
    }

    @Test
    public void backingFileThatIsNotTheTemplateBaseRefused() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        sourceBase("another-base");
        agents(command -> happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("is not the base of its template"));
        verify(volumeService, never()).ensureTemplateOnPool(anyLong(), any(), any());
        assertEquals("nothing is exported", 1, sent.size());
        verify(volumeDetailsDao).removeDetail(20L, VolumeOrchestrationService.TRANSFER_SLOT);
    }

    @Test
    public void chainDepthTwoRefused() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> happyPath(command, 2));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("backing chain of 2 images"));
        assertEquals(1, sent.size());
    }

    @Test
    public void failedPullIsAbortedAndTheExportAndHashingStopped() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> command instanceof CheckVolumeFileTransferCommand && ((CheckVolumeFileTransferCommand)command).getKind()
                == CheckVolumeFileTransferCommand.Kind.PULL
                ? new CheckVolumeFileTransferAnswer((CheckVolumeFileTransferCommand)command, CheckVolumeFileTransferAnswer.State.FAILED, null, 0,
                        "TLS x509 authz check is denied")
                : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("authz check is denied"));
        assertNull("nothing is put in place", sentOf(FinishVolumeFileCopyCommand.class));
        int abort = sentClasses().indexOf(AbortVolumeFileCopyCommand.class);
        assertEquals(DEST_HOST, (long)sentTo.get(abort));
        AbortVolumeFileCopyCommand aborted = (AbortVolumeFileCopyCommand)sent.get(abort);
        assertEquals("copy-uuid", aborted.getPath());
        assertEquals("copy-uuid", aborted.getExportName());
        assertEquals(StopVolumeExportCommand.class, sent.get(sent.size() - 1).getClass());
        assertEquals(SRC_HOST, (long)sentTo.get(sent.size() - 1));
        verify(volumeDetailsDao).removeDetail(20L, VolumeOrchestrationService.TRANSFER_SLOT);
    }

    @Test
    public void copyWhoseBytesDoNotMatchIsAborted() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> command instanceof FinishVolumeFileCopyCommand
                ? new FinishVolumeFileCopyAnswer((FinishVolumeFileCopyCommand)command, "SHA-256 mismatch") : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("SHA-256 mismatch"));
        assertEquals(List.of(FinishVolumeFileCopyCommand.class, AbortVolumeFileCopyCommand.class, StopVolumeExportCommand.class),
                sentClasses().subList(sent.size() - 3, sent.size()));
    }

    @Test
    public void sourceThatChangedDuringTheCopyRefused() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> command instanceof CheckVolumeFileTransferCommand && ((CheckVolumeFileTransferCommand)command).getKind()
                == CheckVolumeFileTransferCommand.Kind.HASH
                ? succeeded((CheckVolumeFileTransferCommand)command, OTHER_SHA, LENGTH + 512) : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("changed during the copy"));
        assertNull(sentOf(FinishVolumeFileCopyCommand.class));
        assertTrue(sentClasses().contains(AbortVolumeFileCopyCommand.class));
    }

    @Test
    public void hostThatCannotBeAskedForAWhileDoesNotEndTheCopy() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        AtomicInteger checks = new AtomicInteger();
        when(agentManager.send(anyLong(), any(Command.class))).thenAnswer(invocation -> {
            Command command = invocation.getArgument(1);
            sent.add(command);
            if (command instanceof CheckVolumeFileTransferCommand && checks.incrementAndGet() == 1) {
                throw new AgentUnavailableException("the agent reconnects", DEST_HOST);
            }
            return happyPath(command, 0);
        });

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertTrue(result.getResult(), result.isSuccess());
        assertEquals(3, checks.get());
    }

    @Test
    public void unitThatDoesNotEndInTimeIsGivenUp() throws Exception {
        agents(command -> new CheckVolumeFileTransferAnswer((CheckVolumeFileTransferCommand)command, CheckVolumeFileTransferAnswer.State.RUNNING, null, 0, null));

        CloudRuntimeException late = assertThrows(CloudRuntimeException.class, () -> strategy.awaitUnit(DEST_HOST, SRC_HOST, CheckVolumeFileTransferCommand.Kind.PULL,
                "copy-uuid", -KvmLocalHostToHostDataMotionStrategy.UNIT_GRACE_SECONDS - 1));

        assertTrue(late.getMessage(), late.getMessage().contains("did not end"));
    }

    @Test
    public void unsecuredDestinationRefusedBeforeAnythingIsExported() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> command instanceof PrepareVolumeFileCopyCommand
                ? new PrepareVolumeFileCopyAnswer((PrepareVolumeFileCopyCommand)command, "The host is not secured") : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("not secured"));
        assertFalse(sentClasses().contains(StartVolumeExportCommand.class));
        assertFalse(sentClasses().contains(StartVolumeFileHashCommand.class));
    }

    private void slots(String... values) {
        List<VolumeDetailVO> details = new ArrayList<>();
        long id = 30;
        for (String value : values) {
            details.add(new VolumeDetailVO(id++, VolumeOrchestrationService.TRANSFER_SLOT, value, false));
        }
        when(volumeDetailsDao.findDetails(VolumeOrchestrationService.TRANSFER_SLOT)).thenReturn(details, new ArrayList<>());
    }

    private void job(long jobId, JobInfo.Status status) {
        AsyncJobVO job = mock(AsyncJobVO.class);
        lenient().when(job.getStatus()).thenReturn(status);
        when(jobManager.getAsyncJob(jobId)).thenReturn(job);
    }

    private void bound(String value) {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.key(), ConfigKey.Scope.Global, null)).thenReturn(value);
        ConfigKey.init(depot);
    }

    @Test
    public void copyWaitsWhileAHostTakesPartInAsManyCopiesAsTheBoundAllows() throws Exception {
        bound("1");
        slots(3 + "," + DEST_HOST + ",55");
        job(55L, JobInfo.Status.IN_PROGRESS);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        agents(command -> happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertTrue(result.getResult(), result.isSuccess());
        verify(strategy).pause(KvmLocalHostToHostDataMotionStrategy.SLOT_POLL_MILLIS);
        verify(volumeDetailsDao, never()).removeDetail(30L, VolumeOrchestrationService.TRANSFER_SLOT);
        verify(volumeDetailsDao).addDetail(20L, VolumeOrchestrationService.TRANSFER_SLOT, SRC_HOST + "," + DEST_HOST + "," + JOB_ID, false);
    }

    @Test
    public void slotOfACopyWhoseJobEndedFreesItself() throws Exception {
        bound("1");
        slots(SRC_HOST + "," + DEST_HOST + ",44");
        job(44L, JobInfo.Status.FAILED);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        agents(command -> happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertTrue(result.getResult(), result.isSuccess());
        verify(volumeDetailsDao).removeDetail(30L, VolumeOrchestrationService.TRANSFER_SLOT);
        verify(strategy, never()).pause(KvmLocalHostToHostDataMotionStrategy.SLOT_POLL_MILLIS);
    }

    @Test
    public void copyThatFindsNoSlotWithinCopyVolumeWaitIsRefusedBeforeAnythingRuns() throws Exception {
        bound("2");
        when(configDao.getValue("copy.volume.wait")).thenReturn("0");
        when(volumeDetailsDao.findDetails(VolumeOrchestrationService.TRANSFER_SLOT)).thenReturn(List.of(
                new VolumeDetailVO(30L, VolumeOrchestrationService.TRANSFER_SLOT, "3," + SRC_HOST + ",55", false),
                new VolumeDetailVO(31L, VolumeOrchestrationService.TRANSFER_SLOT, SRC_HOST + ",4,56", false)));
        job(55L, JobInfo.Status.IN_PROGRESS);
        job(56L, JobInfo.Status.IN_PROGRESS);

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("volume.move.concurrency.per.host"));
        verify(agentManager, never()).send(anyLong(), any(Command.class));
        verify(volumeDetailsDao, never()).addDetail(eq(20L), eq(VolumeOrchestrationService.TRANSFER_SLOT), any(), eq(false));
    }

    @Test
    public void concurrencyPerHostIsADynamicSettingOfTwoByDefault() {
        assertEquals("volume.move.concurrency.per.host", VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.key());
        assertEquals("2", VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.defaultValue());
        assertTrue(VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.isDynamic());
        assertEquals(List.of(ConfigKey.Scope.Global), VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.getScopes());
    }

    /** A volume copy whose base is not yet on the destination pool, which has the base copied through this strategy. */
    private List<Command> copyWithItsBase(TemplateInfo sourceTemplate, TemplateInfo destinationTemplate, AtomicReference<CopyCommandResult> baseResult)
            throws Exception {
        PrimaryDataStore srcStore = (PrimaryDataStore)sourceTemplate.getDataStore();
        PrimaryDataStore destStore = (PrimaryDataStore)destinationTemplate.getDataStore();
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        registeredTemplate(false, ImageFormat.QCOW2);
        List<Command> baseCommands = new ArrayList<>();
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenAnswer(invocation -> {
            int before = sent.size();
            baseResult.set(copy(sourceTemplate, destinationTemplate));
            baseCommands.addAll(sent.subList(before, sent.size()));
            return "tmpl-uuid";
        });
        agents(command -> {
            if (command instanceof CopyVolumeFileFromHostCommand && "tmpl-uuid".equals(((CopyVolumeFileFromHostCommand)command).getPath())) {
                baseRecordedBeforeItsPull.set(Mockito.mockingDetails(volumeDetailsDao).getInvocations().stream()
                        .anyMatch(call -> call.getMethod().getName().equals("addDetail") && VolumeOrchestrationService.RELOCATION_BASE.equals(call.getArgument(1))));
            }
            return command instanceof InspectVolumeFileCommand && ((InspectVolumeFileCommand)command).getPath().equals("tmpl-src")
                    ? happyPath(command, 0) : happyPath(command, 1);
        });

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        return baseCommands;
    }

    @Test
    public void templateBaseIsCopiedUnderTheTemplateUuidWithinTheVolumeCopy() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        AtomicReference<CopyCommandResult> baseResult = new AtomicReference<>();

        List<Command> base = copyWithItsBase(template(TEMPLATE_ID, "tmpl-uuid", "tmpl-src", srcStore), template(TEMPLATE_ID, "tmpl-uuid", null, destStore),
                baseResult);

        assertTrue(baseResult.get().getResult(), baseResult.get().isSuccess());
        assertEquals("tmpl-src", ((InspectVolumeFileCommand)base.get(0)).getPath());
        CopyVolumeFileFromHostCommand pull = (CopyVolumeFileFromHostCommand)base.stream().filter(c -> c instanceof CopyVolumeFileFromHostCommand).findFirst().get();
        assertEquals("tmpl-uuid", pull.getPath());
        assertEquals("template-" + TEMPLATE_ID + "-" + DEST_POOL, pull.getExportName());
        FinishVolumeFileCopyCommand finish = (FinishVolumeFileCopyCommand)base.stream().filter(c -> c instanceof FinishVolumeFileCopyCommand).findFirst().get();
        assertNull(finish.getBackingInstallPath());
        TemplateObjectTO copied = (TemplateObjectTO)((CopyCmdAnswer)baseResult.get().getAnswer()).getNewData();
        assertEquals("tmpl-uuid", copied.getPath());
        assertEquals("the base's size is its allocation on the destination pool, as for any base", Long.valueOf(ALLOCATED), copied.getSize());
        assertTrue("the base transfer is recorded on the volume being copied before it starts", baseRecordedBeforeItsPull.get());
        verify(volumeDetailsDao).addDetail(20L, VolumeOrchestrationService.RELOCATION_BASE, "template-" + TEMPLATE_ID + "-" + DEST_POOL + ",tmpl-uuid", false);
        verify(volumeDetailsDao).removeDetail(20L, VolumeOrchestrationService.RELOCATION_BASE);
        verify(volumeDetailsDao, Mockito.times(1)).addDetail(anyLong(), eq(VolumeOrchestrationService.TRANSFER_SLOT), any(), eq(false));
        verify(volumeDetailsDao).addDetail(eq(20L), eq(VolumeOrchestrationService.TRANSFER_SLOT), any(), eq(false));
    }

    @Test
    public void rawRegisteredTemplateBaseIsInspectedAsTheQcow2ItIsStoredAs() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        TemplateInfo source = template(TEMPLATE_ID, "tmpl-uuid", "tmpl-src", srcStore);
        when(source.getFormat()).thenReturn(ImageFormat.RAW);
        AtomicReference<CopyCommandResult> baseResult = new AtomicReference<>();

        List<Command> base = copyWithItsBase(source, template(TEMPLATE_ID, "tmpl-uuid", null, local(DEST_POOL, DEST_HOST)), baseResult);

        assertTrue(baseResult.get().getResult(), baseResult.get().isSuccess());
        assertEquals("qcow2", ((InspectVolumeFileCommand)base.get(0)).getFormat());
    }

    @Test
    public void templateBaseIsCopiedBetweenHostsOnlyForAVolumeCopy() throws Exception {
        CopyCommandResult result = copy(template(TEMPLATE_ID, "tmpl-uuid", "tmpl-src", local(SRC_POOL, SRC_HOST)),
                template(TEMPLATE_ID, "tmpl-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("only for a volume copy"));
        verify(agentManager, never()).send(anyLong(), any(Command.class));
    }

    private void directDownloadTemplate() {
        registeredTemplate(true, ImageFormat.QCOW2);
    }

    @Test
    public void rawDirectDownloadBaseIsInspectedAndReferencedAsRaw() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        registeredTemplate(true, ImageFormat.RAW);
        agents(command -> {
            if (command instanceof InspectVolumeFileCommand && "src-path".equals(((InspectVolumeFileCommand)command).getPath())) {
                return new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, LENGTH, "/var/lib/libvirt/images/tmpl-src", "raw", 1);
            }
            return happyPath(command, 0);
        });

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        List<String> bases = new ArrayList<>();
        for (Command command : sent) {
            if (command instanceof InspectVolumeFileCommand && !"src-path".equals(((InspectVolumeFileCommand)command).getPath())) {
                bases.add(((InspectVolumeFileCommand)command).getPath() + ":" + ((InspectVolumeFileCommand)command).getFormat());
            }
        }
        assertEquals("a direct-download base is stored as downloaded", List.of("tmpl-src:raw", "tmpl-dest:raw"), bases);
        assertEquals("raw", sentOf(FinishVolumeFileCopyCommand.class).getBackingFormat());
    }

    @Test
    public void overlayRecordingAnotherBaseFormatRefused() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        lenient().when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        registeredTemplate(false, ImageFormat.QCOW2);
        agents(command -> command instanceof InspectVolumeFileCommand
                ? new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, LENGTH, "/var/lib/libvirt/images/tmpl-src", "raw", 1)
                : happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("records its base as raw"));
        assertFalse("nothing is exported", sentClasses().contains(StartVolumeExportCommand.class));
    }

    @Test
    public void directDownloadBaseThatDiffersOnTheDestinationRefused() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        directDownloadTemplate();
        when(agentManager.send(anyLong(), any(Command.class))).thenAnswer(invocation -> {
            long hostId = invocation.getArgument(0);
            Command command = invocation.getArgument(1);
            sentTo.add(hostId);
            sent.add(command);
            return command instanceof CheckVolumeFileTransferCommand && hostId == DEST_HOST
                    ? succeeded((CheckVolumeFileTransferCommand)command, OTHER_SHA, LENGTH) : happyPath(command, 1);
        });

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("different bytes"));
        assertFalse("nothing is exported", sentClasses().contains(StartVolumeExportCommand.class));
        List<String> hashed = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i) instanceof StartVolumeFileHashCommand) {
                hashed.add(sentTo.get(i) + ":" + ((StartVolumeFileHashCommand)sent.get(i)).getPath());
            }
        }
        assertEquals("each host hashes its own base in a unit", List.of(SRC_HOST + ":tmpl-src", DEST_HOST + ":tmpl-dest"), hashed);
        assertEquals("both hashings are stopped", 2, sentClasses().stream().filter(c -> c == StopVolumeExportCommand.class).count());
    }

    @Test
    public void directDownloadBaseWithTheSameBytesIsReused() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        directDownloadTemplate();
        agents(command -> happyPath(command, command instanceof InspectVolumeFileCommand && !"src-path".equals(((InspectVolumeFileCommand)command).getPath()) ? 0 : 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        List<String> inspected = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i) instanceof InspectVolumeFileCommand) {
                inspected.add(sentTo.get(i) + ":" + ((InspectVolumeFileCommand)sent.get(i)).getPath());
            }
        }
        assertEquals(List.of(SRC_HOST + ":src-path", SRC_HOST + ":tmpl-src", DEST_HOST + ":tmpl-dest"), inspected);
        assertEquals("tmpl-dest", sentOf(FinishVolumeFileCopyCommand.class).getBackingInstallPath());
    }

    @Test
    public void hostHeldForDownEndsTheCopyBeforeItsDeadline() throws Exception {
        long started = clock.get();
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        when(hosts.get(DEST_HOST).getStatus()).thenReturn(Status.Down);
        agents(command -> command instanceof CheckVolumeFileTransferCommand ? null : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("Host " + DEST_HOST + " is Down"));
        assertEquals("given up at once, hours before the unit's own limit", started, clock.get());
        assertTrue("the normal failure path runs", sentClasses().contains(AbortVolumeFileCopyCommand.class));
        assertEquals(StopVolumeExportCommand.class, sent.get(sent.size() - 1).getClass());
    }

    @Test
    public void hostNotUpForLongerThanAnAgentMayBeSilentEndsTheCopy() throws Exception {
        long started = clock.get();
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        when(hosts.get(DEST_HOST).getStatus()).thenReturn(Status.Alert);
        agents(command -> command instanceof CheckVolumeFileTransferCommand ? null : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("Host " + DEST_HOST + " is Alert"));
        long waited = clock.get() - started;
        assertTrue("the stock agent-dead window: ping.interval x ping.timeout = 150 s, waited " + waited, waited > 150_000L && waited <= 160_000L);
    }

    @Test
    public void agentRestartShorterThanThatWindowDoesNotEndTheCopy() throws Exception {
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        AtomicInteger unanswered = new AtomicInteger();
        when(hosts.get(DEST_HOST).getStatus()).thenReturn(Status.Disconnected);
        agents(command -> {
            if (command instanceof CheckVolumeFileTransferCommand && ((CheckVolumeFileTransferCommand)command).getKind() == CheckVolumeFileTransferCommand.Kind.PULL
                    && unanswered.incrementAndGet() <= 6) {
                return null;
            }
            return happyPath(command, 0);
        });

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertTrue(result.getResult(), result.isSuccess());
        assertEquals(7, unanswered.get());
    }

    @Test
    public void destinationEnteringMaintenanceDuringTheCopyEndsItAtOnce() throws Exception {
        copyWhileAHostEntersMaintenance(DEST_HOST);
    }

    @Test
    public void sourceEnteringMaintenanceDuringThePullEndsItAtOnce() throws Exception {
        copyWhileAHostEntersMaintenance(SRC_HOST);
    }

    private void copyWhileAHostEntersMaintenance(long hostId) throws Exception {
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        AtomicInteger pulls = new AtomicInteger();
        when(hosts.get(hostId).getResourceState()).thenAnswer(invocation -> pulls.get() >= 2 ? ResourceState.PrepareForMaintenance : ResourceState.Enabled);
        agents(command -> {
            if (command instanceof CheckVolumeFileTransferCommand && ((CheckVolumeFileTransferCommand)command).getKind() == CheckVolumeFileTransferCommand.Kind.PULL) {
                pulls.incrementAndGet();
                return new CheckVolumeFileTransferAnswer((CheckVolumeFileTransferCommand)command, CheckVolumeFileTransferAnswer.State.RUNNING, null, 0, null);
            }
            return happyPath(command, 0);
        });
        long started = clock.get();

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("Host " + hostId + " is in PrepareForMaintenance"));
        assertEquals("given up at the poll that saw it", 2, pulls.get());
        assertTrue("hours before the unit's own limit", clock.get() - started <= KvmLocalHostToHostDataMotionStrategy.POLL_MILLIS);
        Command abort = sent.stream().filter(command -> command instanceof AbortVolumeFileCopyCommand).findFirst().orElse(null);
        assertNotNull("the normal failure path runs", abort);
        assertEquals(DEST_HOST, (long)sentTo.get(sent.indexOf(abort)));
        for (Command command : sent) {
            if (command instanceof AbortVolumeFileCopyCommand || command instanceof StopVolumeExportCommand || command instanceof CheckVolumeFileTransferCommand) {
                assertTrue(command.getClass().getSimpleName() + " reaches a host in maintenance", command.isBypassHostMaintenance());
            }
        }
    }
}
