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
package org.apache.cloudstack.oidc.membership;

/**
 * Where the periodic check learns whether people are still in the groups their provider admits, without them signing
 * in. A source that cannot tell answers null, and nothing is changed for that person.
 */
public interface OidcMembershipSource {

    /**
     * @param member the person as the provider names them: the subject without the admission rule's prefix, or, for an
     *        account not yet taken over at a sign-in, its username
     * @return whether the person is a member of the group, or null when the source cannot tell
     */
    Boolean isMember(String member, String group);
}
