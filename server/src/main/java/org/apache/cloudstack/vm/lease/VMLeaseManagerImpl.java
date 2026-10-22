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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.SetVirtualMachineLeaseCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.RenewVirtualMachineLeaseCmd;
import org.apache.cloudstack.api.command.user.vm.StopVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobDispatcher;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.resourcelimit.ResourceLimitGrantService;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import com.cloud.api.ApiGsonHelper;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.capacity.CapacityManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.ha.HaWorkVO;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.dao.HighAvailabilityDao;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.Gson;

public class VMLeaseManagerImpl extends ManagerBase implements VMLeaseManager, Configurable, PluggableService,
        StateListener<State, VirtualMachine.Event, VirtualMachine> {
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5;   // 5 seconds
    private static final long HOUR_MS = 3_600_000L;
    private static final String LEASE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String JOB_MARKER_PREFIX = "job:";
    private static final String RUN_MARKER_PREFIX = "ms:";
    private static final String HA_MARKER_PREFIX = "ha:";

    /**
     * States in which an Instance runs no session, so that it holds no lease.
     */
    static final Set<State> STATES_WITHOUT_SESSION = EnumSet.of(State.Stopped, State.Destroyed, State.Expunging, State.Error);

    /**
     * States an Instance passes through on its way to running or to a state without a session.
     */
    private static final Set<State> SETTLING_STATES = EnumSet.of(State.Starting, State.Stopping, State.Migrating);

    private static final List<String> LEASE_DETAILS = List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION,
            VmDetailConstants.INSTANCE_LEASE_EXECUTION, VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING);

    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Inject
    private UserVmJoinDao userVmJoinDao;

    @Inject
    private AsyncJobManager asyncJobManager;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountDao accountDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private CapacityManager capacityManager;
    @Inject
    private HighAvailabilityDao haDao;
    @Inject
    private ManagementServerHostDao managementServerHostDao;
    @Inject
    private AsyncJobDao asyncJobDao;

    private AsyncJobDispatcher asyncJobDispatcher;

    ScheduledExecutorService vmLeaseExecutor;
    ScheduledExecutorService vmLeaseExpiryEventExecutor;
    Gson gson = ApiGsonHelper.getBuilder().create();

    public static final String JOB_INITIATOR = "jobInitiator";

    /**
     * A warning of the end of a lease, sent once the lease lock is released.
     */
    private static final class Warning {
        private final long userId;
        private final long accountId;
        private final long domainId;
        private final long vmId;
        private final String subject;
        private final String body;

        private Warning(UserVmVO vm, String subject, String body) {
            this.userId = vm.getUserId();
            this.accountId = vm.getAccountId();
            this.domainId = vm.getDomainId();
            this.vmId = vm.getId();
            this.subject = subject;
            this.body = body;
        }
    }

    @Override
    public String getConfigComponentName() {
        return VMLeaseManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                InstanceLeaseEnabled,
                InstanceLeaseSchedulerInterval,
                InstanceLeaseExpiryEventSchedulerInterval,
                InstanceLeaseExpiryEventHoursBefore,
                InstanceLeaseRenewalEnabled
        };
    }

    @Override
    public List<Class<?>> getCommands() {
        return List.of(RenewVirtualMachineLeaseCmd.class, SetVirtualMachineLeaseCmd.class);
    }

    public void setAsyncJobDispatcher(final AsyncJobDispatcher dispatcher) {
        asyncJobDispatcher = dispatcher;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        return true;
    }

    @Override
    public boolean start() {
        VirtualMachine.State.getStateMachine().registerListener(this);
        scheduleLeaseExecutors();
        return true;
    }

    @Override
    public boolean stop() {
        shutDownLeaseExecutors();
        return true;
    }

    /**
     * This method will cancel lease on instances running under lease
     * will be primarily used when feature gets disabled
     */
    public void cancelLeaseOnExistingInstances() {
        List<UserVmJoinVO> leaseExpiringForInstances = userVmJoinDao.listInstancesWithPendingLease(null);
        logger.debug("Total instances found for lease cancellation: {}", leaseExpiringForInstances.size());
        for (UserVmJoinVO instance : leaseExpiringForInstances) {
            vmInstanceDetailsDao.addDetail(instance.getId(), VmDetailConstants.INSTANCE_LEASE_EXECUTION,
                    LeaseActionExecution.CANCELLED.name(), false);
            String leaseCancellationMsg = String.format("Lease is cancelled for the instance: %s (id: %s) ", instance.getName(), instance.getUuid());
            ActionEventUtils.onActionEvent(instance.getUserId(), instance.getAccountId(), instance.getDomainId(),
                    EventTypes.VM_LEASE_CANCELLED, leaseCancellationMsg, instance.getId(), ApiCommandResourceType.VirtualMachine.toString());
        }
    }

    @Override
    public void onLeaseFeatureToggle() {
        if (!VMLeaseManager.InstanceLeaseEnabled.value()) {
            cancelLeaseOnExistingInstances();
        }
    }

    /**
     * The executors run whether or not leases are in force: the scheduler also ends restarts whose owner is gone, which
     * may hold host resources of stopped Instances.
     */
    private void scheduleLeaseExecutors() {
        if (vmLeaseExecutor == null || vmLeaseExecutor.isShutdown()) {
            logger.debug("Scheduling lease executor");
            vmLeaseExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("VMLeasePollExecutor"));
            vmLeaseExecutor.scheduleAtFixedRate(new VMLeaseSchedulerTask(), 5L, InstanceLeaseSchedulerInterval.value(), TimeUnit.SECONDS);
        }

        if (vmLeaseExpiryEventExecutor == null || vmLeaseExpiryEventExecutor.isShutdown()) {
            logger.debug("Scheduling lease expiry event executor");
            vmLeaseExpiryEventExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("VmLeaseExpiryEventExecutor"));
            vmLeaseExpiryEventExecutor.scheduleAtFixedRate(new VMLeaseExpiryEventSchedulerTask(), 5L, InstanceLeaseExpiryEventSchedulerInterval.value(), TimeUnit.SECONDS);
        }
    }

    private void shutDownLeaseExecutors() {
        if (vmLeaseExecutor != null) {
            logger.debug("Shutting down lease executor");
            vmLeaseExecutor.shutdown();
            vmLeaseExecutor = null;
        }

        if (vmLeaseExpiryEventExecutor != null) {
            logger.debug("Shutting down lease expiry event executor");
            vmLeaseExpiryEventExecutor.shutdown();
            vmLeaseExpiryEventExecutor = null;
        }
    }

    class VMLeaseSchedulerTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            Date currentTimestamp = DateUtils.round(new Date(), Calendar.MINUTE);
            String displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, currentTimestamp);
            logger.debug("VMLeaseSchedulerTask is being called at {}", displayTime);
            GlobalLock scanLock = GlobalLock.getInternLock("VMLeaseSchedulerTask");
            try {
                // a run under way on another management server covers this tick
                if (scanLock.lock(0)) {
                    try {
                        reallyRun();
                    } finally {
                        scanLock.unlock();
                    }
                }
            } catch (Exception e) {
                logger.warn("The lease scheduler run failed", e);
            } finally {
                scanLock.releaseRef();
            }
        }
    }

    class VMLeaseExpiryEventSchedulerTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            logger.debug("VMLeaseExpiryEventSchedulerTask is being called");
            GlobalLock scanLock = GlobalLock.getInternLock("VMLeaseExpiryEventSchedulerTask");
            try {
                if (scanLock.lock(0)) {
                    try {
                        sendExpiryWarnings();
                    } finally {
                        scanLock.unlock();
                    }
                }
            } catch (Exception e) {
                logger.warn("The lease expiry warning run failed", e);
            } finally {
                scanLock.releaseRef();
            }
        }
    }

    protected void reallyRun() {
        endRestartsWhoseOwnerIsGone();
        if (!InstanceLeaseEnabled.value()) {
            logger.debug("Instance lease feature is disabled, no action is required");
            return;
        }
        giveRunningInstancesTheirLease();
        executeExpiredLeases();
    }

    /**
     * Acts on the expired leases of running Instances. An Instance in a restart of its session is left to a later run: the
     * restart may hold off the expiry action's stop, and the Instance runs its session on once the restart ends.
     */
    private void executeExpiredLeases() {
        List<UserVmJoinVO> leaseExpiredInstances = userVmJoinDao.listEligibleInstancesWithExpiredLease();
        Set<Long> actionableInstanceIds = new HashSet<>();
        for (UserVmJoinVO userVmVO : leaseExpiredInstances) {
            if (restartMarker(userVmVO.getId()) != null) {
                logger.debug("Leaving the expired lease of instance {} (id: {}) to a later run, as a restart of its session is in progress",
                        userVmVO.getName(), userVmVO.getUuid());
                continue;
            }
            actionableInstanceIds.add(userVmVO.getId());
        }
        if (actionableInstanceIds.isEmpty()) {
            logger.debug("Lease scheduler found no instance to work upon");
            return;
        }

        List<Long> submittedJobIds = new ArrayList<>();
        List<Long> successfulInstanceIds = new ArrayList<>();
        List<Long> failedToSubmitInstanceIds = new ArrayList<>();
        for (Long instanceId : actionableInstanceIds) {
            UserVmJoinVO instance = userVmJoinDao.findById(instanceId);
            ExpiryAction expiryAction = getLeaseExpiryAction(instance);
            if (expiryAction == null) {
                continue;
            }
            // the lease still ends: the stop gives up what the session holds
            if (expiryAction == ExpiryAction.DESTROY && Boolean.TRUE.equals(instance.isDeleteProtection())) {
                logger.debug("Stopping instance: {} (id: {}) on lease expiry instead of destroying it, as it is protected from deletion",
                        instance.getName(), instance.getUuid());
                expiryAction = ExpiryAction.STOP;
            }
            final long eventId = ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, instance.getAccountId(), null,
                    EventTypes.VM_LEASE_EXPIRED, true,
                    String.format("Executing lease expiry action (%s) for instance: %s (id: %s)", expiryAction, instance.getName(), instance.getUuid()),
                    instance.getId(), ApiCommandResourceType.VirtualMachine.toString(), 0);

            Long jobId = executeExpiryAction(instance, expiryAction, eventId);
            if (jobId != null) {
                submittedJobIds.add(jobId);
                successfulInstanceIds.add(instanceId);
            } else {
                failedToSubmitInstanceIds.add(instanceId);
            }
        }
        logger.debug("Successfully submitted lease expiry jobs with ids: {} and instance ids: {}", submittedJobIds, successfulInstanceIds);
        if (!failedToSubmitInstanceIds.isEmpty()) {
            logger.debug("Lease scheduler failed to submit jobs for instance ids: {}", failedToSubmitInstanceIds);
        }
    }

    /**
     * A running Instance of an offering with a lease whose session ran without one, because it started before leases were
     * in force or while they were not (which cancelled its lease), gets the offering's lease from now. Any other lease
     * state of the session stays as it is: a pending lease, one that ran out, one an administrator turned off.
     */
    private void giveRunningInstancesTheirLease() {
        Map<Long, Integer> hoursByOffering = new HashMap<>();
        for (ServiceOfferingDetailsVO detail : serviceOfferingDetailsDao.findDetails(LEASE_HOURS)) {
            Integer hours = parseHours(detail.getValue());
            if (hours != null) {
                hoursByOffering.put(detail.getResourceId(), hours);
            }
        }
        if (hoursByOffering.isEmpty()) {
            return;
        }
        for (VMInstanceVO vm : vmInstanceDao.listByTypeAndState(VirtualMachine.Type.User, State.Running)) {
            Integer hours = hoursByOffering.get(vm.getServiceOfferingId());
            if (hours == null) {
                continue;
            }
            Map<String, String> lease = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId(), List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE,
                    VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, VmDetailConstants.INSTANCE_LEASE_EXECUTION));
            if (!lease.isEmpty() && !LeaseActionExecution.CANCELLED.name().equals(lease.get(VmDetailConstants.INSTANCE_LEASE_EXECUTION))) {
                continue;
            }
            Date expiry = new Date(now().getTime() + hours * HOUR_MS);
            logger.info("Giving running {} the lease of its offering until {} UTC", vm, formatLeaseDate(expiry));
            writeLease(vm.getId(), expiry, offeringExpiryAction(vm.getServiceOfferingId()));
        }
    }

    Long executeExpiryAction(UserVmJoinVO instance, ExpiryAction expiryAction, long eventId) {
        // for qualified vms, prepare Stop/Destroy(Cmd) and submit to Job Manager
        switch (expiryAction) {
            case STOP: {
                logger.debug("Stopping instance: {} (id: {}) on lease expiry", instance.getName(), instance.getUuid());
                return executeStopInstanceJob(instance, eventId);
            }
            case DESTROY: {
                logger.debug("Destroying instance: {} (id: {}) on lease expiry", instance.getName(), instance.getUuid());
                return executeDestroyInstanceJob(instance, eventId);
            }
            default: {
                logger.error("Invalid configuration for instance.lease.expiryaction for instance: {} (id: {}), " +
                        "valid values are: \"STOP\" and  \"DESTROY\"", instance.getName(), instance.getUuid());
            }
        }
        return null;
    }

    long executeStopInstanceJob(UserVmJoinVO vm, long eventId) {
        final Map<String, String> params = new HashMap<>();
        params.put(ApiConstants.ID, String.valueOf(vm.getId()));
        params.put("ctxUserId", String.valueOf(User.UID_SYSTEM));
        params.put("ctxAccountId", String.valueOf(Account.ACCOUNT_ID_SYSTEM));
        params.put(ApiConstants.CTX_START_EVENT_ID, String.valueOf(eventId));
        params.put(JOB_INITIATOR, VMLeaseManager.class.getSimpleName());
        final StopVMCmd cmd = new StopVMCmd();
        ComponentContext.inject(cmd);
        AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, vm.getAccountId(), StopVMCmd.class.getName(), gson.toJson(params), vm.getId(),
                cmd.getApiResourceType() != null ? cmd.getApiResourceType().toString() : null, null);
        job.setDispatcher(asyncJobDispatcher.getName());
        return asyncJobManager.submitAsyncJob(job);
    }

    long executeDestroyInstanceJob(UserVmJoinVO vm, long eventId) {
        final Map<String, String> params = new HashMap<>();
        params.put(ApiConstants.ID, String.valueOf(vm.getId()));
        params.put("ctxUserId", String.valueOf(User.UID_SYSTEM));
        params.put("ctxAccountId", String.valueOf(Account.ACCOUNT_ID_SYSTEM));
        params.put(ApiConstants.CTX_START_EVENT_ID, String.valueOf(eventId));
        params.put(JOB_INITIATOR, VMLeaseManager.class.getSimpleName());
        final DestroyVMCmd cmd = new DestroyVMCmd();
        ComponentContext.inject(cmd);

        AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, vm.getAccountId(), DestroyVMCmd.class.getName(), gson.toJson(params), vm.getId(),
                cmd.getApiResourceType() != null ? cmd.getApiResourceType().toString() : null, null);
        job.setDispatcher(asyncJobDispatcher.getName());
        return asyncJobManager.submitAsyncJob(job);
    }

    public ExpiryAction getLeaseExpiryAction(UserVmJoinVO instance) {
        return EnumUtils.getEnumIgnoreCase(VMLeaseManager.ExpiryAction.class, instance.getLeaseExpiryAction());
    }

    // Sessions

    @Override
    public boolean preStateTransitionEvent(State oldState, VirtualMachine.Event event, State newState, VirtualMachine vo, boolean status, Object opaque) {
        return true;
    }

    /**
     * A start of an Instance that ran no session begins one; a transition into a state without a session ends it.
     * Within a restart of the running session neither happens. A failure here never fails the transition: a running
     * Instance left without its lease gets one at the next scheduler run.
     */
    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<State, VirtualMachine.Event> transition, VirtualMachine vm, boolean status,
            Object opaque) {
        if (!status || vm == null || vm.getType() != VirtualMachine.Type.User) {
            return true;
        }
        State oldState = transition.getCurrentState();
        State newState = transition.getToState();
        try {
            if (newState == State.Running && (oldState == State.Starting || oldState == State.Stopped)) {
                if (restartMarker(vm.getId()) == null) {
                    beginSession(vm);
                }
            } else if (STATES_WITHOUT_SESSION.contains(newState) && !STATES_WITHOUT_SESSION.contains(oldState)) {
                if (restartMarker(vm.getId()) == null) {
                    clearLease(vm.getId());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to keep the lease of {} in step with its transition {} -> {}", vm, oldState, newState, e);
        }
        return true;
    }

    private void beginSession(VirtualMachine vm) {
        if (!InstanceLeaseEnabled.value()) {
            return;
        }
        Integer hours = offeringLeaseHours(vm.getServiceOfferingId());
        if (hours == null) {
            return;
        }
        writeLease(vm.getId(), new Date(now().getTime() + hours * HOUR_MS), offeringExpiryAction(vm.getServiceOfferingId()));
    }

    private void clearLease(long vmId) {
        for (String detail : LEASE_DETAILS) {
            vmInstanceDetailsDao.removeDetail(vmId, detail);
        }
    }

    /**
     * Sets the lease of the Instance's running session, and warns at once for the narrowest warning window it is already
     * in. The end of the session clears the lease without the lease lock, so the session is checked again once the lease
     * is written: a stop that landed in between leaves no lease behind.
     *
     * @return whether the lease was set, false when the Instance does not run
     */
    private boolean writeLease(long vmId, Date expiry, ExpiryAction action) {
        List<Warning> due = new ArrayList<>(1);
        boolean written = underLeaseLock(vmId, () -> {
            VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
            if (vm == null || vm.getRemoved() != null || vm.getState() != State.Running) {
                return false;
            }
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, formatLeaseDate(expiry), false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, action.name(), false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXECUTION, LeaseActionExecution.PENDING.name(), false);
            if (sessionEnded(vmId)) {
                clearLease(vmId);
                return false;
            }
            Warning warning = dueWarning(vmId, now());
            if (warning != null) {
                due.add(warning);
            }
            return true;
        });
        due.forEach(this::send);
        return written;
    }

    private boolean sessionEnded(long vmId) {
        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        return vm == null || vm.getRemoved() != null || (STATES_WITHOUT_SESSION.contains(vm.getState()) && restartMarker(vmId) == null);
    }

    @Override
    public UserVm renewLease(long vmId) throws ResourceAllocationException {
        UserVmVO vm = findUserVm(vmId);
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        if (!InstanceLeaseRenewalEnabled.value()) {
            throw new InvalidParameterValueException(String.format("Renewing an Instance lease is turned off (%s)", InstanceLeaseRenewalEnabled.key()));
        }
        checkLeasesInForce();
        if (vm.getState() != State.Running) {
            throw new InvalidParameterValueException(String.format("The lease of Instance %s is renewed only while it runs; it is %s",
                    vm.getUuid(), vm.getState()));
        }
        Integer hours = offeringLeaseHours(vm.getServiceOfferingId());
        if (hours == null) {
            throw new InvalidParameterValueException(String.format("The service offering of Instance %s gives it no lease to renew", vm.getUuid()));
        }
        Map<String, String> lease = vmInstanceDetailsDao.listDetailsKeyPairs(vmId,
                List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        Date current = parseLeaseDate(lease.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE));
        if (current == null || !LeaseActionExecution.PENDING.name().equals(lease.get(VmDetailConstants.INSTANCE_LEASE_EXECUTION))) {
            throw new InvalidParameterValueException(String.format("Instance %s has no lease to renew", vm.getUuid()));
        }
        if (!current.after(now())) {
            throw new InvalidParameterValueException(String.format("The lease of Instance %s has ended; its expiry action is due", vm.getUuid()));
        }
        Account owner = accountDao.findById(vm.getAccountId());
        resourceLimitService.checkWithinQuota(owner, serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId()),
                templateDao.findByIdIncludingRemoved(vm.getTemplateId()));

        Date expiry = new Date(now().getTime() + hours * HOUR_MS);
        if (!writeLease(vmId, expiry, offeringExpiryAction(vm.getServiceOfferingId()))) {
            throw new InvalidParameterValueException(String.format("The lease of Instance %s is renewed only while it runs; it stopped", vm.getUuid()));
        }
        ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), vm.getAccountId(), vm.getDomainId(), EventTypes.VM_LEASE_RENEW,
                String.format("Renewed the lease of Instance %s until %s UTC", vm.getUuid(), formatLeaseDate(expiry)), vmId,
                ApiCommandResourceType.VirtualMachine.toString());
        return userVmDao.findById(vmId);
    }

    @Override
    public UserVm setLease(long vmId, Date expiry) {
        UserVmVO vm = findUserVm(vmId);
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        checkLeasesInForce();
        if (expiry == null) {
            throw new InvalidParameterValueException("The end of the lease is required");
        }
        if (vm.getState() != State.Running) {
            throw new InvalidParameterValueException(String.format("A lease is set for the running session; Instance %s is %s", vm.getUuid(), vm.getState()));
        }
        VMInstanceDetailVO currentAction = vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        ExpiryAction action = currentAction == null ? null : EnumUtils.getEnumIgnoreCase(ExpiryAction.class, currentAction.getValue());
        if (!writeLease(vmId, expiry, action != null ? action : offeringExpiryAction(vm.getServiceOfferingId()))) {
            throw new InvalidParameterValueException(String.format("A lease is set for the running session; Instance %s stopped", vm.getUuid()));
        }
        ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), vm.getAccountId(), vm.getDomainId(), EventTypes.VM_LEASE_UPDATE,
                String.format("Set the lease of Instance %s to end at %s UTC", vm.getUuid(), formatLeaseDate(expiry)), vmId,
                ApiCommandResourceType.VirtualMachine.toString());
        return userVmDao.findById(vmId);
    }

    private UserVmVO findUserVm(long vmId) {
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find an Instance with ID " + vmId);
        }
        return vm;
    }

    private void checkLeasesInForce() {
        if (!InstanceLeaseEnabled.value()) {
            throw new InvalidParameterValueException(String.format("Instance leases are not in force (%s)", InstanceLeaseEnabled.key()));
        }
    }

    // Warnings

    protected void sendExpiryWarnings() {
        if (!InstanceLeaseEnabled.value()) {
            return;
        }
        SortedSet<Integer> hours = warningHours();
        if (hours.isEmpty()) {
            return;
        }
        Date now = now();
        Set<Long> vmIds = new LinkedHashSet<>();
        for (UserVmJoinVO instance : userVmJoinDao.listInstancesWithPendingLease(new Date(now.getTime() + hours.last() * HOUR_MS))) {
            vmIds.add(instance.getId());
        }
        for (long vmId : vmIds) {
            try {
                Warning warning = underLeaseLock(vmId, () -> dueWarning(vmId, now));
                if (warning != null) {
                    send(warning);
                }
            } catch (Exception e) {
                logger.warn("Failed to warn the owner of Instance {} of the end of its lease", vmId, e);
            }
        }
    }

    private SortedSet<Integer> warningHours() {
        try {
            return VMLeaseManager.parseWarningHours(InstanceLeaseExpiryEventHoursBefore.value());
        } catch (InvalidParameterValueException e) {
            logger.warn("Sending no lease warnings: {}", e.getMessage());
            return new TreeSet<>();
        }
    }

    /**
     * The warning due for the narrowest warning window the lease is in, recorded as warned, unless that window or a
     * narrower one was warned for this lease already. The caller holds the Instance's lease lock, and sends the warning
     * once it released the lock.
     */
    private Warning dueWarning(long vmId, Date now) {
        if (!InstanceLeaseEnabled.value()) {
            return null;
        }
        Map<String, String> lease = vmInstanceDetailsDao.listDetailsKeyPairs(vmId, List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE,
                VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION, VmDetailConstants.INSTANCE_LEASE_EXECUTION, VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
        Date expiry = parseLeaseDate(lease.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE));
        if (expiry == null || !LeaseActionExecution.PENDING.name().equals(lease.get(VmDetailConstants.INSTANCE_LEASE_EXECUTION))) {
            return null;
        }
        Integer due = VMLeaseManager.dueWarningHours(warningHours(), expiry, now);
        if (due == null) {
            return null;
        }
        Integer warned = parseHours(lease.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING));
        if (warned != null && warned <= due) {
            return null;
        }
        vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_WARNING, String.valueOf(due), false);
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm == null) {
            return null;
        }
        ExpiryAction action = EnumUtils.getEnumIgnoreCase(ExpiryAction.class, lease.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION));
        String within = due == 1 ? "1 hour" : due + " hours";
        String subject = String.format("The lease of Instance %s ends in under %s", vm.getHostName(), within);
        String body = String.format("The lease of Instance %s (%s) ends at %s UTC, in under %s. The Instance will then be %s. "
                        + "Renew the lease to keep it running.", vm.getHostName(), vm.getUuid(), formatLeaseDate(expiry), within,
                action == ExpiryAction.DESTROY ? "destroyed" : "stopped");
        return new Warning(vm, subject, body);
    }

    private void send(Warning warning) {
        ActionEventUtils.onActionEvent(warning.userId, warning.accountId, warning.domainId, EventTypes.VM_LEASE_EXPIRING, warning.body, warning.vmId,
                ApiCommandResourceType.VirtualMachine.toString());
        Account owner = accountDao.findById(warning.accountId);
        if (owner != null) {
            mailAccount(owner, warning.subject, warning.body);
        }
    }

    protected void mailAccount(Account account, String subject, String body) {
        try {
            ResourceLimitGrantService mailer = ComponentContext.getDelegateComponentOfType(ResourceLimitGrantService.class);
            mailer.notifyAccount(account, subject, body);
        } catch (Exception e) {
            logger.warn("Unable to mail \"{}\" to account {}", subject, account, e);
        }
    }

    // Restarts within a running session

    @Override
    public String beginRestart(long vmId) {
        AsyncJob job = currentJob();
        String marker = job != null ? JOB_MARKER_PREFIX + job.getId() : currentRun();
        vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.RESTART_IN_PROGRESS, marker, false);
        return marker;
    }

    @Override
    public String handRestartToHaWork(long vmId, String marker, long haWorkId) {
        String handedOver = VMLeaseManager.haWorkRestartMarker(haWorkId);
        if (marker != null && marker.equals(restartMarker(vmId))) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.RESTART_IN_PROGRESS, handedOver, false);
            // a worker may have finished the item already, when its end of the restart found the restart not yet handed over
            if (!restartIsLive(handedOver)) {
                endRestart(vmId, handedOver);
            }
        }
        return handedOver;
    }

    @Override
    public void endRestart(long vmId, String marker) {
        if (marker == null || !marker.equals(restartMarker(vmId))) {
            return;
        }
        vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.RESTART_IN_PROGRESS);
        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        if (vm != null && (vm.getRemoved() != null || STATES_WITHOUT_SESSION.contains(vm.getState()))) {
            logger.info("The restart of {} ended without it running; its session ends", vm);
            clearLease(vmId);
            capacityManager.releaseResourcesOfStoppedVm(vm);
        }
    }

    @Override
    public void endSession(long vmId) {
        clearLease(vmId);
        vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.RESTART_IN_PROGRESS);
    }

    private String restartMarker(long vmId) {
        VMInstanceDetailVO marker = vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.RESTART_IN_PROGRESS);
        return marker == null ? null : marker.getValue();
    }

    /**
     * Ends the restarts whose owner can no longer finish them: a job no longer in progress, a management server run that
     * is gone, an HA work item that is done. An Instance between states is left until it settles, so that the end of
     * its restart decides by where it came to rest.
     */
    private void endRestartsWhoseOwnerIsGone() {
        for (VMInstanceDetailVO marker : vmInstanceDetailsDao.findDetails(VmDetailConstants.RESTART_IN_PROGRESS)) {
            try {
                if (restartIsLive(marker.getValue())) {
                    continue;
                }
                VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(marker.getResourceId());
                if (vm != null && vm.getRemoved() == null && SETTLING_STATES.contains(vm.getState())) {
                    logger.debug("Leaving restart {} of {}, whose owner is gone, until the Instance leaves {}", marker.getValue(), vm, vm.getState());
                    continue;
                }
                logger.info("Ending restart {} of Instance {}, whose owner is gone", marker.getValue(), marker.getResourceId());
                endRestart(marker.getResourceId(), marker.getValue());
            } catch (Exception e) {
                logger.warn("Failed to end restart {} of Instance {}", marker.getValue(), marker.getResourceId(), e);
            }
        }
    }

    private boolean restartIsLive(String marker) {
        try {
            if (marker.startsWith(JOB_MARKER_PREFIX)) {
                AsyncJobVO job = asyncJobDao.findById(Long.parseLong(marker.substring(JOB_MARKER_PREFIX.length())));
                return job != null && job.getStatus() == JobInfo.Status.IN_PROGRESS && !AsyncJobVO.JOB_DISPATCHER_PSEUDO.equals(job.getDispatcher());
            }
            if (marker.startsWith(HA_MARKER_PREFIX)) {
                HaWorkVO work = haDao.findById(Long.parseLong(marker.substring(HA_MARKER_PREFIX.length())));
                return work != null && work.getStep() != HighAvailabilityManager.Step.Done && work.getStep() != HighAvailabilityManager.Step.Cancelled;
            }
            if (marker.startsWith(RUN_MARKER_PREFIX)) {
                String[] run = marker.substring(RUN_MARKER_PREFIX.length()).split(":");
                if (run.length != 2) {
                    return false;
                }
                ManagementServerHostVO host = managementServerHostDao.findByMsid(Long.parseLong(run[0]));
                return host != null && host.getRemoved() == null && host.getState() == ManagementServerHost.State.Up
                        && host.getRunid() == Long.parseLong(run[1]);
            }
        } catch (NumberFormatException e) {
            logger.debug("Restart marker {} is malformed", marker);
        }
        return false;
    }

    /**
     * The job the current thread runs, if it is one that ends: a thread's pseudo job stays in progress for the whole run
     * of its management server.
     */
    private static AsyncJob currentJob() {
        AsyncJobExecutionContext context = AsyncJobExecutionContext.getCurrent();
        AsyncJob job = context == null ? null : context.getJob();
        return job == null || AsyncJobVO.JOB_DISPATCHER_PSEUDO.equals(job.getDispatcher()) ? null : job;
    }

    private String currentRun() {
        long msid = ManagementServerNode.getManagementServerId();
        ManagementServerHostVO host = managementServerHostDao.findByMsid(msid);
        return RUN_MARKER_PREFIX + msid + ":" + (host == null ? 0 : host.getRunid());
    }

    // Helpers

    protected Date now() {
        return new Date();
    }

    /**
     * Serialises the changes of one Instance's lease and its warnings across management servers.
     */
    protected <T> T underLeaseLock(long vmId, Supplier<T> operation) {
        GlobalLock lock = GlobalLock.getInternLock("vm.lease." + vmId);
        try {
            if (!lock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                throw new CloudRuntimeException("Unable to lock the lease of Instance " + vmId);
            }
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    private Integer offeringLeaseHours(long offeringId) {
        String value = serviceOfferingDetailsDao.getDetail(offeringId, LEASE_HOURS);
        Integer hours = VMLeaseManager.leaseHoursOf(value);
        if (hours == null && StringUtils.isNotBlank(value)) {
            logger.warn("Service offering {} has the malformed lease detail {}={}; it gives no lease", offeringId, LEASE_HOURS, value);
        }
        return hours;
    }

    private ExpiryAction offeringExpiryAction(long offeringId) {
        String value = serviceOfferingDetailsDao.getDetail(offeringId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        ExpiryAction action = EnumUtils.getEnumIgnoreCase(ExpiryAction.class, value);
        if (action == null && StringUtils.isNotBlank(value)) {
            logger.warn("Service offering {} has the unknown lease expiry action {}; its leases stop the Instance", offeringId, value);
        }
        return action != null ? action : ExpiryAction.STOP;
    }

    private static Integer parseHours(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            int hours = Integer.parseInt(value.trim());
            return hours > 0 ? hours : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static SimpleDateFormat leaseDateFormat() {
        SimpleDateFormat format = new SimpleDateFormat(LEASE_DATE_FORMAT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private static String formatLeaseDate(Date date) {
        return leaseDateFormat().format(date);
    }

    private static Date parseLeaseDate(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return leaseDateFormat().parse(value);
        } catch (ParseException e) {
            return null;
        }
    }
}
