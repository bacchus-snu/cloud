/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.CheckVolumeFileTransferAnswer;
import com.cloud.agent.api.storage.CheckVolumeFileTransferCommand;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.storage.FinishVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.FinishVolumeFileCopyCommand;
import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.agent.api.storage.StartVolumeFileHashCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.configuration.Config;
import com.cloud.configuration.ManagementServiceConfiguration;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Copies a volume, or a template base, between the local file system pools of two KVM hosts, directly
 * and byte for byte: the owning host exports the file over NBD with TLS to exactly the other host's
 * certificate, which pulls it, verifies length and SHA-256 and only then puts it in place. A qcow2
 * overlay keeps its backing file: the template base is put on the destination pool first (from
 * the source pool when needed) and the overlay is pointed at it there.
 * <p>
 * The hosts run every long step (the export, the pull with its read-back, the hashing) as units of their own; each
 * request to an agent returns at once, and this strategy follows the units by polling. A volume copy takes one of the
 * copy slots ({@link VolumeOrchestrationService#VolumeMoveConcurrencyPerHost}) of each of its two hosts for its whole
 * duration, the copy of the base it needs included, and waits for them when the hosts are busy. A slot is a volume
 * detail of the volume the copy goes to, owned by the copy's async job, so the bound holds across management servers
 * and a slot whose job ended frees itself.
 */
public class KvmLocalHostToHostDataMotionStrategy implements DataMotionStrategy {
    protected Logger logger = LogManager.getLogger(getClass());

    /** The export outlives the copy that reads it by this many seconds. */
    static final int EXPORT_GRACE_SECONDS = 300;
    /** A unit is given up this many seconds after its own time limit. */
    static final int UNIT_GRACE_SECONDS = 60;
    static final long POLL_MILLIS = 5_000L;
    /** The short commands of a transfer read no data, so they are given up quickly. */
    static final int CHECK_WAIT_SECONDS = 60;
    static final int INSPECT_WAIT_SECONDS = 300;
    static final long SLOT_POLL_MILLIS = 10_000L;
    private static final int SLOT_LOCK_SECONDS = 60;
    private static final String SLOT_LOCK = "volume.transfer.slots";
    private static final long VERIFY_BASE_SECONDS = 600;
    private static final long VERIFY_BYTES_PER_SECOND = 100L << 20;
    /**
     * The volume copy running in this thread: its source host, destination host and destination volume. The copy of the
     * base it needs shares its slots and is recorded on that volume.
     */
    private static final ThreadLocal<long[]> HELD_SLOTS = new ThreadLocal<>();

    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentManager;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VMTemplatePoolDao templatePoolDao;
    @Inject
    private VolumeService volumeService;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private VolumeDetailsDao volumeDetailsDao;
    @Inject
    private AsyncJobManager jobManager;

    /** A template's base on the destination pool, and the format it is stored in. */
    private static final class Base {
        private final String installPath;
        private final String format;

        Base(String installPath, String format) {
            this.installPath = installPath;
            this.format = format;
        }
    }

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        boolean volumes = srcData instanceof VolumeInfo && destData instanceof VolumeInfo;
        boolean templates = srcData instanceof TemplateInfo && destData instanceof TemplateInfo;
        if (!volumes && !templates) {
            return StrategyPriority.CANT_HANDLE;
        }
        Long srcHostId = localHostId(srcData.getDataStore());
        Long destHostId = localHostId(destData.getDataStore());
        if (srcHostId == null || destHostId == null || srcHostId.equals(destHostId)) {
            return StrategyPriority.CANT_HANDLE;
        }
        return isKvmHost(srcHostId) && isKvmHost(destHostId) ? StrategyPriority.HYPERVISOR : StrategyPriority.CANT_HANDLE;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        return StrategyPriority.CANT_HANDLE;
    }

    /**
     * @return the host owning a local file system pool, or null for any other store
     */
    static Long localHostId(DataStore store) {
        if (!(store instanceof PrimaryDataStore) || store.getRole() != DataStoreRole.Primary) {
            return null;
        }
        if (((PrimaryDataStore)store).getPoolType() != StoragePoolType.Filesystem) {
            return null;
        }
        Scope scope = store.getScope();
        if (scope == null || scope.getScopeType() != ScopeType.HOST) {
            return null;
        }
        return scope.getScopeId();
    }

    private boolean isKvmHost(long hostId) {
        HostVO host = hostDao.findById(hostId);
        return host != null && host.getHypervisorType() == HypervisorType.KVM;
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCommandResult result;
        try {
            Answer answer = srcData instanceof VolumeInfo ? copyVolumeInSlots((VolumeInfo)srcData, (VolumeInfo)destData)
                    : copyTemplate((TemplateInfo)srcData, (TemplateInfo)destData);
            result = new CopyCommandResult(null, answer);
        } catch (Exception e) {
            logger.warn("Unable to copy {} to {}: {}", srcData, destData, e.getMessage());
            result = new CopyCommandResult(null, new CopyCmdAnswer(e.getMessage()));
            result.setResult(e.getMessage());
        }
        callback.complete(result);
    }

    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo, Host srcHost, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        CopyCommandResult result = new CopyCommandResult(null, null);
        result.setResult("Unsupported operation requested for copying data.");
        callback.complete(result);
    }

    /**
     * Seconds a host needs beyond the transfer to read a file of this length back (at no less than 100 MiB/s).
     */
    static int verifySeconds(long length) {
        return (int)Math.min(Integer.MAX_VALUE / 2, VERIFY_BASE_SECONDS + length / VERIFY_BYTES_PER_SECOND);
    }

    /**
     * Seconds a unit reading a file of this length, after a transfer of at most {@code wait} seconds, may run.
     */
    static int unitSeconds(int wait, long length) {
        return (int)Math.min(Integer.MAX_VALUE / 2, (long)wait + verifySeconds(length));
    }

    protected long now() {
        return System.currentTimeMillis();
    }

    /**
     * Milliseconds an agent may stay silent before the management server takes it for gone, as it does for any agent.
     */
    long agentDeadMillis() {
        Integer interval = ManagementServiceConfiguration.PingInterval.value();
        Float timeout = ManagementServiceConfiguration.PingTimeout.value();
        return (long)((interval == null ? 60 : interval) * (timeout == null ? 2.5f : timeout) * 1000);
    }

    protected void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException("Interrupted while a volume copy waited", e);
        }
    }

    protected long currentJobId() {
        AsyncJobExecutionContext context = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (context == null || context.getJob() == null) {
            throw new CloudRuntimeException("Volumes are copied between hosts only within an async job.");
        }
        return context.getJob().getId();
    }

    private boolean isJobRunning(long jobId) {
        AsyncJob job = jobManager.getAsyncJob(jobId);
        return job != null && job.getStatus() == JobInfo.Status.IN_PROGRESS;
    }

    /**
     * Takes a copy slot of each of the two hosts for the copy into the given volume, waiting while either host already
     * takes part in as many copies as the bound allows. The slots are counted from the slot details of every volume,
     * under a lock all management servers share; a slot whose job no longer runs is removed as it is counted.
     */
    protected void acquireSlots(long volumeId, long srcHostId, long destHostId, int waitSeconds) {
        long jobId = currentJobId();
        long deadline = now() + waitSeconds * 1000L;
        boolean announced = false;
        while (true) {
            Integer bound = VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.value();
            GlobalLock lock = GlobalLock.getInternLock(SLOT_LOCK);
            try {
                if (lock.lock(SLOT_LOCK_SECONDS)) {
                    try {
                        Map<Long, Integer> busy = liveSlotsPerHost();
                        if (bound == null || bound <= 0 || (busy.getOrDefault(srcHostId, 0) < bound && busy.getOrDefault(destHostId, 0) < bound)) {
                            volumeDetailsDao.addDetail(volumeId, VolumeOrchestrationService.TRANSFER_SLOT, srcHostId + "," + destHostId + "," + jobId, false);
                            return;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } finally {
                lock.releaseRef();
            }
            if (now() > deadline) {
                throw new CloudRuntimeException(String.format("Hosts %d and %d take part in as many volume copies as %s allows; no slot came free within %d seconds.",
                        srcHostId, destHostId, VolumeOrchestrationService.VolumeMoveConcurrencyPerHost.key(), waitSeconds));
            }
            if (!announced) {
                logger.info("The copy into volume {} waits for a copy slot of hosts {} and {}", volumeId, srcHostId, destHostId);
                announced = true;
            }
            pause(SLOT_POLL_MILLIS);
        }
    }

    private Map<Long, Integer> liveSlotsPerHost() {
        Map<Long, Integer> busy = new HashMap<>();
        for (VolumeDetailVO slot : volumeDetailsDao.findDetails(VolumeOrchestrationService.TRANSFER_SLOT)) {
            String[] fields = slot.getValue() == null ? new String[0] : slot.getValue().split(",");
            Long jobId = fields.length == 3 ? NumbersUtil.parseLong(fields[2], -1L) : -1L;
            if (jobId < 0 || !isJobRunning(jobId)) {
                volumeDetailsDao.removeDetail(slot.getResourceId(), VolumeOrchestrationService.TRANSFER_SLOT);
                continue;
            }
            for (int i = 0; i < 2; i++) {
                busy.merge(NumbersUtil.parseLong(fields[i], -1L), 1, Integer::sum);
            }
        }
        return busy;
    }

    private void releaseSlots(long volumeId) {
        volumeDetailsDao.removeDetail(volumeId, VolumeOrchestrationService.TRANSFER_SLOT);
    }

    int copyWaitSeconds() {
        return NumbersUtil.parseInt(configDao.getValue(Config.CopyVolumeWait.key()), Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));
    }

    private Answer copyVolumeInSlots(VolumeInfo src, VolumeInfo dest) {
        long srcHostId = localHostId(src.getDataStore());
        long destHostId = localHostId(dest.getDataStore());
        acquireSlots(dest.getId(), srcHostId, destHostId, copyWaitSeconds());
        HELD_SLOTS.set(new long[] {srcHostId, destHostId, dest.getId()});
        try {
            return copyVolume(src, dest);
        } finally {
            HELD_SLOTS.remove();
            releaseSlots(dest.getId());
        }
    }

    private Answer copyVolume(VolumeInfo src, VolumeInfo dest) {
        PrimaryDataStore srcStore = (PrimaryDataStore)src.getDataStore();
        PrimaryDataStore destStore = (PrimaryDataStore)dest.getDataStore();
        int wait = copyWaitSeconds();
        String destPath = dest.getPath();
        if (destPath == null) {
            // Recorded before any byte moves, so that removing the destination volume removes whatever the copy left.
            destPath = dest.getUuid();
            VolumeVO destVolume = volumeDao.findById(dest.getId());
            destVolume.setPath(destPath);
            volumeDao.update(destVolume.getId(), destVolume);
        }
        InspectVolumeFileAnswer inspected = inspect(localHostId(srcStore), srcStore, src.getPath(), formatOf(src.getFormat()));
        if (inspected.getChainDepth() > 1) {
            throw new CloudRuntimeException(String.format("Volume %s has a backing chain of %d images; only a single template base is supported.",
                    src, inspected.getChainDepth()));
        }
        Base base = inspected.getChainDepth() == 1 ? templateBaseOnDestination(src, srcStore, destStore, inspected, dest.getUuid()) : null;
        transfer(srcStore, src.getPath(), destStore, destPath, inspected, base == null ? null : base.installPath, base == null ? null : base.format,
                dest.getUuid(), wait);
        VolumeObjectTO copied = new VolumeObjectTO();
        copied.setUuid(dest.getUuid());
        copied.setPath(destPath);
        copied.setSize(src.getSize() == null ? inspected.getVirtualSize() : src.getSize());
        copied.setFormat(ImageFormat.valueOf(inspected.getFormat().toUpperCase()));
        return new CopyCmdAnswer(copied);
    }

    /**
     * The format of a template's base on a local file system pool: a template copied from secondary storage is
     * converted into the pool's format, qcow2, while a direct-download template is stored as downloaded, in its
     * registered format.
     */
    static String baseFormat(boolean directDownload, ImageFormat registeredFormat) {
        return directDownload ? formatOf(registeredFormat) : "qcow2";
    }

    /**
     * The overlay's base must be the template's base on the source pool, recorded in the format that base is stored
     * in; the same template's base is then made present on the destination pool.
     */
    private Base templateBaseOnDestination(VolumeInfo src, PrimaryDataStore srcStore, PrimaryDataStore destStore, InspectVolumeFileAnswer overlay,
            String name) {
        String backingFile = overlay.getBackingFile();
        Long templateId = src.getTemplateId();
        if (templateId == null) {
            throw new CloudRuntimeException(String.format("Volume %s has the backing file %s but no template.", src, backingFile));
        }
        VMTemplateStoragePoolVO sourceBase = templatePoolDao.findByPoolTemplate(srcStore.getId(), templateId, null);
        String backingName = Paths.get(backingFile).getFileName().toString();
        if (sourceBase == null || !backingName.equals(sourceBase.getInstallPath())) {
            throw new CloudRuntimeException(String.format("The backing file %s of volume %s is not the base of its template on pool %s (%s).",
                    backingFile, src, srcStore.getName(), sourceBase == null ? "none" : sourceBase.getInstallPath()));
        }
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(templateId);
        if (template == null) {
            throw new CloudRuntimeException(String.format("Template %d of volume %s does not exist.", templateId, src));
        }
        String format = baseFormat(template.isDirectDownload(), template.getFormat());
        if (overlay.getBackingFormat() != null && !format.equals(overlay.getBackingFormat())) {
            throw new CloudRuntimeException(String.format("Volume %s records its base as %s, but the base of template %s is stored as %s.", src,
                    overlay.getBackingFormat(), template.getUuid(), format));
        }
        String destinationBase = volumeService.ensureTemplateOnPool(templateId, destStore, srcStore);
        if (template.isDirectDownload()) {
            // Each pool downloads a direct-download template from its URL itself, so its base may hold other bytes.
            int wait = copyWaitSeconds();
            long srcHostId = localHostId(srcStore);
            long destHostId = localHostId(destStore);
            InspectVolumeFileAnswer sourceBaseFile = inspect(srcHostId, srcStore, sourceBase.getInstallPath(), format);
            InspectVolumeFileAnswer destinationBaseFile = inspect(destHostId, destStore, destinationBase, format);
            CheckVolumeFileTransferAnswer sourceHash;
            CheckVolumeFileTransferAnswer destinationHash;
            int sourceLifetime = unitSeconds(wait, sourceBaseFile.getLength());
            int destinationLifetime = unitSeconds(wait, destinationBaseFile.getLength());
            try {
                startHash(srcHostId, srcStore, sourceBase.getInstallPath(), name, sourceLifetime);
                startHash(destHostId, destStore, destinationBase, name, destinationLifetime);
                sourceHash = awaitUnit(srcHostId, destHostId, CheckVolumeFileTransferCommand.Kind.HASH, name, sourceLifetime);
                destinationHash = awaitUnit(destHostId, srcHostId, CheckVolumeFileTransferCommand.Kind.HASH, name, destinationLifetime);
            } finally {
                sendQuietly(srcHostId, new StopVolumeExportCommand(name));
                sendQuietly(destHostId, new StopVolumeExportCommand(name));
            }
            if (sourceHash.getLength() != destinationHash.getLength() || !sourceHash.getSha256().equalsIgnoreCase(destinationHash.getSha256())) {
                throw new CloudRuntimeException(String.format("The base of direct-download template %s on pool %s holds different bytes than on pool %s;"
                        + " volume %s cannot be put onto it.", template.getUuid(), destStore.getName(), srcStore.getName(), src));
            }
        }
        return new Base(destinationBase, format);
    }

    /**
     * Copies the base of a template for the volume copy that needs it, within that copy's slots: a base is copied
     * between hosts only so.
     */
    private Answer copyTemplate(TemplateInfo src, TemplateInfo dest) {
        PrimaryDataStore srcStore = (PrimaryDataStore)src.getDataStore();
        PrimaryDataStore destStore = (PrimaryDataStore)dest.getDataStore();
        long[] held = HELD_SLOTS.get();
        if (held == null || held[0] != localHostId(srcStore) || held[1] != localHostId(destStore)) {
            throw new CloudRuntimeException(String.format("The base of template %s is copied from pool %s to pool %s only for a volume copy between the same hosts.",
                    src.getUuid(), srcStore.getName(), destStore.getName()));
        }
        int wait = copyWaitSeconds();
        InspectVolumeFileAnswer inspected = inspect(localHostId(srcStore), srcStore, src.getInstallPath(), baseFormat(src.isDirectDownload(), src.getFormat()));
        if (inspected.getChainDepth() != 0) {
            throw new CloudRuntimeException(String.format("The base of template %s on pool %s has a backing file.", src, srcStore.getName()));
        }
        // The base file of a template on a pool is named after the template's UUID.
        String destPath = dest.getUuid();
        String name = VolumeOrchestrationService.templateBaseTransferName(dest.getId(), destStore.getId());
        // recorded so that whoever recovers the volume copy also stops this transfer
        volumeDetailsDao.addDetail(held[2], VolumeOrchestrationService.RELOCATION_BASE, name + "," + destPath, false);
        FinishVolumeFileCopyAnswer pulled;
        try {
            pulled = transfer(srcStore, src.getInstallPath(), destStore, destPath, inspected, null, null, name, wait);
        } finally {
            volumeDetailsDao.removeDetail(held[2], VolumeOrchestrationService.RELOCATION_BASE);
        }
        TemplateObjectTO copied = new TemplateObjectTO();
        copied.setUuid(dest.getUuid());
        copied.setPath(destPath);
        copied.setSize(pulled.getAllocatedSize());
        copied.setPhysicalSize(inspected.getLength());
        copied.setFormat(ImageFormat.valueOf(inspected.getFormat().toUpperCase()));
        return new CopyCmdAnswer(copied);
    }

    static String formatOf(ImageFormat format) {
        if (format != ImageFormat.QCOW2 && format != ImageFormat.RAW) {
            throw new CloudRuntimeException(String.format("Only qcow2 and raw files are copied between hosts, not %s.", format));
        }
        return format.toString().toLowerCase();
    }

    private InspectVolumeFileAnswer inspect(long hostId, PrimaryDataStore store, String path, String format) {
        Answer answer = send(hostId, new InspectVolumeFileCommand(new StorageFilerTO(store), path, format, INSPECT_WAIT_SECONDS));
        if (!(answer instanceof InspectVolumeFileAnswer) || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Unable to inspect %s on pool %s: %s", path, store.getName(), details(answer)));
        }
        return (InspectVolumeFileAnswer)answer;
    }

    /**
     * Exports the source file to exactly the destination host's TLS identity and has the destination pull it, while
     * the source hashes it; once both units have ended, the destination puts the file in place only if what it read
     * back has the source's length and SHA-256. The export and the hashing are always stopped, and a pull that did not
     * end in place is aborted so that no partial file stays behind.
     */
    private FinishVolumeFileCopyAnswer transfer(PrimaryDataStore srcStore, String srcPath, PrimaryDataStore destStore, String destPath,
            InspectVolumeFileAnswer inspected, String backingInstallPath, String backingFormat, String name, int wait) {
        long srcHostId = localHostId(srcStore);
        long destHostId = localHostId(destStore);
        Answer prepared = send(destHostId, new PrepareVolumeFileCopyCommand());
        if (!(prepared instanceof PrepareVolumeFileCopyAnswer) || !prepared.getResult()) {
            throw new CloudRuntimeException(String.format("Host %d cannot receive the copy: %s", destHostId, details(prepared)));
        }
        int lifetime = unitSeconds(wait, inspected.getLength());
        boolean copied = false;
        try {
            startHash(srcHostId, srcStore, srcPath, name, lifetime);
            Answer exported = send(srcHostId, new StartVolumeExportCommand(new StorageFilerTO(srcStore), srcPath, name,
                    ((PrepareVolumeFileCopyAnswer)prepared).getClientIdentity(), lifetime + EXPORT_GRACE_SECONDS));
            if (!(exported instanceof StartVolumeExportAnswer) || !exported.getResult()) {
                throw new CloudRuntimeException(String.format("Host %d cannot export %s: %s", srcHostId, srcPath, details(exported)));
            }
            StartVolumeExportAnswer export = (StartVolumeExportAnswer)exported;
            Answer started = send(destHostId, new CopyVolumeFileFromHostCommand(export.getAddress(), export.getPort(), name, new StorageFilerTO(destStore),
                    destPath, inspected.getLength(), lifetime));
            if (started == null || !started.getResult()) {
                throw new CloudRuntimeException(String.format("Host %d cannot copy %s from host %d: %s", destHostId, srcPath, srcHostId, details(started)));
            }
            CheckVolumeFileTransferAnswer pulled = awaitUnit(destHostId, srcHostId, CheckVolumeFileTransferCommand.Kind.PULL, name, lifetime);
            CheckVolumeFileTransferAnswer hashed = awaitUnit(srcHostId, destHostId, CheckVolumeFileTransferCommand.Kind.HASH, name, lifetime);
            if (hashed.getLength() != inspected.getLength()) {
                throw new CloudRuntimeException(String.format("%s on host %d changed during the copy: %d bytes, then %d.", srcPath, srcHostId,
                        inspected.getLength(), hashed.getLength()));
            }
            Answer answer = send(destHostId, new FinishVolumeFileCopyCommand(new StorageFilerTO(destStore), destPath, name, inspected.getFormat(),
                    inspected.getLength(), hashed.getSha256(), backingInstallPath, backingFormat));
            if (!(answer instanceof FinishVolumeFileCopyAnswer) || !answer.getResult()) {
                throw new CloudRuntimeException(String.format("Copying %s from host %d to host %d failed: %s", srcPath, srcHostId, destHostId, details(answer)));
            }
            FinishVolumeFileCopyAnswer finished = (FinishVolumeFileCopyAnswer)answer;
            if (!hashed.getSha256().equalsIgnoreCase(finished.getSha256()) || !hashed.getSha256().equalsIgnoreCase(pulled.getSha256())) {
                throw new CloudRuntimeException(String.format("Host %d reported SHA-256 %s for %s, expected %s.", destHostId, finished.getSha256(), destPath,
                        hashed.getSha256()));
            }
            copied = true;
            return finished;
        } finally {
            if (!copied) {
                sendQuietly(destHostId, new AbortVolumeFileCopyCommand(new StorageFilerTO(destStore), destPath, name));
            }
            sendQuietly(srcHostId, new StopVolumeExportCommand(name));
        }
    }

    private void startHash(long hostId, PrimaryDataStore store, String path, String name, int lifetime) {
        Answer started = send(hostId, new StartVolumeFileHashCommand(new StorageFilerTO(store), path, name, lifetime));
        if (started == null || !started.getResult()) {
            throw new CloudRuntimeException(String.format("Host %d cannot hash %s: %s", hostId, path, details(started)));
        }
    }

    /**
     * Follows the unit of the given kind and name on the host until it ends, at most {@code lifetime} seconds and a
     * grace. A host that cannot be asked for a while does not end the wait, as the unit runs on regardless, unless the
     * management server holds it for Down, or it has not been Up for longer than an agent may stay silent. The wait also
     * ends once this host or the other host of the transfer, {@code peerHostId}, is in a maintenance state.
     *
     * @return the outcome of a unit that succeeded
     */
    protected CheckVolumeFileTransferAnswer awaitUnit(long hostId, long peerHostId, CheckVolumeFileTransferCommand.Kind kind, String name, int lifetime) {
        String what = kind == CheckVolumeFileTransferCommand.Kind.PULL ? "copy" : "hashing";
        long deadline = now() + (lifetime + (long)UNIT_GRACE_SECONDS) * 1000L;
        Long notUpSince = null;
        while (true) {
            Answer answer;
            try {
                CheckVolumeFileTransferCommand check = new CheckVolumeFileTransferCommand(kind, name);
                check.setWait(CHECK_WAIT_SECONDS);
                answer = agentManager.send(hostId, check);
            } catch (Exception e) {
                answer = null;
                logger.debug("Unable to ask host {} about {} {}: {}", hostId, kind, name, e.getMessage());
            }
            if (answer instanceof CheckVolumeFileTransferAnswer && answer.getResult()) {
                CheckVolumeFileTransferAnswer checked = (CheckVolumeFileTransferAnswer)answer;
                if (checked.getState() == CheckVolumeFileTransferAnswer.State.SUCCEEDED) {
                    return checked;
                }
                if (checked.getState() == CheckVolumeFileTransferAnswer.State.FAILED) {
                    throw new CloudRuntimeException(String.format("The %s of %s on host %d failed: %s", what, name, hostId, checked.getDetails()));
                }
                notUpSince = null;
            } else {
                HostVO host = hostDao.findById(hostId);
                Status status = host == null ? Status.Removed : host.getStatus();
                if (status == Status.Up) {
                    notUpSince = null;
                } else {
                    notUpSince = notUpSince == null ? now() : notUpSince;
                    if (status == Status.Down || status == Status.Removed || now() - notUpSince > agentDeadMillis()) {
                        throw new CloudRuntimeException(String.format("Host %d is %s; the %s of %s on it is given up.", hostId, status, what, name));
                    }
                }
            }
            for (long id : new long[] {hostId, peerHostId}) {
                HostVO host = hostDao.findById(id);
                if (VolumeOrchestrationService.inMaintenance(host)) {
                    throw new CloudRuntimeException(String.format("Host %d is in %s; the %s of %s is given up.", id, host.getResourceState(), what, name));
                }
            }
            if (now() > deadline) {
                throw new CloudRuntimeException(String.format("The %s of %s on host %d did not end within %d seconds.", what, name, hostId,
                        lifetime + UNIT_GRACE_SECONDS));
            }
            pause(POLL_MILLIS);
        }
    }

    private Answer send(long hostId, Command command) {
        try {
            return agentManager.send(hostId, command);
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Unable to send %s to host %d: %s", command.getClass().getSimpleName(), hostId, e.getMessage()), e);
        }
    }

    private void sendQuietly(long hostId, Command command) {
        try {
            Answer answer = agentManager.send(hostId, command);
            if (answer == null || !answer.getResult()) {
                logger.warn("{} on host {} failed: {}", command.getClass().getSimpleName(), hostId, details(answer));
            }
        } catch (Exception e) {
            logger.warn("Unable to send {} to host {}: {}", command.getClass().getSimpleName(), hostId, e.getMessage());
        }
    }

    private static String details(Answer answer) {
        return answer == null ? "no answer" : answer.getDetails();
    }
}
