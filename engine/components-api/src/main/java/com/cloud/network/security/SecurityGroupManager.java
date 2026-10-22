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

import java.util.HashMap;
import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;

import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;

/**
 * Ensures that network firewall rules stay updated as VMs go up and down
 *
 */
public interface SecurityGroupManager {

    public static final String DEFAULT_GROUP_NAME = "default";
    public static final String DEFAULT_GROUP_DESCRIPTION = "Default Security Group";
    public static final int TIME_BETWEEN_CLEANUPS = 60;
    public static final int WORKER_THREAD_COUNT = 10;

    ConfigKey<String> DefaultGroupIngressRules = new ValidatedConfigKey<>(ConfigKey.CATEGORY_NETWORK, String.class,
            "network.securitygroups.default.ingress.rules", "",
            "Ingress rules every new default security group gets, accounts and projects alike: a JSON array of rules, each with " +
                    "\"protocol\" (tcp, udp, icmp, all or a protocol number), \"startport\" and \"endport\" for tcp and udp, \"icmptype\" and " +
                    "\"icmpcode\" for icmp, and either \"cidrlist\" (an array of CIDRs) or \"owngroup\": true, which admits the traffic of the " +
                    "account's own default group. Empty for no rules.",
            true, ConfigKey.Scope.Domain, null, SecurityGroupRuleTemplate::validate);

    public SecurityGroupVO createSecurityGroup(String name, String description, Long domainId, Long accountId, String accountName);

    /**
     * Creates the account's default security group if it has none. A new group gets the rules of
     * {@link #DefaultGroupIngressRules} for the account's domain.
     */
    public SecurityGroupVO createDefaultSecurityGroup(Long accountId);

    /**
     * Adds to the account's default security group, creating it if needed, the rules of {@link #DefaultGroupIngressRules}
     * that it lacks; rules the group has beyond them are kept. Idempotent.
     */
    void applyDefaultSecurityGroupRules(long accountId);

    public boolean addInstanceToGroups(UserVm userVm, List<Long> groups);

    public void removeInstanceFromGroups(UserVm userVm);

    public void fullSync(long agentId, HashMap<String, Pair<Long, Long>> newGroupStates);

    public String getSecurityGroupsNamesForVm(long vmId);

    public List<SecurityGroupVO> getSecurityGroupsForVm(long vmId);

    public boolean isVmSecurityGroupEnabled(Long vmId);

    SecurityGroup getDefaultSecurityGroup(long accountId);

    SecurityGroup getSecurityGroup(String name, long accountId);

    boolean isVmMappedToDefaultSecurityGroup(long vmId);

    void scheduleRulesetUpdateToHosts(List<Long> affectedVms, boolean updateSeqno, Long delayMs);
}
