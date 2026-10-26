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
package org.apache.cloudstack.engine.orchestration.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloud.resource.ResourceState;
import com.cloud.host.Status;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientStorageCapacityException;
import com.cloud.exception.StorageAccessException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

/**
 * VolumeOrchestrationService is a PURE orchestration service on CloudStack
 * volumes.  It does not understand resource limits, ACL, action events, or
 * anything that has to do with the self-service portion of CloudStack.  Its
 * job is to carry out any orchestration needed among the physical components
 * to provision volumes.
 */
public interface VolumeOrchestrationService {

    ConfigKey<Long> CustomDiskOfferingMinSize = new ConfigKey<Long>("Advanced",
        Long.class,
        "custom.diskoffering.size.min",
        "1",
        "Minimum size in GB for custom disk offering.",
        true
    );

    ConfigKey<Long> CustomDiskOfferingMaxSize = new ConfigKey<Long>("Advanced",
        Long.class,
        "custom.diskoffering.size.max",
        "1024",
        "Maximum size in GB for custom disk offering.",
        true
    );

    ConfigKey<Long> MaxVolumeSize = new ConfigKey<Long>("Storage",
            Long.class,
            "storage.max.volume.size",
            "2000",
            "The maximum size for a volume (in GiB).",
            true);

    ConfigKey<String> VolumeAllocationAlgorithm = new ConfigKey<>(
            String.class,
            "volume.allocation.algorithm",
            "Advanced",
            "random",
            "Order in which storage pool within a cluster will be considered for volume allocation. The value can be 'random', 'firstfit', 'userdispersing', or 'firstfitleastconsumed'.",
            true,
            ConfigKey.Scope.Global, null, null, null, null, null,
            ConfigKey.Kind.Select,
            "random,firstfit,userdispersing,firstfitleastconsumed");

    ConfigKey<Boolean> VolumeMoveKeepSource = new ConfigKey<>(Boolean.class, "volume.move.keep.source", ConfigKey.CATEGORY_ADVANCED, "true",
            "Whether moving a volume from the local storage of one host to another keeps the copy at its previous location as a separate, detached volume of the "
                    + "same owner, named <name>-previous-<yyyyMMdd>, which counts against the owner's storage quota until the owner deletes it. When false, "
                    + "that copy is deleted as soon as the volume uses its new location, and the move needs no quota for it.",
            true, ConfigKey.Scope.Domain, null);

    ConfigKey<Integer> VolumeMoveConcurrencyPerHost = new ConfigKey<>(Integer.class, "volume.move.concurrency.per.host", ConfigKey.CATEGORY_ADVANCED,
            "2", "The most direct volume copies between the local storage of two hosts that one host takes part in at a time, as source or "
                    + "destination; a further copy waits for a free slot on both of its hosts, at most copy.volume.wait seconds. 0 or less sets no bound.",
            true, ConfigKey.Scope.Global, null);

    /** The volume detail, on a volume that holds the copy of another volume being moved, naming that volume's id. */
    String RELOCATION_OF = "relocation.of";

    /**
     * The volume detail, on the volume a direct copy between hosts goes to, that holds one of the copy slots of its
     * source and destination hosts while the copy runs: {@code <source host id>,<destination host id>,<async job id>}.
     */
    String TRANSFER_SLOT = "volume.transfer.slot";

    /**
     * The volume detail, on the volume a direct copy between hosts goes to, that names the transfer of the template base
     * the copy needs while that transfer runs: {@code <transfer name>,<base file on the destination pool>}.
     */
    String RELOCATION_BASE = "relocation.base";

    /**
     * @return whether the host can serve the files of its local pool to a direct copy: it is Up and in no maintenance
     *         state, in which its agent takes only the commands of the maintenance
     */
    static boolean servesLocalVolumes(Host host) {
        return host != null && host.getStatus() == Status.Up && !inMaintenance(host);
    }

    /**
     * @return whether the host is in a maintenance state, in which it serves no files of its local pool to a copy
     */
    static boolean inMaintenance(Host host) {
        return host != null && ResourceState.isMaintenanceState(host.getResourceState());
    }

