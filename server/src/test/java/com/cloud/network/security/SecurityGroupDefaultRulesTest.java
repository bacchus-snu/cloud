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
package com.cloud.network.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.security.SecurityRule.SecurityRuleType;
import com.cloud.network.security.dao.SecurityGroupDao;
import com.cloud.network.security.dao.SecurityGroupRuleDao;
import com.cloud.network.security.dao.SecurityGroupVMMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;

public class SecurityGroupDefaultRulesTest {

    private static final String TEMPLATE = "[{\"protocol\":\"tcp\",\"startport\":22,\"endport\":22,\"cidrlist\":[\"10.0.0.5/32\"]},"
            + "{\"protocol\":\"all\",\"owngroup\":true}]";

    private final SecurityGroupManagerImpl manager = spy(new SecurityGroupManagerImpl());
    private final SecurityGroupDao groupDao = mock(SecurityGroupDao.class);
    private final SecurityGroupRuleDao ruleDao = mock(SecurityGroupRuleDao.class);
    private final SecurityGroupVMMapDao vmMapDao = mock(SecurityGroupVMMapDao.class);
    private final AccountDao accountDao = mock(AccountDao.class);
    private final ConfigDepotImpl configDepot = mock(ConfigDepotImpl.class);
    private final Map<String, String> globalSettings = new HashMap<>();
    private final Map<Long, String> domainSettings = new HashMap<>();
    private final Map<Long, SecurityGroupVO> groups = new HashMap<>();
    private final List<SecurityGroupRuleVO> rules = new ArrayList<>();
    private MockedStatic<Transaction> transactions;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        FieldUtils.writeField(manager, "_securityGroupDao", groupDao, true);
        FieldUtils.writeField(manager, "_securityGroupRuleDao", ruleDao, true);
        FieldUtils.writeField(manager, "_securityGroupVMMapDao", vmMapDao, true);
        FieldUtils.writeField(manager, "_accountDao", accountDao, true);
        ConfigKey.init(configDepot);
        when(configDepot.getConfigStringValue(anyString(), any(ConfigKey.Scope.class), nullable(Long.class))).thenAnswer(i ->
                i.getArgument(1) == ConfigKey.Scope.Domain && i.getArgument(2) != null ? domainSettings.get((Long) i.getArgument(2))
                        : globalSettings.get((String) i.getArgument(0)));
        when(groupDao.findByAccountAndName(anyLong(), eq(SecurityGroupManager.DEFAULT_GROUP_NAME))).thenAnswer(i -> groups.get((Long) i.getArgument(0)));
        when(groupDao.acquireInLockTable(anyLong())).thenAnswer(i -> groups.values().stream().filter(g -> g.getId() == (Long) i.getArgument(0)).findFirst()
                .orElse(null));
        doAnswer(i -> {
            SecurityGroupVO group = new SecurityGroupVO(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
            FieldUtils.writeField(group, "id", 1000L + (Long) i.getArgument(3), true);
            groups.put(i.getArgument(3), group);
            return group;
        }).when(manager).createSecurityGroup(anyString(), anyString(), anyLong(), anyLong(), anyString());
        when(ruleDao.persist(any(SecurityGroupRuleVO.class))).thenAnswer(i -> {
            rules.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(ruleDao.listBySecurityGroupId(anyLong(), eq(SecurityRuleType.IngressRule))).thenAnswer(i -> rules.stream()
                .filter(r -> r.getSecurityGroupId() == (Long) i.getArgument(0) && r.getRuleType() == SecurityRuleType.IngressRule)
                .collect(Collectors.toList()));
        when(vmMapDao.listVmIdsBySecurityGroup(anyLong())).thenReturn(List.of(77L));
        doAnswer(i -> null).when(manager).scheduleRulesetUpdateToHosts(any(), anyBoolean(), nullable(Long.class));
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(any(TransactionCallback.class))).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0))
                .doInTransaction(null));
    }

    @After
    public void tearDown() {
        transactions.close();
        ConfigKey.init(null);
    }

    private AccountVO account(long id, long domainId, Account.Type type) {
        AccountVO account = new AccountVO("account-" + id, domainId, null, type, "uuid-" + id);
        account.setId(id);
        when(accountDao.findById(id)).thenReturn(account);
        return account;
    }

    private List<SecurityGroupRuleVO> rulesOf(long accountId) {
        long groupId = groups.get(accountId).getId();
        return rules.stream().filter(r -> r.getSecurityGroupId() == groupId).collect(Collectors.toList());
    }

    private static void assertTemplateRules(List<SecurityGroupRuleVO> rules, long groupId) {
        assertEquals(2, rules.size());
        SecurityGroupRuleVO ssh = rules.stream().filter(r -> "tcp".equals(r.getProtocol())).findFirst().orElseThrow();
        assertEquals(SecurityRuleType.IngressRule, ssh.getRuleType());
        assertEquals(22, ssh.getStartPort());
        assertEquals(22, ssh.getEndPort());
        assertEquals("10.0.0.5/32", ssh.getAllowedSourceIpCidr());
        assertNull(ssh.getAllowedNetworkId());
        SecurityGroupRuleVO own = rules.stream().filter(r -> "all".equals(r.getProtocol())).findFirst().orElseThrow();
        assertEquals(SecurityRuleType.IngressRule, own.getRuleType());
        assertEquals(groupId, (long) own.getAllowedNetworkId());
        assertEquals(0, own.getStartPort());
        assertEquals(0, own.getEndPort());
    }

    @Test
    public void defaultSgTemplateAppliedForNewAccountAndProject() {
        globalSettings.put(SecurityGroupManager.DefaultGroupIngressRules.key(), TEMPLATE);
        account(5L, 2L, Account.Type.NORMAL);
        account(6L, 2L, Account.Type.PROJECT);

        manager.createDefaultSecurityGroup(5L);
        manager.createDefaultSecurityGroup(6L);

        assertTemplateRules(rulesOf(5L), groups.get(5L).getId());
        assertTemplateRules(rulesOf(6L), groups.get(6L).getId());
        manager.createDefaultSecurityGroup(5L);
        assertEquals("an existing group is left as it is", 4, rules.size());
    }

    @Test
    public void templateOfTheAccountsDomainApplies() {
        globalSettings.put(SecurityGroupManager.DefaultGroupIngressRules.key(), TEMPLATE);
        domainSettings.put(3L, "[]");
        account(5L, 3L, Account.Type.NORMAL);
        manager.createDefaultSecurityGroup(5L);
        assertTrue(rulesOf(5L).isEmpty());
    }

    @Test
    public void emptyTemplateAddsNoRule() {
        account(5L, 2L, Account.Type.NORMAL);
        manager.createDefaultSecurityGroup(5L);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void appliedRulesAreReEnsuredAndKeepTheOthers() {
        account(5L, 2L, Account.Type.NORMAL);
        manager.createDefaultSecurityGroup(5L);
        long groupId = groups.get(5L).getId();
        SecurityGroupRuleVO own = new SecurityGroupRuleVO(SecurityRuleType.IngressRule, groupId, 0, 0, "all", groupId);
        SecurityGroupRuleVO web = new SecurityGroupRuleVO(SecurityRuleType.IngressRule, groupId, 80, 80, "tcp", "0.0.0.0/0");
        rules.add(own);
        rules.add(web);
        globalSettings.put(SecurityGroupManager.DefaultGroupIngressRules.key(), TEMPLATE);

        manager.applyDefaultSecurityGroupRules(5L);
        assertEquals(3, rulesOf(5L).size());
        assertTrue(rulesOf(5L).contains(web));
        verify(manager).scheduleRulesetUpdateToHosts(List.of(77L), true, null);

        manager.applyDefaultSecurityGroupRules(5L);
        assertEquals("idempotent", 3, rulesOf(5L).size());

        account(8L, 2L, Account.Type.NORMAL);
        manager.applyDefaultSecurityGroupRules(8L);
        assertTemplateRules(rulesOf(8L), groups.get(8L).getId());
    }

    @Test
    public void unchangedGroupSendsNoRulesetUpdate() {
        globalSettings.put(SecurityGroupManager.DefaultGroupIngressRules.key(), TEMPLATE);
        account(5L, 2L, Account.Type.NORMAL);
        manager.createDefaultSecurityGroup(5L);
        manager.applyDefaultSecurityGroupRules(5L);
        verify(manager, never()).scheduleRulesetUpdateToHosts(any(), anyBoolean(), nullable(Long.class));
    }

    @Test
    public void templateIsValidated() {
        SecurityGroupRuleTemplate.validate("");
        SecurityGroupRuleTemplate.validate(TEMPLATE);
        assertEquals(2, SecurityGroupRuleTemplate.parse(TEMPLATE).size());
        SecurityGroupRuleTemplate.validate("[{\"protocol\":\"icmp\",\"icmptype\":8,\"icmpcode\":0,\"cidrlist\":[\"0.0.0.0/0\",\"::/0\"]}]");
        for (String invalid : List.of("not json", "{}", "[{\"protocol\":\"tcp\",\"cidrlist\":[\"10.0.0.0/8\"]}]",
                "[{\"protocol\":\"tcp\",\"startport\":22,\"endport\":21,\"cidrlist\":[\"10.0.0.0/8\"]}]",
                "[{\"protocol\":\"tcp\",\"startport\":22,\"endport\":22,\"cidrlist\":[\"10.0.0.300/8\"]}]",
                "[{\"protocol\":\"tcp\",\"startport\":22,\"endport\":22}]",
                "[{\"protocol\":\"tcp\",\"startport\":22,\"endport\":22,\"cidrlist\":[\"10.0.0.0/8\"],\"owngroup\":true}]",
                "[{\"protocol\":\"gre\",\"owngroup\":true}]",
                "[{\"protocol\":\"all\",\"startport\":1,\"endport\":2,\"owngroup\":true}]",
                "[{\"protocol\":\"all\",\"owngroup\":true,\"account\":\"other\"}]")) {
            assertThrows(invalid, InvalidParameterValueException.class, () -> SecurityGroupRuleTemplate.validate(invalid));
        }
    }
}
