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

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.cloudstack.oidc.api.command.CreateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.command.UpdateOidcProviderCmd;
import org.apache.cloudstack.oidc.api.response.OidcProviderResponse;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.component.PluggableService;

/**
 * Sign-in through OpenID providers (OpenID Connect authorization code flow with PKCE). Each provider admits the people
 * its admission rule accepts into accounts of one domain and role, creating an account at the first sign-in when the
 * provider allows it, and disables the accounts of people who no longer qualify.
 */
public interface OidcAuthManager extends PluggableAPIAuthenticator, PluggableService {

    /** Account detail naming the provider that disabled the account because its user no longer qualified. */
    String DISABLED_BY_DETAIL = "oidc.disabledby";

    /** Path, below the web client, at which providers return the browser: {@code <client url>/oidc/callback/<provider>}. */
    String CALLBACK_PATH = "/oidc/callback/";

    ConfigKey<String> ClientUrl = new ValidatedConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class, "oidc.client.url", "",
            "External https URL of the web client, for example https://cloud.example.org/client. Providers return the browser to " +
                    "<url>" + CALLBACK_PATH + "<provider>, the address to register at each provider, and a finished sign-in continues at <url>/. " +
                    "While empty, nobody can sign in through a provider.",
            true, ConfigKey.Scope.Global, null, OidcAuthManager::validateClientUrl);

    ConfigKey<Integer> LoginTimeout = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "oidc.login.timeout", "600",
            "Seconds within which a sign-in started at a provider must come back; a later answer is refused.", true, ConfigKey.Scope.Global);

    ConfigKey<Long> ReconcileInterval = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Long.class, "oidc.reconcile.interval", "3600",
            "Seconds between the checks of the providers' membership sources, which disable the accounts of people who left the groups " +
                    "their provider admits and re-enable the ones this check or a sign-in disabled; 0 stops the checks.",
            true, ConfigKey.Scope.Global);

    OidcProviderVO createProvider(CreateOidcProviderCmd cmd);

    OidcProviderVO updateProvider(UpdateOidcProviderCmd cmd);

    /**
     * Deletes a provider that no user signs in with any more; one with linked users has to be disabled instead.
     */
    boolean deleteProvider(long id);

    /**
     * @param enabledOnly true for the providers that people can sign in with
     */
    List<OidcProviderVO> listProviders(Long id, String name, boolean enabledOnly);

    OidcProviderVO findProviderByUuid(String uuid);

    /**
     * @param details false for what the sign-in page shows: name, label, end-session link and refusal message
     */
    OidcProviderResponse createProviderResponse(OidcProviderVO provider, boolean details);

    /**
     * Starts a sign-in at the provider.
     *
     * @throws OidcLoginException with {@link OidcLoginError#UNAVAILABLE} when the provider cannot be used now
     */
    OidcLoginStart startLogin(String providerName) throws OidcLoginException;

    /**
     * Finishes a sign-in from the provider's answer: checks it against the request it answers and the browser that
     * started it, verifies the ID token, applies the provider's admission rule and finds, adopts or creates the account.
     *
     * @param answer the parameters the provider returned the browser with
     * @param binding the value of the browser's {@link OidcLoginStart#bindingCookie} cookie, or null
     * @return the user to start a session for
     */
    long completeLogin(String providerName, Map<String, String> answer, String binding) throws OidcLoginException;

    /**
     * Checks every provider's membership source and disables or re-enables accounts as a sign-in would.
     */
    void reconcile();

    /** @return the configured web client URL without a trailing slash, or null when it is not set */
    static URI clientUri() {
        String value = StringUtils.stripEnd(StringUtils.trimToEmpty(ClientUrl.value()), "/");
        return value.isEmpty() ? null : URI.create(value);
    }

    static void validateClientUrl(String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (Exception e) {
            throw new InvalidParameterValueException(String.format("%s is not a URL", value));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new InvalidParameterValueException(String.format("%s is not an https URL without a query or fragment", value));
        }
    }
}
