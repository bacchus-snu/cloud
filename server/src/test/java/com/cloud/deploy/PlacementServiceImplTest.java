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
package com.cloud.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.cloudstack.api.command.admin.host.UpdateHostMoveRateCmd;
import org.apache.cloudstack.api.command.user.vm.CheckPlacementCmd;
import org.apache.cloudstack.api.response.PlacementResponse;
import org.apache.cloudstack.api.response.PlacementVolumeResponse;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.security.keys.KeysManager;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.codec.binary.Hex;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.VolumeMoveRequiredException;
import com.cloud.gpu.GpuCardVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.GpuCardDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.resource.ResourceManager;
import com.cloud.server.StatsCollector;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeStats;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class PlacementServiceImplTest {

    private static final long ZONE = 1L;
    private static final long CLUSTER = 1L;
    private static final long POD = 1L;
    private static final long ACCOUNT = 5L;
    private static final long DOMAIN = 7L;
    private static final long VM = 100L;
    private static final long OFFERING = 20L;
    private static final long TEMPLATE = 30L;
    private static final long DISK_OFFERING = 40L;
    private static final long HOST_A = 1L;
    private static final long HOST_B = 2L;
    private static final long HOST_C = 3L;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long NOW = 1_800_000_000L;

    @Spy
    @InjectMocks
    private PlacementServiceImpl service = new PlacementServiceImpl();

    @Mock
    private DeploymentPlanningManager deploymentPlanningManager;
    @Mock
    private CapacityManager capacityManager;
    @Mock
    private ResourceManager resourceManager;
    @Mock
    private StorageManager storageManager;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private UserVmManager userVmManager;
    @Mock
    private AccountManager accountManager;
    @Mock
    private KeysManager keysManager;
    @Mock
    private SnapshotHelper snapshotHelper;
    @Mock
    private StatsCollector statsCollector;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private StoragePoolHostDao storagePoolHostDao;
    @Mock
    private VMSnapshotDao vmSnapshotDao;
    @Mock
    private VgpuProfileDao vgpuProfileDao;
    @Mock
    private GpuCardDao gpuCardDao;

    private final ConfigDepotImpl configDepot = mock(ConfigDepotImpl.class);

    /** The host side of admission: free pool cores, free memory, free GPU groups and free local pool bytes per host. */
    private final Map<Long, HostVO> hosts = new LinkedHashMap<>();
    private final Map<Long, Integer> freeCores = new HashMap<>();
    private final Map<Long, Long> freeMemory = new HashMap<>();
    private final Map<Long, Integer> freeGroups = new HashMap<>();
    private final Map<Long, Long> freePoolBytes = new HashMap<>();
    private final Map<Long, StoragePoolVO> pools = new HashMap<>();
    private final Map<Long, VolumeVO> volumes = new LinkedHashMap<>();
    private final Map<String, Long> physical = new HashMap<>();
    private long remainingStorageQuota = -1;
    private boolean volumeCountShort;

    private UserVmVO vm;
    private ServiceOfferingVO offering;
    private Account owner;

    @Before
    public void setUp() throws Exception {
        ConfigKey.init(configDepot);
        doReturn(NOW).when(service).nowSeconds();
        when(keysManager.getHashKey()).thenReturn("shared-secret-of-the-management-servers");

        for (long id : new long[] {HOST_A, HOST_B, HOST_C}) {
            host(id);
        }
        owner = mock(Account.class);
        when(owner.getId()).thenReturn(ACCOUNT);
        when(owner.getDomainId()).thenReturn(DOMAIN);
        when(owner.getType()).thenReturn(Account.Type.NORMAL);
        when(accountManager.getAccount(ACCOUNT)).thenReturn(owner);

        offering = mock(ServiceOfferingVO.class);
        when(offering.getId()).thenReturn(OFFERING);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2000);
        when(offering.getRamSize()).thenReturn(8192);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING);
        when(serviceOfferingDao.findById(OFFERING)).thenReturn(offering);
        when(serviceOfferingDao.findById(VM, OFFERING)).thenReturn(offering);

        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getId()).thenReturn(TEMPLATE);
        when(template.getName()).thenReturn("debian-13");
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE)).thenReturn(template);
        when(templateDao.findById(TEMPLATE)).thenReturn(template);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(diskOffering.getId()).thenReturn(DISK_OFFERING);
        when(diskOffering.isUseLocalStorage()).thenReturn(true);
        when(diskOfferingDao.findById(DISK_OFFERING)).thenReturn(diskOffering);
        when(diskOfferingDao.findByIdIncludingRemoved(DISK_OFFERING)).thenReturn(diskOffering);

        vm = new UserVmVO(VM, "i-5-100-VM", "vm", TEMPLATE, HypervisorType.KVM, 1L, false, false, DOMAIN, ACCOUNT, 1L, OFFERING, null, null, null, "vm");
        vm.setDataCenterId(ZONE);
        ReflectionTestUtils.setField(vm, "state", VirtualMachine.State.Stopped);
        when(vmInstanceDao.findById(VM)).thenReturn(vm);
        when(volumeDao.findUsableVolumesForInstance(VM)).thenAnswer(i -> volumes.values().stream()
                .filter(v -> v.getInstanceId() != null && v.getInstanceId() == VM).collect(Collectors.toList()));
        when(volumeDao.findById(anyLong())).thenAnswer(i -> volumes.get((Long)i.getArgument(0)));

        // admission of compute: the allocators admit a host when the capacity manager says the offering fits it and,
        // for a vGPU offering, the resource manager finds a usable GPU group on it
        when(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(any(Host.class), any(), anyBoolean())).thenAnswer(i -> {
            Host host = i.getArgument(0);
            com.cloud.offering.ServiceOffering requested = i.getArgument(1);
            if (CapacityManager.isHostCapacityExempt(requested)) {
                return new Pair<>(true, true);
            }
            return new Pair<>(true, coresFit(host.getId(), requested.getCpu()) && freeMemory.get(host.getId()) >= requested.getRamSize() * 1024L * 1024L);
        });
        when(capacityManager.checkIfHostHasPoolCores(any(Host.class), anyInt())).thenAnswer(i -> coresFit(((Host)i.getArgument(0)).getId(), i.getArgument(1)));
        when(capacityManager.getFreeCapacity(any(Host.class))).thenAnswer(i -> {
            long id = ((Host)i.getArgument(0)).getId();
            return new CapacityManager.HostFreeCapacity(1_000_000L, freeMemory.get(id), 64, 2000, freeCores.get(id));
        });
        when(resourceManager.isGPUDeviceAvailable(any(), any(Host.class), anyLong())).thenAnswer(i -> freeGroups.getOrDefault(((Host)i.getArgument(1)).getId(), 0) > 0);
        when(resourceManager.listAllUpAndEnabledHosts(Host.Type.Routing, null, null, ZONE)).thenAnswer(i -> new ArrayList<>(hosts.values()));
        when(deploymentPlanningManager.listHostsAdmittingVm(any(VirtualMachineProfile.class), any(DataCenterDeployment.class))).thenAnswer(i -> {
            VirtualMachineProfile profile = i.getArgument(0);
            List<Host> admitted = new ArrayList<>();
            for (HostVO host : hosts.values()) {
                boolean gpu = !CapacityManager.isHostCapacityExempt(profile.getServiceOffering())
                        || resourceManager.isGPUDeviceAvailable(profile.getServiceOffering(), host, profile.getId());
                if (gpu && capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, profile.getServiceOffering(), true).second()) {
                    admitted.add(host);
                }
            }
            return admitted;
        });
        // admission of storage: the storage allocators take a pool for one disk, and the pool space check takes all
        // the disks a pool receives together
        when(deploymentPlanningManager.listPoolsForDisk(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class))).thenAnswer(i -> {
            DiskProfile disk = i.getArgument(0);
            long hostId = ((DeploymentPlan)i.getArgument(2)).getHostId();
            StoragePoolVO pool = pools.get(hostId);
            return freePoolBytes.get(pool.getId()) >= disk.getSize() ? Collections.singletonList(pool) : Collections.emptyList();
        });
        when(storageManager.storagePoolHasEnoughSpace(anyList(), any(StoragePool.class), any())).thenAnswer(i -> {
            List<Pair<Volume, DiskProfile>> disks = i.getArgument(0);
            StoragePool pool = i.getArgument(1);
            long asked = 0;
            for (Pair<Volume, DiskProfile> disk : disks) {
                Volume volume = disk.first();
                if (volume == null || volume.getState() != Volume.State.Ready || volume.getPoolId() != pool.getId()) {
                    asked += disk.second().getSize();
                }
            }
            return asked <= freePoolBytes.get(pool.getId());
        });
        when(storagePoolHostDao.listByPoolId(anyLong())).thenAnswer(i -> {
            long poolId = i.getArgument(0);
            return Collections.singletonList(new StoragePoolHostVO(poolId, poolId - 10, "/var/lib/libvirt/images"));
        });
        when(storagePoolDao.findById(anyLong())).thenAnswer(i -> pools.values().stream().filter(p -> p.getId() == (Long)i.getArgument(0)).findFirst().orElse(null));

        when(resourceLimitService.getResourceLimitStorageTagsForResourceCountOperation(anyBoolean(), any(), isNull())).thenReturn(Arrays.asList((String)null));
        when(resourceLimitService.getRemainingStorageQuota(owner)).thenAnswer(i -> remainingStorageQuota);
        doAnswer(i -> {
            if (volumeCountShort) {
                throw new ResourceAllocationException("volume limit", ResourceType.volume);
            }
            return null;
        }).when(resourceLimitService).checkResourceLimit(any(Account.class), eq(ResourceType.volume), anyLong());
        when(statsCollector.getVolumeStats(any())).thenAnswer(i -> {
            Long used = physical.get((String)i.getArgument(0));
            if (used == null) {
                return null;
            }
            VolumeStats stats = mock(VolumeStats.class);
            when(stats.getPhysicalSize()).thenReturn(used);
            return stats;
        });
    }

    @After
    public void tearDown() {
        ConfigKey.init(null);
    }

    private boolean coresFit(long hostId, int vcpus) {
        return freeCores.get(hostId) >= (vcpus + 1) / 2;
    }

    private HostVO host(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getName()).thenReturn("host" + (char)('A' + id - 1));
        when(host.getDataCenterId()).thenReturn(ZONE);
        when(host.getPodId()).thenReturn(POD);
        when(host.getClusterId()).thenReturn(CLUSTER);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getType()).thenReturn(Host.Type.Routing);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.checkHostServiceOfferingTags(any())).thenReturn(true);
        when(hostDao.findById(id)).thenReturn(host);
        hosts.put(id, host);
        freeCores.put(id, 16);
        freeMemory.put(id, 256 * GIB);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        long poolId = id + 10;
        when(pool.getId()).thenReturn(poolId);
        when(pool.getName()).thenReturn("host" + (char)('A' + id - 1) + " local storage");
        when(pool.getScope()).thenReturn(ScopeType.HOST);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.Filesystem);
        when(pool.getClusterId()).thenReturn(CLUSTER);
        pools.put(id, pool);
        freePoolBytes.put(poolId, 1000 * GIB);
        return host;
    }

    private VolumeVO volume(long id, Volume.Type type, long hostId, long sizeGiB) {
        VolumeVO volume = new VolumeVO(type, type == Volume.Type.ROOT ? "ROOT-100" : "DATA-" + id, ZONE, DOMAIN, ACCOUNT, DISK_OFFERING,
                Storage.ProvisioningType.THIN, sizeGiB * GIB, null, null, null);
        ReflectionTestUtils.setField(volume, "id", id);
        volume.setState(Volume.State.Ready);
        volume.setPoolId(pools.get(hostId).getId());
        volume.setInstanceId(VM);
        volume.setPath("path-" + id);
        if (type == Volume.Type.ROOT) {
            volume.setTemplateId(TEMPLATE);
        }
        volumes.put(id, volume);
        return volume;
    }

    private void full(long hostId) {
        freeCores.put(hostId, 0);
    }

    private PlacementVerdict start() {
        return service.check(PlacementRequest.start(VM, null, ACCOUNT));
    }

    private void setting(ConfigKey<?> key, ConfigKey.Scope scope, Long id, String value) {
        when(configDepot.getConfigStringValue(key.key(), scope, id)).thenReturn(value);
    }

    @Test
    public void verdictHereWhenCurrentHostFits() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.here, verdict.getVerdict());
        assertTrue(verdict.getVolumes().isEmpty());
        assertNull(verdict.getToken());
        assertNull(verdict.getReason());
    }

    @Test
    public void verdictMoveListsSizesAndEstimate() {
        volume(201, Volume.Type.ROOT, HOST_A, 20);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        physical.put("path-201", 10 * GIB);
        physical.put("path-202", 10 * GIB);
        full(HOST_A);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.move, verdict.getVerdict());
        assertEquals(Arrays.asList(201L, 202L), verdict.getVolumeIds());
        assertEquals(20 * GIB, verdict.getVolumes().get(0).getSize());
        assertEquals(10 * GIB, verdict.getVolumes().get(0).getPhysicalSize());
        assertEquals(20 * GIB, verdict.getBytesToMove());
        // ceil(2 x 10 GiB x 8 bits / (800 x 10^6 bit/s)) = ceil(214.75)
        assertEquals(215, verdict.getEstimatedSeconds());
        assertTrue(verdict.isPreviousCopyKept());
        assertNotNull(verdict.getToken());
        PlacementResponse response = new PlacementResponse(verdict);
        assertEquals("move", response.getVerdict());
        assertEquals(Long.valueOf(20 * GIB), response.getBytesToMove());
        assertEquals(Long.valueOf(215), response.getEstimatedSeconds());
        assertEquals(Boolean.TRUE, response.getPreviousCopyKept());
        PlacementVolumeResponse first = response.getVolumes().get(0);
        assertEquals(volumes.get(201L).getUuid(), first.getId());
        assertEquals("ROOT-100", first.getName());
        assertEquals(Long.valueOf(10 * GIB), first.getPhysicalSize());
    }

    @Test
    public void estimateUsesSlowerHostMoveRate() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        physical.put("path-201", 10 * GIB);
        full(HOST_A);
        hosts.remove(HOST_C);
        when(hostDetailsDao.findDetail(HOST_A, PlacementService.HOST_MOVE_RATE_DETAIL)).thenReturn(new DetailVO(HOST_A, "move.mbps", "400"));
        when(hostDetailsDao.findDetail(HOST_B, PlacementService.HOST_MOVE_RATE_DETAIL)).thenReturn(new DetailVO(HOST_B, "move.mbps", "900"));

        PlacementVerdict verdict = start();

        // ceil(10 GiB x 8 / (400 x 10^6)) = ceil(214.75); at 900 it would be 96
        assertEquals(215, verdict.getEstimatedSeconds());
        setting(PlacementService.VolumeMoveEstimateMbps, ConfigKey.Scope.Global, null, "1600");
        when(hostDetailsDao.findDetail(HOST_A, PlacementService.HOST_MOVE_RATE_DETAIL)).thenReturn(null);
        assertEquals("a host without a rate counts at volume.move.estimate.mbps", 96, start().getEstimatedSeconds());
    }

    @Test
    public void verdictNoneNamesGpuReason() {
        when(offering.getVgpuProfileId()).thenReturn(9L);
        when(offering.getGpuCount()).thenReturn(2);
        VgpuProfileVO profile = mock(VgpuProfileVO.class);
        when(profile.getCardId()).thenReturn(3L);
        when(vgpuProfileDao.findById(9L)).thenReturn(profile);
        GpuCardVO card = mock(GpuCardVO.class);
        when(card.getName()).thenReturn("A100-SXM4-80GB");
        when(gpuCardDao.findById(3L)).thenReturn(card);
        volume(201, Volume.Type.ROOT, HOST_A, 10);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals("gpu group A100-SXM4-80GB x2", verdict.getReason());
        freeGroups.put(HOST_A, 1);
        assertEquals(PlacementVerdict.Verdict.here, start().getVerdict());
    }

    @Test
    public void verdictAnyHostForNewVm() {
        when(userVmManager.configureCustomRootDiskSize(any(), any(), any(), any())).thenReturn(20 * GIB);

        PlacementVerdict verdict = service.check(PlacementRequest.deploy(TEMPLATE, OFFERING, ZONE, ACCOUNT).withDataDiskSizesGiB(Arrays.asList(100L)));

        assertEquals(PlacementVerdict.Verdict.anyhost, verdict.getVerdict());
        assertNull(verdict.getToken());
        for (long id : new long[] {HOST_A, HOST_B, HOST_C}) {
            freePoolBytes.put(id + 10, 110 * GIB);
        }
        assertEquals("root and data disk together do not fit any local pool", PlacementVerdict.Verdict.none,
                service.check(PlacementRequest.deploy(TEMPLATE, OFFERING, ZONE, ACCOUNT).withDataDiskSizesGiB(Arrays.asList(100L))).getVerdict());
    }

    @Test
    public void verdictNeverContainsHostNames() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        List<PlacementVerdict> verdicts = new ArrayList<>();
        verdicts.add(start());
        full(HOST_A);
        verdicts.add(start());
        PlacementVerdict move = verdicts.get(1);
        full(HOST_B);
        full(HOST_C);
        verdicts.add(start());
        List<String> texts = new ArrayList<>();
        for (PlacementVerdict verdict : verdicts) {
            texts.add(verdict.describe());
            texts.add(String.valueOf(verdict.getReason()));
            texts.add(String.valueOf(verdict.getToken()));
            PlacementResponse response = new PlacementResponse(verdict);
            texts.add(String.valueOf(response.getVerdict()) + response.getReason() + response.getPlacementToken());
            for (PlacementVolumeResponse volume : response.getVolumes()) {
                texts.add(volume.getId() + volume.getName());
            }
        }
        freeCores.put(HOST_B, 16);
        texts.add(assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), false, null)).getMessage());
        texts.add(assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, "1-00")).getMessage());
        freeCores.put(HOST_B, 0);
        texts.add(assertThrows(InsufficientServerCapacityException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, move.getToken()))
                .getMessage());
        for (String text : texts) {
            for (HostVO host : hosts.values()) {
                assertFalse(text, text.contains(host.getName()));
            }
            for (StoragePoolVO pool : pools.values()) {
                assertFalse(text, text.contains(pool.getName()));
            }
        }
        assertEquals(PlacementVerdict.Verdict.move, move.getVerdict());
        assertTrue("the approval reveals its expiry and an opaque code only", move.getToken().matches(NOW + 900 + "-[0-9a-f]{64}"));
    }

    @Test
    public void checkAndAdmissionAgreeAtBoundary() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        hosts.remove(HOST_B);
        hosts.remove(HOST_C);
        // CPU cores: 4 vCPUs take 2 whole cores
        freeCores.put(HOST_A, 2);
        assertAgrees(PlacementVerdict.Verdict.here, null);
        freeCores.put(HOST_A, 1);
        assertAgrees(PlacementVerdict.Verdict.none, PlacementServiceImpl.REASON_CPU_POOL);
        freeCores.put(HOST_A, 2);
        // memory: 8 GiB
        freeMemory.put(HOST_A, 8 * GIB);
        assertAgrees(PlacementVerdict.Verdict.here, null);
        freeMemory.put(HOST_A, 8 * GIB - 1);
        assertAgrees(PlacementVerdict.Verdict.none, PlacementServiceImpl.REASON_MEMORY);
        freeMemory.put(HOST_A, 8 * GIB);
        // storage: a data disk not created yet asks its 30 GiB of the local pool
        VolumeVO data = volume(202, Volume.Type.DATADISK, HOST_A, 30);
        data.setState(Volume.State.Allocated);
        data.setPoolId(null);
        freePoolBytes.put(11L, 30 * GIB);
        assertAgrees(PlacementVerdict.Verdict.here, null);
        freePoolBytes.put(11L, 30 * GIB - 1);
        assertAgrees(PlacementVerdict.Verdict.none, PlacementServiceImpl.REASON_LOCAL_STORAGE);
        freePoolBytes.put(11L, 30 * GIB);
        // a GPU group
        when(offering.getVgpuProfileId()).thenReturn(9L);
        when(offering.getGpuCount()).thenReturn(1);
        freeGroups.put(HOST_A, 1);
        assertAgrees(PlacementVerdict.Verdict.here, null);
        freeGroups.put(HOST_A, 0);
        assertAgrees(PlacementVerdict.Verdict.none, "gpu group unknown x1");
    }

    /** The verdict for the Instance's host is here exactly when the predicates that admit it at its start admit it. */
    private void assertAgrees(PlacementVerdict.Verdict expected, String reason) {
        HostVO host = hosts.get(HOST_A);
        boolean compute = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, offering, true).second()
                && (!CapacityManager.isHostCapacityExempt(offering) || resourceManager.isGPUDeviceAvailable(offering, host, VM));
        List<Pair<Volume, DiskProfile>> disks = new ArrayList<>();
        for (VolumeVO volume : volumes.values()) {
            disks.add(new Pair<>(volume, new DiskProfile(volume, mock(DiskOfferingVO.class), HypervisorType.KVM)));
        }
        boolean storage = storageManager.storagePoolHasEnoughSpace(disks, pools.get(HOST_A), CLUSTER);
        PlacementVerdict verdict = start();
        assertEquals(expected, verdict.getVerdict());
        assertEquals(expected == PlacementVerdict.Verdict.here, compute && storage);
        assertEquals(reason, verdict.getReason());
    }

    @Test
    public void hostRankingPrefersMostLocalBytes() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_B, 30);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.move, verdict.getVerdict());
        assertEquals("the host holding the 30 GiB disk takes the 10 GiB one", Long.valueOf(HOST_B), verdict.getDestinationHostId());
        assertEquals(Collections.singletonList(201L), verdict.getVolumeIds());
        assertEquals(Long.valueOf(12L), verdict.getDestinationPoolId());
        volumes.get(201L).setSize(40 * GIB);
        assertEquals(Long.valueOf(HOST_A), start().getDestinationHostId());
    }

    @Test
    public void hostRankingTiesGoToTheAllocatorOrder() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        LinkedHashMap<Long, HostVO> reordered = new LinkedHashMap<>();
        reordered.put(HOST_C, hosts.get(HOST_C));
        reordered.put(HOST_B, hosts.get(HOST_B));
        reordered.put(HOST_A, hosts.get(HOST_A));
        hosts.clear();
        hosts.putAll(reordered);

        assertEquals("ties go to the allocator's order", Long.valueOf(HOST_C), start().getDestinationHostId());
    }

    @Test
    public void startWithFlagMigratesRootAndDataToChosenHost() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        full(HOST_A);
        PlacementVerdict checked = start();

        PlacementVerdict approved = service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, checked.getToken());

        assertEquals(Arrays.asList(201L, 202L), approved.getVolumeIds());
        assertEquals(checked.getDestinationHostId(), approved.getDestinationHostId());
        assertEquals(checked.getDestinationPoolId(), approved.getDestinationPoolId());
    }

    @Test
    public void startWithoutFlagThrowsMoveRequiredWhenOnlyOtherHostFits() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        assertNull("no move is needed while the Instance's host fits", service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), false, null));
        full(HOST_A);

        VolumeMoveRequiredException e = assertThrows(VolumeMoveRequiredException.class,
                () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), false, null));

        assertEquals(PlacementVerdict.Verdict.move, e.getVerdict().getVerdict());
        assertEquals(Collections.singletonList(201L), e.getVerdict().getVolumeIds());
        assertTrue(e.getMessage(), e.getMessage().contains("ROOT-100"));
    }

    @Test
    public void cliStartWithFlagWithoutTokenRefused() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);

        VolumeMoveRequiredException e = assertThrows(VolumeMoveRequiredException.class,
                () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, null));

        assertEquals(PlacementVerdict.Verdict.move, e.getVerdict().getVerdict());
        assertNotNull("the exception carries the new verdict to approve", e.getVerdict().getToken());
    }

    @Test
    public void startWithFlagButStaleTokenRefused() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        full(HOST_A);
        String token = start().getToken();
        volumes.get(202L).setPoolId(13L);

        VolumeMoveRequiredException e = assertThrows(VolumeMoveRequiredException.class,
                () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, token));

        assertEquals(PlacementVerdict.Verdict.move, e.getVerdict().getVerdict());
        assertNotEquals(token, e.getVerdict().getToken());
    }

    @Test
    public void startWithFlagWhenDestinationFillsLeavesVolumesOnOnePool() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        full(HOST_A);
        hosts.remove(HOST_C);
        PlacementVerdict checked = start();
        assertEquals(Long.valueOf(HOST_B), checked.getDestinationHostId());
        // B's pool still takes either disk, but not both
        freePoolBytes.put(12L, 55 * GIB);

        assertThrows(InsufficientServerCapacityException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, checked.getToken()));

        assertEquals(PlacementVerdict.Verdict.none, start().getVerdict());
        assertEquals(PlacementServiceImpl.REASON_LOCAL_STORAGE, start().getReason());
        assertTrue(volumes.values().stream().allMatch(v -> v.getPoolId() == 11L));
    }

    @Test
    public void tokenBindsDestinationVolumesVmIdAndExpiry() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        hosts.remove(HOST_C);
        PlacementVerdict checked = start();
        String token = checked.getToken();
        PlacementServiceImpl.Candidate onB = candidate(HOST_B, 201L);
        long expiry = PlacementServiceImpl.expiryOf(token);

        assertEquals(NOW + 900, expiry);
        assertTrue(service.tokenMatches(token, onB, VM, expiry));
        assertFalse("another destination", service.tokenMatches(token, candidate(HOST_C, 201L), VM, expiry));
        assertFalse("another Instance", service.tokenMatches(token, onB, 101L, expiry));
        assertFalse("no Instance", service.tokenMatches(token, onB, null, expiry));
        assertFalse("another expiry", service.tokenMatches(token, onB, VM, expiry + 1));
        volumes.get(201L).setSize(11 * GIB);
        assertFalse("another size", service.tokenMatches(token, candidate(HOST_B, 201L), VM, expiry));
        volumes.get(201L).setSize(10 * GIB);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        assertFalse("other volumes", service.tokenMatches(token, candidate(HOST_B, 201L, 202L), VM, expiry));
        String forged = expiry + "-" + Hex.encodeHexString(MessageDigest.getInstance("SHA-256")
                .digest(("host=2\ninstance=100\nvolumes=201:11:" + 10 * GIB + "\nexpires=" + expiry).getBytes(StandardCharsets.UTF_8)));
        assertFalse("a hash anyone can compute is no approval", service.tokenMatches(forged, onB, VM, expiry));
        when(keysManager.getHashKey()).thenReturn("another-secret");
        assertFalse("the approval is keyed by the servers' secret", service.tokenMatches(token, onB, VM, expiry));
    }

    private PlacementServiceImpl.Candidate candidate(long hostId, Long... volumeIds) {
        List<VolumeVO> moved = Arrays.stream(volumeIds).map(volumes::get).collect(Collectors.toList());
        return new PlacementServiceImpl.Candidate(hosts.getOrDefault(hostId, host(hostId)), pools.get(hostId), moved, 0);
    }

    @Test
    public void expiredTokenRefused() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        String token = start().getToken();
        doReturn(NOW + 900).when(service).nowSeconds();
        assertNotNull(service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, token));

        doReturn(NOW + 901).when(service).nowSeconds();

        assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, token));
    }

    @Test
    public void tokenLifetimeIsASetting() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        assertEquals("placement.token.expiry.seconds", PlacementService.PlacementTokenExpirySeconds.key());
        assertEquals("900", PlacementService.PlacementTokenExpirySeconds.defaultValue());
        assertTrue(PlacementService.PlacementTokenExpirySeconds.isDynamic());
        assertEquals(ConfigKey.Scope.Global, PlacementService.PlacementTokenExpirySeconds.getScopes().get(0));
        assertTrue(Arrays.asList(service.getConfigKeys()).contains(PlacementService.PlacementTokenExpirySeconds));
        String issuedBefore = start().getToken();

        setting(PlacementService.PlacementTokenExpirySeconds, ConfigKey.Scope.Global, null, "60");
        String issuedAfter = start().getToken();

        assertEquals(Long.valueOf(NOW + 900), PlacementServiceImpl.expiryOf(issuedBefore));
        assertEquals(Long.valueOf(NOW + 60), PlacementServiceImpl.expiryOf(issuedAfter));
        doReturn(NOW + 61).when(service).nowSeconds();
        assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, issuedAfter));
        assertNotNull("a change applies to the next approval only", service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, issuedBefore));
    }

    @Test
    public void moveRejectsPrimaryOnlySnapshots() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        doThrow(new CloudRuntimeException("snapshots on primary only")).when(snapshotHelper).checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volumes.get(201L),
                HypervisorType.KVM);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_PRIMARY_SNAPSHOTS, verdict.getReason());
    }

    @Test
    public void moveRejectsVmSnapshots() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        when(vmSnapshotDao.findByVm(VM)).thenReturn(Collections.singletonList(mock(VMSnapshotVO.class)));

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_INSTANCE_SNAPSHOTS, verdict.getReason());
    }

    @Test
    public void moveRejectsEncryptedVolume() {
        volume(201, Volume.Type.ROOT, HOST_A, 10).setPassphraseId(3L);
        full(HOST_A);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_ENCRYPTED_VOLUME, verdict.getReason());
    }

    @Test
    public void moveVerdictNoneWhenStorageQuotaShort() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        full(HOST_A);
        remainingStorageQuota = 60 * GIB;
        assertEquals(PlacementVerdict.Verdict.move, start().getVerdict());

        remainingStorageQuota = 60 * GIB - 1;
        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_STORAGE_QUOTA, verdict.getReason());
    }

    @Test
    public void moveVerdictNoneWhenVolumeCountShort() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        volume(202, Volume.Type.DATADISK, HOST_A, 50);
        full(HOST_A);
        volumeCountShort = true;

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_STORAGE_QUOTA, verdict.getReason());
        verify(resourceLimitService, atLeastOnce()).checkResourceLimit(owner, ResourceType.volume, 2L);
    }

    @Test
    public void moveVerdictNeedsNoPreviousCopyQuotaWhenKeepSourceFalse() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        remainingStorageQuota = 0;
        volumeCountShort = true;
        assertEquals(PlacementVerdict.Verdict.none, start().getVerdict());

        setting(VolumeOrchestrationService.VolumeMoveKeepSource, ConfigKey.Scope.Domain, DOMAIN, "false");
        clearInvocations(resourceLimitService);
        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.move, verdict.getVerdict());
        assertFalse(verdict.isPreviousCopyKept());
        verify(resourceLimitService, never()).checkResourceLimit(any(Account.class), eq(ResourceType.volume), anyLong());
        verify(resourceLimitService, never()).getRemainingStorageQuota(any(Account.class));
    }

    @Test
    public void deployFromVolumeNeedsApprovalToMove() throws Exception {
        VolumeVO kept = volume(201, Volume.Type.DATADISK, HOST_A, 10);
        kept.setInstanceId(null);
        kept.setTemplateId(TEMPLATE);
        full(HOST_A);

        PlacementVerdict checked = service.check(PlacementRequest.deployFromVolume(201L, OFFERING, ACCOUNT));

        assertEquals(PlacementVerdict.Verdict.move, checked.getVerdict());
        assertEquals(Collections.singletonList(201L), checked.getVolumeIds());
        assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.deployFromVolume(201L, OFFERING, ACCOUNT), false, null));
        assertNotNull(service.approvedMove(PlacementRequest.deployFromVolume(201L, OFFERING, ACCOUNT), true, checked.getToken()));
        // the Instance created from the volume starts with the approval, which binds no Instance
        kept.setInstanceId(VM);
        assertNotNull(service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT).approvedBeforeTheInstanceExisted(), true, checked.getToken()));
        assertThrows(VolumeMoveRequiredException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, checked.getToken()));
    }

    @Test
    public void checkPlacementAcceptsOnlyTheFourCombinations() {
        Account caller = owner;
        VolumeVO detached = volume(201, Volume.Type.DATADISK, HOST_A, 10);
        detached.setInstanceId(null);
        detached.setTemplateId(TEMPLATE);
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(ZONE);
        when(dataCenterDao.findById(ZONE)).thenReturn(zone);

        assertEquals(PlacementRequest.Kind.START, service.requestOf(cmd(VM, null, null, null, null), caller).getKind());
        assertEquals(PlacementRequest.Kind.START, service.requestOf(cmd(VM, null, null, OFFERING, null), caller).getKind());
        assertEquals(PlacementRequest.Kind.ATTACH, service.requestOf(cmd(VM, 201L, null, null, null), caller).getKind());
        assertEquals(PlacementRequest.Kind.DEPLOY_FROM_VOLUME, service.requestOf(cmd(null, 201L, null, OFFERING, null), caller).getKind());
        assertEquals(PlacementRequest.Kind.DEPLOY, service.requestOf(cmd(null, null, TEMPLATE, OFFERING, ZONE), caller).getKind());

        Object[][] refused = {
            {null, null, null, null, null},
            {null, 201L, null, null, null},
            {null, null, TEMPLATE, null, ZONE},
            {null, null, null, OFFERING, ZONE},
            {VM, 201L, null, OFFERING, null},
            {VM, null, TEMPLATE, OFFERING, null},
            {null, 201L, TEMPLATE, OFFERING, null},
            {VM, 201L, TEMPLATE, null, null},
        };
        for (Object[] combination : refused) {
            assertThrows(Arrays.toString(combination), InvalidParameterValueException.class,
                    () -> service.requestOf(cmd((Long)combination[0], (Long)combination[1], (Long)combination[2], (Long)combination[3], (Long)combination[4]), caller));
        }
        assertThrows("a new Instance needs its zone", InvalidParameterValueException.class, () -> service.requestOf(cmd(null, null, TEMPLATE, OFFERING, null), caller));
        ReflectionTestUtils.setField(vm, "state", VirtualMachine.State.Running);
        assertThrows("a running Instance's placement is not a start", InvalidParameterValueException.class,
                () -> service.requestOf(cmd(VM, null, null, null, null), caller));
        assertEquals(PlacementRequest.Kind.ATTACH, service.requestOf(cmd(VM, 201L, null, null, null), caller).getKind());
        detached.setInstanceId(VM);
        assertThrows("an attached volume", InvalidParameterValueException.class, () -> service.requestOf(cmd(VM, 201L, null, null, null), caller));
    }

    private CheckPlacementCmd cmd(Long vmId, Long volumeId, Long templateId, Long offeringId, Long zoneId) {
        CheckPlacementCmd cmd = new CheckPlacementCmd();
        ReflectionTestUtils.setField(cmd, "virtualMachineId", vmId);
        ReflectionTestUtils.setField(cmd, "volumeId", volumeId);
        ReflectionTestUtils.setField(cmd, "templateId", templateId);
        ReflectionTestUtils.setField(cmd, "serviceOfferingId", offeringId);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);
        return cmd;
    }

    @Test
    public void updateHostMoveRateStoresHostDetail() {
        UpdateHostMoveRateCmd cmd = new UpdateHostMoveRateCmd();
        ReflectionTestUtils.setField(cmd, "hostId", HOST_B);
        ReflectionTestUtils.setField(cmd, "mbps", 400L);

        service.updateHostMoveRate(cmd);

        Map<String, String> stored = new HashMap<>();
        stored.put("move.mbps", "400");
        verify(hostDetailsDao).persist(HOST_B, stored);
        DetailVO detail = new DetailVO(HOST_B, "move.mbps", "400");
        ReflectionTestUtils.setField(detail, "id", 77L);
        when(hostDetailsDao.findDetail(HOST_B, "move.mbps")).thenReturn(detail);
        ReflectionTestUtils.setField(cmd, "mbps", 0L);
        service.updateHostMoveRate(cmd);
        verify(hostDetailsDao).remove(77L);
        ReflectionTestUtils.setField(cmd, "mbps", -1L);
        assertThrows(InvalidParameterValueException.class, () -> service.updateHostMoveRate(cmd));
        assertTrue(Arrays.asList(service.getConfigKeys()).contains(PlacementService.VolumeMoveEstimateMbps));
        assertEquals("800", PlacementService.VolumeMoveEstimateMbps.defaultValue());
        assertTrue(PlacementService.VolumeMoveEstimateMbps.isDynamic());
        assertTrue(service.getCommands().contains(UpdateHostMoveRateCmd.class));
        assertTrue(service.getCommands().contains(CheckPlacementCmd.class));
    }

    @Test
    public void verdictHereOnAnyHostTheLocalPoolIsAttachedTo() throws Exception {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        when(storagePoolHostDao.listByPoolId(11L)).thenReturn(Arrays.asList(new StoragePoolHostVO(11L, HOST_A, "/var/lib/libvirt/images"),
                new StoragePoolHostVO(11L, HOST_B, "/var/lib/libvirt/images")));
        full(HOST_A);

        PlacementVerdict verdict = start();

        assertEquals("a start on any host the pool is attached to moves nothing", PlacementVerdict.Verdict.here, verdict.getVerdict());
        assertNull(service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), false, null));
        full(HOST_B);
        assertEquals("a volume is moved only from a pool on exactly one host", PlacementServiceImpl.REASON_LOCAL_STORAGE, start().getReason());
    }

    @Test
    public void moveRefusedWhileTheVolumesHostIsNotUp() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        full(HOST_A);
        when(hosts.get(HOST_A).getStatus()).thenReturn(Status.Disconnected);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_VOLUMES_UNAVAILABLE, verdict.getReason());
        assertNull(verdict.getToken());
        assertThrows(InsufficientServerCapacityException.class, () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, "1-00"));
    }

    @Test
    public void sharedDisksOfANewInstanceTakeAPoolTogether() {
        when(diskOfferingDao.findById(DISK_OFFERING).isUseLocalStorage()).thenReturn(false);
        StoragePoolVO shared = mock(StoragePoolVO.class);
        when(shared.getId()).thenReturn(50L);
        when(shared.getScope()).thenReturn(ScopeType.ZONE);
        freePoolBytes.put(50L, 30 * GIB);
        when(deploymentPlanningManager.listPoolsForDisk(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class))).thenAnswer(i ->
                freePoolBytes.get(50L) >= ((DiskProfile)i.getArgument(0)).getSize() ? Collections.singletonList(shared) : Collections.emptyList());
        PlacementRequest request = PlacementRequest.deploy(TEMPLATE, OFFERING, ZONE, ACCOUNT).withDataDiskSizesGiB(Arrays.asList(20L, 20L));

        assertEquals("each disk fits the pool alone, not both", PlacementVerdict.Verdict.none, service.check(request).getVerdict());
        freePoolBytes.put(50L, 40 * GIB);
        assertEquals(PlacementVerdict.Verdict.anyhost, service.check(request).getVerdict());
    }

    @Test
    public void deployFromAVolumeWithoutTemplateIsRefused() {
        VolumeVO kept = volume(201, Volume.Type.DATADISK, HOST_A, 10);
        kept.setInstanceId(null);

        assertThrows(InvalidParameterValueException.class, () -> service.check(PlacementRequest.deployFromVolume(201L, OFFERING, ACCOUNT)));
        assertThrows(InvalidParameterValueException.class, () -> service.check(PlacementRequest.deployFromVolume(299L, OFFERING, ACCOUNT)));
    }

    @Test
    public void attachChecksTheCallerMayUseTheVolumeWithTheInstance() {
        VolumeVO detached = volume(201, Volume.Type.DATADISK, HOST_A, 10);
        detached.setInstanceId(null);
        doThrow(new PermissionDeniedException("the volume and the Instance belong to different owners")).when(accountManager)
                .checkAccess(owner, null, true, detached, vm);

        assertThrows(PermissionDeniedException.class, () -> service.requestOf(cmd(VM, 201L, null, null, null), owner));
    }

    @Test
    public void updateHostMoveRateIsAnEventOnTheHost() throws Exception {
        ActionEvent event = PlacementServiceImpl.class.getMethod("updateHostMoveRate", UpdateHostMoveRateCmd.class).getAnnotation(ActionEvent.class);

        assertNotNull(event);
        assertEquals(EventTypes.EVENT_HOST_MOVE_RATE_UPDATE, event.eventType());
        assertEquals(Host.class, EventTypes.getEntityClassForEvent(EventTypes.EVENT_HOST_MOVE_RATE_UPDATE));
    }

    @Test
    public void verdictWhenAVolumesPoolIsGone() {
        volume(201, Volume.Type.ROOT, HOST_A, 10).setPoolId(99L);

        PlacementVerdict verdict = start();

        assertEquals(PlacementVerdict.Verdict.none, verdict.getVerdict());
        assertEquals(PlacementServiceImpl.REASON_LOCAL_STORAGE, verdict.getReason());
    }

    @Test
    public void approvalDoesNotApplyWhenNoHostHasRoom() {
        volume(201, Volume.Type.ROOT, HOST_A, 10);
        for (long id : new long[] {HOST_A, HOST_B, HOST_C}) {
            full(id);
        }

        InsufficientCapacityException e = assertThrows(InsufficientServerCapacityException.class,
                () -> service.approvedMove(PlacementRequest.start(VM, null, ACCOUNT), true, null));

        assertTrue(e.getMessage(), e.getMessage().endsWith("not possible now: " + PlacementServiceImpl.REASON_CPU_POOL));
    }
}
