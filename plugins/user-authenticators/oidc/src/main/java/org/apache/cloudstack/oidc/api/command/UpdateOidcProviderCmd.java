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
package org.apache.cloudstack.oidc.api.command;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.RoleResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.oidc.OidcApiConstants;
import org.apache.cloudstack.oidc.OidcAuthManager;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;

@APICommand(name = "updateOidcProvider", description = "Changes an OpenID provider; parameters left out keep their values, and an empty value removes an "
        + "optional one. Accounts keep their domain and username; the role of each follows at its next sign-in",
        responseObject = OidcProviderResponse.class, entityType = {}, requestHasSensitiveInfo = true, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin}, since = "4.23.0.100")
public class UpdateOidcProviderCmd extends BaseCmd {

    @Inject
    OidcAuthManager oidcAuthManager;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = OidcProviderResponse.class, required = true, description = "The provider")
    private Long id;

    @Parameter(name = ApiConstants.LABEL, type = CommandType.STRING, description = "Name of the provider shown on the sign-in page")
    private String label;

    @Parameter(name = OidcApiConstants.ISSUER, type = CommandType.STRING, length = 1024,
            description = "Issuer identifier of the provider, an https URL; it changes only while no user signs in with the provider")
    private String issuer;

    @Parameter(name = OidcApiConstants.CA_CERTIFICATE, type = CommandType.STRING, length = 65535,
            description = "PEM certificates of the authorities the provider's TLS is checked against instead of the CA certificates the JDK ships; "
                    + "empty for the JDK's")
    private String caCertificate;

    @Parameter(name = ApiConstants.CLIENT_ID, type = CommandType.STRING, description = "Client ID registered at the provider")
    private String clientId;

    @Parameter(name = OidcApiConstants.CLIENT_SECRET, type = CommandType.STRING, length = 1024, description = "Client secret")
    private String clientSecret;

    @Parameter(name = OidcApiConstants.SCOPES, type = CommandType.STRING, description = "Scopes to ask for, with openid")
    private String scopes;

    @Parameter(name = OidcApiConstants.AUTHORIZE_PARAMS, type = CommandType.STRING, length = 4096,
            description = "Extra parameters of the authorization request, a JSON object of strings")
    private String authorizeParams;

    @Parameter(name = OidcApiConstants.USERNAME_CLAIM, type = CommandType.STRING, description = "Claim the username of new accounts comes from")
    private String usernameClaim;

    @Parameter(name = OidcApiConstants.LOWERCASE_USERNAME, type = CommandType.BOOLEAN, description = "Whether to lowercase the username")
    private Boolean lowercaseUsername;

    @Parameter(name = OidcApiConstants.USERNAME_PATTERN, type = CommandType.STRING, length = 1024,
            description = "Regular expression every username must match in full")
    private String usernamePattern;

    @Parameter(name = OidcApiConstants.REQUIRE_VERIFIED_EMAIL, type = CommandType.BOOLEAN,
            description = "Whether the provider must have verified the e-mail address")
    private Boolean requireVerifiedEmail;

    @Parameter(name = OidcApiConstants.ADMISSION, type = CommandType.STRING, length = 4096, description = "Admission rule, a JSON object")
    private String admission;

    @Parameter(name = OidcApiConstants.REFUSAL, type = CommandType.STRING, length = 65535,
            description = "Message, an HTML fragment, for people the admission rule does not admit")
    private String refusal;

    @Parameter(name = OidcApiConstants.JIT, type = CommandType.BOOLEAN,
            description = "Whether an admitted person without an account gets one at the first sign-in")
    private Boolean jit;

    @Parameter(name = OidcApiConstants.END_SESSION_URL, type = CommandType.STRING, length = 1024, description = "The provider's end-session address")
    private String endSessionUrl;

    @Parameter(name = OidcApiConstants.END_SESSION_LABEL, type = CommandType.STRING, description = "Text of the link to the end-session address")
    private String endSessionLabel;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "Domain of new accounts; existing accounts keep theirs")
    private Long domainId;

    @Parameter(name = ApiConstants.ROLE_ID, type = CommandType.UUID, entityType = RoleResponse.class, description = "Role of the provider's accounts")
    private Long roleId;

    @Parameter(name = OidcApiConstants.MEMBERSHIP_SOURCE, type = CommandType.STRING, length = 4096,
            description = "Membership source of the periodic check, a JSON object")
    private String membershipSource;

    @Parameter(name = OidcApiConstants.MEMBERSHIP_SECRET, type = CommandType.STRING, length = 8192, description = "Secret of the membership source")
    private String membershipSecret;

    @Parameter(name = ApiConstants.ENABLED, type = CommandType.BOOLEAN, description = "Whether people can sign in with the provider")
    private Boolean enabled;

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getCaCertificate() {
        return caCertificate;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getScopes() {
        return scopes;
    }

    public String getAuthorizeParams() {
        return authorizeParams;
    }

    public String getUsernameClaim() {
        return usernameClaim;
    }

    public Boolean getLowercaseUsername() {
        return lowercaseUsername;
    }

    public String getUsernamePattern() {
        return usernamePattern;
    }

    public Boolean getRequireVerifiedEmail() {
        return requireVerifiedEmail;
    }

    public String getAdmission() {
        return admission;
    }

    public String getRefusal() {
        return refusal;
    }

    public Boolean getJit() {
        return jit;
    }

    public String getEndSessionUrl() {
        return endSessionUrl;
    }

    public String getEndSessionLabel() {
        return endSessionLabel;
    }

    public Long getDomainId() {
        return domainId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public String getMembershipSource() {
        return membershipSource;
    }

    public String getMembershipSecret() {
        return membershipSecret;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public void execute() {
        OidcProviderVO provider = oidcAuthManager.updateProvider(this);
        OidcProviderResponse response = oidcAuthManager.createProviderResponse(provider, true);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
