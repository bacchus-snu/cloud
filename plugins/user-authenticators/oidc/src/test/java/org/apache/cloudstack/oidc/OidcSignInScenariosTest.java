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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.response.LoginCmdResponse;
import org.apache.cloudstack.oidc.api.command.CreateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.command.OidcCallbackCmd;
import org.apache.cloudstack.oidc.api.command.OidcLoginCmd;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;

import com.cloud.exception.CloudAuthenticationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.google.gson.JsonObject;

/**
 * The 27 scenarios of the sign-in service this module replaces, each ported to the module: provider
 * configuration, the answer's binding to the browser and state, ID token and identity rules, the session handoff, the
 * accounts, and the HTTP flow from the start to the web client.
 */
public class OidcSignInScenariosTest extends OidcTestBase {

    /** The browser binding cookie is host-only and per provider: {@code __Host-oidcbinding-<provider>}. */
    private static final String BINDING_PREFIX = "__Host-oidcbinding-";

    private static final class HttpCall {
        final List<String> setCookies = new ArrayList<>();
        final Map<String, String> headers = new HashMap<>();
        String redirect;
        final HttpSession session = mock(HttpSession.class);
    }

    private <T extends APIAuthenticator> T command(T cmd) throws Exception {
        FieldUtils.writeField(cmd, "apiServer", apiServer, true);
        cmd.setAuthenticators(List.of(manager));
        return cmd;
    }

