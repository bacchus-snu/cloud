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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.response.LoginCmdResponse;
import org.apache.cloudstack.oidc.OidcApiConstants;
import org.apache.cloudstack.oidc.OidcCallbackServlet;
import org.apache.cloudstack.oidc.OidcLoginError;
import org.apache.cloudstack.oidc.OidcLoginException;
import org.apache.cloudstack.oidc.OidcLoginStart;

import com.cloud.api.ApiServlet;
import com.cloud.exception.CloudAuthenticationException;

/**
 * Finishes a sign-in with the provider's answer, which the provider's callback path hands over as it received it, and
 * starts the session in the new session the API servlet opened for this login.
 */
@APICommand(name = OidcCallbackCmd.APINAME, description = "Finishes a sign-in through an OpenID provider with the provider's answer; the provider's "
        + "callback path calls it", requestHasSensitiveInfo = true, responseObject = LoginCmdResponse.class, entityType = {}, since = "4.23.0.100")
public class OidcCallbackCmd extends OidcLoginCmd {

    public static final String APINAME = "oidcCallback";

    /** Parameters of a provider's answer to an authorization request. */
    static final List<String> ANSWER_PARAMETERS = List.of(OidcApiConstants.CODE, OidcApiConstants.STATE, OidcApiConstants.ERROR, OidcApiConstants.ISS);

    @Parameter(name = OidcApiConstants.CODE, type = CommandType.STRING, description = "Authorization code of the provider's answer")
    private String code;

    @Parameter(name = OidcApiConstants.STATE, type = CommandType.STRING, description = "State of the provider's answer")
    private String state;

    @Parameter(name = OidcApiConstants.ERROR, type = CommandType.STRING, description = "Error of the provider's answer")
    private String error;

    @Parameter(name = OidcApiConstants.ISS, type = CommandType.STRING, description = "Issuer of the provider's answer")
    private String iss;

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session, InetAddress remoteAddress, String responseType,
            StringBuilder auditTrailSb, HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        URI client;
        try {
            client = configuredClient(params, responseType);
        } catch (ServerApiException e) {
            ApiServlet.invalidateHttpSession(session, "sign-in through OpenID providers is not configured");
            throw e;
        }
        String name = providerName(params);
        String clientPath = clientPath(client);
        boolean secure = "https".equalsIgnoreCase(client.getScheme());
        resp.setHeader("Cache-Control", "no-store");
        try {
            try {
                if (name == null) {
                    throw new OidcLoginException(OidcLoginError.UNAVAILABLE, "not a provider name");
                }
                resp.addHeader("Set-Cookie", setCookie(OidcLoginStart.bindingCookie(name), "", "/", true, true, 0));
                Object forwarded = req.getAttribute(OidcCallbackServlet.ANSWER_ATTRIBUTE);
                Map<String, String> answer = answer(forwarded instanceof String ? (String) forwarded : "");
                long userId = oidcAuthManager.completeLogin(name, answer, cookie(req.getHeader("Cookie"), OidcLoginStart.bindingCookie(name)));
                LoginCmdResponse login = (LoginCmdResponse) apiServer.loginVerifiedUser(session, userId, remoteAddress);
                for (String cookie : sessionCookies(login, clientPath, secure)) {
                    resp.addHeader("Set-Cookie", cookie);
                }
                auditTrailSb.append(" provider=").append(name).append(" userid=").append(userId);
                resp.sendRedirect(client + "/");
            } catch (OidcLoginException e) {
                endWithoutSession(e.getError(), e.getMessage(), client, name, session, auditTrailSb, resp);
            } catch (CloudAuthenticationException e) {
                endWithoutSession(OidcLoginError.REFUSED, e.getMessage(), client, name, session, auditTrailSb, resp);
            } catch (RuntimeException e) {
                failed(name, e);
                endWithoutSession(OidcLoginError.FAILED, e.getMessage(), client, name, session, auditTrailSb, resp);
            }
        } catch (IOException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Could not redirect the browser");
        }
        return "";
    }

    private void endWithoutSession(OidcLoginError reason, String message, URI client, String name, HttpSession session, StringBuilder auditTrailSb,
            HttpServletResponse resp) throws IOException {
        ApiServlet.invalidateHttpSession(session, "sign-in through an OpenID provider ended without a session");
        refuse(reason, message, client, name, auditTrailSb, resp);
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.LOGIN_API;
    }

    /**
     * @param query the query string the provider returned the browser with, form-encoded
     * @throws OidcLoginException with {@link OidcLoginError#FAILED} when it is not form-encoded or repeats a parameter
     */
    static Map<String, String> answer(String query) throws OidcLoginException {
        Map<String, String> answer = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            if (!ANSWER_PARAMETERS.contains(name)) {
                continue;
            }
            if (answer.put(name, decode(eq < 0 ? "" : pair.substring(eq + 1))) != null) {
                throw new OidcLoginException(OidcLoginError.FAILED, String.format("the answer has more than one %s", name));
            }
        }
        return answer;
    }

    private static String decode(String value) throws OidcLoginException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new OidcLoginException(OidcLoginError.FAILED, "the answer is not form-encoded");
        }
    }
}
