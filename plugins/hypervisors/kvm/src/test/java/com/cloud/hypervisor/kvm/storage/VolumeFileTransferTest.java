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
package com.cloud.hypervisor.kvm.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.cloudstack.utils.security.CertUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloud.utils.exception.CloudRuntimeException;

public class VolumeFileTransferTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void addProvider() {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * A host certificate issued the way the root CA provider issues it: the subject is the CSR's subject as
     * BouncyCastle prints it (the keystore's dname cn=..,ou=..,o=..,c=..), re-parsed as an X.500 principal.
     */
    public static X509Certificate hostCertificate(String fqdn) throws Exception {
        KeyPair ca = CertUtils.generateRandomKeyPair(2048);
        X509Certificate caCertificate = CertUtils.generateV3Certificate(null, ca, ca.getPublic(), "CN=ca.cloudstack.apache.org", "SHA256withRSA", 30, null, null);
        KeyPair host = CertUtils.generateRandomKeyPair(2048);
        return CertUtils.generateV3Certificate(caCertificate, ca, host.getPublic(), "C=cloudstack,O=cloudstack,OU=cloudstack,CN=" + fqdn, "SHA256withRSA", 30,
                Collections.singletonList(fqdn), Collections.singletonList("10.0.0.12"));
    }

    @Test
    public void identityIsTheSubjectInEncodingOrder() throws Exception {
        X509Certificate certificate = hostCertificate("dst.example.org");
        assertEquals("C=cloudstack,O=cloudstack,OU=cloudstack,CN=dst.example.org", certificate.getSubjectX500Principal().getName());
        assertEquals("CN=dst.example.org,OU=cloudstack,O=cloudstack,C=cloudstack", VolumeFileTransfer.identityOf(certificate));
    }

    @Test
    public void fileNamesStayInThePool() {
        assertEquals("/pool/abc", VolumeFileTransfer.fileIn("/pool", "abc").toString());
        for (String name : Arrays.asList("../abc", "a/b", "..", ".", "", "/etc/passwd")) {
            assertThrows(name, CloudRuntimeException.class, () -> VolumeFileTransfer.fileIn("/pool", name));
        }
        assertThrows(CloudRuntimeException.class, () -> VolumeFileTransfer.checkName("a,b"));
        assertThrows(CloudRuntimeException.class, () -> VolumeFileTransfer.checkName("-a"));
        assertEquals("f00dcafe-0000-4000-8000-0000000000aa", VolumeFileTransfer.checkName("f00dcafe-0000-4000-8000-0000000000aa"));
    }

    @Test
    public void abortStopsTheCopyAndRemovesOnlyThePart() throws Exception {
        Path pool = folder.newFolder("pool").toPath();
        Path file = pool.resolve("f00dcafe");
        Files.write(file, new byte[] {1});
        Files.write(pool.resolve("f00dcafe" + VolumeFileTransfer.PART_SUFFIX), new byte[] {2});
        FakeProcessRunner runner = new FakeProcessRunner(command -> null);
        new VolumeFileTransfer(runner, pool, pool.resolve("state")).abort("f00dcafe", file);
        assertEquals(Arrays.asList("systemctl", "stop", "cloudstack-volume-copy-f00dcafe"), runner.commands.get(0));
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(pool.resolve("f00dcafe" + VolumeFileTransfer.PART_SUFFIX)));
    }

    private static String runScript(String script, String... arguments) {
        List<String> command = new ArrayList<>(Arrays.asList("sh", "-c", script, "sh"));
        command.addAll(Arrays.asList(arguments));
        VolumeFileTransfer.ProcessResult result = new VolumeFileTransfer.SystemProcessRunner().run(command, 60_000L);
        return result.getExitCode() + ":" + result.getOutput();
    }

    private static byte[] bytes(int length, int seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    public void pullScriptRecordsTheHashOfWhatItReadsBackUpToTheExpectedLength() throws Exception {
        Path dir = folder.newFolder("pull").toPath();
        byte[] source = bytes(196768, 1);
        Path exported = dir.resolve("exported");
        Files.write(exported, Arrays.copyOf(source, 197120));
        Path part = dir.resolve("copy.part");
        Path result = dir.resolve("copy.result");

        String ran = runScript(VolumeFileTransfer.PULL_SCRIPT, part.toString(), String.valueOf(source.length), result.toString(),
                "cp", exported.toString(), part.toString());

        assertTrue(ran, ran.startsWith("0:"));
        assertEquals("the hash covers the expected length, the length is the file's own", FakeProcessRunner.sha256(source) + " 197120\n",
                new String(Files.readAllBytes(result), StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("copy.result.tmp")));
    }

    @Test
    public void pullScriptThatFailsRecordsNothing() throws Exception {
        Path dir = folder.newFolder("failed").toPath();
        Path result = dir.resolve("copy.result");

        String ran = runScript(VolumeFileTransfer.PULL_SCRIPT, dir.resolve("copy.part").toString(), "10", result.toString(), "false");

        assertFalse(ran, ran.startsWith("0:"));
        assertFalse(Files.exists(result));
    }

    @Test
    public void hashScriptRecordsTheHashAndLengthOfTheWholeFile() throws Exception {
        Path dir = folder.newFolder("hash").toPath();
        byte[] data = bytes(300001, 2);
        Path file = dir.resolve("volume");
        Files.write(file, data);
        Path result = dir.resolve("hash.result");

        String ran = runScript(VolumeFileTransfer.HASH_SCRIPT, file.toString(), result.toString());

        assertTrue(ran, ran.startsWith("0:"));
        assertEquals(FakeProcessRunner.sha256(data) + " 300001\n", new String(Files.readAllBytes(result), StandardCharsets.UTF_8));
    }

    @Test
    public void agentPackageShipsTheTransferSliceAsATopLevelLowWeightSlice() throws Exception {
        assertFalse("a name without a dash places the slice at the top level", VolumeFileTransfer.TRANSFER_SLICE.replace(".slice", "").contains("-"));
        Path slice = Path.of("../../../packaging/systemd", VolumeFileTransfer.TRANSFER_SLICE);
        List<String> lines = Files.readAllLines(slice);
        assertTrue(lines.contains("[Slice]"));
        assertTrue(lines.contains("CPUWeight=10"));
        assertTrue(lines.contains("IOWeight=10"));
        assertFalse("a copy always progresses", lines.stream().anyMatch(line -> line.contains("idle")));
        assertTrue(Files.readAllLines(Path.of("../../../debian/rules")).stream().anyMatch(line -> line.contains("packaging/systemd/" + VolumeFileTransfer.TRANSFER_SLICE)
                && line.contains("$(PACKAGE)-agent/lib/systemd/system/")));
    }
}
