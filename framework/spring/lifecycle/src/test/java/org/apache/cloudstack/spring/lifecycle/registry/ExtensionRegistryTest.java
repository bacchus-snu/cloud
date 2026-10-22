/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.spring.lifecycle.registry;

import org.junit.Assert;
import org.junit.Test;

public class ExtensionRegistryTest {

    private static ExtensionRegistry registry(boolean accessControl) {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.setOrderConfigKey("user.authenticators.order");
        registry.setExcludeKey("user.authenticators.exclude");
        registry.setAccessControl(accessControl);
        return registry;
    }

    @Test
    public void anAccessControlRegistryGatesItsOrderAndExcludeSettings() {
        Assert.assertArrayEquals(new String[] {"user.authenticators.order", "user.authenticators.exclude"},
                registry(true).getAccessControlConfigKeyNames());
        Assert.assertEquals(0, registry(false).getAccessControlConfigKeyNames().length);
    }
}
