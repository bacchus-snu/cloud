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
package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VirtualMachineManagerImplRelocationTest {
    private static final long VM_ID = 500L;
    private static final String VM_UUID = "vm-uuid";
    private static final long OFFERING_ID = 8L;
    private static final long DEST_POOL = 12L;
    private static final long DEST_HOST = 2L;
    private static final long SRC_POD = 101L;
    private static final long DEST_POD = 102L;
    private static final long SRC_HOST = 1L;

    private final VMInstanceDao vmDao = mock(VMInstanceDao.class);
    private final PrimaryDataStoreDao poolDao = mock(PrimaryDataStoreDao.class);
    private final StoragePoolHostDao poolHostDao = mock(StoragePoolHostDao.class);
    private final HostDao hostDao = mock(HostDao.class);
    private final VolumeDao volsDao = mock(VolumeDao.class);
    private final VMSnapshotDao vmSnapshotDao = mock(VMSnapshotDao.class);
    private final ServiceOfferingDao offeringDao = mock(ServiceOfferingDao.class);
    private final ResourceManager resourceMgr = mock(ResourceManager.class);
    private final CapacityManager capacityMgr = mock(CapacityManager.class);
    private final VolumeOrchestrationService volumeMgr = mock(VolumeOrchestrationService.class);
    private final NetworkOrchestrationService networkMgr = mock(NetworkOrchestrationService.class);
    private final VmWorkJobDao workJobDao = mock(VmWorkJobDao.class);
    private final AsyncJobManager jobMgr = mock(AsyncJobManager.class);

    private VirtualMachineManagerImpl vmm;
    private VMInstanceVO vm;
    private StoragePoolVO destPool;
    private HostVO destHost;
    private ServiceOfferingVO offering;
    private final List<VirtualMachine.Event> events = new ArrayList<>();
    private MockedStatic<AsyncJobExecutionContext> jobContextStatic;
    private AsyncJobExecutionContext jobContext;

    @Before
    public void setUp() throws Exception {
        vmm = Mockito.spy(new VirtualMachineManagerImpl());
        ReflectionTestUtils.setField(vmm, "_vmDao", vmDao);
        ReflectionTestUtils.setField(vmm, "_storagePoolDao", poolDao);
        ReflectionTestUtils.setField(vmm, "_poolHostDao", poolHostDao);
        ReflectionTestUtils.setField(vmm, "_hostDao", hostDao);
        ReflectionTestUtils.setField(vmm, "_volsDao", volsDao);
        ReflectionTestUtils.setField(vmm, "_vmSnapshotDao", vmSnapshotDao);
        ReflectionTestUtils.setField(vmm, "_offeringDao", offeringDao);
        ReflectionTestUtils.setField(vmm, "_resourceMgr", resourceMgr);
        ReflectionTestUtils.setField(vmm, "_capacityMgr", capacityMgr);
        ReflectionTestUtils.setField(vmm, "volumeMgr", volumeMgr);
        ReflectionTestUtils.setField(vmm, "_networkMgr", networkMgr);
        ReflectionTestUtils.setField(vmm, "_workJobDao", workJobDao);
        ReflectionTestUtils.setField(vmm, "_jobMgr", jobMgr);

        vm = new VMInstanceVO(VM_ID, OFFERING_ID, "vm", "i-2-500-VM", VirtualMachine.Type.User, 9L, HypervisorType.KVM, 1L, 6L, 5L, 2L, false);
        ReflectionTestUtils.setField(vm, "uuid", VM_UUID);
        vm.setPodIdToDeployIn(SRC_POD);
        vm.setLastHostId(SRC_HOST);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        doReturn(vm).when(vmm).findVmById(VM_ID);

        destPool = mock(StoragePoolVO.class);
        when(destPool.getId()).thenReturn(DEST_POOL);
        when(destPool.getPodId()).thenReturn(DEST_POD);
        when(destPool.getScope()).thenReturn(ScopeType.HOST);
        when(poolDao.findById(DEST_POOL)).thenReturn(destPool);
        when(poolHostDao.listByPoolId(DEST_POOL)).thenReturn(List.of(new StoragePoolHostVO(DEST_POOL, DEST_HOST, "/var/lib/libvirt/images")));
        destHost = mock(HostVO.class);
        when(destHost.getId()).thenReturn(DEST_HOST);
        when(destHost.getName()).thenReturn("dest");
        when(hostDao.findById(DEST_HOST)).thenReturn(destHost);
        when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());
        for (long id : new long[] {100L, 101L}) {
            VolumeVO volume = mock(VolumeVO.class);
            when(volume.getId()).thenReturn(id);
            when(volume.getInstanceId()).thenReturn(VM_ID);
            when(volsDao.findById(id)).thenReturn(volume);
        }
        offering = mock(ServiceOfferingVO.class);
        when(offeringDao.findById(VM_ID, OFFERING_ID)).thenReturn(offering);
        when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(destHost, offering, false)).thenReturn(new Pair<>(true, true));
        doAnswer(invocation -> {
            VMInstanceVO target = invocation.getArgument(0);
            VirtualMachine.Event event = invocation.getArgument(1);
            events.add(event);
            target.setState(VirtualMachine.State.getStateMachine().getNextState(target.getState(), event));
            return true;
        }).when(vmm).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), any());

        jobContext = mock(AsyncJobExecutionContext.class);
        jobContextStatic = Mockito.mockStatic(AsyncJobExecutionContext.class);
        jobContextStatic.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);
    }

    @After
    public void tearDown() {
        jobContextStatic.close();
    }

    private void runAsWorkItem() throws Exception {
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(true);
        new VmWorkJobHandlerProxy(vmm).handleVmWorkJob(new VmWorkRelocateStoppedVm(new VmWork(2L, 5L, VM_ID, "VirtualMachineManagerImpl"),
                List.of(100L, 101L), DEST_POOL));
    }

    @Test
    public void relocateVolumesForStoppedVmRunsAsItsVmWorkItem() {
        String command = VmWorkRelocateStoppedVm.class.getName();
        VmWorkJobVO workJob = mock(VmWorkJobVO.class);
        when(workJob.getId()).thenReturn(900L);
        doReturn(new Pair<>(null, VM_ID)).when(vmm).retrievePendingWorkJob(VM_UUID, command);
        doReturn(new Pair<>(workJob, new VmWork(2L, 5L, VM_ID, "VirtualMachineManagerImpl"))).when(vmm).createWorkJobAndWorkInfo(command, VM_ID);
        doNothing().when(vmm).setCmdInfoAndSubmitAsyncJob(any(), any(), anyLong());
        doReturn(vm).when(vmm).retrieveVmFromJobOutcome(any(), anyString(), anyString());
        try {
            doReturn(null).when(vmm).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(any());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        vmm.relocateStoppedVm(VM_UUID, List.of(100L, 101L), DEST_POOL);

        ArgumentCaptor<VmWork> work = ArgumentCaptor.forClass(VmWork.class);
        verify(vmm).setCmdInfoAndSubmitAsyncJob(eq(workJob), work.capture(), eq(VM_ID));
        VmWorkRelocateStoppedVm relocate = (VmWorkRelocateStoppedVm)work.getValue();
        assertEquals(List.of(100L, 101L), relocate.getVolumeIds());
        assertEquals(DEST_POOL, relocate.getDestPoolId());
        assertEquals(VM_ID, relocate.getVmId());
        verify(jobContext).joinJob(900L);
        verify(volumeMgr, never()).relocateVolumes(anyList(), any(), any());
        assertTrue("nothing happens to the Instance outside its work item", events.isEmpty());
    }

    @Test
    public void workItemMovesTheVolumesAndLeavesTheInstanceStoppedOnTheDestination() throws Exception {
        List<VirtualMachine.State> statesDuringMove = new ArrayList<>();
        when(volumeMgr.relocateVolumes(anyList(), eq(destPool), any(Runnable.class))).thenAnswer(invocation -> {
            statesDuringMove.add(vm.getState());
            List<VolumeVO> volumes = invocation.getArgument(0);
            assertEquals(2, volumes.size());
            ((Runnable)invocation.getArgument(2)).run();
            return Collections.emptyList();
        });

        runAsWorkItem();

        assertEquals(List.of(VirtualMachine.State.Migrating), statesDuringMove);
        assertEquals(List.of(VirtualMachine.Event.StorageMigrationRequested, VirtualMachine.Event.AgentReportStopped), events);
        assertEquals(VirtualMachine.State.Stopped, vm.getState());
        assertEquals(Long.valueOf(DEST_HOST), vm.getLastHostId());
        assertEquals(Long.valueOf(DEST_POD), vm.getPodIdToDeployIn());
        verify(vmDao).update(VM_ID, vm);
        ArgumentCaptor<DataCenterDeployment> plan = ArgumentCaptor.forClass(DataCenterDeployment.class);
        verify(networkMgr).reallocate(any(), plan.capture());
        assertEquals("the pod changed, so the networks follow", DEST_POD, plan.getValue().getPodId().longValue());
    }

    @Test
    public void samePodNeedsNoNetworkReallocation() throws Exception {
        vm.setPodIdToDeployIn(DEST_POD);
        when(volumeMgr.relocateVolumes(anyList(), eq(destPool), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable)invocation.getArgument(2)).run();
            return Collections.emptyList();
        });

        runAsWorkItem();

        verify(networkMgr, never()).reallocate(any(), any());
    }

    @Test
    public void failedMoveReturnsTheInstanceToStopped() throws Exception {
        when(volumeMgr.relocateVolumes(anyList(), eq(destPool), any(Runnable.class))).thenThrow(new CloudRuntimeException("SHA-256 mismatch"));

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, this::runAsWorkItem);

        assertTrue(e.getMessage(), e.getMessage().contains("SHA-256 mismatch"));
        assertEquals(List.of(VirtualMachine.Event.StorageMigrationRequested, VirtualMachine.Event.AgentReportStopped), events);
        assertEquals(VirtualMachine.State.Stopped, vm.getState());
        assertEquals("the Instance stays with its old host", Long.valueOf(SRC_HOST), vm.getLastHostId());
        verify(networkMgr, never()).reallocate(any(), any());
    }

    @Test
    public void runningVmVolumesNeverMove() {
        vm.setState(VirtualMachine.State.Running);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, this::runAsWorkItem);

        assertTrue(e.getMessage(), e.getMessage().contains("never do"));
        assertTrue(events.isEmpty());
        verify(volumeMgr, never()).relocateVolumes(anyList(), any(), any());
    }

    @Test
    public void destinationWithoutAGpuGroupRefused() {
        when(offering.getVgpuProfileId()).thenReturn(4L);
        when(resourceMgr.isGPUDeviceAvailable(offering, destHost, VM_ID)).thenReturn(false);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, this::runAsWorkItem);

        assertTrue(e.getMessage(), e.getMessage().contains("no free GPU group"));
        assertTrue(events.isEmpty());
        verify(volumeMgr, never()).relocateVolumes(anyList(), any(), any());
    }

    @Test
    public void volumeOfAnotherInstanceRefused() {
        VolumeVO foreign = mock(VolumeVO.class);
        when(foreign.getInstanceId()).thenReturn(501L);
        when(volsDao.findById(101L)).thenReturn(foreign);

        assertThrows(InvalidParameterValueException.class, this::runAsWorkItem);
        assertSame(VirtualMachine.State.Stopped, vm.getState());
        assertTrue(events.isEmpty());
    }

    @Test
    public void instanceThatCannotEnterMigratingIsNotMoved() throws Exception {
        doReturn(false).when(vmm).stateTransitTo(any(VirtualMachine.class), eq(VirtualMachine.Event.StorageMigrationRequested), any());

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, this::runAsWorkItem);

        assertTrue(e.getMessage(), e.getMessage().contains("changed concurrently"));
        verify(volumeMgr, never()).relocateVolumes(anyList(), any(), any());
    }
}
