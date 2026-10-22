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

import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.FakeProcessRunner;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.storage.Storage.StoragePoolType;

public class LibvirtInspectVolumeFileCommandWrapperTest {
    private static final String POOL_UUID = "a1b2c3d4-0000-4000-8000-000000000007";
    private static final String VOLUME = "8e1d4c2b-7a6f-4e3d-b2c1-a0f9e8d7c6b5";
    private static final byte[] DATA = "overlay bytes".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path poolDir;
    private LibvirtComputingResource resource;
    private KVMStoragePool pool;
    private InspectVolumeFileCommand command;
    private FakeProcessRunner runner;
    private LibvirtInspectVolumeFileCommandWrapper wrapper;

    @Before
    public void setUp() throws IOException {
        poolDir = folder.newFolder("pool").toPath();
        Files.write(poolDir.resolve(VOLUME), DATA);
        resource = Mockito.mock(LibvirtComputingResource.class);
        KVMStoragePoolManager poolManager = Mockito.mock(KVMStoragePoolManager.class);
        pool = Mockito.mock(KVMStoragePool.class);
        Mockito.when(resource.getStoragePoolMgr()).thenReturn(poolManager);
        Mockito.when(poolManager.getStoragePool(StoragePoolType.Filesystem, POOL_UUID)).thenReturn(pool);
        Mockito.when(pool.getLocalPath()).thenReturn(poolDir.toString());
        StorageFilerTO poolTO = Mockito.mock(StorageFilerTO.class);
        Mockito.when(poolTO.getType()).thenReturn(StoragePoolType.Filesystem);
        Mockito.when(poolTO.getUuid()).thenReturn(POOL_UUID);
        command = new InspectVolumeFileCommand(poolTO, VOLUME, "qcow2", 10800);
    }

    private void respond(Function<List<String>, VolumeFileTransfer.ProcessResult> responder) {
        runner = new FakeProcessRunner(responder);
        wrapper = Mockito.spy(new LibvirtInspectVolumeFileCommandWrapper());
        Mockito.doReturn(new VolumeFileTransfer(runner, folder.getRoot().toPath(), folder.getRoot().toPath().resolve("state"))).when(wrapper).getTransfer();
    }

    private static String chain(int images) {
        return chain(images, "qcow2");
    }

    private static String chain(int images, String baseFormat) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < images; i++) {
            json.append(i == 0 ? "" : ",").append("{\"virtual-size\": 4294967296, \"filename\": \"/pool/image").append(i)
                    .append("\", \"format\": \"").append(i + 1 == images && i > 0 ? baseFormat : "qcow2").append("\"");
            if (i + 1 < images) {
                json.append(", \"backing-filename\": \"/pool/image").append(i + 1).append("\", \"full-backing-filename\": \"/pool/image").append(i + 1);
                if (baseFormat != null) {
                    json.append("\", \"backing-filename-format\": \"").append(i + 2 == images ? baseFormat : "qcow2");
                }
                json.append("\"");
            }
            json.append("}");
        }
        return json.append("]").toString();
    }

    @Test
    public void inspectionReportsFormatSizesAndChainWithoutReadingTheData() throws IOException {
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(2)) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("qcow2", answer.getFormat());
        assertEquals(4294967296L, answer.getVirtualSize());
        assertEquals(DATA.length, answer.getLength());
        assertEquals("/pool/image1", answer.getBackingFile());
        assertEquals("the format the overlay records for its base", "qcow2", answer.getBackingFormat());
        assertEquals(1, answer.getChainDepth());
        Mockito.verify(pool).refresh();
        String file = poolDir.resolve(VOLUME).toString();
        assertEquals("the recorded format is used, never probed", Arrays.asList("qemu-img", "info", "-f", "qcow2", file), runner.commands.get(0));
        assertEquals(Arrays.asList("qemu-img", "info", "--backing-chain", "-U", "-f", "qcow2", "--output=json", file), runner.commands.get(1));
        assertEquals("the data is hashed by a unit of its own, not within the request", 2, runner.commands.size());
    }

    @Test
    public void standaloneImageHasNoBacking() {
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(1)) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertTrue(answer.getResult());
        assertNull(answer.getBackingFile());
        assertNull(answer.getBackingFormat());
        assertEquals(0, answer.getChainDepth());
    }

    @Test
    public void rawBaseRecordedByTheOverlayIsReported() {
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(2, "raw")) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("raw", answer.getBackingFormat());
    }

    @Test
    public void baseFormatNotRecordedIsReportedAsUnknown() {
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(2, null)) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("/pool/image1", answer.getBackingFile());
        assertNull(answer.getBackingFormat());
    }

    @Test
    public void lockedSourceRefused() {
        respond(command -> command.contains("info") && !command.contains("--backing-chain") ? FakeProcessRunner.failed(1,
                "qemu-img: Could not open '/pool/x': Failed to get shared \"write\" lock\nIs another process using the image [/pool/x]?") : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("is in use"));
        assertEquals("the chain is not read once the lock check fails", 1, runner.commands.size());
    }

    @Test
    public void chainDepthTwoRefused() {
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(3)) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(command, resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("backing chain of 2 images"));
    }

    @Test
    public void fileOutsideThePoolRefused() {
        respond(command -> null);
        InspectVolumeFileCommand escaping = new InspectVolumeFileCommand(command.getPool(), "../etc/shadow", "qcow2", 60);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(escaping, resource);
        assertFalse(answer.getResult());
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    public void qcow2FileRecordedAsRawRefused() throws IOException {
        Files.write(poolDir.resolve(VOLUME), new byte[] {'Q', 'F', 'I', (byte)0xfb, 0, 0, 0, 3});
        respond(command -> command.contains("--backing-chain") ? FakeProcessRunner.ok(chain(1)) : null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(new InspectVolumeFileCommand(command.getPool(), VOLUME, "raw", 60), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("qcow2"));
    }

    @Test
    public void unsupportedRecordedFormatRefused() {
        respond(command -> null);
        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)wrapper.execute(new InspectVolumeFileCommand(command.getPool(), VOLUME, "vhd", 60), resource);
        assertFalse(answer.getResult());
        assertTrue(runner.commands.isEmpty());
    }
}
