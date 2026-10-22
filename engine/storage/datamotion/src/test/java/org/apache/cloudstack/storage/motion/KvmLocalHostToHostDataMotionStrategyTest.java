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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostAnswer;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.class)
public class KvmLocalHostToHostDataMotionStrategyTest {
    private static final long SRC_HOST = 1L;
    private static final long DEST_HOST = 2L;
    private static final long SRC_POOL = 11L;
    private static final long DEST_POOL = 12L;
    private static final long TEMPLATE_ID = 5L;
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

    @InjectMocks
    private KvmLocalHostToHostDataMotionStrategy strategy = new KvmLocalHostToHostDataMotionStrategy();

    private final List<Long> sentTo = new ArrayList<>();
    private final List<Command> sent = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        for (long hostId : new long[] {SRC_HOST, DEST_HOST, 3L}) {
            HostVO host = mock(HostVO.class);
            lenient().when(host.getHypervisorType()).thenReturn(hostId == 3L ? HypervisorType.VMware : HypervisorType.KVM);
            lenient().when(hostDao.findById(hostId)).thenReturn(host);
        }
        lenient().when(configDao.getValue("copy.volume.wait")).thenReturn("10800");
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

    private Answer happyPath(Command command, int chainDepth) {
        if (command instanceof InspectVolumeFileCommand) {
            return new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, 25624576L, SHA,
                    chainDepth == 0 ? null : "/var/lib/libvirt/images/tmpl-src", chainDepth == 0 ? null : "qcow2", chainDepth);
        }
        if (command instanceof PrepareVolumeFileCopyCommand) {
            return new PrepareVolumeFileCopyAnswer((PrepareVolumeFileCopyCommand)command, true, DN);
        }
        if (command instanceof StartVolumeExportCommand) {
            return new StartVolumeExportAnswer((StartVolumeExportCommand)command, "10.0.0.11", 10810);
        }
        if (command instanceof CopyVolumeFileFromHostCommand) {
            return new CopyVolumeFileFromHostAnswer((CopyVolumeFileFromHostCommand)command, 25624576L, SHA, ALLOCATED);
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

    @Test
    public void volumeCopyInspectsPreparesExportsPullsAndStopsTheExport() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        VolumeVO destRow = mock(VolumeVO.class);
        when(destRow.getId()).thenReturn(20L);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        registeredTemplate(false, ImageFormat.RAW);
        agents(command -> happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        verify(destRow).setPath("copy-uuid");
        verify(volumeDao).update(20L, destRow);
        assertEquals(List.of(SRC_HOST, DEST_HOST, SRC_HOST, DEST_HOST, SRC_HOST), sentTo);
        InspectVolumeFileCommand inspect = (InspectVolumeFileCommand)sent.get(0);
        assertEquals("src-path", inspect.getPath());
        assertEquals("the recorded format", "qcow2", inspect.getFormat());
        assertEquals(10800, inspect.getWait());
        assertTrue(sent.get(1) instanceof PrepareVolumeFileCopyCommand);
        StartVolumeExportCommand export = (StartVolumeExportCommand)sent.get(2);
        assertEquals("src-path", export.getPath());
        assertEquals("copy-uuid", export.getName());
        assertEquals("the export admits exactly the destination's identity", DN, export.getClientIdentity());
        assertEquals(10800 + KvmLocalHostToHostDataMotionStrategy.EXPORT_GRACE_SECONDS, export.getLifetime());
        CopyVolumeFileFromHostCommand pull = (CopyVolumeFileFromHostCommand)sent.get(3);
        assertEquals("10.0.0.11", pull.getSourceAddress());
        assertEquals(10810, pull.getSourcePort());
        assertEquals("copy-uuid", pull.getExportName());
        assertEquals("copy-uuid", pull.getPath());
        assertEquals(25624576L, pull.getExpectedLength());
        assertEquals(SHA, pull.getExpectedSha256());
        assertEquals("the overlay is pointed at the destination's base", "tmpl-dest", pull.getBackingInstallPath());
        assertEquals("a template copied from secondary storage is stored as qcow2 on a file system pool, whatever its registered format", "qcow2",
                pull.getBackingFormat());
        assertEquals("the pull itself is bounded by copy.volume.wait", 10800, pull.getTransferTimeout());
        assertEquals("the command also covers reading back, rebasing and checking", 10800 + KvmLocalHostToHostDataMotionStrategy.verifySeconds(25624576L),
                pull.getWait());
        assertEquals(600 + 1024, KvmLocalHostToHostDataMotionStrategy.verifySeconds(100L << 30));
        assertEquals("copy-uuid", ((StopVolumeExportCommand)sent.get(4)).getName());

        VolumeObjectTO copied = (VolumeObjectTO)((CopyCmdAnswer)result.getAnswer()).getNewData();
        assertEquals("copy-uuid", copied.getPath());
        assertEquals(ImageFormat.QCOW2, copied.getFormat());
        assertEquals(Long.valueOf(4294967296L), copied.getSize());
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
    public void failedPullIsAbortedAndTheExportStopped() throws Exception {
        VolumeVO destRow = mock(VolumeVO.class);
        when(volumeDao.findById(20L)).thenReturn(destRow);
        agents(command -> command instanceof CopyVolumeFileFromHostCommand
                ? new CopyVolumeFileFromHostAnswer((CopyVolumeFileFromHostCommand)command, "SHA-256 mismatch") : happyPath(command, 0));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", local(SRC_POOL, SRC_HOST)), volume(20L, "copy-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("SHA-256 mismatch"));
        assertEquals(List.of(SRC_HOST, DEST_HOST, SRC_HOST, DEST_HOST, DEST_HOST, SRC_HOST), sentTo);
        AbortVolumeFileCopyCommand abort = (AbortVolumeFileCopyCommand)sent.get(4);
        assertEquals("copy-uuid", abort.getPath());
        assertEquals("copy-uuid", abort.getExportName());
        assertTrue(sent.get(5) instanceof StopVolumeExportCommand);
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
        assertFalse(sent.stream().anyMatch(command -> command instanceof StartVolumeExportCommand));
    }

    @Test
    public void templateBaseIsCopiedUnderTheTemplateUuid() throws Exception {
        agents(command -> happyPath(command, 0));
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);

        CopyCommandResult result = copy(template(TEMPLATE_ID, "tmpl-uuid", "tmpl-src", local(SRC_POOL, SRC_HOST)), template(TEMPLATE_ID, "tmpl-uuid", null, destStore));

        assertTrue(result.getResult(), result.isSuccess());
        assertEquals("tmpl-src", ((InspectVolumeFileCommand)sent.get(0)).getPath());
        CopyVolumeFileFromHostCommand pull = (CopyVolumeFileFromHostCommand)sent.get(3);
        assertEquals("tmpl-uuid", pull.getPath());
        assertNull(pull.getBackingInstallPath());
        assertEquals("template-" + TEMPLATE_ID + "-" + DEST_POOL, pull.getExportName());
        TemplateObjectTO copied = (TemplateObjectTO)((CopyCmdAnswer)result.getAnswer()).getNewData();
        assertEquals("tmpl-uuid", copied.getPath());
        assertEquals("the base's size is its allocation on the destination pool, as for any base", Long.valueOf(ALLOCATED), copied.getSize());
        verify(volumeDao, never()).update(anyLong(), any());
        verify(agentManager, never()).send(eq(DEST_HOST), any(AbortVolumeFileCopyCommand.class));
    }