    /**
     * @return the name of the direct transfer of a template's base to a local pool
     */
    static String templateBaseTransferName(long templateId, long destPoolId) {
        return String.format("template-%d-%d", templateId, destPoolId);
    }

    VolumeInfo moveVolume(VolumeInfo volume, long destPoolDcId, Long destPoolPodId, Long destPoolClusterId, HypervisorType dataDiskHyperType)
        throws ConcurrentOperationException, StorageUnavailableException;

    Volume allocateDuplicateVolume(Volume oldVol, DiskOffering diskOffering, Long templateId);

    boolean volumeOnSharedStoragePool(Volume volume);

    boolean volumeInactive(Volume volume);

    String getVmNameOnVolume(Volume volume);

    StoragePool findChildDataStoreInDataStoreCluster(DataCenter dc, Pod pod, Long clusterId, Long hostId, VirtualMachine vm, Long datastoreClusterId);

    VolumeInfo createVolumeFromSnapshot(Volume volume, Snapshot snapshot, UserVm vm) throws StorageUnavailableException;

    Volume migrateVolume(Volume volume, StoragePool destPool) throws StorageUnavailableException;

    Volume liveMigrateVolume(Volume volume, StoragePool destPool);

    /**
     * Moves volumes from the local storage of their KVM hosts to the local storage pool of another KVM host by
     * copying each file directly between the hosts, one volume after the other. Each volume keeps its id, uuid,
     * device and Instance; only its location changes, for all of them together in one transaction once every copy
     * is verified. The file at the previous location becomes a new volume of the same owner, or is deleted, as
     * {@link #VolumeMoveKeepSource} says for the owner's domain. On failure nothing has moved and the copies are removed.
     * <p>
     * Runs within an async job. A volume attached to an Instance moves only while that Instance's own work item holds
     * it in Migrating; a running Instance's volumes never move.
     *
     * @param inSwitch work done inside the transaction that repoints the volumes, or null
     * @return the previous copies that are kept
     */
    List<Volume> relocateVolumes(List<? extends Volume> volumes, StoragePool destPool, Runnable inSwitch);

    default List<Volume> relocateVolumes(List<? extends Volume> volumes, StoragePool destPool) {
        return relocateVolumes(volumes, destPool, null);
    }

    void cleanupStorageJobs();

    void destroyVolume(Volume volume);

    DiskProfile allocateRawVolume(Type type, String name, DiskOffering offering, Long size, Long minIops, Long maxIops, VirtualMachine vm, VirtualMachineTemplate template,
            Account owner, Long deviceId, Long kmsKeyId, boolean incrementResourceCount);

    VolumeInfo createVolumeOnPrimaryStorage(VirtualMachine vm, VolumeInfo volume, HypervisorType rootDiskHyperType, StoragePool storagePool, Long clusterId, Long podId) throws NoTransitionException;

    void release(VirtualMachineProfile profile);

    void release(long vmId, long hostId);

    void cleanupVolumes(long vmId) throws ConcurrentOperationException;

    /**
     * Detaches, as a data volume with its contents, disk offering and resource counts, the volume an Instance was being deployed from
     * when that deployment fails, so that the volume outlives the failed Instance; nothing for a volume no longer attached to it. Called
     * before the Instance is moved to Error, since an Instance in Error is expunged with every volume still attached to it.
     */
    void detachVolumeOfFailedDeployment(long vmId, long volumeId);

    /**
     * @return the snapshot from which the volume, not created yet, is created on the pool that its Instance's deployment chooses, as a
     *         volume from a Template is; null when the volume is not created from a snapshot that way
     * @throws com.cloud.utils.exception.CloudRuntimeException when that snapshot no longer exists or is not available in the volume's zone
     */
    Snapshot snapshotToCreateFrom(Volume volume);

    /**
     * @return whether the volume, not created yet, records a snapshot it is created from at its Instance's first start
     */
    boolean isPendingCreationFromSnapshot(Volume volume);

    /**
     * @return the least size that the volume, not created yet, is created at: the size of the snapshot it is created from, or of its
     *         Template for a ROOT volume; 0 for any other volume
     */
    long leastSizeAtCreation(Volume volume);

