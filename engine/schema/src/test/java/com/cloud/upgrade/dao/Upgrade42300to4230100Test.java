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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.cloud.server.ResourceTag;

public class Upgrade42300to4230100Test {

    private final Upgrade42300to4230100 upgrade = new Upgrade42300to4230100();

    @Test
    public void prepareAndCleanupScriptsAreFound() throws IOException {
        assertOneScript(upgrade.getPrepareScripts());
        assertOneScript(upgrade.getCleanupScripts());
    }

    @Test
    public void tagValuesAreWidenedToTheLengthTheApiAccepts() throws IOException {
        assertTrue(prepareScript().contains("CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.resource_tags', 'value', 'value', 'varchar("
                + ResourceTag.VALUE_MAX_LENGTH + ")');"));
    }

    @Test
    public void currentDistributionsHaveGuestOsTypesWithKvmMappings() throws IOException {
        String script = prepareScript();
        for (String os : new String[] {"Ubuntu 26.04 LTS", "Debian GNU/Linux 13 (64-bit)", "Rocky Linux 10", "AlmaLinux 10", "Arch Linux", "Alpine Linux"}) {
            assertEquals(os, 2, occurrences(script, "'" + os + "'"));
        }
        for (String os : new String[] {"Fedora Linux (64 bit)", "OpenSUSE Linux (64 bit)"}) {
            assertEquals(os, 1, occurrences(script, "'" + os + "'"));
        }
    }

    private String prepareScript() throws IOException {
        try (InputStream script = upgrade.getPrepareScripts()[0]) {
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }

    private static void assertOneScript(InputStream[] scripts) throws IOException {
        assertEquals(1, scripts.length);
        assertNotNull(scripts[0]);
        scripts[0].close();
    }
}
