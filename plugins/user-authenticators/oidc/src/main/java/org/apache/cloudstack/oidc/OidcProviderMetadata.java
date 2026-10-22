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

import java.util.List;

/**
 * The parts of a provider's discovery document (OpenID Connect Discovery 1.0) that sign-in uses.
 */
final class OidcProviderMetadata {

    private final String issuer;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String jwksUri;
    private final String userinfoEndpoint;
    private final boolean issParameterSupported;
    private final List<String> tokenEndpointAuthMethods;

    OidcProviderMetadata(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri, String userinfoEndpoint,
            boolean issParameterSupported, List<String> tokenEndpointAuthMethods) {
        this.issuer = issuer;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.jwksUri = jwksUri;
        this.userinfoEndpoint = userinfoEndpoint;
        this.issParameterSupported = issParameterSupported;
        this.tokenEndpointAuthMethods = tokenEndpointAuthMethods;
    }

    String getIssuer() {
        return issuer;
    }

    String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    String getTokenEndpoint() {
        return tokenEndpoint;
    }

    String getJwksUri() {
        return jwksUri;
    }

    /** @return the userinfo endpoint, or null when the provider has none */
    String getUserinfoEndpoint() {
        return userinfoEndpoint;
    }

    /** @return whether authorization responses carry the issuer (RFC 9207) */
    boolean isIssParameterSupported() {
        return issParameterSupported;
    }

    /** @return the client authentication methods of the token endpoint; empty means client_secret_basic */
    List<String> getTokenEndpointAuthMethods() {
        return tokenEndpointAuthMethods;
    }
}
