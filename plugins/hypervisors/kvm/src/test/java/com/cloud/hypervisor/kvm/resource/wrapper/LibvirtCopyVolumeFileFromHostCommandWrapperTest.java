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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostAnswer;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.FakeProcessRunner;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.storage.Storage.StoragePoolType;

public class LibvirtCopyVolumeFileFromHostCommandWrapperTest {
    private static final String POOL_UUID = "a1b2c3d4-0000-4000-8000-000000000007";
    private static final String NAME = "f00dcafe-0000-4000-8000-0000000000aa";
    private static final String BASE = "0c7f2a6e-5d0b-4f1e-9a43-1b2c3d4e5f60";
    private static final byte[] DATA = "QFIû qcow2 bytes of the overlay".getBytes(StandardCharsets.ISO_8859_1);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final long ALLOCATED = 24608L * 1024;

    private Path poolDir;
    private Path tlsDir;
    private KVMStoragePool pool;
    private LibvirtComputingResource resource;
    private StorageFilerTO poolTO;
    private FakeProcessRunner runner;
    private LibvirtCopyVolumeFileFromHostCommandWrapper wrapper;

    @Before
    public void setUp() throws IOException {
        poolDir = folder.newFolder("pool").toPath();
        tlsDir = folder.newFolder("qemu").toPath();
        for (String file : Arrays.asList("ca-cert.pem", "client-cert.pem", "client-key.pem")) {
            Files.write(tlsDir.resolve(file), new byte[] {1});
        }
        resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.isHostSecured()).thenReturn(true);
        KVMStoragePoolManager poolManager = Mockito.mock(KVMStoragePoolManager.class);
        KVMStoragePool pool = Mockito.mock(KVMStoragePool.class);
        Mockito.when(resource.getStoragePoolMgr()).thenReturn(poolManager);
        Mockito.when(poolManager.getStoragePool(StoragePoolType.Filesystem, POOL_UUID)).thenReturn(pool);
        Mockito.when(pool.getLocalPath()).thenReturn(poolDir.toString());
        KVMPhysicalDisk copied = Mockito.mock(KVMPhysicalDisk.class);
        Mockito.when(copied.getSize()).thenReturn(ALLOCATED);
        Mockito.when(pool.getPhysicalDisk(NAME)).thenReturn(copied);
        this.pool = pool;
        poolTO = Mockito.mock(StorageFilerTO.class);
        Mockito.when(poolTO.getType()).thenReturn(StoragePoolType.Filesystem);
        Mockito.when(poolTO.getUuid()).thenReturn(POOL_UUID);
        respond(command -> null);
    }

    private void respond(Function<List<String>, VolumeFileTransfer.ProcessResult> responder) {
        runner = new FakeProcessRunner(command -> {
            VolumeFileTransfer.ProcessResult answer = responder.apply(command);
            if (answer != null) {
                return answer;
            }
            if (command.contains("convert")) {
                write(Path.of(command.get(command.size() - 1)), DATA);
            }
            if (FakeProcessRunner.matches(command, "systemctl", "stop")) {
                return FakeProcessRunner.failed(5, "Failed to stop " + command.get(2) + ": Unit " + command.get(2) + " not loaded.");
            }
            return null;
        });
        wrapper = Mockito.spy(new LibvirtCopyVolumeFileFromHostCommandWrapper());
        Mockito.doReturn(new VolumeFileTransfer(runner, tlsDir)).when(wrapper).getTransfer();
    }

    private static void write(Path file, byte[] data) {
        try {
            Files.write(file, data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CopyVolumeFileFromHostCommand command(long length, String sha256, String backing) {
        return command(length, sha256, backing, backing == null ? null : "qcow2");
    }

    private CopyVolumeFileFromHostCommand command(long length, String sha256, String backing, String backingFormat) {
        return new CopyVolumeFileFromHostCommand("10.0.0.11", 10810, NAME, poolTO, NAME, "qcow2", length, sha256, backing, backingFormat, 10800, 12000);
    }

    private Path file() {
        return poolDir.resolve(NAME);
    }

    private Path part() {
        return poolDir.resolve(NAME + VolumeFileTransfer.PART_SUFFIX);
    }

    @Test
    public void copyIsVerifiedThenRenamedIntoPlace() throws IOException {
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertArrayEquals(DATA, Files.readAllBytes(file()));
        assertFalse(Files.exists(part()));
        List<String> copy = runner.find("convert");
        assertEquals(Arrays.asList("systemd-run", "--unit=cloudstack-volume-copy-" + NAME, "--collect", "--wait", "--service-type=exec",
                "--property=RuntimeMaxSec=10800", "--", "qemu-img", "convert", "-p", "-O", "raw",
                "--object", "tls-creds-x509,id=tls0,endpoint=client,dir=" + tlsDir,
                "--image-opts", "driver=raw,file.driver=nbd,file.host=10.0.0.11,file.port=10810,file.export=" + NAME + ",file.tls-creds=tls0",
                part().toString()), copy);
        List<String> dropCache = runner.find("dd");
        assertEquals(Arrays.asList("dd", "if=" + part(), "iflag=nocache", "count=0", "status=none"), dropCache);
        assertTrue("the page cache is dropped after the copy", runner.commands.indexOf(dropCache) > runner.commands.indexOf(copy));
        assertNull("no backing file, no rebase", runner.find("rebase"));
        assertNotNull(runner.find("qemu-img", "check", "-f", "qcow2", file().toString()));
        assertEquals("the agent waits a minute beyond the pull's own limit", Long.valueOf((10800 + 60) * 1000L),
                runner.timeouts.get(runner.commands.indexOf(copy)));
    }

    @Test
    public void answerCarriesThePoolsAllocationOfTheCopy() throws IOException {
        CopyVolumeFileFromHostAnswer answer = (CopyVolumeFileFromHostAnswer)wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null),
                resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(ALLOCATED, answer.getAllocatedSize());
        Mockito.verify(pool).refresh();
    }

    /** QEMU exports a raw file rounded up to whole 512-byte sectors; a never-written qcow2 file is not a multiple of 512 bytes. */
    private static byte[] freshQcow2(int length) {
        byte[] bytes = new byte[length];
        bytes[0] = 'Q';
        bytes[1] = 'F';
        bytes[2] = 'I';
        bytes[3] = (byte)0xfb;
        bytes[length - 1] = 1;
        return bytes;
    }

    @Test
    public void exportRoundedUpTo512BytesIsTrimmedToTheSourceLength() throws IOException {
        byte[] source = freshQcow2(196768);
        respond(command -> {
            if (command.contains("convert")) {
                write(Path.of(command.get(command.size() - 1)), Arrays.copyOf(source, 197120));
                return FakeProcessRunner.ok("");
            }
            return null;
        });
        Answer answer = wrapper.execute(command(source.length, VolumeFileTransfer.sha256(write(source)), null), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertArrayEquals(source, Files.readAllBytes(file()));
        assertFalse(Files.exists(part()));
    }

    @Test
    public void nonZeroBytesBeyondTheSourceLengthRefused() throws IOException {
        byte[] source = freshQcow2(196768);
        byte[] exported = Arrays.copyOf(source, 197120);
        exported[197119] = 1;
        respond(command -> {
            if (command.contains("convert")) {
                write(Path.of(command.get(command.size() - 1)), exported);
                return FakeProcessRunner.ok("");
            }
            return null;
        });
        Answer answer = wrapper.execute(command(source.length, VolumeFileTransfer.sha256(write(source)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Length mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    private Path write(byte[] data) throws IOException {
        Path scratch = folder.newFile().toPath();
        Files.write(scratch, data);
        return scratch;
    }

    @Test
    public void hashMismatchRemovesPart() throws IOException {
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write("other bytes, same len".getBytes(StandardCharsets.UTF_8))), null),
                resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void lengthMismatchRemovesPart() throws IOException {
        Answer answer = wrapper.execute(command(DATA.length + 1L, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Length mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void rebaseUsesDestinationInstallPath() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), BASE), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        List<String> rebase = runner.find("rebase");
        assertEquals(Arrays.asList("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "qcow2", "-b", poolDir.resolve(BASE).toString(), file().toString()), rebase);
        List<String> check = runner.find("qemu-img", "check");
        assertTrue("checked after the rebase", runner.commands.indexOf(check) > runner.commands.indexOf(rebase));
    }

    @Test
    public void rawBaseIsRecordedAsRawInTheOverlay() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), BASE, "raw"), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Arrays.asList("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "raw", "-b", poolDir.resolve(BASE).toString(), file().toString()),
                runner.find("rebase"));
    }

    @Test
    public void unsupportedBaseFormatRefusedBeforeCopying() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), BASE, "vmdk"), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("vmdk"));
        assertNull(runner.find("convert"));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void missingBaseOnDestinationRefusedBeforeCopying() throws IOException {
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), BASE), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("not on the destination pool"));
        assertNull(runner.find("convert"));
    }

    @Test
    public void nonTlsRefused() throws IOException {
        Mockito.when(resource.isHostSecured()).thenReturn(false);
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("only over TLS"));
        assertTrue("nothing runs on an unsecured host", runner.commands.isEmpty());
    }

    @Test
    public void missingClientCredentialsRefused() throws IOException {
        Files.delete(tlsDir.resolve("client-key.pem"));
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("client-key.pem"));
        assertNull(runner.find("convert"));
    }

    @Test
    public void timeoutRemovesPart() throws IOException {
        respond(command -> {
            if (command.contains("convert")) {
                write(Path.of(command.get(command.size() - 1)), Arrays.copyOf(DATA, 4));
                return FakeProcessRunner.timedOut();
            }
            return null;
        });
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("did not finish within 10800 seconds"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
        List<String> copy = runner.find("convert");
        List<String> stop = runner.commands.get(runner.commands.size() - 1);
        assertEquals(Arrays.asList("systemctl", "stop", "cloudstack-volume-copy-" + NAME), stop);
        assertTrue("stopped after the copy timed out", runner.commands.lastIndexOf(stop) > runner.commands.indexOf(copy));
        assertEquals("the agent waits a minute beyond the unit's own limit", Long.valueOf((10800 + 60) * 1000L),
                runner.timeouts.get(runner.commands.indexOf(copy)));
    }

    @Test
    public void failedCopyRemovesPartAndReportsTheUnitLog() throws IOException {
        respond(command -> {
            if (command.contains("convert")) {
                write(Path.of(command.get(command.size() - 1)), Arrays.copyOf(DATA, 4));
                return FakeProcessRunner.failed(1, "Finished with result: exit-code");
            }
            if (command.contains("journalctl")) {
                return FakeProcessRunner.ok("qemu-img: Could not open: TLS x509 authz check is denied");
            }
            return null;
        });
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("authz check is denied"));
        assertFalse(Files.exists(part()));
        assertNotNull(runner.find("journalctl", "-u", "cloudstack-volume-copy-" + NAME));
    }

    @Test
    public void failedCheckRemovesTheCopiedFile() throws IOException {
        respond(command -> FakeProcessRunner.matches(command, "qemu-img", "check") ? FakeProcessRunner.failed(2, "2 errors were found") : null);
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(part()));
    }

    @Test
    public void existingDestinationNeverOverwritten() throws IOException {
        byte[] other = "someone else's file".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), other);
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("already exists"));
        assertArrayEquals(other, Files.readAllBytes(file()));
        assertNull(runner.find("convert"));
    }

    @Test
    public void destinationHoldingTheExpectedBytesCountsAsCopied() throws IOException {
        Files.write(file(), DATA);
        Answer answer = wrapper.execute(command(DATA.length, VolumeFileTransfer.sha256(write(DATA)), null), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertNull(runner.find("convert"));
    }
}
