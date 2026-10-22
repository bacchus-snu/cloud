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

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.LoginCmdResponse;
import org.apache.cloudstack.oidc.OidcApiConstants;
import org.apache.cloudstack.oidc.OidcAuthManager;
import org.apache.cloudstack.oidc.OidcLoginError;
import org.apache.cloudstack.oidc.OidcLoginException;
import org.apache.cloudstack.oidc.OidcLoginStart;

import com.cloud.user.Account;

/**
 * Starts a sign-in. It is no login: it keeps nothing in a session and leaves the browser's session as it is; the
 * provider's answer comes to {@link OidcCallbackCmd} through the provider's callback path.
 */
@APICommand(name = "oidcLogin", description = "Starts a sign-in through an OpenID provider: sends the browser to the provider, which returns it to the "
        + "provider's callback path; that starts the session and continues at the web client, with oidcerror=<code> and provider=<name> when no session "
        + "was started", requestHasSensitiveInfo = false, responseObject = LoginCmdResponse.class, entityType = {}, since = "4.23.0.100")
public class OidcLoginCmd extends BaseCmd implements APIAuthenticator {

    static final Pattern PROVIDER_NAME = Pattern.compile("[a-z][a-z0-9]{0,63}");

    @Inject
    ApiServerService apiServer;

    OidcAuthManager oidcAuthManager;

    @Parameter(name = ApiConstants.PROVIDER, type = CommandType.STRING, required = true, description = "Name of the OpenID provider")
    private String provider;

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
        URI client = configuredClient(params, responseType);
        String name = providerName(params);
        resp.setHeader("Cache-Control", "no-store");
        try {
            try {
                if (name == null) {
                    throw new OidcLoginException(OidcLoginError.UNAVAILABLE, "not a provider name");
                }
                OidcLoginStart start = oidcAuthManager.startLogin(name);
                resp.addHeader("Set-Cookie", setCookie(OidcLoginStart.bindingCookie(name), start.getBinding(), "/", true, true,
                        OidcAuthManager.LoginTimeout.value()));
                resp.sendRedirect(start.getAuthorizationUrl());
            } catch (OidcLoginException e) {
                refuse(e.getError(), e.getMessage(), client, name, auditTrailSb, resp);
            } catch (RuntimeException e) {
                failed(name, e);
                refuse(OidcLoginError.FAILED, e.getMessage(), client, name, auditTrailSb, resp);
            }
        } catch (IOException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Could not redirect the browser");
        }
        return "";
    }

    /** @return the web client address, which every outcome of a sign-in continues at */
    URI configuredClient(Map<String, Object[]> params, String responseType) {
        URI client = OidcAuthManager.clientUri();
        if (client == null) {
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, apiServer.getSerializedApiError(ApiErrorCode.ACCOUNT_ERROR.getHttpCode(),
                    "Sign-in through OpenID providers is not configured", params, responseType));
        }
        return client;
    }

    /** @return the provider named by the request, or null for a name that cannot be a provider's */
    static String providerName(Map<String, Object[]> params) {
        String name = param(params, ApiConstants.PROVIDER);
        return name != null && PROVIDER_NAME.matcher(name).matches() ? name : null;
    }

    /** A sign-in that ends without a session is the person's or the provider's doing, not a fault of this server. */
    void refuse(OidcLoginError reason, String message, URI client, String name, StringBuilder auditTrailSb, HttpServletResponse resp) throws IOException {
        logger.info("Sign-in through OpenID provider {} ended without a session ({}): {}", name, reason.getCode(), message);
        auditTrailSb.append(" ").append(OidcApiConstants.OIDC_ERROR).append("=").append(reason.getCode());
        resp.sendRedirect(errorUrl(client, reason, name));
    }

    void failed(String name, RuntimeException e) {
        logger.warn("Sign-in through OpenID provider {} failed: {}", name, e.getMessage());
        logger.debug("Sign-in through OpenID provider {} failed", name, e);
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
            logger.error("No OpenID Connect sign-in manager is registered");
        }
    }

    static String param(Map<String, Object[]> params, String name) {
        Object[] values = params.get(name);
        return values == null || values.length == 0 || values[0] == null ? null : values[0].toString();
    }

    /**
     * Reads a cookie from the raw header. The browser sends every cookie of the path, including the web client's, and
     * one value a strict parser rejects must not hide the others.
     */
    public static String cookie(String header, String name) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public static String setCookie(String name, String value, String path, boolean httpOnly, boolean secure, Integer maxAge) {
        StringBuilder sb = new StringBuilder(name).append('=').append(value).append("; Path=").append(path);
        if (maxAge != null) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (secure) {
            sb.append("; Secure");
        }
        return sb.append("; SameSite=Lax").toString();
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The cookies through which the web client takes over a session started on the server, as after a SAML sign-in:
     * the session key twice (readable by the client, which sends it as a parameter, and HttpOnly for the API path),
     * the identity the client reads, and the flag that makes it discover its APIs instead of showing its own form.
     */
    public static List<String> sessionCookies(LoginCmdResponse login, String clientPath, boolean secure) {
        List<String> cookies = new ArrayList<>();
        cookies.add(setCookie(ApiConstants.SESSIONKEY, login.getSessionKey(), clientPath + "/api", true, secure, null));
        cookies.add(setCookie(ApiConstants.SESSIONKEY, login.getSessionKey(), clientPath, false, secure, null));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("userid", login.getUserId());
        values.put("domainid", login.getDomainId());
        values.put("role", login.getType());
        values.put("username", login.getUsername());
        values.put("account", login.getAccount());
        values.put("userfullname", String.format("%s %s", nullToEmpty(login.getFirstName()), nullToEmpty(login.getLastName())).trim());
        values.put("timezone", login.getTimeZone());
        values.put(ApiConstants.MANAGEMENT_SERVER_ID, login.getManagementServerId());
        values.put("twoFaEnabled", login.is2FAenabled() == null ? "false" : login.is2FAenabled().toLowerCase());
        values.put("twoFaProvider", login.getProviderFor2FA());
        values.put("isSAML", "true");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                cookies.add(setCookie(entry.getKey(), encode(entry.getValue()), clientPath, false, secure, null));
            }
        }
        return cookies;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String clientPath(URI client) {
        String path = client.getRawPath();
        return path == null ? "" : path;
    }

    /** @return the web client address a sign-in that started no session returns to; nothing from the request decides it */
    public static String errorUrl(URI client, OidcLoginError error, String provider) {
        String url = client + "/?" + OidcApiConstants.OIDC_ERROR + "=" + error.getCode();
        return provider == null ? url : url + "&" + ApiConstants.PROVIDER + "=" + provider;
    }
}
