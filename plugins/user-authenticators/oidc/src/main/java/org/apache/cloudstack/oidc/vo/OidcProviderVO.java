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
package org.apache.cloudstack.oidc.vo;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.Identity;
import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.db.Encrypt;
import com.cloud.utils.db.GenericDao;

/**
 * An OpenID provider people sign in with, and the one domain and role its admitted users' accounts get.
 */
@Entity
@Table(name = "oidc_provider")
public class OidcProviderVO implements Identity, InternalIdentity {

    public static final String DEFAULT_SCOPES = "openid profile email";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "label")
    private String label;

    @Column(name = "issuer")
    private String issuer;

    @Column(name = "ca_certificate", length = 65535)
    private String caCertificate;

    @Column(name = "client_id")
    private String clientId;

    @Encrypt
    @Column(name = "client_secret")
    private String clientSecret;

    @Column(name = "scopes")
    private String scopes = DEFAULT_SCOPES;

    @Column(name = "authorize_params")
    private String authorizeParams;

    @Column(name = "username_claim")
    private String usernameClaim;

    @Column(name = "lowercase_username")
    private boolean lowercaseUsername;

    @Column(name = "username_pattern")
    private String usernamePattern;

    @Column(name = "require_verified_email")
    private boolean requireVerifiedEmail;

    @Column(name = "admission")
    private String admission;

    @Column(name = "refusal")
    private String refusal;

    @Column(name = "jit")
    private boolean jit = true;

    @Column(name = "end_session_url")
    private String endSessionUrl;

    @Column(name = "end_session_label")
    private String endSessionLabel;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "role_id")
    private long roleId;

    @Column(name = "membership_source")
    private String membershipSource;

    @Encrypt
    @Column(name = "membership_secret")
    private String membershipSecret;

    @Column(name = "enabled")
    private boolean enabled = true;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    public OidcProviderVO() {
        uuid = UUID.randomUUID().toString();
    }

    /** A detached copy, to change and check before it replaces the stored row. */
    public OidcProviderVO(OidcProviderVO other) {
        id = other.id;
        uuid = other.uuid;
        name = other.name;
        label = other.label;
        issuer = other.issuer;
        caCertificate = other.caCertificate;
        clientId = other.clientId;
        clientSecret = other.clientSecret;
        scopes = other.scopes;
        authorizeParams = other.authorizeParams;
        usernameClaim = other.usernameClaim;
        lowercaseUsername = other.lowercaseUsername;
        usernamePattern = other.usernamePattern;
        requireVerifiedEmail = other.requireVerifiedEmail;
        admission = other.admission;
        refusal = other.refusal;
        jit = other.jit;
        endSessionUrl = other.endSessionUrl;
        endSessionLabel = other.endSessionLabel;
        domainId = other.domainId;
        roleId = other.roleId;
        membershipSource = other.membershipSource;
        membershipSecret = other.membershipSecret;
        enabled = other.enabled;
        created = other.created;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** @return the PEM certificates of the authorities the provider's TLS is checked against instead of the JDK's, if any */
    public String getCaCertificate() {
        return caCertificate;
    }

    public void setCaCertificate(String caCertificate) {
        this.caCertificate = caCertificate;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    /** @return a JSON object of extra parameters of the authorization request, or null */
    public String getAuthorizeParams() {
        return authorizeParams;
    }

    public void setAuthorizeParams(String authorizeParams) {
        this.authorizeParams = authorizeParams;
    }

    public String getUsernameClaim() {
        return usernameClaim;
    }

    public void setUsernameClaim(String usernameClaim) {
        this.usernameClaim = usernameClaim;
    }

    public boolean isLowercaseUsername() {
        return lowercaseUsername;
    }

    public void setLowercaseUsername(boolean lowercaseUsername) {
        this.lowercaseUsername = lowercaseUsername;
    }

    public String getUsernamePattern() {
        return usernamePattern;
    }

    public void setUsernamePattern(String usernamePattern) {
        this.usernamePattern = usernamePattern;
    }

    public boolean isRequireVerifiedEmail() {
        return requireVerifiedEmail;
    }

    public void setRequireVerifiedEmail(boolean requireVerifiedEmail) {
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    /** @return the admission rule as a JSON object, see {@link org.apache.cloudstack.oidc.OidcAdmission} */
    public String getAdmission() {
        return admission;
    }

    public void setAdmission(String admission) {
        this.admission = admission;
    }

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    public boolean isJit() {
        return jit;
    }

    public void setJit(boolean jit) {
        this.jit = jit;
    }

    public String getEndSessionUrl() {
        return endSessionUrl;
    }

    public void setEndSessionUrl(String endSessionUrl) {
        this.endSessionUrl = endSessionUrl;
    }

    public String getEndSessionLabel() {
        return endSessionLabel;
    }

    public void setEndSessionLabel(String endSessionLabel) {
        this.endSessionLabel = endSessionLabel;
    }

    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    public long getRoleId() {
        return roleId;
    }

    public void setRoleId(long roleId) {
        this.roleId = roleId;
    }

    /** @return the membership source as a JSON object, or null for none */
    public String getMembershipSource() {
        return membershipSource;
    }

    public void setMembershipSource(String membershipSource) {
        this.membershipSource = membershipSource;
    }

    public String getMembershipSecret() {
        return membershipSecret;
    }

    public void setMembershipSecret(String membershipSecret) {
        this.membershipSecret = membershipSecret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Date getCreated() {
        return created;
    }

    @Override
    public String toString() {
        return String.format("OIDC provider %s [%s]", name, uuid);
    }
}
