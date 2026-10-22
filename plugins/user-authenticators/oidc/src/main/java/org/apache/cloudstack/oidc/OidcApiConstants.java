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

public final class OidcApiConstants {

    public static final String ISSUER = "issuer";
    public static final String CA_CERTIFICATE = "cacertificate";
    public static final String CLIENT_SECRET = "clientsecret";
    public static final String SCOPES = "scopes";
    public static final String AUTHORIZE_PARAMS = "authorizeparams";
    public static final String USERNAME_CLAIM = "usernameclaim";
    public static final String LOWERCASE_USERNAME = "lowercaseusername";
    public static final String USERNAME_PATTERN = "usernamepattern";
    public static final String REQUIRE_VERIFIED_EMAIL = "requireverifiedemail";
    public static final String ADMISSION = "admission";
    public static final String REFUSAL = "refusal";
    public static final String JIT = "jit";
    public static final String END_SESSION_URL = "endsessionurl";
    public static final String END_SESSION_LABEL = "endsessionlabel";
    public static final String MEMBERSHIP_SOURCE = "membershipsource";
    public static final String MEMBERSHIP_SECRET = "membershipsecret";
    public static final String OIDC_PROVIDER = "oidcprovider";

    /** Parameters of a provider's answer to an authorization request. */
    public static final String CODE = "code";
    public static final String STATE = "state";
    public static final String ERROR = "error";
    public static final String ISS = "iss";

    /** Parameter of the web client address that a failed sign-in returns to. */
    public static final String OIDC_ERROR = "oidcerror";

    private OidcApiConstants() {
    }
}
