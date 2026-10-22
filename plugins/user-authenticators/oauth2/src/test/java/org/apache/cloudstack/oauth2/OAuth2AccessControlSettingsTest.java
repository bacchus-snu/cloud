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
package org.apache.cloudstack.oauth2;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class OAuth2AccessControlSettingsTest {

    @Test
    public void everyOAuth2SettingIsGated() {
        OAuth2AuthManagerImpl manager = new OAuth2AuthManagerImpl();
        Assert.assertEquals(Arrays.stream(manager.getConfigKeys()).map(ConfigKey::key).collect(Collectors.toSet()),
                Set.of(manager.getAccessControlConfigKeyNames()));
    }

    @Test
    public void theProviderRegistryIsMarkedAsAccessControl() throws Exception {
        try (InputStream xml = getClass().getResourceAsStream("/META-INF/cloudstack/oauth2/spring-oauth2-context.xml")) {
            NodeList beans = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("bean");
            for (int i = 0; i < beans.getLength(); i++) {
                Element bean = (Element) beans.item(i);
                if ("userOAuth2AuthenticatorsRegistry".equals(bean.getAttribute("id"))) {
                    NodeList properties = bean.getElementsByTagName("property");
                    for (int j = 0; j < properties.getLength(); j++) {
                        Element property = (Element) properties.item(j);
                        if ("accessControl".equals(property.getAttribute("name"))) {
                            Assert.assertEquals("true", property.getAttribute("value"));
                            return;
                        }
                    }
                }
            }
        }
        Assert.fail("the OAuth2 provider registry is not marked as access control");
    }
}
