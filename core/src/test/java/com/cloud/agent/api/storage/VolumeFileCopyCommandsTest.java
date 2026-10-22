//
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
//

package com.cloud.agent.api.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.serializer.GsonHelper;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.google.gson.Gson;

public class VolumeFileCopyCommandsTest {
    private static final String DN = "CN=dst.example.org,OU=cloudstack,O=cloudstack,C=cloudstack";
    private static final String SHA = "7ab22542e29addb1d6a24721e20b39785a549f023eb32ff4b3b92151fca09288";

    private final Gson gson = GsonHelper.getGson();

    private StorageFilerTO pool() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        Mockito.when(pool.getId()).thenReturn(7L);
        Mockito.when(pool.getUuid()).thenReturn("a1b2c3d4-0000-4000-8000-000000000007");
        Mockito.when(pool.getPath()).thenReturn("/var/lib/libvirt/images");
        Mockito.when(pool.getPoolType()).thenReturn(StoragePoolType.Filesystem);
        return new StorageFilerTO(pool);
    }

    /** Commands travel to the agent inside a Command[] that records each element's class. */
    private Command overTheWire(Command cmd) {
        Command[] received = gson.fromJson(gson.toJson(new Command[] {cmd}, Command[].class), Command[].class);
        assertEquals(cmd.getClass(), received[0].getClass());
        return received[0];
    }

    private Answer answerOverTheWire(Answer answer) {
        Answer[] received = gson.fromJson(gson.toJson(new Answer[] {answer}, Answer[].class), Answer[].class);
        assertEquals(answer.getClass(), received[0].getClass());
        return received[0];
    }

    @Test
    public void inspectRoundTrip() {
        InspectVolumeFileCommand cmd = (InspectVolumeFileCommand)overTheWire(new InspectVolumeFileCommand(pool(), "8e1d4c2b", "qcow2", 10800));
        assertEquals("8e1d4c2b", cmd.getPath());
        assertEquals("qcow2", cmd.getFormat());
        assertEquals("a1b2c3d4-0000-4000-8000-000000000007", cmd.getPool().getUuid());
        assertEquals(StoragePoolType.Filesystem, cmd.getPool().getType());
        assertEquals(10800, cmd.getWait());
        assertFalse(cmd.executeInSequence());

        InspectVolumeFileAnswer answer = (InspectVolumeFileAnswer)answerOverTheWire(
                new InspectVolumeFileAnswer(cmd, "qcow2", 4294967296L, 25624576L, "/var/lib/libvirt/images/0c7f2a6e", "raw", 1));
        assertTrue(answer.getResult());
        assertEquals("qcow2", answer.getFormat());
        assertEquals(4294967296L, answer.getVirtualSize());
        assertEquals(25624576L, answer.getLength());
        assertEquals("/var/lib/libvirt/images/0c7f2a6e", answer.getBackingFile());
        assertEquals("raw", answer.getBackingFormat());
        assertEquals(1, answer.getChainDepth());
    }

    @Test
    public void prepareRoundTrip() {
        PrepareVolumeFileCopyCommand cmd = (PrepareVolumeFileCopyCommand)overTheWire(new PrepareVolumeFileCopyCommand());
        assertFalse(cmd.executeInSequence());
        PrepareVolumeFileCopyAnswer answer = (PrepareVolumeFileCopyAnswer)answerOverTheWire(new PrepareVolumeFileCopyAnswer(cmd, true, DN));
        assertTrue(answer.getResult());
        assertEquals(DN, answer.getClientIdentity());
    }

    @Test
    public void exportRoundTrip() {
        StartVolumeExportCommand cmd = (StartVolumeExportCommand)overTheWire(new StartVolumeExportCommand(pool(), "8e1d4c2b", "f00dcafe-1", DN, 11100));
        assertEquals("8e1d4c2b", cmd.getPath());
        assertEquals("f00dcafe-1", cmd.getName());
        assertEquals(DN, cmd.getClientIdentity());
        assertEquals(11100, cmd.getLifetime());
        assertFalse(cmd.executeInSequence());
        StartVolumeExportAnswer answer = (StartVolumeExportAnswer)answerOverTheWire(new StartVolumeExportAnswer(cmd, "10.0.0.11", 10810));
        assertEquals("10.0.0.11", answer.getAddress());
        assertEquals(10810, answer.getPort());

        StopVolumeExportCommand stop = (StopVolumeExportCommand)overTheWire(new StopVolumeExportCommand("f00dcafe-1"));
        assertEquals("f00dcafe-1", stop.getName());
        assertFalse(stop.executeInSequence());
    }

    @Test
    public void copyRoundTrip() {
        CopyVolumeFileFromHostCommand cmd = (CopyVolumeFileFromHostCommand)overTheWire(new CopyVolumeFileFromHostCommand("10.0.0.11", 10810, "f00dcafe-1",
                pool(), "f00dcafe-1", 25624576L, 11400));
        assertEquals("10.0.0.11", cmd.getSourceAddress());
        assertEquals(10810, cmd.getSourcePort());
        assertEquals("f00dcafe-1", cmd.getExportName());
        assertEquals("f00dcafe-1", cmd.getPath());
        assertEquals(25624576L, cmd.getExpectedLength());
        assertEquals(11400, cmd.getLifetime());
        assertFalse(cmd.executeInSequence());

        CheckVolumeFileTransferCommand check = (CheckVolumeFileTransferCommand)overTheWire(
                new CheckVolumeFileTransferCommand(CheckVolumeFileTransferCommand.Kind.PULL, "f00dcafe-1"));
        assertEquals(CheckVolumeFileTransferCommand.Kind.PULL, check.getKind());
        assertEquals("f00dcafe-1", check.getName());
        assertFalse(check.executeInSequence());
        CheckVolumeFileTransferAnswer checked = (CheckVolumeFileTransferAnswer)answerOverTheWire(
                new CheckVolumeFileTransferAnswer(check, CheckVolumeFileTransferAnswer.State.SUCCEEDED, SHA, 25624576L, null));
        assertTrue(checked.getResult());
        assertEquals(CheckVolumeFileTransferAnswer.State.SUCCEEDED, checked.getState());
        assertEquals(SHA, checked.getSha256());
        assertEquals(25624576L, checked.getLength());
        CheckVolumeFileTransferAnswer failedPull = (CheckVolumeFileTransferAnswer)answerOverTheWire(
                new CheckVolumeFileTransferAnswer(check, CheckVolumeFileTransferAnswer.State.FAILED, null, 0, "authz check is denied"));
        assertEquals(CheckVolumeFileTransferAnswer.State.FAILED, failedPull.getState());
        assertEquals("authz check is denied", failedPull.getDetails());

        FinishVolumeFileCopyCommand finish = (FinishVolumeFileCopyCommand)overTheWire(new FinishVolumeFileCopyCommand(pool(), "f00dcafe-1", "f00dcafe-1",
                "qcow2", 25624576L, SHA, "0c7f2a6e", "raw"));
        assertEquals("f00dcafe-1", finish.getPath());
        assertEquals("f00dcafe-1", finish.getExportName());
        assertEquals("qcow2", finish.getFormat());
        assertEquals(25624576L, finish.getExpectedLength());
        assertEquals(SHA, finish.getExpectedSha256());
        assertEquals("0c7f2a6e", finish.getBackingInstallPath());
        assertEquals("raw", finish.getBackingFormat());
        assertFalse(finish.executeInSequence());
        FinishVolumeFileCopyCommand noBacking = (FinishVolumeFileCopyCommand)overTheWire(new FinishVolumeFileCopyCommand(pool(), "x", "x", "raw", 1L, SHA,
                null, null));
        assertNull(noBacking.getBackingInstallPath());
        assertNull(noBacking.getBackingFormat());

        FinishVolumeFileCopyAnswer answer = (FinishVolumeFileCopyAnswer)answerOverTheWire(new FinishVolumeFileCopyAnswer(finish, 25624576L, SHA, 24608L * 1024));
        assertTrue(answer.getResult());
        assertEquals(25624576L, answer.getLength());
        assertEquals(SHA, answer.getSha256());
        assertEquals(24608L * 1024, answer.getAllocatedSize());
        FinishVolumeFileCopyAnswer failed = (FinishVolumeFileCopyAnswer)answerOverTheWire(new FinishVolumeFileCopyAnswer(finish, "hash mismatch"));
        assertFalse(failed.getResult());
        assertEquals("hash mismatch", failed.getDetails());

        AbortVolumeFileCopyCommand abort = (AbortVolumeFileCopyCommand)overTheWire(new AbortVolumeFileCopyCommand(pool(), "f00dcafe-1", "f00dcafe-1"));
        assertEquals("f00dcafe-1", abort.getPath());
        assertEquals("f00dcafe-1", abort.getExportName());
        assertEquals(StoragePoolType.Filesystem, abort.getPool().getType());
        assertFalse(abort.executeInSequence());
    }

    @Test
    public void hashRoundTrip() {
        StartVolumeFileHashCommand cmd = (StartVolumeFileHashCommand)overTheWire(new StartVolumeFileHashCommand(pool(), "8e1d4c2b", "f00dcafe-1", 11400));
        assertEquals("8e1d4c2b", cmd.getPath());
        assertEquals("f00dcafe-1", cmd.getName());
        assertEquals(11400, cmd.getLifetime());
        assertEquals("a1b2c3d4-0000-4000-8000-000000000007", cmd.getPool().getUuid());
        assertFalse(cmd.executeInSequence());
    }
}
