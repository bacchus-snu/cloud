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
package org.apache.cloudstack.registry;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class CoreRegistryAccessControlTest {

    private static final Set<String> ACCESS_CONTROL_KEYS = Set.of("api.checkers.exclude", "api.checkers.acl.exclude",
            "user.authenticators.order", "user.authenticators.exclude", "user.password.encoders.order", "user.password.encoders.exclude",
            "pluggableApi.authenticators.order", "pluggableApi.authenticators.exclude", "user.2fa.providers.order",
            "user.2fa.providers.exclude", "security.checkers.order", "security.checkers.exclude");

    @Test
    public void theRegistriesThatEnforceAccessControlAreMarked() throws Exception {
        Set<String> marked = new HashSet<>();
        try (InputStream xml = getClass().getResourceAsStream("/META-INF/cloudstack/core/spring-core-registry-core-context.xml")) {
            NodeList beans = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("bean");
            for (int i = 0; i < beans.getLength(); i++) {
                Element bean = (Element) beans.item(i);
                if (!bean.getAttribute("class").endsWith(".ExtensionRegistry")) {
                    continue;
                }
                Set<String> keys = new HashSet<>();
                boolean accessControl = false;
                NodeList properties = bean.getElementsByTagName("property");
                for (int j = 0; j < properties.getLength(); j++) {
                    Element property = (Element) properties.item(j);
                    String name = property.getAttribute("name");
                    if ("orderConfigKey".equals(name) || "excludeKey".equals(name)) {
                        keys.add(property.getAttribute("value"));
                    } else if ("accessControl".equals(name)) {
                        accessControl = Boolean.parseBoolean(property.getAttribute("value"));
                    }
                }
                boolean enforces = keys.stream().anyMatch(ACCESS_CONTROL_KEYS::contains);
                Assert.assertEquals(bean.getAttribute("id"), enforces, accessControl);
                if (accessControl) {
                    marked.addAll(keys);
                }
            }
        }
        Assert.assertEquals(ACCESS_CONTROL_KEYS, marked);
    }
}
