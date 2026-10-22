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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.oidc.OidcAuthManager;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.EnumUtils;

import com.cloud.api.ApiServer;
import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.user.Account;
import com.cloud.utils.HttpUtils;

@APICommand(name = "listOidcProviders", description = "Lists the OpenID providers people sign in with, with their end-session links. Unauthenticated, "
        + "it lists the enabled providers and what the sign-in page shows; root administrators whose role allows this API, from a session or "
        + "with a signed request, see every provider and its configuration, never its secrets", responseObject = OidcProviderResponse.class, entityType = {},
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User}, since = "4.23.0.100")
public class ListOidcProvidersCmd extends BaseListCmd implements APIAuthenticator {

    @Inject
    ApiServerService apiServer;

    OidcAuthManager oidcAuthManager;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = OidcProviderResponse.class, description = "The provider")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "The name of the provider")
    private String name;

    @Override
    public long getEntityOwnerId() {
        return Account.Type.NORMAL.ordinal();
    }

    @Override
    public void execute() throws ServerApiException {
        throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "This is an authentication api, cannot be used directly");
    }

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session, InetAddress remoteAddress, String responseType,
            StringBuilder auditTrailSb, HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        boolean details = isAdministrator(params, session, remoteAddress, req);
        Long providerId = null;
        String[] ids = (String[]) params.get(ApiConstants.ID);
        if (ArrayUtils.isNotEmpty(ids)) {
            OidcProviderVO byUuid = oidcAuthManager.findProviderByUuid(ids[0]);
            providerId = byUuid == null ? -1L : byUuid.getId();
        }
        String[] names = (String[]) params.get(ApiConstants.NAME);
        String providerName = ArrayUtils.isNotEmpty(names) ? names[0] : null;
        List<OidcProviderResponse> responses = new ArrayList<>();
        for (OidcProviderVO provider : oidcAuthManager.listProviders(providerId, providerName, !details)) {
            responses.add(oidcAuthManager.createProviderResponse(provider, details));
        }
        ListResponse<OidcProviderResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        response.setResponseName(getCommandName());
        setResponseObject(response);
        return ApiResponseSerializer.toSerializedString(response, responseType);
    }

    /**
     * A caller sees the configuration only as a root administrator whose role rules allow this API, from a valid session or from a
     * request signed with its API key.
     */
    protected boolean isAdministrator(Map<String, Object[]> params, HttpSession session, InetAddress remoteAddress, HttpServletRequest req) {
        Long userId = session == null ? null : (Long) session.getAttribute("userid");
        Object accountObj = session == null ? null : session.getAttribute("accountobj");
        if (userId == null || !(accountObj instanceof Account)) {
            return isSignedByAdministrator(params, remoteAddress);
        }
        HttpUtils.ApiSessionKeyCheckOption sessionKeyCheckOption = EnumUtils.getEnumIgnoreCase(HttpUtils.ApiSessionKeyCheckOption.class,
                ApiServer.ApiSessionKeyCheckLocations.value(), HttpUtils.ApiSessionKeyCheckOption.CookieAndParameter);
        if (!HttpUtils.validateSessionKey(session, params, req.getCookies(), ApiConstants.SESSIONKEY, sessionKeyCheckOption)) {
            return false;
        }
        try {
            return apiServer.verifyUser(userId) && apiServer.verifyRequest(params, userId, remoteAddress)
                    && _accountService.isRootAdmin(((Account) accountObj).getId());
        } catch (ServerApiException e) {
            return false;
        }
    }

    /**
     * A request signed with an API key is verified as any other API call is: the key's user and account, its role rules and the
     * signature. Unsigned requests are not offered for verification, so the public listing logs nothing.
     */
    private boolean isSignedByAdministrator(Map<String, Object[]> params, InetAddress remoteAddress) {
        boolean signed = params.keySet().stream().anyMatch(ApiConstants.SIGNATURE::equalsIgnoreCase)
                && params.keySet().stream().anyMatch(ApiConstants.API_KEY::equalsIgnoreCase);
        if (!signed) {
            return false;
        }
        try {
            return apiServer.verifyRequest(params, null, remoteAddress) && _accountService.isRootAdmin(CallContext.current().getCallingAccountId());
        } catch (ServerApiException e) {
            return false;
        }
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.READONLY_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
        for (PluggableAPIAuthenticator authenticator : authenticators) {
            if (authenticator instanceof OidcAuthManager) {
                oidcAuthManager = (OidcAuthManager) authenticator;
            }
        }
        if (oidcAuthManager == null) {
            logger.error("No OpenID Connect sign-in manager is registered to list providers");
        }
    }
}
