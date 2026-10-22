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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.persistence.Column;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ConfigKeyScheduledExecutionWrapper;
import org.apache.cloudstack.oidc.api.command.CreateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.command.UpdateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;
import org.apache.cloudstack.oidc.membership.OidcMembershipSource;
import org.apache.cloudstack.oidc.vo.OidcLoginRequestVO;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;
import org.apache.cloudstack.resourcedetail.UserDetailVO;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.GlobalLock;

public class OidcAuthManagerImplTest extends OidcTestBase {

    private static final String STUDENTS_PROVIDER = "students";
    private static final String MEMBERS_PROVIDER = "members";

    // ---------------------------------------------------------------- answer and token checks

    @Test
    public void issParamCheckedWhenSupported() throws Exception {
        students();
        idp.issParameterSupported = true;
        manager.forgetProviderMetadata();
        login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));

        idp.sendIss = false;
        assertRefused(OidcLoginError.FAILED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));

        idp.sendIss = true;
        Browser wrongIssuer = new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major"));
        wrongIssuer.answer.put("iss", "https://other.example");
        OidcLoginException e = assertThrows(OidcLoginException.class, () -> wrongIssuer.complete(STUDENTS_PROVIDER));
        assertEquals(OidcLoginError.FAILED, e.getError());

        idp.issParameterSupported = false;
        manager.forgetProviderMetadata();
        idp.sendIss = false;
        login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        idp.sendIss = true;
        login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        Browser forged = new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major"));
        forged.answer.put("iss", "https://other.example");
        e = assertThrows(OidcLoginException.class, () -> forged.complete(STUDENTS_PROVIDER));
        assertEquals("an issuer in the answer is checked even where the provider does not announce it", OidcLoginError.FAILED, e.getError());
    }

    @Test
    public void idTokenFromTheProviderIsVerified() {
        students();
        idp.idTokenTamper = claims -> claims.addProperty("aud", "someone-else");
        assertRefused(OidcLoginError.FAILED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        idp.idTokenTamper = claims -> claims.addProperty("nonce", "replayed");
        assertRefused(OidcLoginError.FAILED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertEquals(0, accountsCreated.get());
    }

    @Test
    public void stateConsumedOnce() throws Exception {
        students();
        Browser browser = new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major"));
        browser.complete(STUDENTS_PROVIDER);
        OidcLoginException replay = assertThrows(OidcLoginException.class, () -> browser.complete(STUDENTS_PROVIDER));
        assertEquals(OidcLoginError.EXPIRED, replay.getError());
        assertTrue("a consumed request is gone", requests.isEmpty());

        Browser twice = new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> twice.complete(STUDENTS_PROVIDER)));
            }
            int sessions = 0;
            for (Future<Long> result : results) {
                try {
                    result.get();
                    sessions++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertEquals(OidcLoginError.EXPIRED, ((OidcLoginException) ex.getCause()).getError());
                }
            }
            assertEquals("one answer starts one session", 1, sessions);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void expiredRequestIsRefused() throws Exception {
        students();
        settings.put(OidcAuthManager.LoginTimeout.key(), "600");
        Browser browser = new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major"));
        doReturn(java.time.Instant.now().plusSeconds(601)).when(manager).now();
        OidcLoginException e = assertThrows(OidcLoginException.class, () -> browser.complete(STUDENTS_PROVIDER));
        assertEquals(OidcLoginError.EXPIRED, e.getError());
        assertEquals("a late answer never reaches the token endpoint", 0, idp.tokenRequests.get());
    }

    // ---------------------------------------------------------------- accounts

    @Test
    public void jitCreatesAnAccountOfTheProvidersDomainAndRole() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        UserVO user = users.get(userId);
        assertEquals("alice", user.getUsername());
        assertEquals("alice@students.example.org", user.getEmail());
        assertEquals(User.Source.OAUTH2, user.getSource());
        AccountVO account = accountOf(user);
        assertEquals("alice", account.getAccountName());
        assertEquals(STUDENTS, account.getDomainId());
        assertEquals(STUDENT_ROLE, (long) account.getRoleId());
        assertEquals(Account.Type.NORMAL, account.getType());
        assertEquals("s-1", linkOf(user).getSubject());
        assertTrue("the default security group template is ensured at sign-in", sgApplied.contains(account.getId()));
        Object[] call = createCalls.get(0);
        assertEquals("Student alice", argument(call, 2));
        assertTrue("a long random password nobody knows", ((String) argument(call, 1)).length() >= 32);
        assertEquals(userId, login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        assertEquals(1, accountsCreated.get());
    }

    @Test
    public void noEmailLinking() throws Exception {
        students();
        AccountVO existing = account("bob", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        UserVO bob = user(existing, "bob", "alice@students.example.org", User.Source.UNKNOWN);

        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertNotEquals("an address shared with another user never links to that user", bob.getId(), userId);
        assertNull(linkOf(bob));
        assertEquals("the stale copy of the address is released", "bob@stale.invalid", users.get(bob.getId()).getEmail());
        assertEquals("alice@students.example.org", users.get(userId).getEmail());

        AccountVO namesake = account("carol", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        UserVO carol = user(namesake, "carol", "carol@elsewhere.example", User.Source.UNKNOWN);
        assertRefused(OidcLoginError.CONFLICT, STUDENTS_PROVIDER, student("s-2", "carol", "major"));
        assertNull("a user of the same name is not linked either", linkOf(carol));
        assertEquals(1, accountsCreated.get());
    }

    @Test
    public void jitCreatesOnceUnderConcurrency() throws Exception {
        students();
        duringCreate = () -> sleep(100);
        int logins = 6;
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < logins; i++) {
            browsers.add(new Browser().begin(STUDENTS_PROVIDER).signIn(student("s-1", "alice", "major")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(logins);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (Browser browser : browsers) {
                results.add(pool.submit(() -> browser.complete(STUDENTS_PROVIDER)));
            }
            Set<Long> sessions = new HashSet<>();
            for (Future<Long> result : results) {
                sessions.add(result.get());
            }
            assertEquals("every sign-in reaches the one account", 1, sessions.size());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accountsCreated.get());
        assertEquals(1, links.size());
    }

    @Test
    public void jitOffRefusesAnAdmittedUserWithoutAnAccount() throws Exception {
        OidcProviderVO provider = students();
        provider.setJit(false);
        assertRefused(OidcLoginError.NO_ACCOUNT, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertEquals(0, accountsCreated.get());
        assertTrue(links.isEmpty());

        AccountVO account = account("bob", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        UserVO bob = user(account, "bob", "bob@students.example.org", User.Source.OAUTH2);
        links.put(nextId(), linkFor(provider, "s-2", bob));
        assertEquals("a linked account still signs in", bob.getId(), login(STUDENTS_PROVIDER, student("s-2", "bob", "major")));

        UserVO carol = markedAccount(STUDENTS, STUDENT_ROLE, "carol", "carol@students.example.org", false);
        assertEquals("an adopted account still signs in", carol.getId(), login(STUDENTS_PROVIDER, student("s-3", "carol", "major")));
        assertEquals(0, accountsCreated.get());
    }

    private OidcUserLinkVO linkFor(OidcProviderVO provider, String subject, UserVO user) {
        OidcUserLinkVO link = new OidcUserLinkVO(provider.getId(), subject, user.getId());
        setId(link, nextId());
        return link;
    }

    @Test
    public void loginUpdatesEmailNameRoleNotDomainNotUsername() throws Exception {
        OidcProviderVO provider = students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));

        provider.setRoleId(OTHER_USER_ROLE);
        provider.setDomainId(ROOT);
        MockIdp.Person changed = new MockIdp.Person("s-1").claim("username", "alice2").claim("email", "alice.new@students.example.org")
                .claim("name", "Alice Kim").groups("groups", "major");
        assertEquals(userId, login(STUDENTS_PROVIDER, changed));
        UserVO user = users.get(userId);
        assertEquals("alice.new@students.example.org", user.getEmail());
        assertEquals("Alice Kim", user.getFirstname());
        assertEquals("alice", user.getUsername());
        AccountVO account = accountOf(user);
        assertEquals(OTHER_USER_ROLE, (long) account.getRoleId());
        assertEquals(STUDENTS, account.getDomainId());
        assertEquals("alice", account.getAccountName());

        provider.setRoleId(MEMBER_ROLE);
        assertRefused(OidcLoginError.CONFLICT, STUDENTS_PROVIDER, changed);
        assertEquals("a role of another account type is never given", OTHER_USER_ROLE, (long) accountOf(users.get(userId)).getRoleId());
    }

    @Test
    public void identityChecksRefuseWithoutTouchingAccounts() {
        members();
        assertRefused(OidcLoginError.IDENTITY, MEMBERS_PROVIDER, member("x@members.example.org", "regular-members@members.example.org")
                .claim("email_verified", false));
        assertRefused(OidcLoginError.IDENTITY, MEMBERS_PROVIDER, new MockIdp.Person("other-connection|x").claim("email", "x@members.example.org")
                .claim("email_verified", true).groups("https://example.org/groups", "regular-members@members.example.org"));
        assertRefused(OidcLoginError.IDENTITY, MEMBERS_PROVIDER, member("x@gmail.com", "regular-members@members.example.org"));
        assertEquals(0, accountsCreated.get());
    }

    @Test
    public void lossOfQualificationDisablesAndMarks() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice", "alumni"));
        AccountVO account = accountOf(users.get(userId));
        assertEquals(Account.State.DISABLED, account.getState());
        assertEquals(STUDENTS_PROVIDER, detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));

        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-9", "dave", "alumni"));
        assertNull("nobody gets an account without qualifying", userNamed("dave"));
        assertEquals(1, accountsCreated.get());
    }

    @Test
    public void reenableOnlyIfWeDisabled() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        assertEquals(userId, login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        AccountVO account = accountOf(users.get(userId));
        assertEquals(Account.State.ENABLED, account.getState());
        assertNull(detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));

        account.setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertEquals("an administrator's disable stays", Account.State.DISABLED, account.getState());
        verify(accountManager, times(1)).enableAccount(account.getId());

        account.setState(Account.State.LOCKED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        account.setState(Account.State.ENABLED);
        users.get(userId).setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
    }

    @Test
    public void gateAccountAdoptedOnlyWithMark() throws Exception {
        students();
        UserVO alice = markedAccount(STUDENTS, STUDENT_ROLE, "alice", "alice@students.example.org", false);
        userDetails.computeIfAbsent(alice.getId(), k -> new HashMap<>()).put(UserDetailVO.PasswordChangeRequired, "true");
        assertEquals(alice.getId(), login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        assertEquals(0, accountsCreated.get());
        assertEquals("s-1", linkOf(alice).getSubject());
        assertEquals("password sign-in ends with the adoption", User.Source.OAUTH2, users.get(alice.getId()).getSource());
        Map<String, String> details = detailsOf(accountOf(alice));
        assertNull(details.get(OidcAuthManagerImpl.GATE_MARK));
        assertNull(details.get(OidcAuthManagerImpl.GATE_DISABLED_BY));
        assertNull(userDetails.get(alice.getId()).get(UserDetailVO.PasswordChangeRequired));

        AccountVO unmarked = account("bob", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        UserVO bob = user(unmarked, "bob", "bob@students.example.org", User.Source.UNKNOWN);
        assertRefused(OidcLoginError.CONFLICT, STUDENTS_PROVIDER, student("s-2", "bob", "major"));
        assertNull(linkOf(bob));
        assertEquals(User.Source.UNKNOWN, users.get(bob.getId()).getSource());

        links.clear();
        assertRefused("adoption happens once: the mark is gone", OidcLoginError.CONFLICT, student("s-3", "alice", "major"));
    }

    private void assertRefused(String message, OidcLoginError error, MockIdp.Person person) {
        assertEquals(message, error, refused(STUDENTS_PROVIDER, person));
    }

    @Test
    public void gateAdoptionCarriesGateDisable() throws Exception {
        students();
        UserVO alice = markedAccount(STUDENTS, STUDENT_ROLE, "alice", "alice@students.example.org", true);
        assertEquals("a disable of the earlier service counts as ours: a qualified person is enabled again", alice.getId(),
                login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        assertEquals(Account.State.ENABLED, accountOf(alice).getState());
        assertNull(detailsOf(accountOf(alice)).get(OidcAuthManager.DISABLED_BY_DETAIL));

        UserVO bob = markedAccount(STUDENTS, STUDENT_ROLE, "bob", "bob@students.example.org", true);
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-2", "bob", "alumni"));
        assertEquals(bob.getId(), linkOf(bob).getUserId());
        assertEquals(Account.State.DISABLED, accountOf(bob).getState());
        assertEquals("the earlier service's mark carries over", STUDENTS_PROVIDER, detailsOf(accountOf(bob)).get(OidcAuthManager.DISABLED_BY_DETAIL));
        assertNull(detailsOf(accountOf(bob)).get(OidcAuthManagerImpl.GATE_DISABLED_BY));

        UserVO carol = markedAccount(STUDENTS, STUDENT_ROLE, "carol", "carol@students.example.org", false);
        accountOf(carol).setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-3", "carol", "major"));
        assertEquals("an administrator's disable of a marked account stays", Account.State.DISABLED, accountOf(carol).getState());
        assertNull(detailsOf(accountOf(carol)).get(OidcAuthManager.DISABLED_BY_DETAIL));
    }

    @Test
    public void staleLinkOfARemovedAccountGivesANewAccount() throws Exception {
        students();
        long first = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        UserVO removed = users.get(first);
        removed.setUsername("alice-removed");
        markRemoved(removed);
        long second = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertNotEquals(first, second);
        assertEquals(2, accountsCreated.get());
        assertEquals(1, links.size());
    }

    // ---------------------------------------------------------------- periodic reconcile

    /** A source that knows people by their identity at the provider. */
    private OidcMembershipSource memberships(Map<String, Boolean> answers) {
        return (member, group) -> answers.get(member + "/" + group);
    }

    @Test
    public void reconcileDisablesOnLostMembership() throws Exception {
        OidcProviderVO provider = students();
        long alice = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        long bob = login(STUDENTS_PROVIDER, student("s-2", "bob", "major"));
        long carol = login(STUDENTS_PROVIDER, student("s-3", "carol", "major"));
        Map<String, Boolean> answers = new HashMap<>();
        answers.put("s-1/major", true);
        answers.put("s-2/major", false);
        doReturn(memberships(answers)).when(manager).membershipSource(any(OidcProviderVO.class));

        manager.reconcile();

        assertEquals(Account.State.ENABLED, accountOf(users.get(alice)).getState());
        assertEquals(Account.State.DISABLED, accountOf(users.get(bob)).getState());
        assertEquals(provider.getName(), detailsOf(accountOf(users.get(bob))).get(OidcAuthManager.DISABLED_BY_DETAIL));
        assertEquals("a source that cannot tell changes nothing", Account.State.ENABLED, accountOf(users.get(carol)).getState());
    }

    @Test
    public void reconcileReenablesOnlyOwnDisable() throws Exception {
        students();
        long bob = login(STUDENTS_PROVIDER, student("s-2", "bob", "major"));
        long carol = login(STUDENTS_PROVIDER, student("s-3", "carol", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-2", "bob"));
        accountOf(users.get(carol)).setState(Account.State.DISABLED);
        Map<String, Boolean> answers = new HashMap<>();
        answers.put("s-2/major", true);
        answers.put("s-3/major", true);
        doReturn(memberships(answers)).when(manager).membershipSource(any(OidcProviderVO.class));

        manager.reconcile();

        assertEquals(Account.State.ENABLED, accountOf(users.get(bob)).getState());
        assertNull(detailsOf(accountOf(users.get(bob))).get(OidcAuthManager.DISABLED_BY_DETAIL));
        assertEquals(Account.State.DISABLED, accountOf(users.get(carol)).getState());
    }

    @Test
    public void reconcileSkipsProviderWithoutSource() throws Exception {
        OidcProviderVO provider = students();
        long alice = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertNull(provider.getMembershipSource());
        assertNull(manager.membershipSource(provider));

        manager.reconcile();

        assertEquals(Account.State.ENABLED, accountOf(users.get(alice)).getState());
        verify(accountManager, never()).disableAccount(eq(null), eq(null), anyLong());
    }

    @Test
    public void administratorsEnableEndsTheMark() throws Exception {
        assertEquals("an enable by anyone removes the marks of this module and of the earlier service",
                Set.of(OidcAuthManager.DISABLED_BY_DETAIL, OidcAuthManagerImpl.GATE_DISABLED_BY), disableReasons);
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        AccountVO account = accountOf(users.get(userId));
        assertEquals(STUDENTS_PROVIDER, detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));

        enableAsAdministrator(account);
        assertNull("an enable by anyone ends this module's claim on the account", detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));

        account.setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        Map<String, Boolean> answers = new HashMap<>();
        answers.put("s-1/major", true);
        doReturn(memberships(answers)).when(manager).membershipSource(any(OidcProviderVO.class));
        manager.reconcile();
        assertEquals("neither sign-in nor the reconcile lifts an administrator's disable", Account.State.DISABLED, accountOf(users.get(userId)).getState());

        UserVO bob = markedAccount(STUDENTS, STUDENT_ROLE, "bob", "bob@students.example.org", true);
        enableAsAdministrator(accountOf(bob));
        assertNull("the earlier service's claim ends the same way", detailsOf(accountOf(bob)).get(OidcAuthManagerImpl.GATE_DISABLED_BY));
        accountOf(bob).setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-2", "bob", "major"));
        assertEquals(Account.State.DISABLED, accountOf(bob).getState());
    }

    @Test
    public void enableEndsTheMarkWithoutWaitingForTheAccountLock() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        AccountVO account = accountOf(users.get(userId));
        String accountLock = "oidc.account." + account.getId();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            ReentrantLock lock = locks.computeIfAbsent(accountLock, n -> new ReentrantLock());
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        ExecutorService administrator = Executors.newSingleThreadExecutor();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            administrator.submit(() -> enableAsAdministrator(account)).get(5, TimeUnit.SECONDS);
            assertEquals(Account.State.ENABLED, account.getState());
            assertNull("the enable ends the claim at once, also while a sign-in or check holds the account",
                    detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));
        } finally {
            release.countDown();
            holder.join(10000);
            administrator.shutdownNow();
        }

        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        Mockito.clearInvocations(manager);
        assertEquals(userId, login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        assertEquals(Account.State.ENABLED, accountOf(users.get(userId)).getState());
        verify(manager, times(1)).underLock(eq(accountLock), any());
    }

    @Test
    public void reconcileCoversUnadoptedGateAccounts() throws Exception {
        members();
        String group = "regular-members@members.example.org";
        UserVO alice = markedAccount(ROOT, MEMBER_ROLE, "alice@members.example.org", "alice@members.example.org", false);
        UserVO bob = markedAccount(ROOT, MEMBER_ROLE, "bob@members.example.org", "bob@members.example.org", false);
        UserVO carol = markedAccount(ROOT, MEMBER_ROLE, "carol@members.example.org", "carol@members.example.org", true);
        UserVO operator = markedAccount(ROOT, MEMBER_ROLE, "operator", "operator@elsewhere.example", false);
        UserVO elsewhere = markedAccount(STUDENTS, STUDENT_ROLE, "dave@members.example.org", "dave@members.example.org", false);
        AccountVO unmarked = account("erin@members.example.org", ROOT, Account.Type.ADMIN, MEMBER_ROLE);
        user(unmarked, "erin@members.example.org", "erin@members.example.org", User.Source.UNKNOWN);
        Map<String, Boolean> answers = new HashMap<>();
        for (String person : List.of("alice@members.example.org", "operator", "dave@members.example.org", "erin@members.example.org")) {
            answers.put(person + "/" + group, false);
        }
        answers.put("bob@members.example.org/" + group, true);
        answers.put("carol@members.example.org/" + group, true);
        doReturn(memberships(answers)).when(manager).membershipSource(any(OidcProviderVO.class));

        manager.reconcile();

        assertEquals("a member who left is disabled without signing in", Account.State.DISABLED, accountOf(alice).getState());
        assertEquals("members", detailsOf(accountOf(alice)).get(OidcAuthManager.DISABLED_BY_DETAIL));
        assertEquals("the account stays to be taken over at sign-in", "true", detailsOf(accountOf(alice)).get(OidcAuthManagerImpl.GATE_MARK));
        assertNull(linkOf(alice));
        assertEquals(Account.State.ENABLED, accountOf(bob).getState());
        assertEquals("the earlier service's disable counts as this module's", Account.State.ENABLED, accountOf(carol).getState());
        assertNull(detailsOf(accountOf(carol)).get(OidcAuthManagerImpl.GATE_DISABLED_BY));
        assertEquals("a name the provider does not give is not its account", Account.State.ENABLED, accountOf(operator).getState());
        assertEquals("an account of another domain is not its account", Account.State.ENABLED, accountOf(elsewhere).getState());
        assertEquals("an unmarked account is nobody's to take over", Account.State.ENABLED, unmarked.getState());

        assertEquals(alice.getId(), login(MEMBERS_PROVIDER, member("alice@members.example.org", group)));
        assertEquals("the qualified person signs in to the account the reconcile disabled", Account.State.ENABLED, accountOf(alice).getState());
        assertNull(detailsOf(accountOf(alice)).get(OidcAuthManager.DISABLED_BY_DETAIL));
    }

    @Test
    public void reconcileAsksTheSourceByTheLinksSubject() throws Exception {
        members();
        long userId = login(MEMBERS_PROVIDER, member("alice@members.example.org", "regular-members@members.example.org"));
        users.get(userId).setEmail("someone.else@members.example.org");
        List<String> asked = new ArrayList<>();
        doReturn((OidcMembershipSource) (member, group) -> {
            asked.add(member);
            return "alice@members.example.org".equals(member) ? Boolean.FALSE : Boolean.TRUE;
        }).when(manager).membershipSource(any(OidcProviderVO.class));

        manager.reconcile();

        assertEquals("the person is known by the provider's subject, not by an address CloudStack keeps", List.of("alice@members.example.org"), asked);
        assertEquals(Account.State.DISABLED, accountOf(users.get(userId)).getState());
    }

    @Test
    public void signInAndReconcileTakeTurnsOnAnAccount() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        long accountId = users.get(userId).getAccountId();
        Map<String, Boolean> answers = new HashMap<>();
        answers.put("s-1/major", false);
        doReturn(memberships(answers)).when(manager).membershipSource(any(OidcProviderVO.class));
        AtomicBoolean once = new AtomicBoolean();
        List<Thread> reconciles = new ArrayList<>();
        doAnswer(i -> {
            if ((Long) i.getArgument(0) == accountId && OidcAuthManager.DISABLED_BY_DETAIL.equals(i.getArgument(1)) && once.compareAndSet(false, true)) {
                Thread reconcile = new Thread(() -> manager.reconcile());
                reconciles.add(reconcile);
                reconcile.start();
                reconcile.join(500);
            }
            return detail(i.getArgument(0), i.getArgument(1));
        }).when(accountDetailsDao).findDetail(anyLong(), anyString());

        login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertEquals("the reconcile ran while the sign-in decided about the account", 1, reconciles.size());
        reconciles.get(0).join(10000);

        AccountVO account = accountOf(users.get(userId));
        assertEquals(Account.State.DISABLED, account.getState());
        assertEquals("a disabled account keeps the mark that lets a qualified sign-in enable it again", STUDENTS_PROVIDER,
                detailsOf(account).get(OidcAuthManager.DISABLED_BY_DETAIL));
    }

    @Test
    public void emailIsTakenAsPresentedAndReleasedWithinTheDomain() throws Exception {
        students();
        AccountVO other = account("bob", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        UserVO bob = user(other, "bob", "alice@students.example.org", User.Source.UNKNOWN);
        AccountVO elsewhere = account("carol", ROOT, Account.Type.NORMAL, USER_ROLE);
        UserVO carol = user(elsewhere, "carol", "Alice@Students.example.org", User.Source.UNKNOWN);
        MockIdp.Person alice = new MockIdp.Person("s-1").claim("username", "alice").claim("email", "Alice@Students.example.org")
                .claim("name", "Alice").groups("groups", "major");

        long userId = login(STUDENTS_PROVIDER, alice);
        assertEquals("a provider that need not verify addresses is taken at its word, the address as presented", "Alice@Students.example.org",
                argument(createCalls.get(0), 4));
        assertEquals("Alice@Students.example.org", users.get(userId).getEmail());
        assertEquals("another user of the provider's domain holding the address gives it up", "bob@stale.invalid", users.get(bob.getId()).getEmail());
        assertEquals("a user of another domain keeps it", "Alice@Students.example.org", users.get(carol.getId()).getEmail());

        assertRefused(OidcLoginError.IDENTITY, STUDENTS_PROVIDER, new MockIdp.Person("s-2").claim("username", "dave").claim("name", "Dave")
                .groups("groups", "major"));
        assertNull("without an address nobody gets an account", userNamed("dave"));
        assertEquals(1, accountsCreated.get());
    }

    /** @return the last job the manager queued on its executor */
    private Runnable queuedJob() {
        ArgumentCaptor<Runnable> jobs = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, Mockito.atLeastOnce()).execute(jobs.capture());
        return jobs.getValue();
    }

    @Test
    public void disqualificationStopsResourcesOnlyWhileItsDisableHolds() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice", "alumni"));
        AccountVO account = accountOf(users.get(userId));
        assertEquals(Account.State.DISABLED, account.getState());
        assertTrue("the account cleanup task stops what the job does not", account.getNeedsCleanup());
        verify(accountManager, never()).disableAccount(nullable(String.class), nullable(Long.class), anyLong());
        queuedJob().run();
        verify(accountManager, times(1)).stopResourcesOfDisabledAccount(account.getId());
        assertEquals(Account.State.DISABLED, account.getState());
        assertFalse(account.getNeedsCleanup());

        assertEquals(userId, login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice", "alumni"));
        Runnable outrunBySignIn = queuedJob();
        assertEquals(userId, login(STUDENTS_PROVIDER, student("s-1", "alice", "major")));
        outrunBySignIn.run();
        assertEquals("a job whose disable a qualified sign-in lifted does nothing", Account.State.ENABLED, accountOf(users.get(userId)).getState());

        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice", "alumni"));
        Runnable outrunByAdministrator = queuedJob();
        enableAsAdministrator(account);
        outrunByAdministrator.run();
        assertEquals("a job whose disable an administrator lifted does nothing", Account.State.ENABLED, accountOf(users.get(userId)).getState());
        verify(accountManager, times(1)).stopResourcesOfDisabledAccount(anyLong());
        verify(accountManager, never()).disableAccount(nullable(String.class), nullable(Long.class), anyLong());
    }

    /** Runs the administrator's change once, right after the module read the account's mark, as if it committed then. */
    private void afterTheMarkIsRead(AccountVO account, Runnable administrator) {
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(i -> {
            com.cloud.user.AccountDetailVO seen = detail(i.getArgument(0), i.getArgument(1));
            if ((Long) i.getArgument(0) == account.getId() && OidcAuthManager.DISABLED_BY_DETAIL.equals(i.getArgument(1)) && once.compareAndSet(false, true)) {
                administrator.run();
            }
            return seen;
        }).when(accountDetailsDao).findDetail(anyLong(), anyString());
    }

    @Test
    public void resourceStopNeverUndoesAnEnableAfterItsCheck() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        AccountVO account = accountOf(users.get(userId));
        Runnable stop = queuedJob();
        afterTheMarkIsRead(account, () -> enableAsAdministrator(account));

        stop.run();

        assertEquals("an enable committed after the stop read the mark is never undone", Account.State.ENABLED, account.getState());
        verify(accountManager, never()).disableAccount(nullable(String.class), nullable(Long.class), anyLong());
    }

    @Test
    public void reEnableNeverOverridesALockAfterItsCheck() throws Exception {
        students();
        long userId = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertRefused(OidcLoginError.NOT_ADMITTED, STUDENTS_PROVIDER, student("s-1", "alice"));
        AccountVO account = accountOf(users.get(userId));
        afterTheMarkIsRead(account, () -> account.setState(Account.State.LOCKED));

        assertRefused(OidcLoginError.DISABLED, STUDENTS_PROVIDER, student("s-1", "alice", "major"));

        assertEquals("a lock committed after a sign-in read the mark is never overridden", Account.State.LOCKED, account.getState());
    }

    @Test
    public void defaultTrustIsTheRunningJdksCaCertificates() throws Exception {
        java.security.KeyStore authorities = OidcClient.jdkAuthorities();
        assertTrue("the running JDK ships CA certificates", authorities.size() > 0);
        javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init(authorities);
        javax.net.ssl.X509TrustManager x509 = (javax.net.ssl.X509TrustManager) trust.getTrustManagers()[0];
        assertTrue(x509.getAcceptedIssuers().length > 0);
        assertNotNull(OidcClient.httpClient(null));
    }

    @Test
    public void expiredLoginRequestsArePurgedPeriodically() throws Exception {
        students();
        settings.put(OidcAuthManager.LoginTimeout.key(), "600");
        new Browser().begin(STUDENTS_PROVIDER);
        new Browser().begin(STUDENTS_PROVIDER);
        manager.purgeExpiredLoginRequests();
        assertEquals("pending sign-ins stay", 2, requests.size());
        doReturn(java.time.Instant.now().plusSeconds(601)).when(manager).now();
        manager.purgeExpiredLoginRequests();
        assertTrue(requests.isEmpty());
        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(OidcAuthManagerImpl.PURGE_INTERVAL_SECONDS), eq(OidcAuthManagerImpl.PURGE_INTERVAL_SECONDS),
                eq(TimeUnit.SECONDS));
    }

    @Test
    public void reconcileIntervalIsASetting() {
        assertEquals("oidc.reconcile.interval", OidcAuthManager.ReconcileInterval.key());
        assertEquals("3600", OidcAuthManager.ReconcileInterval.defaultValue());
        assertEquals(Long.class, OidcAuthManager.ReconcileInterval.type());
        assertTrue(OidcAuthManager.ReconcileInterval.isDynamic());
        assertEquals(ConfigKey.Scope.Global, OidcAuthManager.ReconcileInterval.getScopes().get(0));
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ConfigKeyScheduledExecutionWrapper runner = manager.reconcileRunner(executor);
        Mockito.doNothing().when(manager).runReconcile();

        settings.put(OidcAuthManager.ReconcileInterval.key(), "0");
        runner.run();
        verify(manager, never()).runReconcile();
        verify(executor).schedule(runner, 60, TimeUnit.SECONDS);

        settings.put(OidcAuthManager.ReconcileInterval.key(), "120");
        runner.run();
        verify(manager).runReconcile();
        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(executor).schedule(eq(runner), delay.capture(), eq(TimeUnit.NANOSECONDS));
        assertTrue(delay.getValue() > TimeUnit.SECONDS.toNanos(110) && delay.getValue() <= TimeUnit.SECONDS.toNanos(120));
    }

    @Test
    public void reconcileRunsOnOneManagementServerAtATime() {
        GlobalLock held = mock(GlobalLock.class);
        when(held.lock(0)).thenReturn(false);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(OidcAuthManagerImpl.RECONCILE_LOCK)).thenReturn(held);
            manager.runReconcile();
            verify(manager, never()).reconcile();
            verify(held).releaseRef();
        }
        GlobalLock free = mock(GlobalLock.class);
        when(free.lock(0)).thenReturn(true);
        doReturn(null).when(manager).membershipSource(any(OidcProviderVO.class));
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(OidcAuthManagerImpl.RECONCILE_LOCK)).thenReturn(free);
            manager.runReconcile();
            verify(manager).reconcile();
            verify(free).unlock();
        }
    }

    // ---------------------------------------------------------------- providers

    private CreateOidcProviderCmd createCmd(String name, long domainId, long roleId) {
        CreateOidcProviderCmd cmd = parameters(CreateOidcProviderCmd.class);
        when(cmd.getName()).thenReturn(name);
        when(cmd.getLabel()).thenReturn("Label " + name);
        when(cmd.getIssuer()).thenReturn("https://id.example/o");
        when(cmd.getClientId()).thenReturn("client");
        when(cmd.getClientSecret()).thenReturn("secret-value");
        when(cmd.getUsernameClaim()).thenReturn("username");
        when(cmd.getUsernamePattern()).thenReturn("[a-z][a-z0-9]{1,19}");
        when(cmd.getAdmission()).thenReturn("{\"groups_claim\":\"groups\",\"allowed_groups\":[\"major\"]}");
        when(cmd.getDomainId()).thenReturn(domainId);
        when(cmd.getRoleId()).thenReturn(roleId);
        return cmd;
    }

    @Test
    public void providerIsCreatedWithItsDefaults() {
        OidcProviderVO provider = manager.createProvider(createCmd("students", STUDENTS, STUDENT_ROLE));
        assertEquals("students", provider.getName());
        assertEquals(OidcProviderVO.DEFAULT_SCOPES, provider.getScopes());
        assertTrue(provider.isJit());
        assertTrue(provider.isEnabled());
        assertFalse(provider.isLowercaseUsername());
        assertEquals("secret-value", provider.getClientSecret());
        assertNotNull(providers.get(provider.getId()));
    }

    @Test
    public void providerConfigurationIsValidated() {
        manager.createProvider(createCmd("students", STUDENTS, STUDENT_ROLE));
        Map<String, Callable<Object>> invalid = new HashMap<>();
        invalid.put("duplicate name", () -> manager.createProvider(createCmd("students", STUDENTS, STUDENT_ROLE)));
        invalid.put("name with capitals", () -> manager.createProvider(createCmd("STUDENTS", STUDENTS, STUDENT_ROLE)));
        invalid.put("unknown domain", () -> manager.createProvider(createCmd("a", 999L, STUDENT_ROLE)));
        invalid.put("unknown role", () -> manager.createProvider(createCmd("b", STUDENTS, 999L)));
        invalid.put("an Admin-type role outside ROOT", () -> manager.createProvider(createCmd("c", STUDENTS, MEMBER_ROLE)));
        invalid.put("plain http issuer", () -> {
            CreateOidcProviderCmd cmd = createCmd("d", STUDENTS, STUDENT_ROLE);
            when(cmd.getIssuer()).thenReturn("http://id.example/o");
            return manager.createProvider(cmd);
        });
        invalid.put("bad pattern", () -> {
            CreateOidcProviderCmd cmd = createCmd("e", STUDENTS, STUDENT_ROLE);
            when(cmd.getUsernamePattern()).thenReturn("[a-z");
            return manager.createProvider(cmd);
        });
        invalid.put("admission without groups", () -> {
            CreateOidcProviderCmd cmd = createCmd("f", STUDENTS, STUDENT_ROLE);
            when(cmd.getAdmission()).thenReturn("{\"groups_claim\":\"groups\",\"allowed_groups\":[]}");
            return manager.createProvider(cmd);
        });
        invalid.put("admission not JSON", () -> {
            CreateOidcProviderCmd cmd = createCmd("g", STUDENTS, STUDENT_ROLE);
            when(cmd.getAdmission()).thenReturn("major");
            return manager.createProvider(cmd);
        });
        invalid.put("authorization parameter overriding the protocol", () -> {
            CreateOidcProviderCmd cmd = createCmd("h", STUDENTS, STUDENT_ROLE);
            when(cmd.getAuthorizeParams()).thenReturn("{\"redirect_uri\":\"https://evil.example/\"}");
            return manager.createProvider(cmd);
        });
        invalid.put("scopes without openid", () -> {
            CreateOidcProviderCmd cmd = createCmd("i", STUDENTS, STUDENT_ROLE);
            when(cmd.getScopes()).thenReturn("profile email");
            return manager.createProvider(cmd);
        });
        invalid.put("end-session link without label", () -> {
            CreateOidcProviderCmd cmd = createCmd("j", STUDENTS, STUDENT_ROLE);
            when(cmd.getEndSessionUrl()).thenReturn("https://id.example/session/end");
            return manager.createProvider(cmd);
        });
        invalid.put("membership source without its key", () -> {
            CreateOidcProviderCmd cmd = createCmd("k", STUDENTS, STUDENT_ROLE);
            when(cmd.getMembershipSource()).thenReturn("{\"type\":\"google-directory\"}");
            return manager.createProvider(cmd);
        });
        invalid.put("unknown membership source", () -> {
            CreateOidcProviderCmd cmd = createCmd("l", STUDENTS, STUDENT_ROLE);
            when(cmd.getMembershipSource()).thenReturn("{\"type\":\"ldap\"}");
            when(cmd.getMembershipSecret()).thenReturn("{}");
            return manager.createProvider(cmd);
        });
        for (Map.Entry<String, Callable<Object>> entry : invalid.entrySet()) {
            assertThrows(entry.getKey(), InvalidParameterValueException.class, () -> entry.getValue().call());
        }
        assertEquals(1, providers.size());
    }

    @Test
    public void providerRoleCannotExceedTheCallersRole() {
        doThrow(new PermissionDeniedException("role above the caller's")).when(accountManager).checkRoleEscalation(eq(caller), any(Account.class));
        assertThrows(PermissionDeniedException.class, () -> manager.createProvider(createCmd("members", ROOT, MEMBER_ROLE)));
        ArgumentCaptor<Account> requested = ArgumentCaptor.forClass(Account.class);
        verify(accountManager).checkRoleEscalation(eq(caller), requested.capture());
        assertEquals(MEMBER_ROLE, (long) requested.getValue().getRoleId());
        assertEquals(Account.Type.ADMIN, requested.getValue().getType());
        assertEquals(ROOT, requested.getValue().getDomainId());
        verify(accountManager).checkAccess(eq(caller), eq(domains.get(ROOT)));
        assertTrue(providers.isEmpty());

        OidcProviderVO students = students();
        UpdateOidcProviderCmd update = parameters(UpdateOidcProviderCmd.class);
        when(update.getId()).thenReturn(students.getId());
        when(update.getLabel()).thenReturn("relabelled");
        doThrow(new PermissionDeniedException("role above the caller's")).when(accountManager).checkRoleEscalation(eq(caller), any(Account.class));
        assertThrows("configuring any part of a provider whose role is above the caller's is refused", PermissionDeniedException.class,
                () -> manager.updateProvider(update));
        assertEquals("STUDENTS", providers.get(students.getId()).getLabel());
    }

    @Test
    public void providerUpdateKeepsWhatIsLeftOutAndClearsWhatIsEmpty() {
        OidcProviderVO provider = students();
        UpdateOidcProviderCmd update = parameters(UpdateOidcProviderCmd.class);
        when(update.getId()).thenReturn(provider.getId());
        when(update.getEndSessionUrl()).thenReturn("");
        when(update.getEndSessionLabel()).thenReturn("");
        when(update.getJit()).thenReturn(false);
        OidcProviderVO updated = manager.updateProvider(update);
        assertNull(updated.getEndSessionUrl());
        assertNull(updated.getEndSessionLabel());
        assertFalse(updated.isJit());
        assertEquals(MockIdp.CLIENT_SECRET, updated.getClientSecret());
        assertEquals("<p>Only majors.</p>", updated.getRefusal());
        assertEquals("students", updated.getName());
    }

    @Test
    public void providerWithUsersIsDisabledNotDeleted() throws Exception {
        OidcProviderVO provider = students();
        long alice = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        assertThrows(InvalidParameterValueException.class, () -> manager.deleteProvider(provider.getId()));
        assertNotNull(providers.get(provider.getId()));
        markRemoved(users.get(alice));
        assertTrue("links of removed users do not hold a provider", manager.deleteProvider(provider.getId()));
        assertTrue(providers.isEmpty());
        assertTrue(links.isEmpty());
    }

    @Test
    public void providerChangesAreAudited() {
        OidcProviderVO provider = manager.createProvider(createCmd("students", STUDENTS, STUDENT_ROLE));
        verify(manager).recordEvent(eq(EventTypes.EVENT_OIDC_PROVIDER_CREATE), Mockito.contains("students"));
        UpdateOidcProviderCmd update = parameters(UpdateOidcProviderCmd.class);
        when(update.getId()).thenReturn(provider.getId());
        when(update.getEnabled()).thenReturn(false);
        manager.updateProvider(update);
        verify(manager).recordEvent(eq(EventTypes.EVENT_OIDC_PROVIDER_UPDATE), Mockito.contains("students"));
        manager.deleteProvider(provider.getId());
        verify(manager).recordEvent(eq(EventTypes.EVENT_OIDC_PROVIDER_DELETE), Mockito.contains("students"));
    }

    @Test
    public void issuerChangeIsRefusedWhileUsersAreLinked() throws Exception {
        OidcProviderVO provider = students();
        long alice = login(STUDENTS_PROVIDER, student("s-1", "alice", "major"));
        UpdateOidcProviderCmd update = parameters(UpdateOidcProviderCmd.class);
        when(update.getId()).thenReturn(provider.getId());
        when(update.getIssuer()).thenReturn(idp.issuer());
        manager.updateProvider(update);

        when(update.getIssuer()).thenReturn("https://other.example/o");
        assertThrows("subjects of one issuer never sign in through another", InvalidParameterValueException.class, () -> manager.updateProvider(update));
        assertEquals(idp.issuer(), providers.get(provider.getId()).getIssuer());

        markRemoved(users.get(alice));
        manager.updateProvider(update);
        assertEquals("https://other.example/o", providers.get(provider.getId()).getIssuer());
        assertTrue("links to the old issuer's subjects go with it", links.isEmpty());
    }

    @Test
    public void providerTlsTrustsTheSystemStoreOrItsOwnAuthority() throws Exception {
        OidcProviderVO provider = students();
        assertNotNull("a provider's own authority is trusted for it", manager.startLogin(STUDENTS_PROVIDER));

        javax.net.ssl.SSLContext previous = javax.net.ssl.SSLContext.getDefault();
        javax.net.ssl.SSLContext.setDefault(idp.sslContext());
        try {
            provider.setCaCertificate(null);
            OidcLoginException e = assertThrows(OidcLoginException.class, () -> manager.startLogin(STUDENTS_PROVIDER));
            assertEquals("authorities added to the JVM's default TLS context are not trusted for providers", OidcLoginError.UNAVAILABLE, e.getError());
        } finally {
            javax.net.ssl.SSLContext.setDefault(previous);
        }

        java.nio.file.Path store = java.nio.file.Files.createTempFile("trust", ".p12");
        java.security.KeyStore trusting = java.security.KeyStore.getInstance("PKCS12");
        trusting.load(null, null);
        trusting.setCertificateEntry("idp", java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(idp.caPem().getBytes(StandardCharsets.US_ASCII))));
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(store)) {
            trusting.store(out, "changeit".toCharArray());
        }
        Map<String, String> properties = new HashMap<>();
        for (String name : List.of("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType")) {
            properties.put(name, System.getProperty(name));
        }
        System.setProperty("javax.net.ssl.trustStore", store.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        try {
            manager.forgetProviderMetadata();
            OidcLoginException e = assertThrows(OidcLoginException.class, () -> manager.startLogin(STUDENTS_PROVIDER));
            assertEquals("a trust store another component names for the whole process is not the providers'", OidcLoginError.UNAVAILABLE, e.getError());
        } finally {
            properties.forEach((name, value) -> {
                if (value == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, value);
                }
            });
            java.nio.file.Files.delete(store);
        }

        CreateOidcProviderCmd cmd = createCmd("other", STUDENTS, STUDENT_ROLE);
        when(cmd.getCaCertificate()).thenReturn("not a certificate");
        assertThrows(InvalidParameterValueException.class, () -> manager.createProvider(cmd));
        when(cmd.getCaCertificate()).thenReturn(idp.caPem());
        assertEquals(idp.caPem().trim(), manager.createProvider(cmd).getCaCertificate());
    }

    @Test
    public void secretsNeverLeaveInResponses() {
        OidcProviderVO provider = members();
        provider.setMembershipSource("{\"type\":\"google-directory\"}");
        provider.setMembershipSecret("{\"private_key\":\"key-material\"}");
        for (boolean details : new boolean[] {false, true}) {
            String json = new com.google.gson.Gson().toJson(manager.createProviderResponse(provider, details));
            assertFalse(json, json.contains(MockIdp.CLIENT_SECRET));
            assertFalse(json, json.contains("key-material"));
        }
    }

    @Test
    public void logoutLinksComeFromProviderFields() {
        OidcProviderVO withLink = students();
        OidcProviderVO withoutLink = members();
        withoutLink.setEndSessionUrl(null);
        withoutLink.setEndSessionLabel(null);
        OidcProviderVO disabled = provider("closed", ROOT, USER_ROLE, "email", ".*", "{\"groups_claim\":\"g\",\"allowed_groups\":[\"x\"]}");
        disabled.setEnabled(false);

        List<OidcProviderVO> shown = manager.listProviders(null, null, true);
        assertEquals(List.of(withLink.getId(), withoutLink.getId()), shown.stream().map(OidcProviderVO::getId).collect(java.util.stream.Collectors.toList()));
        OidcProviderResponse link = manager.createProviderResponse(withLink, false);
        assertEquals("https://id.example/session/end", link.getEndSessionUrl());
        assertEquals("Sign out of Student ID", link.getEndSessionLabel());
        assertEquals("students", link.getName());
        assertEquals("STUDENTS", link.getLabel());
        assertEquals("<p>Only majors.</p>", link.getRefusal());
        assertNull("the public listing shows no configuration", link.getIssuer());
        assertNull(link.getClientId());
        assertNull(link.getAdmission());
        OidcProviderResponse noLink = manager.createProviderResponse(withoutLink, false);
        assertNull(noLink.getEndSessionUrl());
        assertNull(noLink.getEndSessionLabel());

        withLink.setEndSessionUrl("https://id.example/logout");
        assertEquals("https://id.example/logout", manager.createProviderResponse(withLink, false).getEndSessionUrl());
        OidcProviderResponse full = manager.createProviderResponse(withLink, true);
        assertEquals(idp.issuer(), full.getIssuer());
        assertEquals(CLIENT_URL + "/oidc/callback/students", full.getCallbackUrl());
        assertEquals("/students/", full.getDomainPath());
        assertEquals("Student", full.getRoleName());
        assertEquals(3, manager.listProviders(null, null, false).size());
    }

    @Test
    public void publicListingNeedsNoSessionAndShowsWhatTheSignInPageShows() throws Exception {
        students();
        OidcProviderVO closed = members();
        closed.setEnabled(false);
        org.apache.cloudstack.oidc.api.command.ListOidcProvidersCmd cmd = new org.apache.cloudstack.oidc.api.command.ListOidcProvidersCmd();
        cmd.setAuthenticators(List.of(manager));
        Map<String, Object[]> params = new HashMap<>();
        params.put("command", new String[] {"listOidcProviders"});
        String json = cmd.authenticate("listOidcProviders", params, null, java.net.InetAddress.getLoopbackAddress(), "json", new StringBuilder(),
                mock(javax.servlet.http.HttpServletRequest.class), mock(javax.servlet.http.HttpServletResponse.class));
        assertTrue(json, json.contains("\"name\":\"students\""));
        assertTrue(json, json.contains("\"endsessionurl\":\"https://id.example/session/end\""));
        assertTrue(json, json.contains("\"endsessionlabel\":\"Sign out of Student ID\""));
        assertFalse("a disabled provider is not offered", json.contains("members"));
        assertFalse("no configuration without a session", json.contains("clientid") || json.contains("issuer") || json.contains("admission"));
    }

    @Test
    public void signInSettingsAreAccessControlSettings() {
        Set<String> keys = new HashSet<>();
        for (ConfigKey<?> key : manager.getConfigKeys()) {
            keys.add(key.key());
            assertTrue(key.key(), key.isDynamic());
        }
        assertEquals(Set.of("oidc.client.url", "oidc.login.timeout", "oidc.reconcile.interval"), keys);
        assertEquals(Set.of("oidc.client.url", "oidc.reconcile.interval"), Set.of(manager.getAccessControlConfigKeyNames()));
        assertEquals("600", OidcAuthManager.LoginTimeout.defaultValue());
        assertThrows(InvalidParameterValueException.class, () -> OidcAuthManager.validateClientUrl("ftp://cloud.example/client"));
        assertThrows(InvalidParameterValueException.class, () -> OidcAuthManager.validateClientUrl("https://cloud.example/client?next=x"));
        assertThrows("the browser binding needs a secure cookie", InvalidParameterValueException.class,
                () -> OidcAuthManager.validateClientUrl("http://cloud.example/client"));
        OidcAuthManager.validateClientUrl("https://cloud.example/client");
        OidcAuthManager.validateClientUrl("");
    }

    @Test
    public void everyColumnIsInTheSchemaUpgrade() throws Exception {
        String script;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/db/schema-42300to4230100.sql")) {
            assertNotNull(in);
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<Class<?>, String> tables = new HashMap<>();
        tables.put(OidcProviderVO.class, "oidc_provider");
        tables.put(OidcLoginRequestVO.class, "oidc_login_request");
        tables.put(OidcUserLinkVO.class, "oidc_user_link");
        for (Map.Entry<Class<?>, String> entry : tables.entrySet()) {
            int start = script.indexOf("CREATE TABLE IF NOT EXISTS `cloud`.`" + entry.getValue() + "` (");
            assertTrue(entry.getValue(), start >= 0);
            String table = script.substring(start, script.indexOf(") ENGINE=InnoDB", start));
            for (Field field : entry.getKey().getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    assertTrue(entry.getValue() + "." + column.name(), table.contains("`" + column.name() + "` "));
                }
            }
        }
        assertTrue("subjects compare case-sensitively", script.contains("`subject` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"));
    }
}
