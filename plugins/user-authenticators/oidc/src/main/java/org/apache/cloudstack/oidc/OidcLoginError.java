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
 * Why a sign-in through a provider did not start a session; the web client receives the code as {@code oidcerror}.
 */
public enum OidcLoginError {
    /** The provider is unknown, disabled or cannot be reached, or sign-in is not configured. */
    UNAVAILABLE("unavailable"),
    /** The answer matches no pending sign-in of this browser: too late, used already, or started elsewhere. */
    EXPIRED("expired"),
    /** The provider answered with an error, for example because the person cancelled. */
    DENIED("denied"),
    /** The provider's answer or tokens failed verification. */
    FAILED("failed"),
    /** The provider's claims do not give a usable identity: username, e-mail address, verification or subject. */
    IDENTITY("identity"),
    /** The provider's admission rule does not admit the person; the provider's refusal message explains why. */
    NOT_ADMITTED("notadmitted"),
    /** The person is admitted but has no account, and the provider does not create accounts. */
    NO_ACCOUNT("noaccount"),
    /** The account the person would get conflicts with an existing one, or with the provider's role. */
    CONFLICT("conflict"),
    /** The account or user was disabled or locked by an administrator. */
    DISABLED("disabled"),
    /** CloudStack refused the session, for example from an address outside the account's allowed CIDRs. */
    REFUSED("refused");

    private final String code;

    OidcLoginError(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
