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
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.CheckVolumeFileTransferAnswer;
import com.cloud.agent.api.storage.CheckVolumeFileTransferCommand;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.storage.FinishVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.FinishVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StartVolumeFileHashCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.FakeProcessRunner;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.storage.Storage.StoragePoolType;

/**
 * The destination's side of a copy: the pull starts as a unit and the request returns, the unit is followed, and the
 * finish puts the file in place from what the unit recorded. The fake runner plays the units: a pull writes the
 * partial file and records the hash and length of what it holds, as the unit's script does.
 */
public class LibvirtCopyVolumeFileFromHostCommandWrapperTest {
    private static final String POOL_UUID = "a1b2c3d4-0000-4000-8000-000000000007";
    private static final String NAME = "f00dcafe-0000-4000-8000-0000000000aa";
    private static final String BASE = "0c7f2a6e-5d0b-4f1e-9a43-1b2c3d4e5f60";
    private static final String UNIT = "cloudstack-volume-copy-" + NAME;
    private static final byte[] DATA = "QFIû qcow2 bytes of the overlay".getBytes(StandardCharsets.ISO_8859_1);
    private static final long ALLOCATED = 24608L * 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path poolDir;
    private Path tlsDir;
    private Path stateDir;
    private KVMStoragePool pool;
    private LibvirtComputingResource resource;
    private StorageFilerTO poolTO;
    private FakeProcessRunner runner;
    private VolumeFileTransfer transfer;
    /** What the pull writes into the partial file; null for a pull that ends without recording anything. */
    private byte[] pulled = DATA;
    private String unitState = "inactive";

    @Before
    public void setUp() throws IOException {
        poolDir = folder.newFolder("pool").toPath();
        tlsDir = folder.newFolder("qemu").toPath();
        stateDir = folder.getRoot().toPath().resolve("state");
        for (String file : Arrays.asList("ca-cert.pem", "client-cert.pem", "client-key.pem")) {
            Files.write(tlsDir.resolve(file), new byte[] {1});
        }
        resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.lenient().when(resource.isHostSecured()).thenReturn(true);
        KVMStoragePoolManager poolManager = Mockito.mock(KVMStoragePoolManager.class);
        pool = Mockito.mock(KVMStoragePool.class);
        Mockito.lenient().when(resource.getStoragePoolMgr()).thenReturn(poolManager);
        Mockito.lenient().when(poolManager.getStoragePool(StoragePoolType.Filesystem, POOL_UUID)).thenReturn(pool);
        Mockito.lenient().when(pool.getLocalPath()).thenReturn(poolDir.toString());
        KVMPhysicalDisk copied = Mockito.mock(KVMPhysicalDisk.class);
        Mockito.lenient().when(copied.getSize()).thenReturn(ALLOCATED);
        Mockito.lenient().when(pool.getPhysicalDisk(NAME)).thenReturn(copied);
        poolTO = Mockito.mock(StorageFilerTO.class);
        Mockito.lenient().when(poolTO.getType()).thenReturn(StoragePoolType.Filesystem);
        Mockito.lenient().when(poolTO.getUuid()).thenReturn(POOL_UUID);
        respond(command -> null);
    }

    private void respond(Function<List<String>, VolumeFileTransfer.ProcessResult> responder) {
        runner = new FakeProcessRunner(command -> {
            VolumeFileTransfer.ProcessResult answer = responder.apply(command);
            if (answer != null) {
                return answer;
            }
            if (command.get(0).equals("systemd-run")) {
                return playUnit(command);
            }
            if (FakeProcessRunner.matches(command, "systemctl", "is-active")) {
                // an ended unit started with --collect is unloaded: systemctl reports it inactive and unknown (exit 4)
                return "active".equals(unitState) ? FakeProcessRunner.ok("active") : FakeProcessRunner.failed(4, unitState);
            }
            if (FakeProcessRunner.matches(command, "systemctl", "stop")) {
                return FakeProcessRunner.failed(5, "Failed to stop " + command.get(2) + ": Unit " + command.get(2) + " not loaded.");
            }
            return null;
        });
        transfer = new VolumeFileTransfer(runner, tlsDir, stateDir);
    }

