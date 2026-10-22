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
import java.util.Map;

import javax.inject.Inject;

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
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.storage.AbortVolumeFileCopyCommand;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostAnswer;
import com.cloud.agent.api.storage.CopyVolumeFileFromHostCommand;
import com.cloud.agent.api.storage.InspectVolumeFileAnswer;
import com.cloud.agent.api.storage.InspectVolumeFileCommand;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.agent.api.storage.StartVolumeExportAnswer;
import com.cloud.agent.api.storage.StartVolumeExportCommand;
import com.cloud.agent.api.storage.StopVolumeExportCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.configuration.Config;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
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
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Copies a volume, or a template base, between the local file system pools of two KVM hosts, directly
 * and byte for byte: the owning host exports the file over NBD with TLS to exactly the other host's
 * certificate, which pulls it, verifies length and SHA-256 and only then puts it in place. A qcow2
 * overlay keeps its backing file: the template base is put on the destination pool first (from
 * the source pool when needed) and the overlay is pointed at it there.
 */
public class KvmLocalHostToHostDataMotionStrategy implements DataMotionStrategy {
    protected Logger logger = LogManager.getLogger(getClass());

    /** The export outlives the copy that reads it by this many seconds. */
    static final int EXPORT_GRACE_SECONDS = 300;
    private static final long VERIFY_BASE_SECONDS = 600;
    private static final long VERIFY_BYTES_PER_SECOND = 100L << 20;

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
            Answer answer = srcData instanceof VolumeInfo ? copyVolume((VolumeInfo)srcData, (VolumeInfo)destData)
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
     * Seconds the destination needs after the pull: reading the file back (at no less than 100 MiB/s), rebasing and checking it.
     */
    static int verifySeconds(long length) {
        return (int)Math.min(Integer.MAX_VALUE / 2, VERIFY_BASE_SECONDS + length / VERIFY_BYTES_PER_SECOND);
    }

    int copyWaitSeconds() {
        return NumbersUtil.parseInt(configDao.getValue(Config.CopyVolumeWait.key()), Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));
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
        InspectVolumeFileAnswer inspected = inspect(localHostId(srcStore), srcStore, src.getPath(), formatOf(src.getFormat()), wait);
        if (inspected.getChainDepth() > 1) {
            throw new CloudRuntimeException(String.format("Volume %s has a backing chain of %d images; only a single template base is supported.",
                    src, inspected.getChainDepth()));
        }
        Base base = inspected.getChainDepth() == 1 ? templateBaseOnDestination(src, srcStore, destStore, inspected) : null;
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
    private Base templateBaseOnDestination(VolumeInfo src, PrimaryDataStore srcStore, PrimaryDataStore destStore, InspectVolumeFileAnswer overlay) {
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
            String sourceHash = inspect(localHostId(srcStore), srcStore, sourceBase.getInstallPath(), format, wait).getSha256();
            String destinationHash = inspect(localHostId(destStore), destStore, destinationBase, format, wait).getSha256();
            if (!sourceHash.equalsIgnoreCase(destinationHash)) {
                throw new CloudRuntimeException(String.format("The base of direct-download template %s on pool %s holds different bytes than on pool %s;"
                        + " volume %s cannot be put onto it.", template.getUuid(), destStore.getName(), srcStore.getName(), src));
            }
        }
        return new Base(destinationBase, format);
    }

    private Answer copyTemplate(TemplateInfo src, TemplateInfo dest) {
        PrimaryDataStore srcStore = (PrimaryDataStore)src.getDataStore();
        PrimaryDataStore destStore = (PrimaryDataStore)dest.getDataStore();
        int wait = copyWaitSeconds();
        InspectVolumeFileAnswer inspected = inspect(localHostId(srcStore), srcStore, src.getInstallPath(), baseFormat(src.isDirectDownload(), src.getFormat()),
                wait);
        if (inspected.getChainDepth() != 0) {
            throw new CloudRuntimeException(String.format("The base of template %s on pool %s has a backing file.", src, srcStore.getName()));
        }
        // The base file of a template on a pool is named after the template's UUID.
        String destPath = dest.getUuid();
        CopyVolumeFileFromHostAnswer pulled = transfer(srcStore, src.getInstallPath(), destStore, destPath, inspected, null, null,
                String.format("template-%d-%d", dest.getId(), destStore.getId()), wait);
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

    private InspectVolumeFileAnswer inspect(long hostId, PrimaryDataStore store, String path, String format, int wait) {
        Answer answer = send(hostId, new InspectVolumeFileCommand(new StorageFilerTO(store), path, format, wait));
        if (!(answer instanceof InspectVolumeFileAnswer) || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Unable to inspect %s on pool %s: %s", path, store.getName(), details(answer)));
        }
        return (InspectVolumeFileAnswer)answer;
    }

    /**
     * Exports the source file to exactly the destination host's TLS identity, has the destination pull and
     * verify it, and always stops the export. A pull that did not end cleanly is aborted so that no partial
     * file stays behind.
     */
    private CopyVolumeFileFromHostAnswer transfer(PrimaryDataStore srcStore, String srcPath, PrimaryDataStore destStore, String destPath,
            InspectVolumeFileAnswer inspected, String backingInstallPath, String backingFormat, String name, int wait) {
        long srcHostId = localHostId(srcStore);
        long destHostId = localHostId(destStore);
        Answer prepared = send(destHostId, new PrepareVolumeFileCopyCommand());
        if (!(prepared instanceof PrepareVolumeFileCopyAnswer) || !prepared.getResult()) {
            throw new CloudRuntimeException(String.format("Host %d cannot receive the copy: %s", destHostId, details(prepared)));
        }
        Answer exported = send(srcHostId, new StartVolumeExportCommand(new StorageFilerTO(srcStore), srcPath, name,
                ((PrepareVolumeFileCopyAnswer)prepared).getClientIdentity(), wait + EXPORT_GRACE_SECONDS));
        if (!(exported instanceof StartVolumeExportAnswer) || !exported.getResult()) {
            throw new CloudRuntimeException(String.format("Host %d cannot export %s: %s", srcHostId, srcPath, details(exported)));
        }
        boolean copied = false;
        try {
            StartVolumeExportAnswer export = (StartVolumeExportAnswer)exported;
            Answer answer = send(destHostId, new CopyVolumeFileFromHostCommand(export.getAddress(), export.getPort(), name, new StorageFilerTO(destStore),
                    destPath, inspected.getFormat(), inspected.getLength(), inspected.getSha256(), backingInstallPath, backingFormat, wait, wait + verifySeconds(inspected.getLength())));
            if (!(answer instanceof CopyVolumeFileFromHostAnswer) || !answer.getResult()) {
                throw new CloudRuntimeException(String.format("Copying %s from host %d to host %d failed: %s", srcPath, srcHostId, destHostId, details(answer)));
            }
            if (!inspected.getSha256().equalsIgnoreCase(((CopyVolumeFileFromHostAnswer)answer).getSha256())) {
                throw new CloudRuntimeException(String.format("Host %d reported SHA-256 %s for %s, expected %s.", destHostId,
                        ((CopyVolumeFileFromHostAnswer)answer).getSha256(), destPath, inspected.getSha256()));
            }
            copied = true;
            return (CopyVolumeFileFromHostAnswer)answer;
        } finally {
            if (!copied) {
                sendQuietly(destHostId, new AbortVolumeFileCopyCommand(new StorageFilerTO(destStore), destPath, name));
            }
            sendQuietly(srcHostId, new StopVolumeExportCommand(name));
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
