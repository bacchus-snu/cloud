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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.persistence.Column;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKeyScheduledExecutionWrapper;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.MessageSubscriber;
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
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.cloud.alert.AlertManager;
import com.cloud.configuration.Resource;
import com.cloud.configuration.Resource.ResourceOwnerType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceLimitVO;
import com.cloud.configuration.dao.ResourceLimitDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
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
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ResourceLimitRequestServiceImplTest {

    private static final long SYSTEM = Account.ACCOUNT_ID_SYSTEM;
    private static final long ADMIN = 2L;
    private static final long STUDENT = 5L;
    private static final long CLASSMATE = 6L;
    private static final long TEACHER = 7L;
    private static final long RESOURCE_ADMIN = 8L;
    private static final long OUTSIDER = 13L;
    private static final long MEMBER = 9L;
    private static final long PROJECT = 12L;
    private static final long DOMAIN = 3L;
    private static final long OTHER_DOMAIN = 4L;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final List<String> TAGS = Arrays.asList("cpu", "a6000", "a100-40g", "a100-80g", "gtx1080");
    private static final String ASK_EXPIRY = "2026-11-30";

    @Spy
    @InjectMocks
    private ResourceLimitRequestServiceImpl service = new ResourceLimitRequestServiceImpl();

    @Mock
    private ResourceLimitRequestDao requestDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private AccountDao accountDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private UserDao userDao;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private ResourceLimitDao resourceLimitDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private ConfigurationDao configurationDao;
    @Mock
    private MessageBus messageBus;
    @Mock
    private AlertManager alertManager;

    private final ConfigDepotImpl configDepot = mock(ConfigDepotImpl.class);
    private final Map<String, String> settings = new HashMap<>();
    private final Map<Long, AccountVO> accounts = new LinkedHashMap<>();
    private final Map<Long, ResourceLimitRequestVO> requests = new LinkedHashMap<>();
    private final Map<String, Long> limits = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<ServiceOfferingVO> oneGpuOfferings = new ArrayList<>();
    private final List<ServiceOfferingVO> offeringsWithoutGpu = new ArrayList<>();
    private Date now = Date.from(Instant.parse("2026-11-01T03:00:00Z"));
    private boolean inTransaction;
    private int transactions;
    private Integer failAtWrite;
    private User systemUser;
    private Account systemAccount;

    @Before
    public void setUp() {
        ConfigKey.init(configDepot);
        when(configDepot.getConfigStringValue(anyString(), any(ConfigKey.Scope.class), nullable(Long.class))).thenAnswer(i -> settings.get((String)i.getArgument(0)));
        when(configurationDao.getValue(anyString())).thenAnswer(i -> settings.get((String)i.getArgument(0)));
        when(configurationDao.getValueAndInitIfNotExist(anyString(), anyString(), anyString()))
                .thenAnswer(i -> settings.computeIfAbsent(i.getArgument(0), name -> i.getArgument(2)));
        when(configurationDao.update(anyString(), anyString(), anyString())).thenAnswer(i -> {
            settings.put(i.getArgument(0), i.getArgument(2));
            return true;
        });
        settings.put("max.account.cpus", "2");
        settings.put("max.account.memory", "4096");
        settings.put("max.account.primary.storage", "20");
        settings.put("max.account.gpus", "0");
        settings.put("max.account.snapshots", "-1");
        settings.put("max.account.secondary.storage", "400");

        doAnswer(i -> now).when(service).now();
        doAnswer(i -> {
            Map<String, Long> before = new HashMap<>(limits);
            transactions++;
            inTransaction = true;
            try {
                return ((TransactionCallback<?>)i.getArgument(0)).doInTransaction(null);
            } catch (RuntimeException e) {
                limits.clear();
                limits.putAll(before);
                throw e;
            } finally {
                inTransaction = false;
            }
        }).when(service).inTransaction(any());
        doAnswer(i -> {
            Account owner = i.getArgument(1);
            events.add(i.getArgument(2) + " " + owner.getId() + " by " + i.getArgument(0) + ": " + i.getArgument(3));
            return null;
        }).when(service).recordEvent(anyLong(), any(Account.class), anyString(), anyString());
        doReturn((Executor)Runnable::run).when(service).mailExecutor();
        doAnswer(i -> new ArrayList<>(oneGpuOfferings)).when(service).listOneGpuOfferings();
        doAnswer(i -> new ArrayList<>(offeringsWithoutGpu)).when(service).listOfferingsWithoutGpu();
        ServiceOfferingVO cpuOffering = mock(ServiceOfferingVO.class);
        when(cpuOffering.getHostTag()).thenReturn("cpu");
        offeringsWithoutGpu.add(cpuOffering);
        gpuOffering(31L, "a6000", 12, 24 * 1024);
        gpuOffering(32L, "a100-40g", 12, 96 * 1024);
        gpuOffering(33L, "a100-80g", 12, 96 * 1024);
        gpuOffering(34L, "gtx1080", 6, 12 * 1024);
        when(resourceLimitService.getResourceLimitHostTags()).thenAnswer(i -> new ArrayList<>(TAGS));

        account(SYSTEM, "system", Account.Type.ADMIN, null);
        account(ADMIN, "admin", Account.Type.ADMIN, "admin@example.org");
        account(STUDENT, "student", Account.Type.NORMAL, "student@example.org");
        account(CLASSMATE, "classmate", Account.Type.NORMAL, "classmate@example.org");
        account(TEACHER, "teacher", Account.Type.DOMAIN_ADMIN, "teacher@example.org");
        account(MEMBER, "member", Account.Type.ADMIN, "member@example.org");
        account(RESOURCE_ADMIN, "operator", Account.Type.RESOURCE_DOMAIN_ADMIN, "operator@example.org", OTHER_DOMAIN);
        account(OUTSIDER, "outsider", Account.Type.NORMAL, "outsider@example.org", OTHER_DOMAIN);
        account(PROJECT, "PrjAcct-course", Account.Type.PROJECT, null);
        when(accountManager.isRootAdmin(anyLong())).thenAnswer(i -> {
            AccountVO account = accounts.get((Long)i.getArgument(0));
            return account != null && account.getType() == Account.Type.ADMIN;
        });
        when(accountDao.listAll()).thenAnswer(i -> new ArrayList<>(accounts.values()));
        when(accountDao.findById(anyLong())).thenAnswer(i -> accounts.get((Long)i.getArgument(0)));
        when(accountDao.findByIdIncludingRemoved(anyLong())).thenAnswer(i -> accounts.get((Long)i.getArgument(0)));
        when(accountDao.lockRow(anyLong(), anyBoolean())).thenAnswer(i -> {
            assertTrue("an account is locked inside a transaction", inTransaction);
            return accounts.get((Long)i.getArgument(0));
        });
        systemUser = mock(User.class);
        when(systemUser.getId()).thenReturn(User.UID_SYSTEM);
        systemAccount = accounts.get(SYSTEM);
        when(accountManager.getSystemUser()).thenReturn(systemUser);
        when(accountManager.getSystemAccount()).thenReturn(systemAccount);
        DomainVO domain = mock(DomainVO.class);
        when(domain.getName()).thenReturn("students");
        when(domain.getUuid()).thenReturn("domain-uuid");
        when(domainDao.findByIdIncludingRemoved(DOMAIN)).thenReturn(domain);

        // the access rules of the account manager: a normal account reaches only its own; admin accounts reach every account
        doAnswer(i -> {
            Account caller = i.getArgument(0);
            for (Object entity : Arrays.copyOfRange(i.getArguments(), 3, i.getArguments().length)) {
                long owner = entity instanceof Account ? ((Account)entity).getId() : ((ResourceLimitRequestVO)entity).getAccountId();
                if (caller.getType() == Account.Type.NORMAL && owner != caller.getId()) {
                    throw new PermissionDeniedException("no access");
                }
            }
            return null;
        }).when(accountManager).checkAccess(any(Account.class), isNull(), eq(true), any(ControlledEntity[].class));
        when(accountManager.finalizeOwner(any(Account.class), nullable(String.class), nullable(Long.class), isNull())).thenAnswer(i -> {
            Account caller = i.getArgument(0);
            String name = i.getArgument(1);
            if (name == null) {
                return caller;
            }
            Account owner = accounts.values().stream().filter(a -> a.getAccountName().equals(name)).findFirst().orElseThrow();
            if (caller.getType() == Account.Type.NORMAL && owner.getId() != caller.getId()) {
                throw new PermissionDeniedException("no access");
            }
            return owner;
        });
        // as stock: a normal account sees its own; without listall an account sees its own; with listall a domain admin sees its
        // subtree and other admin types are not narrowed here
        doAnswer(i -> {
            Account caller = i.getArgument(0);
            Long id = i.getArgument(1);
            List<Long> permitted = i.getArgument(4);
            Ternary<Long, Boolean, ?> scope = i.getArgument(5);
            boolean listAll = i.getArgument(6);
            if (scope.first() == null) {
                if (caller.getType() == Account.Type.NORMAL) {
                    permitted.add(caller.getId());
                } else if (!listAll) {
                    if (id == null) {
                        permitted.add(caller.getId());
                    } else if (caller.getType() != Account.Type.ADMIN) {
                        scope.first(caller.getDomainId());
                        scope.second(true);
                    }
                } else if (caller.getType() == Account.Type.DOMAIN_ADMIN) {
                    scope.first(caller.getDomainId());
                    scope.second(true);
                }
            } else if (caller.getType() == Account.Type.NORMAL) {
                permitted.add(caller.getId());
            }
            return null;
        }).when(accountManager).buildACLSearchParameters(any(Account.class), nullable(Long.class), nullable(String.class), isNull(), any(), any(), anyBoolean(),
                eq(false));

        when(requestDao.persist(any(ResourceLimitRequestVO.class))).thenAnswer(i -> {
            ResourceLimitRequestVO request = i.getArgument(0);
            ReflectionTestUtils.setField(request, "id", (long)(requests.size() + 100));
            ReflectionTestUtils.setField(request, "created", now);
            requests.put(request.getId(), request);
            return request;
        });
        when(requestDao.findById(anyLong())).thenAnswer(i -> requests.get((Long)i.getArgument(0)));
        when(requestDao.update(anyLong(), any(ResourceLimitRequestVO.class))).thenReturn(true);
        when(requestDao.listByAccountAndStates(anyLong(), any(ResourceLimitRequestVO.State[].class))).thenAnswer(i -> {
            long accountId = i.getArgument(0);
            List<ResourceLimitRequestVO.State> states = new ArrayList<>();
            for (Object state : Arrays.copyOfRange(i.getArguments(), 1, i.getArguments().length)) {
                if (state instanceof ResourceLimitRequestVO.State[]) {
                    states.addAll(Arrays.asList((ResourceLimitRequestVO.State[])state));
                } else {
                    states.add((ResourceLimitRequestVO.State)state);
                }
            }
            return requests.values().stream().filter(r -> r.getAccountId() == accountId && states.contains(r.getState())).collect(Collectors.toList());
        });
        when(requestDao.listByState(any(ResourceLimitRequestVO.State.class))).thenAnswer(i -> requests.values().stream()
                .filter(r -> r.getState() == i.getArgument(0)).collect(Collectors.toList()));
        when(requestDao.listApprovedWithExpiry()).thenAnswer(i -> requests.values().stream()
                .filter(r -> r.getState() == ResourceLimitRequestVO.State.Approved && r.getApprovedExpiry() != null).collect(Collectors.toList()));
        when(requestDao.search(nullable(Long.class), nullable(ResourceLimitRequestVO.State.class), any(), nullable(Long.class), anyBoolean(), any(Filter.class)))
                .thenAnswer(i -> {
                    Long id = i.getArgument(0);
                    ResourceLimitRequestVO.State state = i.getArgument(1);
                    List<Long> permitted = i.getArgument(2);
                    Long domainId = i.getArgument(3);
                    List<ResourceLimitRequestVO> found = requests.values().stream()
                            .filter(r -> id == null || r.getId() == id)
                            .filter(r -> state == null || r.getState() == state)
                            .filter(r -> permitted.isEmpty() || permitted.contains(r.getAccountId()))
                            .filter(r -> !permitted.isEmpty() || domainId == null || r.getDomainId() == domainId)
                            .collect(Collectors.toList());
                    return new Pair<>(found, found.size());
                });

        when(resourceLimitService.updateResourceLimit(anyLong(), isNull(), any(Integer.class), anyLong(), nullable(String.class))).thenAnswer(i -> {
            assertTrue("limits are written in the account's transaction", inTransaction);
            assertEquals("limits are written as the system", SYSTEM, CallContext.current().getCallingAccount().getId());
            if (failAtWrite != null && writes.size() == failAtWrite) {
                throw new CloudRuntimeException("database connection lost");
            }
            long accountId = i.getArgument(0);
            ResourceType type = typeOf(i.getArgument(2));
            long max = i.getArgument(3);
            String tag = i.getArgument(4);
            limits.put(key(accountId, type, tag), ResourceType.isStorageType(type) && max >= 0 ? max * GIB : max);
            writes.add(key(accountId, type, tag) + "=" + max);
            return null;
        });
        when(resourceLimitDao.findByOwnerIdAndTypeAndTag(anyLong(), eq(ResourceOwnerType.Account), any(ResourceType.class), nullable(String.class))).thenAnswer(i -> {
            Long stored = limits.get(key(i.getArgument(0), i.getArgument(2), i.getArgument(3)));
            return stored == null ? null : new ResourceLimitVO(i.getArgument(2), stored, i.getArgument(0), ResourceOwnerType.Account, i.getArgument(3));
        });

        as(STUDENT);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
        ConfigKey.init(null);
    }

    private static String key(long accountId, ResourceType type, String tag) {
        return accountId + ":" + type + (tag == null ? "" : "/" + tag);
    }

    private static ResourceType typeOf(int ordinal) {
        return Arrays.stream(ResourceType.values()).filter(t -> t.getOrdinal() == ordinal).findFirst().orElseThrow();
    }

    private void gpuOffering(long id, String type, int vcpus, int memoryMib) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getId()).thenReturn(id);
        when(offering.getHostTag()).thenReturn(type);
        when(offering.getCpu()).thenReturn(vcpus);
        when(offering.getRamSize()).thenReturn(memoryMib);
        when(offering.getVgpuProfileId()).thenReturn(id + 100);
        when(offering.getGpuCount()).thenReturn(1);
        oneGpuOfferings.add(offering);
    }

    private AccountVO account(long id, String name, Account.Type type, String email) {
        return account(id, name, type, email, DOMAIN);
    }

    private AccountVO account(long id, String name, Account.Type type, String email, long domainId) {
        AccountVO account = mock(AccountVO.class);
        when(account.getId()).thenReturn(id);
        when(account.getUuid()).thenReturn(name + "-uuid");
        when(account.getAccountName()).thenReturn(name);
        when(account.getType()).thenReturn(type);
        when(account.getDomainId()).thenReturn(domainId);
        when(account.getState()).thenReturn(Account.State.ENABLED);
        accounts.put(id, account);
        UserVO user = mock(UserVO.class);
        when(user.getId()).thenReturn(id * 10);
        when(user.getAccountId()).thenReturn(id);
        when(user.getUsername()).thenReturn(name);
        when(user.getEmail()).thenReturn(email);
        when(user.getState()).thenReturn(Account.State.ENABLED);
        when(userDao.listByAccount(id)).thenReturn(Collections.singletonList(user));
        when(userDao.findByIdIncludingRemoved(id * 10)).thenReturn(user);
        return account;
    }

    private void as(long accountId) {
        CallContext.unregisterAll();
        User user = mock(User.class);
        when(user.getId()).thenReturn(accountId * 10);
        CallContext.register(user, accounts.get(accountId));
    }

    /** Starts the service with an executor of its own, so that no sweep runs in the test. */
    private void started() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(service, "sweepExecutor", executor);
        doReturn(mock(ConfigKeyScheduledExecutionWrapper.class)).when(service).sweepRunner(executor);
        service.start();
    }

    private Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }

    private CreateResourceLimitRequestCmd ask(Long cpu, Long memory, Long storage, Map<String, Long> gpus, String expiry) {
        CreateResourceLimitRequestCmd cmd = mock(CreateResourceLimitRequestCmd.class);
        when(cmd.getCpu()).thenReturn(cpu);
        when(cmd.getMemory()).thenReturn(memory);
        when(cmd.getStorage()).thenReturn(storage);
        when(cmd.getGpu()).thenReturn(gpus);
        when(cmd.getExpiry()).thenReturn(expiry);
        when(cmd.getReason()).thenReturn("thesis experiments");
        return cmd;
    }

    private ApproveResourceLimitRequestCmd approval(long id) {
        ApproveResourceLimitRequestCmd cmd = mock(ApproveResourceLimitRequestCmd.class);
        when(cmd.getId()).thenReturn(id);
        // a mocked getter of a number or a map answers 0 or an empty map; an approval without adjustments answers null
        when(cmd.getCpu()).thenReturn(null);
        when(cmd.getMemory()).thenReturn(null);
        when(cmd.getStorage()).thenReturn(null);
        when(cmd.getGpu()).thenReturn(null);
        return cmd;
    }

    private static Map<String, Long> gpus(Object... typeAndCount) {
        Map<String, Long> counts = new TreeMap<>();
        for (int i = 0; i < typeAndCount.length; i += 2) {
            counts.put((String)typeAndCount[i], ((Number)typeAndCount[i + 1]).longValue());
        }
        return counts;
    }

    /** A grant of the student, approved by the member as asked. */
    private ResourceLimitRequestVO grant(Long cpu, Long memory, Long storage, Map<String, Long> gpus, String expiry) {
        as(STUDENT);
        ResourceLimitRequestVO request = service.createRequest(ask(cpu, memory, storage, gpus, expiry));
        as(MEMBER);
        service.approveRequest(approval(request.getId()));
        as(STUDENT);
        return request;
    }

    private long limit(ResourceType type, String tag) {
        Long stored = limits.get(key(STUDENT, type, tag));
        assertNotNull("no " + type + "/" + tag + " limit was written", stored);
        return stored;
    }

    private ListResourceLimitRequestsCmd listing(boolean listAll, ResourceLimitRequestVO.State state, Long id) {
        ListResourceLimitRequestsCmd cmd = mock(ListResourceLimitRequestsCmd.class);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.listAll()).thenReturn(listAll);
        when(cmd.getState()).thenReturn(state);
        when(cmd.getId()).thenReturn(id);
        return cmd;
    }

    private List<String> listedIds(ListResourceLimitRequestsCmd cmd) {
        ListResponse<ResourceLimitRequestResponse> response = service.listRequests(cmd);
        return response.getResponses().stream().map(ResourceLimitRequestResponse::getId).collect(Collectors.toList());
    }

    private FakeSmtpServer relay() throws Exception {
        FakeSmtpServer server = new FakeSmtpServer();
        settings.put("resource.request.smtp.host", "127.0.0.1");
        settings.put("resource.request.smtp.port", String.valueOf(server.getPort()));
        settings.put("resource.request.smtp.sender", "cloud@example.org");
        return server;
    }

    @Test
    public void userCanCreateAndCancelOwnRequest() {
        ResourceLimitRequestVO request = service.createRequest(ask(8L, 16384L, 100L, gpus("a100-40g", 1), ASK_EXPIRY));

        assertEquals(STUDENT, request.getAccountId());
        assertEquals(DOMAIN, request.getDomainId());
        assertEquals(ResourceLimitRequestVO.State.Pending, request.getState());
        assertEquals(8L, request.getCpu());
        assertEquals(16384L, request.getMemoryMib());
        assertEquals(100L, request.getStorageGib());
        assertEquals(gpus("a100-40g", 1), request.getGpus());
        assertEquals("the expiry date ends at 23:59:59 Asia/Seoul", at("2026-11-30T14:59:59Z"), request.getRequestedExpiry());
        assertTrue(events.get(0), events.get(0).startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_CREATE + " " + STUDENT + " by 50"));
        assertTrue("a pending request writes no limit", writes.isEmpty());

        ResourceLimitRequestVO cancelled = service.cancelRequest(request.getId());

        assertEquals(ResourceLimitRequestVO.State.Cancelled, cancelled.getState());
        assertTrue(events.get(1), events.get(1).startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_CANCEL));
        assertThrows("a cancelled request cannot be cancelled again", InvalidParameterValueException.class, () -> service.cancelRequest(request.getId()));

        ResourceLimitRequestVO granted = grant(8L, null, null, null, ASK_EXPIRY);
        assertEquals(8L, limit(ResourceType.cpu, "cpu"));
        service.cancelRequest(granted.getId());
        assertEquals("a user gives up a grant, and the limits follow", 2L, limit(ResourceType.cpu, "cpu"));
        assertThrows("a request asks for something", InvalidParameterValueException.class, () -> service.createRequest(ask(0L, null, null, gpus("a6000", 0),
                ASK_EXPIRY)));
        assertThrows("a GPU type is one of the one-GPU offerings", InvalidParameterValueException.class,
                () -> service.createRequest(ask(null, null, null, gpus("h100", 1), ASK_EXPIRY)));
        assertThrows(InvalidParameterValueException.class, () -> service.createRequest(ask(-1L, null, null, null, ASK_EXPIRY)));
    }

    @Test
    public void userCannotSeeOthersRequests() {
        ResourceLimitRequestVO mine = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        as(CLASSMATE);
        ResourceLimitRequestVO theirs = service.createRequest(ask(2L, null, null, null, ASK_EXPIRY));

        as(STUDENT);
        assertEquals(Collections.singletonList(mine.getUuid()), listedIds(listing(true, null, null)));
        assertTrue("another account's request is not found by its id", listedIds(listing(false, null, theirs.getId())).isEmpty());
        assertThrows(PermissionDeniedException.class, () -> service.cancelRequest(theirs.getId()));
        CreateResourceLimitRequestCmd forClassmate = ask(4L, null, null, null, ASK_EXPIRY);
        when(forClassmate.getAccountName()).thenReturn("classmate");
        when(forClassmate.getDomainId()).thenReturn(DOMAIN);
        assertThrows("a user cannot ask for another account", PermissionDeniedException.class, () -> service.createRequest(forClassmate));

        as(MEMBER);
        assertEquals("an approver sees every request", Arrays.asList(mine.getUuid(), theirs.getUuid()), listedIds(listing(true, null, null)));
        assertEquals("the pending queue", 2, service.listRequests(listing(true, ResourceLimitRequestVO.State.Pending, null)).getCount().intValue());
        ResourceLimitRequestResponse response = service.listRequests(listing(true, null, mine.getId())).getResponses().get(0);
        assertEquals("student", response.getAccountName());
        assertEquals("students", response.getDomainName());
        assertEquals(Long.valueOf(4L), response.getCpu());
        assertEquals("Pending", response.getState());
        assertNull(response.getApprovedCpu());
        CreateResourceLimitRequestCmd forTeacher = ask(4L, null, null, null, ASK_EXPIRY);
        when(forTeacher.getAccountName()).thenReturn("teacher");
        when(forTeacher.getDomainId()).thenReturn(DOMAIN);
        assertEquals("an admin acts for an account", TEACHER, service.createRequest(forTeacher).getAccountId());
    }

    @Test
    public void normalUserCannotApprove() {
        for (Class<?> command : Arrays.asList(ApproveResourceLimitRequestCmd.class, RejectResourceLimitRequestCmd.class, RevokeResourceLimitRequestCmd.class)) {
            assertEquals(command.getSimpleName(), Collections.singletonList(RoleType.Admin), Arrays.asList(command.getAnnotation(APICommand.class).authorized()));
        }
        for (Class<?> command : Arrays.asList(CreateResourceLimitRequestCmd.class, ListResourceLimitRequestsCmd.class, CancelResourceLimitRequestCmd.class)) {
            assertTrue(command.getSimpleName(), Arrays.asList(command.getAnnotation(APICommand.class).authorized()).contains(RoleType.User));
        }
        assertEquals(ResourceLimitGrantService.APPROVE_API, ApproveResourceLimitRequestCmd.class.getAnnotation(APICommand.class).name());
        ResourceLimitRequestVO request = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));

        as(CLASSMATE);
        assertThrows("a user whose role were given the API still cannot decide for another account", PermissionDeniedException.class,
                () -> service.approveRequest(approval(request.getId())));
        as(STUDENT);
        assertThrows("no account decides its own request", PermissionDeniedException.class, () -> service.approveRequest(approval(request.getId())));
        assertThrows(PermissionDeniedException.class, () -> service.rejectRequest(request.getId(), "no"));
        assertEquals(ResourceLimitRequestVO.State.Pending, request.getState());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void requestFromAdminTypeAccountRefused() {
        as(MEMBER);
        assertThrows(InvalidParameterValueException.class, () -> service.createRequest(ask(4L, null, null, null, ASK_EXPIRY)));
        as(ADMIN);
        assertThrows(InvalidParameterValueException.class, () -> service.createRequest(ask(4L, null, null, null, ASK_EXPIRY)));
        CreateResourceLimitRequestCmd forProject = ask(4L, null, null, null, ASK_EXPIRY);
        when(forProject.getAccountName()).thenReturn("PrjAcct-course");
        assertThrows("project accounts follow the project defaults", InvalidParameterValueException.class, () -> service.createRequest(forProject));
        assertTrue(requests.isEmpty());

        service.recompute(accounts.get(ADMIN));
        service.recompute(accounts.get(MEMBER));
        service.recompute(accounts.get(PROJECT));
        assertTrue("the limits of admin-type and project accounts are never written", writes.isEmpty());
    }

    @Test
    public void approveMayAdjustValuesAndExpiry() {
        ResourceLimitRequestVO request = service.createRequest(ask(16L, 32768L, 200L, gpus("a100-40g", 2), ASK_EXPIRY));
        as(MEMBER);
        ApproveResourceLimitRequestCmd adjusted = approval(request.getId());
        when(adjusted.getCpu()).thenReturn(8L);
        when(adjusted.getGpu()).thenReturn(gpus("a100-40g", 1, "a6000", 1));
        when(adjusted.getExpiry()).thenReturn("2026-11-15");
        when(adjusted.getComment()).thenReturn("one A100 is free now");

        ResourceLimitRequestVO approved = service.approveRequest(adjusted);

        assertEquals(ResourceLimitRequestVO.State.Approved, approved.getState());
        assertEquals("the request keeps what was asked", 16L, approved.getCpu());
        assertEquals(gpus("a100-40g", 2), approved.getGpus());
        assertEquals(at("2026-11-30T14:59:59Z"), approved.getRequestedExpiry());
        assertEquals("the grant records what was approved", Long.valueOf(8L), approved.getApprovedCpu());
        assertEquals("unadjusted values are the asked ones", Long.valueOf(32768L), approved.getApprovedMemoryMib());
        assertEquals(Long.valueOf(200L), approved.getApprovedStorageGib());
        assertEquals(gpus("a100-40g", 1, "a6000", 1), approved.getApprovedGpus());
        assertEquals(at("2026-11-15T14:59:59Z"), approved.getApprovedExpiry());
        assertEquals(Long.valueOf(MEMBER * 10), approved.getApproverId());
        assertEquals(now, approved.getDecidedAt());
        assertEquals("one A100 is free now", approved.getComment());
        assertEquals(1L, limit(ResourceType.gpu, "a100-40g"));
        assertEquals(1L, limit(ResourceType.gpu, "a6000"));
        assertEquals(8L, limit(ResourceType.cpu, "cpu"));
        ResourceLimitRequestResponse response = service.createResponse(approved);
        assertEquals(Long.valueOf(16L), response.getCpu());
        assertEquals(Long.valueOf(8L), response.getApprovedCpu());
        assertEquals("member", response.getApprover());
        assertEquals(gpus("a100-40g", 1, "a6000", 1), response.getApprovedGpu());
        assertThrows("a decided request is not decided again", InvalidParameterValueException.class, () -> service.approveRequest(approval(request.getId())));
        assertTrue(events.stream().anyMatch(e -> e.startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_APPROVE + " " + STUDENT + " by 90")));

        as(STUDENT);
        ResourceLimitRequestVO second = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        as(MEMBER);
        assertEquals(ResourceLimitRequestVO.State.Rejected, service.rejectRequest(second.getId(), "use the lab cluster").getState());
        assertEquals("use the lab cluster", second.getComment());
        assertThrows("only a grant is revoked", InvalidParameterValueException.class, () -> service.revokeRequest(second.getId(), "no"));
        as(STUDENT);
        ApproveResourceLimitRequestCmd tooLong = approval(service.createRequest(ask(4L, null, null, null, ASK_EXPIRY)).getId());
        when(tooLong.getExpiry()).thenReturn("2026-10-31");
        as(MEMBER);
        assertThrows("an expiry in the past is refused", InvalidParameterValueException.class, () -> service.approveRequest(tooLong));
    }

    @Test
    public void effectiveQuotaIsMaxOfActiveGrants() {
        grant(4L, null, null, gpus("a100-40g", 2), ASK_EXPIRY);
        grant(8L, null, null, gpus("a6000", 1), ASK_EXPIRY);

        assertEquals("the larger grant, not the sum", 8L, limit(ResourceType.cpu, "cpu"));
        assertEquals("GPU types are independent", 2L, limit(ResourceType.gpu, "a100-40g"));
        assertEquals(1L, limit(ResourceType.gpu, "a6000"));
        assertEquals(0L, limit(ResourceType.gpu, "a100-80g"));
        assertEquals(0L, limit(ResourceType.gpu, "cpu"));
    }

    @Test
    public void expiredGrantIgnored() {
        grant(16L, null, null, gpus("gtx1080", 2), "2026-11-02");
        assertEquals(16L, limit(ResourceType.cpu, "cpu"));

        now = at("2026-11-02T14:59:59Z");
        service.recompute(accounts.get(STUDENT));
        assertEquals("a grant lasts through 23:59:59 of its last day", 16L, limit(ResourceType.cpu, "cpu"));

        now = at("2026-11-02T15:00:00Z");
        service.recompute(accounts.get(STUDENT));
        assertEquals("an expired grant counts no more, even before the expiry check marks it", 2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(0L, limit(ResourceType.gpu, "gtx1080"));
    }

    @Test
    public void revokedGrantIgnored() {
        ResourceLimitRequestVO grant = grant(16L, null, null, gpus("a100-80g", 1), ASK_EXPIRY);
        as(MEMBER);

        ResourceLimitRequestVO revoked = service.revokeRequest(grant.getId(), "the course ended");

        assertEquals(ResourceLimitRequestVO.State.Revoked, revoked.getState());
        assertEquals("the course ended", revoked.getComment());
        assertEquals(2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(0L, limit(ResourceType.gpu, "a100-80g"));
        assertTrue(events.stream().anyMatch(e -> e.startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_REVOKE + " " + STUDENT)));
    }

    @Test
    public void defaultsActAsPermanentGrant() {
        service.recompute(accounts.get(STUDENT));

        assertEquals(2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(4096L, limit(ResourceType.memory, "cpu"));
        assertEquals(20 * GIB, limit(ResourceType.primary_storage, null));
        assertEquals(0L, limit(ResourceType.gpu, null));

        grant(1L, 8192L, 10L, null, ASK_EXPIRY);
        assertEquals("a grant below a default does not lower it", 2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(8192L, limit(ResourceType.memory, "cpu"));
        assertEquals(20 * GIB, limit(ResourceType.primary_storage, null));

        now = at("2026-12-01T00:00:00Z");
        service.recompute(accounts.get(STUDENT));
        assertEquals("with every grant expired the defaults remain", 4096L, limit(ResourceType.memory, "cpu"));

        settings.put("max.account.cpus", "-1");
        service.recompute(accounts.get(STUDENT));
        assertEquals("an unlimited default stays unlimited", Resource.RESOURCE_UNLIMITED, limit(ResourceType.cpu, "cpu"));
        assertEquals(Resource.RESOURCE_UNLIMITED, limit(ResourceType.cpu, null));
        assertEquals(Resource.RESOURCE_UNLIMITED, limit(ResourceType.user_vm, null));
    }

    @Test
    public void recomputeWritesEveryTagAndType() {
        service.recompute(accounts.get(STUDENT));

        assertEquals(28, writes.size());
        List<String> untagged = writes.subList(0, 8).stream().map(w -> w.substring(0, w.indexOf('='))).collect(Collectors.toList());
        assertEquals("untagged first", Arrays.asList("5:cpu", "5:memory", "5:gpu", "5:user_vm", "5:primary_storage", "5:volume", "5:secondary_storage",
                "5:snapshot"), untagged);
        for (int i = 8; i < 28; i++) {
            assertTrue(writes.get(i), writes.get(i).contains("/"));
        }
        for (String tag : TAGS) {
            for (ResourceType type : ResourceLimitService.HostTagsSupportingTypes) {
                assertTrue(type + "/" + tag, limits.containsKey(key(STUDENT, type, tag)));
            }
        }
    }

    @Test
    public void recomputeUntaggedIsSum() {
        grant(8L, 16384L, null, gpus("a100-40g", 2, "a6000", 1), ASK_EXPIRY);

        assertEquals("cpu_q + sum of g_t x unit_vcpu", 8L + 2 * 12 + 12, limit(ResourceType.cpu, null));
        assertEquals("mem_q + sum of g_t x unit_ram", 16384L + 2 * 98304 + 24576, limit(ResourceType.memory, null));
        assertEquals(3L, limit(ResourceType.gpu, null));
        assertEquals("cpu_q + sum of g_t", 8L + 3, limit(ResourceType.user_vm, null));
        assertEquals(24L, limit(ResourceType.cpu, "a100-40g"));
        assertEquals(2 * 98304L, limit(ResourceType.memory, "a100-40g"));
        assertEquals(2L, limit(ResourceType.user_vm, "a100-40g"));
        assertEquals(12L, limit(ResourceType.cpu, "a6000"));
        assertEquals(24576L, limit(ResourceType.memory, "a6000"));
    }

    @Test
    public void recomputeDerivesCountsAndCombinedStorage() {
        grant(6L, null, 150L, null, ASK_EXPIRY);

        assertEquals("user_vm tag cpu = cpu_q", 6L, limit(ResourceType.user_vm, "cpu"));
        assertEquals(6L, limit(ResourceType.cpu, "cpu"));
        assertEquals("primary_storage = the storage quota", 150 * GIB, limit(ResourceType.primary_storage, null));
        assertEquals("volume = storage_q", 150L, limit(ResourceType.volume, null));
        assertEquals("secondary_storage is not consulted while storage is one quota", Resource.RESOURCE_UNLIMITED, limit(ResourceType.secondary_storage, null));
        assertEquals("snapshot from max.account.snapshots, -1 as shipped", Resource.RESOURCE_UNLIMITED, limit(ResourceType.snapshot, null));
        assertEquals(0L, limit(ResourceType.gpu, "cpu"));
        assertEquals(0L, limit(ResourceType.user_vm, "a100-80g"));
    }

    @Test
    public void recomputeIsIdempotent() {
        grant(8L, null, null, gpus("a6000", 1), ASK_EXPIRY);
        writes.clear();

        service.recompute(accounts.get(STUDENT));
        service.recompute(accounts.get(STUDENT));

        assertTrue("a repeated run changes nothing: " + writes, writes.isEmpty());
        settings.put("max.account.memory", "8192");
        service.recompute(accounts.get(STUDENT));
        assertEquals("only what changed is written", Arrays.asList("5:memory=" + (8192 + 24576), "5:memory/cpu=8192"), writes);
    }

    @Test
    public void recomputeRunsInOneTransaction() {
        service.recompute(accounts.get(STUDENT));
        Map<String, Long> before = new HashMap<>(limits);
        writes.clear();
        transactions = 0;
        settings.put("max.account.cpus", "6");
        settings.put("max.account.memory", "8192");
        failAtWrite = 1;

        assertThrows(CloudRuntimeException.class, () -> service.recompute(accounts.get(STUDENT)));

        assertEquals("one transaction for the account", 1, transactions);
        assertEquals("a failure leaves no partial limits", before, limits);
        failAtWrite = null;
        service.recompute(accounts.get(STUDENT));
        assertEquals(6L, limit(ResourceType.cpu, "cpu"));
    }

    @Test
    public void memoryInMbStorageInBytes() {
        grant(null, 16384L, 100L, null, ASK_EXPIRY);

        assertTrue("memory is written in MiB: " + writes, writes.contains("5:memory/cpu=16384"));
        assertTrue("storage is given in GiB, which the limit stores in bytes: " + writes, writes.contains("5:primary_storage=100"));
        assertEquals(100 * GIB, limit(ResourceType.primary_storage, null));
        writes.clear();
        service.recompute(accounts.get(STUDENT));
        assertTrue("a limit stored in bytes equals the same GiB: " + writes, writes.isEmpty());
    }

    @Test
    public void recomputeReadsLiveDefaults() {
        service.recompute(accounts.get(STUDENT));
        assertEquals(2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(Resource.RESOURCE_UNLIMITED, limit(ResourceType.snapshot, null));

        settings.put("max.account.cpus", "4");
        settings.put("max.account.snapshots", "50");
        service.recompute(accounts.get(STUDENT));
        assertEquals(4L, limit(ResourceType.cpu, "cpu"));
        assertEquals(50L, limit(ResourceType.snapshot, null));

        settings.put("resource.limit.storage.combined", "false");
        service.recompute(accounts.get(STUDENT));
        assertEquals(400 * GIB, limit(ResourceType.secondary_storage, null));
        settings.put("max.account.secondary.storage", "500");
        service.recompute(accounts.get(STUDENT));
        assertEquals(500 * GIB, limit(ResourceType.secondary_storage, null));

        settings.put("max.account.cpus", "four");
        assertThrows("a default that is not a number grants nothing else", CloudRuntimeException.class, () -> service.recompute(accounts.get(STUDENT)));
    }

    @Test
    public void gpuTypesAndUnitsComeFromTheOneGpuOfferings() {
        gpuOffering(40L, "a6000", 16, 32768);
        ServiceOfferingVO classTagged = mock(ServiceOfferingVO.class);
        when(classTagged.getId()).thenReturn(41L);
        when(classTagged.getHostTag()).thenReturn("a100-40g,a100-40g-nvswitch");
        when(classTagged.getVgpuProfileId()).thenReturn(141L);
        when(classTagged.getGpuCount()).thenReturn(1);
        oneGpuOfferings.add(classTagged);

        Map<String, ResourceLimitRequestServiceImpl.GpuUnit> units = service.gpuUnits();

        assertEquals(Arrays.asList("a100-40g", "a100-80g", "a6000", "gtx1080"), new ArrayList<>(units.keySet()));
        assertEquals("the newest offering of a type sizes it", 16L, units.get("a6000").vcpus);
        assertEquals(32768L, units.get("a6000").memoryMib);
        assertEquals(12L, units.get("a100-40g").vcpus);
    }

    @Test
    public void hourlyTaskExpiresGrantAndRecomputes() {
        ResourceLimitRequestVO grant = grant(16L, null, null, gpus("a100-40g", 1), "2026-11-02");
        events.clear();
        writes.clear();

        now = at("2026-11-03T00:00:00Z");
        service.sweep(now);

        assertEquals(ResourceLimitRequestVO.State.Expired, grant.getState());
        assertEquals(2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(0L, limit(ResourceType.gpu, "a100-40g"));
        assertEquals(1, events.stream().filter(e -> e.startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_EXPIRE + " " + STUDENT + " by 1")).count());
        assertTrue("the other accounts' limits are written too", writes.stream().anyMatch(w -> w.startsWith(CLASSMATE + ":")));
        assertTrue("admin-type, project and system accounts are left alone",
                writes.stream().noneMatch(w -> w.startsWith(ADMIN + ":") || w.startsWith(MEMBER + ":") || w.startsWith(PROJECT + ":") || w.startsWith(SYSTEM + ":")));
        events.clear();
        service.sweep(now);
        assertTrue("an expiry happens once", events.isEmpty());
    }

    @Test
    public void runningVmUntouchedAtExpiry() throws Exception {
        ResourceLimitRequestVO grant = grant(16L, null, null, gpus("a100-40g", 1), "2026-11-02");

        service.sweep(at("2026-11-03T00:00:00Z"));

        assertEquals(ResourceLimitRequestVO.State.Expired, grant.getState());
        for (Field field : ResourceLimitRequestServiceImpl.class.getDeclaredFields()) {
            String type = field.getType().getSimpleName();
            assertFalse("the expiry only lowers limits; it has no way to stop an Instance: " + field,
                    type.contains("VirtualMachine") || type.contains("UserVm") || type.contains("VmService"));
        }
        verify(resourceLimitService, never()).checkWithinQuota(any(), any(), any());
    }

    private List<String> warnings() {
        return events.stream().filter(e -> e.startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_EXPIRING + " ")).collect(Collectors.toList());
    }

    @Test
    public void expiryWarningsAtSevenAndOneDays() {
        grant(8L, null, null, null, "2026-11-20");
        events.clear();

        service.sweep(at("2026-11-13T14:00:00Z"));
        assertTrue("more than 7 days left: " + events, events.isEmpty());

        service.sweep(at("2026-11-13T15:00:00Z"));
        service.sweep(at("2026-11-14T15:00:00Z"));
        assertEquals(1, events.size());
        assertTrue(events.get(0), events.get(0).startsWith(EventTypes.EVENT_RESOURCE_LIMIT_REQUEST_EXPIRING + " " + STUDENT + " by 1"));
        assertTrue(events.get(0), events.get(0).contains("within 7 day(s)"));

        service.sweep(at("2026-11-19T15:00:00Z"));
        service.sweep(at("2026-11-20T10:00:00Z"));
        assertEquals(2, events.size());
        assertTrue(events.get(1), events.get(1).contains("within 1 day(s)"));
        assertEquals(Integer.valueOf(1), requests.values().iterator().next().getExpiryWarningDays());
    }

    @Test
    public void warningDaysAreASetting() {
        settings.put("resource.request.expiry.warning.days", "14,3,1");
        grant(8L, null, null, null, "2026-11-30");
        events.clear();

        service.sweep(at("2026-11-16T15:00:00Z"));
        service.sweep(at("2026-11-27T15:00:00Z"));
        service.sweep(at("2026-11-29T15:00:00Z"));

        assertEquals(3, warnings().size());
        assertTrue(warnings().get(0).contains("within 14 day(s)"));
        assertTrue(warnings().get(1).contains("within 3 day(s)"));
        assertTrue(warnings().get(2).contains("within 1 day(s)"));

        grant(4L, null, null, null, "2026-12-01");
        events.clear();
        service.sweep(at("2026-11-30T16:00:00Z"));
        assertEquals("a grant found late gets only the narrowest warning it is in: " + events, 1, warnings().size());
        assertTrue(warnings().get(0).contains("within 1 day(s)"));

        settings.put("resource.request.expiry.warning.days", "");
        grant(4L, null, null, null, "2026-12-01");
        events.clear();
        service.sweep(at("2026-11-30T20:00:00Z"));
        assertTrue("no warning days, no warnings", warnings().isEmpty());
        assertThrows(InvalidParameterValueException.class, () -> ResourceLimitGrantService.validateWarningDays("7,0"));
        assertThrows(InvalidParameterValueException.class, () -> ResourceLimitGrantService.validateWarningDays("seven"));
        ResourceLimitGrantService.validateWarningDays("14, 3,1");
    }

    @Test
    public void expiryNotRequiredAllowsGrantWithoutEndDate() {
        assertThrows("an expiry is required as shipped", InvalidParameterValueException.class, () -> service.createRequest(ask(8L, null, null, null, null)));

        settings.put("resource.request.expiry.required", "false");
        ResourceLimitRequestVO grant = grant(8L, null, null, null, null);

        assertNull(grant.getRequestedExpiry());
        assertNull(grant.getApprovedExpiry());
        now = at("2036-01-01T00:00:00Z");
        service.sweep(now);
        assertEquals("a grant without an end never expires", ResourceLimitRequestVO.State.Approved, grant.getState());
        assertEquals(8L, limit(ResourceType.cpu, "cpu"));
    }

    @Test
    public void defaultAndMaxExpiryDaysApply() {
        settings.put("resource.request.expiry.max.days", "30");

        assertThrows("the latest expiry is 30 days from today", InvalidParameterValueException.class,
                () -> service.createRequest(ask(8L, null, null, null, "2026-12-02")));
        ResourceLimitRequestVO request = service.createRequest(ask(8L, null, null, null, "2026-12-01"));

        as(MEMBER);
        ApproveResourceLimitRequestCmd longer = approval(request.getId());
        when(longer.getExpiry()).thenReturn("2026-12-15");
        InvalidParameterValueException capped = assertThrows(InvalidParameterValueException.class, () -> service.approveRequest(longer));
        assertTrue(capped.getMessage(), capped.getMessage().contains("2026-12-01"));
        settings.put("resource.request.expiry.max.days", "10");
        assertThrows("the cap applies to the requested expiry when the approver keeps it", InvalidParameterValueException.class,
                () -> service.approveRequest(approval(request.getId())));
        settings.put("resource.request.expiry.max.days", "0");
        assertEquals(at("2026-12-01T14:59:59Z"), service.approveRequest(approval(request.getId())).getApprovedExpiry());

        settings.put("resource.request.expiry.required", "false");
        settings.put("resource.request.expiry.max.days", "30");
        as(STUDENT);
        assertThrows("a bounded expiry needs an end date", InvalidParameterValueException.class, () -> service.createRequest(ask(8L, null, null, null, null)));
        assertEquals("the form's proposed expiry is a setting the UI reads", "resource.request.expiry.default.days",
                ResourceLimitGrantService.ExpiryDefaultDays.key());
        assertEquals(Integer.valueOf(0), ResourceLimitGrantService.ExpiryDefaultDays.value());
    }

    @Test
    public void expiryTimezoneIsASetting() {
        settings.put("resource.request.expiry.timezone", "UTC");

        ResourceLimitRequestVO request = service.createRequest(ask(8L, null, null, null, "2026-12-31"));

        assertEquals(at("2026-12-31T23:59:59Z"), request.getRequestedExpiry());
        service.cancelRequest(request.getId());
        settings.put("resource.request.expiry.timezone", "Asia/Seoul");
        assertEquals(at("2026-12-31T14:59:59Z"), service.createRequest(ask(8L, null, null, null, "2026-12-31")).getRequestedExpiry());
        assertThrows(InvalidParameterValueException.class, () -> ResourceLimitGrantService.validateTimezone("Mars/Olympus"));
        ResourceLimitGrantService.validateTimezone("UTC");
    }

    @Test
    public void checkIntervalIsASetting() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        Mockito.doNothing().when(service).runSweep();
        ConfigKeyScheduledExecutionWrapper runner = service.sweepRunner(executor);

        settings.put("resource.request.expiry.check.interval", "0");
        runner.run();
        verify(executor).schedule(runner, 60, TimeUnit.SECONDS);
        verify(service, never()).runSweep();

        settings.put("resource.request.expiry.check.interval", "120");
        runner.run();
        verify(service, times(1)).runSweep();
        verify(executor).schedule(eq(runner), Mockito.longThat(delay -> delay > TimeUnit.SECONDS.toNanos(119)), eq(TimeUnit.NANOSECONDS));
        assertEquals("resource.request.expiry.check.interval", ResourceLimitGrantService.ExpiryCheckInterval.key());
        assertEquals("3600", ResourceLimitGrantService.ExpiryCheckInterval.defaultValue());
    }

    @Test
    public void sweepRunsOnOneManagementServerAtATime() {
        GlobalLock held = mock(GlobalLock.class);
        when(held.lock(0)).thenReturn(false);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(held);

            service.runSweep();

            verify(service, never()).sweep(any(Date.class));
            verify(held).releaseRef();
        }
        GlobalLock free = mock(GlobalLock.class);
        when(free.lock(0)).thenReturn(true);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(free);

            service.runSweep();

            verify(service).sweep(now);
            verify(free).unlock();
        }
    }

    @Test
    public void settingsAreDynamicGlobalKeysWithTheDecidedDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (ConfigKey<?> key : service.getConfigKeys()) {
            assertTrue(key.key(), key.isDynamic());
            assertEquals(key.key(), ConfigKey.Scope.Global, key.getScopes().get(0));
            defaults.put(key.key(), key.defaultValue());
        }
        assertEquals("true", defaults.get("resource.request.expiry.required"));
        assertEquals("0", defaults.get("resource.request.expiry.default.days"));
        assertEquals("0", defaults.get("resource.request.expiry.max.days"));
        assertEquals("Asia/Seoul", defaults.get("resource.request.expiry.timezone"));
        assertEquals("7,1", defaults.get("resource.request.expiry.warning.days"));
        assertEquals("3600", defaults.get("resource.request.expiry.check.interval"));
        assertEquals("", defaults.get("resource.request.smtp.host"));
        assertEquals("Secure", ResourceLimitGrantService.SmtpPassword.category());
        assertTrue(defaults.keySet().containsAll(Arrays.asList("resource.request.smtp.port", "resource.request.smtp.useAuth", "resource.request.smtp.username",
                "resource.request.smtp.password", "resource.request.smtp.useStartTLS", "resource.request.smtp.enabledSecurityProtocols",
                "resource.request.smtp.sender")));
    }

    @Test
    public void everyColumnOfARequestIsInTheSchemaUpgrade() throws Exception {
        String script;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/db/schema-42300to4230100.sql")) {
            assertNotNull(in);
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = script.indexOf("CREATE TABLE IF NOT EXISTS `cloud`.`resource_limit_request` (");
        assertTrue("the table is created only where it is missing", start >= 0);
        String table = script.substring(start, script.indexOf(") ENGINE=InnoDB", start));
        List<String> columns = new ArrayList<>();
        for (Field field : ResourceLimitRequestVO.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name());
                assertTrue(column.name(), table.contains("`" + column.name() + "` "));
            }
        }
        assertEquals(22, columns.size());
    }

    @Test
    public void accountCreationWritesTheLimitsFromTheDefaults() {
        started();
        ArgumentCaptor<MessageSubscriber> added = ArgumentCaptor.forClass(MessageSubscriber.class);
        verify(messageBus).subscribe(eq(AccountManager.MESSAGE_ADD_ACCOUNT_EVENT), added.capture());
        Map<Long, Long> accountGroup = new HashMap<>();
        accountGroup.put(STUDENT, 1L);

        added.getValue().onPublishMessage("AccountManager", AccountManager.MESSAGE_ADD_ACCOUNT_EVENT, accountGroup);

        assertEquals("every limit of a new account is written from the defaults", 28, writes.size());
        assertEquals(2L, limit(ResourceType.cpu, "cpu"));
        assertEquals(0L, limit(ResourceType.gpu, "a100-40g"));
        failAtWrite = writes.size();
        limits.clear();
        added.getValue().onPublishMessage("AccountManager", AccountManager.MESSAGE_ADD_ACCOUNT_EVENT, accountGroup);
        assertTrue("a failed write does not fail the account creation; the next expiry check writes them", limits.isEmpty());
        service.stop();
    }

    @Test
    public void removedAccountsOpenRequestsAreCancelled() {
        ResourceLimitRequestVO granted = grant(8L, null, null, null, ASK_EXPIRY);
        ResourceLimitRequestVO pending = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        started();
        ArgumentCaptor<MessageSubscriber> removed = ArgumentCaptor.forClass(MessageSubscriber.class);
        verify(messageBus).subscribe(eq(AccountManager.MESSAGE_REMOVE_ACCOUNT_EVENT), removed.capture());

        removed.getValue().onPublishMessage("AccountManager", AccountManager.MESSAGE_REMOVE_ACCOUNT_EVENT, STUDENT);

        assertEquals(ResourceLimitRequestVO.State.Cancelled, pending.getState());
        assertEquals(ResourceLimitRequestVO.State.Cancelled, granted.getState());
        service.stop();
    }

    @Test
    public void aSecondPendingRequestIsRefused() {
        ResourceLimitRequestVO first = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));

        InvalidParameterValueException second = assertThrows(InvalidParameterValueException.class,
                () -> service.createRequest(ask(8L, null, null, gpus("a6000", 1), ASK_EXPIRY)));

        assertTrue(second.getMessage(), second.getMessage().contains(first.getUuid()));
        assertEquals(1, requests.size());
        as(MEMBER);
        service.approveRequest(approval(first.getId()));
        as(STUDENT);
        assertEquals("a renewal is a new request beside the grant", ResourceLimitRequestVO.State.Pending,
                service.createRequest(ask(8L, null, null, null, ASK_EXPIRY)).getState());
        as(CLASSMATE);
        assertEquals("the rule is per account", ResourceLimitRequestVO.State.Pending, service.createRequest(ask(2L, null, null, null, ASK_EXPIRY)).getState());
    }

    @Test
    public void onlyTheOwnerGivesUpAGrant() {
        ResourceLimitRequestVO grant = grant(8L, null, null, null, ASK_EXPIRY);

        as(MEMBER);
        assertThrows("another account ends a grant through revoke", PermissionDeniedException.class, () -> service.cancelRequest(grant.getId()));
        as(TEACHER);
        assertThrows(PermissionDeniedException.class, () -> service.cancelRequest(grant.getId()));
        assertEquals(ResourceLimitRequestVO.State.Approved, grant.getState());
        assertEquals(8L, limit(ResourceType.cpu, "cpu"));

        as(STUDENT);
        assertEquals(ResourceLimitRequestVO.State.Cancelled, service.cancelRequest(grant.getId()).getState());
        assertEquals(2L, limit(ResourceType.cpu, "cpu"));

        ResourceLimitRequestVO pending = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        as(MEMBER);
        assertEquals("a pending request is withdrawn by whoever acts for the account", ResourceLimitRequestVO.State.Cancelled,
                service.cancelRequest(pending.getId()).getState());
    }

    @Test
    public void aDecisionForAnAccountWhoseLimitsNoLongerFollowGrants() {
        ResourceLimitRequestVO grant = grant(8L, null, null, null, ASK_EXPIRY);
        ResourceLimitRequestVO pending = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        when(accounts.get(STUDENT).getType()).thenReturn(Account.Type.ADMIN);
        writes.clear();
        as(MEMBER);

        InvalidParameterValueException refused = assertThrows(InvalidParameterValueException.class, () -> service.approveRequest(approval(pending.getId())));

        assertTrue(refused.getMessage(), refused.getMessage().contains("not set by grants"));
        assertEquals(ResourceLimitRequestVO.State.Rejected, service.rejectRequest(pending.getId(), "the account is an admin now").getState());
        assertEquals(ResourceLimitRequestVO.State.Revoked, service.revokeRequest(grant.getId(), "the account is an admin now").getState());
        assertTrue("an account whose limits are unlimited is never written: " + writes, writes.isEmpty());
    }

    @Test
    public void aResourceAdminListsOnlyItsDomain() {
        service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
        as(OUTSIDER);
        ResourceLimitRequestVO outsiders = service.createRequest(ask(2L, null, null, null, ASK_EXPIRY));

        as(RESOURCE_ADMIN);
        assertEquals(Collections.singletonList(outsiders.getUuid()), listedIds(listing(true, null, null)));
        as(MEMBER);
        assertEquals("a root admin still sees every request", 2, listedIds(listing(true, null, null)).size());
    }

    @Test
    public void oneSweepRunsShortlyAfterStart() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ReflectionTestUtils.setField(service, "sweepExecutor", executor);
        ConfigKeyScheduledExecutionWrapper periodic = mock(ConfigKeyScheduledExecutionWrapper.class);
        doReturn(periodic).when(service).sweepRunner(executor);

        service.start();

        verify(periodic).start();
        verify(executor).schedule(any(Runnable.class), eq(60L), eq(TimeUnit.SECONDS));
        settings.put("resource.request.expiry.check.interval", "0");
        Mockito.clearInvocations(executor);
        service.start();
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void theReportedGpuTypesAreKeptInTheDatabase() {
        grant(4L, null, null, gpus("a100-40g", 1), ASK_EXPIRY);
        oneGpuOfferings.removeIf(o -> "a100-40g".equals(o.getHostTag()));
        // another management server, or this one before a restart, reported the same set
        settings.put(ResourceLimitRequestServiceImpl.REPORTED_UNSIZED_GPU_TYPES, "a100-40g");

        service.sweep(now);

        verify(alertManager, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        when(resourceLimitService.getResourceLimitHostTags()).thenAnswer(i -> new ArrayList<>(Arrays.asList("cpu", "a6000", "a100-80g", "gtx1080", "h100")));
        service.sweep(now);
        verify(alertManager, times(1)).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        assertEquals("the set is stored where every management server reads it", "a100-40g,h100",
                settings.get(ResourceLimitRequestServiceImpl.REPORTED_UNSIZED_GPU_TYPES));
    }

    @Test
    public void aGpuTypeWithoutItsOneGpuOfferingIsReported() {
        grant(4L, null, null, gpus("a100-40g", 1), ASK_EXPIRY);
        List<ServiceOfferingVO> a100 = oneGpuOfferings.stream().filter(o -> "a100-40g".equals(o.getHostTag())).collect(Collectors.toList());
        oneGpuOfferings.removeAll(a100);
        when(resourceLimitService.getResourceLimitHostTags()).thenAnswer(i -> new ArrayList<>(Arrays.asList("cpu", "a6000", "a100-80g", "gtx1080", "h100")));

        service.sweep(now);
        service.sweep(now);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(alertManager, times(1)).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_RESOURCE_LIMIT_EXCEEDED), eq(0L), isNull(), subject.capture(), anyString());
        assertTrue(subject.getValue(), subject.getValue().contains("a100-40g") && subject.getValue().contains("h100"));
        assertFalse("a tag of an offering without a GPU is not a GPU type", subject.getValue().contains("cpu"));
        assertEquals("the grant of a type nothing sizes counts nothing", 0L, limit(ResourceType.gpu, null));
        assertEquals("a100-40g,h100", settings.get(ResourceLimitRequestServiceImpl.REPORTED_UNSIZED_GPU_TYPES));

        oneGpuOfferings.addAll(a100);
        when(resourceLimitService.getResourceLimitHostTags()).thenAnswer(i -> new ArrayList<>(TAGS));
        service.sweep(now);
        verify(alertManager, times(1)).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        assertEquals("a recovery clears the stored set", "", settings.get(ResourceLimitRequestServiceImpl.REPORTED_UNSIZED_GPU_TYPES));
        oneGpuOfferings.removeAll(a100);
        service.sweep(now);
        verify(alertManager, times(2)).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_RESOURCE_LIMIT_EXCEEDED), eq(0L), isNull(), subject.capture(), anyString());
        assertEquals("GPU types without a one-GPU offering: a100-40g", subject.getValue());
    }

    @Test
    public void smtpRelaySettingsAreAccessControlSettings() {
        Set<String> gated = Set.of(service.getAccessControlConfigKeyNames());

        assertEquals(Set.of("resource.request.smtp.host", "resource.request.smtp.port", "resource.request.smtp.useAuth", "resource.request.smtp.useStartTLS",
                "resource.request.smtp.enabledSecurityProtocols", "resource.request.smtp.username", "resource.request.smtp.password"), gated);
        Set<String> registered = Arrays.stream(service.getConfigKeys()).map(ConfigKey::key).collect(Collectors.toSet());
        assertTrue(registered.containsAll(gated));
        assertFalse(gated.contains("resource.request.smtp.sender"));
        assertFalse(gated.contains("resource.request.expiry.warning.days"));
    }

    @Test
    public void approversAreDecidedByTheRoleCheckersOnly() throws Exception {
        // AccountManager.checkApiAccess runs the checkers it is given, which are the API ACL checkers only, so a request
        // limiter never decides who approves
        assertEquals("#{apiAclCheckersRegistry.registered}", beanProperty("META-INF/cloudstack/core/spring-server-core-managers-context.xml",
                "accountManagerImpl", "apiAccessCheckers"));
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream("META-INF/cloudstack/api/spring-core-lifecycle-api-context-inheritable.xml")) {
            assertNotNull(xml);
            NodeList beans = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("bean");
            boolean found = false;
            for (int i = 0; i < beans.getLength(); i++) {
                Map<String, String> properties = properties((Element)beans.item(i));
                if ("apiAclCheckersRegistry".equals(properties.get("registry"))) {
                    assertEquals("org.apache.cloudstack.acl.APIAclChecker", properties.get("typeClass"));
                    found = true;
                }
            }
            assertTrue(found);
        }
    }

    private static Map<String, String> properties(Element bean) {
        Map<String, String> properties = new HashMap<>();
        NodeList nodes = bean.getElementsByTagName("property");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element property = (Element)nodes.item(i);
            properties.put(property.getAttribute("name"), property.hasAttribute("ref") ? property.getAttribute("ref") : property.getAttribute("value"));
        }
        return properties;
    }

    private String beanProperty(String resource, String beanId, String name) throws Exception {
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, xml);
            NodeList beans = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("bean");
            for (int i = 0; i < beans.getLength(); i++) {
                Element bean = (Element)beans.item(i);
                if (beanId.equals(bean.getAttribute("id"))) {
                    return properties(bean).get(name);
                }
            }
        }
        return null;
    }

    @Test
    public void notificationFailuresKeepTheCommittedChange() throws Exception {
        try (FakeSmtpServer server = relay()) {
            Mockito.doNothing().when(accountManager).checkApiAccess(any(Account.class), anyString());
            when(userDao.listByAccount(anyLong())).thenThrow(new CloudRuntimeException("database connection lost"));

            ResourceLimitRequestVO request = service.createRequest(ask(8L, null, null, null, ASK_EXPIRY));
            assertEquals(ResourceLimitRequestVO.State.Pending, request.getState());
            as(MEMBER);
            assertEquals(ResourceLimitRequestVO.State.Approved, service.approveRequest(approval(request.getId())).getState());
            assertEquals(ResourceLimitRequestVO.State.Revoked, service.revokeRequest(request.getId(), "the term ended").getState());
            as(STUDENT);
            ResourceLimitRequestVO second = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
            as(MEMBER);
            doReturn((Executor)task -> {
                throw new RejectedExecutionException("shut down");
            }).when(service).mailExecutor();
            UserVO reachable = mock(UserVO.class);
            when(reachable.getState()).thenReturn(Account.State.ENABLED);
            when(reachable.getEmail()).thenReturn("student@example.org");
            doAnswer(i -> Collections.singletonList(reachable)).when(userDao).listByAccount(anyLong());
            assertEquals(ResourceLimitRequestVO.State.Rejected, service.rejectRequest(second.getId(), "no").getState());
            assertEquals(0, server.getMessages().size());

            started();
            ArgumentCaptor<MessageSubscriber> removed = ArgumentCaptor.forClass(MessageSubscriber.class);
            verify(messageBus).subscribe(eq(AccountManager.MESSAGE_REMOVE_ACCOUNT_EVENT), removed.capture());
            when(requestDao.listByAccountAndStates(eq(STUDENT), any(ResourceLimitRequestVO.State[].class))).thenThrow(new CloudRuntimeException("db"));
            removed.getValue().onPublishMessage("AccountManager", AccountManager.MESSAGE_REMOVE_ACCOUNT_EVENT, STUDENT);
        }
    }

    @Test
    public void newRequestMailsApprovers() throws Exception {
        try (FakeSmtpServer server = relay()) {
            doThrow(new PermissionDeniedException("not allowed")).when(accountManager).checkApiAccess(any(Account.class), eq(ResourceLimitGrantService.APPROVE_API));
            Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(MEMBER), ResourceLimitGrantService.APPROVE_API);
            Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(ADMIN), ResourceLimitGrantService.APPROVE_API);

            ResourceLimitRequestVO request = service.createRequest(ask(8L, null, null, gpus("a100-40g", 1), ASK_EXPIRY));

            assertEquals(1, server.getMessages().size());
            FakeSmtpServer.Message mail = server.getMessages().get(0);
            assertEquals(Arrays.asList("admin@example.org", "member@example.org"), mail.recipients);
            assertTrue(mail.sender, mail.sender.contains("cloud@example.org"));
            assertTrue(mail.data, mail.data.contains(request.getUuid()));
            assertTrue(mail.data, mail.data.contains("student"));
            assertTrue(mail.data, mail.data.contains("thesis experiments"));
        }
    }

    @Test
    public void approversFollowTheRoleRules() throws Exception {
        // the API checkers and the access to the requesting account decide, whatever the account type: an admin-type account
        // whose role may not approve is not an approver, and a domain admin whose role may approve is
        doThrow(new PermissionDeniedException("the role denies it")).when(accountManager).checkApiAccess(any(Account.class),
                eq(ResourceLimitGrantService.APPROVE_API));
        Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(TEACHER), ResourceLimitGrantService.APPROVE_API);

        List<Long> approvers = service.approversOf(accounts.get(STUDENT)).stream().map(Account::getId).collect(Collectors.toList());
        assertEquals(Collections.singletonList(TEACHER), approvers);

        Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(MEMBER), ResourceLimitGrantService.APPROVE_API);
        Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(STUDENT), ResourceLimitGrantService.APPROVE_API);
        Mockito.doNothing().when(accountManager).checkApiAccess(accounts.get(CLASSMATE), ResourceLimitGrantService.APPROVE_API);
        approvers = service.approversOf(accounts.get(STUDENT)).stream().map(Account::getId).collect(Collectors.toList());
        assertEquals("the requester is never its own approver, and an approver reaches the requesting account", Arrays.asList(TEACHER, MEMBER), approvers);
        verify(accountManager, never()).checkApiAccess(accounts.get(SYSTEM), ResourceLimitGrantService.APPROVE_API);

        when(accounts.get(MEMBER).getState()).thenReturn(Account.State.DISABLED);
        doThrow(new PermissionDeniedException("other domain")).when(accountManager).checkAccess(eq(accounts.get(TEACHER)), isNull(), eq(true),
                any(ControlledEntity[].class));
        assertTrue("an approver must reach the requesting account and be enabled", service.approversOf(accounts.get(STUDENT)).isEmpty());
    }

    @Test
    public void decisionMailsRequester() throws Exception {
        try (FakeSmtpServer server = relay()) {
            doThrow(new PermissionDeniedException("not allowed")).when(accountManager).checkApiAccess(any(Account.class), anyString());
            ResourceLimitRequestVO approved = service.createRequest(ask(8L, null, null, null, ASK_EXPIRY));
            as(MEMBER);
            ApproveResourceLimitRequestCmd adjusted = approval(approved.getId());
            when(adjusted.getCpu()).thenReturn(6L);
            when(adjusted.getComment()).thenReturn("6 is enough");

            service.approveRequest(adjusted);
            as(STUDENT);
            ResourceLimitRequestVO rejected = service.createRequest(ask(4L, null, null, null, ASK_EXPIRY));
            as(MEMBER);
            service.rejectRequest(rejected.getId(), "already granted");
            service.revokeRequest(approved.getId(), "the term ended");

            List<FakeSmtpServer.Message> mails = server.getMessages();
            assertEquals(3, mails.size());
            for (FakeSmtpServer.Message mail : mails) {
                assertEquals(Collections.singletonList("student@example.org"), mail.recipients);
            }
            assertTrue(mails.get(0).data, mails.get(0).data.contains("was approved") && mails.get(0).data.contains("cpu 6") && mails.get(0).data.contains("6 is enough"));
            assertTrue(mails.get(1).data, mails.get(1).data.contains("was rejected") && mails.get(1).data.contains("already granted"));
            assertTrue(mails.get(2).data, mails.get(2).data.contains("was revoked") && mails.get(2).data.contains("the term ended"));
        }
    }

    @Test
    public void expiryWarningMailsRequester() throws Exception {
        grant(8L, null, null, gpus("a6000", 1), "2026-11-20");
        try (FakeSmtpServer server = relay()) {
            service.sweep(at("2026-11-13T15:00:00Z"));
            service.sweep(at("2026-11-13T16:00:00Z"));

            assertEquals(1, server.getMessages().size());
            FakeSmtpServer.Message mail = server.getMessages().get(0);
            assertEquals(Collections.singletonList("student@example.org"), mail.recipients);
            assertTrue(mail.data, mail.data.contains("expires within 7 day(s)"));
            assertTrue(mail.data, mail.data.contains("2026-11-20 23:59:59 KST"));
        }
    }

    @Test
    public void noSmtpConfiguredSkipsMailLogsOnceButKeepsBadge() throws Exception {
        try (FakeSmtpServer server = new FakeSmtpServer()) {
            settings.put("resource.request.smtp.port", String.valueOf(server.getPort()));
            Mockito.doNothing().when(accountManager).checkApiAccess(any(Account.class), anyString());

            ResourceLimitRequestVO request = service.createRequest(ask(8L, null, null, null, ASK_EXPIRY));

            assertEquals("nothing is sent", 0, server.getConnections());
            verify(service, times(1)).smtpUnset(anyString());
            verify(service, never()).approversOf(any());
            verify(service, never()).mailSender();
            as(MEMBER);
            assertEquals("the pending request still counts for the badge", Collections.singletonList(request.getUuid()),
                    listedIds(listing(true, ResourceLimitRequestVO.State.Pending, null)));
            service.approveRequest(approval(request.getId()));
            verify(service, times(2)).smtpUnset(anyString());
            assertEquals(0, server.getConnections());
        }
    }
}