    private VolumeFileTransfer.ProcessResult playUnit(List<String> command) {
        try {
            int pull = command.indexOf(VolumeFileTransfer.PULL_SCRIPT);
            if (pull > 0) {
                Path part = Path.of(command.get(pull + 2));
                long length = Long.parseLong(command.get(pull + 3));
                if (pulled != null) {
                    Files.write(part, pulled);
                    record(Path.of(command.get(pull + 4)), FakeProcessRunner.sha256(Arrays.copyOf(pulled, (int)Math.min(length, pulled.length))),
                            pulled.length);
                }
            }
            int hash = command.indexOf(VolumeFileTransfer.HASH_SCRIPT);
            if (hash > 0) {
                Path file = Path.of(command.get(hash + 2));
                record(Path.of(command.get(hash + 3)), FakeProcessRunner.sha256(file), Files.size(file));
            }
            return FakeProcessRunner.ok("Running as unit: " + command.get(1).substring("--unit=".length()) + ".service");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(Path result, String sha256, long length) throws IOException {
        Files.write(result, (sha256 + " " + length + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private LibvirtCopyVolumeFileFromHostCommandWrapper startWrapper() {
        LibvirtCopyVolumeFileFromHostCommandWrapper wrapper = Mockito.spy(new LibvirtCopyVolumeFileFromHostCommandWrapper());
        Mockito.doReturn(transfer).when(wrapper).getTransfer();
        return wrapper;
    }

    private Answer start(long expectedLength) {
        return startWrapper().execute(new CopyVolumeFileFromHostCommand("10.0.0.11", 10810, NAME, poolTO, NAME, expectedLength, 11400), resource);
    }

    private CheckVolumeFileTransferAnswer check(CheckVolumeFileTransferCommand.Kind kind) {
        LibvirtCheckVolumeFileTransferCommandWrapper wrapper = Mockito.spy(new LibvirtCheckVolumeFileTransferCommandWrapper());
        Mockito.doReturn(transfer).when(wrapper).getTransfer();
        return (CheckVolumeFileTransferAnswer)wrapper.execute(new CheckVolumeFileTransferCommand(kind, NAME), resource);
    }

    private FinishVolumeFileCopyAnswer finish(long expectedLength, String sha256, String backing, String backingFormat) {
        LibvirtFinishVolumeFileCopyCommandWrapper wrapper = Mockito.spy(new LibvirtFinishVolumeFileCopyCommandWrapper());
        Mockito.doReturn(transfer).when(wrapper).getTransfer();
        return (FinishVolumeFileCopyAnswer)wrapper.execute(new FinishVolumeFileCopyCommand(poolTO, NAME, NAME, "qcow2", expectedLength, sha256, backing,
                backingFormat), resource);
    }

    private FinishVolumeFileCopyAnswer copy(long expectedLength, String sha256, String backing, String backingFormat) {
        Answer started = start(expectedLength);
        assertTrue(started.getDetails(), started.getResult());
        return finish(expectedLength, sha256, backing, backingFormat);
    }

    private Path file() {
        return poolDir.resolve(NAME);
    }

    private Path part() {
        return poolDir.resolve(NAME + VolumeFileTransfer.PART_SUFFIX);
    }

    private Path result() {
        return stateDir.resolve(UNIT + ".result");
    }

    @Test
    public void pullRunsAsALowPriorityUnitAndTheRequestReturnsAtOnce() {
        Answer answer = start(DATA.length);

        assertTrue(answer.getDetails(), answer.getResult());
        List<String> pull = runner.find("systemd-run");
        assertEquals(Arrays.asList("systemd-run", "--unit=" + UNIT, "--slice=volumetransfer.slice", "--collect", "--service-type=exec",
                "--expand-environment=no", "--property=RuntimeMaxSec=11400", "--",
                "sh", "-c", VolumeFileTransfer.PULL_SCRIPT, "sh", part().toString(), String.valueOf(DATA.length), result().toString(),
                "qemu-img", "convert", "-p", "-t", "none", "-O", "raw",
                "--object", "tls-creds-x509,id=tls0,endpoint=client,dir=" + tlsDir,
                "--image-opts", "driver=raw,file.driver=nbd,file.host=10.0.0.11,file.port=10810,file.export=" + NAME + ",file.tls-creds=tls0",
                part().toString()), pull);
        assertFalse("the agent does not wait for the unit", pull.contains("--wait"));
        assertTrue("starting the unit is a short command", runner.timeouts.get(runner.commands.indexOf(pull)) <= 120_000L);
        assertNull("reading back and hashing belong to the unit", runner.find("dd"));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void checkFollowsTheUnitUntilItEnds() {
        start(DATA.length);

        unitState = "active";
        assertEquals(CheckVolumeFileTransferAnswer.State.RUNNING, check(CheckVolumeFileTransferCommand.Kind.PULL).getState());

        unitState = "inactive";
        CheckVolumeFileTransferAnswer ended = check(CheckVolumeFileTransferCommand.Kind.PULL);
        assertTrue(ended.getDetails(), ended.getResult());
        assertEquals(CheckVolumeFileTransferAnswer.State.SUCCEEDED, ended.getState());
        assertEquals(FakeProcessRunner.sha256(DATA), ended.getSha256());
        assertEquals(DATA.length, ended.getLength());
        assertNotNull(runner.find("systemctl", "is-active", UNIT));
    }

    @Test
    public void endedUnitIsReadAsEndedWhetherSystemdStillHoldsItOrHasCollectedIt() {
        start(DATA.length);
        for (VolumeFileTransfer.ProcessResult ended : Arrays.asList(FakeProcessRunner.failed(3, "inactive"), FakeProcessRunner.failed(4, "inactive"))) {
            respond(command -> FakeProcessRunner.matches(command, "systemctl", "is-active") ? ended : null);

            CheckVolumeFileTransferAnswer checked = check(CheckVolumeFileTransferCommand.Kind.PULL);

            assertTrue(checked.getDetails(), checked.getResult());
            assertEquals(CheckVolumeFileTransferAnswer.State.SUCCEEDED, checked.getState());
        }
    }

    @Test
    public void copyIsVerifiedThenRenamedIntoPlace() throws IOException {
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), null, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(FakeProcessRunner.sha256(DATA), answer.getSha256());
        assertArrayEquals(DATA, Files.readAllBytes(file()));
        assertFalse(Files.exists(part()));
        assertFalse("the outcome is forgotten once used", Files.exists(result()));
        assertNull("no backing file, no rebase", runner.find("rebase"));
        assertNotNull(runner.find("qemu-img", "check", "-f", "qcow2", file().toString()));
    }

    @Test
    public void answerCarriesThePoolsAllocationOfTheCopy() {
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), null, null);
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
        pulled = Arrays.copyOf(source, 197120);

        FinishVolumeFileCopyAnswer answer = copy(source.length, FakeProcessRunner.sha256(source), null, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertArrayEquals(source, Files.readAllBytes(file()));
        assertFalse(Files.exists(part()));
    }

    @Test
    public void nonZeroBytesBeyondTheSourceLengthRefused() {
        byte[] source = freshQcow2(196768);
        pulled = Arrays.copyOf(source, 197120);
        pulled[197119] = 1;

        FinishVolumeFileCopyAnswer answer = copy(source.length, FakeProcessRunner.sha256(source), null, null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Length mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    /** A write without the page cache covers whole logical blocks of the destination device, which may be 4096 bytes. */
    @Test
    public void writeRoundedUpToA4096ByteBlockIsTrimmedToTheSourceLength() throws IOException {
        byte[] source = freshQcow2(196768);
        pulled = Arrays.copyOf(source, 200704);

        FinishVolumeFileCopyAnswer answer = copy(source.length, FakeProcessRunner.sha256(source), null, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertArrayEquals(source, Files.readAllBytes(file()));
    }

    @Test
    public void onlyZerosUpTo512Or4096ByteRoundingAreDropped() {
        byte[] source = freshQcow2(196768);
        byte[] nonZeroInABlock = Arrays.copyOf(source, 200704);
        nonZeroInABlock[200703] = 1;
        for (byte[] tail : Arrays.asList(nonZeroInABlock, Arrays.copyOf(source, 197632), Arrays.copyOf(source, 196768 + 4096))) {
            pulled = tail;

            FinishVolumeFileCopyAnswer answer = copy(source.length, FakeProcessRunner.sha256(source), null, null);

            assertFalse(tail.length + " bytes", answer.getResult());
            assertTrue(answer.getDetails(), answer.getDetails().contains("Length mismatch"));
            assertFalse(Files.exists(file()));
        }
    }

    @Test
    public void hashMismatchRemovesPart() {
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256("other bytes, same len".getBytes(StandardCharsets.UTF_8)), null, null);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void lengthMismatchRemovesPart() {
        FinishVolumeFileCopyAnswer answer = copy(DATA.length + 1L, FakeProcessRunner.sha256(DATA), null, null);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Length mismatch"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void rebaseUsesDestinationInstallPath() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), BASE, "qcow2");
        assertTrue(answer.getDetails(), answer.getResult());
        List<String> rebase = runner.find("rebase");
        assertEquals(Arrays.asList("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "qcow2", "-b", poolDir.resolve(BASE).toString(), file().toString()), rebase);
        List<String> check = runner.find("qemu-img", "check");
        assertTrue("checked after the rebase", runner.commands.indexOf(check) > runner.commands.indexOf(rebase));
    }

    @Test
    public void rawBaseIsRecordedAsRawInTheOverlay() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), BASE, "raw");
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Arrays.asList("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "raw", "-b", poolDir.resolve(BASE).toString(), file().toString()),
                runner.find("rebase"));
    }

    @Test
    public void unsupportedBaseFormatRefusedBeforeTheFileTakesItsName() throws IOException {
        Files.write(poolDir.resolve(BASE), new byte[] {7});
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), BASE, "vmdk");
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("vmdk"));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void missingBaseOnDestinationRefused() {
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), BASE, "qcow2");
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("not on the destination pool"));
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(part()));
    }

