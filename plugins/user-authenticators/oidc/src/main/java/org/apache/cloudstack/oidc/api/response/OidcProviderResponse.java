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
package org.apache.cloudstack.oidc.api.response;

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.oidc.OidcApiConstants;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = OidcProviderVO.class)
public class OidcProviderResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "The ID of the provider")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "The name of the provider, which its sign-in addresses use")
    private String name;

    @SerializedName(ApiConstants.LABEL)
    @Param(description = "The name of the provider shown on the sign-in page")
    private String label;

    @SerializedName(OidcApiConstants.END_SESSION_URL)
    @Param(description = "The provider's end-session address, where a person signs out of the provider itself; absent when the provider has none")
    private String endSessionUrl;

    @SerializedName(OidcApiConstants.END_SESSION_LABEL)
    @Param(description = "The text of the link to the end-session address")
    private String endSessionLabel;

    @SerializedName(OidcApiConstants.REFUSAL)
    @Param(description = "The message, an HTML fragment, for people the provider's admission rule does not admit")
    private String refusal;

    @SerializedName(OidcApiConstants.ISSUER)
    @Param(description = "The issuer identifier of the provider")
    private String issuer;

    @SerializedName(OidcApiConstants.CA_CERTIFICATE)
    @Param(description = "The PEM certificates of the authorities the provider's TLS is checked against, if not the CA certificates the JDK ships")
    private String caCertificate;

    @SerializedName(ApiConstants.CLIENT_ID)
    @Param(description = "The client ID registered at the provider")
    private String clientId;

    @SerializedName("callbackurl")
    @Param(description = "The redirect URI to register at the provider")
    private String callbackUrl;

    @SerializedName(OidcApiConstants.SCOPES)
    @Param(description = "The scopes asked for")
    private String scopes;

    @SerializedName(OidcApiConstants.AUTHORIZE_PARAMS)
    @Param(description = "Extra parameters of the authorization request, a JSON object")
    private String authorizeParams;

    @SerializedName(OidcApiConstants.USERNAME_CLAIM)
    @Param(description = "The claim the username comes from")
    private String usernameClaim;

    @SerializedName(OidcApiConstants.LOWERCASE_USERNAME)
    @Param(description = "Whether the username is lowercased")
    private Boolean lowercaseUsername;

    @SerializedName(OidcApiConstants.USERNAME_PATTERN)
    @Param(description = "The regular expression every username matches in full")
    private String usernamePattern;

    @SerializedName(OidcApiConstants.REQUIRE_VERIFIED_EMAIL)
    @Param(description = "Whether the provider must have verified the e-mail address")
    private Boolean requireVerifiedEmail;

    @SerializedName(OidcApiConstants.ADMISSION)
    @Param(description = "The admission rule, a JSON object")
    private String admission;

    @SerializedName(OidcApiConstants.JIT)
    @Param(description = "Whether an admitted person without an account gets one at the first sign-in")
    private Boolean jit;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "The ID of the domain of the provider's accounts")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN_PATH)
    @Param(description = "The path of the domain of the provider's accounts")
    private String domainPath;

    @SerializedName(ApiConstants.ROLE_ID)
    @Param(description = "The ID of the role of the provider's accounts")
    private String roleId;

    @SerializedName(ApiConstants.ROLE_NAME)
    @Param(description = "The name of the role of the provider's accounts")
    private String roleName;

    @SerializedName(OidcApiConstants.MEMBERSHIP_SOURCE)
    @Param(description = "The membership source of the periodic check, a JSON object; absent when there is none")
    private String membershipSource;

    @SerializedName(ApiConstants.ENABLED)
    @Param(description = "Whether people can sign in with the provider")
    private Boolean enabled;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "When the provider was created")
    private Date created;

    public OidcProviderResponse() {
        setObjectName(OidcApiConstants.OIDC_PROVIDER);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getRefusal() {
        return refusal;
    }

    public void setRefusal(String refusal) {
        this.refusal = refusal;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

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

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

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

    public Boolean getLowercaseUsername() {
        return lowercaseUsername;
    }

    public void setLowercaseUsername(Boolean lowercaseUsername) {
        this.lowercaseUsername = lowercaseUsername;
    }

    public String getUsernamePattern() {
        return usernamePattern;
    }

    public void setUsernamePattern(String usernamePattern) {
        this.usernamePattern = usernamePattern;
    }

    public Boolean getRequireVerifiedEmail() {
        return requireVerifiedEmail;
    }

    public void setRequireVerifiedEmail(Boolean requireVerifiedEmail) {
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    public String getAdmission() {
        return admission;
    }

    public void setAdmission(String admission) {
        this.admission = admission;
    }

    public Boolean getJit() {
        return jit;
    }

    public void setJit(Boolean jit) {
        this.jit = jit;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getDomainPath() {
        return domainPath;
    }

    public void setDomainPath(String domainPath) {
        this.domainPath = domainPath;
    }

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getMembershipSource() {
        return membershipSource;
    }

    public void setMembershipSource(String membershipSource) {
        this.membershipSource = membershipSource;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
