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
package org.apache.cloudstack.ldap;

import java.lang.reflect.Field;
import java.util.Set;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.command.LdapDeleteConfigurationCmd;
import org.apache.cloudstack.api.command.LinkAccountToLdapCmd;
import org.apache.cloudstack.api.command.LinkDomainToLdapCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.ldap.dao.LdapConfigurationDao;
import org.apache.cloudstack.ldap.dao.LdapTrustMapDao;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;

@RunWith(MockitoJUnitRunner.class)
public class LdapManagerImplTest {

    @Mock
    LdapConfigurationDao ldapConfigurationDao;
    @Mock
    LdapContextFactory ldapContextFactory;
    @Mock
    LdapTrustMapDao ldapTrustMapDao;
    @Mock
    AccountManager accountManager;
    @Mock
    AccountDao accountDao;
    @Mock
    DomainDao domainDao;
    @Mock
    Account caller;
    @Mock
    DomainVO domain;

    @InjectMocks
    LdapManagerImpl ldapManager = new LdapManagerImpl();

    @Test
    public void theDirectoryServerTableIsLeftToRoleRules() throws Exception {
        // a configuration manager the LDAP manager might consult refuses everything
        ConfigurationManager refusing = Mockito.mock(ConfigurationManager.class, invocation -> {
            throw new PermissionDeniedException("refused");
        });
        for (Field field : LdapManagerImpl.class.getDeclaredFields()) {
            if (field.getType() == ConfigurationManager.class) {
                field.setAccessible(true);
                field.set(ldapManager, refusing);
            }
        }
        LdapConfigurationVO server = Mockito.mock(LdapConfigurationVO.class);
        Mockito.when(server.getId()).thenReturn(7L);
        Mockito.when(ldapConfigurationDao.find("ldap.example.org", 389, null)).thenReturn(null, server);
        Mockito.when(ldapConfigurationDao.findById(7L)).thenReturn(server);
        LdapDeleteConfigurationCmd delete = Mockito.mock(LdapDeleteConfigurationCmd.class);
        Mockito.when(delete.getId()).thenReturn(7L);
        CallContext context = Mockito.mock(CallContext.class);

        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            callContext.when(CallContext::current).thenReturn(context);
            ldapManager.addConfiguration("ldap.example.org", 389, null);
            ldapManager.deleteConfiguration("ldap.example.org", 389, null);
            ldapManager.deleteConfiguration(delete);
        }

        Mockito.verify(ldapConfigurationDao).persist(Mockito.any(LdapConfigurationVO.class));
        Mockito.verify(ldapConfigurationDao, Mockito.times(2)).remove(7L);
    }

    private void asCallerWithBaseDn(Runnable call) {
        CallContext context = Mockito.mock(CallContext.class);
        Mockito.when(context.getCallingAccount()).thenReturn(caller);
        try (MockedStatic<LdapConfiguration> configuration = Mockito.mockStatic(LdapConfiguration.class);
             MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            configuration.when(() -> LdapConfiguration.getBaseDn(2L)).thenReturn("dc=example,dc=org");
            callContext.when(CallContext::current).thenReturn(context);
            call.run();
        }
    }

    @Test
    public void linkingADomainRefusesTheAccountsItsCallerCouldNotCreate() {
        LinkDomainToLdapCmd cmd = Mockito.mock(LinkDomainToLdapCmd.class);
        Mockito.when(cmd.getDomainId()).thenReturn(2L);
        Mockito.when(cmd.getLdapDomain()).thenReturn("cn=admins,dc=example,dc=org");
        Mockito.when(cmd.getType()).thenReturn("GROUP");
        Mockito.when(cmd.getAccountType()).thenReturn(Account.Type.DOMAIN_ADMIN);
        Mockito.when(domainDao.findById(2L)).thenReturn(domain);
        Mockito.when(domain.getId()).thenReturn(2L);
        Mockito.doThrow(new PermissionDeniedException("escalation")).when(accountManager).checkRoleEscalation(Mockito.eq(caller), Mockito.any());

        asCallerWithBaseDn(() -> Assert.assertThrows(PermissionDeniedException.class, () -> ldapManager.linkDomainToLdap(cmd)));

        ArgumentCaptor<Account> requested = ArgumentCaptor.forClass(Account.class);
        Mockito.verify(accountManager).checkAccess(caller, domain);
        Mockito.verify(accountManager).checkRoleEscalation(Mockito.eq(caller), requested.capture());
        Assert.assertEquals(Account.Type.DOMAIN_ADMIN, requested.getValue().getType());
        Assert.assertEquals(Long.valueOf(RoleType.DomainAdmin.getId()), requested.getValue().getRoleId());
        Mockito.verifyNoInteractions(ldapTrustMapDao);
    }

    @Test
    public void linkingAnAccountRefusesARoleItsCallerDoesNotHave() {
        LinkAccountToLdapCmd cmd = Mockito.mock(LinkAccountToLdapCmd.class);
        Mockito.when(cmd.getDomainId()).thenReturn(2L);
        Mockito.when(cmd.getAccountName()).thenReturn("admins");
        Mockito.when(cmd.getLdapDomain()).thenReturn("cn=admins,dc=example,dc=org");
        Mockito.when(cmd.getType()).thenReturn("GROUP");
        Mockito.when(cmd.getAccountType()).thenReturn(Account.Type.ADMIN);
        Mockito.when(cmd.getRoleId()).thenReturn(RoleType.Admin.getId());
        Mockito.when(domainDao.findById(2L)).thenReturn(domain);
        Account existing = Mockito.mock(Account.class);
        Mockito.when(accountDao.findActiveAccount("admins", 2L)).thenReturn(null, existing);
        Mockito.doThrow(new PermissionDeniedException("escalation")).when(accountManager).checkRoleEscalation(Mockito.eq(caller), Mockito.any());

        asCallerWithBaseDn(() -> {
            Assert.assertThrows(PermissionDeniedException.class, () -> ldapManager.linkAccountToLdap(cmd));
            Assert.assertThrows(PermissionDeniedException.class, () -> ldapManager.linkAccountToLdap(cmd));
        });

        ArgumentCaptor<Account> requested = ArgumentCaptor.forClass(Account.class);
        Mockito.verify(accountManager, Mockito.times(2)).checkAccess(caller, domain);
        Mockito.verify(accountManager, Mockito.times(2)).checkRoleEscalation(Mockito.eq(caller), requested.capture());
        Assert.assertEquals(Long.valueOf(RoleType.Admin.getId()), requested.getAllValues().get(0).getRoleId());
        Assert.assertSame(existing, requested.getAllValues().get(1));
        Mockito.verify(accountDao, Mockito.never()).persist(Mockito.any(AccountVO.class));
        Mockito.verifyNoInteractions(ldapTrustMapDao);
    }

    @Test
    public void everyLdapSettingButTheTimeoutAndPageSizeGovernsSignIn() {
        LdapConfiguration configuration = new LdapConfiguration();
        Set<String> gated = Set.of(configuration.getAccessControlConfigKeyNames());
        for (ConfigKey<?> key : configuration.getConfigKeys()) {
            boolean operational = key.key().equals("ldap.read.timeout") || key.key().equals("ldap.request.page.size");
            Assert.assertEquals(key.key(), !operational, gated.contains(key.key()));
        }
    }
}
