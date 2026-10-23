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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.FakeProcessRunner;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.storage.Storage.StoragePoolType;

public class LibvirtStartVolumeExportCommandWrapperTest {
    private static final String POOL_UUID = "a1b2c3d4-0000-4000-8000-000000000007";
    private static final String VOLUME = "8e1d4c2b-7a6f-4e3d-b2c1-a0f9e8d7c6b5";
    private static final String NAME = "f00dcafe-0000-4000-8000-0000000000aa";
    private static final String DN = "CN=dst.example.org,OU=cloudstack,O=cloudstack,C=cloudstack";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path poolDir;
    private Path tlsDir;
    private Path stateDir;
    private LibvirtComputingResource resource;
    private StartVolumeExportCommand command;
    private FakeProcessRunner runner;

    @Before
    public void setUp() throws IOException {
        poolDir = folder.newFolder("pool").toPath();
        tlsDir = folder.newFolder("qemu").toPath();
        stateDir = folder.getRoot().toPath().resolve("state");
        Files.write(poolDir.resolve(VOLUME), new byte[] {1, 2, 3});
        for (String file : Arrays.asList("ca-cert.pem", "server-cert.pem", "server-key.pem")) {
            Files.write(tlsDir.resolve(file), new byte[] {1});
        }
        resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.isHostSecured()).thenReturn(true);
        Mockito.when(resource.getPrivateIp()).thenReturn("10.0.0.11");
        KVMStoragePoolManager poolManager = Mockito.mock(KVMStoragePoolManager.class);
        KVMStoragePool pool = Mockito.mock(KVMStoragePool.class);
        Mockito.when(resource.getStoragePoolMgr()).thenReturn(poolManager);
        Mockito.when(poolManager.getStoragePool(StoragePoolType.Filesystem, POOL_UUID)).thenReturn(pool);
        Mockito.when(pool.getLocalPath()).thenReturn(poolDir.toString());
        StorageFilerTO poolTO = Mockito.mock(StorageFilerTO.class);
        Mockito.when(poolTO.getType()).thenReturn(StoragePoolType.Filesystem);
        Mockito.when(poolTO.getUuid()).thenReturn(POOL_UUID);
        command = new StartVolumeExportCommand(poolTO, VOLUME, NAME, DN, 11100);
    }

    private LibvirtStartVolumeExportCommandWrapper wrapper(Function<List<String>, VolumeFileTransfer.ProcessResult> responder) {
        runner = new FakeProcessRunner(responder);
        VolumeFileTransfer transfer = new VolumeFileTransfer(runner, tlsDir, stateDir) {
            @Override
            protected boolean isPortFree(String address, int port) {
                return true;
            }

            @Override
            protected void sleep(long millis) {
            }
        };
        LibvirtStartVolumeExportCommandWrapper wrapper = Mockito.spy(new LibvirtStartVolumeExportCommandWrapper());
        Mockito.doReturn(transfer).when(wrapper).getTransfer();
        return wrapper;
    }

    private static VolumeFileTransfer.ProcessResult listening(List<String> command, int port) {
        if (command.get(0).equals("ss")) {
            return command.contains(":" + port) ? FakeProcessRunner.ok("LISTEN 0 4096 10.0.0.11:" + port + " 0.0.0.0:*") : FakeProcessRunner.ok("");
        }
        return null;
    }

    @Test
    public void exportServesTheFileReadOnlyToExactlyTheDestination() {
        StartVolumeExportAnswer answer = (StartVolumeExportAnswer)wrapper(command -> listening(command, 10809)).execute(command, resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("10.0.0.11", answer.getAddress());
        assertEquals(10809, answer.getPort());
        List<String> start = runner.find("systemd-run");
        assertEquals(Arrays.asList("systemd-run", "--unit=cloudstack-volume-export-" + NAME, "--slice=volumetransfer.slice", "--collect",
                "--service-type=exec", "--expand-environment=no", "--property=RuntimeMaxSec=11100", "--",
                "qemu-nbd", "--read-only", "--persistent", "--format=raw", "--export-name=" + NAME,
                "--object", "tls-creds-x509,id=tls0,endpoint=server,dir=" + tlsDir + ",verify-peer=on",
                "--object", "{\"qom-type\":\"authz-simple\",\"id\":\"authz0\",\"identity\":\"" + DN + "\"}",
                "--tls-creds=tls0", "--tls-authz=authz0", "--bind=10.0.0.11", "--port=10809", poolDir.resolve(VOLUME).toString()), start);
        assertEquals("a leftover unit of the same name is stopped first", Arrays.asList("systemctl", "stop", "cloudstack-volume-export-" + NAME),
                runner.commands.get(0));
    }

    @Test
    public void portInUseMovesToTheNextPort() {
        AtomicInteger starts = new AtomicInteger();
        StartVolumeExportAnswer answer = (StartVolumeExportAnswer)wrapper(command -> {
            if (command.get(0).equals("systemd-run")) {
                starts.incrementAndGet();
                return null;
            }
            if (command.get(0).equals("systemctl") && command.get(1).equals("is-active")) {
                return starts.get() == 1 ? FakeProcessRunner.failed(4, "inactive") : FakeProcessRunner.ok("active");
            }
            if (command.get(0).equals("journalctl")) {
                return FakeProcessRunner.ok("qemu-nbd: Failed to find an available port: Address already in use");
            }
            return listening(command, 10810);
        }).execute(command, resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(10810, answer.getPort());
        assertEquals(2, starts.get());
        assertNotNull(runner.find("journalctl", "-u", "cloudstack-volume-export-" + NAME));
    }

    @Test
    public void exportThatEndsForAnotherReasonFails() {
        StartVolumeExportAnswer answer = (StartVolumeExportAnswer)wrapper(command -> {
            if (command.get(0).equals("systemctl") && command.get(1).equals("is-active")) {
                return FakeProcessRunner.failed(4, "inactive");
            }
            if (command.get(0).equals("journalctl")) {
                return FakeProcessRunner.ok("qemu-nbd: Failed to get shared \"resize\" lock");
            }
            return listening(command, -1);
        }).execute(command, resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("lock"));
        assertEquals("no other port is tried", 1, runner.commands.stream().filter(c -> c.get(0).equals("systemd-run")).count());
    }

    @Test
    public void exportWhoseStateSystemdCannotTellIsNotReadAsEnded() {
        Answer answer = wrapper(command -> {
            if (FakeProcessRunner.matches(command, "systemctl", "is-active")) {
                return FakeProcessRunner.failed(1, "Failed to connect to bus: No such file or directory");
            }
            return listening(command, -1);
        }).execute(command, resource);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("Unable to read the state"));
        assertEquals("no other port is tried", 1, runner.commands.stream().filter(c -> c.get(0).equals("systemd-run")).count());
    }

    @Test
    public void nonTlsRefused() {
        Mockito.when(resource.isHostSecured()).thenReturn(false);
        LibvirtStartVolumeExportCommandWrapper wrapper = wrapper(command -> null);
        Answer answer = wrapper.execute(command, resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("only over TLS"));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    public void missingServerCredentialsRefused() throws IOException {
        Files.delete(tlsDir.resolve("server-key.pem"));
        Answer answer = wrapper(command -> listening(command, 10809)).execute(command, resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("server-key.pem"));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    public void stopStopsTheExportAndTheHashingAndToleratesMissingOnes() throws IOException {
        FakeProcessRunner stopRunner = new FakeProcessRunner(command -> FakeProcessRunner.failed(5, "Failed to stop x.service: Unit x.service not loaded."));
        LibvirtStopVolumeExportCommandWrapper stop = Mockito.spy(new LibvirtStopVolumeExportCommandWrapper());
        Mockito.doReturn(new VolumeFileTransfer(stopRunner, tlsDir, stateDir)).when(stop).getTransfer();
        Files.createDirectories(stateDir);
        Files.write(stateDir.resolve("cloudstack-volume-hash-" + NAME + ".result"), "0 1".getBytes(StandardCharsets.UTF_8));
        Answer answer = stop.execute(new StopVolumeExportCommand(NAME), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals(Arrays.asList(Arrays.asList("systemctl", "stop", "cloudstack-volume-export-" + NAME),
                Arrays.asList("systemctl", "stop", "cloudstack-volume-hash-" + NAME)), stopRunner.commands);
        assertFalse("the hashing's outcome is forgotten", Files.exists(stateDir.resolve("cloudstack-volume-hash-" + NAME + ".result")));
    }

    @Test
    public void exportRefusedWhileTheSliceMayRunOnOtherCpusThanTheHostsOwnProcesses() {
        Answer answer = wrapper(command -> {
            if (FakeProcessRunner.matches(command, "systemctl", "show", "AllowedCPUs")) {
                return FakeProcessRunner.ok(command.contains("system.slice") ? "13 27" : "");
            }
            return listening(command, 10809);
        }).execute(command, resource);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("volumetransfer.slice"));
        assertTrue("no unit starts", runner.commands.stream().noneMatch(c -> c.get(0).equals("systemd-run")));
    }
}
