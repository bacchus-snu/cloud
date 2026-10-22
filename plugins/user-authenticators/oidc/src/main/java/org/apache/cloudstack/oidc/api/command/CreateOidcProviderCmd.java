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

@APICommand(name = "createOidcProvider", description = "Adds an OpenID provider people sign in with; it admits the people its admission rule accepts into "
        + "accounts of one domain and role", responseObject = OidcProviderResponse.class, entityType = {},
        requestHasSensitiveInfo = true, responseHasSensitiveInfo = false, authorized = {RoleType.Admin}, since = "4.23.0.100")
public class CreateOidcProviderCmd extends BaseCmd {

    @Inject
    OidcAuthManager oidcAuthManager;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "Name of the provider in the sign-in addresses: lowercase letters and digits, starting with a letter; it cannot be changed")
    private String name;

    @Parameter(name = ApiConstants.LABEL, type = CommandType.STRING, required = true, description = "Name of the provider shown on the sign-in page")
    private String label;

    @Parameter(name = OidcApiConstants.ISSUER, type = CommandType.STRING, required = true, length = 1024,
            description = "Issuer identifier of the provider, an https URL; its discovery document must name the same issuer")
    private String issuer;

    @Parameter(name = OidcApiConstants.CA_CERTIFICATE, type = CommandType.STRING, length = 65535,
            description = "PEM certificates of the authorities the provider's TLS is checked against instead of the CA certificates the JDK ships; "
                    + "by default the JDK's")
    private String caCertificate;

    @Parameter(name = ApiConstants.CLIENT_ID, type = CommandType.STRING, required = true, description = "Client ID registered at the provider")
    private String clientId;

    @Parameter(name = OidcApiConstants.CLIENT_SECRET, type = CommandType.STRING, required = true, length = 1024, description = "Client secret")
    private String clientSecret;

    @Parameter(name = OidcApiConstants.SCOPES, type = CommandType.STRING, description = "Scopes to ask for, with openid; default \"openid profile email\"")
    private String scopes;

    @Parameter(name = OidcApiConstants.AUTHORIZE_PARAMS, type = CommandType.STRING, length = 4096,
            description = "Extra parameters of the authorization request, a JSON object of strings, for example {\"connection\":\"workspace\"}")
    private String authorizeParams;

    @Parameter(name = OidcApiConstants.USERNAME_CLAIM, type = CommandType.STRING, required = true,
            description = "Claim the username of the account comes from, for example preferred_username or email")
    private String usernameClaim;

    @Parameter(name = OidcApiConstants.LOWERCASE_USERNAME, type = CommandType.BOOLEAN, description = "Whether to lowercase the username; default false")
    private Boolean lowercaseUsername;

    @Parameter(name = OidcApiConstants.USERNAME_PATTERN, type = CommandType.STRING, required = true, length = 1024,
            description = "Regular expression every username must match in full; patterns of providers sharing a domain should not overlap")
    private String usernamePattern;

    @Parameter(name = OidcApiConstants.REQUIRE_VERIFIED_EMAIL, type = CommandType.BOOLEAN,
            description = "Whether the provider must have verified the e-mail address (email_verified true); default false")
    private Boolean requireVerifiedEmail;

    @Parameter(name = OidcApiConstants.ADMISSION, type = CommandType.STRING, required = true, length = 4096,
            description = "Admission rule, a JSON object: groups_claim, the claim listing the person's groups; allowed_groups, the groups any one of "
                    + "which admits; and optionally subject_prefix, the prefix of every admitted subject")
    private String admission;

    @Parameter(name = OidcApiConstants.REFUSAL, type = CommandType.STRING, length = 65535,
            description = "Message, an HTML fragment, for people the admission rule does not admit")
    private String refusal;

    @Parameter(name = OidcApiConstants.JIT, type = CommandType.BOOLEAN,
            description = "Whether an admitted person without an account gets one at the first sign-in; default true. When false, only people with a "
                    + "linked or adopted account can sign in")
    private Boolean jit;

    @Parameter(name = OidcApiConstants.END_SESSION_URL, type = CommandType.STRING, length = 1024,
            description = "The provider's end-session address, linked from the sign-in page so that people can sign out of the provider too")
    private String endSessionUrl;

    @Parameter(name = OidcApiConstants.END_SESSION_LABEL, type = CommandType.STRING, description = "Text of the link to the end-session address")
    private String endSessionLabel;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, required = true,
            description = "Domain of the provider's accounts")
    private Long domainId;

    @Parameter(name = ApiConstants.ROLE_ID, type = CommandType.UUID, entityType = RoleResponse.class, required = true,
            description = "Role of the provider's accounts; their account type follows from it")
    private Long roleId;

    @Parameter(name = OidcApiConstants.MEMBERSHIP_SOURCE, type = CommandType.STRING, length = 4096,
            description = "Membership source of the periodic check, a JSON object: {\"type\":\"google-directory\"} with an optional \"subject\", the "
                    + "Workspace user its service account acts for")
    private String membershipSource;

    @Parameter(name = OidcApiConstants.MEMBERSHIP_SECRET, type = CommandType.STRING, length = 8192,
            description = "Secret of the membership source; for google-directory, the service account's JSON key")
    private String membershipSecret;

    @Parameter(name = ApiConstants.ENABLED, type = CommandType.BOOLEAN, description = "Whether people can sign in with the provider; default true")
    private Boolean enabled;

    public String getName() {
        return name;
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
        OidcProviderVO provider = oidcAuthManager.createProvider(this);
        OidcProviderResponse response = oidcAuthManager.createProviderResponse(provider, true);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
