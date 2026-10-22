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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.persistence.EntityExistsException;

import org.apache.cloudstack.api.command.admin.host.ListHostPoolCoresCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPoolCoresCmd;
import org.apache.cloudstack.api.response.HostPoolCoreResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.HostPoolCoreManager.PoolCore;
import com.cloud.capacity.dao.HostCoreAssignmentDao;
import com.cloud.capacity.dao.HostPoolCoreDao;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class HostPoolCoreManagerImplTest {

    private static final long HOST_ID = 10L;
    private static final long OTHER_HOST_ID = 11L;

    @Mock
    HostDao hostDao;
    @Mock
    HostPoolCoreDao hostPoolCoreDao;
    @Mock
    HostCoreAssignmentDao hostCoreAssignmentDao;
    @Mock
    VMInstanceDao vmInstanceDao;

    private final Tables db = new Tables();
    private final Map<Long, ReentrantLock> hostLocks = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private HostVO host;

    /**
     * The host_pool_core and host_core_assignment tables: each operation is atomic, like a committed statement or
     * transaction, with the keys of the schema (UNIQUE(host, core) on both, assignments referencing registered cores
     * ON DELETE RESTRICT) and the VM-state conditions of the release and sweep statements, which keep the cores of a VM
     * whose restart within its running session is in progress.
     */
    private final class Tables {
        private final Map<Long, List<HostPoolCoreVO>> registered = new LinkedHashMap<>();
        private final Map<String, HostCoreAssignmentVO> rows = new LinkedHashMap<>();
        private final Set<Long> restarting = ConcurrentHashMap.newKeySet();
        private long nextId = 1;
        private volatile CyclicBarrier afterRead;

        private String key(long hostId, int core) {
            return hostId + ":" + core;
        }

        synchronized List<HostPoolCoreVO> registeredCores(long hostId) {
            return registered.getOrDefault(hostId, new ArrayList<>()).stream().sorted(Comparator.comparingInt(HostPoolCoreVO::getCore))
                    .collect(Collectors.toList());
        }

        synchronized void replace(long hostId, List<HostPoolCoreVO> cores) {
            for (HostPoolCoreVO row : registered.getOrDefault(hostId, new ArrayList<>())) {
                if (cores.stream().noneMatch(row::sameCore) && rows.containsKey(key(hostId, row.getCore()))) {
                    throw new CloudRuntimeException("Cannot delete or update a parent row: fk_host_core_assignment__host_pool_core");
                }
            }
            registered.put(hostId, new ArrayList<>(cores));
        }

        List<HostCoreAssignmentVO> byHost(long hostId) {
            List<HostCoreAssignmentVO> result;
            synchronized (this) {
                result = rows.values().stream().filter(r -> r.getHostId() == hostId).collect(Collectors.toList());
            }
            CyclicBarrier barrier = afterRead;
            if (barrier != null) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }

        synchronized List<HostCoreAssignmentVO> byVm(long vmId) {
            return rows.values().stream().filter(r -> r.getVmId() == vmId).collect(Collectors.toList());
        }

        synchronized List<HostCoreAssignmentVO> all() {
            return new ArrayList<>(rows.values());
        }

        synchronized void clearAssignments() {
            rows.clear();
        }

        synchronized int removeAssignmentsOfHost(long hostId) {
            return removeWhere(r -> r.getHostId() == hostId);
        }

        synchronized int removeRegistrationOfHost(long hostId) {
            if (rows.values().stream().anyMatch(r -> r.getHostId() == hostId)) {
                throw new CloudRuntimeException("Cannot delete or update a parent row: fk_host_core_assignment__host_pool_core");
            }
            List<HostPoolCoreVO> removed = registered.remove(hostId);
            return removed == null ? 0 : removed.size();
        }

        private synchronized int removeWhere(Predicate<HostCoreAssignmentVO> condition) {
            List<String> keys = rows.entrySet().stream().filter(e -> condition.test(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
            keys.forEach(rows::remove);
            return keys.size();
        }

        synchronized void assign(long hostId, long vmId, List<Integer> cores) {
            for (int core : cores) {
                if (registered.getOrDefault(hostId, new ArrayList<>()).stream().noneMatch(r -> r.getCore() == core)) {
                    throw new CloudRuntimeException("Cannot add or update a child row: fk_host_core_assignment__host_pool_core");
                }
                HostCoreAssignmentVO held = rows.get(key(hostId, core));
                if (held != null && held.getVmId() != vmId) {
                    throw new EntityExistsException("Duplicate entry for key uk_host_core_assignment__host_id__core");
                }
            }
            removeWhere(r -> r.getVmId() == vmId);
            for (int core : cores) {
                put(hostId, core, vmId);
            }
        }

        synchronized void put(long hostId, int core, long vmId) {
            HostCoreAssignmentVO row = new HostCoreAssignmentVO(hostId, core, vmId);
            ReflectionTestUtils.setField(row, "id", nextId++);
            rows.put(key(hostId, core), row);
        }

        private boolean gone(VMInstanceVO vm) {
            return vm == null || vm.getRemoved() != null;
        }

        private boolean notRunning(VMInstanceVO vm) {
            return gone(vm) || (HostCoreAssignmentDao.STATES_WITHOUT_CORES.contains(vm.getState()) && !restarting.contains(vm.getId()));
        }

        synchronized int releaseIfNotRunning(long vmId) {
            return notRunning(vmInstanceDao.findByIdIncludingRemoved(vmId)) ? removeWhere(r -> r.getVmId() == vmId) : 0;
        }

        synchronized int removeStale(long hostId) {
            return removeWhere(r -> {
                if (r.getHostId() != hostId) {
                    return false;
                }
                VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(r.getVmId());
                if (gone(vm)) {
                    return true;
                }
                return !restarting.contains(vm.getId()) && (HostCoreAssignmentDao.STATES_WITHOUT_CORES.contains(vm.getState())
                        || vm.getHostId() == null || vm.getHostId() != hostId);
            });
        }
    }

    private HostPoolCoreManagerImpl newManager(boolean lockHosts) {
        HostPoolCoreManagerImpl m = new HostPoolCoreManagerImpl() {
            @Override
            protected <T, E extends Exception> T withHostLock(long hostId, HostLockedOperation<T, E> operation, Supplier<E> lockTimeout) throws E {
                if (!lockHosts) {
                    return operation.run();
                }
                ReentrantLock lock = hostLocks.computeIfAbsent(hostId, id -> new ReentrantLock());
                lock.lock();
                try {
                    return operation.run();
                } finally {
                    lock.unlock();
                }
            }
        };
        m.hostDao = hostDao;
        m.hostPoolCoreDao = hostPoolCoreDao;
        m.hostCoreAssignmentDao = hostCoreAssignmentDao;
        m.vmInstanceDao = vmInstanceDao;
        return m;
    }

    @Before
    public void setUp() {
        lenient().when(hostPoolCoreDao.listByHostId(anyLong())).thenAnswer(i -> db.registeredCores(i.getArgument(0)));
        lenient().doAnswer(i -> {
            db.replace(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(hostPoolCoreDao).replace(anyLong(), anyList());
        lenient().when(hostCoreAssignmentDao.listByHostId(anyLong())).thenAnswer(i -> db.byHost(i.getArgument(0)));
        lenient().when(hostCoreAssignmentDao.releaseIfNotRunning(anyLong())).thenAnswer(i -> db.releaseIfNotRunning(i.getArgument(0)));
        lenient().when(hostCoreAssignmentDao.removeStale(anyLong())).thenAnswer(i -> db.removeStale(i.getArgument(0)));
        lenient().when(hostCoreAssignmentDao.removeByHostId(anyLong())).thenAnswer(i -> db.removeAssignmentsOfHost(i.getArgument(0)));
        lenient().when(hostPoolCoreDao.removeByHostId(anyLong())).thenAnswer(i -> db.removeRegistrationOfHost(i.getArgument(0)));
        lenient().doAnswer(i -> {
            db.assign(i.getArgument(0), i.getArgument(1), i.getArgument(2));
            return null;
        }).when(hostCoreAssignmentDao).assign(anyLong(), anyLong(), anyList());
        host = mockHost(HOST_ID, HypervisorType.KVM);
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    private HostVO mockHost(long id, HypervisorType hypervisorType) {
        HostVO h = mock(HostVO.class);
        lenient().when(h.getId()).thenReturn(id);
        lenient().when(h.getUuid()).thenReturn("host-" + id);
        lenient().when(h.getType()).thenReturn(Host.Type.Routing);
        lenient().when(h.getHypervisorType()).thenReturn(hypervisorType);
        lenient().when(hostDao.findById(id)).thenReturn(h);
        return h;
    }

    private VMInstanceVO mockVm(long id, VirtualMachine.State state, Long hostId) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getUuid()).thenReturn("vm-" + id);
        lenient().when(vm.getHostName()).thenReturn("vm" + id);
        lenient().when(vm.getInstanceName()).thenReturn("i-2-" + id + "-VM");
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getHostId()).thenReturn(hostId);
        lenient().when(vmInstanceDao.findById(id)).thenReturn(vm);
        lenient().when(vmInstanceDao.findByIdIncludingRemoved(id)).thenReturn(vm);
        return vm;
    }

    /** Cores {first..last} of node as registration JSON, each core's sibling thread being its id + offset. */
    private static String cores(int node, int first, int last, int offset) {
        List<String> entries = new ArrayList<>();
        for (int c = first; c <= last; c++) {
            entries.add(String.format("{\"node\": %d, \"threads\": [%d, %d]}", node, c, c + offset));
        }
        return String.join(", ", entries);
    }

    private void register(long hostId, String... coreLists) {
        db.replace(hostId, HostPoolCoreManagerImpl.parsePoolCores("[" + String.join(", ", coreLists) + "]").stream()
                .map(c -> new HostPoolCoreVO(hostId, c.getCore(), c.getThreads()[1], c.getNode())).collect(Collectors.toList()));
    }

    private static List<Integer> coreIds(List<PoolCore> cores) {
        return cores.stream().map(PoolCore::getCore).collect(Collectors.toList());
    }

    private UpdateHostPoolCoresCmd updateCmd(long hostId, String json) {
        UpdateHostPoolCoresCmd cmd = mock(UpdateHostPoolCoresCmd.class);
        lenient().when(cmd.getHostId()).thenReturn(hostId);
        lenient().when(cmd.getCores()).thenReturn(json);
        return cmd;
    }

    // Registration

    @Test
    public void registrationRejectsCoresThatAreNotPairs() {
        for (String json : List.of("[{\"node\": 0, \"threads\": [8]}]", "[{\"node\": 0, \"threads\": [8, 24, 40]}]",
                "[{\"node\": 0, \"threads\": []}]", "[{\"node\": 0}]", "[{\"node\": 0, \"threads\": [24, 8]}]",
                "[{\"node\": 0, \"threads\": [8, 8]}]")) {
            assertThrows(json, IllegalArgumentException.class, () -> HostPoolCoreManagerImpl.parsePoolCores(json));
            assertThrows(json, InvalidParameterValueException.class, () -> newManager(true).updateHostPoolCores(updateCmd(HOST_ID, json)));
        }
        verify(hostPoolCoreDao, never()).replace(anyLong(), anyList());
    }

    @Test
    public void registrationRejectsARepeatedThread() {
        for (String json : List.of("[{\"node\": 0, \"threads\": [8, 24]}, {\"node\": 0, \"threads\": [24, 40]}]",
                "[{\"node\": 0, \"threads\": [8, 24]}, {\"node\": 1, \"threads\": [8, 24]}]",
                "[{\"node\": 0, \"threads\": [8, 24]}, {\"node\": 0, \"threads\": [6, 8]}]")) {
            assertThrows(json, IllegalArgumentException.class, () -> HostPoolCoreManagerImpl.parsePoolCores(json));
            assertThrows(json, InvalidParameterValueException.class, () -> newManager(true).updateHostPoolCores(updateCmd(HOST_ID, json)));
        }
    }

    @Test
    public void registrationRejectsMalformedInput() {
        for (String json : List.of("not json", "{}", "[1]", "[{\"node\": -1, \"threads\": [8, 24]}]",
                "[{\"node\": \"0\", \"threads\": [8, 24]}]", "[{\"node\": 0, \"threads\": [8.5, 24]}]",
                "[{\"node\": 0, \"threads\": [-2, 24]}]", "[{\"node\": 0, \"threads\": [8, 4294967320]}]")) {
            assertThrows(json, IllegalArgumentException.class, () -> HostPoolCoreManagerImpl.parsePoolCores(json));
        }
    }

    @Test
    public void registrationIsStoredOneRowPerCore() {
        String json = "[{\"node\": 1, \"threads\": [9, 25]}, {\"node\": 0, \"threads\": [6, 22]}, {\"node\" : 1, \"threads\":[8,24]}]";

        newManager(true).updateHostPoolCores(updateCmd(HOST_ID, json));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HostPoolCoreVO>> rows = ArgumentCaptor.forClass(List.class);
        verify(hostPoolCoreDao).replace(eq(HOST_ID), rows.capture());
        assertEquals(List.of("HostPoolCore {host: 10, core: 6,22, node: 0}", "HostPoolCore {host: 10, core: 8,24, node: 1}",
                "HostPoolCore {host: 10, core: 9,25, node: 1}"), rows.getValue().stream().map(Object::toString).collect(Collectors.toList()));
        assertEquals(List.of(6, 8, 9), coreIds(newManager(true).listPoolCores(HOST_ID)));
    }

    @Test
    public void registrationIsRefusedForAHostThatDoesNotRunVmsOnPoolCores() {
        mockHost(OTHER_HOST_ID, HypervisorType.VMware);
        assertThrows(InvalidParameterValueException.class,
                () -> newManager(true).updateHostPoolCores(updateCmd(OTHER_HOST_ID, "[" + cores(0, 8, 9, 16) + "]")));
    }

    @Test
    public void registrationCannotDropOrChangeACoreThatAVmHolds() {
        register(HOST_ID, cores(0, 8, 11, 16));
        mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        db.put(HOST_ID, 9, 1L);
        HostPoolCoreManagerImpl manager = newManager(true);

        assertThrows(InvalidParameterValueException.class, () -> manager.updateHostPoolCores(updateCmd(HOST_ID, "[" + cores(0, 10, 11, 16) + "]")));
        assertThrows(InvalidParameterValueException.class,
                () -> manager.updateHostPoolCores(updateCmd(HOST_ID, "[{\"node\": 0, \"threads\": [9, 40]}]")));
        assertThrows(InvalidParameterValueException.class, () -> manager.updateHostPoolCores(updateCmd(HOST_ID, "[]")));
        verify(hostPoolCoreDao, never()).replace(anyLong(), anyList());

        manager.updateHostPoolCores(updateCmd(HOST_ID, "[" + cores(0, 9, 12, 16) + "]"));
        assertEquals(List.of(9, 10, 11, 12), coreIds(manager.listPoolCores(HOST_ID)));
    }

    @Test
    public void emptyRegistrationRemovesThePoolCores() {
        register(HOST_ID, cores(0, 8, 9, 16));

        newManager(true).updateHostPoolCores(updateCmd(HOST_ID, "[]"));

        verify(hostPoolCoreDao).replace(HOST_ID, List.of());
        assertTrue(newManager(true).listPoolCores(HOST_ID).isEmpty());
    }

    @Test
    public void listHostPoolCoresShowsEachCoreWithItsVm() {
        register(HOST_ID, cores(0, 8, 9, 16), cores(1, 20, 20, 16));
        mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        db.put(HOST_ID, 9, 1L);
        ListHostPoolCoresCmd cmd = mock(ListHostPoolCoresCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);

        ListResponse<HostPoolCoreResponse> response = newManager(true).listHostPoolCores(cmd);

        assertEquals(Integer.valueOf(3), response.getCount());
        List<HostPoolCoreResponse> cores = response.getResponses();
        assertEquals(List.of(8, 9, 20), cores.stream().map(HostPoolCoreResponse::getCore).collect(Collectors.toList()));
        assertEquals(List.of(9, 25), cores.get(1).getThreads());
        assertEquals(Integer.valueOf(1), cores.get(2).getNode());
        assertEquals("host-" + HOST_ID, cores.get(0).getHostId());
        assertNull(cores.get(0).getVirtualMachineId());
        assertEquals("vm-1", cores.get(1).getVirtualMachineId());
        assertEquals("i-2-1-VM", cores.get(1).getInstanceName());
        assertNull(cores.get(2).getVirtualMachineId());
    }

    // Allocation

    @Test
    public void allocationPrefersSingleNode() throws Exception {
        List<PoolCore> free = HostPoolCoreManagerImpl.parsePoolCores("[" + cores(0, 6, 7, 16) + ", " + cores(1, 8, 14, 16) + "]");

        assertEquals(List.of(6, 7), coreIds(HostPoolCoreManagerImpl.choosePoolCores(free, 2)));
        assertEquals(List.of(8, 9, 10), coreIds(HostPoolCoreManagerImpl.choosePoolCores(free, 3)));
        assertEquals(List.of(6, 7, 8, 9, 10, 11, 12, 13), coreIds(HostPoolCoreManagerImpl.choosePoolCores(free, 8)));
        assertNull(HostPoolCoreManagerImpl.choosePoolCores(free, 10));

        List<PoolCore> tie = HostPoolCoreManagerImpl.parsePoolCores("[" + cores(1, 4, 5, 16) + ", " + cores(0, 1, 2, 16) + "]");
        assertEquals(List.of(1, 2), coreIds(HostPoolCoreManagerImpl.choosePoolCores(tie, 2)));

        register(HOST_ID, cores(0, 6, 7, 16), cores(1, 8, 14, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        assertEquals(List.of(6, 7), coreIds(manager.allocatePoolCores(mockVm(1L, VirtualMachine.State.Starting, HOST_ID), host, 4)));
        assertEquals(List.of(8, 9, 10), coreIds(manager.allocatePoolCores(mockVm(2L, VirtualMachine.State.Starting, HOST_ID), host, 6)));
        assertEquals(List.of(11), coreIds(manager.allocatePoolCores(mockVm(3L, VirtualMachine.State.Starting, HOST_ID), host, 2)));
        assertEquals(3, manager.countFreePoolCores(HOST_ID));
        assertEquals(6, db.all().size());
    }

    @Test
    public void assignedCoresCarryTheirSiblingThreads() throws Exception {
        register(HOST_ID, "{\"node\": 0, \"threads\": [8, 24]}, {\"node\": 0, \"threads\": [9, 25]}");

        List<PoolCore> assigned = newManager(true).allocatePoolCores(mockVm(1L, VirtualMachine.State.Starting, HOST_ID), host, 4);

        assertEquals(2, assigned.size());
        assertEquals(List.of(8, 24), toList(assigned.get(0).getThreads()));
        assertEquals(List.of(9, 25), toList(assigned.get(1).getThreads()));
        assertEquals(0, assigned.get(0).getNode());
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<>();
        for (int v : values) {
            list.add(v);
        }
        return list;
    }

    @Test
    public void systemVmTakesOneWholeCore() throws Exception {
        register(HOST_ID, cores(0, 8, 11, 16));
        HostPoolCoreManagerImpl manager = newManager(true);

        assertEquals(1, manager.allocatePoolCores(mockVm(1L, VirtualMachine.State.Starting, HOST_ID), host, 1).size());
        assertEquals(2, manager.allocatePoolCores(mockVm(2L, VirtualMachine.State.Starting, HOST_ID), host, 3).size());
        assertEquals(1, manager.countFreePoolCores(HOST_ID));
        assertEquals(1, HostPoolCoreManager.coresForVcpus(1));
        assertEquals(1, HostPoolCoreManager.coresForVcpus(2));
        assertEquals(2, HostPoolCoreManager.coresForVcpus(3));
        assertEquals(24, HostPoolCoreManager.coresForVcpus(48));
    }

    @Test
    public void allocationFailsCleanlyWhenTheHostHasTooFewFreeCores() throws Exception {
        register(HOST_ID, cores(0, 8, 9, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        manager.allocatePoolCores(mockVm(1L, VirtualMachine.State.Starting, HOST_ID), host, 2);

        InsufficientServerCapacityException e = assertThrows(InsufficientServerCapacityException.class,
                () -> manager.allocatePoolCores(mockVm(2L, VirtualMachine.State.Starting, HOST_ID), host, 4));

        assertEquals(Host.class, e.getScope());
        assertEquals(HOST_ID, e.getId());
        assertEquals(1, db.all().size());
        assertTrue(db.byVm(2L).isEmpty());
    }

    @Test
    public void hostWithoutPoolCoresOffersNoCpuOnlyCapacity() {
        HostPoolCoreManagerImpl manager = newManager(true);

        assertTrue(manager.listPoolCores(HOST_ID).isEmpty());
        assertEquals(0, manager.countFreePoolCores(HOST_ID));
        assertThrows(InsufficientServerCapacityException.class,
                () -> manager.allocatePoolCores(mockVm(1L, VirtualMachine.State.Starting, HOST_ID), host, 2));
    }

    @Test
    public void allocationOnAnotherHostReplacesTheVmsEarlierCores() throws Exception {
        HostVO other = mockHost(OTHER_HOST_ID, HypervisorType.KVM);
        register(HOST_ID, cores(0, 8, 9, 16));
        register(OTHER_HOST_ID, cores(0, 30, 31, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO vm = mockVm(1L, VirtualMachine.State.Starting, HOST_ID);
        manager.allocatePoolCores(vm, host, 4);

        manager.allocatePoolCores(vm, other, 2);

        assertEquals(List.of(OTHER_HOST_ID), db.byVm(1L).stream().map(HostCoreAssignmentVO::getHostId).collect(Collectors.toList()));
        assertEquals(2, manager.countFreePoolCores(HOST_ID));
    }

    @Test
    public void releasePoolCoresFreesTheCoresOfAVmThatNoLongerRuns() throws Exception {
        register(HOST_ID, cores(0, 8, 11, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO stopped = mockVm(1L, VirtualMachine.State.Starting, HOST_ID);
        VMInstanceVO running = mockVm(2L, VirtualMachine.State.Starting, HOST_ID);
        VMInstanceVO removed = mockVm(3L, VirtualMachine.State.Starting, HOST_ID);
        manager.allocatePoolCores(stopped, host, 2);
        manager.allocatePoolCores(running, host, 2);
        manager.allocatePoolCores(removed, host, 2);
        when(stopped.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(running.getState()).thenReturn(VirtualMachine.State.Running);
        when(removed.getRemoved()).thenReturn(new Date());

        for (long vmId = 1; vmId <= 3; vmId++) {
            manager.releasePoolCores(vmId);
        }

        assertTrue(db.byVm(1L).isEmpty());
        assertEquals(1, db.byVm(2L).size());
        assertTrue(db.byVm(3L).isEmpty());
        assertEquals(3, manager.countFreePoolCores(HOST_ID));
    }

    @Test
    public void delayedReleaseLeavesTheCoresOfALaterStart() throws Exception {
        HostVO other = mockHost(OTHER_HOST_ID, HypervisorType.KVM);
        register(HOST_ID, cores(0, 8, 9, 16));
        register(OTHER_HOST_ID, cores(0, 30, 31, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO vm = mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        manager.allocatePoolCores(vm, host, 4);
        // The VM stops, and is started on another host before the stop's release runs.
        when(vm.getState()).thenReturn(VirtualMachine.State.Starting);
        lenient().when(vm.getHostId()).thenReturn(OTHER_HOST_ID);
        manager.allocatePoolCores(vm, other, 2);

        manager.releasePoolCores(1L);

        assertEquals(List.of("HostCoreAssignment {host: 11, core: 30, vm: 1}"), db.byVm(1L).stream().map(Object::toString).collect(Collectors.toList()));
        assertEquals(2, manager.countFreePoolCores(HOST_ID));
        assertEquals(1, manager.countFreePoolCores(OTHER_HOST_ID));

        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        manager.releasePoolCores(1L);
        assertTrue(db.byVm(1L).isEmpty());
    }

    @Test
    public void concurrentStartsGetDisjointCores() throws Exception {
        register(HOST_ID, cores(0, 0, 3, 8), cores(1, 4, 7, 8));
        HostPoolCoreManagerImpl manager = newManager(true);
        for (int round = 0; round < 50; round++) {
            db.clearAssignments();
            int starts = 12;
            CyclicBarrier start = new CyclicBarrier(starts);
            List<Future<List<PoolCore>>> results = new ArrayList<>();
            for (int i = 0; i < starts; i++) {
                VMInstanceVO vm = mockVm(100L + i, VirtualMachine.State.Starting, HOST_ID);
                int vcpus = i % 3 == 0 ? 4 : 2;
                results.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return manager.allocatePoolCores(vm, host, vcpus);
                }));
            }
            Set<Integer> taken = new HashSet<>();
            int assigned = 0;
            for (Future<List<PoolCore>> result : results) {
                try {
                    for (PoolCore core : result.get(30, TimeUnit.SECONDS)) {
                        assertTrue("core " + core + " assigned twice", taken.add(core.getCore()));
                        assigned++;
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof InsufficientServerCapacityException);
                }
            }
            assertEquals(8, assigned);
            assertEquals(8, db.all().size());
        }
    }

    @Test
    public void uniqueKeyRefusesADuplicateCoreTakenPastTheLock() throws Exception {
        register(HOST_ID, cores(0, 8, 8, 16));
        HostPoolCoreManagerImpl unlocked = newManager(false);
        db.afterRead = new CyclicBarrier(2);
        List<Future<List<PoolCore>>> results = new ArrayList<>();
        for (long vmId = 1; vmId <= 2; vmId++) {
            VMInstanceVO vm = mockVm(vmId, VirtualMachine.State.Starting, HOST_ID);
            results.add(executor.submit(() -> unlocked.allocatePoolCores(vm, host, 2)));
        }
        int succeeded = 0;
        int refused = 0;
        for (Future<List<PoolCore>> result : results) {
            try {
                assertEquals(List.of(8), coreIds(result.get(30, TimeUnit.SECONDS)));
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof InsufficientServerCapacityException);
                refused++;
            }
        }
        db.afterRead = null;

        assertEquals(1, succeeded);
        assertEquals(1, refused);
        assertEquals(1, db.all().size());
    }

    @Test
    public void stopRacingStartNeverSharesACore() throws Exception {
        register(HOST_ID, cores(0, 8, 9, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO stopping = mockVm(1L, VirtualMachine.State.Stopped, null);
        VMInstanceVO starting = mockVm(2L, VirtualMachine.State.Starting, HOST_ID);
        for (int round = 0; round < 200; round++) {
            db.clearAssignments();
            manager.allocatePoolCores(stopping, host, 4);
            CyclicBarrier go = new CyclicBarrier(2);
            Future<?> stop = executor.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                manager.releasePoolCores(1L);
                return null;
            });
            Future<List<PoolCore>> start = executor.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return manager.allocatePoolCores(starting, host, 2);
            });
            stop.get(30, TimeUnit.SECONDS);
            try {
                assertEquals(1, start.get(30, TimeUnit.SECONDS).size());
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof InsufficientServerCapacityException);
            }
            assertTrue(db.byVm(1L).isEmpty());
            Set<Integer> held = new HashSet<>();
            for (HostCoreAssignmentVO row : db.all()) {
                assertTrue(held.add(row.getCore()));
            }
        }
    }

    // Sweep

    @Test
    public void assignmentSweepRemovesStaleRows() {
        mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        mockVm(2L, VirtualMachine.State.Starting, HOST_ID);
        mockVm(3L, VirtualMachine.State.Stopping, HOST_ID);
        mockVm(4L, VirtualMachine.State.Stopped, null);
        mockVm(5L, VirtualMachine.State.Running, OTHER_HOST_ID);
        mockVm(6L, VirtualMachine.State.Error, HOST_ID);
        mockVm(7L, VirtualMachine.State.Stopped, HOST_ID);
        when(mockVm(8L, VirtualMachine.State.Running, HOST_ID).getRemoved()).thenReturn(new Date());
        for (long vmId = 1; vmId <= 8; vmId++) {
            db.put(HOST_ID, (int) vmId, vmId);
        }
        db.put(OTHER_HOST_ID, 5, 5L);

        newManager(true).removeStaleAssignments(HOST_ID);

        assertEquals(List.of(1, 2, 3), db.byHost(HOST_ID).stream().map(HostCoreAssignmentVO::getCore).collect(Collectors.toList()));
        assertEquals(1, db.byHost(OTHER_HOST_ID).size());
    }

    @Test
    public void hostRemovalRemovesItsAssignmentsThenItsRegistration() throws Exception {
        HostVO other = mockHost(OTHER_HOST_ID, HypervisorType.KVM);
        register(HOST_ID, cores(0, 8, 9, 16));
        register(OTHER_HOST_ID, cores(0, 30, 31, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        manager.allocatePoolCores(mockVm(1L, VirtualMachine.State.Running, HOST_ID), host, 2);
        manager.allocatePoolCores(mockVm(2L, VirtualMachine.State.Running, OTHER_HOST_ID), other, 2);

        manager.removeHostPoolCores(HOST_ID);

        assertTrue(db.byHost(HOST_ID).isEmpty());
        assertTrue(manager.listPoolCores(HOST_ID).isEmpty());
        assertEquals(1, db.byHost(OTHER_HOST_ID).size());
        assertEquals(2, manager.listPoolCores(OTHER_HOST_ID).size());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(hostCoreAssignmentDao, hostPoolCoreDao);
        order.verify(hostCoreAssignmentDao).removeByHostId(HOST_ID);
        order.verify(hostPoolCoreDao).removeByHostId(HOST_ID);
    }

    @Test
    public void heldStatesAreTheStatesInWhichAVmMayRun() {
        for (VirtualMachine.State state : VirtualMachine.State.values()) {
            boolean expected = state != VirtualMachine.State.Stopped && state != VirtualMachine.State.Destroyed
                    && state != VirtualMachine.State.Expunging && state != VirtualMachine.State.Error;
            assertEquals(state.name(), expected, HostPoolCoreManager.holdsCores(state));
        }
        assertFalse(HostPoolCoreManager.usesPoolCores(null));
        assertTrue(HostPoolCoreManager.usesPoolCores(host));
        assertFalse(HostPoolCoreManager.usesPoolCores(mockHost(OTHER_HOST_ID, HypervisorType.VMware)));
    }

    // Restart within a running session

    @Test
    public void forcedRebootKeepsItsCoresWhileAnotherStartRacesForThem() throws Exception {
        register(HOST_ID, cores(0, 8, 9, 16), cores(1, 10, 11, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO rebooting = mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        assertEquals(List.of(8, 9), coreIds(manager.allocatePoolCores(rebooting, host, 4)));
        VMInstanceVO neighbour = mockVm(3L, VirtualMachine.State.Running, HOST_ID);
        assertEquals(List.of(10), coreIds(manager.allocatePoolCores(neighbour, host, 2)));
        for (int round = 0; round < 100; round++) {
            // the forced reboot's intermediate stop: the release and the sweep keep the cores of the restarting VM
            db.restarting.add(1L);
            when(rebooting.getState()).thenReturn(VirtualMachine.State.Stopped);
            when(rebooting.getHostId()).thenReturn(null);
            manager.releasePoolCores(1L);
            manager.removeStaleAssignments(HOST_ID);
            assertEquals(List.of(8, 9), db.byVm(1L).stream().map(HostCoreAssignmentVO::getCore).sorted().collect(Collectors.toList()));
            assertEquals(1, manager.countFreePoolCores(HOST_ID));
            assertEquals(3, manager.countFreePoolCores(HOST_ID, 1L));

            VMInstanceVO racer = mockVm(100L + round, VirtualMachine.State.Starting, HOST_ID);
            when(rebooting.getState()).thenReturn(VirtualMachine.State.Starting);
            when(rebooting.getHostId()).thenReturn(HOST_ID);
            CyclicBarrier go = new CyclicBarrier(2);
            Future<List<PoolCore>> restart = executor.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return manager.allocatePoolCores(rebooting, host, 4);
            });
            Future<List<PoolCore>> race = executor.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return manager.allocatePoolCores(racer, host, 4);
            });
            assertEquals("the restart reuses exactly the cores it held", List.of(8, 9), coreIds(restart.get(30, TimeUnit.SECONDS)));
            try {
                race.get(30, TimeUnit.SECONDS);
                org.junit.Assert.fail("a racing start took two cores of a host with one free core");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof InsufficientServerCapacityException);
            }
            db.restarting.remove(1L);
            when(rebooting.getState()).thenReturn(VirtualMachine.State.Running);
            Set<Integer> held = new HashSet<>();
            for (HostCoreAssignmentVO row : db.all()) {
                assertTrue("core " + row.getCore() + " held twice", held.add(row.getCore()));
            }
            assertEquals(Set.of(8, 9, 10), held);
        }
    }

    @Test
    public void restartKeepsTheCoresItHoldsRatherThanABetterFit() throws Exception {
        register(HOST_ID, cores(0, 8, 9, 16), cores(1, 10, 13, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO vm = mockVm(1L, VirtualMachine.State.Starting, HOST_ID);
        db.put(HOST_ID, 12, 1L);
        db.put(HOST_ID, 13, 1L);
        org.mockito.Mockito.clearInvocations(hostCoreAssignmentDao);

        assertEquals("a fresh choice would take node 0", List.of(8, 9), coreIds(HostPoolCoreManagerImpl.choosePoolCores(
                manager.listPoolCores(HOST_ID).stream().filter(c -> c.getCore() < 12).collect(Collectors.toList()), 2)));
        assertEquals(List.of(12, 13), coreIds(manager.allocatePoolCores(vm, host, 4)));
        verify(hostCoreAssignmentDao, never()).assign(anyLong(), anyLong(), anyList());

        assertEquals("a VM holding fewer cores than it now needs gets a new set", 3, manager.allocatePoolCores(vm, host, 6).size());
        verify(hostCoreAssignmentDao).assign(anyLong(), eq(1L), anyList());
    }

    @Test
    public void restartThatEndsWithTheVmStoppedReleasesItsCores() throws Exception {
        register(HOST_ID, cores(0, 8, 9, 16));
        HostPoolCoreManagerImpl manager = newManager(true);
        VMInstanceVO vm = mockVm(1L, VirtualMachine.State.Running, HOST_ID);
        manager.allocatePoolCores(vm, host, 4);
        db.restarting.add(1L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        manager.releasePoolCores(1L);
        assertEquals(2, db.byVm(1L).size());

        db.restarting.remove(1L);
        manager.releasePoolCores(1L);

        assertTrue(db.byVm(1L).isEmpty());
        assertEquals(2, manager.countFreePoolCores(HOST_ID));
    }
}