    /**
     * Calls the command as the API servlet does: with the parameters it parsed and, for an answer the callback path
     * forwarded, the provider's query string as the callback path received it.
     */
    private HttpCall call(APIAuthenticator cmd, String command, Map<String, String> params, String cookieHeader, String answerQuery) throws Exception {
        HttpCall call = new HttpCall();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Cookie")).thenReturn(cookieHeader);
        when(req.getAttribute(OidcCallbackServlet.ANSWER_ATTRIBUTE)).thenReturn(answerQuery);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        doAnswer(i -> {
            if ("Set-Cookie".equalsIgnoreCase(i.getArgument(0))) {
                call.setCookies.add(i.getArgument(1));
            } else {
                call.headers.put(i.getArgument(0), i.getArgument(1));
            }
            return null;
        }).when(resp).addHeader(anyString(), anyString());
        doAnswer(i -> {
            call.headers.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(resp).setHeader(anyString(), anyString());
        doAnswer(i -> {
            call.redirect = i.getArgument(0);
            return null;
        }).when(resp).sendRedirect(anyString());
        Map<String, Object[]> apiParams = new HashMap<>();
        apiParams.put("command", new String[] {command});
        params.forEach((k, v) -> apiParams.put(k, new String[] {v}));
        cmd.authenticate(command, apiParams, call.session, InetAddress.getLoopbackAddress(), "json", new StringBuilder(), req, resp);
        return call;
    }

    private HttpCall start(String provider) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("provider", provider);
        return call(command(new OidcLoginCmd()), "oidcLogin", params, null, null);
    }

    private static String bindingCookie(HttpCall start) {
        return start.setCookies.stream().filter(c -> c.startsWith(BINDING_PREFIX)).findFirst().orElse(null);
    }

    private HttpCall callback(String provider, Map<String, String> answer, String cookieHeader) throws Exception {
        return callback(provider, answer, answer, cookieHeader);
    }

    /** @param parsed the answer as the API servlet's parameters carry it after the forward */
    private HttpCall callback(String provider, Map<String, String> answer, Map<String, String> parsed, String cookieHeader) throws Exception {
        Map<String, String> params = new HashMap<>(parsed);
        params.put("provider", provider);
        return call(command(new OidcCallbackCmd()), "oidcCallback", params, cookieHeader, MockIdp.answerQuery(answer));
    }

    private HttpCall flow(String provider, MockIdp.Person person, boolean withCookie) throws Exception {
        HttpCall start = start(provider);
        Map<String, String> answer = idp.authorize(start.redirect, person);
        String cookie = bindingCookie(start).split(";")[0];
        return callback(provider, answer, withCookie ? "JSESSIONID=x; userfullname=A \"B\"; bad\\value=1; " + cookie + "; other=y" : "JSESSIONID=x");
    }

    private LoginCmdResponse loginResponse(String username) {
        LoginCmdResponse login = new LoginCmdResponse();
        login.setSessionKey("k+e/y=");
        login.setUserId("u-1");
        login.setDomainId("d-1");
        login.setType("1");
        login.setUsername(username);
        login.setAccount(username);
        login.setFirstName("M");
        login.setLastName("-");
        login.set2FAenabled("false");
        login.setManagementServerId("ms");
        return login;
    }

    private CreateOidcProviderCmd shipped(String name) {
        CreateOidcProviderCmd cmd = parameters(CreateOidcProviderCmd.class);
        when(cmd.getName()).thenReturn(name);
        when(cmd.getClientSecret()).thenReturn("secret");
        if ("students".equals(name)) {
            when(cmd.getLabel()).thenReturn("Student ID");
            when(cmd.getIssuer()).thenReturn("https://id.example.org/o");
            when(cmd.getClientId()).thenReturn("cloudstack");
            when(cmd.getUsernameClaim()).thenReturn("username");
            when(cmd.getUsernamePattern()).thenReturn("[a-z][a-z0-9]{1,19}");
            when(cmd.getAdmission()).thenReturn("{\"groups_claim\":\"groups\",\"allowed_groups\":[\"major\"]}");
            when(cmd.getRefusal()).thenReturn("<p>majors only; ask the operators</p>");
            when(cmd.getDomainId()).thenReturn(STUDENTS);
            when(cmd.getRoleId()).thenReturn(STUDENT_ROLE);
            when(cmd.getEndSessionUrl()).thenReturn("https://id.example.org/o/session/end");
            when(cmd.getEndSessionLabel()).thenReturn("Sign out of Student ID");
        } else {
            when(cmd.getLabel()).thenReturn("Members");
            when(cmd.getIssuer()).thenReturn("https://members.example.com/");
            when(cmd.getClientId()).thenReturn("member-client");
            when(cmd.getAuthorizeParams()).thenReturn("{\"connection\":\"workspace\"}");
            when(cmd.getUsernameClaim()).thenReturn("email");
            when(cmd.getLowercaseUsername()).thenReturn(true);
            when(cmd.getUsernamePattern()).thenReturn("[a-z0-9._-]+@members\\.example\\.org");
            when(cmd.getRequireVerifiedEmail()).thenReturn(true);
            when(cmd.getAdmission()).thenReturn("{\"groups_claim\":\"https://example.org/groups\",\"allowed_groups\":"
                    + "[\"regular-members@members.example.org\"],\"subject_prefix\":\"workspace|\"}");
            when(cmd.getRefusal()).thenReturn("<p>regular members only</p>");
            when(cmd.getDomainId()).thenReturn(ROOT);
            when(cmd.getRoleId()).thenReturn(MEMBER_ROLE);
        }
        return cmd;
    }

    // ---------------------------------------------------------------- 1-4: providers (doors)

    @Test
    public void repositoryProviders() {
        OidcProviderVO students = manager.createProvider(shipped("students"));
        OidcProviderVO members = manager.createProvider(shipped("members"));
        assertEquals(Set.of("students", "members"), providers.values().stream().map(OidcProviderVO::getName).collect(Collectors.toSet()));
        assertEquals(STUDENTS, students.getDomainId());
        assertEquals(STUDENT_ROLE, students.getRoleId());
        assertEquals(ROOT, members.getDomainId());
        assertEquals(MEMBER_ROLE, members.getRoleId());
        assertEquals("workspace", object(members.getAuthorizeParams()).get("connection").getAsString());
        assertEquals("member-client", members.getClientId());
        assertTrue(students.getRefusal().contains("operators"));
        assertTrue(students.isJit() && members.isJit());
        assertEquals("https://id.example.org/o/session/end", students.getEndSessionUrl());
    }

    @Test
    public void usernameNamespacesAreDisjoint() throws Exception {
        OidcProviderVO students = students();
        OidcProviderVO members = members();
        OidcAdmission studentRule = OidcAdmission.parse(students.getAdmission());
        OidcAdmission memberRule = OidcAdmission.parse(members.getAdmission());
        assertEquals("student100", OidcIdentity.from(students, studentRule, claims("s", "username", "student100")).getUsername());
        assertThrows(OidcLoginException.class, () -> OidcIdentity.from(students, studentRule, claims("s", "username", "alex@members.example.org")));
        assertEquals("alex@members.example.org", OidcIdentity.from(members, memberRule, memberClaims("alex@members.example.org")).getUsername());
        for (String name : List.of("student100", "alex@gmail.com", "x@members.example.org.evil", "x@sub.members.example.org")) {
            OidcLoginException e = assertThrows(name, OidcLoginException.class, () -> OidcIdentity.from(members, memberRule, memberClaims(name)));
            assertEquals(OidcLoginError.IDENTITY, e.getError());
        }
    }

    private static JsonObject claims(String sub, String claim, String value) {
        JsonObject claims = new JsonObject();
        claims.addProperty("sub", sub);
        claims.addProperty(claim, value);
        claims.addProperty("email", "a@students.example.org");
        return claims;
    }

    private static JsonObject memberClaims(String email) {
        JsonObject claims = new JsonObject();
        claims.addProperty("sub", "workspace|" + email);
        claims.addProperty("email", email);
        claims.addProperty("email_verified", true);
        return claims;
    }

    @Test
    public void disabledProviderIsClosed() throws Exception {
        students();
        OidcProviderVO members = members();
        members.setEnabled(false);
        assertEquals(List.of("students"), manager.listProviders(null, null, true).stream().map(OidcProviderVO::getName).collect(Collectors.toList()));
        OidcLoginException e = assertThrows(OidcLoginException.class, () -> manager.startLogin("members"));
        assertEquals(OidcLoginError.UNAVAILABLE, e.getError());
        assertNotNull(manager.startLogin("students"));
    }

    @Test
    public void providerWithoutRequiredFieldsIsRefused() {
        for (String missing : List.of("issuer", "clientid", "clientsecret", "usernameclaim", "usernamepattern", "admission", "label")) {
            CreateOidcProviderCmd cmd = shipped("students");
            switch (missing) {
                case "issuer":
                    when(cmd.getIssuer()).thenReturn(" ");
                    break;
                case "clientid":
                    when(cmd.getClientId()).thenReturn("");
                    break;
                case "clientsecret":
                    when(cmd.getClientSecret()).thenReturn("");
                    break;
                case "usernameclaim":
                    when(cmd.getUsernameClaim()).thenReturn(null);
                    break;
                case "usernamepattern":
                    when(cmd.getUsernamePattern()).thenReturn("");
                    break;
                case "admission":
                    when(cmd.getAdmission()).thenReturn("{}");
                    break;
                default:
                    when(cmd.getLabel()).thenReturn("");
            }
            assertThrows(missing, InvalidParameterValueException.class, () -> manager.createProvider(cmd));
        }
        assertTrue(providers.isEmpty());
    }

    // ---------------------------------------------------------------- 5: cookies of the callback path

    @Test
    public void bindingCookieIsReadLeniently() {
        String header = "userfullname=A \"B\"; bad\\value=1; oidcbinding=abc_-1; x=y";
        assertEquals("abc_-1", OidcLoginCmd.cookie(header, "oidcbinding"));
        assertNull(OidcLoginCmd.cookie("a=b", "oidcbinding"));
        assertNull(OidcLoginCmd.cookie("", "oidcbinding"));
        assertNull(OidcLoginCmd.cookie(null, "oidcbinding"));
    }

    // ---------------------------------------------------------------- 6: state

    @Test
    public void stateRoundTripTamperAndExpiry() throws Exception {
        students();
        Browser good = new Browser().begin("students").signIn(student("s-1", "alice", "major"));
        assertTrue(good.complete("students") > 0);

        Browser tampered = new Browser().begin("students").signIn(student("s-1", "alice", "major"));
        String state = tampered.answer.get("state");
        tampered.answer.put("state", state.substring(0, state.length() - 2) + (state.endsWith("AA") ? "BB" : "AA"));
        assertEquals(OidcLoginError.EXPIRED, assertThrows(OidcLoginException.class, () -> tampered.complete("students")).getError());

        Browser otherBrowser = new Browser().begin("students").signIn(student("s-1", "alice", "major"));
        OidcLoginException e = assertThrows(OidcLoginException.class, () -> manager.completeLogin("students", otherBrowser.answer, "someone-elses-binding"));
        assertEquals(OidcLoginError.EXPIRED, e.getError());

        Map<String, String> garbage = new HashMap<>();
        garbage.put("code", "c");
        garbage.put("state", "garbage");
        assertEquals(OidcLoginError.EXPIRED, assertThrows(OidcLoginException.class, () -> manager.completeLogin("students", garbage, "b")).getError());

        Browser late = new Browser().begin("students").signIn(student("s-1", "alice", "major"));
        org.mockito.Mockito.doReturn(java.time.Instant.now().plusSeconds(OidcAuthManager.LoginTimeout.value() + 1)).when(manager).now();
        assertEquals(OidcLoginError.EXPIRED, assertThrows(OidcLoginException.class, () -> late.complete("students")).getError());
    }

    // ---------------------------------------------------------------- 7: ID token

    @Test
    public void idTokenIssuerAudienceAndExpiry() throws Exception {
        students();
        login("students", student("s-1", "alice", "major"));
        idp.idTokenTamper = claims -> claims.addProperty("iss", "https://other.example/");
        assertRefused(OidcLoginError.FAILED, "students", student("s-1", "alice", "major"));
        idp.idTokenTamper = claims -> claims.addProperty("aud", "c");
        assertRefused(OidcLoginError.FAILED, "students", student("s-1", "alice", "major"));
        idp.idTokenTamper = claims -> claims.addProperty("exp", java.time.Instant.now().getEpochSecond() - 3600);
        assertRefused(OidcLoginError.FAILED, "students", student("s-1", "alice", "major"));
    }

    // ---------------------------------------------------------------- 8-10: identity

    @Test
    public void memberEmailBecomesLowercaseUsername() throws Exception {
        OidcProviderVO members = members();
        JsonObject claims = memberClaims("Alex@members.example.org");
        claims.addProperty("name", "W");
        claims.add("https://example.org/groups", array(List.of("regular-members@members.example.org")));
        OidcIdentity who = OidcIdentity.from(members, OidcAdmission.parse(members.getAdmission()), claims);
        assertEquals("alex@members.example.org", who.getUsername());
        assertEquals("the address is kept as presented", "Alex@members.example.org", who.getEmail());
        assertEquals("W", who.getFirstName());
        assertEquals(Set.of("regular-members@members.example.org"), who.getGroups());
    }

    @Test
    public void identityRefusals() throws Exception {
        OidcProviderVO members = members();
        OidcAdmission rule = OidcAdmission.parse(members.getAdmission());
        JsonObject unverified = memberClaims("x@members.example.org");
        unverified.remove("email_verified");
        JsonObject otherConnection = memberClaims("x@members.example.org");
        otherConnection.addProperty("sub", "other-connection|x");
        JsonObject gmail = memberClaims("x@gmail.com");
        gmail.remove("sub");
        for (JsonObject claims : List.of(unverified, otherConnection, gmail, new JsonObject())) {
            assertEquals(claims.toString(), OidcLoginError.IDENTITY,
                    assertThrows(OidcLoginException.class, () -> OidcIdentity.from(members, rule, claims)).getError());
        }
        OidcProviderVO students = students();
        OidcAdmission studentRule = OidcAdmission.parse(students.getAdmission());
        assertThrows(OidcLoginException.class, () -> OidcIdentity.from(students, studentRule, claims("s", "username", "a@b")));
        JsonObject noAddress = claims("s", "username", "alice");
        noAddress.remove("email");
        assertEquals("an address is required for admission", OidcLoginError.IDENTITY,
                assertThrows(OidcLoginException.class, () -> OidcIdentity.from(students, studentRule, noAddress)).getError());
        JsonObject presented = claims("s", "username", "alice");
        presented.addProperty("email", "Alice@Students.example.org");
        assertEquals("an address the provider need not verify is kept as presented", "Alice@Students.example.org",
                OidcIdentity.from(students, studentRule, presented).getEmail());
    }

    @Test
    public void studentGroupsFromUserinfo() throws Exception {
        students();
        MockIdp.Person alice = new MockIdp.Person("s-1").claim("username", "alice").claim("email", "a@students.example.org").userinfo("groups", "major");
        long userId = login("students", alice);
        assertEquals("alice", users.get(userId).getUsername());
        assertEquals("the display name falls back to the username", "alice", users.get(userId).getFirstname());

        MockIdp.Person mallory = new MockIdp.Person("s-2").claim("username", "mallory").claim("email", "m@students.example.org").userinfo("groups", "major");
        mallory.userinfo.addProperty("sub", "s-1");
        assertRefused("userinfo of another subject is refused", mallory);
    }

    private void assertRefused(String message, MockIdp.Person person) {
        OidcLoginError error = refused("students", person);
        assertNotNull(message, error);
        assertEquals(message, OidcLoginError.FAILED, error);
    }

    // ---------------------------------------------------------------- 11-14: session handoff

    @Test
    public void sessionCookiesMatchTheWebClient() {
        List<String> cookies = OidcLoginCmd.sessionCookies(loginResponse("m@members.example.org"), "/client", true);
        Map<String, List<String>> byName = new HashMap<>();
        for (String cookie : cookies) {
            String[] parts = cookie.split("=", 2);
            byName.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
        }
        assertEquals(List.of("k+e/y=; Path=/client/api; HttpOnly; Secure; SameSite=Lax", "k+e/y=; Path=/client; Secure; SameSite=Lax"), byName.get("sessionkey"));
        assertEquals(List.of("m%40members.example.org; Path=/client; Secure; SameSite=Lax"), byName.get("username"));
        assertEquals("true", byName.get("isSAML").get(0).split(";")[0]);
        assertEquals("false", byName.get("twoFaEnabled").get(0).split(";")[0]);
        assertEquals("M%20-", byName.get("userfullname").get(0).split(";")[0]);
        assertFalse(byName.containsKey("twoFaProvider"));
        assertFalse("the container sets the session cookie", byName.containsKey("JSESSIONID"));
        assertFalse(String.join("\n", OidcLoginCmd.sessionCookies(loginResponse("u"), "/client", false)).contains("Secure"));
    }

    @Test
    public void signInContinuesAtTheFixedClientAddress() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        MockIdp.Person member = member("m@members.example.org", "regular-members@members.example.org");
        HttpCall start = start("members");
        Map<String, String> answer = idp.authorize(start.redirect, member);
        answer.put("next", "https://evil.example/");
        answer.put("redirect", "//evil.example");
        HttpCall done = callback("members", answer, bindingCookie(start).split(";")[0]);
        assertEquals(CLIENT_URL + "/", done.redirect);
        assertEquals("no-store", done.headers.get("Cache-Control"));
        assertTrue(done.setCookies.stream().anyMatch(c -> c.startsWith(BINDING_PREFIX + "members=;") && c.contains("Max-Age=0")
                && c.contains("; Path=/;")));
    }

