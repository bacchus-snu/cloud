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
package org.apache.cloudstack.auth;

import java.util.List;

import com.cloud.utils.component.Adapter;

/**
 * Sign-in providers that link users to their identities elsewhere. An account one of whose users is linked takes its
 * Instance names from that link; any other account takes them from its own name, which may not be a provider's.
 */
public interface SignInProviderLinks extends Adapter {

    /**
     * The account detail, valued "true", with which an earlier sign-in service marks the accounts a provider adopts at their
     * owner's first sign-in through it.
     */
    String ADOPTION_MARK = "gate";

    /**
     * @return the provider that a user of the account, not removed, signs in through and that user's username, or null
     *         when no such user is linked
     */
    Link findAccountLink(long accountId);

    /**
     * @return the names of all the providers, enabled or not
     */
    List<String> listProviderNames();

    final class Link {
        private final String providerName;
        private final String username;

        public Link(String providerName, String username) {
            this.providerName = providerName;
            this.username = username;
        }

        public String getProviderName() {
            return providerName;
        }

        public String getUsername() {
            return username;
        }
    }
}
