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
package org.apache.cloudstack.oidc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.dao.RoleDao;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.oidc.dao.OidcLoginRequestDao;
import org.apache.cloudstack.oidc.dao.OidcProviderDao;
import org.apache.cloudstack.oidc.dao.OidcUserLinkDao;
import org.apache.cloudstack.oidc.vo.OidcLoginRequestVO;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.user.Account;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserAccount;
import com.cloud.user.UserAccountVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The sign-in manager over an in-memory CloudStack (accounts, users, details, domains, roles and the module's own
 * tables, with their unique keys) and a mock provider on a loopback HTTPS port. Accounts, users and providers behave
 * as the DAO layer hands them out: each read is a new entity, and an update takes only an entity the DAO handed out
 * and writes the fields changed on it.
 */
public abstract class OidcTestBase {

    /** Rows of one table and the entities read from it. */
    protected static final class Table<T> {
        final Map<Long, T> rows = new ConcurrentHashMap<>();
        private final Map<Object, T> issued = Collections.synchronizedMap(new IdentityHashMap<>());

        T read(long id) {
            T row = rows.get(id);
            return row == null ? null : issue(row);
        }

        T issue(T row) {
            synchronized (rows) {
                T entity = copy(row);
                issued.put(entity, copy(row));
                return entity;
            }
        }

