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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.codec.binary.Hex;

/**
 * Records the commands a {@link VolumeFileTransfer} runs and answers them from a function;
 * unanswered commands succeed with no output.
 */
public class FakeProcessRunner implements VolumeFileTransfer.ProcessRunner {
    public final List<List<String>> commands = new ArrayList<>();
    public final List<Long> timeouts = new ArrayList<>();
    private final Function<List<String>, VolumeFileTransfer.ProcessResult> responder;

    public FakeProcessRunner(Function<List<String>, VolumeFileTransfer.ProcessResult> responder) {
        this.responder = responder;
    }

    public static VolumeFileTransfer.ProcessResult ok(String output) {
        return new VolumeFileTransfer.ProcessResult(0, output, false);
    }

    public static VolumeFileTransfer.ProcessResult failed(int exitCode, String output) {
        return new VolumeFileTransfer.ProcessResult(exitCode, output, false);
    }

    public static String sha256(byte[] data) {
        try {
            return Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    public static VolumeFileTransfer.ProcessResult timedOut() {
        return new VolumeFileTransfer.ProcessResult(-1, "", true);
    }

    @Override
    public VolumeFileTransfer.ProcessResult run(List<String> command, long timeoutMillis) {
        commands.add(new ArrayList<>(command));
        timeouts.add(timeoutMillis);
        VolumeFileTransfer.ProcessResult result = responder.apply(command);
        return result == null ? ok("") : result;
    }

    public List<String> find(String... prefixOrContained) {
        for (List<String> command : commands) {
            if (matches(command, prefixOrContained)) {
                return command;
            }
        }
        return null;
    }

    public static boolean matches(List<String> command, String... contained) {
        for (String part : contained) {
            if (!command.contains(part)) {
                return false;
            }
        }
        return true;
    }
}
