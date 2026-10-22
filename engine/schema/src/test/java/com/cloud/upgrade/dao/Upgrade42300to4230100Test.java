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
package com.cloud.upgrade.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import org.junit.Test;

public class Upgrade42300to4230100Test {

    private final Upgrade42300to4230100 upgrade = new Upgrade42300to4230100();

    @Test
    public void prepareAndCleanupScriptsAreFound() throws IOException {
        assertOneScript(upgrade.getPrepareScripts());
        assertOneScript(upgrade.getCleanupScripts());
    }

    private static void assertOneScript(InputStream[] scripts) throws IOException {
        assertEquals(1, scripts.length);
        assertNotNull(scripts[0]);
        scripts[0].close();
    }
}