    @Test
    public void nonTlsRefused() {
        Mockito.when(resource.isHostSecured()).thenReturn(false);
        Answer answer = start(DATA.length);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("only over TLS"));
        assertTrue("nothing runs on an unsecured host", runner.commands.isEmpty());
    }

    @Test
    public void missingClientCredentialsRefused() throws IOException {
        Files.delete(tlsDir.resolve("client-key.pem"));
        Answer answer = start(DATA.length);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("client-key.pem"));
        assertNull(runner.find("systemd-run"));
    }

    @Test
    public void pullThatEndsWithoutAnOutcomeIsReportedWithTheUnitLog() throws IOException {
        pulled = null;
        respond(command -> command.contains("journalctl") ? FakeProcessRunner.ok("qemu-img: Could not open: TLS x509 authz check is denied") : null);
        start(DATA.length);
        Files.write(part(), Arrays.copyOf(DATA, 4));

        CheckVolumeFileTransferAnswer checked = check(CheckVolumeFileTransferCommand.Kind.PULL);
        assertEquals(CheckVolumeFileTransferAnswer.State.FAILED, checked.getState());
        assertTrue(checked.getDetails(), checked.getDetails().contains("authz check is denied"));
        List<String> journal = runner.find("journalctl", "-u", UNIT);
        assertTrue("the log of this run only", journal.get(journal.size() - 1).startsWith("--since=@"));

        FinishVolumeFileCopyAnswer answer = finish(DATA.length, FakeProcessRunner.sha256(DATA), null, null);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("did not complete"));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(file()));
    }

    @Test
    public void pullThatRecordsAnUnreadableOutcomeIsReportedWithWhatItRecorded() throws IOException {
        start(DATA.length);
        Files.write(result(), (" " + DATA.length + "\n").getBytes(StandardCharsets.UTF_8));

        CheckVolumeFileTransferAnswer checked = check(CheckVolumeFileTransferCommand.Kind.PULL);
        assertEquals(CheckVolumeFileTransferAnswer.State.FAILED, checked.getState());
        assertTrue(checked.getDetails(), checked.getDetails().contains("[" + DATA.length + "]"));
        assertFalse("the unit did end", checked.getDetails().contains("without completing"));
    }

    @Test
    public void finishRefusedWhileThePullRuns() {
        start(DATA.length);
        unitState = "active";

        FinishVolumeFileCopyAnswer answer = finish(DATA.length, FakeProcessRunner.sha256(DATA), null, null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("still runs"));
        assertTrue("a running pull keeps its file", Files.exists(part()));
    }

    @Test
    public void failedCheckRemovesTheCopiedFile() {
        respond(command -> FakeProcessRunner.matches(command, "qemu-img", "check") ? FakeProcessRunner.failed(2, "2 errors were found") : null);
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), null, null);
        assertFalse(answer.getResult());
        assertFalse(Files.exists(file()));
        assertFalse(Files.exists(part()));
    }

    @Test
    public void existingDestinationNeverOverwritten() throws IOException {
        byte[] other = "someone else's file".getBytes(StandardCharsets.UTF_8);
        Files.write(file(), other);
        Answer started = start(DATA.length);
        assertFalse(started.getResult());
        assertTrue(started.getDetails(), started.getDetails().contains("already exists"));
        assertNull(runner.find("systemd-run"));

        byte[] sameLength = Arrays.copyOf(other, DATA.length);
        Files.write(file(), sameLength);
        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), null, null);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("already exists"));
        assertArrayEquals(sameLength, Files.readAllBytes(file()));
        assertNull("an existing file is only hashed", runner.find("qemu-img", "convert"));
    }

    @Test
    public void destinationHoldingTheExpectedBytesCountsAsCopied() throws IOException {
        Files.write(file(), DATA);

        FinishVolumeFileCopyAnswer answer = copy(DATA.length, FakeProcessRunner.sha256(DATA), null, null);

        assertTrue(answer.getDetails(), answer.getResult());
        assertNotNull("the existing file is hashed by the pull's unit", runner.find("--unit=" + UNIT, VolumeFileTransfer.HASH_SCRIPT, file().toString()));
        assertNull(runner.find("qemu-img", "convert"));
        assertArrayEquals(DATA, Files.readAllBytes(file()));
    }

    @Test
    public void abortStopsThePullAndForgetsItsOutcome() throws IOException {
        start(DATA.length);
        Files.write(file(), DATA);
        LibvirtAbortVolumeFileCopyCommandWrapper abort = Mockito.spy(new LibvirtAbortVolumeFileCopyCommandWrapper());
        Mockito.doReturn(transfer).when(abort).getTransfer();

        Answer answer = abort.execute(new AbortVolumeFileCopyCommand(poolTO, NAME, NAME), resource);

        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Arrays.asList(Arrays.asList("systemctl", "stop", UNIT), Arrays.asList("systemctl", "stop", "cloudstack-volume-hash-" + NAME)),
                runner.commands.subList(runner.commands.size() - 2, runner.commands.size()));
        assertFalse(Files.exists(part()));
        assertFalse(Files.exists(result()));
        assertTrue("the completed file is left alone", Files.exists(file()));
    }

    @Test
    public void hashingRunsAsALowPriorityUnitWhoseOutcomeTheCheckReports() throws IOException {
        Files.write(file(), DATA);
        LibvirtStartVolumeFileHashCommandWrapper hash = Mockito.spy(new LibvirtStartVolumeFileHashCommandWrapper());
        Mockito.doReturn(transfer).when(hash).getTransfer();

        Answer started = hash.execute(new StartVolumeFileHashCommand(poolTO, NAME, NAME, 11400), resource);

        assertTrue(started.getDetails(), started.getResult());
        assertEquals(Arrays.asList("systemd-run", "--unit=cloudstack-volume-hash-" + NAME, "--slice=volumetransfer.slice", "--collect",
                "--service-type=exec", "--expand-environment=no", "--property=RuntimeMaxSec=11400", "--",
                "sh", "-c", VolumeFileTransfer.HASH_SCRIPT, "sh", file().toString(), stateDir.resolve("cloudstack-volume-hash-" + NAME + ".result").toString()),
                runner.find("systemd-run"));
        CheckVolumeFileTransferAnswer hashed = check(CheckVolumeFileTransferCommand.Kind.HASH);
        assertEquals(CheckVolumeFileTransferAnswer.State.SUCCEEDED, hashed.getState());
        assertEquals(FakeProcessRunner.sha256(DATA), hashed.getSha256());
        assertEquals(DATA.length, hashed.getLength());
    }

    @Test
    public void transferRefusedBeforeAnyUnitWhileTheSliceMayRunOnOtherCpusThanTheHostsOwnProcesses() {
        respond(command -> {
            if (FakeProcessRunner.matches(command, "systemctl", "show", "AllowedCPUs")) {
                return FakeProcessRunner.ok(command.contains("system.slice") ? "13 27\n" : "\n");
            }
            return null;
        });

        Answer answer = start(DATA.length);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("volumetransfer.slice") && answer.getDetails().contains("[13, 27]"));
        assertNull("no unit starts", runner.find("systemd-run"));
    }

    @Test
    public void transferStartsWhenTheSliceIsConfinedLikeTheHostsOwnProcesses() {
        respond(command -> {
            if (FakeProcessRunner.matches(command, "systemctl", "show", "AllowedCPUs")) {
                return FakeProcessRunner.ok(command.contains("system.slice") ? "13 27\n" : "13,27\n");
            }
            return null;
        });

        Answer answer = start(DATA.length);

        assertTrue(answer.getDetails(), answer.getResult());
        assertNotNull(runner.find("systemd-run"));
    }

    @Test
    public void unitStateSystemdCannotTellIsNoOutcome() {
        start(DATA.length);
        respond(command -> FakeProcessRunner.matches(command, "systemctl", "is-active")
                ? FakeProcessRunner.failed(1, "Failed to connect to bus: No such file or directory") : null);

        CheckVolumeFileTransferAnswer checked = check(CheckVolumeFileTransferCommand.Kind.PULL);
        assertFalse("an error answer, which the management server asks again", checked.getResult());
        assertNull(checked.getState());
        assertTrue(checked.getDetails(), checked.getDetails().contains("Unable to read the state"));

        FinishVolumeFileCopyAnswer finished = finish(DATA.length, FakeProcessRunner.sha256(DATA), null, null);
        assertFalse(finished.getResult());
        assertTrue("the pull's file stays while its state is unknown", Files.exists(part()));

        respond(command -> FakeProcessRunner.matches(command, "systemctl", "is-active") ? FakeProcessRunner.timedOut() : null);
        assertFalse(check(CheckVolumeFileTransferCommand.Kind.PULL).getResult());
    }
}
