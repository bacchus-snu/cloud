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
package org.apache.cloudstack.saml;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.Assert;
import org.junit.Test;

public class SAML2AccessControlSettingsTest {

    @Test
    public void theSignInSettingsAreGatedButNotTheMetadataDescriptions() {
        SAML2AuthManagerImpl manager = new SAML2AuthManagerImpl();
        Set<String> gated = Set.of(manager.getAccessControlConfigKeyNames());
        Set<String> descriptive = Set.of("saml2.sp.org.name", "saml2.sp.org.url", "saml2.sp.contact.person", "saml2.sp.contact.email", "saml2.timeout");
        List<String> keys = Arrays.stream(manager.getConfigKeys()).map(ConfigKey::key).collect(Collectors.toList());
        for (String key : keys) {
            Assert.assertEquals(key, !descriptive.contains(key), gated.contains(key));
        }
        Assert.assertTrue(gated.contains("saml2.failed.login.redirect.url"));
        Assert.assertEquals(keys.size() - descriptive.size() + 1, gated.size());
    }
}
