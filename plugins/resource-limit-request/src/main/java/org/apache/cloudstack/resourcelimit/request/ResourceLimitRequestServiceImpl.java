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

package org.apache.cloudstack.resourcelimit.request;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKeyScheduledExecutionWrapper;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.resourcelimit.ResourceLimitGrantService;
import org.apache.cloudstack.resourcelimit.request.api.command.ApproveResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.CancelResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.CreateResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.ListResourceLimitRequestsCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.RejectResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.RevokeResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.response.ResourceLimitRequestResponse;
import org.apache.cloudstack.resourcelimit.request.dao.ResourceLimitRequestDao;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;
import org.apache.cloudstack.utils.mailing.MailAddress;
import org.apache.cloudstack.utils.mailing.SMTPMailProperties;
import org.apache.cloudstack.utils.mailing.SMTPMailSender;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.cloud.alert.AlertManager;
import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.Config;
import com.cloud.configuration.Resource;
import com.cloud.configuration.Resource.ResourceOwnerType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceLimitVO;
import com.cloud.configuration.dao.ResourceLimitDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.offering.ServiceOffering;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;

public class ResourceLimitRequestServiceImpl extends ManagerBase implements ResourceLimitRequestService, Configurable {

    private static final String SWEEP_LOCK = "resource.limit.request.sweep";
    private static final String MAIL_NAMESPACE = "resource.request.smtp";
    private static final String MAIL_TIMEOUT_MILLIS = "30000";
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long FIRST_SWEEP_DELAY_SECONDS = 60;
    static final String REPORTED_UNSIZED_GPU_TYPES = "resource.request.unsized.gpu.types.reported";
    private static final long MAX_REQUESTED = Integer.MAX_VALUE;
    private static final DateTimeFormatter MAIL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz");

    @Inject
    ResourceLimitRequestDao requestDao;
    @Inject
    AccountManager accountManager;
    @Inject
    AccountDao accountDao;
    @Inject
    DomainDao domainDao;
    @Inject
    UserDao userDao;
    @Inject
    ResourceLimitService resourceLimitService;
    @Inject
    ResourceLimitDao resourceLimitDao;
    @Inject
    ServiceOfferingDao serviceOfferingDao;
    @Inject
    ConfigurationDao configurationDao;
    @Inject
    MessageBus messageBus;
    @Inject
    AlertManager alertManager;

    private SearchBuilder<ServiceOfferingVO> unitOfferingSearch;
    private SearchBuilder<ServiceOfferingVO> offeringWithoutGpuSearch;
    private ScheduledExecutorService sweepExecutor;
    private ExecutorService mailExecutor;

    /** The size of one GPU of a type: the vCPUs and memory its one-GPU offering gives with it. */
    static final class GpuUnit {
        final long vcpus;
        final long memoryMib;

        GpuUnit(long vcpus, long memoryMib) {
            this.vcpus = vcpus;
            this.memoryMib = memoryMib;
        }
    }

    /** The values of one category each, as the larger of the defaults and every active grant; -1 is unlimited. */
    static final class Quota {
        long cpu;
        long memoryMib;
        long storageGib;
        final Map<String, Long> gpus = new TreeMap<>();
    }

    /** The account defaults, read when a recompute runs. */
    static final class Defaults {
        long cpu;
        long memoryMib;
        long storageGib;
        long gpusPerType;
        long snapshots;
        long secondaryStorageGib;
    }

    /** One limit to write; the value is in the unit {@link ResourceLimitService#updateResourceLimit} takes. */
    static final class LimitRow {
        final ResourceType type;
        final String tag;
        final long value;

        LimitRow(ResourceType type, String tag, long value) {
            this.type = type;
            this.tag = tag;
            this.value = value;
        }

        long storedValue() {
            return ResourceType.isStorageType(type) && value >= 0 ? value * ResourceType.bytesToGiB : value;
        }

        @Override
        public String toString() {
            return String.format("%s%s=%d", type, tag == null ? "" : "/" + tag, value);
        }
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        unitOfferingSearch = serviceOfferingDao.createSearchBuilder();
        unitOfferingSearch.and("vgpuProfileId", unitOfferingSearch.entity().getVgpuProfileId(), SearchCriteria.Op.NNULL);
        unitOfferingSearch.and("gpuCount", unitOfferingSearch.entity().getGpuCount(), SearchCriteria.Op.EQ);
        unitOfferingSearch.and("state", unitOfferingSearch.entity().getState(), SearchCriteria.Op.EQ);
        unitOfferingSearch.and("systemUse", unitOfferingSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        unitOfferingSearch.done();
        offeringWithoutGpuSearch = serviceOfferingDao.createSearchBuilder();
        offeringWithoutGpuSearch.and("vgpuProfileId", offeringWithoutGpuSearch.entity().getVgpuProfileId(), SearchCriteria.Op.NULL);
        offeringWithoutGpuSearch.and("state", offeringWithoutGpuSearch.entity().getState(), SearchCriteria.Op.EQ);
        offeringWithoutGpuSearch.and("systemUse", offeringWithoutGpuSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        offeringWithoutGpuSearch.done();
        sweepExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("ResourceLimitRequestSweep"));
        mailExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("ResourceLimitRequestMail"));
        return true;
    }

    @Override
    public boolean start() {
        messageBus.subscribe(AccountManager.MESSAGE_ADD_ACCOUNT_EVENT, (sender, subject, args) -> onAccountsAdded(args));
        messageBus.subscribe(AccountManager.MESSAGE_REMOVE_ACCOUNT_EVENT, (sender, subject, args) -> onAccountRemoved(args));
        sweepRunner(sweepExecutor).start();
        if (ExpiryCheckInterval.value() > 0) {
            sweepExecutor.schedule(new SweepTask(), FIRST_SWEEP_DELAY_SECONDS, TimeUnit.SECONDS);
        }
        return true;
    }

