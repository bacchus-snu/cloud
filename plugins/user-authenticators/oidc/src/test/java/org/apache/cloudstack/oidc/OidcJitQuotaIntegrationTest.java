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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.cloudstack.framework.messagebus.MessageBusBase;
import org.apache.cloudstack.resourcelimit.request.ResourceLimitRequestServiceImpl;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.domain.Domain;
import com.cloud.user.Account;
import com.cloud.user.AccountManagerImpl;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.User;
import com.cloud.user.UserAccountVO;
import com.cloud.user.UserVO;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;

/**
 * A first sign-in creates the account through the generic account-creation path, whose add-account message makes
 * the resource limit request service write the new account's limits from the defaults.
 */
public class OidcJitQuotaIntegrationTest extends OidcTestBase {

    @Test
    @SuppressWarnings("unchecked")
    public void jitRunsRecomputeFromDefaults() throws Exception {
        MessageBusBase bus = new MessageBusBase();
        AccountManagerImpl accountService = spy(AccountManagerImpl.class);
        DomainManager domainManager = mock(DomainManager.class);
        when(domainManager.getDomain(anyLong())).thenAnswer(i -> domains.get((Long) i.getArgument(0)));
        com.cloud.user.dao.UserAccountDao userAccounts = mock(com.cloud.user.dao.UserAccountDao.class);
        when(userAccounts.validateUsernameInDomain(anyString(), anyLong())).thenReturn(true);
        when(userAccounts.findById(anyLong())).thenAnswer(i -> {
            UserVO user = users.get((Long) i.getArgument(0));
            UserAccountVO view = mock(UserAccountVO.class);
            when(view.getId()).thenReturn(user.getId());
            when(view.getAccountId()).thenReturn(user.getAccountId());
            when(view.getUsername()).thenReturn(user.getUsername());
            return view;
        });
        FieldUtils.writeField(accountService, "_domainMgr", domainManager, true);
        FieldUtils.writeField(accountService, "userAccountDao", userAccounts, true);
        FieldUtils.writeField(accountService, "_messageBus", bus, true);
        com.cloud.user.dao.UserDao userRows = mock(com.cloud.user.dao.UserDao.class);
        when(userRows.persist(any(UserVO.class))).thenAnswer(i -> {
            UserVO row = i.getArgument(0);
            setId(row, nextId());
            row.setState(Account.State.ENABLED);
            users.put(row.getId(), row);
            return row;
        });
        FieldUtils.writeField(accountService, "_userDao", userRows, true);
        org.apache.cloudstack.auth.UserAuthenticator encoder = mock(org.apache.cloudstack.auth.UserAuthenticator.class);
        when(encoder.encode(anyString())).thenReturn("encoded");
        FieldUtils.writeField(accountService, "_userPasswordEncoders", java.util.List.of(encoder), true);
        doNothing().when(accountService).checkAccess(any(Account.class), any(Domain.class));
        doNothing().when(accountService).checkRoleEscalation(any(Account.class), any(Account.class));
        doAnswer(i -> account(i.getArgument(0), i.getArgument(3), i.getArgument(1), i.getArgument(2))).when(accountService)
                .createAccount(anyString(), any(Account.Type.class), anyLong(), anyLong(), nullable(String.class), nullable(Map.class), anyString());
        manager.accountManager = accountService;
        doReturn(new UserVO(User.UID_SYSTEM)).when(accountService).getSystemUser();
        AccountVO system = new AccountVO("system", ROOT, null, Account.Type.ADMIN, ADMIN_ROLE, "system-uuid");
        system.setId(Account.ACCOUNT_ID_SYSTEM);
        doReturn(system).when(accountService).getSystemAccount();

        ResourceLimitRequestServiceImpl limits = spy(new ResourceLimitRequestServiceImpl());
        FieldUtils.writeField(limits, "messageBus", bus, true);
        FieldUtils.writeField(limits, "accountDao", accountDao, true);
        FieldUtils.writeField(limits, "sweepExecutor", mock(ScheduledExecutorService.class), true);
        doNothing().when(limits).recompute(any(Account.class));
        limits.start();

        students();
        long userId;
        try (MockedStatic<Transaction> transactions = Mockito.mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(any(TransactionCallback.class)))
                    .thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
            try (TransactionLegacy txn = TransactionLegacy.open("jitRunsRecomputeFromDefaults")) {
                userId = login("students", student("s-1", "alice", "major"));
            }
            transactions.verify(() -> Transaction.execute(any(TransactionCallback.class)), Mockito.times(1));
        }

        AccountVO created = accountOf(users.get(userId));
        assertEquals(User.Source.OAUTH2, users.get(userId).getSource());
        assertEquals(STUDENT_ROLE, (long) created.getRoleId());
        ArgumentCaptor<Account> recomputed = ArgumentCaptor.forClass(Account.class);
        verify(limits).recompute(recomputed.capture());
        assertEquals("the new account's limits are written from the defaults", created.getId(), recomputed.getValue().getId());
    }
}
