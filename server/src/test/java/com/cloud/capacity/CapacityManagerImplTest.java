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
package com.cloud.capacity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.utils.bytescale.ByteScaleUtils;
import org.apache.cloudstack.utils.cache.LazyCache;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.dao.CapacityDao;
import com.cloud.capacity.dao.HostCoreAssignmentDao;
import com.cloud.capacity.dao.HostPoolCoreDao;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class CapacityManagerImplTest {
    @Mock
    ClusterDetailsDao clusterDetailsDao;
    @Mock
    ServiceOfferingDao serviceOfferingDao;
    @Mock
    CapacityDao capacityDao;
    @Mock
    HostDao hostDao;
    @Mock
    VMInstanceDao vmInstanceDao;
    @Mock
    VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    UserVmDao userVmDao;
    @Mock
    MessageBus messageBus;
    @Mock
    HostPoolCoreManager hostPoolCoreManager;

    @Spy
    @InjectMocks
    CapacityManagerImpl capacityManager = new CapacityManagerImpl();


    private Host host;
    private ServiceOffering offering;
    private static final long CLUSTER_ID = 1L;
    private static final int OFFERING_CPU = 4;
    private static final int OFFERING_CPU_SPEED = 2000;
    private static final int OFFERING_MEMORY = 4096;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long HOST_ID = 10L;
    private static final long VM_ID = 100L;
    private static final long CPU_OFFERING_ID = 20L;
    private static final long GPU_OFFERING_ID = 21L;
    private static final long GPU_PROFILE_ID = 1L;

    @Before
    public void setUp() {
        host = mock(Host.class);
        offering = mock(ServiceOffering.class);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);
        when(offering.getCpu()).thenReturn(OFFERING_CPU);
        when(offering.getSpeed()).thenReturn(OFFERING_CPU_SPEED);
        when(offering.getRamSize()).thenReturn(OFFERING_MEMORY);
    }

    @Test
    public void testGetClusterValues() {
        long clusterId = 1L;
        String cpuOvercommit = "2.0";
        String memoryOvercommit = "1.0";
        Map<String, String> clusterDetails = new HashMap<>();
        clusterDetails.put(VmDetailConstants.CPU_OVER_COMMIT_RATIO, cpuOvercommit);
        clusterDetails.put(VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, memoryOvercommit);

        when(clusterDetailsDao.findDetails(eq(clusterId), anyList())).thenReturn(clusterDetails);

        Pair<String, String> result = capacityManager.getClusterValues(clusterId);

        assertEquals(cpuOvercommit, result.first());
        assertEquals(memoryOvercommit, result.second());
        verify(clusterDetailsDao).findDetails(eq(clusterId), anyList());
    }

    @Test
    public void testGetServiceOfferingsMap() {
        Long offering1Id = 1L;
        ServiceOfferingVO offering1 = mock(ServiceOfferingVO.class);
        when(offering1.getId()).thenReturn(offering1Id);
        Long offering2Id = 2L;
        ServiceOfferingVO offering2 = mock(ServiceOfferingVO.class);
        when(offering2.getId()).thenReturn(offering2Id);
        when(serviceOfferingDao.listAllIncludingRemoved()).thenReturn(List.of(offering1, offering2));
        Map<Long, ServiceOfferingVO> result = capacityManager.getServiceOfferingsMap();
        assertEquals(2, result.size());
        assertEquals(offering1, result.get(offering1Id));
        assertEquals(offering2, result.get(offering2Id));
        verify(serviceOfferingDao).listAllIncludingRemoved();
    }

    @Test
    public void testGetServiceOfferingsMapEmptyList() {
        when(serviceOfferingDao.listAllIncludingRemoved()).thenReturn(Collections.emptyList());
        Map<Long, ServiceOfferingVO> result = capacityManager.getServiceOfferingsMap();
        assertTrue(result.isEmpty());
        verify(serviceOfferingDao).listAllIncludingRemoved();
    }

    @Test
    public void testCheckIfHostHasCpuCapabilityAndCapacity() {
        Float cpuOvercommit = 2.0f;
        Float memoryOvercommit = 1.5f;
        Pair<String, String> clusterDetails = new Pair<>(String.valueOf(cpuOvercommit), String.valueOf(memoryOvercommit));
        doReturn(clusterDetails).when(capacityManager).getClusterValues(CLUSTER_ID);
        doReturn(true).when(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU),
                eq(OFFERING_CPU_SPEED));
        doReturn(true).when(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)), eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
        Pair<Boolean, Boolean> result = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, offering, false);
        assertTrue(result.first());
        assertTrue(result.second());
        verify(capacityManager).getClusterValues(CLUSTER_ID);
        verify(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU), eq(OFFERING_CPU_SPEED));
        verify(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)),
                eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
    }

    @Test
    public void testCheckIfHostHasNoCpuCapabilityButHasCapacity() {
        Float cpuOvercommit = 1.5f;
        Float memoryOvercommit = 1.2f;
        Pair<String, String> clusterDetails = new Pair<>(String.valueOf(cpuOvercommit), String.valueOf(memoryOvercommit));
        doReturn(clusterDetails).when(capacityManager).getClusterValues(CLUSTER_ID);
        doReturn(false).when(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU),
                eq(OFFERING_CPU_SPEED));
        doReturn(true).when(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)), eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
        Pair<Boolean, Boolean> result = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, offering, false);
        assertFalse(result.first());
        assertTrue(result.second());
        verify(capacityManager).getClusterValues(CLUSTER_ID);
        verify(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU), eq(OFFERING_CPU_SPEED));
        verify(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)),
                eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
    }

    @Test
    public void testCheckIfHostHasCpuCapabilityButNoCapacity() {
        Float cpuOvercommit = 2.0f;
        Float memoryOvercommit = 1.5f;
        Pair<String, String> clusterDetails = new Pair<>(String.valueOf(cpuOvercommit), String.valueOf(memoryOvercommit));
        doReturn(clusterDetails).when(capacityManager).getClusterValues(CLUSTER_ID);
        doReturn(true).when(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU),
                eq(OFFERING_CPU_SPEED));
        doReturn(false).when(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)), eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
        Pair<Boolean, Boolean> result = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, offering, false);
        assertTrue(result.first());
        assertFalse(result.second());
        verify(capacityManager).getClusterValues(CLUSTER_ID);
        verify(capacityManager).checkIfHostHasCpuCapability(any(Host.class), eq(OFFERING_CPU), eq(OFFERING_CPU_SPEED));
        verify(capacityManager).checkIfHostHasCapacity(eq(host), eq(OFFERING_CPU * OFFERING_CPU_SPEED),
                eq(ByteScaleUtils.mebibytesToBytes(OFFERING_MEMORY)),
                eq(false), eq(cpuOvercommit), eq(memoryOvercommit), eq(false));
    }

    private HostVO mockHost(int cpus, long speed, long memory) {
        HostVO h = mock(HostVO.class);
        lenient().when(h.getId()).thenReturn(HOST_ID);
        lenient().when(h.getClusterId()).thenReturn(CLUSTER_ID);
        lenient().when(h.getCpus()).thenReturn(cpus);
        lenient().when(h.getSpeed()).thenReturn(speed);
        lenient().when(h.getTotalMemory()).thenReturn(memory);
        lenient().when(h.getResourceState()).thenReturn(ResourceState.Enabled);
        return h;
    }

    private ServiceOfferingVO mockOffering(long id, int cpu, int speed, int ramMb, Long vgpuProfileId) {
        ServiceOfferingVO so = mock(ServiceOfferingVO.class);
        lenient().when(so.getId()).thenReturn(id);
        lenient().when(so.getCpu()).thenReturn(cpu);
        lenient().when(so.getSpeed()).thenReturn(speed);
        lenient().when(so.getRamSize()).thenReturn(ramMb);
        lenient().when(so.getVgpuProfileId()).thenReturn(vgpuProfileId);
        return so;
    }

    private VMInstanceVO mockVm(long id, long offeringId, Long hostId, Long lastHostId) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getServiceOfferingId()).thenReturn(offeringId);
        lenient().when(vm.getHostId()).thenReturn(hostId);
        lenient().when(vm.getLastHostId()).thenReturn(lastHostId);
        lenient().when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        lenient().when(vm.getUpdateTime()).thenReturn(new Date());
        return vm;
    }

    private CapacityVO capacity(long id, short type, long used, long reserved, long total) {
        CapacityVO c = new CapacityVO(HOST_ID, 1L, 1L, CLUSTER_ID, used, total, type);
        c.setReservedCapacity(reserved);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private void mockCapacityRows(CapacityVO cpu, CapacityVO memory, CapacityVO cpuCore) {
        mockCapacityRows(HOST_ID, cpu, memory, cpuCore);
    }

    private void mockCapacityRows(long hostId, CapacityVO cpu, CapacityVO memory, CapacityVO cpuCore) {
        for (CapacityVO c : List.of(cpu, memory, cpuCore)) {
            lenient().when(capacityDao.findByHostIdType(hostId, c.getCapacityType())).thenReturn(c);
            lenient().when(capacityDao.lockRow(c.getId(), true)).thenReturn(c);
        }
    }

    private void mockClusterRatios(String cpuRatio, String memoryRatio) {
        ClusterDetailsVO cpuDetail = mock(ClusterDetailsVO.class);
        lenient().when(cpuDetail.getValue()).thenReturn(cpuRatio);
        ClusterDetailsVO memoryDetail = mock(ClusterDetailsVO.class);
        lenient().when(memoryDetail.getValue()).thenReturn(memoryRatio);
        lenient().when(clusterDetailsDao.findDetail(CLUSTER_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(cpuDetail);
        lenient().when(clusterDetailsDao.findDetail(CLUSTER_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(memoryDetail);
        lenient().doReturn(new Pair<>(cpuRatio, memoryRatio)).when(capacityManager).getClusterValues(CLUSTER_ID);
    }

    private MockedStatic<Transaction> mockTransaction() {
        MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class, Mockito.withSettings().strictness(Strictness.LENIENT));
        transaction.when(() -> Transaction.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        return transaction;
    }

    @Test
    public void allocateVmCapacityLeavesHostUsedUnchangedForGpuOffering() {
        HostVO fullHost = mockHost(8, 2000L, 16 * GIB);
        when(hostDao.findById(HOST_ID)).thenReturn(fullHost);
        VMInstanceVO vm = mockVm(VM_ID, GPU_OFFERING_ID, HOST_ID, null);
        ServiceOfferingVO gpuOffering = mockOffering(GPU_OFFERING_ID, 48, 2000, 96 * 1024, GPU_PROFILE_ID);
        when(serviceOfferingDao.findById(VM_ID, GPU_OFFERING_ID)).thenReturn(gpuOffering);
        CapacityVO cpu = capacity(1L, Capacity.CAPACITY_TYPE_CPU, 16000L, 0L, 16000L);
        CapacityVO memory = capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 0L, 16 * GIB);
        CapacityVO cpuCore = capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 8L, 0L, 8L);
        mockCapacityRows(cpu, memory, cpuCore);
        mockClusterRatios("1.0", "1.0");

        try (MockedStatic<Transaction> ignored = mockTransaction()) {
            capacityManager.allocateVmCapacity(vm, false);
        }

        assertEquals(16000L, cpu.getUsedCapacity());
        assertEquals(16 * GIB, memory.getUsedCapacity());
        assertEquals(8L, cpuCore.getUsedCapacity());
        verify(capacityDao, never()).update(anyLong(), any(CapacityVO.class));
    }

    @Test
    public void checkIfHostHasCapacityTrueForGpuVmOnFullPool() {
        HostVO fullHost = mockHost(8, 2000L, 16 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 16000L, 0L, 16000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 0L, 16 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 8L, 0L, 8L));
        mockClusterRatios("1.0", "1.0");
        ServiceOfferingVO gpuOffering = mockOffering(GPU_OFFERING_ID, 12, 2000, 96 * 1024, GPU_PROFILE_ID);
        ServiceOfferingVO cpuOffering = mockOffering(CPU_OFFERING_ID, 2, 2000, 2048, null);

        Pair<Boolean, Boolean> gpuResult = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(fullHost, gpuOffering, true);
        Pair<Boolean, Boolean> cpuResult = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(fullHost, cpuOffering, true);

        assertTrue(gpuResult.first());
        assertTrue(gpuResult.second());
        assertFalse(cpuResult.second());
    }

    @Test
    public void checkIfHostHasCpuCapabilityIgnoresGpuVmCount() {
        HostVO host = mockHost(8, 2000L, 64 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 0L, 0L, 16000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 64 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 0L, 0L, 8L));
        mockClusterRatios("1.0", "1.0");
        ServiceOfferingVO gpuOffering = mockOffering(GPU_OFFERING_ID, 48, 2000, 96 * 1024, GPU_PROFILE_ID);
        ServiceOfferingVO cpuOffering = mockOffering(CPU_OFFERING_ID, 10, 2000, 2048, null);

        assertTrue(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, gpuOffering, false).first());
        assertFalse(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, cpuOffering, false).first());
    }

    @Test
    public void updateCapacityForHostSkipsGpuVmsInUsedAndReserved() {
        ReflectionTestUtils.setField(capacityManager, "_vmCapacityReleaseInterval", 3600);
        ReflectionTestUtils.setField(capacityManager, "clusterValuesCache",
                new LazyCache<Long, Pair<String, String>>(16, 60, clusterId -> new Pair<>("1.0", "1.0")));
        HostVO host = mockHost(32, 2000L, 128 * GIB);
        ServiceOfferingVO gpuOffering = mockOffering(GPU_OFFERING_ID, 12, 2000, 96 * 1024, GPU_PROFILE_ID);
        ServiceOfferingVO cpuOffering = mockOffering(CPU_OFFERING_ID, 4, 2000, 8 * 1024, null);
        doReturn(gpuOffering).when(capacityManager).getServiceOffering(GPU_OFFERING_ID);
        doReturn(cpuOffering).when(capacityManager).getServiceOffering(CPU_OFFERING_ID);
        VMInstanceVO runningGpuVm = mockVm(1L, GPU_OFFERING_ID, HOST_ID, HOST_ID);
        VMInstanceVO runningCpuVm = mockVm(2L, CPU_OFFERING_ID, HOST_ID, HOST_ID);
        VMInstanceVO stoppedGpuVm = mockVm(3L, GPU_OFFERING_ID, null, HOST_ID);
        VMInstanceVO stoppedCpuVm = mockVm(4L, CPU_OFFERING_ID, null, HOST_ID);
        when(vmInstanceDao.listIdServiceOfferingForUpVmsByHostId(HOST_ID)).thenReturn(new ArrayList<>(List.of(runningGpuVm, runningCpuVm)));
        when(vmInstanceDao.listIdServiceOfferingForVmsMigratingFromHost(HOST_ID)).thenReturn(new ArrayList<>());
        when(vmInstanceDao.listByLastHostId(HOST_ID)).thenReturn(List.of(stoppedGpuVm, stoppedCpuVm));
        CapacityVO cpu = capacity(1L, Capacity.CAPACITY_TYPE_CPU, 999L, 999L, 64000L);
        CapacityVO memory = capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 1L, 1L, 128 * GIB);
        CapacityVO cpuCore = capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 99L, 99L, 32L);
        when(capacityDao.listByHostIdTypes(eq(HOST_ID), anyList())).thenReturn(List.of(cpu, memory, cpuCore));

        capacityManager.updateCapacityForHost(host);

        assertEquals(4L * 2000, cpu.getUsedCapacity());
        assertEquals(4L * 2000, cpu.getReservedCapacity());
        assertEquals(8 * GIB, memory.getUsedCapacity());
        assertEquals(8 * GIB, memory.getReservedCapacity());
        assertEquals(4L, cpuCore.getUsedCapacity());
        assertEquals(4L, cpuCore.getReservedCapacity());
    }

    private CapacityVO[] stopCpuOnlyVm(int releaseIntervalSeconds, VirtualMachine.State oldState, VirtualMachine.Event event) {
        return transitCpuOnlyVm(releaseIntervalSeconds, oldState, event, VirtualMachine.State.Stopped, null, 8000L);
    }

    private CapacityVO[] transitCpuOnlyVm(int releaseIntervalSeconds, VirtualMachine.State oldState, VirtualMachine.Event event,
            VirtualMachine.State newState, Long hostIdAfter, long usedCpuBefore) {
        ReflectionTestUtils.setField(capacityManager, "_vmCapacityReleaseInterval", releaseIntervalSeconds);
        HostVO host = mockHost(32, 2000L, 128 * GIB);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VMInstanceVO vm = mockVm(VM_ID, CPU_OFFERING_ID, hostIdAfter, HOST_ID);
        ServiceOfferingVO cpuOffering = mockOffering(CPU_OFFERING_ID, 4, 2000, 8 * 1024, null);
        when(serviceOfferingDao.findById(VM_ID, CPU_OFFERING_ID)).thenReturn(cpuOffering);
        long vms = usedCpuBefore / 8000L;
        CapacityVO cpu = capacity(1L, Capacity.CAPACITY_TYPE_CPU, usedCpuBefore, 0L, 64000L);
        CapacityVO memory = capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, vms * 8 * GIB, 0L, 128 * GIB);
        CapacityVO cpuCore = capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, vms * 4L, 0L, 32L);
        mockCapacityRows(cpu, memory, cpuCore);
        mockClusterRatios("1.0", "1.0");
        if (newState == VirtualMachine.State.Stopped) {
            when(userVmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
        }

        try (MockedStatic<Transaction> ignored = mockTransaction()) {
            capacityManager.postStateTransitionEvent(new StateMachine2.Transition<>(oldState, event, newState, null),
                    vm, true, new Pair<>(hostIdAfter == null ? HOST_ID : null, null));
        }
        return new CapacityVO[] {cpu, memory, cpuCore};
    }

    private void assertRows(CapacityVO[] rows, long usedVms, long reservedVms) {
        assertEquals(usedVms * 8000L, rows[0].getUsedCapacity());
        assertEquals(reservedVms * 8000L, rows[0].getReservedCapacity());
        assertEquals(usedVms * 8 * GIB, rows[1].getUsedCapacity());
        assertEquals(reservedVms * 8 * GIB, rows[1].getReservedCapacity());
        assertEquals(usedVms * 4L, rows[2].getUsedCapacity());
        assertEquals(reservedVms * 4L, rows[2].getReservedCapacity());
    }

    @Test
    public void powerOnReportFromStoppedAllocatesCapacity() {
        CapacityVO[] rows = transitCpuOnlyVm(0, VirtualMachine.State.Stopped, VirtualMachine.Event.FollowAgentPowerOnReport,
                VirtualMachine.State.Running, HOST_ID, 0L);

        assertRows(rows, 1, 0);
    }

    @Test
    public void followAgentPowerOffReportFromRunningReservesWithWindow() {
        assertRows(stopCpuOnlyVm(3600, VirtualMachine.State.Running, VirtualMachine.Event.FollowAgentPowerOffReport), 0, 1);
    }

    @Test
    public void followAgentPowerOffReportFromStoppingReleasesToFree() {
        assertRows(stopCpuOnlyVm(3600, VirtualMachine.State.Stopping, VirtualMachine.Event.FollowAgentPowerOffReport), 0, 0);
    }

    @Test
    public void powerOnReportOnFullHostRecordsUsageWithoutThrowing() {
        CapacityVO[] rows = transitCpuOnlyVm(0, VirtualMachine.State.Stopped, VirtualMachine.Event.FollowAgentPowerOnReport,
                VirtualMachine.State.Running, HOST_ID, 64000L);

        assertRows(rows, 9, 0);
    }

    @Test
    public void followAgentPowerOffReportFromMigratingReleasesEachHostOnce() {
        ReflectionTestUtils.setField(capacityManager, "_vmCapacityReleaseInterval", 3600);
        long destinationHostId = HOST_ID + 1;
        HostVO source = mockHost(32, 2000L, 128 * GIB);
        HostVO destination = mockHost(32, 2000L, 128 * GIB);
        lenient().when(destination.getId()).thenReturn(destinationHostId);
        when(hostDao.findById(HOST_ID)).thenReturn(source);
        when(hostDao.findById(destinationHostId)).thenReturn(destination);
        VMInstanceVO vm = mockVm(VM_ID, CPU_OFFERING_ID, null, HOST_ID);
        ServiceOfferingVO cpuOffering = mockOffering(CPU_OFFERING_ID, 4, 2000, 8 * 1024, null);
        when(serviceOfferingDao.findById(VM_ID, CPU_OFFERING_ID)).thenReturn(cpuOffering);
        CapacityVO[] sourceRows = {capacity(1L, Capacity.CAPACITY_TYPE_CPU, 16000L, 0L, 64000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 0L, 128 * GIB), capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 8L, 0L, 32L)};
        CapacityVO[] destinationRows = {capacity(4L, Capacity.CAPACITY_TYPE_CPU, 16000L, 0L, 64000L),
                capacity(5L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 0L, 128 * GIB), capacity(6L, Capacity.CAPACITY_TYPE_CPU_CORE, 8L, 0L, 32L)};
        mockCapacityRows(HOST_ID, sourceRows[0], sourceRows[1], sourceRows[2]);
        mockCapacityRows(destinationHostId, destinationRows[0], destinationRows[1], destinationRows[2]);
        mockClusterRatios("1.0", "1.0");
        when(userVmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));

        try (MockedStatic<Transaction> ignored = mockTransaction()) {
            capacityManager.postStateTransitionEvent(new StateMachine2.Transition<>(VirtualMachine.State.Migrating,
                    VirtualMachine.Event.FollowAgentPowerOffReport, VirtualMachine.State.Stopped, null), vm, true, new Pair<>(destinationHostId, null));
        }

        assertRows(sourceRows, 1, 0);
        assertRows(destinationRows, 1, 0);
    }

    @Test
    public void releaseVmCapacityLeavesRowsUnchangedForGpuOffering() {
        HostVO host = mockHost(8, 2000L, 16 * GIB);
        VMInstanceVO vm = mockVm(VM_ID, GPU_OFFERING_ID, null, HOST_ID);
        ServiceOfferingVO gpuOffering = mockOffering(GPU_OFFERING_ID, 12, 2000, 96 * 1024, GPU_PROFILE_ID);
        when(serviceOfferingDao.findById(VM_ID, GPU_OFFERING_ID)).thenReturn(gpuOffering);
        CapacityVO cpu = capacity(1L, Capacity.CAPACITY_TYPE_CPU, 16000L, 0L, 16000L);
        CapacityVO memory = capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 0L, 16 * GIB);
        CapacityVO cpuCore = capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 8L, 0L, 8L);
        mockCapacityRows(cpu, memory, cpuCore);
        mockClusterRatios("1.0", "1.0");

        try (MockedStatic<Transaction> ignored = mockTransaction()) {
            assertTrue(capacityManager.releaseVmCapacity(vm, false, true, host));
        }

        assertEquals(16000L, cpu.getUsedCapacity());
        assertEquals(0L, cpu.getReservedCapacity());
        assertEquals(16 * GIB, memory.getUsedCapacity());
        assertEquals(8L, cpuCore.getUsedCapacity());
        verify(capacityDao, never()).update(anyLong(), any(CapacityVO.class));
    }

    @Test
    public void stopWithZeroWindowReleasesToFree() {
        CapacityVO[] rows = stopCpuOnlyVm(0, VirtualMachine.State.Stopping, VirtualMachine.Event.OperationSucceeded);

        assertEquals(0L, rows[0].getUsedCapacity());
        assertEquals(0L, rows[0].getReservedCapacity());
        assertEquals(0L, rows[1].getUsedCapacity());
        assertEquals(0L, rows[1].getReservedCapacity());
        assertEquals(0L, rows[2].getUsedCapacity());
        assertEquals(0L, rows[2].getReservedCapacity());
    }

    @Test
    public void followAgentPowerOffReportReleasesToFreeWhenWindowZero() {
        CapacityVO[] rows = stopCpuOnlyVm(0, VirtualMachine.State.Running, VirtualMachine.Event.FollowAgentPowerOffReport);

        assertEquals(0L, rows[0].getUsedCapacity());
        assertEquals(0L, rows[0].getReservedCapacity());
        assertEquals(0L, rows[1].getUsedCapacity());
        assertEquals(0L, rows[1].getReservedCapacity());
        assertEquals(0L, rows[2].getUsedCapacity());
        assertEquals(0L, rows[2].getReservedCapacity());
    }

    @Test
    public void stopWithDefaultWindowStillReserves() {
        CapacityVO[] rows = stopCpuOnlyVm(3600, VirtualMachine.State.Stopping, VirtualMachine.Event.OperationSucceeded);

        assertEquals(0L, rows[0].getUsedCapacity());
        assertEquals(8000L, rows[0].getReservedCapacity());
        assertEquals(0L, rows[1].getUsedCapacity());
        assertEquals(8 * GIB, rows[1].getReservedCapacity());
        assertEquals(0L, rows[2].getUsedCapacity());
        assertEquals(4L, rows[2].getReservedCapacity());
    }

    @Test
    public void getFreeCapacityIsNullWithoutCapacityRows() {
        assertEquals(null, capacityManager.getFreeCapacity(mockHost(16, 2000L, 64 * GIB)));
    }

    @Test
    public void getFreeCapacityAgreesWithCheckIfHostHasCapacity() {
        HostVO host = mockHost(16, 2000L, 64 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 8000L, 2000L, 32000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 16 * GIB, 4 * GIB, 64 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 4L, 1L, 16L));
        mockClusterRatios("2.0", "1.5");

        CapacityManager.HostFreeCapacity free = capacityManager.getFreeCapacity(host);

        assertNotNull(free);
        assertEquals(64000L - 10000L, free.getFreeCpuMhz());
        assertEquals(96 * GIB - 20 * GIB, free.getFreeMemoryBytes());
        assertEquals(16, free.getCpus());
        assertEquals(2000, free.getSpeedMhz());
        int freeCpu = (int) free.getFreeCpuMhz();
        long freeMemory = free.getFreeMemoryBytes();
        assertTrue(capacityManager.checkIfHostHasCapacity(host, freeCpu, freeMemory, false, 2.0f, 1.5f, true));
        assertFalse(capacityManager.checkIfHostHasCapacity(host, freeCpu + 1, freeMemory, false, 2.0f, 1.5f, true));
        assertFalse(capacityManager.checkIfHostHasCapacity(host, freeCpu, freeMemory + 1, false, 2.0f, 1.5f, true));
    }

    @Test
    public void mhzCheckNeverBindsAtLowestHostSpeed() {
        int hostCpus = 16;
        int hostSpeed = 2400;
        int usedVcpus = 10;
        HostVO host = mockHost(hostCpus, (long) hostSpeed, 64 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, (long) usedVcpus * hostSpeed, 0L, (long) hostCpus * hostSpeed),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 64 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, usedVcpus, 0L, hostCpus));
        mockClusterRatios("1.0", "1.0");

        assertEquals((long) (hostCpus - usedVcpus) * hostSpeed, capacityManager.getFreeCapacity(host).getFreeCpuMhz());
        for (int offeringSpeed : new int[] {hostSpeed, 2000}) {
            for (int vcpus = 1; usedVcpus + vcpus <= hostCpus; vcpus++) {
                assertTrue(String.format("%d vCPU at %d MHz", vcpus, offeringSpeed),
                        capacityManager.checkIfHostHasCapacity(host, vcpus * offeringSpeed, GIB, false, 1.0f, 1.0f, true));
            }
        }
    }

    @Test
    public void hostWithoutCpusOrMemoryHasNoComputeCapacity() {
        assertTrue(capacityManager.hasNoComputeCapacity(mockHost(0, 2000L, GIB)));
        assertTrue(capacityManager.hasNoComputeCapacity(mockHost(8, 2000L, 0L)));
        assertTrue(capacityManager.hasNoComputeCapacity(mockHost(8, 0L, 16 * GIB)));
        assertFalse(capacityManager.hasNoComputeCapacity(mockHost(8, 2000L, 16 * GIB)));
    }

    private HostVO mockKvmHost(int cpus, long speed, long memory) {
        HostVO h = mockHost(cpus, speed, memory);
        lenient().when(h.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return h;
    }

    /**
     * Replaces the pool-core manager by a real one over in-memory rows: the host registers cores 0..registered-1 on
     * node 0, and other VMs hold the first {@code used} of them. A release here removes the VM's rows; its condition
     * on the VM's state is covered by HostPoolCoreManagerImplTest.
     */
    @SuppressWarnings("unchecked")
    private List<HostCoreAssignmentVO> useRealPoolCoreManager(int registered, int used) {
        List<HostCoreAssignmentVO> rows = Collections.synchronizedList(new ArrayList<>());
        HostPoolCoreDao hostPoolCoreDao = mock(HostPoolCoreDao.class);
        HostCoreAssignmentDao assignmentDao = mock(HostCoreAssignmentDao.class);
        List<HostPoolCoreVO> cores = new ArrayList<>();
        for (int c = 0; c < registered; c++) {
            cores.add(new HostPoolCoreVO(HOST_ID, c, c + 64, 0));
        }
        lenient().when(hostPoolCoreDao.listByHostId(HOST_ID)).thenReturn(cores);
        lenient().when(assignmentDao.listByHostId(HOST_ID)).thenAnswer(i -> new ArrayList<>(rows));
        lenient().when(assignmentDao.releaseIfNotRunning(anyLong())).thenAnswer(i -> {
            long vmId = i.getArgument(0);
            int before = rows.size();
            rows.removeIf(r -> r.getVmId() == vmId);
            return before - rows.size();
        });
        lenient().doAnswer(i -> {
            long vmId = i.getArgument(1);
            rows.removeIf(r -> r.getVmId() == vmId);
            for (Integer core : (List<Integer>) i.getArgument(2)) {
                rows.add(new HostCoreAssignmentVO(i.getArgument(0), core, vmId));
            }
            return null;
        }).when(assignmentDao).assign(anyLong(), anyLong(), anyList());
        for (int c = 0; c < used; c++) {
            rows.add(new HostCoreAssignmentVO(HOST_ID, c, 1000L + c));
        }
        HostPoolCoreManagerImpl real = new HostPoolCoreManagerImpl() {
            @Override
            protected <T, E extends Exception> T withHostLock(long hostId, HostLockedOperation<T, E> operation, Supplier<E> lockTimeout) throws E {
                return operation.run();
            }
        };
        real.hostPoolCoreDao = hostPoolCoreDao;
        real.hostCoreAssignmentDao = assignmentDao;
        ReflectionTestUtils.setField(capacityManager, "hostPoolCoreManager", real);
        return rows;
    }

    private HostPoolCoreManager poolCoreManager() {
        return (HostPoolCoreManager) ReflectionTestUtils.getField(capacityManager, "hostPoolCoreManager");
    }

    @Test
    public void admissionCountsWholeCores() {
        HostVO host = mockKvmHost(8, 2000L, 64 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 6000L, 0L, 16000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 64 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 3L, 0L, 8L));
        mockClusterRatios("1.0", "1.0");
        useRealPoolCoreManager(4, 3);
        ServiceOfferingVO twoVcpus = mockOffering(CPU_OFFERING_ID, 2, 2000, 1024, null);
        ServiceOfferingVO fourVcpus = mockOffering(CPU_OFFERING_ID, 4, 2000, 1024, null);

        assertTrue(capacityManager.checkIfHostHasCapacity(host, 4 * 2000, GIB, false, 1.0f, 1.0f, true));
        assertTrue(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, twoVcpus, true).second());
        assertFalse(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, fourVcpus, true).second());
        assertTrue(capacityManager.checkIfHostHasPoolCores(host, 1));
        assertTrue(capacityManager.checkIfHostHasPoolCores(host, 2));
        assertFalse(capacityManager.checkIfHostHasPoolCores(host, 3));
        assertEquals(1, capacityManager.getFreeCapacity(host).getFreeCores());
        assertTrue(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host,
                mockOffering(GPU_OFFERING_ID, 12, 2000, 1024, GPU_PROFILE_ID), true).second());
    }

    @Test
    public void hostWithoutPoolCoresOffersNoCpuOnlyCapacity() {
        HostVO host = mockKvmHost(8, 2000L, 16 * GIB);
        mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 0L, 0L, 16000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 16 * GIB),
                capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 0L, 0L, 8L));
        mockClusterRatios("1.0", "1.0");
        useRealPoolCoreManager(0, 0);

        assertTrue(capacityManager.hasNoComputeCapacity(host));
        assertFalse(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host, mockOffering(CPU_OFFERING_ID, 2, 2000, 1024, null), true).second());
        assertEquals(0, capacityManager.getFreeCapacity(host).getFreeCores());
        assertTrue(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host,
                mockOffering(GPU_OFFERING_ID, 12, 2000, 1024, GPU_PROFILE_ID), true).second());

        useRealPoolCoreManager(4, 0);
        assertFalse(capacityManager.hasNoComputeCapacity(host));
        assertFalse(capacityManager.hasNoComputeCapacity(mockHost(8, 2000L, 16 * GIB)));
    }

    @Test
    public void mhzCheckNeverBindsWhenTheCoresAdmit() {
        int poolCores = 8;
        int hostSpeed = 2400;
        HostVO host = mockKvmHost(2 * poolCores, hostSpeed, 64 * GIB);
        mockClusterRatios("1.0", "1.0");
        for (int usedCores = 0; usedCores <= poolCores; usedCores++) {
            mockCapacityRows(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 2L * usedCores * hostSpeed, 0L, 2L * poolCores * hostSpeed),
                    capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 64 * GIB),
                    capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 2L * usedCores, 0L, 2L * poolCores));
            useRealPoolCoreManager(poolCores, usedCores);
            for (int vcpus = 1; vcpus <= 2 * poolCores + 1; vcpus++) {
                boolean coresAdmit = HostPoolCoreManager.coresForVcpus(vcpus) <= poolCores - usedCores;
                Pair<Boolean, Boolean> result = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(host,
                        mockOffering(CPU_OFFERING_ID, vcpus, hostSpeed, 1024, null), true);
                assertEquals(String.format("%d vCPU with %d cores used", vcpus, usedCores), coresAdmit, result.first() && result.second());
            }
        }
    }

    @Test
    public void coresAllocatedOnStartReleasedOnStop() throws Exception {
        List<HostCoreAssignmentVO> rows = useRealPoolCoreManager(4, 0);
        poolCoreManager().allocatePoolCores(mockVm(VM_ID, CPU_OFFERING_ID, HOST_ID, null), mockKvmHost(8, 2000L, 128 * GIB), 4);
        assertEquals(2, rows.size());

        stopCpuOnlyVm(0, VirtualMachine.State.Stopping, VirtualMachine.Event.OperationSucceeded);

        assertTrue(rows.isEmpty());
    }

    @Test
    public void coresReleasedOnPowerOff() throws Exception {
        List<HostCoreAssignmentVO> rows = useRealPoolCoreManager(4, 1);
        poolCoreManager().allocatePoolCores(mockVm(VM_ID, CPU_OFFERING_ID, HOST_ID, null), mockKvmHost(8, 2000L, 128 * GIB), 4);
        assertEquals(3, rows.size());

        stopCpuOnlyVm(3600, VirtualMachine.State.Running, VirtualMachine.Event.FollowAgentPowerOffReport);

        assertEquals(1, rows.size());
        assertEquals(1000L, rows.get(0).getVmId());
    }

    @Test
    public void coresReleasedOnEveryTransitionIntoStopped() {
        Object[][] transitions = {
                {VirtualMachine.State.Starting, VirtualMachine.Event.OperationFailed},
                {VirtualMachine.State.Starting, VirtualMachine.Event.AgentReportStopped},
                {VirtualMachine.State.Running, VirtualMachine.Event.AgentReportStopped},
                {VirtualMachine.State.Stopping, VirtualMachine.Event.AgentReportStopped},
                {VirtualMachine.State.Migrating, VirtualMachine.Event.FollowAgentPowerOffReport},
        };
        for (Object[] transition : transitions) {
            Mockito.clearInvocations(hostPoolCoreManager);
            stopCpuOnlyVm(0, (VirtualMachine.State) transition[0], (VirtualMachine.Event) transition[1]);
            verify(hostPoolCoreManager).releasePoolCores(VM_ID);
        }
    }

    @Test
    public void coresReleasedEvenWhenCapacityReleaseFails() {
        VMInstanceVO vm = mockVm(VM_ID, CPU_OFFERING_ID, null, HOST_ID);
        HostVO host = mockKvmHost(8, 2000L, 16 * GIB);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.doThrow(new CloudRuntimeException("capacity release failed")).when(capacityManager)
                .releaseVmCapacity(any(VirtualMachine.class), Mockito.anyBoolean(), Mockito.anyBoolean(), any(Host.class));

        org.junit.Assert.assertThrows(CloudRuntimeException.class, () -> capacityManager.postStateTransitionEvent(
                new StateMachine2.Transition<>(VirtualMachine.State.Stopping, VirtualMachine.Event.OperationSucceeded, VirtualMachine.State.Stopped, null),
                vm, true, new Pair<>(HOST_ID, null)));

        verify(hostPoolCoreManager).releasePoolCores(VM_ID);
    }

    @Test
    public void poolCoresCountedForEachVm() {
        HostVO host = mockKvmHost(16, 2000L, 64 * GIB);
        useRealPoolCoreManager(8, 3);

        assertTrue(capacityManager.checkIfHostHasPoolCores(host, 4, 2));
        assertFalse(capacityManager.checkIfHostHasPoolCores(host, 4, 3));
        assertTrue(capacityManager.checkIfHostHasPoolCores(host, 3, 2));
        assertFalse(capacityManager.checkIfHostHasPoolCores(host, 3, 3));
        assertTrue(capacityManager.checkIfHostHasPoolCores(host, 1, 5));
        assertFalse(capacityManager.checkIfHostHasPoolCores(host, 1, 6));
        assertTrue(capacityManager.checkIfHostHasPoolCores(mockHost(16, 2000L, 64 * GIB), 4, 100));
    }

    @Test
    public void coresKeptWhileTheVmMayRun() {
        VMInstanceVO vm = mockVm(VM_ID, CPU_OFFERING_ID, HOST_ID, HOST_ID);
        HostVO host = mockKvmHost(8, 2000L, 16 * GIB);
        lenient().when(hostDao.findById(HOST_ID)).thenReturn(host);
        VirtualMachine.State[][] transitions = {
                {VirtualMachine.State.Running, VirtualMachine.State.Stopping},
                {VirtualMachine.State.Starting, VirtualMachine.State.Running},
                {VirtualMachine.State.Stopping, VirtualMachine.State.Running},
                {VirtualMachine.State.Running, VirtualMachine.State.Running},
        };
        VirtualMachine.Event[] events = {VirtualMachine.Event.StopRequested, VirtualMachine.Event.OperationSucceeded,
                VirtualMachine.Event.OperationFailed, VirtualMachine.Event.FollowAgentPowerOnReport};
        for (int i = 0; i < events.length; i++) {
            capacityManager.postStateTransitionEvent(new StateMachine2.Transition<>(transitions[i][0], events[i], transitions[i][1], null),
                    vm, true, new Pair<>(HOST_ID, HOST_ID));
        }

        verify(hostPoolCoreManager, never()).releasePoolCores(anyLong());
    }

    @Test
    public void updateCapacityForHostSweepsStaleAssignments() {
        ReflectionTestUtils.setField(capacityManager, "_vmCapacityReleaseInterval", 0);
        ReflectionTestUtils.setField(capacityManager, "clusterValuesCache",
                new LazyCache<Long, Pair<String, String>>(16, 60, clusterId -> new Pair<>("1.0", "1.0")));
        when(vmInstanceDao.listIdServiceOfferingForUpVmsByHostId(HOST_ID)).thenAnswer(i -> new ArrayList<>());
        when(vmInstanceDao.listIdServiceOfferingForVmsMigratingFromHost(HOST_ID)).thenAnswer(i -> new ArrayList<>());
        when(vmInstanceDao.listByLastHostId(HOST_ID)).thenAnswer(i -> new ArrayList<>());
        when(capacityDao.listByHostIdTypes(eq(HOST_ID), anyList())).thenAnswer(i -> List.of(capacity(1L, Capacity.CAPACITY_TYPE_CPU, 0L, 0L, 16000L),
                capacity(2L, Capacity.CAPACITY_TYPE_MEMORY, 0L, 0L, 16 * GIB), capacity(3L, Capacity.CAPACITY_TYPE_CPU_CORE, 0L, 0L, 8L)));

        capacityManager.updateCapacityForHost(mockKvmHost(8, 2000L, 16 * GIB));
        verify(hostPoolCoreManager).removeStaleAssignments(HOST_ID);

        Mockito.clearInvocations(hostPoolCoreManager);
        capacityManager.updateCapacityForHost(mockHost(8, 2000L, 16 * GIB));
        verify(hostPoolCoreManager, never()).removeStaleAssignments(anyLong());
    }
}
