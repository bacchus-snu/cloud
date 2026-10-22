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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A provider's admission rule, a JSON object: {@code groups_claim} names the claim listing the person's groups,
 * {@code allowed_groups} the groups any one of which admits, and the optional {@code subject_prefix} the prefix every
 * admitted subject has. The same rule decides at sign-in, from the claims, and in the periodic check, from the
 * provider's membership source.
 */
public final class OidcAdmission {

    static final String GROUPS_CLAIM = "groups_claim";
    static final String ALLOWED_GROUPS = "allowed_groups";
    static final String SUBJECT_PREFIX = "subject_prefix";
    private static final Set<String> FIELDS = Set.of(GROUPS_CLAIM, ALLOWED_GROUPS, SUBJECT_PREFIX);

    private final String groupsClaim;
    private final List<String> allowedGroups;
    private final String subjectPrefix;

    OidcAdmission(String groupsClaim, List<String> allowedGroups, String subjectPrefix) {
        this.groupsClaim = groupsClaim;
        this.allowedGroups = allowedGroups;
        this.subjectPrefix = subjectPrefix;
    }

    /**
     * @throws InvalidParameterValueException if the value is not an admission rule
     */
    public static OidcAdmission parse(String json) {
        JsonObject rule;
        try {
            rule = JsonParser.parseString(StringUtils.defaultString(json)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException("The admission rule must be a JSON object");
        }
        for (String field : rule.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new InvalidParameterValueException(String.format("The admission rule has an unknown field %s", field));
            }
        }
        String groupsClaim = OidcClient.string(rule, GROUPS_CLAIM);
        if (StringUtils.isBlank(groupsClaim)) {
            throw new InvalidParameterValueException(String.format("The admission rule needs %s", GROUPS_CLAIM));
        }
        List<String> allowed = new ArrayList<>();
        JsonElement groups = rule.get(ALLOWED_GROUPS);
        if (groups != null && groups.isJsonArray()) {
            for (JsonElement group : groups.getAsJsonArray()) {
                if (!group.isJsonPrimitive() || StringUtils.isBlank(group.getAsString())) {
                    throw new InvalidParameterValueException(String.format("%s must list group names", ALLOWED_GROUPS));
                }
                allowed.add(group.getAsString());
            }
        }
        if (allowed.isEmpty()) {
            throw new InvalidParameterValueException(String.format("The admission rule needs at least one of %s", ALLOWED_GROUPS));
        }
        JsonElement prefix = rule.get(SUBJECT_PREFIX);
        if (prefix != null && !(prefix.isJsonPrimitive() && prefix.getAsJsonPrimitive().isString())) {
            throw new InvalidParameterValueException(String.format("%s must be a string", SUBJECT_PREFIX));
        }
        return new OidcAdmission(groupsClaim, Collections.unmodifiableList(allowed), prefix == null ? "" : prefix.getAsString());
    }

    public String getGroupsClaim() {
        return groupsClaim;
    }

    public List<String> getAllowedGroups() {
        return allowedGroups;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public boolean admits(Collection<String> groups) {
        return allowedGroups.stream().anyMatch(groups::contains);
    }

    /**
     * @param membership whether the person is a member of a group: true, false, or null when it cannot be told
     * @return true if a member of an allowed group, false if a member of none, null if that cannot be told
     */
    public Boolean admits(Function<String, Boolean> membership) {
        boolean unknown = false;
        for (String group : allowedGroups) {
            Boolean member = membership.apply(group);
            if (Boolean.TRUE.equals(member)) {
                return true;
            }
            unknown |= member == null;
        }
        return unknown ? null : false;
    }
}
