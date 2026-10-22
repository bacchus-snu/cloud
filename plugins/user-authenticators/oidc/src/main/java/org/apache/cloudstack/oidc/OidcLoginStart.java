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

/**
 * A sign-in sent to its provider: where the browser goes, and the value it must bring back to the callback path.
 */
public class OidcLoginStart {

    /**
     * Prefix of the cookie that binds the answer to the browser that started the sign-in. The {@code __Host-} prefix
     * makes browsers accept it only from this host, Secure, with {@code Path=/} and no domain, so that another host of
     * the site cannot plant one.
     */
    public static final String BINDING_COOKIE_PREFIX = "__Host-oidcbinding-";

    private final String authorizationUrl;
    private final String binding;

    public OidcLoginStart(String authorizationUrl, String binding) {
        this.authorizationUrl = authorizationUrl;
        this.binding = binding;
    }

    /** @return the name of the binding cookie of a sign-in at the provider */
    public static String bindingCookie(String provider) {
        return BINDING_COOKIE_PREFIX + provider;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public String getBinding() {
        return binding;
    }
}
