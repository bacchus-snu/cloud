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

package org.apache.cloudstack.resourcelimit;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.function.Predicate;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.commons.lang3.math.NumberUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;

/**
 * Resource limits granted on request. An account's limits are a function of its approved, unexpired grants and of the
 * account defaults ({@code max.account.*}), which act as a grant that never expires: per category the larger of the two,
 * never their sum.
 */
public interface ResourceLimitGrantService {

    /** The API that decides requests; the accounts its role rules allow are the approvers. */
    String APPROVE_API = "approveResourceLimitRequest";

    ConfigKey<Boolean> ExpiryRequired = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Boolean.class, "resource.request.expiry.required", "true",
            "Whether a resource limit request and its grant need an expiry date. When false, a grant without one never expires.", true,
            ConfigKey.Scope.Global);

    ConfigKey<Integer> ExpiryDefaultDays = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "resource.request.expiry.default.days", "0",
            "Days from today that the request form proposes as the expiry date; 0 proposes none.", true, ConfigKey.Scope.Global);

    ConfigKey<Integer> ExpiryMaxDays = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "resource.request.expiry.max.days", "0",
            "The latest expiry date of a resource limit request or grant, in days from today; 0 sets no bound.", true, ConfigKey.Scope.Global);

    ConfigKey<String> ExpiryTimezone = new ValidatedConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class, "resource.request.expiry.timezone", "Asia/Seoul",
            "The time zone in which an expiry date of a resource limit grant ends, at 23:59:59 of that date.", true, ConfigKey.Scope.Global, null,
            ResourceLimitGrantService::validateTimezone);

    ConfigKey<String> ExpiryWarningDays = new ValidatedConfigKey<>(String.class, "resource.request.expiry.warning.days", ConfigKey.CATEGORY_ADVANCED, "7,1",
            "Days before the expiry of a resource limit grant at which its account is warned, once each, by an event and an e-mail; empty for no warning.",
            true, ConfigKey.Scope.Global, ConfigKey.Kind.CSV, null, ResourceLimitGrantService::validateWarningDays);

    ConfigKey<Integer> ExpiryCheckInterval = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "resource.request.expiry.check.interval", "3600",
            "Seconds between the runs that expire resource limit grants, rewrite every account's limits from its grants and the account defaults, " +
                    "and send the expiry warnings; 0 stops the runs.", true, ConfigKey.Scope.Global);

    ConfigKey<String> SmtpHost = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class, "resource.request.smtp.host", "",
            "Host of the SMTP relay for resource limit request e-mail; while empty, nothing is sent.", true, ConfigKey.Scope.Global);

    ConfigKey<Integer> SmtpPort = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "resource.request.smtp.port", "25",
            "Port of the SMTP relay for resource limit request e-mail.", true, ConfigKey.Scope.Global);

    ConfigKey<Boolean> SmtpUseAuth = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Boolean.class, "resource.request.smtp.useAuth", "false",
            "Whether the SMTP relay for resource limit request e-mail needs authentication; with it and without resource.request.smtp.useStartTLS, " +
                    "the connection uses TLS from the start.", true, ConfigKey.Scope.Global);

    ConfigKey<String> SmtpUsername = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class, "resource.request.smtp.username", "",
            "User name for the SMTP relay for resource limit request e-mail.", true, ConfigKey.Scope.Global);

    ConfigKey<String> SmtpPassword = new ConfigKey<>("Secure", String.class, "resource.request.smtp.password", "",
            "Password for the SMTP relay for resource limit request e-mail.", true, ConfigKey.Scope.Global);

    ConfigKey<Boolean> SmtpUseStartTLS = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Boolean.class, "resource.request.smtp.useStartTLS", "false",
            "Whether an authenticated connection to the SMTP relay for resource limit request e-mail starts plain and upgrades with STARTTLS.", true,
            ConfigKey.Scope.Global);

    ConfigKey<String> SmtpEnabledSecurityProtocols = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class,
            "resource.request.smtp.enabledSecurityProtocols", "",
            "White-space separated TLS protocols for the SMTP relay for resource limit request e-mail, for example \"TLSv1.2 TLSv1.3\"; empty for the defaults.",
            true, ConfigKey.Scope.Global);

    ConfigKey<String> SmtpSender = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class, "resource.request.smtp.sender", "",
            "Sender address of resource limit request e-mail.", true, ConfigKey.Scope.Global);

    /**
     * Writes the account's resource limits from its approved, unexpired grants and the current account defaults: every
     * untagged limit first, then every limit of each resource limit host tag, so that no tagged limit falls back to an
     * untagged one. Idempotent; it runs in one transaction and is serialised per account. Accounts whose limits can only be
     * unlimited, and project accounts, are left alone.
     */
    void recompute(Account account);

    /**
     * Mails every enabled user of the account that has an e-mail address, through the relay of the
     * {@code resource.request.smtp.*} settings. Without a relay host nothing is sent and one line is logged.
     */
    void notifyAccount(Account account, String subject, String body);

    /**
     * @return whether the account's limits follow its grants, so that it may request them: root admin accounts are
     *         unlimited, and the system account, project accounts and removed accounts have no grants
     */
    static boolean limitsFollowGrants(Account account, Predicate<Account> isRootAdmin) {
        return account != null && account.getRemoved() == null && account.getId() != Account.ACCOUNT_ID_SYSTEM && account.getType() != Account.Type.PROJECT
                && !isRootAdmin.test(account);
    }

    /** @return the latest expiry of a request or grant in days from today, or 0 for no bound */
    static int maxExpiryDays() {
        return Math.max(0, ExpiryMaxDays.value());
    }

    /** @return the days from today that the request form proposes as the expiry, within the bound, or 0 for none */
    static int proposedExpiryDays() {
        int proposed = Math.max(0, ExpiryDefaultDays.value());
        return maxExpiryDays() > 0 ? Math.min(proposed, maxExpiryDays()) : proposed;
    }

    static void validateTimezone(String value) {
        try {
            ZoneId.of(value);
        } catch (DateTimeException | NullPointerException e) {
            throw new InvalidParameterValueException(String.format("%s is not a time zone; use a region name such as UTC or Asia/Seoul", value));
        }
    }

    static void validateWarningDays(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        for (String day : value.split(",")) {
            if (NumberUtils.toInt(day.trim(), 0) <= 0) {
                throw new InvalidParameterValueException(String.format("%s is not a comma-separated list of positive day counts", value));
            }
        }
    }
}