    @Test
    public void verifiedSessionStartsForTheSignedInUser() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        HttpCall done = flow("members", member("m@members.example.org", "regular-members@members.example.org"), true);
        UserVO member = userNamed("m@members.example.org");
        verify(apiServer).loginVerifiedUser(done.session, member.getId(), InetAddress.getLoopbackAddress());
        assertTrue(done.setCookies.stream().anyMatch(c -> c.startsWith("sessionkey=k+e/y=; Path=/client/api; HttpOnly")));
    }

    @Test
    public void refusedSessionReturnsToTheClientWithTheReason() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class)))
                .thenThrow(new CloudAuthenticationException("outside the allowed CIDRs"));
        HttpCall done = flow("members", member("m@members.example.org", "regular-members@members.example.org"), true);
        assertEquals(CLIENT_URL + "/?oidcerror=refused&provider=members", done.redirect);
        verify(done.session).invalidate();
        assertFalse(done.setCookies.stream().anyMatch(c -> c.startsWith("sessionkey=")));
    }

    // ---------------------------------------------------------------- 15-20: accounts

    @Test
    public void domainIsNeverCreatedImplicitly() {
        CreateOidcProviderCmd cmd = shipped("students");
        when(cmd.getDomainId()).thenReturn(77L);
        assertThrows(InvalidParameterValueException.class, () -> manager.createProvider(cmd));
        students();
        domains.get(STUDENTS).setState(com.cloud.domain.Domain.State.Inactive);
        assertRefused(OidcLoginError.UNAVAILABLE, "students", student("s-1", "alice", "major"));
        assertEquals(0, accountsCreated.get());
        assertEquals(2, domains.size());
    }

    @Test
    public void studentAccountGetsTheRoleThroughAccountCreation() throws Exception {
        students();
        long userId = login("students", student("s-1", "alice", "major"));
        Object[] call = createCalls.get(0);
        assertEquals("alice", argument(call, 0));
        assertEquals("alice@students.example.org", argument(call, 4));
        assertEquals("alice", argument(call, 6));
        assertEquals(Account.Type.NORMAL, argument(call, 7));
        assertEquals(STUDENT_ROLE, (long) argument(call, 8));
        assertEquals(STUDENTS, (long) argument(call, 9));
        assertEquals(User.Source.OAUTH2, argument(call, 14));
        assertEquals(STUDENTS, accountOf(users.get(userId)).getDomainId());
    }

    @Test
    public void memberAccountIsAnAdminTypeAccountInRoot() throws Exception {
        members();
        long userId = login("members", member("m@members.example.org", "regular-members@members.example.org"));
        AccountVO account = accountOf(users.get(userId));
        assertEquals(Account.Type.ADMIN, account.getType());
        assertEquals(MEMBER_ROLE, (long) account.getRoleId());
        assertEquals(ROOT, account.getDomainId());
    }

    @Test
    public void disabledUserIsRefused() throws Exception {
        students();
        long userId = login("students", student("s-1", "alice", "major"));
        users.get(userId).setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.DISABLED, "students", student("s-1", "alice", "major"));
        assertEquals(Account.State.DISABLED, users.get(userId).getState());
    }

    @Test
    public void noPasswordIsWrittenAtSignIn() throws Exception {
        students();
        long alice = login("students", student("s-1", "alice", "major"));
        String password = users.get(alice).getPassword();
        login("students", student("s-1", "alice", "major"));
        login("students", student("s-2", "bob", "major"));
        assertEquals(password, users.get(alice).getPassword());
        assertNotEquals("each account gets its own random password", argument(createCalls.get(0), 1), argument(createCalls.get(1), 1));
        verify(accountManager, never()).updateUser(any(org.apache.cloudstack.api.command.admin.user.UpdateUserCmd.class));
    }

    @Test
    public void notAdmittedDisablesOnlyAccountsOfTheProvider() throws Exception {
        students();
        AccountVO unlinked = account("alice", STUDENTS, Account.Type.NORMAL, STUDENT_ROLE);
        user(unlinked, "alice", "alice@students.example.org", User.Source.UNKNOWN);
        assertRefused(OidcLoginError.NOT_ADMITTED, "students", student("s-1", "alice", "alumni"));
        assertEquals("an account neither linked nor marked is not the provider's", Account.State.ENABLED, unlinked.getState());
        assertTrue(links.isEmpty());

        long bob = login("students", student("s-2", "bob", "major"));
        accountOf(users.get(bob)).setState(Account.State.DISABLED);
        assertRefused(OidcLoginError.NOT_ADMITTED, "students", student("s-2", "bob", "alumni"));
        assertNull("an administrator's disable gets no mark", detailsOf(accountOf(users.get(bob))).get(OidcAuthManager.DISABLED_BY_DETAIL));
        verify(accountManager, never()).disableAccount(eq(null), eq(null), anyLong());
    }

    // ---------------------------------------------------------------- 21-27: the HTTP flow

    @Test
    public void signInStartsAtTheProvider() throws Exception {
        members();
        HttpCall start = start("members");
        URI authorization = URI.create(start.redirect);
        assertTrue(start.redirect.startsWith(idp.issuer() + "/authorize?"));
        Map<String, String> params = MockIdp.query(authorization.getRawQuery());
        assertEquals("workspace", params.get("connection"));
        assertEquals(MockIdp.CLIENT_ID, params.get("client_id"));
        assertEquals(CLIENT_URL + "/oidc/callback/members", params.get("redirect_uri"));
        assertEquals("code", params.get("response_type"));
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals(OidcProviderVO.DEFAULT_SCOPES, params.get("scope"));
        assertTrue(params.get("state").length() >= 43);
        assertTrue(params.get("nonce").length() >= 43);
        String cookie = bindingCookie(start);
        assertNotNull(cookie);
        assertTrue(cookie, cookie.startsWith(BINDING_PREFIX + "members=") && cookie.endsWith("; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=Lax"));
        assertEquals("no-store", start.headers.get("Cache-Control"));
        assertFalse("the database keeps no state or binding value", requests.values().iterator().next().getStateHash().equals(params.get("state")));
    }

    @Test
    public void memberGetsASession() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        HttpCall done = flow("members", member("m@members.example.org", "regular-members@members.example.org"), true);
        assertEquals(CLIENT_URL + "/", done.redirect);
        assertTrue(done.setCookies.stream().anyMatch(c -> c.startsWith("sessionkey=")));
        assertTrue(done.setCookies.stream().anyMatch(c -> c.startsWith(BINDING_PREFIX + "members=;") && c.contains("Max-Age=0")));
        UserVO member = userNamed("m@members.example.org");
        assertEquals("m@members.example.org", member.getEmail());
    }

    @Test
    public void callbackFromAnotherBrowserIsRefused() throws Exception {
        members();
        HttpCall done = flow("members", member("m@members.example.org", "regular-members@members.example.org"), false);
        assertEquals(CLIENT_URL + "/?oidcerror=expired&provider=members", done.redirect);
        verify(apiServer, never()).loginVerifiedUser(any(), anyLong(), any());
        assertEquals("the code is never redeemed", 0, idp.tokenRequests.get());
    }

    @Test
    public void stateOfAnotherProviderIsRefused() throws Exception {
        students();
        members();
        HttpCall start = start("students");
        Map<String, String> answer = idp.authorize(start.redirect, member("m@members.example.org", "regular-members@members.example.org"));
        HttpCall done = callback("members", answer, bindingCookie(start).split(";")[0]);
        assertEquals(CLIENT_URL + "/?oidcerror=expired&provider=members", done.redirect);
        verify(apiServer, never()).loginVerifiedUser(any(), anyLong(), any());
    }

    @Test
    public void outsideTheGroupIsDisabledAndRefused() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        flow("members", member("m@members.example.org", "regular-members@members.example.org"), true);
        HttpCall refused = flow("members", member("m@members.example.org", "alumni-members@members.example.org"), true);
        assertEquals(CLIENT_URL + "/?oidcerror=notadmitted&provider=members", refused.redirect);
        assertEquals(Account.State.DISABLED, accountOf(userNamed("m@members.example.org")).getState());
        assertTrue("the refusal message is the provider's", manager.createProviderResponse(manager.listProviders(null, "members", true).get(0), false)
                .getRefusal().contains("regular members"));
    }

    @Test
    public void closedProviderSaysSo() throws Exception {
        students();
        OidcProviderVO members = members();
        members.setEnabled(false);
        assertEquals(CLIENT_URL + "/?oidcerror=unavailable&provider=members", start("members").redirect);
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("code", "c");
        answer.put("state", "s");
        assertEquals(CLIENT_URL + "/?oidcerror=unavailable&provider=members", callback("members", answer, "oidcbinding=b").redirect);
        assertFalse(manager.listProviders(null, null, true).stream().anyMatch(p -> p.getName().equals("members")));
        assertTrue(start("students").redirect.startsWith(idp.issuer() + "/authorize?"));
    }

    @Test
    public void unknownPaths() throws Exception {
        students();
        assertEquals(CLIENT_URL + "/?oidcerror=unavailable&provider=nope", start("nope").redirect);
        assertEquals("a name that is no provider name is not echoed", CLIENT_URL + "/?oidcerror=unavailable", start("Evil; Domain=x").redirect);

        OidcCallbackServlet servlet = new OidcCallbackServlet();
        for (String path : new String[] {null, "", "/", "/students/extra", "/STUDENTS", "/two words", "/../api"}) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getPathInfo()).thenReturn(path);
            HttpServletResponse resp = mock(HttpServletResponse.class);
            servlet.doGet(req, resp);
            verify(resp).sendError(HttpServletResponse.SC_NOT_FOUND);
            verify(req, never()).getRequestDispatcher(anyString());
        }
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getPathInfo()).thenReturn("/students");
        when(req.getQueryString()).thenReturn("code=a%2Bb%26c&state=s");
        RequestDispatcher dispatcher = mock(RequestDispatcher.class);
        when(req.getRequestDispatcher("/api?command=oidcCallback&provider=students")).thenReturn(dispatcher);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        servlet.doGet(req, resp);
        verify(req).setAttribute(OidcCallbackServlet.ANSWER_ATTRIBUTE, "code=a%2Bb%26c&state=s");
        verify(dispatcher).forward(req, resp);
    }

    // ---------------------------------------------------------------- sessions and the answer's transport

    @Test
    public void signInStartKeepsNoSession() throws Exception {
        members();
        assertEquals("starting a sign-in is not a login", APIAuthenticationType.READONLY_API, new OidcLoginCmd().getAPIType());
        assertEquals(APIAuthenticationType.LOGIN_API, new OidcCallbackCmd().getAPIType());
        HttpCall start = start("members");
        assertTrue(start.redirect.startsWith(idp.issuer() + "/authorize?"));
        verifyNoInteractions(start.session);
        HttpCall closed = start("nope");
        assertEquals(CLIENT_URL + "/?oidcerror=unavailable&provider=nope", closed.redirect);
        verifyNoInteractions(closed.session);
    }

    @Test
    public void bindingCookieIsHostOnly() throws Exception {
        students();
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        MockIdp.Person member = member("m@members.example.org", "regular-members@members.example.org");
        HttpCall start = start("members");
        String cookie = bindingCookie(start);
        assertFalse("no other host may set or receive it", cookie.toLowerCase().contains("domain="));
        String binding = cookie.split(";")[0].substring((BINDING_PREFIX + "members=").length());

        Map<String, String> answer = idp.authorize(start.redirect, member);
        HttpCall planted = callback("members", answer, "oidcbinding=" + binding);
        assertEquals("a binding under a name any host of the site can set is not the browser's", CLIENT_URL + "/?oidcerror=expired&provider=members",
                planted.redirect);

        start = start("members");
        binding = bindingCookie(start).split(";")[0].substring((BINDING_PREFIX + "members=").length());
        answer = idp.authorize(start.redirect, member);
        HttpCall otherProvider = callback("members", answer, BINDING_PREFIX + "students=" + binding);
        assertEquals("each provider has its own binding", CLIENT_URL + "/?oidcerror=expired&provider=members", otherProvider.redirect);

        start = start("members");
        answer = idp.authorize(start.redirect, member);
        assertEquals(CLIENT_URL + "/", callback("members", answer, bindingCookie(start).split(";")[0]).redirect);
        verify(apiServer, times(1)).loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class));
    }

    @Test
    public void callbackKeepsTheProvidersAnswerVerbatim() throws Exception {
        members();
        when(apiServer.loginVerifiedUser(any(HttpSession.class), anyLong(), any(InetAddress.class))).thenReturn(loginResponse("m@members.example.org"));
        idp.codeSuffix = "+a&b=c d%e/";
        HttpCall start = start("members");
        Map<String, String> answer = idp.authorize(start.redirect, member("m@members.example.org", "regular-members@members.example.org"));
        Map<String, String> reparsed = new HashMap<>(answer);
        reparsed.put("code", answer.get("code").replace("+", " ").split("&")[0]);
        HttpCall done = callback("members", answer, reparsed, bindingCookie(start).split(";")[0]);
        assertEquals("the code reaches the token endpoint as the provider issued it", CLIENT_URL + "/", done.redirect);
    }
}
