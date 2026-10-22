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
package com.cloud.api.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.LoginCmdResponse;
import org.junit.Before;
import org.junit.Test;

import com.cloud.domain.Domain;
import com.cloud.user.AccountService;
import com.cloud.user.DomainService;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

public class DefaultLoginAPIAuthenticatorCmdTest {

    private final DefaultLoginAPIAuthenticatorCmd cmd = new DefaultLoginAPIAuthenticatorCmd();
    private final ApiServerService apiServer = mock(ApiServerService.class);
    private final AccountService accountService = mock(AccountService.class);
    private final DomainService domainService = mock(DomainService.class);
    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpSession session = mock(HttpSession.class);
    private final Map<String, Object[]> params = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        cmd._apiServer = apiServer;
        cmd._accountService = accountService;
        cmd._domainService = domainService;
        when(req.getMethod()).thenReturn("POST");
        Domain domain = mock(Domain.class);
        when(domain.getId()).thenReturn(5L);
        when(domainService.findDomainByIdOrPath(nullable(Long.class), eq("/students/"))).thenReturn(domain);
        when(apiServer.getSerializedApiError(anyInt(), anyString(), any(), anyString())).thenAnswer(i -> i.getArgument(1));
        params.put("username", new String[] {"alice"});
        params.put("password", new String[] {"pw"});
        params.put("domain", new String[] {"/students/"});
    }

    private UserAccount user(User.Source source) {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(7L);
        when(user.getSource()).thenReturn(source);
        when(accountService.getActiveUserAccount("alice", 5L)).thenReturn(user);
        return user;
    }

    private String login() {
        return cmd.authenticate("login", params, session, InetAddress.getLoopbackAddress(), "json", new StringBuilder(), req, mock(HttpServletResponse.class));
    }

    @Test
    public void passwordLoginRejectedForOauth2User() throws Exception {
        user(User.Source.OAUTH2);
        ServerApiException e = assertThrows(ServerApiException.class, this::login);
        assertEquals(ApiErrorCode.ACCOUNT_ERROR, e.getErrorCode());
        verify(apiServer, never()).loginUser(any(), anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(session).invalidate();
    }

    @Test
    public void passwordLoginOfAPasswordUserGoesOn() throws Exception {
        user(User.Source.UNKNOWN);
        LoginCmdResponse response = new LoginCmdResponse();
        response.setResponseName("loginresponse");
        when(apiServer.loginUser(eq(session), eq("alice"), eq("pw"), eq(5L), eq("/students/"), any(), eq(params))).thenReturn(response);
        login();
        verify(apiServer).loginUser(eq(session), eq("alice"), eq("pw"), eq(5L), eq("/students/"), any(), eq(params));
    }
}