    private void directDownloadTemplate() {
        registeredTemplate(true, ImageFormat.QCOW2);
    }

    @Test
    public void rawRegisteredTemplateBaseIsInspectedAsTheQcow2ItIsStoredAs() throws Exception {
        agents(command -> happyPath(command, 0));
        TemplateInfo source = template(TEMPLATE_ID, "tmpl-uuid", "tmpl-src", local(SRC_POOL, SRC_HOST));
        when(source.getFormat()).thenReturn(ImageFormat.RAW);

        CopyCommandResult result = copy(source, template(TEMPLATE_ID, "tmpl-uuid", null, local(DEST_POOL, DEST_HOST)));

        assertTrue(result.getResult(), result.isSuccess());
        assertEquals("qcow2", ((InspectVolumeFileCommand)sent.get(0)).getFormat());
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
                return new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, 25624576L, SHA, "/var/lib/libvirt/images/tmpl-src",
                        "raw", 1);
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
        CopyVolumeFileFromHostCommand pull = (CopyVolumeFileFromHostCommand)sent.stream().filter(c -> c instanceof CopyVolumeFileFromHostCommand).findFirst().get();
        assertEquals("raw", pull.getBackingFormat());
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
                ? new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 4294967296L, 25624576L, SHA, "/var/lib/libvirt/images/tmpl-src", "raw", 1)
                : happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("records its base as raw"));
        assertFalse("nothing is exported", sent.stream().anyMatch(command -> command instanceof StartVolumeExportCommand));
    }

    @Test
    public void directDownloadBaseThatDiffersOnTheDestinationRefused() throws Exception {
        PrimaryDataStore srcStore = local(SRC_POOL, SRC_HOST);
        PrimaryDataStore destStore = local(DEST_POOL, DEST_HOST);
        when(volumeDao.findById(20L)).thenReturn(mock(VolumeVO.class));
        sourceBase("tmpl-src");
        when(volumeService.ensureTemplateOnPool(TEMPLATE_ID, destStore, srcStore)).thenReturn("tmpl-dest");
        directDownloadTemplate();
        agents(command -> command instanceof InspectVolumeFileCommand && "tmpl-dest".equals(((InspectVolumeFileCommand)command).getPath())
                ? new InspectVolumeFileAnswer((InspectVolumeFileCommand)command, "qcow2", 2147483648L, 101056512L, OTHER_SHA, null, null, 0) : happyPath(command, 1));

        CopyCommandResult result = copy(volume(10L, "src-uuid", "src-path", srcStore), volume(20L, "copy-uuid", null, destStore));

        assertFalse(result.isSuccess());
        assertTrue(result.getResult(), result.getResult().contains("different bytes"));
        assertFalse("nothing is exported", sent.stream().anyMatch(command -> command instanceof StartVolumeExportCommand));
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
    }
}
