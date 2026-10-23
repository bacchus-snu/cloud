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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.storage.CheckVolumeFileTransferAnswer;
import com.cloud.agent.api.storage.CheckVolumeFileTransferCommand;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Host side of a byte-for-byte volume file copy between the local pools of two hosts. The owning host
 * serves the file read-only with qemu-nbd over TLS to exactly one client certificate; the other host
 * pulls it with qemu-img. Every long-running step (the export, the pull with its read-back, the hashing
 * of a file) runs as a transient systemd unit named after the transfer, in the transfer slice, so that it is
 * bounded in time, can be stopped by name, yields to the host's guests and holds no request of the agent while
 * it runs. A unit records its outcome in a state file that outlives it.
 */
public class VolumeFileTransfer {
    protected Logger logger = LogManager.getLogger(getClass());

    public static final String QEMU_TLS_DIR = "/etc/pki/qemu";
    public static final String STATE_DIR = "/run/cloudstack-volume-transfer";
    public static final String PART_SUFFIX = ".part";
    static final String EXPORT_UNIT_PREFIX = "cloudstack-volume-export-";
    static final String COPY_UNIT_PREFIX = "cloudstack-volume-copy-";
    static final String HASH_UNIT_PREFIX = "cloudstack-volume-hash-";
    static final String RESULT_SUFFIX = ".result";
    static final String STARTED_SUFFIX = ".started";
    /**
     * The slice every transfer unit runs in. It sits at the top level, so that its low CPU and I/O weights compete with
     * the guests' slice rather than only with the host's own processes, and it is confined to the same CPUs as
     * system.slice; the agent package ships its unit file.
     */
    public static final String TRANSFER_SLICE = "volumetransfer.slice";
    static final String HOST_SLICE = "system.slice";
    /**
     * Runs the pull given as its remaining arguments, makes the partial file durable, drops it from the page cache and
     * records the SHA-256 of the bytes read back from the disk, up to the expected length, with the file's length.
     */
    public static final String PULL_SCRIPT = "set -e; part=$1; length=$2; result=$3; shift 3; \"$@\"; sync \"$part\"; "
            + "dd if=\"$part\" iflag=nocache count=0 status=none; sum=$(head -c \"$length\" \"$part\" | sha256sum); "
            + "printf '%s %s\\n' \"${sum%% *}\" \"$(stat -c %s \"$part\")\" > \"$result.tmp\"; mv \"$result.tmp\" \"$result\"";
    /** Records the SHA-256 and the length of a file. */
    public static final String HASH_SCRIPT = "set -e; file=$1; result=$2; sum=$(sha256sum < \"$file\"); "
            + "printf '%s %s\\n' \"${sum%% *}\" \"$(stat -c %s \"$file\")\" > \"$result.tmp\"; mv \"$result.tmp\" \"$result\"";
    private static final List<String> RUNNING_STATES = Arrays.asList("active", "activating", "deactivating", "reloading", "refreshing");
    private static final List<String> ENDED_STATES = Arrays.asList("inactive", "failed", "unknown", "");
    /** systemctl is-active exits with 0 for an active unit and 3 for any other state of a loaded unit. */
    private static final int IS_ACTIVE_NOT_ACTIVE = 3;
    /** systemctl is-active exits with 4 for a unit systemd does not hold, such as an ended unit it collected. */
    private static final int IS_ACTIVE_NO_SUCH_UNIT = 4;
    static final int FIRST_EXPORT_PORT = 10809;
    static final int EXPORT_PORTS = 64;
    static final long COMMAND_TIMEOUT_MS = 120_000L;
    static final long EXPORT_READY_TIMEOUT_MS = 30_000L;
    static final long EXPORT_READY_POLL_MS = 250L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.:-]{1,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> SERVER_CREDENTIALS = Arrays.asList("ca-cert.pem", "server-cert.pem", "server-key.pem");
    private static final List<String> CLIENT_CREDENTIALS = Arrays.asList("ca-cert.pem", "client-cert.pem", "client-key.pem");
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final int SECTOR = 512;
    /** QEMU aligns requests without the page cache to the device's logical block size, which it caps at this. */
    private static final int MAX_BLOCK = 4096;
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
        private final String backingFile;
        private final String backingFormat;
        private final int chainDepth;

        Inspection(String format, long virtualSize, long length, String backingFile, String backingFormat, int chainDepth) {
            this.format = format;
            this.virtualSize = virtualSize;
            this.length = length;
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

    /** What a unit of a transfer has come to: still running, or ended with the hash and length it recorded, or without them. */
    public static final class Outcome {
        private final CheckVolumeFileTransferAnswer.State state;
        private final String sha256;
        private final long length;
        private final String details;

        Outcome(CheckVolumeFileTransferAnswer.State state, String sha256, long length, String details) {
            this.state = state;
            this.sha256 = sha256;
            this.length = length;
            this.details = details;
        }

        public CheckVolumeFileTransferAnswer.State getState() {
            return state;
        }

        public String getSha256() {
            return sha256;
        }

        public long getLength() {
            return length;
        }

        public String getDetails() {
            return details;
        }
    }

    private final ProcessRunner runner;
    private final Path tlsDir;
    private final Path stateDir;

    public VolumeFileTransfer() {
        this(new SystemProcessRunner(), Paths.get(QEMU_TLS_DIR), Paths.get(STATE_DIR));
    }

    public VolumeFileTransfer(ProcessRunner runner, Path tlsDir, Path stateDir) {
        this.runner = runner;
        this.tlsDir = tlsDir;
        this.stateDir = stateDir;
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

    /**
     * Refuses a file another process has open for writing or that is not of its recorded format, then reports its
     * format, sizes and backing chain. The format is never probed from the file's contents. Reads no data beyond the
     * image's metadata, so it ends quickly; {@link #startHash} hashes the file.
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
        return new Inspection(top.get("format").getAsString(), top.get("virtual-size").getAsLong(), Files.size(file), backing, backingFormat,
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
     * A file whose length is not a multiple of the transfer's I/O unit arrives padded with zeros: an NBD export is a whole
     * number of 512-byte sectors, and writes that bypass the page cache cover whole logical blocks of the destination
     * device, at most {@link #MAX_BLOCK} bytes. Only such padding is dropped, so that the copy has the file's own length.
     */
    private static void dropSectorPadding(Path part, long expectedLength) throws IOException {
        long length = Files.size(part);
        if (length == expectedLength || (length != roundUp(expectedLength, SECTOR) && length != roundUp(expectedLength, MAX_BLOCK))) {
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

    private static long roundUp(long length, int unit) {
        return (length + unit - 1) / unit * unit;
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
        requireConfinedSlice();
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
            List<String> command = unitCommand(unit, lifetimeSeconds,
                    "qemu-nbd", "--read-only", "--persistent", "--format=raw", "--export-name=" + name,
                    "--object", "tls-creds-x509,id=tls0,endpoint=server,dir=" + tlsDir + ",verify-peer=on",
                    "--object", authz.toString(),
                    "--tls-creds=tls0", "--tls-authz=authz0", "--bind=" + address, "--port=" + port, file.toString());
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
            if (!isRunning(unit)) {
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

    /**
     * Stops the export and the hashing of the given name and forgets the hashing's outcome.
     */
    public void stopExport(String name) throws IOException {
        stopUnit(EXPORT_UNIT_PREFIX + checkName(name));
        String hash = HASH_UNIT_PREFIX + name;
        stopUnit(hash);
        clearState(hash);
    }

    /**
     * The command that starts the given program as a transient unit of the given name in the transfer slice, ended after
     * {@code lifetimeSeconds}; it returns once the program runs. The program gets its arguments as given: the service
     * manager would otherwise replace every "${...}" in them, a shell script's own included.
     */
    static List<String> unitCommand(String unit, int lifetimeSeconds, String... program) {
        List<String> command = new ArrayList<>(Arrays.asList("systemd-run", "--unit=" + unit, "--slice=" + TRANSFER_SLICE, "--collect",
                "--service-type=exec", "--expand-environment=no", "--property=RuntimeMaxSec=" + lifetimeSeconds, "--"));
        command.addAll(Arrays.asList(program));
        return command;
    }

    /**
     * Refuses to start a transfer unit unless the transfer slice may use exactly the CPUs the host's own processes may
     * use, so that a transfer never runs on CPUs given to guests, whatever was installed or configured first.
     */
    void requireConfinedSlice() {
        Set<Integer> slice = allowedCpus(TRANSFER_SLICE);
        Set<Integer> host = allowedCpus(HOST_SLICE);
        if (!slice.equals(host)) {
            throw new CloudRuntimeException(String.format("%s may use CPUs %s, but the host's own processes in %s use %s; volume transfers are refused "
                    + "until %s is confined like %s.", TRANSFER_SLICE, slice, HOST_SLICE, host, TRANSFER_SLICE, HOST_SLICE));
        }
    }

    /**
     * @return the CPUs the unit is confined to; empty when it is not confined
     */
    private Set<Integer> allowedCpus(String unit) {
        ProcessResult shown = run(Arrays.asList("systemctl", "show", "-p", "AllowedCPUs", "--value", unit), COMMAND_TIMEOUT_MS);
        if (!shown.succeeded()) {
            throw new CloudRuntimeException(String.format("Unable to read the CPUs of %s: %s", unit, shown.getOutput().trim()));
        }
        Set<Integer> cpus = new TreeSet<>();
        for (String range : shown.getOutput().trim().split("[\\s,]+")) {
            if (range.isEmpty()) {
                continue;
            }
            String[] ends = range.split("-");
            try {
                for (int cpu = Integer.parseInt(ends[0]); cpu <= Integer.parseInt(ends[ends.length - 1]); cpu++) {
                    cpus.add(cpu);
                }
            } catch (NumberFormatException e) {
                throw new CloudRuntimeException(String.format("Unable to read the CPUs of %s: %s", unit, shown.getOutput().trim()), e);
            }
        }
        return cpus;
    }

    private Path stateFile(String unit, String suffix) {
        return stateDir.resolve(unit + suffix);
    }

    private void clearState(String unit) throws IOException {
        Files.deleteIfExists(stateFile(unit, RESULT_SUFFIX));
        Files.deleteIfExists(stateFile(unit, RESULT_SUFFIX + ".tmp"));
        Files.deleteIfExists(stateFile(unit, STARTED_SUFFIX));
    }

    /**
     * Starts a unit that records its outcome in its state file; any earlier unit of the name and its outcome are gone first.
     */
    private void startUnit(String unit, int lifetimeSeconds, List<String> program) throws IOException {
        stopUnit(unit);
        requireConfinedSlice();
        Files.createDirectories(stateDir);
        clearState(unit);
        Files.write(stateFile(unit, STARTED_SUFFIX), String.valueOf(System.currentTimeMillis() / 1000).getBytes(StandardCharsets.UTF_8));
        ProcessResult started = run(unitCommand(unit, lifetimeSeconds, program.toArray(new String[0])), COMMAND_TIMEOUT_MS);
        if (!started.succeeded()) {
            throw new CloudRuntimeException(String.format("Unable to start %s: %s", unit, started.getOutput().trim()));
        }
    }

    /**
     * Starts hashing {@code file} as the unit of the given name; {@link #check} reports the hash and the length it read.
     */
    public void startHash(String name, Path file, int lifetimeSeconds) throws IOException {
        checkName(name);
        if (!Files.isRegularFile(file)) {
            throw new CloudRuntimeException(String.format("Volume file %s does not exist.", file));
        }
        String unit = HASH_UNIT_PREFIX + name;
        startUnit(unit, lifetimeSeconds, Arrays.asList("sh", "-c", HASH_SCRIPT, "sh", file.toString(), stateFile(unit, RESULT_SUFFIX).toString()));
        logger.info("Hashing {} as {}", file, unit);
    }

    /**
     * Starts pulling an export into {@code file}.part as the unit of the given export name, which then makes the part
     * durable, drops it from the page cache and hashes what it reads back; {@link #check} follows it and
     * {@link #finishPull} puts the file in place. A {@code file} that already exists is not pulled again: when it has
     * the expected length its bytes are hashed instead, so that {@link #finishPull} can tell whether it is the copy.
     */
    public void startPull(String sourceAddress, int sourcePort, String exportName, Path file, long expectedLength, int lifetimeSeconds) throws IOException {
        checkName(exportName);
        checkAddress(sourceAddress);
        requireCredentials(false);
        String unit = COPY_UNIT_PREFIX + exportName;
        String result = stateFile(unit, RESULT_SUFFIX).toString();
        if (Files.exists(file)) {
            if (Files.size(file) != expectedLength) {
                throw new CloudRuntimeException(String.format("Destination file %s already exists.", file));
            }
            startUnit(unit, lifetimeSeconds, Arrays.asList("sh", "-c", HASH_SCRIPT, "sh", file.toString(), result));
            logger.info("{} already exists; hashing it as {}", file, unit);
            return;
        }
        Path part = Paths.get(file + PART_SUFFIX);
        stopUnit(unit);
        Files.deleteIfExists(part);
        startUnit(unit, lifetimeSeconds, Arrays.asList("sh", "-c", PULL_SCRIPT, "sh", part.toString(), String.valueOf(expectedLength), result,
                "qemu-img", "convert", "-p", "-t", "none", "-O", "raw",
                "--object", "tls-creds-x509,id=tls0,endpoint=client,dir=" + tlsDir,
                "--image-opts", String.format("driver=raw,file.driver=nbd,file.host=%s,file.port=%d,file.export=%s,file.tls-creds=tls0",
                        sourceAddress, sourcePort, exportName),
                part.toString()));
        logger.info("Pulling {} from {}:{} into {} as {}", exportName, sourceAddress, sourcePort, part, unit);
    }

    /**
     * @return whether the pull or the hashing of the given name still runs, or what it recorded when it ended
     */
    public Outcome check(CheckVolumeFileTransferCommand.Kind kind, String name) throws IOException {
        String unit = (kind == CheckVolumeFileTransferCommand.Kind.PULL ? COPY_UNIT_PREFIX : HASH_UNIT_PREFIX) + checkName(name);
        if (isRunning(unit)) {
            return new Outcome(CheckVolumeFileTransferAnswer.State.RUNNING, null, 0, null);
        }
        Outcome recorded = readOutcome(unit);
        if (recorded != null) {
            return recorded;
        }
        Path result = stateFile(unit, RESULT_SUFFIX);
        String reason = Files.isRegularFile(result)
                ? String.format("%s recorded [%s], not a SHA-256 and a length", unit, new String(Files.readAllBytes(result), StandardCharsets.UTF_8).trim())
                : String.format("%s ended without completing", unit);
        return new Outcome(CheckVolumeFileTransferAnswer.State.FAILED, null, 0, String.format("%s: %s", reason, tail(unitLog(unit))));
    }

    /**
     * Only a state systemctl reports is an answer; any other outcome, such as a lost connection to systemd, says
     * nothing about the unit.
     */
    private boolean isRunning(String unit) {
        ProcessResult state = run(Arrays.asList("systemctl", "is-active", unit), COMMAND_TIMEOUT_MS);
        String output = state.getOutput().trim();
        if (!state.isTimedOut()) {
            if ((state.getExitCode() == 0 || state.getExitCode() == IS_ACTIVE_NOT_ACTIVE) && RUNNING_STATES.contains(output)) {
                return true;
            }
            if ((state.getExitCode() == IS_ACTIVE_NOT_ACTIVE || state.getExitCode() == IS_ACTIVE_NO_SUCH_UNIT) && ENDED_STATES.contains(output)) {
                return false;
            }
        }
        throw new CloudRuntimeException(String.format("Unable to read the state of %s: %s", unit, state.isTimedOut() ? "systemctl timed out" : tail(output)));
    }

    /**
     * @return the hash and length the unit recorded, or null when it recorded none
     */
    private Outcome readOutcome(String unit) throws IOException {
        Path result = stateFile(unit, RESULT_SUFFIX);
        if (!Files.isRegularFile(result)) {
            return null;
        }
        String[] fields = new String(Files.readAllBytes(result), StandardCharsets.UTF_8).trim().split("\\s+");
        if (fields.length != 2 || !SHA256.matcher(fields[0]).matches()) {
            return null;
        }
        try {
            return new Outcome(CheckVolumeFileTransferAnswer.State.SUCCEEDED, fields[0], Long.parseLong(fields[1]), null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String unitLog(String unit) throws IOException {
        Path started = stateFile(unit, STARTED_SUFFIX);
        List<String> command = new ArrayList<>(Arrays.asList("journalctl", "--no-pager", "-o", "cat", "-n", "20", "-u", unit));
        if (Files.isRegularFile(started)) {
            command.add("--since=@" + new String(Files.readAllBytes(started), StandardCharsets.UTF_8).trim());
        }
        return run(command, COMMAND_TIMEOUT_MS).getOutput();
    }

    private void stopUnit(String unit) {
        ProcessResult stopped = run(Arrays.asList("systemctl", "stop", unit), COMMAND_TIMEOUT_MS);
        if (!stopped.succeeded() && !stopped.getOutput().contains("not loaded")) {
            throw new CloudRuntimeException(String.format("Unable to stop %s: %s", unit, stopped.getOutput().trim()));
        }
    }

    /**
     * Puts the file pulled by the ended unit of the given export name in place: only when the length and the SHA-256 it
     * read back match does {@code file}.part take the file's name; a qcow2 file that had a backing file is then pointed
     * at {@code backing}, of {@code backingFormat}, without touching its data, and checked. An existing file is never
     * overwritten; one that the unit found to hold exactly the expected bytes counts as copied. Reads no data itself.
     *
     * @return the SHA-256 of the bytes read back
     */
    public String finishPull(String exportName, Path file, String format, long expectedLength, String expectedSha256, Path backing, String backingFormat)
            throws IOException {
        checkName(exportName);
        if (backing != null && !FORMATS.contains(backingFormat)) {
            throw new CloudRuntimeException(String.format("Only qcow2 and raw base files are referenced, not %s.", backingFormat));
        }
        String unit = COPY_UNIT_PREFIX + exportName;
        if (isRunning(unit)) {
            throw new CloudRuntimeException(String.format("The pull into %s still runs.", file));
        }
        Path part = Paths.get(file + PART_SUFFIX);
        try {
            Outcome pulled = readOutcome(unit);
            if (pulled == null) {
                throw new CloudRuntimeException(String.format("The pull into %s did not complete: %s", file, tail(unitLog(unit))));
            }
            if (Files.exists(file) && !Files.exists(part)) {
                if (backing == null && pulled.getLength() == expectedLength && expectedSha256.equalsIgnoreCase(pulled.getSha256())) {
                    logger.info("{} already holds the expected bytes", file);
                    return pulled.getSha256();
                }
                throw new CloudRuntimeException(String.format("Destination file %s already exists.", file));
            }
            if (!Files.isRegularFile(part)) {
                throw new CloudRuntimeException(String.format("The pull into %s left no partial file.", file));
            }
            if (backing != null && !Files.isRegularFile(backing)) {
                throw new CloudRuntimeException(String.format("Base file %s is not on the destination pool.", backing));
            }
            dropSectorPadding(part, expectedLength);
            long length = Files.size(part);
            if (length != expectedLength) {
                throw new CloudRuntimeException(String.format("Length mismatch after copying into %s: expected %d, read back %d.", file, expectedLength, length));
            }
            if (!expectedSha256.equalsIgnoreCase(pulled.getSha256())) {
                throw new CloudRuntimeException(String.format("SHA-256 mismatch after copying into %s: expected %s, read back %s.", file, expectedSha256,
                        pulled.getSha256()));
            }
            try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(file)) {
                throw new CloudRuntimeException(String.format("Destination file %s appeared during the copy.", file));
            }
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(file.getParent());
            finish(file, format, backing, backingFormat);
            return pulled.getSha256();
        } finally {
            Files.deleteIfExists(part);
            clearState(unit);
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
     * Stops the pull and the hashing of the given name, removes the pull's partial file and forgets both outcomes; the
     * completed file is left alone.
     */
    public void abort(String exportName, Path file) throws IOException {
        String unit = COPY_UNIT_PREFIX + checkName(exportName);
        stopUnit(unit);
        Files.deleteIfExists(Paths.get(file + PART_SUFFIX));
        clearState(unit);
        String hash = HASH_UNIT_PREFIX + exportName;
        stopUnit(hash);
        clearState(hash);
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