    /**
     * Removes at once a volume of a deployment that failed: one not created yet is removed, and one created is destroyed and expunged from
     * its storage, since nothing a failed deployment created is kept for recovery.
     */
    void expungeVolumeOfFailedDeployment(Volume volume);

    void revokeAccess(DataObject dataObject, Host host, DataStore dataStore);

    void revokeAccess(long vmId, long hostId);

    void migrateVolumes(VirtualMachine vm, VirtualMachineTO vmTo, Host srcHost, Host destHost, Map<Volume, StoragePool> volumeToPool);

    boolean storageMigration(VirtualMachineProfile vm, Map<Volume, StoragePool> volumeToPool) throws StorageUnavailableException;

    void prepareForMigration(VirtualMachineProfile vm, DeployDestination dest);

    void prepare(VirtualMachineProfile vm, DeployDestination dest) throws StorageUnavailableException, InsufficientStorageCapacityException, ConcurrentOperationException, StorageAccessException, ResourceAllocationException;

    boolean canVmRestartOnAnotherServer(long vmId);

    void saveVolumeDetails(Long diskOfferingId, Long volumeId);

    /**
     * Allocate a volume or multiple volumes in case of template is registered with the 'deploy-as-is' option, allowing multiple disks
     */
    List<DiskProfile> allocateTemplatedVolumes(Type type, String name, DiskOffering offering, Long rootDisksize, Long minIops, Long maxIops, VirtualMachineTemplate template, VirtualMachine vm,
                                               Account owner, Long kmsKeyId, Volume volume, Snapshot snapshot);

    String getVmNameFromVolumeId(long volumeId);

    String getStoragePoolOfVolume(long volumeId);

    boolean validateVolumeSizeRange(long size);

    StoragePool findStoragePool(DiskProfile dskCh, DataCenter dc, Pod pod, Long clusterId, Long hostId, VirtualMachine vm, Set<StoragePool> avoid);

    List<StoragePool> findStoragePoolsForVolumeWithNewDiskOffering(DiskProfile dskCh, DataCenter dc, Pod pod, Long clusterId, Long hostId, VirtualMachine vm, Set<StoragePool> avoid);

    void updateVolumeDiskChain(long volumeId, String path, String chainInfo, String updatedDataStoreUUID);

    /**
     * Imports an existing volume for a VM into database. Useful while ingesting an unmanaged VM.
     * @param type Type of the volume - ROOT, DATADISK, etc
     * @param name Name of the volume
     * @param offering DiskOffering for the volume
     * @param sizeInBytes size of the volume in bytes
     * @param minIops minimum IOPS for the disk, if not passed DiskOffering value will be used
     * @param maxIops maximum IOPS for the disk, if not passed DiskOffering value will be used
     * @param vm VirtualMachine this volume is attached to
     * @param template Template of the VM of the volume
     * @param owner owner Account for the volume
     * @param deviceId device ID of the virtual disk
     * @param poolId ID of pool in which volume is stored
     * @param path image path of the volume
     * @param chainInfo chain info for the volume. Hypervisor specific.
     * @return  DiskProfile of imported volume
     */
    DiskProfile importVolume(Type type, String name, DiskOffering offering, Long sizeInBytes, Long minIops, Long maxIops,
                             Long zoneId, HypervisorType hypervisorType, VirtualMachine vm, VirtualMachineTemplate template,
                             Account owner, Long deviceId, Long poolId, Storage.StoragePoolType poolType, String path, String chainInfo);

    DiskProfile updateImportedVolume(Type type, DiskOffering offering, VirtualMachine vm, VirtualMachineTemplate template,
                                     Long deviceId, Long poolId, Storage.StoragePoolType poolType, String path, String chainInfo, DiskProfile diskProfile);

    /**
     * Unmanage VM volumes
     */
    void unmanageVolumes(long vmId);

    /**
     * Retrieves the volume's checkpoints paths to be used in the KVM processor. If there are no checkpoints, it will return an empty list.
     */
    Pair<List<String>, Set<String>> getVolumeCheckpointPathsAndImageStoreUrls(long volumeId, HypervisorType hypervisorType);
}