        boolean update(long id, T entity) {
            synchronized (rows) {
                T before = issued.get(entity);
                if (before == null) {
                    throw new IllegalStateException("an update takes an entity the DAO handed out, not " + entity);
                }
                T row = rows.get(id);
                try {
                    for (Field field : fields(entity.getClass())) {
                        Object value = field.get(entity);
                        if (!Objects.equals(value, field.get(before))) {
                            field.set(row, value);
                        }
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                issued.put(entity, copy(entity));
                return true;
            }
        }

        private static List<Field> fields(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field field : FieldUtils.getAllFieldsList(type)) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            return fields;
        }

        @SuppressWarnings("unchecked")
        static <T> T copy(T source) {
            try {
                java.lang.reflect.Constructor<?> constructor = source.getClass().getDeclaredConstructor();
                constructor.setAccessible(true);
                T copy = (T) constructor.newInstance();
                for (Field field : fields(source.getClass())) {
                    field.set(copy, field.get(source));
                }
                return copy;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final long ROOT = Domain.ROOT_DOMAIN;
    static final long STUDENTS = 5L;
    static final long ADMIN_ROLE = 1L;
    static final long USER_ROLE = 4L;
    static final long MEMBER_ROLE = 21L;
    static final long STUDENT_ROLE = 22L;
    static final long OTHER_USER_ROLE = 23L;
    static final String CLIENT_URL = "https://cloud.test/client";

    protected OidcAuthManagerImpl manager;
    protected MockIdp idp;

    protected final OidcProviderDao providerDao = mock(OidcProviderDao.class);
    protected final OidcLoginRequestDao loginRequestDao = mock(OidcLoginRequestDao.class);
    protected final OidcUserLinkDao linkDao = mock(OidcUserLinkDao.class);
    protected final AccountManager accountManager = mock(AccountManager.class);
    protected final AccountDao accountDao = mock(AccountDao.class);
    protected final UserDao userDao = mock(UserDao.class);
    protected final UserAccountDao userAccountDao = mock(UserAccountDao.class);
    protected final AccountDetailsDao accountDetailsDao = mock(AccountDetailsDao.class);
    protected final UserDetailsDao userDetailsDao = mock(UserDetailsDao.class);
    protected final DomainDao domainDao = mock(DomainDao.class);
    protected final RoleDao roleDao = mock(RoleDao.class);
    protected final SecurityGroupManager securityGroupManager = mock(SecurityGroupManager.class);
    protected final ApiServerService apiServer = mock(ApiServerService.class);
    protected final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    protected final ConfigDepotImpl configDepot = mock(ConfigDepotImpl.class);

    protected final Map<String, String> settings = new ConcurrentHashMap<>();
    protected final Table<OidcProviderVO> providerTable = new Table<>();
    protected final Table<AccountVO> accountTable = new Table<>();
    protected final Table<UserVO> userTable = new Table<>();
    protected final Map<Long, OidcProviderVO> providers = providerTable.rows;
    protected final Map<Long, OidcLoginRequestVO> requests = new ConcurrentHashMap<>();
    protected final Map<Long, OidcUserLinkVO> links = new ConcurrentHashMap<>();
    protected final Map<Long, AccountVO> accounts = accountTable.rows;
    protected final Map<Long, UserVO> users = userTable.rows;
    /** Account details that the account service removes when it enables an account, as modules registered them. */
    protected final Set<String> disableReasons = ConcurrentHashMap.newKeySet();
    protected final List<Predicate<UserAccount>> passwordSignInRefusals = new CopyOnWriteArrayList<>();
    protected final Map<Long, Map<String, String>> accountDetails = new ConcurrentHashMap<>();
    protected final Map<Long, Map<String, String>> userDetails = new ConcurrentHashMap<>();
    protected final Map<Long, DomainVO> domains = new HashMap<>();
    protected final Map<Long, RoleVO> roles = new HashMap<>();
    protected final List<Long> sgApplied = new ArrayList<>();
    protected final AtomicInteger accountsCreated = new AtomicInteger();
    protected final List<Object[]> createCalls = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    protected final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Runs inside account creation, after the account exists and before the call returns. */
    protected Runnable duringCreate = () -> { };

    protected Account caller;
    protected User callerUser;

    @Before
    public void setUpOidc() throws Exception {
        idp = new MockIdp();
        manager = spy(new OidcAuthManagerImpl());
        manager.providerDao = providerDao;
        manager.loginRequestDao = loginRequestDao;
        manager.linkDao = linkDao;
        manager.accountManager = accountManager;
        manager.accountDao = accountDao;
        manager.userDao = userDao;
        manager.userAccountDao = userAccountDao;
        manager.accountDetailsDao = accountDetailsDao;
        manager.userDetailsDao = userDetailsDao;
        manager.domainDao = domainDao;
        manager.roleDao = roleDao;
        manager.securityGroupManager = securityGroupManager;
        FieldUtils.writeField(manager, "executor", executor, true);
        doNothing().when(manager).recordEvent(anyString(), anyString());
        doNothing().when(manager).recordAccountEvent(any(AccountVO.class), anyString(), anyString());

        ConfigKey.init(configDepot);
        when(configDepot.getConfigStringValue(anyString(), any(ConfigKey.Scope.class), nullable(Long.class))).thenAnswer(i -> settings.get((String) i.getArgument(0)));
        settings.put(OidcAuthManager.ClientUrl.key(), CLIENT_URL);

        doAnswer(i -> {
            String name = i.getArgument(0);
            ReentrantLock lock = locks.computeIfAbsent(name, n -> new ReentrantLock());
            lock.lock();
            try {
                return ((java.util.concurrent.Callable<?>) i.getArgument(1)).call();
            } finally {
                lock.unlock();
            }
        }).when(manager).underLock(anyString(), any());

        domain(ROOT, "ROOT", "/");
        domain(STUDENTS, "students", "/students/");
        role(ADMIN_ROLE, "Root Admin", RoleType.Admin);
        role(USER_ROLE, "User", RoleType.User);
        role(MEMBER_ROLE, "Member", RoleType.Admin);
        role(STUDENT_ROLE, "Student", RoleType.User);
        role(OTHER_USER_ROLE, "Other User", RoleType.User);
        wireDaos();

        caller = new AccountVO("admin", ROOT, null, Account.Type.ADMIN, ADMIN_ROLE, "admin-uuid");
        ((AccountVO) caller).setId(2L);
        callerUser = new UserVO(2L);
        User system = new UserVO(User.UID_SYSTEM);
        AccountVO systemAccount = new AccountVO("system", ROOT, null, Account.Type.ADMIN, ADMIN_ROLE, "system-uuid");
        systemAccount.setId(Account.ACCOUNT_ID_SYSTEM);
        when(accountManager.getSystemUser()).thenReturn(system);
        when(accountManager.getSystemAccount()).thenReturn(systemAccount);
        CallContext.register(callerUser, caller);
        manager.start();
    }

    /** Enables the account as an administrator does, through the account service, which removes the registered marks with it. */
    protected void enableAsAdministrator(AccountVO account) {
        accountManager.enableAccount(null, null, account.getId());
    }

    @After
    public void tearDownOidc() {
        CallContext.unregister();
        ConfigKey.init(null);
        idp.close();
    }

    protected long nextId() {
        return ids.incrementAndGet();
    }

    protected void domain(long id, String name, String path) {
        DomainVO domain = new DomainVO(name, 2L, id == ROOT ? null : ROOT, null, "domain-" + id);
        domain.setId(id);
        domain.setPath(path);
        domains.put(id, domain);
    }

    protected void role(long id, String name, RoleType type) {
        roles.put(id, new RoleVO(id, name, type, name));
    }

    static void setId(Object vo, long id) {
        try {
            FieldUtils.writeField(vo, "id", id, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void wireDaos() throws Exception {
        when(providerDao.findByName(anyString())).thenAnswer(i -> providers.values().stream().filter(p -> p.getName().equals(i.getArgument(0))).findFirst()
                .map(providerTable::issue).orElse(null));
        when(providerDao.findById(anyLong())).thenAnswer(i -> providerTable.read(i.getArgument(0)));
        when(providerDao.findByUuid(anyString())).thenAnswer(i -> providers.values().stream().filter(p -> p.getUuid().equals(i.getArgument(0))).findFirst()
                .map(providerTable::issue).orElse(null));
        when(providerDao.listAll()).thenAnswer(i -> providers.values().stream().sorted(Comparator.comparingLong(OidcProviderVO::getId)).map(providerTable::issue)
                .collect(Collectors.toList()));
        when(providerDao.persist(any(OidcProviderVO.class))).thenAnswer(i -> {
            OidcProviderVO p = i.getArgument(0);
            if (providers.values().stream().anyMatch(o -> o.getName().equals(p.getName()))) {
                throw new IllegalStateException("duplicate provider name");
            }
            setId(p, nextId());
            providers.put(p.getId(), p);
            return providerTable.read(p.getId());
        });
        when(providerDao.update(anyLong(), any(OidcProviderVO.class))).thenAnswer(i -> providerTable.update(i.getArgument(0), i.getArgument(1)));
        when(providerDao.expunge(anyLong())).thenAnswer(i -> {
            long id = i.getArgument(0);
            if (links.values().stream().anyMatch(l -> l.getProviderId() == id)) {
                throw new IllegalStateException("foreign key: links of the provider exist");
            }
            requests.values().removeIf(r -> r.getProviderId() == id);
            return providers.remove(id) != null;
        });

        when(loginRequestDao.persist(any(OidcLoginRequestVO.class))).thenAnswer(i -> {
            OidcLoginRequestVO r = i.getArgument(0);
            setId(r, nextId());
            requests.put(r.getId(), r);
            return r;
        });
        when(loginRequestDao.findByStateHash(anyString())).thenAnswer(i -> requests.values().stream()
                .filter(r -> r.getStateHash().equals(i.getArgument(0))).findFirst().orElse(null));
        when(loginRequestDao.consume(anyLong())).thenAnswer(i -> requests.remove((Long) i.getArgument(0)) != null);
        when(loginRequestDao.removeCreatedBefore(any(Date.class))).thenAnswer(i -> {
            Date cutoff = i.getArgument(0);
            int before = requests.size();
            requests.values().removeIf(r -> r.getCreated().before(cutoff));
            return before - requests.size();
        });

        when(linkDao.findByProviderAndSubject(anyLong(), anyString())).thenAnswer(i -> links.values().stream()
                .filter(l -> l.getProviderId() == (Long) i.getArgument(0) && l.getSubject().equals(i.getArgument(1))).findFirst().orElse(null));
        when(linkDao.findByUserId(anyLong())).thenAnswer(i -> links.values().stream().filter(l -> l.getUserId() == (Long) i.getArgument(0)).findFirst().orElse(null));
        when(linkDao.listByProvider(anyLong())).thenAnswer(i -> links.values().stream().filter(l -> l.getProviderId() == (Long) i.getArgument(0))
                .sorted(Comparator.comparingLong(OidcUserLinkVO::getId)).collect(Collectors.toList()));
        when(linkDao.persist(any(OidcUserLinkVO.class))).thenAnswer(i -> {
            OidcUserLinkVO l = i.getArgument(0);
            synchronized (links) {
                if (links.values().stream().anyMatch(o -> (o.getProviderId() == l.getProviderId() && o.getSubject().equals(l.getSubject()))
                        || o.getUserId() == l.getUserId())) {
                    throw new IllegalStateException("duplicate link");
                }
                setId(l, nextId());
                links.put(l.getId(), l);
            }
            return l;
        });
        when(linkDao.expunge(anyLong())).thenAnswer(i -> links.remove((Long) i.getArgument(0)) != null);

        when(domainDao.findById(anyLong())).thenAnswer(i -> domains.get((Long) i.getArgument(0)));
        when(roleDao.findById(anyLong())).thenAnswer(i -> roles.get((Long) i.getArgument(0)));

        when(accountDao.findById(anyLong())).thenAnswer(i -> accountTable.read(i.getArgument(0)));
        when(accountDao.update(anyLong(), any(AccountVO.class))).thenAnswer(i -> accountTable.update(i.getArgument(0), i.getArgument(1)));
        when(userDao.findById(anyLong())).thenAnswer(i -> userTable.read(i.getArgument(0)));
        when(userDao.update(anyLong(), any(UserVO.class))).thenAnswer(i -> userTable.update(i.getArgument(0), i.getArgument(1)));
        when(userDao.getUserByName(anyString(), anyLong())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getRemoved() == null && u.getUsername().equals(i.getArgument(0)))
                .filter(u -> accounts.get(u.getAccountId()).getDomainId() == (Long) i.getArgument(1) && accounts.get(u.getAccountId()).getRemoved() == null)
                .findFirst().map(userTable::issue).orElse(null));
        when(userDao.listByAccount(anyLong())).thenAnswer(i -> users.values().stream().filter(u -> u.getAccountId() == (Long) i.getArgument(0))
                .sorted(Comparator.comparingLong(UserVO::getId)).map(userTable::issue).collect(Collectors.toList()));
        when(accountDetailsDao.findResourceIdsByNameAndValueIn(anyString(), any(Object[].class))).thenAnswer(i -> {
            List<Object> values = List.of((Object[]) i.getArgument(1));
            return accountDetails.entrySet().stream().filter(e -> e.getValue().containsKey((String) i.getArgument(0))
                    && values.contains(e.getValue().get((String) i.getArgument(0)))).map(Map.Entry::getKey).sorted().collect(Collectors.toList());
        });
        when(userAccountDao.getUserAccountByEmail(anyString(), anyLong())).thenAnswer(i -> users.values().stream()
                .filter(u -> u.getRemoved() == null && u.getEmail() != null && u.getEmail().equalsIgnoreCase(i.getArgument(0)))
                .filter(u -> accounts.get(u.getAccountId()).getDomainId() == (Long) i.getArgument(1))
                .map(u -> {
                    UserAccountVO view = mock(UserAccountVO.class);
                    when(view.getId()).thenReturn(u.getId());
                    when(view.getUsername()).thenReturn(u.getUsername());
                    return view;
                }).collect(Collectors.toList()));

        when(accountDetailsDao.findDetails(anyLong())).thenAnswer(i -> new HashMap<>(accountDetails.getOrDefault((Long) i.getArgument(0), new HashMap<>())));
        when(accountDetailsDao.findDetail(anyLong(), anyString())).thenAnswer(i -> detail(i.getArgument(0), i.getArgument(1)));
        doAnswer(i -> {
            accountDetails.computeIfAbsent(i.getArgument(0), k -> new ConcurrentHashMap<>()).put(i.getArgument(1), i.getArgument(2));
            return null;
        }).when(accountDetailsDao).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
        doAnswer(i -> {
            accountDetails.computeIfAbsent(i.getArgument(0), k -> new ConcurrentHashMap<>()).remove((String) i.getArgument(1));
            return null;
        }).when(accountDetailsDao).removeDetail(anyLong(), anyString());
        doAnswer(i -> {
            accountDetails.computeIfAbsent(i.getArgument(0), k -> new ConcurrentHashMap<>()).remove((String) i.getArgument(1), (String) i.getArgument(2));
            return null;
        }).when(accountDetailsDao).removeDetail(anyLong(), anyString(), anyString());
        doAnswer(i -> {
            userDetails.computeIfAbsent(i.getArgument(0), k -> new ConcurrentHashMap<>()).remove((String) i.getArgument(1));
            return null;
        }).when(userDetailsDao).removeDetail(anyLong(), anyString());

        doAnswer(i -> {
            synchronized (sgApplied) {
                sgApplied.add(i.getArgument(0));
            }
            return null;
        }).when(securityGroupManager).applyDefaultSecurityGroupRules(anyLong());

        when(accountManager.createUserAccount(anyString(), anyString(), anyString(), anyString(), nullable(String.class), isNull(), anyString(),
                any(Account.Type.class), anyLong(), anyLong(), isNull(), isNull(), isNull(), isNull(), any(User.Source.class))).thenAnswer(i -> {
                    synchronized (createCalls) {
                        createCalls.add(i.getArguments());
                    }
                    String username = i.getArgument(0);
                    long domainId = i.getArgument(9);
                    if (userDao.getUserByName(username, domainId) != null) {
                        throw new com.cloud.exception.InvalidParameterValueException("The user " + username + " already exists in domain " + domainId);
                    }
                    AccountVO account = account(i.getArgument(6), domainId, i.getArgument(7), i.getArgument(8));
                    UserVO user = user(account, username, i.getArgument(4), i.getArgument(14));
                    user.setFirstname(i.getArgument(2));
                    user.setLastname(i.getArgument(3));
                    accountsCreated.incrementAndGet();
                    duringCreate.run();
                    UserAccount view = mock(UserAccount.class);
                    when(view.getId()).thenReturn(user.getId());
                    when(view.getAccountId()).thenReturn(account.getId());
                    when(view.getUsername()).thenReturn(username);
                    return view;
                });
        when(accountManager.disableAccount(isNull(), isNull(), anyLong())).thenAnswer(i -> {
            AccountVO account = accounts.get((Long) i.getArgument(2));
            account.setState(Account.State.DISABLED);
            return account;
        });
        doAnswer(i -> disableReasons.add(i.getArgument(0))).when(accountManager).addDisableReasonDetail(anyString());
        doAnswer(i -> passwordSignInRefusals.add(i.getArgument(0))).when(accountManager).addPasswordSignInRefusal(any());
        when(accountManager.enableAccount(isNull(), isNull(), anyLong())).thenAnswer(i -> {
            enableAccountRow(i.getArgument(2));
            return accounts.get((Long) i.getArgument(2));
        });
        when(accountManager.enableAccount(anyLong())).thenAnswer(i -> {
            enableAccountRow(i.getArgument(0));
            return true;
        });
        when(accountDao.lockRow(anyLong(), any())).thenAnswer(i -> accountTable.read(i.getArgument(0)));
        when(accountManager.stopResourcesOfDisabledAccount(anyLong())).thenAnswer(i -> {
            AccountVO account = accounts.get((Long) i.getArgument(0));
            synchronized (accountTable.rows) {
                if (account.getState() != Account.State.DISABLED || !account.getNeedsCleanup()) {
                    return false;
                }
                account.setNeedsCleanup(false);
            }
            return true;
        });
        doAnswer(i -> ((java.util.function.Supplier<?>) i.getArgument(0)).get()).when(manager).inTransaction(any());

    }

    /** What the account service's enable does to the database, in one transaction. */
    private void enableAccountRow(long accountId) {
        AccountVO account = accounts.get(accountId);
        synchronized (accountTable.rows) {
            account.setState(Account.State.ENABLED);
            account.setNeedsCleanup(false);
            accountDetails.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>()).keySet().removeAll(disableReasons);
        }
    }

    protected com.cloud.user.AccountDetailVO detail(long accountId, String name) {
        String value = accountDetails.getOrDefault(accountId, new HashMap<>()).get(name);
        return value == null ? null : new com.cloud.user.AccountDetailVO(accountId, name, value);
    }

    protected AccountVO account(String name, long domainId, Account.Type type, long roleId) {
        AccountVO account = new AccountVO(name, domainId, null, type, roleId, "account-" + name);
        account.setId(nextId());
        account.setState(Account.State.ENABLED);
        accounts.put(account.getId(), account);
        return account;
    }

    protected UserVO user(AccountVO account, String username, String email, User.Source source) {
        UserVO user = new UserVO(nextId());
        user.setAccountId(account.getId());
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstname(username);
        user.setLastname("-");
        user.setState(Account.State.ENABLED);
        user.setSource(source);
        users.put(user.getId(), user);
        return user;
    }

    /** An account an earlier sign-in service created: marked, named after the user, with the user's password login. */
    protected UserVO markedAccount(long domainId, long roleId, String username, String email, boolean disabledByService) {
        AccountVO account = account(username, domainId, roles.get(roleId).getRoleType().getAccountType(), roleId);
        Map<String, String> details = new ConcurrentHashMap<>();
        details.put(OidcAuthManagerImpl.GATE_MARK, "true");
        if (disabledByService) {
            details.put(OidcAuthManagerImpl.GATE_DISABLED_BY, OidcAuthManagerImpl.GATE_MARK);
            account.setState(Account.State.DISABLED);
        }
        accountDetails.put(account.getId(), details);
        return user(account, username, email, User.Source.UNKNOWN);
    }

    /** Deletes the user and its account as CloudStack does: the rows stay, marked removed. */
    protected void markRemoved(UserVO user) {
        try {
            FieldUtils.writeField(user, "removed", new Date(), true);
            FieldUtils.writeField(accounts.get(user.getAccountId()), "removed", new Date(), true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    protected OidcProviderVO provider(String name, long domainId, long roleId, String usernameClaim, String pattern, String admission) {
        OidcProviderVO p = new OidcProviderVO();
        p.setName(name);
        p.setLabel(name.toUpperCase());
        p.setIssuer(idp.issuer());
        p.setCaCertificate(idp.caPem());
        p.setClientId(MockIdp.CLIENT_ID);
        p.setClientSecret(MockIdp.CLIENT_SECRET);
        p.setUsernameClaim(usernameClaim);
        p.setUsernamePattern(pattern);
        p.setAdmission(admission);
        p.setDomainId(domainId);
        p.setRoleId(roleId);
        setId(p, nextId());
        providers.put(p.getId(), p);
        return p;
    }

    /** The student provider of the ported scenarios: username claim, major group, its own domain, a User-type role. */
    protected OidcProviderVO students() {
        OidcProviderVO p = provider("students", STUDENTS, STUDENT_ROLE, "username", "[a-z][a-z0-9]{1,19}",
                "{\"groups_claim\":\"groups\",\"allowed_groups\":[\"major\"]}");
        p.setRefusal("<p>Only majors.</p>");
        p.setEndSessionUrl("https://id.example/session/end");
        p.setEndSessionLabel("Sign out of Student ID");
        return p;
    }

    /** The member provider: lowercased e-mail usernames, verified addresses, a subject prefix, an Admin-type role in ROOT. */
    protected OidcProviderVO members() {
        OidcProviderVO p = provider("members", ROOT, MEMBER_ROLE, "email", "[a-z0-9._-]+@members\\.example\\.org",
                "{\"groups_claim\":\"https://example.org/groups\",\"allowed_groups\":[\"regular-members@members.example.org\"],"
                        + "\"subject_prefix\":\"workspace|\"}");
        p.setLowercaseUsername(true);
        p.setRequireVerifiedEmail(true);
        p.setAuthorizeParams("{\"connection\":\"workspace\"}");
        p.setRefusal("<p>Only regular members.</p>");
        p.setEndSessionUrl("https://auth.example/oidc/logout");
        p.setEndSessionLabel("Sign out of Members");
        return p;
    }

    /** A person of the student provider, which presents an address without saying whether it verified it. */
    protected static MockIdp.Person student(String sub, String username, String... groups) {
        return new MockIdp.Person(sub).claim("username", username).claim("email", username + "@students.example.org").claim("name", "Student " + username)
                .groups("groups", groups);
    }

    protected static MockIdp.Person member(String email, String... groups) {
        return new MockIdp.Person("workspace|" + email).claim("email", email).claim("email_verified", true).claim("name", "Member")
                .groups("https://example.org/groups", groups);
    }

    /** A browser's sign-in: start, the provider's answer, and the binding cookie the browser brings back. */
    protected final class Browser {
        OidcLoginStart start;
        Map<String, String> answer;

        Browser begin(String provider) throws OidcLoginException {
            start = manager.startLogin(provider);
            return this;
        }

        Browser signIn(MockIdp.Person person) {
            answer = idp.authorize(start.getAuthorizationUrl(), person);
            return this;
        }

        long complete(String provider) throws OidcLoginException {
            return manager.completeLogin(provider, answer, start.getBinding());
        }
    }

    protected long login(String provider, MockIdp.Person person) throws OidcLoginException {
        return new Browser().begin(provider).signIn(person).complete(provider);
    }

    protected OidcLoginError refused(String provider, MockIdp.Person person) {
        try {
            login(provider, person);
            fail("the sign-in started a session");
            return null;
        } catch (OidcLoginException e) {
            return e.getError();
        }
    }

    protected void assertRefused(OidcLoginError error, String provider, MockIdp.Person person) {
        assertEquals(error, refused(provider, person));
    }

    protected UserVO userNamed(String username) {
        return users.values().stream().filter(u -> u.getUsername().equals(username)).findFirst().orElse(null);
    }

    protected AccountVO accountOf(UserVO user) {
        return accounts.get(user.getAccountId());
    }

    protected Map<String, String> detailsOf(AccountVO account) {
        return accountDetails.getOrDefault(account.getId(), new HashMap<>());
    }

    protected OidcUserLinkVO linkOf(UserVO user) {
        return links.values().stream().filter(l -> l.getUserId() == user.getId()).findFirst().orElse(null);
    }

    protected static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    protected static JsonObject object(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    /** A command whose parameters are all absent until stubbed, as the API layer leaves parameters it was not given. */
    protected static <T> T parameters(Class<T> command) {
        return mock(command, invocation -> null);
    }

    protected static <T> T argument(Object[] call, int index) {
        @SuppressWarnings("unchecked")
        T value = (T) call[index];
        return value;
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
