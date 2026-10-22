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
package com.cloud.deploy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.command.admin.host.UpdateHostMoveRateCmd;
import org.apache.cloudstack.api.command.user.vm.CheckPlacementCmd;
import org.apache.cloudstack.api.command.user.vm.ListPlacementCapacityCmd;
import org.apache.cloudstack.api.response.PlacementCapacityResponse;
import org.apache.cloudstack.api.response.PlacementResponse;
import org.apache.cloudstack.api.response.PlacementThisHostResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.security.keys.KeysManager;
import org.apache.cloudstack.gpu.GpuService;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.HostPoolCoreManager;
import com.cloud.configuration.Resource;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.VolumeMoveRequiredException;
import com.cloud.gpu.GpuCardVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.GpuCardDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.server.StatsCollector;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeStats;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

public class PlacementServiceImpl extends ManagerBase implements PlacementService, PluggableService, Configurable {

    static final String REASON_CPU_POOL = "cpu pool";
    static final String REASON_VCPU_STEP = "vcpu step";
    static final String REASON_MEMORY = "memory";
    static final String REASON_LOCAL_STORAGE = "local storage";
    static final String REASON_STORAGE_QUOTA = "storage quota";
    static final String REASON_INSTANCE_SNAPSHOTS = "instance snapshots";
    static final String REASON_PRIMARY_SNAPSHOTS = "volume snapshots on primary storage";
    static final String REASON_ENCRYPTED_VOLUME = "encrypted volume";
    static final String REASON_AFFINITY = "affinity groups";
    static final String REASON_SUITABLE_HOST = "suitable host";
    static final String REASON_VOLUMES_UNAVAILABLE = "volumes unavailable";
    static final String REASON_QUOTA = "quota";

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String TOKEN_KEY_PURPOSE = "cloudstack placement token";
    private static final String TOKEN_SEPARATOR = "-";

    @Inject
    DeploymentPlanningManager deploymentPlanningManager;
    @Inject
    CapacityManager capacityManager;
    @Inject
    ResourceManager resourceManager;
    @Inject
    StorageManager storageManager;
    @Inject
    ResourceLimitService resourceLimitService;
    @Inject
    UserVmManager userVmManager;
    @Inject
    AccountManager accountManager;
    @Inject
    KeysManager keysManager;
    @Inject
    SnapshotHelper snapshotHelper;
    @Inject
    StatsCollector statsCollector;
    @Inject
    VMInstanceDao vmInstanceDao;
    @Inject
    VolumeDao volumeDao;
    @Inject
    VMTemplateDao templateDao;
    @Inject
    ServiceOfferingDao serviceOfferingDao;
    @Inject
    DiskOfferingDao diskOfferingDao;
    @Inject
    DataCenterDao dataCenterDao;
    @Inject
    HostDao hostDao;
    @Inject
    HostDetailsDao hostDetailsDao;
    @Inject
    PrimaryDataStoreDao storagePoolDao;
    @Inject
    StoragePoolHostDao storagePoolHostDao;
    @Inject
    VMSnapshotDao vmSnapshotDao;
    @Inject
    VgpuProfileDao vgpuProfileDao;
    @Inject
    GpuCardDao gpuCardDao;
    @Inject
    GpuService gpuService;

    /** What a request places: its Instance, the volumes it has, and the disks it would create. */
    final class Context {
        final PlacementRequest request;
        final Account owner;
        final long zoneId;
        final VMInstanceVO vm;
        final VMTemplateVO template;
        final ServiceOffering offering;
        final List<VolumeVO> volumes;
        final List<DiskProfile> newDisks;
        final boolean newSize;
        final String subject;

        Context(PlacementRequest request, Account owner, long zoneId, VMInstanceVO vm, VMTemplateVO template, ServiceOffering offering, List<VolumeVO> volumes,
                List<DiskProfile> newDisks, boolean newSize, String subject) {
            this.request = request;
            this.owner = owner;
            this.zoneId = zoneId;
            this.vm = vm;
            this.template = template;
            this.offering = offering;
            this.volumes = volumes;
            this.newDisks = newDisks;
            this.newSize = newSize;
            this.subject = subject;
        }
    }

    /** A host with room, the pool its local disks would use, and the volumes that would have to be copied to it. */
    static final class Candidate {
        final Host host;
        final StoragePool pool;
        final List<VolumeVO> toMove;
        final long bytesHeld;
        String blocker;

        Candidate(Host host, StoragePool pool, List<VolumeVO> toMove, long bytesHeld) {
            this.host = host;
            this.pool = pool;
            this.toMove = toMove;
            this.bytesHeld = bytesHeld;
        }
    }

    static final class Evaluation {
        final Context context;
        final List<Candidate> candidates;
        final boolean tiedToHost;
        final String reason;

        Evaluation(Context context, List<Candidate> candidates, boolean tiedToHost, String reason) {
            this.context = context;
            this.candidates = candidates;
            this.tiedToHost = tiedToHost;
            this.reason = reason;
        }

        List<Candidate> movable() {
            return candidates.stream().filter(c -> !c.toMove.isEmpty() && c.blocker == null).collect(Collectors.toList());
        }
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CheckPlacementCmd.class);
        cmdList.add(ListPlacementCapacityCmd.class);
        cmdList.add(UpdateHostMoveRateCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return PlacementService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {VolumeMoveEstimateMbps, PlacementTokenExpirySeconds};
    }

    @Override
    public PlacementResponse checkPlacement(CheckPlacementCmd cmd) {
        return new PlacementResponse(check(requestOf(cmd, CallContext.current().getCallingAccount())));
    }

    @Override
    public PlacementCapacityResponse listPlacementCapacity(ListPlacementCapacityCmd cmd) {
        return capacityOf(cmd.getZoneId(), cmd.getVirtualMachineId(), cmd.getServiceOfferingId(), CallContext.current().getCallingAccount());
    }

