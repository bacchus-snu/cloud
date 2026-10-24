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
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Runs the lines of the agent package's postinst that install libvirt's qemu hook, with the hook directory and the
 * package's library directory moved into a temporary folder.
 */
public class AgentPackageQemuHookTest {
    private static final String HOOKS = "/etc/libvirt/hooks";
    private static final String LIB = "/usr/share/cloudstack-agent/lib";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path hooks;
    private Path shipped;

    private void configure() throws Exception {
        List<String> postinst = Files.readAllLines(Path.of("../../../debian/cloudstack-agent.postinst"));
        int from = -1;
        int to = -1;
        for (int i = 0; i < postinst.size(); i++) {
            if (from < 0 && postinst.get(i).contains("if [ ! -d \"" + HOOKS + "\" ]")) {
                from = i;
            }
            if (from >= 0 && postinst.get(i).contains("# Print help message")) {
                to = i;
                break;
            }
        }
        assertTrue("the postinst installs the hook before printing its help", from >= 0 && to > from);
        Path lib = folder.newFolder("lib").toPath();
        hooks = folder.getRoot().toPath().resolve("hooks");
        shipped = lib.resolve("libvirtqemuhook");
        Files.write(shipped, "#!/usr/bin/python3\n# the shipped hook\n".getBytes(StandardCharsets.UTF_8));
        String script = "set -e\n" + String.join("\n", postinst.subList(from, to)).replace(HOOKS, hooks.toString()).replace(LIB, lib.toString()) + "\n";
        Process process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), 0, process.exitValue());
    }

    @Test
    public void aHostWithoutAHookGetsTheShippedOne() throws Exception {
        configure();

        assertArrayEquals(Files.readAllBytes(shipped), Files.readAllBytes(hooks.resolve("qemu")));
    }

    @Test
    public void aHookAlreadyInPlaceStays() throws Exception {
        Files.createDirectories(folder.getRoot().toPath().resolve("hooks"));
        byte[] own = "#!/usr/bin/python3\n# the host's own hook\n".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.getRoot().toPath().resolve("hooks").resolve("qemu"), own);

        configure();

        assertArrayEquals(own, Files.readAllBytes(hooks.resolve("qemu")));
    }
}