    @Override
    public boolean stop() {
        if (sweepExecutor != null) {
            sweepExecutor.shutdown();
        }
        if (mailExecutor != null) {
            mailExecutor.shutdown();
        }
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> commands = new ArrayList<>();
        commands.add(CreateResourceLimitRequestCmd.class);
        commands.add(ListResourceLimitRequestsCmd.class);
        commands.add(CancelResourceLimitRequestCmd.class);
        commands.add(ApproveResourceLimitRequestCmd.class);
        commands.add(RejectResourceLimitRequestCmd.class);
        commands.add(RevokeResourceLimitRequestCmd.class);
        return commands;
    }

    @Override
    public String getConfigComponentName() {
        return ResourceLimitRequestServiceImpl.class.getSimpleName();
    }

    @Override
    public String[] getAccessControlConfigKeyNames() {
        return new String[] {SmtpHost.key(), SmtpPort.key(), SmtpUseAuth.key(), SmtpUseStartTLS.key(), SmtpEnabledSecurityProtocols.key(), SmtpUsername.key(),
                SmtpPassword.key()};
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ExpiryRequired, ExpiryDefaultDays, ExpiryMaxDays, ExpiryTimezone, ExpiryWarningDays, ExpiryCheckInterval, SmtpHost, SmtpPort,
                SmtpUseAuth, SmtpUsername, SmtpPassword, SmtpUseStartTLS, SmtpEnabledSecurityProtocols, SmtpSender};
    }

    @Override
    public ResourceLimitRequestVO createRequest(CreateResourceLimitRequestCmd cmd) {
        CallContext context = CallContext.current();
        Account owner = accountManager.finalizeOwner(context.getCallingAccount(), cmd.getAccountName(), cmd.getDomainId(), null);
        if (!followsGrants(owner)) {
            throw new InvalidParameterValueException(String.format("The limits of Account %s are not set by grants; it cannot request them", owner.getAccountName()));
        }
        long cpu = requested(CPU, cmd.getCpu());
        long memory = requested(MEMORY, cmd.getMemory());
        long storage = requested(STORAGE, cmd.getStorage());
        Map<String, Long> gpus = requestedGpus(cmd.getGpu());
        if (cpu + memory + storage + gpus.values().stream().mapToLong(Long::longValue).sum() == 0) {
            throw new InvalidParameterValueException("A resource limit request asks for at least one of cpu, memory, storage and gpu");
        }
        Date expiry = expiryOf(cmd.getExpiry());
        String reason = StringUtils.trimToNull(cmd.getReason());
        ResourceLimitRequestVO request = inTransaction(status -> {
            lockAccount(owner.getId());
            List<ResourceLimitRequestVO> pending = requestDao.listByAccountAndStates(owner.getId(), ResourceLimitRequestVO.State.Pending);
            if (!pending.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Account %s already has the pending resource limit request %s; cancel it or wait "
                        + "for its decision", owner.getAccountName(), pending.get(0).getUuid()));
            }
            ResourceLimitRequestVO created = requestDao.persist(new ResourceLimitRequestVO(owner.getId(), owner.getDomainId(), cpu, memory, storage, gpus,
                    reason, expiry));
            recordEvent(context.getCallingUserId(), owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_CREATE, String.format("Resource limit request %s: %s",
                    created.getUuid(), describe(cpu, memory, storage, gpus, expiry)));
            return created;
        });
        notifyAfterCommit("Mailing the approvers of " + request, () -> mailApprovers(request, owner));
        return request;
    }

    @Override
    public ListResponse<ResourceLimitRequestResponse> listRequests(ListResourceLimitRequestsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> scope = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, cmd.getId(), cmd.getAccountName(), null, permittedAccounts, scope, cmd.listAll(), false);
        if (permittedAccounts.isEmpty() && scope.first() == null && caller.getType() != Account.Type.ADMIN) {
            scope.first(caller.getDomainId());
            scope.second(true);
        }
        Filter filter = new Filter(ResourceLimitRequestVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        filter.addOrderBy(ResourceLimitRequestVO.class, "id", false);
        Pair<List<ResourceLimitRequestVO>, Integer> found = requestDao.search(cmd.getId(), cmd.getState(), permittedAccounts, scope.first(),
                Boolean.TRUE.equals(scope.second()), filter);
        ListResponse<ResourceLimitRequestResponse> response = new ListResponse<>();
        response.setResponses(found.first().stream().map(this::createResponse).collect(Collectors.toList()), found.second());
        return response;
    }

    @Override
    public ResourceLimitRequestVO cancelRequest(long id) {
        CallContext context = CallContext.current();
        ResourceLimitRequestVO request = requestOf(id);
        accountManager.checkAccess(context.getCallingAccount(), null, true, request);
        Account owner = accountDao.findByIdIncludingRemoved(request.getAccountId());
        Date now = now();
        ResourceLimitRequestVO cancelled = inTransaction(status -> {
            lockAccount(owner.getId());
            ResourceLimitRequestVO current = requestDao.findById(id);
            if (current.getState() != ResourceLimitRequestVO.State.Pending && current.getState() != ResourceLimitRequestVO.State.Approved) {
                throw new InvalidParameterValueException(String.format("Resource limit request %s is %s; only a pending request or a grant can be cancelled",
                        current.getUuid(), current.getState()));
            }
            if (current.getState() == ResourceLimitRequestVO.State.Approved && context.getCallingAccount().getId() != current.getAccountId()) {
                throw new PermissionDeniedException(String.format("Grant %s is given up only by its own Account; another account ends it with "
                        + "revokeResourceLimitRequest", current.getUuid()));
            }
            current.setState(ResourceLimitRequestVO.State.Cancelled);
            requestDao.update(current.getId(), current);
            if (followsGrants(owner)) {
                writeLimits(owner, now);
            }
            recordEvent(context.getCallingUserId(), owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_CANCEL,
                    String.format("Resource limit request %s cancelled", current.getUuid()));
            return current;
        });
        return cancelled;
    }

    @Override
    public ResourceLimitRequestVO approveRequest(ApproveResourceLimitRequestCmd cmd) {
        CallContext context = CallContext.current();
        ResourceLimitRequestVO request = requestOf(cmd.getId());
        Account owner = decidableOwner(context.getCallingAccount(), request);
        if (!followsGrants(owner)) {
            throw new InvalidParameterValueException(String.format("The limits of Account %s are not set by grants; its request can be rejected, "
                    + "not approved", owner.getAccountName()));
        }
        long cpu = cmd.getCpu() == null ? request.getCpu() : requested(CPU, cmd.getCpu());
        long memory = cmd.getMemory() == null ? request.getMemoryMib() : requested(MEMORY, cmd.getMemory());
        long storage = cmd.getStorage() == null ? request.getStorageGib() : requested(STORAGE, cmd.getStorage());
        Map<String, Long> gpus = requestedGpus(cmd.getGpu() == null ? request.getGpus() : cmd.getGpu());
        Date expiry = cmd.getExpiry() == null ? grantExpiryOf(request.getRequestedExpiry()) : expiryOf(cmd.getExpiry());
        String comment = StringUtils.trimToNull(cmd.getComment());
        Date now = now();
        ResourceLimitRequestVO approved = inTransaction(status -> {
            lockAccount(owner.getId());
            ResourceLimitRequestVO current = pending(cmd.getId());
            current.approve(cpu, memory, storage, gpus, expiry);
            current.decide(context.getCallingUserId(), now, comment);
            requestDao.update(current.getId(), current);
            writeLimits(owner, now);
            recordEvent(context.getCallingUserId(), owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_APPROVE, String.format("Resource limit request %s approved: %s",
                    current.getUuid(), describe(cpu, memory, storage, gpus, expiry)));
            return current;
        });
        notifyAfterCommit("Mailing the decision of " + approved, () -> mailDecision(approved, owner, "approved"));
        return approved;
    }

    @Override
    public ResourceLimitRequestVO rejectRequest(long id, String reason) {
        CallContext context = CallContext.current();
        ResourceLimitRequestVO request = requestOf(id);
        Account owner = decidableOwner(context.getCallingAccount(), request);
        Date now = now();
        ResourceLimitRequestVO rejected = inTransaction(status -> {
            lockAccount(owner.getId());
            ResourceLimitRequestVO current = pending(id);
            current.setState(ResourceLimitRequestVO.State.Rejected);
            current.decide(context.getCallingUserId(), now, StringUtils.trimToNull(reason));
            requestDao.update(current.getId(), current);
            recordEvent(context.getCallingUserId(), owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_REJECT,
                    String.format("Resource limit request %s rejected", current.getUuid()));
            return current;
        });
        notifyAfterCommit("Mailing the decision of " + rejected, () -> mailDecision(rejected, owner, "rejected"));
        return rejected;
    }

    @Override
    public ResourceLimitRequestVO revokeRequest(long id, String reason) {
        CallContext context = CallContext.current();
        ResourceLimitRequestVO request = requestOf(id);
        Account owner = decidableOwner(context.getCallingAccount(), request);
        Date now = now();
        ResourceLimitRequestVO revoked = inTransaction(status -> {
            lockAccount(owner.getId());
            ResourceLimitRequestVO current = requestDao.findById(id);
            if (current.getState() != ResourceLimitRequestVO.State.Approved) {
                throw new InvalidParameterValueException(String.format("Resource limit request %s is %s; only a grant can be revoked", current.getUuid(),
                        current.getState()));
            }
            current.setState(ResourceLimitRequestVO.State.Revoked);
            current.decide(context.getCallingUserId(), now, StringUtils.trimToNull(reason));
            requestDao.update(current.getId(), current);
            if (followsGrants(owner)) {
                writeLimits(owner, now);
            }
            recordEvent(context.getCallingUserId(), owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_REVOKE,
                    String.format("Resource limit grant %s revoked", current.getUuid()));
            return current;
        });
        notifyAfterCommit("Mailing the decision of " + revoked, () -> mailDecision(revoked, owner, "revoked"));
        return revoked;
    }

    @Override
    public ResourceLimitRequestResponse createResponse(ResourceLimitRequestVO request) {
        ResourceLimitRequestResponse response = new ResourceLimitRequestResponse();
        response.setId(request.getUuid());
        AccountVO account = accountDao.findByIdIncludingRemoved(request.getAccountId());
        if (account != null) {
            response.setAccountName(account.getAccountName());
            response.setAccountId(account.getUuid());
        }
        DomainVO domain = domainDao.findByIdIncludingRemoved(request.getDomainId());
        if (domain != null) {
            response.setDomainName(domain.getName());
            response.setDomainId(domain.getUuid());
        }
        response.setCpu(request.getCpu());
        response.setMemory(request.getMemoryMib());
        response.setStorage(request.getStorageGib());
        response.setGpu(request.getGpus());
        response.setReason(request.getReason());
        response.setExpiry(request.getRequestedExpiry());
        response.setState(request.getState().toString());
        if (request.getApprovedCpu() != null) {
            response.setApprovedCpu(request.getApprovedCpu());
            response.setApprovedMemory(request.getApprovedMemoryMib());
            response.setApprovedStorage(request.getApprovedStorageGib());
            response.setApprovedGpu(request.getApprovedGpus());
            response.setApprovedExpiry(request.getApprovedExpiry());
        }
        if (request.getApproverId() != null) {
            UserVO approver = userDao.findByIdIncludingRemoved(request.getApproverId());
            response.setApprover(approver == null ? null : approver.getUsername());
        }
        response.setDecided(request.getDecidedAt());
        response.setComment(request.getComment());
        response.setExpiryWarningDays(request.getExpiryWarningDays());
        response.setCreated(request.getCreated());
        return response;
    }

    /** Runs a notification of a change that is already saved: a failure is logged and neither fails nor undoes the change. */
    private void notifyAfterCommit(String what, Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            logger.warn("{} failed; the change itself is kept: {}", what, e.getMessage(), e);
        }
    }

    private ResourceLimitRequestVO requestOf(long id) {
        ResourceLimitRequestVO request = requestDao.findById(id);
        if (request == null) {
            throw new InvalidParameterValueException("No such resource limit request");
        }
        return request;
    }

    private ResourceLimitRequestVO pending(long id) {
        ResourceLimitRequestVO current = requestDao.findById(id);
        if (current.getState() != ResourceLimitRequestVO.State.Pending) {
            throw new InvalidParameterValueException(String.format("Resource limit request %s is %s, not pending", current.getUuid(), current.getState()));
        }
        return current;
    }

    /**
     * A decision needs access to the requesting account, as for its limits, and is never the caller's own: no account
     * grants itself limits.
     */
    private Account decidableOwner(Account caller, ResourceLimitRequestVO request) {
        Account owner = accountDao.findById(request.getAccountId());
        if (owner == null) {
            throw new InvalidParameterValueException(String.format("The Account of resource limit request %s no longer exists", request.getUuid()));
        }
        accountManager.checkAccess(caller, null, true, request);
        if (caller.getId() == owner.getId()) {
            throw new PermissionDeniedException("An Account cannot decide its own resource limit request");
        }
        return owner;
    }

    private static long requested(String name, Long value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > MAX_REQUESTED) {
            throw new InvalidParameterValueException(String.format("%s must be between 0 and %d", name, MAX_REQUESTED));
        }
        return value;
    }

    /** @return the given GPU counts without zero counts, after checking that each names a GPU type */
    private Map<String, Long> requestedGpus(Map<String, Long> counts) {
        Map<String, Long> gpus = new TreeMap<>();
        if (counts == null || counts.isEmpty()) {
            return gpus;
        }
        Set<String> types = gpuUnits().keySet();
        for (Map.Entry<String, Long> count : counts.entrySet()) {
            if (!types.contains(count.getKey())) {
                throw new InvalidParameterValueException(String.format("%s is not a GPU type; the GPU types are %s", count.getKey(), types));
            }
            long value = requested(GPU + " " + count.getKey(), count.getValue());
            if (value > 0) {
                gpus.put(count.getKey(), value);
            }
        }
        return gpus;
    }

    protected Date now() {
        return new Date();
    }

    private ZoneId expiryZone() {
        return ZoneId.of(ExpiryTimezone.value());
    }

    private LocalDate today(ZoneId zone) {
        return LocalDate.now(Clock.fixed(now().toInstant(), zone));
    }

    /** @return the end of the given day, 23:59:59 in the expiry zone, after checking it against the expiry settings */
    protected Date expiryOf(String day) {
        if (StringUtils.isBlank(day)) {
            return grantExpiryOf(null);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidParameterValueException(String.format("%s is not a date of the form yyyy-MM-dd", day));
        }
        return endOf(date);
    }

    /** @return the end of the day on which the given expiry ends, checked against the expiry settings as they are now */
    private Date grantExpiryOf(Date expiry) {
        if (expiry == null) {
            if (ExpiryRequired.value()) {
                throw new InvalidParameterValueException("An expiry date is required");
            }
            int maxDays = ResourceLimitGrantService.maxExpiryDays();
            if (maxDays > 0) {
                throw new InvalidParameterValueException(String.format("An expiry date at most %d days from today is required", maxDays));
            }
            return null;
        }
        return endOf(expiry.toInstant().atZone(expiryZone()).toLocalDate());
    }

    private Date endOf(LocalDate date) {
        ZoneId zone = expiryZone();
        LocalDate today = today(zone);
        if (date.isBefore(today)) {
            throw new InvalidParameterValueException(String.format("The expiry date %s is in the past", date));
        }
        int maxDays = ResourceLimitGrantService.maxExpiryDays();
        if (maxDays > 0 && date.isAfter(today.plusDays(maxDays))) {
            throw new InvalidParameterValueException(String.format("The expiry date %s is more than %d days from today; the latest is %s", date, maxDays,
                    today.plusDays(maxDays)));
        }
        return Date.from(date.atTime(23, 59, 59).atZone(zone).toInstant());
    }

    private static boolean expired(ResourceLimitRequestVO grant, Date now) {
        return grant.getApprovedExpiry() != null && now.after(grant.getApprovedExpiry());
    }

    protected <T> T inTransaction(TransactionCallback<T> callback) {
        return Transaction.execute(callback);
    }

    private void lockAccount(long accountId) {
        if (accountDao.lockRow(accountId, true) == null) {
            throw new CloudRuntimeException(String.format("Account %d no longer exists", accountId));
        }
    }

    /** Accounts whose limits follow their grants: root admin accounts are unlimited, and project accounts follow the project defaults. */
    protected boolean followsGrants(Account account) {
        return account != null && account.getRemoved() == null && account.getId() != Account.ACCOUNT_ID_SYSTEM && account.getType() != Account.Type.PROJECT
                && !accountManager.isRootAdmin(account.getId());
    }

    @Override
    public void recompute(Account account) {
        if (!followsGrants(account)) {
            return;
        }
        Date now = now();
        inTransaction(status -> {
            lockAccount(account.getId());
            writeLimits(account, now);
            return null;
        });
    }

    /** Writes the account's limits; the caller holds the account's row lock in the transaction it runs in. */
    protected void writeLimits(Account account, Date now) {
        Defaults defaults = defaults();
        Map<String, GpuUnit> units = gpuUnits();
        Quota quota = effectiveQuota(account, now, defaults, units.keySet());
        boolean combined = Boolean.TRUE.equals(ResourceLimitService.ResourceLimitStorageCombined.valueIn(account.getId()));
        List<LimitRow> rows = limitRows(quota, defaults, units, combined, resourceLimitService.getResourceLimitHostTags());
        CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount());
        try {
            for (LimitRow row : rows) {
                ResourceLimitVO current = resourceLimitDao.findByOwnerIdAndTypeAndTag(account.getId(), ResourceOwnerType.Account, row.type, row.tag);
                if (current != null && current.getMax() != null && current.getMax() == row.storedValue()) {
                    continue;
                }
                resourceLimitService.updateResourceLimit(account.getId(), null, row.type.getOrdinal(), row.value, row.tag);
            }
        } finally {
            CallContext.unregister();
        }
    }

    /** The account defaults as configured now; a default that is not a number fails the recompute rather than grant something else. */
    protected Defaults defaults() {
        Defaults defaults = new Defaults();
        defaults.cpu = configured(Config.DefaultMaxAccountCpus.key(), Config.DefaultMaxAccountCpus.getDefaultValue());
        defaults.memoryMib = configured(Config.DefaultMaxAccountMemory.key(), Config.DefaultMaxAccountMemory.getDefaultValue());
        defaults.storageGib = configured(Config.DefaultMaxAccountPrimaryStorage.key(), Config.DefaultMaxAccountPrimaryStorage.getDefaultValue());
        defaults.gpusPerType = configured(ResourceLimitService.DefaultMaxAccountGpus.key(), ResourceLimitService.DefaultMaxAccountGpus.defaultValue());
        defaults.snapshots = configured(Config.DefaultMaxAccountSnapshots.key(), Config.DefaultMaxAccountSnapshots.getDefaultValue());
        defaults.secondaryStorageGib = configured(ResourceLimitService.MaxAccountSecondaryStorage.key(), ResourceLimitService.MaxAccountSecondaryStorage.defaultValue());
        return defaults;
    }

    private long configured(String key, String fallback) {
        String value = configurationDao.getValue(key);
        String effective = StringUtils.isBlank(value) ? fallback : value.trim();
        try {
            long parsed = Long.parseLong(effective);
            return parsed < 0 ? Resource.RESOURCE_UNLIMITED : parsed;
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException(String.format("The account default %s is not a number: %s", key, value));
        }
    }

    /** Per category the larger of the default and the approved value of every grant that has not expired; the default never expires. */
    protected Quota effectiveQuota(Account account, Date now, Defaults defaults, Set<String> gpuTypes) {
        Quota quota = new Quota();
        quota.cpu = defaults.cpu;
        quota.memoryMib = defaults.memoryMib;
        quota.storageGib = defaults.storageGib;
        for (String type : gpuTypes) {
            quota.gpus.put(type, defaults.gpusPerType);
        }
        for (ResourceLimitRequestVO grant : requestDao.listByAccountAndStates(account.getId(), ResourceLimitRequestVO.State.Approved)) {
            if (expired(grant, now)) {
                continue;
            }
            quota.cpu = larger(quota.cpu, grant.getApprovedCpu());
            quota.memoryMib = larger(quota.memoryMib, grant.getApprovedMemoryMib());
            quota.storageGib = larger(quota.storageGib, grant.getApprovedStorageGib());
            Map<String, Long> granted = grant.getApprovedGpus();
            for (String type : gpuTypes) {
                quota.gpus.put(type, larger(quota.gpus.get(type), granted.get(type)));
            }
        }
        return quota;
    }

    /**
     * The limits of the quota: untagged first, then those of each resource limit host tag. A GPU type's tag gets the GPUs of
     * that type with their vCPUs and memory; any other tag gets the vCPUs and memory of Instances without a GPU. An
     * Instance has at least one vCPU and a volume at least one GiB, so the counts of Instances and volumes follow from the
     * other categories.
     */
    protected static List<LimitRow> limitRows(Quota quota, Defaults defaults, Map<String, GpuUnit> units, boolean combinedStorage, List<String> tags) {
        long gpuVcpus = 0;
        long gpuMemory = 0;
        long gpus = 0;
        for (Map.Entry<String, GpuUnit> unit : units.entrySet()) {
            long count = quota.gpus.getOrDefault(unit.getKey(), 0L);
            gpuVcpus = plus(gpuVcpus, times(count, unit.getValue().vcpus));
            gpuMemory = plus(gpuMemory, times(count, unit.getValue().memoryMib));
            gpus = plus(gpus, count);
        }
        List<LimitRow> rows = new ArrayList<>();
        rows.add(new LimitRow(ResourceType.cpu, null, plus(quota.cpu, gpuVcpus)));
        rows.add(new LimitRow(ResourceType.memory, null, plus(quota.memoryMib, gpuMemory)));
        rows.add(new LimitRow(ResourceType.gpu, null, gpus));
        rows.add(new LimitRow(ResourceType.user_vm, null, plus(quota.cpu, gpus)));
        rows.add(new LimitRow(ResourceType.primary_storage, null, quota.storageGib));
        rows.add(new LimitRow(ResourceType.volume, null, quota.storageGib));
        rows.add(new LimitRow(ResourceType.secondary_storage, null, combinedStorage ? Resource.RESOURCE_UNLIMITED : defaults.secondaryStorageGib));
        rows.add(new LimitRow(ResourceType.snapshot, null, defaults.snapshots));
        for (String tag : tags) {
            GpuUnit unit = units.get(tag);
            if (unit == null) {
                rows.add(new LimitRow(ResourceType.user_vm, tag, quota.cpu));
                rows.add(new LimitRow(ResourceType.cpu, tag, quota.cpu));
                rows.add(new LimitRow(ResourceType.memory, tag, quota.memoryMib));
                rows.add(new LimitRow(ResourceType.gpu, tag, 0));
            } else {
                long count = quota.gpus.getOrDefault(tag, 0L);
                rows.add(new LimitRow(ResourceType.user_vm, tag, count));
                rows.add(new LimitRow(ResourceType.cpu, tag, times(count, unit.vcpus)));
                rows.add(new LimitRow(ResourceType.memory, tag, times(count, unit.memoryMib)));
                rows.add(new LimitRow(ResourceType.gpu, tag, count));
            }
        }
        return rows;
    }

    private static long larger(Long a, Long b) {
        long x = a == null ? 0 : a;
        long y = b == null ? 0 : b;
        return x == Resource.RESOURCE_UNLIMITED || y == Resource.RESOURCE_UNLIMITED ? Resource.RESOURCE_UNLIMITED : Math.max(x, y);
    }

    private static long plus(long a, long b) {
        if (a == Resource.RESOURCE_UNLIMITED || b == Resource.RESOURCE_UNLIMITED) {
            return Resource.RESOURCE_UNLIMITED;
        }
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long times(long count, long unit) {
        if (count == 0) {
            return 0;
        }
        if (count == Resource.RESOURCE_UNLIMITED) {
            return Resource.RESOURCE_UNLIMITED;
        }
        try {
            return Math.multiplyExact(count, unit);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The GPU types and their sizes, from the active one-GPU offerings: a type is the one host tag of such an offering. Of
     * several offerings of a type, the newest counts.
     */
    protected Map<String, GpuUnit> gpuUnits() {
        Map<String, ServiceOfferingVO> byType = new TreeMap<>();
        for (ServiceOfferingVO offering : listOneGpuOfferings()) {
            if (!CapacityManager.isHostCapacityExempt(offering) || offering.getGpuCount() == null || offering.getGpuCount() != 1) {
                continue;
            }
            List<String> tags = com.cloud.utils.StringUtils.csvTagsToList(offering.getHostTag());
            if (tags.size() != 1) {
                logger.warn("One-GPU offering {} has host tags {}, not one GPU type; it does not size a GPU type", offering, tags);
                continue;
            }
            ServiceOfferingVO known = byType.get(tags.get(0));
            if (known == null || offering.getId() > known.getId()) {
                byType.put(tags.get(0), offering);
            }
        }
        Map<String, GpuUnit> units = new TreeMap<>();
        byType.forEach((type, offering) -> units.put(type, new GpuUnit(offering.getCpu(), offering.getRamSize())));
        return units;
    }

    protected List<ServiceOfferingVO> listOfferingsWithoutGpu() {
        SearchCriteria<ServiceOfferingVO> sc = offeringWithoutGpuSearch.create();
        sc.setParameters("state", ServiceOffering.State.Active);
        sc.setParameters("systemUse", false);
        return serviceOfferingDao.search(sc, null);
    }

    /**
     * Reports the GPU types that no active one-GPU offering sizes: those named by an approved, unexpired grant, and the
     * resource limit host tags that no one-GPU offering sizes and no active offering without a GPU carries. Their grants
     * count nothing until such an offering exists. Logged on every run; an alert is raised when the set differs from the
     * one last reported, which is kept in the database so that a restart or another management server does not repeat it.
     */
    protected void reportUnsizedGpuTypes(Date now) {
        Set<String> sized = gpuUnits().keySet();
        Set<String> unsized = new TreeSet<>();
        for (ResourceLimitRequestVO grant : requestDao.listByState(ResourceLimitRequestVO.State.Approved)) {
            if (!expired(grant, now)) {
                grant.getApprovedGpus().keySet().stream().filter(type -> !sized.contains(type)).forEach(unsized::add);
            }
        }
        Set<String> tagsWithoutGpu = new TreeSet<>();
        for (ServiceOfferingVO offering : listOfferingsWithoutGpu()) {
            tagsWithoutGpu.addAll(com.cloud.utils.StringUtils.csvTagsToList(offering.getHostTag()));
        }
        for (String tag : resourceLimitService.getResourceLimitHostTags()) {
            if (StringUtils.isNotBlank(tag) && !sized.contains(tag) && !tagsWithoutGpu.contains(tag)) {
                unsized.add(tag);
            }
        }
        String current = String.join(",", unsized);
        String reported = StringUtils.defaultString(configurationDao.getValueAndInitIfNotExist(REPORTED_UNSIZED_GPU_TYPES, "Hidden", ""));
        if (unsized.isEmpty()) {
            if (!reported.isEmpty()) {
                configurationDao.update(REPORTED_UNSIZED_GPU_TYPES, "Hidden", "");
            }
            return;
        }
        logger.warn("GPU types {} have no active one-GPU offering; their grants count nothing until one exists", unsized);
        if (!current.equals(reported)) {
            alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_RESOURCE_LIMIT_EXCEEDED, 0L, null,
                    String.format("GPU types without a one-GPU offering: %s", String.join(", ", unsized)),
                    "Resource limit grants of these GPU types count nothing, and their resource limit host tags get no GPUs, until an active offering "
                            + "with one GPU of the type exists.");
            configurationDao.update(REPORTED_UNSIZED_GPU_TYPES, "Hidden", current);
        }
    }

    protected List<ServiceOfferingVO> listOneGpuOfferings() {
        SearchCriteria<ServiceOfferingVO> sc = unitOfferingSearch.create();
        sc.setParameters("gpuCount", 1);
        sc.setParameters("state", ServiceOffering.State.Active);
        sc.setParameters("systemUse", false);
        return serviceOfferingDao.search(sc, null);
    }

    @SuppressWarnings("unchecked")
    protected void onAccountsAdded(Object args) {
        for (Long accountId : ((Map<Long, Long>)args).keySet()) {
            try {
                recompute(accountDao.findById(accountId));
            } catch (RuntimeException e) {
                logger.error("Writing the limits of new Account {} failed; the next expiry check writes them: {}", accountId, e.getMessage(), e);
            }
        }
    }

    protected void onAccountRemoved(Object args) {
        long accountId = (Long)args;
        try {
            inTransaction(status -> {
                for (ResourceLimitRequestVO request : requestDao.listByAccountAndStates(accountId, ResourceLimitRequestVO.State.Pending,
                        ResourceLimitRequestVO.State.Approved)) {
                    request.setState(ResourceLimitRequestVO.State.Cancelled);
                    requestDao.update(request.getId(), request);
                }
                return null;
            });
        } catch (RuntimeException e) {
            logger.error("Cancelling the open resource limit requests of removed Account {} failed: {}", accountId, e.getMessage(), e);
        }
    }

    protected ConfigKeyScheduledExecutionWrapper sweepRunner(ScheduledExecutorService executor) {
        return new ConfigKeyScheduledExecutionWrapper(executor, new SweepTask(), ExpiryCheckInterval, TimeUnit.SECONDS);
    }

    final class SweepTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            runSweep();
        }
    }

    /** Runs the sweep on one management server at a time. */
    protected void runSweep() {
        GlobalLock lock = GlobalLock.getInternLock(SWEEP_LOCK);
        try {
            if (!lock.lock(0)) {
                logger.debug("Another management server runs the resource limit expiry check");
                return;
            }
            CallContext.register(accountManager.getSystemUser(), accountManager.getSystemAccount());
            try {
                sweep(now());
            } finally {
                CallContext.unregister();
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }

    /**
     * Expires the grants past their expiry, writes every account's limits from its grants and the defaults as they are
     * now, and sends the expiry warnings that are due. Running Instances are left alone.
     */
    protected void sweep(Date now) {
        try {
            reportUnsizedGpuTypes(now);
        } catch (RuntimeException e) {
            logger.warn("Checking the GPU types of the resource limit grants failed: {}", e.getMessage(), e);
        }
        for (AccountVO account : accountDao.listAll()) {
            if (!followsGrants(account)) {
                continue;
            }
            try {
                inTransaction(status -> {
                    lockAccount(account.getId());
                    for (ResourceLimitRequestVO grant : requestDao.listByAccountAndStates(account.getId(), ResourceLimitRequestVO.State.Approved)) {
                        if (expired(grant, now)) {
                            grant.setState(ResourceLimitRequestVO.State.Expired);
                            requestDao.update(grant.getId(), grant);
                            recordEvent(User.UID_SYSTEM, account, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_EXPIRE,
                                    String.format("Resource limit grant %s expired", grant.getUuid()));
                        }
                    }
                    writeLimits(account, now);
                    return null;
                });
            } catch (RuntimeException e) {
                logger.warn("Expiring the grants and writing the limits of Account {} failed: {}", account, e.getMessage(), e);
            }
        }
        sendExpiryWarnings(now);
    }

    /** @return the days of {@code resource.request.expiry.warning.days}, ascending */
    protected List<Integer> warningDays() {
        String value = ExpiryWarningDays.value();
        Set<Integer> days = new TreeSet<>();
        if (StringUtils.isNotBlank(value)) {
            for (String day : value.split(",")) {
                int parsed = NumberUtils.toInt(day.trim(), 0);
                if (parsed > 0) {
                    days.add(parsed);
                }
            }
        }
        return new ArrayList<>(days);
    }

    /**
     * Warns once for the narrowest warning window a grant is in: a grant that enters it late, or whose warnings were missed,
     * gets that window's warning and not the wider ones it passed.
     */
    protected void sendExpiryWarnings(Date now) {
        List<Integer> days = warningDays();
        if (days.isEmpty()) {
            return;
        }
        for (ResourceLimitRequestVO grant : requestDao.listApprovedWithExpiry()) {
            if (expired(grant, now)) {
                continue;
            }
            long left = grant.getApprovedExpiry().getTime() - now.getTime();
            Integer due = days.stream().filter(day -> left <= day * DAY_MILLIS).findFirst().orElse(null);
            if (due == null || (grant.getExpiryWarningDays() != null && grant.getExpiryWarningDays() <= due)) {
                continue;
            }
            AccountVO owner = accountDao.findById(grant.getAccountId());
            if (!followsGrants(owner)) {
                continue;
            }
            try {
                ResourceLimitRequestVO warned = inTransaction(status -> {
                    lockAccount(owner.getId());
                    ResourceLimitRequestVO current = requestDao.findById(grant.getId());
                    if (current.getState() != ResourceLimitRequestVO.State.Approved
                            || (current.getExpiryWarningDays() != null && current.getExpiryWarningDays() <= due)) {
                        return null;
                    }
                    current.setExpiryWarningDays(due);
                    requestDao.update(current.getId(), current);
                    recordEvent(User.UID_SYSTEM, owner, EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_EXPIRING, String.format(
                            "Resource limit grant %s expires within %d day(s), at %s", current.getUuid(), due, formatted(current.getApprovedExpiry())));
                    return current;
                });
                if (warned != null) {
                    mailExpiryWarning(warned, owner, due);
                }
            } catch (RuntimeException e) {
                logger.warn("Warning Account {} of the expiry of grant {} failed: {}", owner, grant.getUuid(), e.getMessage(), e);
            }
        }
    }

    protected void recordEvent(long userId, Account owner, String type, String description) {
        ActionEventUtils.onActionEvent(userId, owner.getId(), owner.getDomainId(), type, description, owner.getId(), ApiCommandResourceType.Account.toString());
    }

    private String formatted(Date date) {
        return date == null ? "no end" : MAIL_DATE.format(date.toInstant().atZone(expiryZone()));
    }

    private String describe(long cpu, long memory, long storage, Map<String, Long> gpus, Date expiry) {
        return String.format("cpu %d, memory %d MiB, storage %d GiB, gpu %s, until %s", cpu, memory, storage, gpus.isEmpty() ? "none" : gpus, formatted(expiry));
    }

    /**
     * The approvers of a request are the accounts that the API checkers let call {@link #APPROVE_API} and that have access
     * to the requesting account; they follow the role rules, whatever the account type.
     */
    protected List<Account> approversOf(Account owner) {
        List<Account> approvers = new ArrayList<>();
        for (AccountVO account : accountDao.listAll()) {
            if (account.getState() != Account.State.ENABLED || account.getId() == Account.ACCOUNT_ID_SYSTEM || account.getId() == owner.getId()) {
                continue;
            }
            try {
                accountManager.checkApiAccess(account, APPROVE_API);
                accountManager.checkAccess(account, null, true, owner);
            } catch (PermissionDeniedException e) {
                continue;
            }
            approvers.add(account);
        }
        return approvers;
    }

    private void mailApprovers(ResourceLimitRequestVO request, Account owner) {
        String subject = String.format("Resource limit request %s from Account %s", request.getUuid(), owner.getAccountName());
        if (smtpUnset(subject)) {
            return;
        }
        String body = String.format("Account %s asks for %s.%nReason: %s%n%nApprove, adjust or reject it on the resource limit request page.%n",
                owner.getAccountName(), describe(request.getCpu(), request.getMemoryMib(), request.getStorageGib(), request.getGpus(),
                        request.getRequestedExpiry()), StringUtils.defaultString(request.getReason(), "none given"));
        mail(recipientsOf(approversOf(owner)), subject, body);
    }

    private void mailDecision(ResourceLimitRequestVO request, Account owner, String decision) {
        String subject = String.format("Your resource limit request %s was %s", request.getUuid(), decision);
        StringBuilder body = new StringBuilder(String.format("Your resource limit request %s was %s.%n", request.getUuid(), decision));
        if (request.getState() == ResourceLimitRequestVO.State.Approved) {
            body.append(String.format("Granted: %s.%n", describe(request.getApprovedCpu(), request.getApprovedMemoryMib(), request.getApprovedStorageGib(),
                    request.getApprovedGpus(), request.getApprovedExpiry())));
        }
        if (request.getComment() != null) {
            body.append(String.format("Comment: %s%n", request.getComment()));
        }
        notifyAccount(owner, subject, body.toString());
    }

    private void mailExpiryWarning(ResourceLimitRequestVO grant, Account owner, int days) {
        String subject = String.format("Your resource limit grant %s expires within %d day(s)", grant.getUuid(), days);
        String body = String.format("Your resource limit grant %s (%s) ends at %s.%nInstances over the remaining quota cannot start after that; "
                + "to keep the resources, request them again before then.%n", grant.getUuid(), describe(grant.getApprovedCpu(), grant.getApprovedMemoryMib(),
                grant.getApprovedStorageGib(), grant.getApprovedGpus(), grant.getApprovedExpiry()), formatted(grant.getApprovedExpiry()));
        notifyAccount(owner, subject, body);
    }

    @Override
    public void notifyAccount(Account account, String subject, String body) {
        if (smtpUnset(subject)) {
            return;
        }
        List<Account> accounts = new ArrayList<>();
        accounts.add(account);
        mail(recipientsOf(accounts), subject, body);
    }

    /** @return true, after logging one line, when no relay host is set and so nothing is sent */
    protected boolean smtpUnset(String subject) {
        if (StringUtils.isNotBlank(SmtpHost.value())) {
            return false;
        }
        logger.info("Not mailing \"{}\": {} is not set", subject, SmtpHost.key());
        return true;
    }

    private Set<MailAddress> recipientsOf(List<Account> accounts) {
        Set<MailAddress> recipients = new LinkedHashSet<>();
        for (Account account : accounts) {
            for (UserVO user : userDao.listByAccount(account.getId())) {
                if (user.getRemoved() == null && user.getState() == Account.State.ENABLED && StringUtils.isNotBlank(user.getEmail())) {
                    recipients.add(new MailAddress(user.getEmail().trim(), StringUtils.trimToEmpty(String.format("%s %s",
                            StringUtils.defaultString(user.getFirstname()), StringUtils.defaultString(user.getLastname())))));
                }
            }
        }
        return recipients;
    }

    private void mail(Set<MailAddress> recipients, String subject, String body) {
        if (recipients.isEmpty()) {
            logger.debug("Not mailing \"{}\": no recipient has an e-mail address", subject);
            return;
        }
        SMTPMailProperties mail = new SMTPMailProperties();
        mail.setSender(new MailAddress(SmtpSender.value()));
        mail.setSubject(subject);
        mail.setContent(body);
        mail.setContentType("text/plain; charset=utf-8");
        mail.setRecipients(recipients);
        SMTPMailSender sender = mailSender();
        mailExecutor().execute(() -> sender.sendMail(mail));
    }

    /** A sender for the relay as configured now, so that a changed setting applies to the next mail. */
    protected SMTPMailSender mailSender() {
        Map<String, String> configs = new HashMap<>();
        configs.put(SmtpHost.key(), SmtpHost.value());
        configs.put(SmtpPort.key(), String.valueOf(SmtpPort.value()));
        configs.put(SmtpUseAuth.key(), String.valueOf(SmtpUseAuth.value()));
        configs.put(SmtpUsername.key(), StringUtils.trimToNull(SmtpUsername.value()));
        configs.put(SmtpPassword.key(), StringUtils.trimToNull(SmtpPassword.value()));
        configs.put(SmtpUseStartTLS.key(), String.valueOf(SmtpUseStartTLS.value()));
        configs.put(SmtpEnabledSecurityProtocols.key(), SmtpEnabledSecurityProtocols.value());
        configs.put(MAIL_NAMESPACE + "." + SMTPMailSender.CONFIG_TIMEOUT, MAIL_TIMEOUT_MILLIS);
        configs.put(MAIL_NAMESPACE + "." + SMTPMailSender.CONFIG_CONNECTION_TIMEOUT, MAIL_TIMEOUT_MILLIS);
        return new SMTPMailSender(configs, MAIL_NAMESPACE);
    }

    protected Executor mailExecutor() {
        return mailExecutor;
    }
}
