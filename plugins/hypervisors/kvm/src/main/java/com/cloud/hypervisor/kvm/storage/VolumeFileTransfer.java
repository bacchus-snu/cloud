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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Host side of a byte-for-byte volume file copy between the local pools of two hosts. The owning host
 * serves the file read-only with qemu-nbd over TLS to exactly one client certificate; the other host
 * pulls it with qemu-img. Both run as transient systemd units named after the transfer, so they are
 * bounded in time and can be stopped by name.
 */
public class VolumeFileTransfer {
    protected Logger logger = LogManager.getLogger(getClass());

    public static final String QEMU_TLS_DIR = "/etc/pki/qemu";
    public static final String PART_SUFFIX = ".part";
    static final String EXPORT_UNIT_PREFIX = "cloudstack-volume-export-";
    static final String COPY_UNIT_PREFIX = "cloudstack-volume-copy-";
    static final int FIRST_EXPORT_PORT = 10809;
    static final int EXPORT_PORTS = 64;
    static final long COMMAND_TIMEOUT_MS = 120_000L;
    static final long EXPORT_READY_TIMEOUT_MS = 30_000L;
    static final long EXPORT_READY_POLL_MS = 250L;
    private static final long UNIT_GRACE_SECONDS = 60L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.:-]{1,255}");
    private static final List<String> SERVER_CREDENTIALS = Arrays.asList("ca-cert.pem", "server-cert.pem", "server-key.pem");
    private static final List<String> CLIENT_CREDENTIALS = Arrays.asList("ca-cert.pem", "client-cert.pem", "client-key.pem");
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final int SECTOR = 512;
    private static final List<String> FORMATS = Arrays.asList("qcow2", "raw");
    private static final byte[] QCOW2_MAGIC = {'Q', 'F', 'I', (byte)0xfb};

    public static final class ProcessResult {
        private final int exitCode;
        private final String output;
        private final boolean timedOut;

        public ProcessResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    public interface ProcessRunner {
        ProcessResult run(List<String> command, long timeoutMillis);
    }

    public static final class Inspection {
        private final String format;
        private final long virtualSize;
        private final long length;
        private final String sha256;
        private final String backingFile;
        private final String backingFormat;
        private final int chainDepth;

        Inspection(String format, long virtualSize, long length, String sha256, String backingFile, String backingFormat, int chainDepth) {
            this.format = format;
            this.virtualSize = virtualSize;
            this.length = length;
            this.sha256 = sha256;
            this.backingFile = backingFile;
            this.backingFormat = backingFormat;
            this.chainDepth = chainDepth;
        }

        public String getFormat() {
            return format;
        }

        public long getVirtualSize() {
            return virtualSize;
        }

        public long getLength() {
            return length;
        }

        public String getSha256() {
            return sha256;
        }

        public String getBackingFile() {
            return backingFile;
        }

        public String getBackingFormat() {
            return backingFormat;
        }

        public int getChainDepth() {
            return chainDepth;
        }
    }

    private final ProcessRunner runner;
    private final Path tlsDir;

    public VolumeFileTransfer() {
        this(new SystemProcessRunner(), Paths.get(QEMU_TLS_DIR));
    }

    public VolumeFileTransfer(ProcessRunner runner, Path tlsDir) {
        this.runner = runner;
        this.tlsDir = tlsDir;
    }

    /**
     * Resolves a file name relative to a pool directory; the name may not leave the directory.
     */
    public static Path fileIn(String poolDirectory, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
            throw new CloudRuntimeException(String.format("Invalid volume file name [%s].", name));
        }
        return Paths.get(poolDirectory).resolve(name);
    }

    static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new CloudRuntimeException(String.format("Invalid transfer name [%s].", name));
        }
        return name;
    }

    private static String checkAddress(String address) {
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new CloudRuntimeException(String.format("Invalid source address [%s].", address));
        }
        return address;
    }

    /**
     * The identity QEMU's tls-authz compares with: the subject's relative distinguished names in the
     * order of their encoding, as GnuTLS prints a certificate's DN (the order libvirt's
     * tls_allowed_dn_list uses too), not the reversed RFC 4514 order.
     */
    public static String identityOf(X509Certificate certificate) {
        try {
            List<Rdn> rdns = new LdapName(certificate.getSubjectX500Principal().getName(X500Principal.RFC2253)).getRdns();
            return rdns.stream().map(Rdn::toString).collect(Collectors.joining(","));
        } catch (InvalidNameException e) {
            throw new CloudRuntimeException("Unable to read the certificate subject: " + e.getMessage(), e);
        }
    }

    public void requireCredentials(boolean server) {
        List<String> missing = new ArrayList<>();
        for (String file : server ? SERVER_CREDENTIALS : CLIENT_CREDENTIALS) {
            if (!Files.isReadable(tlsDir.resolve(file))) {
                missing.add(file);
            }
        }
        if (!missing.isEmpty()) {
            throw new CloudRuntimeException(String.format("TLS credentials %s are missing in %s; provision the host certificate again.", missing, tlsDir));
        }
    }

    public String clientIdentity() {
        requireCredentials(false);
        try (InputStream in = Files.newInputStream(tlsDir.resolve("client-cert.pem"))) {
            return identityOf((X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in));
        } catch (IOException | CertificateException e) {
            throw new CloudRuntimeException("Unable to read the host's TLS client certificate: " + e.getMessage(), e);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException(e);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return Hex.encodeHexString(digest.digest());
    }

    /**
     * Refuses a file another process has open for writing or that is not of its recorded format, then reports its
     * format, sizes, backing chain and hash. The format is never probed from the file's contents.
     */
    public Inspection inspect(Path file, String format) throws IOException {
        if (!FORMATS.contains(format)) {
            throw new CloudRuntimeException(String.format("Only qcow2 and raw files are copied between hosts, not %s.", format));
        }
        if (!Files.isRegularFile(file)) {
            throw new CloudRuntimeException(String.format("Volume file %s does not exist.", file));
        }
        if ("raw".equals(format) && startsWithQcow2Magic(file)) {
            throw new CloudRuntimeException(String.format("Volume file %s is a qcow2 image but is recorded as raw.", file));
        }
        ProcessResult lockCheck = run(Arrays.asList("qemu-img", "info", "-f", format, file.toString()), COMMAND_TIMEOUT_MS);
        if (!lockCheck.succeeded()) {
            if (lockCheck.getOutput().contains("lock")) {
                throw new CloudRuntimeException(String.format("Volume file %s is in use: %s", file, lockCheck.getOutput().trim()));
            }
            throw new CloudRuntimeException(String.format("Unable to read volume file %s: %s", file, lockCheck.getOutput().trim()));
        }
        ProcessResult chainInfo = run(Arrays.asList("qemu-img", "info", "--backing-chain", "-U", "-f", format, "--output=json", file.toString()), COMMAND_TIMEOUT_MS);
        if (!chainInfo.succeeded()) {
            throw new CloudRuntimeException(String.format("Unable to read the backing chain of %s: %s", file, chainInfo.getOutput().trim()));
        }
        JsonArray chain = JsonParser.parseString(chainInfo.getOutput()).getAsJsonArray();
        JsonObject top = chain.get(0).getAsJsonObject();
        String backing = null;
        if (top.has("full-backing-filename")) {
            backing = top.get("full-backing-filename").getAsString();
        } else if (top.has("backing-filename")) {
            backing = top.get("backing-filename").getAsString();
        }
        String backingFormat = top.has("backing-filename-format") ? top.get("backing-filename-format").getAsString() : null;
        return new Inspection(top.get("format").getAsString(), top.get("virtual-size").getAsLong(), Files.size(file), sha256(file), backing, backingFormat,
                chain.size() - 1);
    }

    private static boolean startsWithQcow2Magic(Path file) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(QCOW2_MAGIC.length);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (head.hasRemaining() && channel.read(head) >= 0) {
                continue;
            }
        }
        return !head.hasRemaining() && Arrays.equals(head.array(), QCOW2_MAGIC);
    }

    /**
     * An NBD export of a file is a whole number of 512-byte sectors, so a file whose length is not a multiple of 512
     * arrives padded with zeros; the padding is dropped so that the copy has the file's own length.
     */
    private static void dropSectorPadding(Path part, long expectedLength) throws IOException {
        long length = Files.size(part);
        long padded = (expectedLength + SECTOR - 1) / SECTOR * SECTOR;
        if (length == expectedLength || length != padded) {
            return;
        }
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer tail = ByteBuffer.allocate((int)(length - expectedLength));
            channel.position(expectedLength);
            while (tail.hasRemaining() && channel.read(tail) >= 0) {
                continue;
            }
            for (byte b : tail.array()) {
                if (b != 0) {
                    return;
                }
            }
            channel.truncate(expectedLength);
        }
    }

    /**
     * Serves the file read-only, byte for byte, to the client presenting the given certificate identity,
     * on the first free port from {@link #FIRST_EXPORT_PORT}, for at most {@code lifetimeSeconds}.
     *
     * @return the port the export listens on
     */
    public int startExport(String name, Path file, String address, String clientIdentity, int lifetimeSeconds) {
        checkName(name);
        checkAddress(address);
        if (clientIdentity == null || clientIdentity.isEmpty()) {
            throw new CloudRuntimeException("No client identity to authorize.");
        }
        requireCredentials(true);
        if (!Files.isRegularFile(file)) {
            throw new CloudRuntimeException(String.format("Volume file %s does not exist.", file));
        }
        String unit = EXPORT_UNIT_PREFIX + name;
        stopUnit(unit);
        JsonObject authz = new JsonObject();
        authz.addProperty("qom-type", "authz-simple");
        authz.addProperty("id", "authz0");
        authz.addProperty("identity", clientIdentity);
        String lastError = "no free port";
        for (int port = FIRST_EXPORT_PORT; port < FIRST_EXPORT_PORT + EXPORT_PORTS; port++) {
            if (!isPortFree(address, port)) {
                continue;
            }
            long since = System.currentTimeMillis() / 1000;
            List<String> command = new ArrayList<>(Arrays.asList("systemd-run", "--unit=" + unit, "--collect", "--service-type=exec",
                    "--property=RuntimeMaxSec=" + lifetimeSeconds, "--",
                    "qemu-nbd", "--read-only", "--persistent", "--format=raw", "--export-name=" + name,
                    "--object", "tls-creds-x509,id=tls0,endpoint=server,dir=" + tlsDir + ",verify-peer=on",
                    "--object", authz.toString(),
                    "--tls-creds=tls0", "--tls-authz=authz0", "--bind=" + address, "--port=" + port, file.toString()));
            ProcessResult started = run(command, COMMAND_TIMEOUT_MS);
            if (!started.succeeded()) {
                throw new CloudRuntimeException(String.format("Unable to start the export of %s: %s", file, started.getOutput().trim()));
            }
            String failure = waitUntilListening(unit, port, since);
            if (failure == null) {
                logger.info("Exporting {} as {} on {}:{} to {}", file, name, address, port, clientIdentity);
                return port;
            }
            lastError = failure;
            if (!failure.contains("Address already in use") && !failure.contains("available port")) {
                break;
            }
        }
        throw new CloudRuntimeException(String.format("Unable to export %s: %s", file, lastError.trim()));
    }

    /**
     * @return null once the unit listens on the port, else what the unit logged before it ended
     */
    private String waitUntilListening(String unit, int port, long sinceEpochSeconds) {
        long deadline = System.currentTimeMillis() + EXPORT_READY_TIMEOUT_MS;
        while (true) {
            ProcessResult listening = run(Arrays.asList("ss", "-Hltn", "sport", "=", ":" + port), COMMAND_TIMEOUT_MS);
            if (listening.succeeded() && !listening.getOutput().trim().isEmpty()) {
                return null;
            }
            ProcessResult state = run(Arrays.asList("systemctl", "is-active", unit), COMMAND_TIMEOUT_MS);
            String unitState = state.getOutput().trim();
            if (!"active".equals(unitState) && !"activating".equals(unitState)) {
                ProcessResult journal = run(Arrays.asList("journalctl", "--no-pager", "-o", "cat", "-n", "20", "-u", unit, "--since=@" + sinceEpochSeconds),
                        COMMAND_TIMEOUT_MS);
                return "the export ended: " + journal.getOutput();
            }
            if (System.currentTimeMillis() > deadline) {
                stopUnit(unit);
                return "the export did not listen within " + EXPORT_READY_TIMEOUT_MS / 1000 + " seconds";
            }
            sleep(EXPORT_READY_POLL_MS);
        }
    }

    protected boolean isPortFree(String address, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(address), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException("Interrupted", e);
        }
    }

    public void stopExport(String name) {
        stopUnit(EXPORT_UNIT_PREFIX + checkName(name));
    }

    private void stopUnit(String unit) {
        ProcessResult stopped = run(Arrays.asList("systemctl", "stop", unit), COMMAND_TIMEOUT_MS);
        if (!stopped.succeeded() && !stopped.getOutput().contains("not loaded")) {
            throw new CloudRuntimeException(String.format("Unable to stop %s: %s", unit, stopped.getOutput().trim()));
        }
    }

    /**
     * Pulls an export into {@code file} through {@code file}.part. The part is synced, dropped from the page
     * cache and read back; only when its length and SHA-256 match does it take the file's name. A qcow2 file
     * that had a backing file is then pointed at {@code backing}, of {@code backingFormat}, without touching its data,
     * and checked.
     * An existing file is never overwritten; one that already holds exactly the expected bytes counts as copied.
     *
     * @return the SHA-256 of the bytes read back
     */
    public String pull(String sourceAddress, int sourcePort, String exportName, Path file, String format, long expectedLength,
            String expectedSha256, Path backing, String backingFormat, int timeoutSeconds) throws IOException {
        checkName(exportName);
        checkAddress(sourceAddress);
        if (backing != null && !FORMATS.contains(backingFormat)) {
            throw new CloudRuntimeException(String.format("Only qcow2 and raw base files are referenced, not %s.", backingFormat));
        }
        requireCredentials(false);
        Path part = Paths.get(file + PART_SUFFIX);
        if (Files.exists(file)) {
            if (backing == null && Files.size(file) == expectedLength && expectedSha256.equalsIgnoreCase(sha256(file))) {
                logger.info("{} already holds the expected bytes", file);
                return expectedSha256;
            }
            throw new CloudRuntimeException(String.format("Destination file %s already exists.", file));
        }
        if (backing != null && !Files.isRegularFile(backing)) {
            throw new CloudRuntimeException(String.format("Base file %s is not on the destination pool.", backing));
        }
        String unit = COPY_UNIT_PREFIX + exportName;
        stopUnit(unit);
        Files.deleteIfExists(part);
        long since = System.currentTimeMillis() / 1000;
        try {
            List<String> command = Arrays.asList("systemd-run", "--unit=" + unit, "--collect", "--wait", "--service-type=exec",
                    "--property=RuntimeMaxSec=" + timeoutSeconds, "--",
                    "qemu-img", "convert", "-p", "-O", "raw",
                    "--object", "tls-creds-x509,id=tls0,endpoint=client,dir=" + tlsDir,
                    "--image-opts", String.format("driver=raw,file.driver=nbd,file.host=%s,file.port=%d,file.export=%s,file.tls-creds=tls0",
                            sourceAddress, sourcePort, exportName),
                    part.toString());
            ProcessResult copied = run(command, TimeUnit.SECONDS.toMillis(timeoutSeconds + UNIT_GRACE_SECONDS));
            if (copied.isTimedOut()) {
                stopUnit(unit);
                throw new CloudRuntimeException(String.format("Copy into %s did not finish within %d seconds.", file, timeoutSeconds));
            }
            if (copied.getExitCode() != 0) {
                ProcessResult journal = run(Arrays.asList("journalctl", "--no-pager", "-o", "cat", "-n", "20", "-u", unit, "--since=@" + since),
                        COMMAND_TIMEOUT_MS);
                throw new CloudRuntimeException(String.format("Copy into %s failed: %s %s", file, tail(copied.getOutput()), tail(journal.getOutput())));
            }
            dropSectorPadding(part, expectedLength);
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            ProcessResult dropped = run(Arrays.asList("dd", "if=" + part, "iflag=nocache", "count=0", "status=none"), COMMAND_TIMEOUT_MS);
            if (!dropped.succeeded()) {
                throw new CloudRuntimeException(String.format("Unable to drop %s from the page cache: %s", part, dropped.getOutput().trim()));
            }
            long length = Files.size(part);
            if (length != expectedLength) {
                throw new CloudRuntimeException(String.format("Length mismatch after copying into %s: expected %d, read back %d.", file, expectedLength, length));
            }
            String sha256 = sha256(part);
            if (!sha256.equalsIgnoreCase(expectedSha256)) {
                throw new CloudRuntimeException(String.format("SHA-256 mismatch after copying into %s: expected %s, read back %s.", file, expectedSha256, sha256));
            }
            if (Files.exists(file)) {
                throw new CloudRuntimeException(String.format("Destination file %s appeared during the copy.", file));
            }
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(file.getParent());
            finish(file, format, backing, backingFormat);
            return sha256;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private void finish(Path file, String format, Path backing, String backingFormat) throws IOException {
        try {
            if (backing != null) {
                ProcessResult rebased = run(Arrays.asList("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", backingFormat, "-b", backing.toString(), file.toString()),
                        COMMAND_TIMEOUT_MS);
                if (!rebased.succeeded()) {
                    throw new CloudRuntimeException(String.format("Unable to point %s at %s: %s", file, backing, rebased.getOutput().trim()));
                }
            }
            if ("qcow2".equalsIgnoreCase(format)) {
                ProcessResult checked = run(Arrays.asList("qemu-img", "check", "-f", "qcow2", file.toString()), COMMAND_TIMEOUT_MS);
                // 3: leaked clusters only, which waste space but do not corrupt data
                if (checked.isTimedOut() || (checked.getExitCode() != 0 && checked.getExitCode() != 3)) {
                    throw new CloudRuntimeException(String.format("qemu-img check of %s failed: %s", file, tail(checked.getOutput())));
                }
            }
        } catch (CloudRuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    /**
     * Stops a pull of the given export and removes its partial file; the completed file is left alone.
     */
    public void abort(String exportName, Path file) throws IOException {
        stopUnit(COPY_UNIT_PREFIX + checkName(exportName));
        Files.deleteIfExists(Paths.get(file + PART_SUFFIX));
    }

    private void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            logger.warn("Unable to sync directory {}: {}", directory, e.getMessage());
        }
    }

    private static String tail(String output) {
        String trimmed = output.trim();
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }

    private ProcessResult run(List<String> command, long timeoutMillis) {
        logger.debug("Running {}", command);
        return runner.run(command, timeoutMillis);
    }

    static final class SystemProcessRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> command, long timeoutMillis) {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (IOException e) {
                return new ProcessResult(-1, e.getMessage(), false);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[8192];
                try (InputStream in = process.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        synchronized (output) {
                            if (output.size() < OUTPUT_LIMIT) {
                                output.write(buffer, 0, Math.min(read, OUTPUT_LIMIT - output.size()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // the process ended or was destroyed
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished;
            try {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                process.destroyForcibly();
            }
            try {
                reader.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text;
            synchronized (output) {
                text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            return new ProcessResult(finished ? process.exitValue() : -1, text, !finished);
        }
    }
}