    /**
     * Each maximum is that of one host, never a sum, and the three need not fit one host together. A host counts only
     * when planning offers it to an Instance of the owner (for the offering's host tags, if one is given) and it takes
     * the smallest such Instance now: the cores of one vCPU step and some memory. A host that does not offers nothing,
     * because no Instance could use its other resources. The vCPUs are a multiple of the step, as an Instance's are.
     */
    PlacementCapacityResponse capacityOf(long zoneId, Long vmId, Long offeringId, Account caller) {
        DataCenterVO zone = dataCenterDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("No such zone");
        }
        VMInstanceVO vm = null;
        Account owner = caller;
        if (vmId != null) {
            vm = vmInstanceDao.findById(vmId);
            if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User) {
                throw new InvalidParameterValueException("No such Instance");
            }
            accountManager.checkAccess(caller, null, true, vm);
            checkZone(zoneId, vm.getDataCenterId());
            owner = accountManager.getAccount(vm.getAccountId());
        }
        ServiceOfferingVO offering = null;
        if (offeringId != null) {
            offering = serviceOfferingDao.findById(offeringId);
            if (offering == null || offering.getRemoved() != null) {
                throw new InvalidParameterValueException("No such service offering");
            }
            if (CapacityManager.isHostCapacityExempt(offering)) {
                throw new InvalidParameterValueException("The service offering has a vGPU profile; the maxima are those of Instances without one");
            }
            accountManager.checkAccess(owner, offering, zone);
        }
        int vcpuStep = UserVmManager.poolVcpuStep(owner.getDomainId());
        int smallestInstanceCores = HostPoolCoreManager.coresForVcpus(vcpuStep);
        long maxCores = 0;
        long maxMemory = 0;
        long maxDisk = 0;
        for (Host host : deploymentPlanningManager.listHostsUsableByAccount(owner, zoneId, offering)) {
            CapacityManager.HostFreeCapacity free = HostPoolCoreManager.usesPoolCores(host) ? capacityManager.getFreeCapacity(host) : null;
            if (free == null || free.getFreeCores() < smallestInstanceCores || free.getFreeMemoryBytes() <= 0) {
                continue;
            }
            maxCores = Math.max(maxCores, free.getFreeCores());
            maxMemory = Math.max(maxMemory, free.getFreeMemoryBytes());
            maxDisk = Math.max(maxDisk, allocatableLocalBytes(host));
        }
        return new PlacementCapacityResponse(requestableVcpus(maxCores, vcpuStep), maxMemory / MIB, maxDisk / GIB, vm == null ? null : thisHostOf(vm, vcpuStep));
    }

    /** What is free on the host an Instance runs on, or on the host of its root volume's local storage when it is stopped; null when there is none. */
    private PlacementThisHostResponse thisHostOf(VMInstanceVO vm, int vcpuStep) {
        HostVO host = hostOf(vm);
        if (host == null) {
            return null;
        }
        CapacityManager.HostFreeCapacity free = capacityManager.getFreeCapacity(host);
        long cores = free == null ? 0 : free.getFreeCores();
        long memory = free == null ? 0 : Math.max(0, free.getFreeMemoryBytes());
        ServiceOffering offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        Integer slots = CapacityManager.isHostCapacityExempt(offering) ? gpuService.slotCount(offering, host.getId()).getAvailable() : null;
        return new PlacementThisHostResponse(requestableVcpus(cores, vcpuStep), memory / MIB, allocatableLocalBytes(host) / GIB, slots);
    }

    /** @return the most vCPUs the cores give, as a multiple of the vCPU step, the sizes an Instance can have */
    private static long requestableVcpus(long cores, int vcpuStep) {
        return 2 * cores / vcpuStep * vcpuStep;
    }

    private HostVO hostOf(VMInstanceVO vm) {
        if (vm.getHostId() != null) {
            return hostDao.findById(vm.getHostId());
        }
        for (VolumeVO volume : volumeDao.findUsableVolumesForInstance(vm.getId())) {
            if (volume.getVolumeType() == Volume.Type.ROOT && volume.getPoolId() != null) {
                Set<Long> hostIds = localPoolHostIds(storagePoolDao.findById(volume.getPoolId()));
                return hostIds.size() == 1 ? hostDao.findById(hostIds.iterator().next()) : null;
            }
        }
        return null;
    }

    /** @return the most bytes of new disks the host's local storage takes in one pool, as the pool space check admits them */
    private long allocatableLocalBytes(Host host) {
        long most = 0;
        for (StoragePoolVO pool : storagePoolDao.findLocalStoragePoolsByHostAndTags(host.getId(), null)) {
            most = Math.max(most, storageManager.getAllocatableBytes(pool));
        }
        return most;
    }

    /**
     * @throws InvalidParameterValueException unless the command names exactly one of the four combinations, on resources
     *         the caller may use, in a state the operation accepts
     */
    PlacementRequest requestOf(CheckPlacementCmd cmd, Account caller) {
        Long vmId = cmd.getVirtualMachineId();
        Long volumeId = cmd.getVolumeId();
        Long templateId = cmd.getTemplateId();
        Long offeringId = cmd.getServiceOfferingId();
        boolean start = vmId != null && volumeId == null && templateId == null;
        boolean attach = vmId != null && volumeId != null && templateId == null && offeringId == null;
        boolean fromVolume = vmId == null && volumeId != null && templateId == null && offeringId != null;
        boolean deploy = vmId == null && volumeId == null && templateId != null && offeringId != null;
        if (!(start || attach || fromVolume || deploy)) {
            throw new InvalidParameterValueException("checkPlacement takes virtualmachineid; virtualmachineid and volumeid; volumeid and serviceofferingid; "
                    + "or templateid and serviceofferingid");
        }
        if (cmd.getRootDiskSize() != null && !deploy) {
            throw new InvalidParameterValueException("rootdisksize applies to a new Instance from a template only");
        }
        if (CollectionUtils.isNotEmpty(cmd.getDataDiskSizes()) && !(deploy || fromVolume)) {
            throw new InvalidParameterValueException("datadisksizes applies to a new Instance only");
        }
        if (!cmd.getDetails().isEmpty() && offeringId == null) {
            throw new InvalidParameterValueException("details apply to the service offering given by serviceofferingid");
        }
        ServiceOfferingVO offering = null;
        if (offeringId != null) {
            offering = serviceOfferingDao.findById(offeringId);
            if (offering == null || offering.getRemoved() != null) {
                throw new InvalidParameterValueException("No such service offering");
            }
        }
        if (start || attach) {
            VMInstanceVO vm = vmInstanceDao.findById(vmId);
            if (vm == null || vm.getRemoved() != null || vm.getType() != VirtualMachine.Type.User) {
                throw new InvalidParameterValueException("No such Instance");
            }
            accountManager.checkAccess(caller, null, true, vm);
            checkZone(cmd.getZoneId(), vm.getDataCenterId());
            if (start && vm.getState() != VirtualMachine.State.Stopped) {
                throw new InvalidParameterValueException(String.format("Instance %s is %s; the placement of an Instance is checked while it is stopped",
                        vm.getUuid(), vm.getState()));
            }
            if (attach && vm.getState() != VirtualMachine.State.Stopped && vm.getState() != VirtualMachine.State.Running) {
                throw new InvalidParameterValueException(String.format("Instance %s is %s; a volume is attached to a running or stopped Instance",
                        vm.getUuid(), vm.getState()));
            }
            if (offering != null) {
                accountManager.checkAccess(accountManager.getAccount(vm.getAccountId()), offering, dataCenterDao.findById(vm.getDataCenterId()));
            }
            if (start) {
                return PlacementRequest.start(vmId, offeringId, vm.getAccountId()).withDetails(cmd.getDetails());
            }
            VolumeVO volume = detachedVolume(volumeId, caller);
            accountManager.checkAccess(caller, null, true, volume, vm);
            return PlacementRequest.attach(vmId, volumeId, volume.getAccountId());
        }
        if (fromVolume) {
            VolumeVO volume = detachedVolume(volumeId, caller);
            if (volume.getState() != Volume.State.Ready || volume.getTemplateId() == null) {
                throw new InvalidParameterValueException(String.format("Volume %s is not a ready volume made from a template", volume.getUuid()));
            }
            checkZone(cmd.getZoneId(), volume.getDataCenterId());
            accountManager.checkAccess(accountManager.getAccount(volume.getAccountId()), offering, dataCenterDao.findById(volume.getDataCenterId()));
            return PlacementRequest.deployFromVolume(volumeId, offeringId, volume.getAccountId()).withDetails(cmd.getDetails())
                    .withDataDiskSizesGiB(cmd.getDataDiskSizes());
        }
        if (cmd.getZoneId() == null) {
            throw new InvalidParameterValueException("zoneid is required for a new Instance from a template");
        }
        DataCenterVO zone = dataCenterDao.findById(cmd.getZoneId());
        VMTemplateVO template = templateDao.findById(templateId);
        if (zone == null || template == null) {
            throw new InvalidParameterValueException("No such zone or Template");
        }
        accountManager.checkAccess(caller, AccessType.UseEntry, false, template);
        accountManager.checkAccess(caller, offering, zone);
        return PlacementRequest.deploy(templateId, offeringId, zone.getId(), caller.getId()).withDetails(cmd.getDetails())
                .withRootDiskSizeGiB(cmd.getRootDiskSize()).withDataDiskSizesGiB(cmd.getDataDiskSizes());
    }

    private void checkZone(Long requested, long actual) {
        if (requested != null && requested != actual) {
            throw new InvalidParameterValueException("zoneid is not the zone of the given resource");
        }
    }

    private VolumeVO detachedVolume(long volumeId, Account caller) {
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null || volume.getRemoved() != null) {
            throw new InvalidParameterValueException("No such volume");
        }
        accountManager.checkAccess(caller, null, true, volume);
        if (volume.getInstanceId() != null) {
            throw new InvalidParameterValueException(String.format("Volume %s is attached to an Instance", volume.getUuid()));
        }
        return volume;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_MOVE_RATE_UPDATE, eventDescription = "updating the move rate of a host")
    public void updateHostMoveRate(UpdateHostMoveRateCmd cmd) {
        HostVO host = hostDao.findById(cmd.getHostId());
        if (host == null || host.getRemoved() != null || host.getType() != Host.Type.Routing) {
            throw new InvalidParameterValueException("No such host");
        }
        Long mbps = cmd.getMbps();
        if (mbps == null || mbps < 0) {
            throw new InvalidParameterValueException("mbps must be a positive number of megabits per second, or 0 to remove the host's rate");
        }
        if (mbps == 0) {
            DetailVO detail = hostDetailsDao.findDetail(host.getId(), HOST_MOVE_RATE_DETAIL);
            if (detail != null) {
                hostDetailsDao.remove(detail.getId());
            }
            return;
        }
        Map<String, String> detail = new HashMap<>();
        detail.put(HOST_MOVE_RATE_DETAIL, String.valueOf(mbps));
        hostDetailsDao.persist(host.getId(), detail);
    }

    @Override
    public PlacementVerdict check(PlacementRequest request) {
        Evaluation evaluation = evaluate(request);
        Candidate move = verdictMove(evaluation);
        if (move == null) {
            return verdictWithoutMove(evaluation);
        }
        long expiry = nowSeconds() + Math.max(0, PlacementTokenExpirySeconds.value());
        return moveVerdict(evaluation, move, token(move, request.getApprovalInstanceId(), expiry));
    }

    @Override
    public PlacementVerdict approvedMove(PlacementRequest request, boolean migrateVolumes, String token) throws InsufficientCapacityException {
        Evaluation evaluation = evaluate(request);
        Candidate best = verdictMove(evaluation);
        if (best == null) {
            PlacementVerdict verdict = verdictWithoutMove(evaluation);
            if (verdict.getVerdict() == PlacementVerdict.Verdict.none) {
                throw new InsufficientServerCapacityException(String.format("%s is not possible now: %s", evaluation.context.subject, verdict.getReason()),
                        DataCenter.class, evaluation.context.zoneId);
            }
            return null;
        }
        long expiry = nowSeconds() + Math.max(0, PlacementTokenExpirySeconds.value());
        PlacementVerdict current = moveVerdict(evaluation, best, token(best, request.getApprovalInstanceId(), expiry));
        if (!migrateVolumes || StringUtils.isBlank(token)) {
            throw new VolumeMoveRequiredException(String.format("%s needs its volumes moved, which the owner approves: it is %s. Check the placement and "
                    + "approve the move with migratevolumes and the placementtoken checkPlacement returns.", evaluation.context.subject, current.describe()),
                    evaluation.context.zoneId, current);
        }
        Long tokenExpiry = expiryOf(token);
        if (tokenExpiry != null && tokenExpiry >= nowSeconds()) {
            for (Candidate candidate : evaluation.movable()) {
                if (tokenMatches(token, candidate, request.getApprovalInstanceId(), tokenExpiry)) {
                    return moveVerdict(evaluation, candidate, token);
                }
            }
        }
        throw new VolumeMoveRequiredException(String.format("The approved move of %s has expired or no longer matches its placement, which is now %s. "
                + "Check the placement and approve again.", evaluation.context.subject, current.describe()), evaluation.context.zoneId, current);
    }

    private PlacementVerdict verdictWithoutMove(Evaluation evaluation) {
        boolean keep = previousCopyKept(evaluation.context);
        if (evaluation.reason != null) {
            return PlacementVerdict.none(evaluation.reason, keep);
        }
        if (noMoveNeeded(evaluation)) {
            return PlacementVerdict.here(keep);
        }
        if (!evaluation.tiedToHost && evaluation.candidates.stream().anyMatch(c -> c.blocker == null)) {
            return PlacementVerdict.anyHost(keep);
        }
        Candidate blocked = evaluation.candidates.stream().filter(c -> c.blocker != null).findFirst().orElse(null);
        return PlacementVerdict.none(blocked == null ? REASON_LOCAL_STORAGE : blocked.blocker, keep);
    }

    /** @return whether a host that already holds every ready local volume of the request takes it */
    private boolean noMoveNeeded(Evaluation evaluation) {
        return evaluation.tiedToHost && evaluation.candidates.stream().anyMatch(c -> c.toMove.isEmpty() && c.blocker == null);
    }

    /** @return the candidate a move goes to, or null when the verdict needs no move or none is possible */
    private Candidate verdictMove(Evaluation evaluation) {
        if (evaluation.reason != null || !evaluation.tiedToHost || noMoveNeeded(evaluation)) {
            return null;
        }
        List<Candidate> movable = evaluation.movable();
        return movable.isEmpty() ? null : movable.get(0);
    }

    private PlacementVerdict moveVerdict(Evaluation evaluation, Candidate candidate, String token) {
        List<PlacementVerdict.MovedVolume> moved = new ArrayList<>();
        for (VolumeVO volume : candidate.toMove) {
            moved.add(new PlacementVerdict.MovedVolume(volume.getId(), volume.getUuid(), volume.getName(), volumeSize(volume), physicalSize(volume)));
        }
        return PlacementVerdict.move(moved, estimatedSeconds(candidate), previousCopyKept(evaluation.context), token, candidate.host.getId(),
                candidate.pool.getId());
    }

    Evaluation evaluate(PlacementRequest request) {
        Context context = contextOf(request);
        if (request.getKind() != PlacementRequest.Kind.ATTACH) {
            try {
                resourceLimitService.checkWithinQuota(context.owner, context.offering, context.template);
            } catch (ResourceAllocationException e) {
                return new Evaluation(context, new ArrayList<>(), false, REASON_QUOTA);
            }
        }
        if (context.newSize && context.vm.getType() == VirtualMachine.Type.User) {
            try {
                userVmManager.validatePoolVcpuStep(VirtualMachine.Type.User, context.offering, context.offering.getCpu(), context.owner.getDomainId());
            } catch (InvalidParameterValueException e) {
                return new Evaluation(context, new ArrayList<>(), false, REASON_VCPU_STEP);
            }
        }
        if (request.getKind() == PlacementRequest.Kind.ATTACH && context.vm.getState() == VirtualMachine.State.Running) {
            return evaluateAttachToRunning(context);
        }
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(context.vm, context.template, context.offering, context.owner, null);
        List<Host> admitting;
        try {
            admitting = deploymentPlanningManager.listHostsAdmittingVm(profile, new DataCenterDeployment(context.zoneId));
        } catch (AffinityConflictException e) {
            return new Evaluation(context, new ArrayList<>(), false, REASON_AFFINITY);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Host host : admitting) {
            Candidate candidate = place(context, profile, host);
            if (candidate != null) {
                candidate.blocker = blocker(context, candidate.toMove);
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingLong((Candidate c) -> c.bytesHeld).reversed());
        boolean tied = tiedToHost(context.volumes);
        if (candidates.isEmpty()) {
            return new Evaluation(context, candidates, tied, admitting.isEmpty() ? computeReason(context) : REASON_LOCAL_STORAGE);
        }
        return new Evaluation(context, candidates, tied, null);
    }

    /**
     * A running Instance never moves, so a volume attached to it is placed on its host: on the pool of its root volume
     * when the volume does not exist yet, or moved to that pool from the local storage of another host.
     */
    private Evaluation evaluateAttachToRunning(Context context) {
        VolumeVO volume = volumeDao.findById(context.request.getVolumeId());
        HostVO host = hostDao.findById(context.vm.getHostId());
        VolumeVO root = context.volumes.stream().filter(v -> v.getVolumeType() == Volume.Type.ROOT && v.getPoolId() != null).findFirst().orElse(null);
        StoragePoolVO rootPool = root == null ? null : storagePoolDao.findById(root.getPoolId());
        if (host == null || rootPool == null) {
            return new Evaluation(context, new ArrayList<>(), true, REASON_LOCAL_STORAGE);
        }
        List<Candidate> here = new ArrayList<>();
        here.add(new Candidate(host, rootPool, new ArrayList<>(), 0));
        DiskOfferingVO offering = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
        if (volume.getState() != Volume.State.Ready || volume.getPoolId() == null) {
            List<Pair<Volume, DiskProfile>> asked = new ArrayList<>();
            asked.add(new Pair<>(null, newDiskProfile(volume, offering)));
            boolean room = storageManager.storagePoolHasEnoughSpace(asked, rootPool, host.getClusterId());
            return new Evaluation(context, room ? here : new ArrayList<>(), true, room ? null : REASON_LOCAL_STORAGE);
        }
        StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
        if (pool == null) {
            return new Evaluation(context, new ArrayList<>(), true, REASON_LOCAL_STORAGE);
        }
        if (pool.getScope() != ScopeType.HOST || localPoolHostIds(pool).contains(host.getId())) {
            return new Evaluation(context, here, true, null);
        }
        DiskProfile disk = new DiskProfile(volume, offering, HypervisorType.KVM);
        disk.setUseLocalStorage(true);
        List<Pair<Volume, DiskProfile>> asked = new ArrayList<>();
        asked.add(new Pair<>(volume, disk));
        if (rootPool.getScope() != ScopeType.HOST || !storageManager.storagePoolHasEnoughSpace(asked, rootPool, host.getClusterId())) {
            return new Evaluation(context, new ArrayList<>(), true, REASON_LOCAL_STORAGE);
        }
        List<VolumeVO> toMove = new ArrayList<>();
        toMove.add(volume);
        Candidate candidate = new Candidate(host, rootPool, toMove, 0);
        candidate.blocker = blocker(context, toMove);
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(candidate);
        return new Evaluation(context, candidates, true, null);
    }

    /** @return whether a ready volume of the request is on the local storage of a host, which ties it to that host */
    private boolean tiedToHost(List<VolumeVO> volumes) {
        for (VolumeVO volume : volumes) {
            if (volume.getState() != Volume.State.Ready || volume.getPoolId() == null) {
                continue;
            }
            StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
            if (pool != null && pool.getScope() == ScopeType.HOST) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the host as a candidate when its storage can take every disk that is not on it already, with the same
     *         storage allocators and pool space check a start uses; or null
     */
    private Candidate place(Context context, VirtualMachineProfile profile, Host host) {
        List<Pair<Volume, DiskProfile>> local = new ArrayList<>();
        List<DiskProfile> shared = new ArrayList<>();
        List<VolumeVO> toMove = new ArrayList<>();
        long held = 0;
        Long heldPoolId = null;
        for (VolumeVO volume : context.volumes) {
            DiskOfferingVO offering = diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
            if (volume.getState() == Volume.State.Ready && volume.getPoolId() != null) {
                StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
                if (pool == null) {
                    return null;
                }
                if (pool.getScope() == ScopeType.HOST) {
                    if (localPoolHostIds(pool).contains(host.getId())) {
                        held += volumeSize(volume);
                        heldPoolId = pool.getId();
                    } else {
                        DiskProfile disk = new DiskProfile(volume, offering, context.vm.getHypervisorType());
                        disk.setUseLocalStorage(true);
                        local.add(new Pair<>(volume, disk));
                        toMove.add(volume);
                    }
                } else if (pool.getScope() == ScopeType.CLUSTER && !Objects.equals(pool.getClusterId(), host.getClusterId())) {
                    return null;
                }
                continue;
            }
            DiskProfile disk = newDiskProfile(volume, offering);
            if (disk.useLocalStorage()) {
                local.add(new Pair<>(null, disk));
            } else {
                shared.add(disk);
            }
        }
        for (DiskProfile disk : context.newDisks) {
            if (disk.useLocalStorage()) {
                local.add(new Pair<>(null, disk));
            } else {
                shared.add(disk);
            }
        }
        DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), host.getId(), null, null);
        StoragePool target = heldPoolId == null ? null : storagePoolDao.findById(heldPoolId);
        if (!local.isEmpty()) {
            List<StoragePool> common = null;
            for (Pair<Volume, DiskProfile> disk : local) {
                List<StoragePool> pools = deploymentPlanningManager.listPoolsForDisk(disk.second(), profile, plan);
                if (common == null) {
                    common = new ArrayList<>(pools);
                } else {
                    List<Long> ids = pools.stream().map(StoragePool::getId).collect(Collectors.toList());
                    common.removeIf(pool -> !ids.contains(pool.getId()));
                }
            }
            if (CollectionUtils.isEmpty(common)) {
                return null;
            }
            Long preferred = heldPoolId;
            target = common.stream().filter(pool -> Objects.equals(pool.getId(), preferred)).findFirst().orElse(common.get(0));
            if (!storageManager.storagePoolHasEnoughSpace(local, target, host.getClusterId())) {
                return null;
            }
        }
        // as planning does: the largest disk first, each on the first pool that still takes it with the disks put there before
        shared.sort(Comparator.comparingLong(DiskProfile::getSize).reversed());
        Map<Long, List<Pair<Volume, DiskProfile>>> sharedByPool = new HashMap<>();
        for (DiskProfile disk : shared) {
            boolean placed = false;
            for (StoragePool pool : deploymentPlanningManager.listPoolsForDisk(disk, profile, plan)) {
                List<Pair<Volume, DiskProfile>> onPool = new ArrayList<>(sharedByPool.getOrDefault(pool.getId(), new ArrayList<>()));
                onPool.add(new Pair<>(null, disk));
                if (storageManager.storagePoolHasEnoughSpace(onPool, pool, host.getClusterId())) {
                    sharedByPool.put(pool.getId(), onPool);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                return null;
            }
        }
        if (!toMove.isEmpty() && target == null) {
            return null;
        }
        return new Candidate(host, target, toMove, held);
    }

    private DiskProfile newDiskProfile(VolumeVO volume, DiskOfferingVO offering) {
        DiskProfile disk = new DiskProfile(Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID, volume.getVolumeType(), volume.getName(), offering.getId(),
                volumeSize(volume), offering.getTagsArray(), offering.isUseLocalStorage(), offering.isRecreatable(),
                volume.getVolumeType() == Volume.Type.ROOT ? volume.getTemplateId() : null);
        disk.setHyperType(HypervisorType.KVM);
        return disk;
    }

    /**
     * @return why a placement that moves these volumes and creates the request's new disks is refused, as the move and
     *         the creation refuse it; or null
     */
    private String blocker(Context context, List<VolumeVO> toMove) {
        if (toMove.isEmpty()) {
            return context.newDisks.isEmpty() || storageQuotaAdmits(context, toMove) ? null : REASON_STORAGE_QUOTA;
        }
        if (context.vm.getId() > 0 && CollectionUtils.isNotEmpty(vmSnapshotDao.findByVm(context.vm.getId()))) {
            return REASON_INSTANCE_SNAPSHOTS;
        }
        for (VolumeVO volume : toMove) {
            StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
            Set<Long> sourceHosts = localPoolHostIds(pool);
            if (pool == null || pool.getPoolType() != Storage.StoragePoolType.Filesystem || sourceHosts.size() != 1) {
                return REASON_LOCAL_STORAGE;
            }
            if (!VolumeOrchestrationService.servesLocalVolumes(hostDao.findById(sourceHosts.iterator().next()))) {
                return REASON_VOLUMES_UNAVAILABLE;
            }
            if (volume.getPassphraseId() != null || volume.getKmsKeyId() != null) {
                return REASON_ENCRYPTED_VOLUME;
            }
            try {
                snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, HypervisorType.KVM);
            } catch (CloudRuntimeException e) {
                return REASON_PRIMARY_SNAPSHOTS;
            }
        }
        return storageQuotaAdmits(context, toMove) ? null : REASON_STORAGE_QUOTA;
    }

    /**
     * Each kept previous copy is a new volume of the owner, counted like any volume being created; so are the disks a new
     * Instance creates. The same limits that admit them are asked, without reserving anything.
     */
    private boolean storageQuotaAdmits(Context context, List<VolumeVO> toMove) {
        Map<String, long[]> demand = new HashMap<>();
        if (previousCopyKept(context)) {
            for (VolumeVO volume : toMove) {
                addDemand(demand, volume.isDisplayVolume(), diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId()), volumeSize(volume));
            }
        }
        for (DiskProfile disk : context.newDisks) {
            addDemand(demand, true, diskOfferingDao.findByIdIncludingRemoved(disk.getDiskOfferingId()), disk.getSize());
        }
        try {
            for (Map.Entry<String, long[]> entry : demand.entrySet()) {
                long count = entry.getValue()[0];
                long bytes = entry.getValue()[1];
                if (entry.getKey() == null) {
                    long remaining = resourceLimitService.getRemainingStorageQuota(context.owner);
                    if (remaining != Resource.RESOURCE_UNLIMITED && bytes > remaining) {
                        return false;
                    }
                    resourceLimitService.checkResourceLimit(context.owner, ResourceType.volume, count);
                } else {
                    resourceLimitService.checkResourceLimitWithTag(context.owner, ResourceType.primary_storage, entry.getKey(), bytes);
                    resourceLimitService.checkResourceLimitWithTag(context.owner, ResourceType.volume, entry.getKey(), count);
                }
            }
        } catch (ResourceAllocationException e) {
            return false;
        }
        return true;
    }

    private void addDemand(Map<String, long[]> demand, boolean display, DiskOfferingVO offering, long bytes) {
        List<String> tags = resourceLimitService.getResourceLimitStorageTagsForResourceCountOperation(display, offering, null);
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            long[] totals = demand.computeIfAbsent(tag, t -> new long[2]);
            totals[0]++;
            totals[1] += bytes;
        }
    }

    private String computeReason(Context context) {
        ServiceOffering offering = context.offering;
        if (CapacityManager.isHostCapacityExempt(offering)) {
            return gpuReason(offering);
        }
        long mhz = (long) offering.getCpu() * (offering.getSpeed() == null ? 0 : offering.getSpeed());
        long memory = offering.getRamSize() * 1024L * 1024L;
        boolean cpu = false;
        boolean cpuAndMemory = false;
        for (HostVO host : resourceManager.listAllUpAndEnabledHosts(Host.Type.Routing, null, null, context.zoneId)) {
            if (host.getHypervisorType() != context.vm.getHypervisorType() || !host.checkHostServiceOfferingTags(offering)) {
                continue;
            }
            CapacityManager.HostFreeCapacity free = capacityManager.getFreeCapacity(host);
            if (free == null) {
                continue;
            }
            boolean hostCpu = capacityManager.checkIfHostHasPoolCores(host, offering.getCpu()) && free.getFreeCpuMhz() >= mhz;
            cpu |= hostCpu;
            cpuAndMemory |= hostCpu && free.getFreeMemoryBytes() >= memory;
        }
        if (!cpu) {
            return REASON_CPU_POOL;
        }
        return cpuAndMemory ? REASON_SUITABLE_HOST : REASON_MEMORY;
    }

    private String gpuReason(ServiceOffering offering) {
        VgpuProfileVO profile = vgpuProfileDao.findById(offering.getVgpuProfileId());
        GpuCardVO card = profile == null || profile.getCardId() == null ? null : gpuCardDao.findById(profile.getCardId());
        String type = card != null ? card.getName() : profile != null ? profile.getName() : "unknown";
        int count = offering.getGpuCount() != null ? offering.getGpuCount() : 1;
        return String.format("gpu group %s x%d", type, count);
    }

    private Context contextOf(PlacementRequest request) {
        Account owner = accountManager.getAccount(request.getOwnerId());
        switch (request.getKind()) {
            case START:
            case ATTACH: {
                VMInstanceVO vm = vmInstanceDao.findById(request.getVmId());
                if (vm == null) {
                    throw new InvalidParameterValueException("No such Instance");
                }
                VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                boolean newOffering = request.getServiceOfferingId() != null;
                ServiceOffering offering = newOffering ? offering(request.getServiceOfferingId(), request.getDetails())
                        : serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                List<VolumeVO> volumes = new ArrayList<>(volumeDao.findUsableVolumesForInstance(vm.getId()));
                String subject = String.format("Starting Instance %s", vm.getUuid());
                if (request.getKind() == PlacementRequest.Kind.ATTACH) {
                    VolumeVO volume = volumeDao.findById(request.getVolumeId());
                    volumes.add(volume);
                    subject = String.format("Attaching volume %s to Instance %s", volume.getName(), vm.getUuid());
                }
                return new Context(request, owner, vm.getDataCenterId(), vm, template, offering, volumes, new ArrayList<>(), newOffering, subject);
            }
            case DEPLOY_FROM_VOLUME: {
                VolumeVO volume = volumeDao.findById(request.getVolumeId());
                if (volume == null) {
                    throw new InvalidParameterValueException("No such volume");
                }
                VMTemplateVO template = volume.getTemplateId() == null ? null : templateDao.findByIdIncludingRemoved(volume.getTemplateId());
                if (template == null) {
                    throw new InvalidParameterValueException(String.format("Volume %s was not made from a Template; an Instance is created only from such a volume",
                            volume.getUuid()));
                }
                ServiceOfferingVO offering = offering(request.getServiceOfferingId(), request.getDetails());
                List<VolumeVO> volumes = new ArrayList<>();
                volumes.add(volume);
                VMInstanceVO vm = newInstance(owner, template, offering, volume.getDataCenterId());
                return new Context(request, owner, volume.getDataCenterId(), vm, template, offering, volumes, dataDisks(request, offering), true,
                        String.format("An Instance from volume %s", volume.getName()));
            }
            default: {
                VMTemplateVO template = templateDao.findByIdIncludingRemoved(request.getTemplateId());
                ServiceOfferingVO offering = offering(request.getServiceOfferingId(), request.getDetails());
                VMInstanceVO vm = newInstance(owner, template, offering, request.getZoneId());
                List<DiskProfile> disks = new ArrayList<>();
                disks.add(rootDisk(request, template, offering, owner));
                disks.addAll(dataDisks(request, offering));
                return new Context(request, owner, request.getZoneId(), vm, template, offering, new ArrayList<>(), disks, true,
                        String.format("An Instance from Template %s", template.getName()));
            }
        }
    }

    private ServiceOfferingVO offering(long offeringId, Map<String, String> details) {
        ServiceOfferingVO offering = serviceOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new InvalidParameterValueException("No such service offering");
        }
        if (offering.isDynamic()) {
            userVmManager.validateCustomParameters(offering, details);
            offering = serviceOfferingDao.getComputeOffering(offering, details);
        }
        return offering;
    }

    /** An Instance not created yet, as planning sees it. */
    private VMInstanceVO newInstance(Account owner, VMTemplateVO template, ServiceOffering offering, long zoneId) {
        UserVmVO vm = new UserVmVO(0, "placement", "placement", template.getId(), template.getHypervisorType(), template.getGuestOSId(), false, false,
                owner.getDomainId(), owner.getId(), User.UID_SYSTEM, offering.getId(), null, null, null, "placement");
        vm.setDataCenterId(zoneId);
        return vm;
    }

    private DiskProfile rootDisk(PlacementRequest request, VMTemplateVO template, ServiceOffering offering, Account owner) {
        DiskOfferingVO rootOffering = diskOfferingDao.findById(offering.getDiskOfferingId());
        Map<String, String> parameters = new HashMap<>(request.getDetails());
        if (request.getRootDiskSizeGiB() != null) {
            parameters.put(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(request.getRootDiskSizeGiB()));
        }
        long size = userVmManager.configureCustomRootDiskSize(parameters, template, template.getHypervisorType(), rootOffering, true, owner.getDomainId());
        DiskProfile disk = new DiskProfile(Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID, Volume.Type.ROOT, "ROOT", rootOffering.getId(), size,
                rootOffering.getTagsArray(), rootOffering.isUseLocalStorage(), false, template.getId());
        disk.setHyperType(template.getHypervisorType());
        return disk;
    }

    /** Data disks of a new Instance take the storage of its root disk offering. */
    private List<DiskProfile> dataDisks(PlacementRequest request, ServiceOffering offering) {
        DiskOfferingVO rootOffering = diskOfferingDao.findById(offering.getDiskOfferingId());
        List<DiskProfile> disks = new ArrayList<>();
        for (Long sizeGiB : request.getDataDiskSizesGiB()) {
            if (sizeGiB == null || sizeGiB <= 0) {
                throw new InvalidParameterValueException("A data disk size must be a positive number of GiB");
            }
            DiskProfile disk = new DiskProfile(Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID, Volume.Type.DATADISK, "DATA", rootOffering.getId(),
                    sizeGiB * GIB, rootOffering.getTagsArray(), rootOffering.isUseLocalStorage(), false, null);
            disk.setHyperType(HypervisorType.KVM);
            disks.add(disk);
        }
        return disks;
    }

    /**
     * @return the hosts a host-local pool is attached to, empty for any other pool; a start on one of them needs no
     *         move of the pool's volumes, and only a pool on exactly one host is moved from
     */
    protected Set<Long> localPoolHostIds(StoragePoolVO pool) {
        Set<Long> hostIds = new HashSet<>();
        if (pool == null || pool.getScope() != ScopeType.HOST) {
            return hostIds;
        }
        for (StoragePoolHostVO poolHost : storagePoolHostDao.listByPoolId(pool.getId())) {
            hostIds.add(poolHost.getHostId());
        }
        return hostIds;
    }

    private boolean previousCopyKept(Context context) {
        return Boolean.TRUE.equals(VolumeOrchestrationService.VolumeMoveKeepSource.valueIn(context.owner.getDomainId()));
    }

    private static long volumeSize(VolumeVO volume) {
        return volume.getSize() == null ? 0 : volume.getSize();
    }

    /** @return the bytes the volume uses by its latest statistics, or its virtual size when there are none */
    protected long physicalSize(VolumeVO volume) {
        VolumeStats stats = statsCollector == null || volume.getPath() == null ? null : statsCollector.getVolumeStats(volume.getPath());
        return stats != null && stats.getPhysicalSize() > 0 ? stats.getPhysicalSize() : volumeSize(volume);
    }

    /** @return the rate in megabits per second at which a host moves volumes */
    protected long hostMoveRate(long hostId) {
        DetailVO detail = hostDetailsDao.findDetail(hostId, HOST_MOVE_RATE_DETAIL);
        if (detail != null) {
            try {
                long mbps = Long.parseLong(detail.getValue().trim());
                if (mbps > 0) {
                    return mbps;
                }
            } catch (NumberFormatException e) {
                logger.warn("Host {} has an unreadable {} '{}'; the estimate uses {}", hostId, HOST_MOVE_RATE_DETAIL, detail.getValue(), VolumeMoveEstimateMbps.key());
            }
        }
        Long estimate = VolumeMoveEstimateMbps.value();
        return estimate == null || estimate <= 0 ? 1 : estimate;
    }

    /** The volumes are copied one after the other, each at the slower of its source and destination host. */
    long estimatedSeconds(Candidate candidate) {
        long destinationRate = hostMoveRate(candidate.host.getId());
        BigDecimal seconds = BigDecimal.ZERO;
        for (VolumeVO volume : candidate.toMove) {
            Set<Long> sourceHosts = localPoolHostIds(storagePoolDao.findById(volume.getPoolId()));
            long rate = sourceHosts.size() != 1 ? destinationRate : Math.min(hostMoveRate(sourceHosts.iterator().next()), destinationRate);
            BigDecimal bits = BigDecimal.valueOf(physicalSize(volume)).multiply(BigDecimal.valueOf(8));
            seconds = seconds.add(bits.divide(BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(1_000_000L)), 30, RoundingMode.HALF_UP));
        }
        return seconds.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    protected long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    /**
     * The approval binds the destination host, each moved volume with its pool and size, the Instance when there is one,
     * and the expiry. It is an HMAC keyed by a secret every management server shares, so only a management server can
     * issue one; it reveals nothing but its expiry.
     */
    String token(Candidate candidate, Long instanceId, long expiry) {
        return expiry + TOKEN_SEPARATOR + Hex.encodeHexString(mac(candidate, instanceId, expiry));
    }

    boolean tokenMatches(String token, Candidate candidate, Long instanceId, long expiry) {
        String[] parts = token.trim().split(TOKEN_SEPARATOR, 2);
        if (parts.length != 2) {
            return false;
        }
        byte[] presented;
        try {
            presented = Hex.decodeHex(parts[1].toCharArray());
        } catch (org.apache.commons.codec.DecoderException e) {
            return false;
        }
        return MessageDigest.isEqual(mac(candidate, instanceId, expiry), presented);
    }

    static Long expiryOf(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        String[] parts = token.trim().split(TOKEN_SEPARATOR, 2);
        try {
            return parts.length == 2 ? Long.parseLong(parts[0]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] mac(Candidate candidate, Long instanceId, long expiry) {
        StringBuilder message = new StringBuilder(TOKEN_KEY_PURPOSE).append('\n');
        message.append("host=").append(candidate.host.getId()).append('\n');
        message.append("instance=").append(instanceId == null ? "" : String.valueOf(instanceId)).append('\n');
        message.append("volumes=").append(candidate.toMove.stream().sorted(Comparator.comparingLong(VolumeVO::getId))
                .map(v -> v.getId() + ":" + v.getPoolId() + ":" + volumeSize(v)).collect(Collectors.joining(","))).append('\n');
        message.append("expires=").append(expiry);
        return hmac(tokenKey(), message.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A key used for these approvals only, derived from the shared secret for keyed hashes. */
    private byte[] tokenKey() {
        String secret = keysManager.getHashKey();
        if (StringUtils.isBlank(secret)) {
            throw new CloudRuntimeException("The management server has no secret to sign placement approvals with");
        }
        return hmac(secret.getBytes(StandardCharsets.UTF_8), TOKEN_KEY_PURPOSE.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new CloudRuntimeException("HMAC-SHA256 is not available", e);
        }
    }
}
