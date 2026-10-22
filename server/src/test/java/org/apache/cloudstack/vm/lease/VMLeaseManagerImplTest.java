/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cloudstack.vm.lease;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.command.admin.vm.SetVirtualMachineLeaseCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.RenewVirtualMachineLeaseCmd;
import org.apache.cloudstack.api.command.user.vm.StopVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobDispatcher;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageSubscriber;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.invocation.Invocation;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ApplicationContext;

import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.capacity.CapacityManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.configuration.Resource;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.ha.HaWorkVO;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.dao.HighAvailabilityDao;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VMLeaseManagerImplTest {
    public static final String DESTROY = "DESTROY";
    public static final String VM_UUID = UUID.randomUUID().toString();
    public static final String VM_NAME = "vm-name";

    private static final long HOUR = 3_600_000L;
    private static final long VM_ID = 7L;
    private static final long OWNER_ID = 5L;
    private static final long GPU_OFFERING_ID = 21L;
    private static final long CPU_OFFERING_ID = 20L;
    private static final long TEMPLATE_ID = 30L;
    private static final Date NOW = new Date(1_800_000_000_000L);

    @Spy
    @InjectMocks
    private VMLeaseManagerImpl vmLeaseManager;

    @Mock
    private UserVmJoinDao userVmJoinDao;

    @Mock
    MessageBus messageBus;

    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Mock
    private AsyncJobManager asyncJobManager;

    @Mock
    private AsyncJobDispatcher asyncJobDispatcher;

    @Mock
    private GlobalLock globalLock;

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private AccountDao accountDao;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private CapacityManager capacityManager;
    @Mock
    private HighAvailabilityDao haDao;
    @Mock
    private ManagementServerHostDao managementServerHostDao;
    @Mock
    private AsyncJobDao asyncJobDao;

    /** vm_instance_details: VM id -> name -> value */
    private final Map<Long, Map<String, String>> vmDetails = new ConcurrentHashMap<>();
    /** service_offering_details: offering id -> name -> value */
    private final Map<Long, Map<String, String>> offeringDetails = new ConcurrentHashMap<>();
    private final Map<ConfigKey<?>, String> originalDefaults = new HashMap<>();
    private final List<String> mails = new ArrayList<>();
    private Date now = NOW;
    private AccountVO owner;

    @Before
    public void setUp() {
        vmLeaseManager.setAsyncJobDispatcher(asyncJobDispatcher);
        lenient().when(asyncJobDispatcher.getName()).thenReturn("AsyncJobDispatcher");
        lenient().when(asyncJobManager.submitAsyncJob(any(AsyncJobVO.class))).thenReturn(1L);
        useDetailTables();
        lenient().doAnswer(i -> now).when(vmLeaseManager).now();
        lenient().doAnswer(i -> ((Supplier<?>) i.getArgument(1)).get()).when(vmLeaseManager).underLeaseLock(anyLong(), any());
        lenient().doAnswer(i -> {
            mails.add(i.getArgument(1));
            return null;
        }).when(vmLeaseManager).mailAccount(any(), anyString(), anyString());
        owner = mock(AccountVO.class);
        lenient().when(owner.getId()).thenReturn(OWNER_ID);
        lenient().when(owner.getDomainId()).thenReturn(1L);
        lenient().when(accountDao.findById(OWNER_ID)).thenReturn(owner);
        offeringDetails.put(GPU_OFFERING_ID, new ConcurrentHashMap<>(Map.of(VMLeaseManager.LEASE_HOURS, "36")));
        CallContext.register(mock(User.class), mock(Account.class));
        try {
            vmLeaseManager.configure("VMLeaseManagerImpl", new HashMap<>());
        } catch (ConfigurationException e) {
            throw new CloudRuntimeException(e);
        }
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        AsyncJobExecutionContext.unregister();
        for (Map.Entry<ConfigKey<?>, String> original : originalDefaults.entrySet()) {
            overrideDefaultConfigValue(original.getKey(), original.getValue());
        }
    }

    /** The current thread runs the given job, as an API job, a work job or a thread's pseudo job does. */
    private static void inJob(long id, String dispatcher) {
        AsyncJobVO job = new AsyncJobVO();
        job.setId(id);
        job.setDispatcher(dispatcher);
        AsyncJobExecutionContext context = new AsyncJobExecutionContext();
        context.setJob(job);
        AsyncJobExecutionContext.setCurrentExecutionContext(context);
    }

    /** The run of this management server, as a restart begun outside any job names it. */
    private String serverRun() {
        ManagementServerHostVO ms = mock(ManagementServerHostVO.class);
        lenient().when(ms.getRunid()).thenReturn(6789L);
        lenient().when(managementServerHostDao.findByMsid(ManagementServerNode.getManagementServerId())).thenReturn(ms);
        return "ms:" + ManagementServerNode.getManagementServerId() + ":6789";
    }

    /** Delivers a completed job to whatever the manager subscribed to job events while leases are in force. */
    private void completeJob(AsyncJobVO job) {
        vmLeaseManager.onLeaseFeatureToggle();
        for (Invocation invocation : Mockito.mockingDetails(messageBus).getInvocations()) {
            if ("subscribe".equals(invocation.getMethod().getName()) && AsyncJob.Topics.JOB_EVENT_PUBLISH.equals(invocation.getArgument(0))) {
                ((MessageSubscriber) invocation.getArgument(1)).onPublishMessage("test", AsyncJob.Topics.JOB_EVENT_PUBLISH, new Pair<>(job, "complete"));
            }
        }
    }

    private AsyncJobVO expiryStopJob(long vmId) {
        Map<String, String> params = new HashMap<>();
        params.put("id", String.valueOf(vmId));
        params.put(VMLeaseManagerImpl.JOB_INITIATOR, VMLeaseManager.class.getSimpleName());
        AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, OWNER_ID, StopVMCmd.class.getName(), new com.google.gson.Gson().toJson(params), vmId,
                "VirtualMachine", null);
        job.setDispatcher("ApiAsyncJobDispatcher");
        job.setStatus(JobInfo.Status.SUCCEEDED);
        return job;
    }

    private void useDetailTables() {
        lenient().doAnswer(i -> {
            vmDetails.computeIfAbsent(i.getArgument(0), id -> new ConcurrentHashMap<>()).put(i.getArgument(1), i.getArgument(2));
            return null;
        }).when(vmInstanceDetailsDao).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
        lenient().doAnswer(i -> {
            Map<String, String> details = vmDetails.get((Long) i.getArgument(0));
            if (details != null) {
                details.remove((String) i.getArgument(1));
            }
            return null;
        }).when(vmInstanceDetailsDao).removeDetail(anyLong(), anyString());
        lenient().when(vmInstanceDetailsDao.findDetail(anyLong(), anyString())).thenAnswer(i -> {
            String value = vmDetails.getOrDefault((Long) i.getArgument(0), Map.of()).get((String) i.getArgument(1));
            return value == null ? null : new VMInstanceDetailVO(i.getArgument(0), i.getArgument(1), value, false);
        });
        lenient().when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList())).thenAnswer(i -> {
            Map<String, String> result = new HashMap<>();
            Map<String, String> details = vmDetails.getOrDefault((Long) i.getArgument(0), Map.of());
            for (Object name : (List<?>) i.getArgument(1)) {
                if (details.containsKey(name)) {
                    result.put((String) name, details.get(name));
                }
            }
            return result;
        });
        lenient().when(vmInstanceDetailsDao.findDetails(anyString())).thenAnswer(i -> vmDetails.entrySet().stream()
                .filter(e -> e.getValue().containsKey((String) i.getArgument(0)))
                .map(e -> new VMInstanceDetailVO(e.getKey(), i.getArgument(0), e.getValue().get((String) i.getArgument(0)), false))
                .collect(Collectors.toList()));
        lenient().when(serviceOfferingDetailsDao.getDetail(anyLong(), anyString())).thenAnswer(i ->
                offeringDetails.getOrDefault((Long) i.getArgument(0), Map.of()).get((String) i.getArgument(1)));
        lenient().when(serviceOfferingDetailsDao.findDetails(VMLeaseManager.LEASE_HOURS)).thenAnswer(i -> offeringDetails.entrySet().stream()
                .filter(e -> e.getValue().containsKey(VMLeaseManager.LEASE_HOURS))
                .map(e -> new ServiceOfferingDetailsVO(e.getKey(), VMLeaseManager.LEASE_HOURS, e.getValue().get(VMLeaseManager.LEASE_HOURS), false))
                .collect(Collectors.toList()));
    }

    private void setDefault(ConfigKey<?> key, String value) throws Exception {
        originalDefaults.putIfAbsent(key, key.defaultValue());
        overrideDefaultConfigValue(key, value);
    }

    private void leaseEnabled(boolean enabled) throws Exception {
        setDefault(VMLeaseManager.InstanceLeaseEnabled, String.valueOf(enabled));
    }

    private UserVmVO gpuVm(State state) {
        return vm(VM_ID, state, GPU_OFFERING_ID);
    }

    private UserVmVO vm(long id, State state, long offeringId) {
        UserVmVO vm = mock(UserVmVO.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getUuid()).thenReturn("vm-" + id);
        lenient().when(vm.getHostName()).thenReturn("vm" + id);
        lenient().when(vm.getInstanceName()).thenReturn("i-5-" + id + "-VM");
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        lenient().when(vm.getServiceOfferingId()).thenReturn(offeringId);
        lenient().when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        lenient().when(vm.getAccountId()).thenReturn(OWNER_ID);
        lenient().when(vm.getDomainId()).thenReturn(1L);
        lenient().when(vm.getUserId()).thenReturn(2L);
        lenient().when(userVmDao.findById(id)).thenReturn(vm);
        lenient().when(vmInstanceDao.findById(id)).thenReturn(vm);
        lenient().when(vmInstanceDao.findByIdIncludingRemoved(id)).thenReturn(vm);
        return vm;
    }

    private void transit(VirtualMachine vm, State from, Event event, State to) {
        assertTrue(vmLeaseManager.postStateTransitionEvent(new StateMachine2.Transition<>(from, event, to, null), vm, true, null));
    }

    private void start(VirtualMachine vm) {
        transit(vm, State.Stopped, Event.StartRequested, State.Starting);
        transit(vm, State.Starting, Event.OperationSucceeded, State.Running);
    }

    private Map<String, String> details(long vmId) {
        return vmDetails.getOrDefault(vmId, Map.of());
    }

    private static SimpleDateFormat utc() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private Date expiry(long vmId) throws ParseException {
        String value = details(vmId).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);
        return value == null ? null : utc().parse(value);
    }

    private void giveLease(long vmId, Date expiry, String action) {
        Map<String, String> details = vmDetails.computeIfAbsent(vmId, id -> new ConcurrentHashMap<>());
        details.put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, utc().format(expiry));
        details.put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, action);
        details.put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, VMLeaseManager.LeaseActionExecution.PENDING.name());
    }

    private static Date in(long millis) {
        return new Date(NOW.getTime() + millis);
    }

    private void assertNoLease(long vmId) {
        Map<String, String> details = details(vmId);
        for (String name : List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION,
                VmDetailConstants.INSTANCE_LEASE_EXECUTION, VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING)) {
            assertFalse(name + " left on " + vmId, details.containsKey(name));
        }
    }

    private void quotaInputs(UserVmVO vm) {
        lenient().when(serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId())).thenReturn(mock(ServiceOfferingVO.class));
        lenient().when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(mock(VMTemplateVO.class));
    }

    private UserVmJoinVO row(long id) {
        UserVmJoinVO row = mock(UserVmJoinVO.class);
        lenient().when(row.getId()).thenReturn(id);
        return row;
    }

    private void runWarnings(long... vmIds) {
        List<UserVmJoinVO> rows = new ArrayList<>();
        for (long id : vmIds) {
            rows.add(row(id));
        }
        lenient().when(userVmJoinDao.listInstancesWithPendingLease(any(Date.class))).thenReturn(rows);
        vmLeaseManager.sendExpiryWarnings();
    }

    private static void assertWarnings(MockedStatic<ActionEventUtils> events, int count) {
        events.verify(() -> ActionEventUtils.onActionEvent(anyLong(), anyLong(), anyLong(), eq(EventTypes.VM_LEASE_EXPIRING), anyString(),
                anyLong(), anyString()), times(count));
    }

    // Sessions

    @Test
    public void leaseStartsAtStartAndClearsAtStop() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        Object[][] stops = {
                {State.Stopping, Event.OperationSucceeded},          // API stop, maintenance force-stop, expiry stop
                {State.Running, Event.FollowAgentPowerOffReport},    // guest power-off
                {State.Running, Event.AgentReportStopped},
                {State.Starting, Event.OperationFailed},
        };
        for (Object[] stop : stops) {
            vmDetails.clear();
            start(vm);
            assertEquals(in(36 * HOUR), expiry(VM_ID));
            assertEquals("STOP", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION));
            assertEquals("PENDING", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));

            transit(vm, (State) stop[0], (Event) stop[1], State.Stopped);

            assertNoLease(VM_ID);
        }
        transit(vm, State.Running, Event.FollowAgentPowerOnReport, State.Running);
        transit(vm, State.Migrating, Event.OperationSucceeded, State.Running);
        transit(vm, State.Stopping, Event.OperationFailed, State.Running);
        assertNoLease(VM_ID);
    }

    @Test
    public void startResetsToThirtySixHours() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        start(vm);
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        now = in(10 * HOUR);

        start(vm);

        assertEquals(in(46 * HOUR), expiry(VM_ID));
        assertNull(details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
    }

    @Test
    public void renewResetsToThirtySixHours() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        quotaInputs(vm);
        giveLease(VM_ID, in(30 * HOUR), "STOP");
        vmDetails.get(VM_ID).put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING, "4");

        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            assertEquals(vm, vmLeaseManager.renewLease(VM_ID));
            events.verify(() -> ActionEventUtils.onActionEvent(anyLong(), eq(OWNER_ID), eq(1L), eq(EventTypes.VM_LEASE_RENEW), anyString(), eq(VM_ID), anyString()));
        }

        assertEquals("a reset to now + 36 h, not 30 + 36 h", in(36 * HOUR), expiry(VM_ID));
        assertNull("a new lease is warned again", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
        verify(accountManager).checkAccess(any(Account.class), isNull(), eq(true), eq(vm));

        UserVmVO stopped = vm(8L, State.Stopped, GPU_OFFERING_ID);
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.renewLease(8L));
        assertTrue(e.getMessage(), e.getMessage().contains("while it runs"));
        assertNoLease(stopped.getId());

        giveLease(VM_ID, in(-60_000), "STOP");
        e = assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.renewLease(VM_ID));
        assertTrue("an ended lease is not renewed: " + e.getMessage(), e.getMessage().contains("has ended"));
    }

    @Test
    public void renewRefusedWhenOverGpuQuota() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        quotaInputs(vm);
        giveLease(VM_ID, in(3 * HOUR), "STOP");
        doThrow(new ResourceAllocationException("quota expired or insufficient: the gpu count for tag a100-40g of account student is 1, above its limit of 0",
                Resource.ResourceType.gpu)).when(resourceLimitService).checkWithinQuota(eq(owner), any(), any());

        ResourceAllocationException e = assertThrows(ResourceAllocationException.class, () -> vmLeaseManager.renewLease(VM_ID));

        assertTrue(e.getMessage().startsWith("quota expired or insufficient"));
        assertEquals("the lease runs out unchanged", in(3 * HOUR), expiry(VM_ID));
        now = in(3 * HOUR + 60_000);
        UserVmJoinVO expired = row(VM_ID);
        when(expired.getLeaseExpiryAction()).thenReturn("STOP");
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(List.of(expired));
        when(userVmJoinDao.findById(VM_ID)).thenReturn(expired);
        doReturn(1L).when(vmLeaseManager).executeStopInstanceJob(eq(expired), anyLong());
        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.reallyRun();
        }
        verify(vmLeaseManager).executeStopInstanceJob(eq(expired), anyLong());
    }

    @Test
    public void expiryStopsVmAndFreesGpu() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        giveLease(VM_ID, in(-60_000), "STOP");
        UserVmJoinVO expired = row(VM_ID);
        when(expired.getLeaseExpiryAction()).thenReturn("STOP");
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(List.of(expired));
        when(userVmJoinDao.findById(VM_ID)).thenReturn(expired);

        try (MockedStatic<ComponentContext> context = Mockito.mockStatic(ComponentContext.class);
             MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            context.when(ComponentContext::getApplicationContext).thenReturn(mock(ApplicationContext.class));
            context.when(() -> ComponentContext.inject(any())).thenReturn(true);
            vmLeaseManager.reallyRun();
        }

        ArgumentCaptor<AsyncJobVO> job = ArgumentCaptor.forClass(AsyncJobVO.class);
        verify(asyncJobManager).submitAsyncJob(job.capture());
        assertEquals("a plain stop, whose transition into Stopped releases the GPU devices", StopVMCmd.class.getName(), job.getValue().getCmd());
        assertNull("the expiry stop is no restart", details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        assertNoLease(VM_ID);
    }

    // Warnings

    @Test
    public void warningsAtFourAndOneHours() throws Exception {
        leaseEnabled(true);
        gpuVm(State.Running);
        giveLease(VM_ID, in(36 * HOUR), "STOP");
        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            runWarnings(VM_ID);
            assertWarnings(events, 0);

            now = in(32 * HOUR + 1);
            runWarnings(VM_ID);
            runWarnings(VM_ID);
            assertWarnings(events, 1);
            assertEquals("4", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));

            now = in(35 * HOUR + 1);
            runWarnings(VM_ID);
            runWarnings(VM_ID);
            assertWarnings(events, 2);
            assertEquals("1", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
        }
        assertEquals(2, mails.size());
        assertTrue(mails.get(0), mails.get(0).contains("4 hours"));
        assertTrue(mails.get(1), mails.get(1).contains("1 hour"));
    }

    @Test
    public void warningHoursAreASetting() throws Exception {
        leaseEnabled(true);
        setDefault(VMLeaseManager.InstanceLeaseExpiryEventHoursBefore, "24,2");
        gpuVm(State.Running);
        giveLease(VM_ID, in(36 * HOUR), "STOP");
        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            now = in(13 * HOUR);
            runWarnings(VM_ID);
            assertEquals("24", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
            now = in(34 * HOUR + 30 * 60_000);
            runWarnings(VM_ID);
            assertEquals("2", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
            assertWarnings(events, 2);

            setDefault(VMLeaseManager.InstanceLeaseExpiryEventHoursBefore, "");
            vmDetails.get(VM_ID).remove(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING);
            now = in(35 * HOUR + 59 * 60_000);
            runWarnings(VM_ID);
            assertWarnings(events, 2);
        }

        ValidatedConfigKey<String> key = (ValidatedConfigKey<String>) VMLeaseManager.InstanceLeaseExpiryEventHoursBefore;
        assertEquals("instance.lease.expiryevent.hoursbefore", key.key());
        assertEquals("4,1", originalDefaults.get(key));
        assertTrue(key.isDynamic());
        assertEquals(ConfigKey.Scope.Global, key.getScopes().get(0));
        key.validateValue("24, 2");
        key.validateValue("");
        for (String invalid : List.of("0", "-1", "x", "4;1", "1.5")) {
            assertThrows(invalid, InvalidParameterValueException.class, () -> key.validateValue(invalid));
        }
        assertEquals(List.of(1, 4), new ArrayList<>(VMLeaseManager.parseWarningHours("4, 1,4")));
    }

    @Test
    public void warningForExpirySetInsideWindowFiresOnceAtOnce() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        giveLease(VM_ID, in(36 * HOUR), "STOP");
        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.setLease(VM_ID, in(5 * 60_000));
            assertWarnings(events, 1);
            assertEquals("the 1 h window's warning at once", "1", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));

            runWarnings(VM_ID);
            now = in(4 * 60_000);
            runWarnings(VM_ID);
            assertWarnings(events, 1);

            quotaInputs(vm);
            now = NOW;
            giveLease(VM_ID, in(2 * HOUR), "STOP");
            vmLeaseManager.renewLease(VM_ID);
            vmLeaseManager.setLease(VM_ID, in(30 * HOUR));
            assertWarnings(events, 1);
        }
        assertEquals(1, mails.size());
    }

    // Settings

    @Test
    public void leaseLengthFollowsOfferingLeasehours() throws Exception {
        leaseEnabled(true);
        offeringDetails.get(GPU_OFFERING_ID).put(VMLeaseManager.LEASE_HOURS, "48");
        UserVmVO vm = gpuVm(State.Running);

        start(vm);
        assertEquals(in(48 * HOUR), expiry(VM_ID));

        quotaInputs(vm);
        now = in(40 * HOUR);
        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.renewLease(VM_ID);
        }
        assertEquals(in(88 * HOUR), expiry(VM_ID));
    }

    @Test
    public void expiryActionComesFromTheOfferingDetail() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        start(vm);
        assertEquals("STOP by default", "STOP", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION));
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);

        offeringDetails.get(GPU_OFFERING_ID).put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, DESTROY);
        start(vm);
        assertEquals("the listener no longer hardcodes STOP", DESTROY, details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION));

        UserVmJoinVO expired = createMockVm(VM_ID, VM_UUID, VM_NAME, State.Running, false, DESTROY);
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(List.of(expired));
        when(userVmJoinDao.findById(VM_ID)).thenReturn(expired);
        doReturn(1L).when(vmLeaseManager).executeDestroyInstanceJob(eq(expired), anyLong());
        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.reallyRun();
        }
        verify(vmLeaseManager).executeDestroyInstanceJob(eq(expired), anyLong());
        verify(vmLeaseManager, never()).executeStopInstanceJob(any(), anyLong());
    }

    @Test
    public void renewalSwitchOffRefuses() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        quotaInputs(vm);
        giveLease(VM_ID, in(3 * HOUR), "STOP");
        setDefault(VMLeaseManager.InstanceLeaseRenewalEnabled, "false");

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.renewLease(VM_ID));

        assertTrue(e.getMessage(), e.getMessage().contains("instance.lease.renewal.enabled"));
        assertEquals(in(3 * HOUR), expiry(VM_ID));
        assertEquals("true", originalDefaults.get(VMLeaseManager.InstanceLeaseRenewalEnabled));
        assertTrue(VMLeaseManager.InstanceLeaseRenewalEnabled.isDynamic());
    }

    @Test
    public void leaseDisabledSettingCancelsStopAndWarnings() throws Exception {
        leaseEnabled(false);
        UserVmVO vm = gpuVm(State.Running);
        lenient().when(vmInstanceDao.listByTypeAndState(VirtualMachine.Type.User, State.Running)).thenReturn(List.of(vm));

        start(vm);
        assertNoLease(VM_ID);
        vmLeaseManager.reallyRun();
        verify(userVmJoinDao, never()).listEligibleInstancesWithExpiredLease();
        assertNoLease(VM_ID);
        giveLease(VM_ID, in(30 * 60_000), "STOP");
        vmLeaseManager.sendExpiryWarnings();
        verify(userVmJoinDao, never()).listInstancesWithPendingLease(any());

        UserVmJoinVO pending = row(VM_ID);
        when(userVmJoinDao.listInstancesWithPendingLease(isNull())).thenReturn(List.of(pending));
        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.onLeaseFeatureToggle();
        }
        assertEquals("CANCELLED", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        assertEquals(0, mails.size());
        verify(asyncJobManager, never()).submitAsyncJob(any());
    }

    @Test
    public void userCannotSetLeaseDirectly() {
        // deploy and updateVirtualMachine refuse leaseduration and leaseexpiryaction from callers without setVirtualMachineLease:
        // UserVmManagerImplTest.userCannotSetLeaseDirectly
        APICommand set = SetVirtualMachineLeaseCmd.class.getAnnotation(APICommand.class);
        assertEquals(VMLeaseManager.SET_LEASE_API, set.name());
        assertEquals(List.of(RoleType.Admin), Arrays.asList(set.authorized()));
        APICommand renew = RenewVirtualMachineLeaseCmd.class.getAnnotation(APICommand.class);
        assertEquals(VMLeaseManager.RENEW_LEASE_API, renew.name());
        assertTrue(Arrays.asList(renew.authorized()).contains(RoleType.User));
        assertTrue(vmLeaseManager.getCommands().containsAll(List.of(RenewVirtualMachineLeaseCmd.class, SetVirtualMachineLeaseCmd.class)));
    }

    @Test
    public void adminCanOverrideExpiry() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        start(vm);

        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.setLease(VM_ID, in(200 * HOUR));
            events.verify(() -> ActionEventUtils.onActionEvent(anyLong(), eq(OWNER_ID), eq(1L), eq(EventTypes.VM_LEASE_UPDATE), anyString(), eq(VM_ID), anyString()));
        }
        assertEquals(in(200 * HOUR), expiry(VM_ID));
        assertEquals("PENDING", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));

        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        now = in(HOUR);
        start(vm);
        assertEquals("the next start is the offering's lease again", in(37 * HOUR), expiry(VM_ID));

        UserVmVO stopped = vm(8L, State.Stopped, GPU_OFFERING_ID);
        assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.setLease(stopped.getId(), in(HOUR)));
    }

    @Test
    public void cpuOnlyHasNoLease() throws Exception {
        leaseEnabled(true);
        UserVmVO cpuVm = vm(9L, State.Running, CPU_OFFERING_ID);
        start(cpuVm);
        assertNoLease(9L);

        when(vmInstanceDao.listByTypeAndState(VirtualMachine.Type.User, State.Running)).thenReturn(List.of(cpuVm));
        vmLeaseManager.reallyRun();
        assertNoLease(9L);

        quotaInputs(cpuVm);
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.renewLease(9L));
        assertTrue(e.getMessage(), e.getMessage().contains("no lease"));
    }

    @Test
    public void runningGpuVmWithoutLeaseGetsOneAtNextRun() throws Exception {
        leaseEnabled(true);
        offeringDetails.get(GPU_OFFERING_ID).put(VMLeaseManager.LEASE_HOURS, "48");
        UserVmVO noLease = gpuVm(State.Running);
        UserVmVO cancelled = vm(10L, State.Running, GPU_OFFERING_ID);
        giveLease(10L, in(HOUR), "STOP");
        vmDetails.get(10L).put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, "CANCELLED");
        UserVmVO disabledByAdmin = vm(11L, State.Running, GPU_OFFERING_ID);
        giveLease(11L, in(HOUR), "STOP");
        vmDetails.get(11L).put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, "DISABLED");
        UserVmVO leased = vm(12L, State.Running, GPU_OFFERING_ID);
        giveLease(12L, in(3 * HOUR), "STOP");
        UserVmVO ranOut = vm(13L, State.Running, GPU_OFFERING_ID);
        giveLease(13L, in(-HOUR), "STOP");
        UserVmVO done = vm(14L, State.Running, GPU_OFFERING_ID);
        giveLease(14L, in(-HOUR), "STOP");
        vmDetails.get(14L).put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, "DONE");
        when(vmInstanceDao.listByTypeAndState(VirtualMachine.Type.User, State.Running)).thenReturn(List.of(noLease, cancelled, disabledByAdmin, leased,
                ranOut, done));

        vmLeaseManager.reallyRun();

        assertEquals("now + the offering's leasehours, not a literal 36 h", in(48 * HOUR), expiry(VM_ID));
        assertEquals(in(48 * HOUR), expiry(10L));
        assertEquals("PENDING", details(10L).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        assertEquals("DISABLED", details(11L).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        assertEquals(in(3 * HOUR), expiry(12L));
        assertEquals("a lease that ran out is not given again", in(-HOUR), expiry(13L));
        assertEquals(in(-HOUR), expiry(14L));
        assertEquals("DONE", details(14L).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));
    }

    @Test
    public void expiryAbsorbedByARestartIsEnforcedOnceTheRestartSettles() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        giveLease(VM_ID, in(-60_000), "STOP");
        UserVmJoinVO expired = row(VM_ID);
        lenient().when(expired.getLeaseExpiryAction()).thenReturn("STOP");
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(List.of(expired));
        lenient().when(userVmJoinDao.findById(VM_ID)).thenReturn(expired);
        lenient().doReturn(1L).when(vmLeaseManager).executeStopInstanceJob(eq(expired), anyLong());
        lenient().when(vmInstanceDao.listByTypeAndState(VirtualMachine.Type.User, State.Running)).thenReturn(List.of(vm));
        inJob(5L, "ApiAsyncJobDispatcher");
        String reboot = vmLeaseManager.beginRestart(VM_ID);
        AsyncJobExecutionContext.unregister();
        AsyncJobVO rebootJob = mock(AsyncJobVO.class);
        lenient().when(rebootJob.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        lenient().when(rebootJob.getDispatcher()).thenReturn("ApiAsyncJobDispatcher");
        lenient().when(asyncJobDao.findById(5L)).thenReturn(rebootJob);

        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.reallyRun();
            verify(vmLeaseManager, never()).executeStopInstanceJob(any(), anyLong());

            // the stop of an earlier run went through inside the restart, and the restart brought the Instance back
            completeJob(expiryStopJob(VM_ID));
            vmLeaseManager.endRestart(VM_ID, reboot);
            vmLeaseManager.reallyRun();
        }

        verify(vmLeaseManager).executeStopInstanceJob(eq(expired), anyLong());
        assertEquals("no new lease", in(-60_000), expiry(VM_ID));
        assertEquals("PENDING", details(VM_ID).get(VmDetailConstants.INSTANCE_LEASE_EXECUTION));
    }

    @Test
    public void stopThatLandsWhileALeaseIsWrittenLeavesNoLease() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        quotaInputs(vm);
        giveLease(VM_ID, in(3 * HOUR), "STOP");
        java.util.concurrent.atomic.AtomicReference<State> state = new java.util.concurrent.atomic.AtomicReference<>(State.Running);
        when(vm.getState()).thenAnswer(i -> state.get());
        boolean[] armed = {true};
        Mockito.doAnswer(i -> {
            vmDetails.computeIfAbsent(i.getArgument(0), id -> new ConcurrentHashMap<>()).put(i.getArgument(1), i.getArgument(2));
            if (armed[0] && VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE.equals(i.getArgument(1))) {
                // the stop commits and ends the session right after the renewal wrote the new end
                armed[0] = false;
                state.set(State.Stopped);
                transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
            }
            return null;
        }).when(vmInstanceDetailsDao).addDetail(anyLong(), anyString(), anyString(), anyBoolean());

        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> vmLeaseManager.renewLease(VM_ID));
            assertTrue(e.getMessage(), e.getMessage().contains("while it runs"));
        }

        assertNoLease(VM_ID);
    }

    @Test
    public void warningIsSentOnceTheLeaseLockIsReleased() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        offeringDetails.get(GPU_OFFERING_ID).put(VMLeaseManager.LEASE_HOURS, "3");
        boolean[] locked = {false};
        List<Boolean> lockedWhenSent = new ArrayList<>();
        Mockito.doAnswer(i -> {
            locked[0] = true;
            try {
                return ((Supplier<?>) i.getArgument(1)).get();
            } finally {
                locked[0] = false;
            }
        }).when(vmLeaseManager).underLeaseLock(anyLong(), any());
        Mockito.doAnswer(i -> {
            lockedWhenSent.add(locked[0]);
            return null;
        }).when(vmLeaseManager).mailAccount(any(), anyString(), anyString());

        try (MockedStatic<ActionEventUtils> events = Mockito.mockStatic(ActionEventUtils.class)) {
            events.when(() -> ActionEventUtils.onActionEvent(anyLong(), anyLong(), anyLong(), eq(EventTypes.VM_LEASE_EXPIRING), anyString(),
                    anyLong(), anyString())).thenAnswer(i -> {
                        lockedWhenSent.add(locked[0]);
                        return 1L;
                    });
            // a start inside the 4 h window warns at once, from the transition listener
            start(vm);
            now = in(2 * HOUR + 1);
            runWarnings(VM_ID);
        }

        assertEquals("the event and the mail of each warning, all after the lock", List.of(false, false, false, false), lockedWhenSent);
    }

    @Test
    public void instanceLeavingManagementEndsItsSession() {
        giveLease(VM_ID, in(HOUR), "STOP");
        vmDetails.get(VM_ID).put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING, "4");
        vmDetails.get(VM_ID).put(VmDetailConstants.RESTART_IN_PROGRESS, "job:7");
        vmDetails.get(VM_ID).put("keyboard", "us");

        vmLeaseManager.endSession(VM_ID);

        assertNoLease(VM_ID);
        assertNull(details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        assertEquals("only the session's state goes", "us", details(VM_ID).get("keyboard"));
    }

    @Test
    public void schedulersSkipATickAnotherServerIsRunning() {
        GlobalLock lock = mock(GlobalLock.class);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            vmLeaseManager.new VMLeaseSchedulerTask().runInContext();
            vmLeaseManager.new VMLeaseExpiryEventSchedulerTask().runInContext();
        }
        verify(lock, times(2)).lock(0);
        verify(vmLeaseManager, never()).reallyRun();
        verify(vmLeaseManager, never()).sendExpiryWarnings();
    }

    // Restarts within a running session

    @Test
    public void restartWithinTheSessionKeepsTheLease() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        start(vm);
        Date expiry = expiry(VM_ID);
        inJob(77L, "ApiAsyncJobDispatcher");
        String forcedReboot = vmLeaseManager.beginRestart(VM_ID);
        assertEquals("job:77", forcedReboot);
        now = in(10 * HOUR);

        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        assertEquals("no clear on the intermediate Stopped", expiry, expiry(VM_ID));
        start(vm);
        assertEquals("no reset at the restart", expiry, expiry(VM_ID));
        vmLeaseManager.endRestart(VM_ID, forcedReboot);
        assertNull(details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        assertEquals(expiry, expiry(VM_ID));
        verify(capacityManager, never()).releaseResourcesOfStoppedVm(any());

        // an HA restart, scheduled outside any job and handed over to its work item
        AsyncJobExecutionContext.unregister();
        String run = serverRun();
        String scheduled = vmLeaseManager.beginRestart(VM_ID);
        assertEquals(run, scheduled);
        HaWorkVO work = mock(HaWorkVO.class);
        when(work.getStep()).thenReturn(HighAvailabilityManager.Step.Scheduled);
        when(haDao.findById(9L)).thenReturn(work);
        String ha = vmLeaseManager.handRestartToHaWork(VM_ID, scheduled, 9L);
        assertEquals("ha:9", ha);
        assertEquals("ha:9", details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        transit(vm, State.Running, Event.AgentReportStopped, State.Stopped);
        start(vm);
        assertEquals(expiry, expiry(VM_ID));
        vmLeaseManager.endRestart(VM_ID, scheduled);
        assertEquals("only the marker's owner ends it", "ha:9", details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        vmLeaseManager.endRestart(VM_ID, ha);
        assertNull(details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));

        // the restart of a reinstall: the same marker, begun in the reinstall's work job
        inJob(78L, "VmWorkJobDispatcher");
        String reinstall = vmLeaseManager.beginRestart(VM_ID);
        assertEquals("job:78", reinstall);
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        start(vm);
        vmLeaseManager.endRestart(VM_ID, reinstall);
        assertEquals(expiry, expiry(VM_ID));

        // a start after a stop outside a restart is a new session again
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        assertNoLease(VM_ID);
        start(vm);
        assertEquals(in(46 * HOUR), expiry(VM_ID));
    }

    @Test
    public void restartThatDoesNotComeBackGivesUpTheSession() throws Exception {
        leaseEnabled(true);
        UserVmVO vm = gpuVm(State.Running);
        start(vm);
        serverRun();
        String marker = vmLeaseManager.beginRestart(VM_ID);
        transit(vm, State.Stopping, Event.OperationSucceeded, State.Stopped);
        transit(vm, State.Stopped, Event.StartRequested, State.Starting);
        transit(vm, State.Starting, Event.OperationFailed, State.Stopped);
        when(vm.getState()).thenReturn(State.Stopped);
        assertNotNull("the session holds its lease until the restart ends", expiry(VM_ID));
        verify(capacityManager, never()).releaseResourcesOfStoppedVm(any());

        vmLeaseManager.endRestart(VM_ID, marker);

        assertNull(details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        assertNoLease(VM_ID);
        verify(capacityManager).releaseResourcesOfStoppedVm(vm);
    }

    @Test
    public void restartsWhoseOwnerIsGoneAreEnded() throws Exception {
        UserVmVO jobDone = vm(31L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO jobRunning = vm(32L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO msRestarted = vm(33L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO msUp = vm(34L, State.Starting, GPU_OFFERING_ID);
        UserVmVO haDone = vm(35L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO haPending = vm(36L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO malformed = vm(37L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO pseudoJob = vm(38L, State.Stopped, GPU_OFFERING_ID);
        UserVmVO stillStarting = vm(39L, State.Starting, GPU_OFFERING_ID);
        String[] markers = {"job:1", "job:2", "ms:345:1", "ms:345:2", "ha:3", "ha:4", "x", "job:5", "job:1"};
        for (int i = 0; i < markers.length; i++) {
            vmDetails.computeIfAbsent(31L + i, id -> new ConcurrentHashMap<>()).put(VmDetailConstants.RESTART_IN_PROGRESS, markers[i]);
            giveLease(31L + i, in(HOUR), "STOP");
        }
        AsyncJobVO done = mock(AsyncJobVO.class);
        when(done.getStatus()).thenReturn(JobInfo.Status.FAILED);
        AsyncJobVO running = mock(AsyncJobVO.class);
        when(running.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        lenient().when(running.getDispatcher()).thenReturn("ApiAsyncJobDispatcher");
        // a thread's pseudo job stays in progress for the whole run of its server, whatever the thread does
        AsyncJobVO pseudo = mock(AsyncJobVO.class);
        lenient().when(pseudo.getStatus()).thenReturn(JobInfo.Status.IN_PROGRESS);
        lenient().when(pseudo.getDispatcher()).thenReturn(AsyncJobVO.JOB_DISPATCHER_PSEUDO);
        when(asyncJobDao.findById(1L)).thenReturn(done);
        when(asyncJobDao.findById(2L)).thenReturn(running);
        when(asyncJobDao.findById(5L)).thenReturn(pseudo);
        ManagementServerHostVO ms = mock(ManagementServerHostVO.class);
        when(ms.getState()).thenReturn(ManagementServerHost.State.Up);
        when(ms.getRunid()).thenReturn(2L);
        when(managementServerHostDao.findByMsid(345L)).thenReturn(ms);
        HaWorkVO finished = mock(HaWorkVO.class);
        when(finished.getStep()).thenReturn(HighAvailabilityManager.Step.Done);
        HaWorkVO pending = mock(HaWorkVO.class);
        when(pending.getStep()).thenReturn(HighAvailabilityManager.Step.Scheduled);
        when(haDao.findById(3L)).thenReturn(finished);
        when(haDao.findById(4L)).thenReturn(pending);

        vmLeaseManager.reallyRun();

        for (UserVmVO ended : List.of(jobDone, msRestarted, haDone, malformed, pseudoJob)) {
            assertNull(details(ended.getId()).get(VmDetailConstants.RESTART_IN_PROGRESS));
            assertNoLease(ended.getId());
            verify(capacityManager).releaseResourcesOfStoppedVm(ended);
        }
        for (UserVmVO live : List.of(jobRunning, msUp, haPending)) {
            assertNotNull(details(live.getId()).get(VmDetailConstants.RESTART_IN_PROGRESS));
            assertNotNull(expiry(live.getId()));
        }
        assertEquals("an Instance between states is left until it settles", "job:1", details(39L).get(VmDetailConstants.RESTART_IN_PROGRESS));
        verify(capacityManager, times(5)).releaseResourcesOfStoppedVm(any());

        // it came back: the restart ends and the session goes on
        when(stillStarting.getState()).thenReturn(State.Running);
        vmLeaseManager.reallyRun();
        assertNull(details(39L).get(VmDetailConstants.RESTART_IN_PROGRESS));
        assertNotNull(expiry(39L));
        verify(capacityManager, never()).releaseResourcesOfStoppedVm(stillStarting);
    }

    @Test
    public void restartMarkerNamesTheJobOrTheServerRunThatOwnsIt() {
        String run = serverRun();
        assertEquals("outside any job, as HA scheduling runs", run, vmLeaseManager.beginRestart(VM_ID));
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.RESTART_IN_PROGRESS, run, false);

        inJob(88L, AsyncJobVO.JOB_DISPATCHER_PSEUDO);
        assertEquals("a pseudo job never completes, so it owns nothing", run, vmLeaseManager.beginRestart(VM_ID));

        inJob(77L, "ApiAsyncJobDispatcher");
        assertEquals("job:77", vmLeaseManager.beginRestart(VM_ID));
    }

    @Test
    public void handOverToAWorkItemThatAlreadyFinishedEndsTheRestart() {
        UserVmVO vm = gpuVm(State.Stopped);
        String run = serverRun();
        giveLease(VM_ID, in(HOUR), "STOP");
        String scheduled = vmLeaseManager.beginRestart(VM_ID);
        HaWorkVO finished = mock(HaWorkVO.class);
        when(finished.getStep()).thenReturn(HighAvailabilityManager.Step.Done);
        when(haDao.findById(9L)).thenReturn(finished);

        // a worker took the item and gave up on the Instance before the scheduler handed the restart over
        vmLeaseManager.endRestart(VM_ID, VMLeaseManager.haWorkRestartMarker(9L));
        assertEquals(run, details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        vmLeaseManager.handRestartToHaWork(VM_ID, scheduled, 9L);

        assertNull(details(VM_ID).get(VmDetailConstants.RESTART_IN_PROGRESS));
        assertNoLease(VM_ID);
        verify(capacityManager).releaseResourcesOfStoppedVm(vm);
    }

    // Stock behaviour kept

    @Test
    public void testReallyRunNoExpiredInstances() throws Exception {
        leaseEnabled(true);
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(new ArrayList<>());
        vmLeaseManager.reallyRun();
        verify(asyncJobManager, never()).submitAsyncJob(any(AsyncJobVO.class));
    }

    @Test
    public void expiryOfADeleteProtectedInstanceStopsIt() throws Exception {
        leaseEnabled(true);
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, true);
        when(vm.getLeaseExpiryAction()).thenReturn("DESTROY");
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(Arrays.asList(vm));
        lenient().when(userVmJoinDao.findById(1L)).thenReturn(vm);
        doReturn(1L).when(vmLeaseManager).executeStopInstanceJob(eq(vm), anyLong());

        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.reallyRun();
        }

        // the Instance is not destroyed, but its lease still ends: the stop gives up its GPUs
        verify(vmLeaseManager).executeStopInstanceJob(eq(vm), anyLong());
        verify(vmLeaseManager, never()).executeDestroyInstanceJob(any(), anyLong());
    }

    @Test
    public void testReallyRunStopAction() throws Exception {
        leaseEnabled(true);
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        List<UserVmJoinVO> expiredVms = Arrays.asList(vm);
        when(userVmJoinDao.listEligibleInstancesWithExpiredLease()).thenReturn(expiredVms);
        when(userVmJoinDao.findById(1L)).thenReturn(vm);
        doReturn(1L).when(vmLeaseManager).executeStopInstanceJob(eq(vm), anyLong());
        try (MockedStatic<ActionEventUtils> utilities = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.reallyRun();
        }
        verify(vmLeaseManager).executeStopInstanceJob(eq(vm), anyLong());
    }

    @Test
    public void testExecuteExpiryActionStop() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        doReturn(1L).when(vmLeaseManager).executeStopInstanceJob(eq(vm), eq(123L));
        Long jobId = vmLeaseManager.executeExpiryAction(vm, VMLeaseManager.ExpiryAction.STOP, 123L);
        assertNotNull(jobId);
        assertEquals(1L, jobId.longValue());
        verify(vmLeaseManager).executeStopInstanceJob(eq(vm), eq(123L));
    }

    @Test
    public void testExecuteExpiryActionDestroy() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false, DESTROY);
        doReturn(1L).when(vmLeaseManager).executeDestroyInstanceJob(eq(vm), eq(123L));
        Long jobId = vmLeaseManager.executeExpiryAction(vm, VMLeaseManager.ExpiryAction.DESTROY, 123L);
        assertNotNull(jobId);
        assertEquals(1L, jobId.longValue());
        verify(vmLeaseManager).executeDestroyInstanceJob(eq(vm), eq(123L));
    }

    @Test
    public void testExecuteStopInstanceJob() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            ApplicationContext mockAppContext = mock(ApplicationContext.class);
            mockedComponentContext.when(ComponentContext::getApplicationContext).thenReturn(mockAppContext);
            mockedComponentContext.when(() -> ComponentContext.inject(any())).thenReturn(true);
            long jobId = vmLeaseManager.executeStopInstanceJob(vm, 123L);
            assertEquals(1L, jobId);
            ArgumentCaptor<AsyncJobVO> jobCaptor = ArgumentCaptor.forClass(AsyncJobVO.class);
            verify(asyncJobManager).submitAsyncJob(jobCaptor.capture());
            AsyncJobVO capturedJob = jobCaptor.getValue();
            assertEquals(User.UID_SYSTEM, capturedJob.getUserId());
            assertEquals(vm.getAccountId(), capturedJob.getAccountId());
            assertEquals(StopVMCmd.class.getName(), capturedJob.getCmd());
            assertEquals(vm.getId(), capturedJob.getInstanceId().longValue());
            assertEquals("AsyncJobDispatcher", capturedJob.getDispatcher());
        }
    }

    @Test
    public void testExecuteDestroyInstanceJob() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false, DESTROY);
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            ApplicationContext mockAppContext = mock(ApplicationContext.class);
            mockedComponentContext.when(ComponentContext::getApplicationContext).thenReturn(mockAppContext);
            mockedComponentContext.when(() -> ComponentContext.inject(any())).thenReturn(true);
            long jobId = vmLeaseManager.executeDestroyInstanceJob(vm, 123L);
            assertEquals(1L, jobId);
            ArgumentCaptor<AsyncJobVO> jobCaptor = ArgumentCaptor.forClass(AsyncJobVO.class);
            verify(asyncJobManager).submitAsyncJob(jobCaptor.capture());
            AsyncJobVO capturedJob = jobCaptor.getValue();
            assertEquals(User.UID_SYSTEM, capturedJob.getUserId());
            assertEquals(vm.getAccountId(), capturedJob.getAccountId());
            assertEquals(DestroyVMCmd.class.getName(), capturedJob.getCmd());
            assertFalse("the API default applies, which keeps the root volume", capturedJob.getCmdInfo().contains("keeprootvolume"));
            assertEquals(vm.getId(), capturedJob.getInstanceId().longValue());
            assertEquals("AsyncJobDispatcher", capturedJob.getDispatcher());
        }
    }

    @Test
    public void testGetLeaseExpiryAction() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        VMLeaseManager.ExpiryAction action = vmLeaseManager.getLeaseExpiryAction(vm);
        assertEquals(VMLeaseManager.ExpiryAction.STOP, action);
    }

    @Test
    public void testGetLeaseExpiryActionNoAction() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        when(vm.getLeaseExpiryAction()).thenReturn(null);
        vm.setLeaseExpiryAction(null);
        assertNull(vmLeaseManager.getLeaseExpiryAction(vm));
    }

    @Test
    public void testGetLeaseExpiryInvalidAction() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, false);
        when(vm.getLeaseExpiryAction()).thenReturn("Unknown");
        assertNull(vmLeaseManager.getLeaseExpiryAction(vm));
    }

    @Test
    public void testGetComponentName() {
        assertEquals(vmLeaseManager.getConfigComponentName(), "VMLeaseManager");
    }

    @Test
    public void testConfigKeys() {
        List<String> keys = Arrays.stream(vmLeaseManager.getConfigKeys()).map(ConfigKey::key).collect(Collectors.toList());
        assertEquals(List.of("instance.lease.enabled", "instance.lease.scheduler.interval", "instance.lease.eventscheduler.interval",
                "instance.lease.expiryevent.hoursbefore", "instance.lease.renewal.enabled"), keys);
    }

    @Test
    public void testConfigure() throws Exception {
        leaseEnabled(true);
        vmLeaseManager.configure("VMLeaseManagerImpl", new HashMap<>());
    }

    @Test
    public void testStopShouldShutdownExecutors() {
        assertTrue(vmLeaseManager.stop());
    }

    @Test
    public void testCancelLeaseOnExistingInstances() {
        UserVmJoinVO vm = createMockVm(1L, VM_UUID, VM_NAME, State.Running, true);
        when(userVmJoinDao.listInstancesWithPendingLease(null)).thenReturn(List.of(vm));
        try (MockedStatic<ActionEventUtils> utilities = Mockito.mockStatic(ActionEventUtils.class)) {
            vmLeaseManager.cancelLeaseOnExistingInstances();
            verify(vmInstanceDetailsDao).addDetail(1L, VmDetailConstants.INSTANCE_LEASE_EXECUTION, VMLeaseManager.LeaseActionExecution.CANCELLED.name(), false);
        }
    }

    @Test
    public void testOnLeaseFeatureToggleEnabled() throws Exception {
        leaseEnabled(true);
        vmLeaseManager.onLeaseFeatureToggle();
    }

    @Test
    public void testOnLeaseFeatureToggleDisabled() throws Exception {
        leaseEnabled(false);
        vmLeaseManager.onLeaseFeatureToggle();
    }

    private UserVmJoinVO createMockVm(Long id, String uuid, String name, State state, boolean deleteProtection) {
        return createMockVm(id, uuid, name, state, deleteProtection, "STOP");
    }

    // Helper method to create mock VMs
    private UserVmJoinVO createMockVm(Long id, String uuid, String name, State state, boolean deleteProtection, String expiryAction) {
        UserVmJoinVO vm = mock(UserVmJoinVO.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getUuid()).thenReturn(uuid);
        lenient().when(vm.isDeleteProtection()).thenReturn(deleteProtection);
        lenient().when(vm.getAccountId()).thenReturn(1L);
        lenient().when(vm.getLeaseExpiryAction()).thenReturn(expiryAction);
        return vm;
    }

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String value) throws IllegalAccessException, NoSuchFieldException {
        final Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(configKey, value);
    }
}
