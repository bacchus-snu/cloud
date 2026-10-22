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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Who signed in, read from verified claims by the provider's rules.
 */
public final class OidcIdentity {

    /** Last name of accounts whose provider gives only a full name; accounts need a non-empty one. */
    static final String NO_LAST_NAME = "-";

    private final String subject;
    private final String username;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final Set<String> groups;

    OidcIdentity(String subject, String username, String email, String firstName, String lastName, Set<String> groups) {
        this.subject = subject;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.groups = groups;
    }

    private static OidcLoginException refused(OidcProviderVO provider, String reason) {
        return new OidcLoginException(OidcLoginError.IDENTITY, String.format("%s: %s", provider.getName(), reason));
    }

    /**
     * @throws OidcLoginException with {@link OidcLoginError#IDENTITY} when the claims give no subject, username or
     *         e-mail address, the username does not match the provider's pattern, the provider requires a verified
     *         address and it is not, or the subject lacks the admission rule's prefix
     */
    public static OidcIdentity from(OidcProviderVO provider, OidcAdmission admission, JsonObject claims) throws OidcLoginException {
        String subject = OidcClient.string(claims, "sub");
        String username = StringUtils.trimToEmpty(OidcClient.string(claims, provider.getUsernameClaim()));
        if (provider.isLowercaseUsername()) {
            username = username.toLowerCase(Locale.ROOT);
        }
        String email = StringUtils.trimToEmpty(OidcClient.string(claims, "email"));
        if (StringUtils.isEmpty(subject) || username.isEmpty() || email.isEmpty()) {
            throw refused(provider, "the claims give no subject, username or e-mail address");
        }
        if (!Pattern.compile(provider.getUsernamePattern()).matcher(username).matches()) {
            throw refused(provider, String.format("username %s does not match the provider's pattern", OidcAuthManagerImpl.printable(username)));
        }
        if (provider.isRequireVerifiedEmail()) {
            JsonElement verified = claims.get("email_verified");
            if (verified == null || !verified.isJsonPrimitive() || !verified.getAsJsonPrimitive().isBoolean() || !verified.getAsBoolean()) {
                throw refused(provider, String.format("e-mail address of %s is not verified", username));
            }
        }
        if (!subject.startsWith(admission.getSubjectPrefix())) {
            throw refused(provider, String.format("subject of %s lacks the prefix %s", username, admission.getSubjectPrefix()));
        }
        String givenName = StringUtils.trimToNull(OidcClient.string(claims, "given_name"));
        String familyName = StringUtils.trimToNull(OidcClient.string(claims, "family_name"));
        String firstName;
        String lastName;
        if (givenName != null && familyName != null) {
            firstName = givenName;
            lastName = familyName;
        } else {
            firstName = StringUtils.defaultIfBlank(StringUtils.trimToNull(OidcClient.string(claims, "name")), username);
            lastName = NO_LAST_NAME;
        }
        return new OidcIdentity(subject, username, email, firstName, lastName, groups(claims.get(admission.getGroupsClaim())));
    }

    private static Set<String> groups(JsonElement claim) {
        Set<String> groups = new LinkedHashSet<>();
        if (claim == null) {
            return groups;
        }
        if (claim.isJsonArray()) {
            for (JsonElement group : claim.getAsJsonArray()) {
                if (group.isJsonPrimitive()) {
                    groups.add(group.getAsString());
                }
            }
        } else if (claim.isJsonPrimitive()) {
            groups.add(claim.getAsString());
        }
        return Collections.unmodifiableSet(groups);
    }

    public String getSubject() {
        return subject;
    }

    public String getUsername() {
        return username;
    }

    /** @return the address as the provider presents it, not case-folded */
    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Set<String> getGroups() {
        return groups;
    }
}
