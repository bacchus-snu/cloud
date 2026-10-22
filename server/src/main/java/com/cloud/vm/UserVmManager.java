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
package com.cloud.vm;

import static com.cloud.user.ResourceLimitService.ResourceLimitHostTags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.resourcelimit.Reserver;

import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VMTemplateVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;

/**
 *
 *
 */
public interface UserVmManager extends UserVmService {
    String EnableDynamicallyScaleVmCK = "enable.dynamic.scale.vm";
    String AllowDiskOfferingChangeDuringScaleVmCK = "allow.diskoffering.change.during.scale.vm";
    String AllowUserExpungeRecoverVmCK ="allow.user.expunge.recover.vm";
    String AllowUserForceStopVmCK = "allow.user.force.stop.vm";
    ConfigKey<Boolean> EnableDynamicallyScaleVm = new ConfigKey<Boolean>("Advanced", Boolean.class, EnableDynamicallyScaleVmCK, "false",
        "Enables/Disables dynamically scaling a vm", true, ConfigKey.Scope.Zone);
    ConfigKey<Boolean> AllowDiskOfferingChangeDuringScaleVm = new ConfigKey<Boolean>("Advanced", Boolean.class, AllowDiskOfferingChangeDuringScaleVmCK, "false",
            "Determines whether to allow or disallow disk offering change for root volume during scaling of a stopped or running vm", true, ConfigKey.Scope.Zone);
    ConfigKey<Boolean> AllowUserExpungeRecoverVm = new ConfigKey<Boolean>("Advanced", Boolean.class, AllowUserExpungeRecoverVmCK, "false",
        "Determines whether users can expunge or recover their vm", true, ConfigKey.Scope.Account);
    ConfigKey<Boolean> AllowUserForceStopVm = new ConfigKey<Boolean>("Advanced", Boolean.class, AllowUserForceStopVmCK, "true",
            "Determines whether users are allowed to force stop a vm", true, ConfigKey.Scope.Account);
    ConfigKey<Boolean> DisplayVMOVFProperties = new ConfigKey<Boolean>("Advanced", Boolean.class, "vm.display.ovf.properties", "false",
            "Set display of VMs OVF properties as part of VM details", true);

    ConfigKey<Boolean> DestroyRootVolumeOnVmDestruction = new ConfigKey<Boolean>("Advanced", Boolean.class, "destroy.root.volume.on.vm.destruction", "false",
            "Destroys the VM's root volume when the VM is destroyed.",
            true, ConfigKey.Scope.Domain);

    ConfigKey<String> StrictHostTags = new ConfigKey<>(
            "Advanced",
            String.class,
            "vm.strict.host.tags",
            "",
            "A comma-separated list of tags which must match during operations like modifying the compute" +
                    "offering for an instance, and starting or live migrating an instance to a specific host.",
            true);
    ConfigKey<Boolean> EnforceStrictResourceLimitHostTagCheck = new ConfigKey<Boolean>(
            "Advanced",
            Boolean.class,
            "vm.strict.resource.limit.host.tag.check",
            "true",
            "If set to true, tags specified in `resource.limit.host.tags` are also included in vm.strict.host.tags.",
            true);

    ConfigKey<String> VmDistinctHostNameScope = new ConfigKey<>(String.class, "vm.distinct.hostname.scope", ConfigKey.CATEGORY_ADVANCED,
            "network",
            "Defines the scope for enforcing unique VM hostnames which determines the resource boundary within which VM hostnames must be unique. Possible values: global, domain, subdomain, account, network.",
            true, ConfigKey.Scope.Global, null, "VM distinct hostname scope", null, null, null, ConfigKey.Kind.Select,
            "global,domain,subdomain,account,network");

    ConfigKey<Boolean> EnableAdditionalVmConfig = new ConfigKey<>(
            "Advanced",
            Boolean.class,
            "enable.additional.vm.configuration",
            "false",
            "allow additional arbitrary configuration to vm",
            true,
            ConfigKey.Scope.Account);


    ConfigKey<Boolean> VmwareAdditionalDetailsFromOvaEnabled = new ConfigKey<Boolean>("Advanced", Boolean.class,
            "vmware.additional.details.from.ova.enabled", "false",
            "If true, allow users to add additional VM settings if VM instance settings are read from OVA.", true, ConfigKey.Scope.Zone);

    ConfigKey<String> VmwareAllowedAdditionalDetailsFromOva = new ConfigKey<>(String.class,
            "vmware.allowed.additional.details.from.ova", "Advanced", "",
            "Comma separated list of allowed additional VM settings if VM instance settings are read from OVA.",
            true, ConfigKey.Scope.Zone, null, null, null, null, null, ConfigKey.Kind.CSV, null);

    ConfigKey<Boolean> AllowDifferentHostTagsOfferingsForVmScale = new ConfigKey<>("Advanced", Boolean.class, "allow.different.host.tags.offerings.for.vm.scale", "false",
            "Enables/Disable allowing to change a VM offering to offerings with different host tags", true);

    ConfigKey<Boolean> AutoMigrateVmOnLiveScaleInsufficientCapacity = new ConfigKey<>("Advanced", Boolean.class, "auto.migrate.vm.on.live.scale.insufficient.capacity",
            "true", "Defines whether a VM should be automatically migrated to a suitable host when the current host " +
                    "lacks sufficient compute capacity to live scale the instance. Defaults to true.", true, ConfigKey.Scope.Cluster);

    ConfigKey<Boolean> EnforceResourceLimitOnValidationVm = new ConfigKey<Boolean>(
            "Advanced", Boolean.class, "enforce.resource.limit.on.backup.validation.vm", "false", "If set to true, validation VMs will be accounted in the resource limit of the " +
            "account/domain.", true, ConfigKey.Scope.Account);

    ConfigKey<Integer> VmPoolVcpuStep = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class, "vm.pool.vcpu.step", "2",
            "The vCPU count of an Instance without a vGPU profile must be a multiple of this value when it is deployed or scaled. " +
                    "Such an Instance runs on ceil(vCPUs/2) dedicated host cores, so with an odd count the sibling thread of its last core stays idle. " +
                    "Values below 1 count as 1.", true, ConfigKey.Scope.Domain);

    /**
     * @return the vCPU step for Instances without a vGPU profile owned in the domain
     */
    static int poolVcpuStep(Long domainId) {
        Integer step = VmPoolVcpuStep.valueIn(domainId);
        return step == null || step < 1 ? 1 : step;
    }

    ConfigKey<Long> VmRootDiskSizeDefault = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Long.class, "vm.root.disk.size.default", "20",
            "The ROOT disk size, in GiB, of an Instance created from a Template when no root disk size is given. A larger Template size, rounded up " +
                    "to whole GiB, takes its place, and so does vm.root.disk.size.min when it is larger. ISO-based ROOT disks and data disks are not affected.",
            true, ConfigKey.Scope.Domain);

    ConfigKey<Long> VmRootDiskSizeMin = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Long.class, "vm.root.disk.size.min", "10",
            "The smallest root disk size, in GiB, that may be given for an Instance created or reinstalled from a Template. A larger Template size, " +
                    "rounded up to whole GiB, takes its place. ISO-based ROOT disks and data disks are not affected.",
            true, ConfigKey.Scope.Domain);

    /**
     * @return the smallest root disk size in GiB the domain admits for an Instance created from a Template, before the Template's own size
     */
    static long rootDiskSizeMinGiB(Long domainId) {
        Long min = VmRootDiskSizeMin.valueIn(domainId);
        return min == null || min < 0 ? 0 : min;
    }

    /**
     * @return the root disk size in GiB the domain gives an Instance created from a Template when none is given, before the Template's
     *         own size; never below {@link #rootDiskSizeMinGiB(Long)}, so the default is always an admissible size
     */
    static long rootDiskSizeDefaultGiB(Long domainId) {
        Long size = VmRootDiskSizeDefault.valueIn(domainId);
        return Math.max(size == null ? 0 : size, rootDiskSizeMinGiB(domainId));
    }

    /**
     * @return whether destroying an Instance owned in the domain keeps its ROOT volume when the caller does not say, the inverse of
     *         {@link #DestroyRootVolumeOnVmDestruction}
     */
    static boolean keepRootVolumeByDefault(Long domainId) {
        return !Boolean.TRUE.equals(DestroyRootVolumeOnVmDestruction.valueIn(domainId));
    }

    /**
     * Live migration is not implemented: an Instance that may be running is never migrated.
     */
    static void refuseLiveMigration(VirtualMachine vm) {
        if (vm.getState() == VirtualMachine.State.Running || vm.getState() == VirtualMachine.State.Migrating) {
            throw new InvalidParameterValueException("live migration is disabled");
        }
    }

    static final int MAX_USER_DATA_LENGTH_BYTES = 2048;

    public  static  final String CKS_NODE = "cksnode";
    public  static  final String SHAREDFSVM = "sharedfsvm";
    String VALIDATION_VM = "validationvm";

    /**
     * @param hostId get all of the virtual machines that belong to one host.
     * @return collection of VirtualMachine.
     */
    List<? extends UserVm> getVirtualMachines(long hostId);

    /**
     * @param vmId id of the virtual machine.
     * @return VirtualMachine
     */
    UserVmVO getVirtualMachine(long vmId);

    /**
     * Stops the virtual machine
     * @param userId the id of the user performing the action
     * @param vmId
     * @return true if stopped; false if problems.
     */
    boolean stopVirtualMachine(long userId, long vmId);

    HashMap<String, VolumeStatsEntry> getVolumeStatistics(long clusterId, String poolUuid, StoragePoolType poolType, int timeout);

    boolean deleteVmGroup(long groupId);

    boolean addInstanceToGroup(long userVmId, String group);

    InstanceGroupVO getGroupForVm(long vmId);

    void removeInstanceFromInstanceGroup(long vmId);

    String finalizeUserData(String userData, Long userDataId, VirtualMachineTemplate template);

    void validateExtraConfig(long accountId, HypervisorType hypervisorType, String extraConfig);

    boolean isVMUsingLocalStorage(VMInstanceVO vm);

    boolean expunge(UserVmVO vm);

    Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long hostId, Map<VirtualMachineProfile.Param, Object> additionalParams, String deploymentPlannerToUse, boolean quickRestore)
        throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

    Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            Map<VirtualMachineProfile.Param, Object> additionalParams, String deploymentPlannerToUse, boolean quickRestore)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

    Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            Map<VirtualMachineProfile.Param, Object> additionalParams, String deploymentPlannerToUse, boolean isExplicitHost, boolean quickRestore)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

    boolean upgradeVirtualMachine(Long id, Long serviceOfferingId, Map<String, String> customParameters) throws ResourceUnavailableException,
        ConcurrentOperationException, ManagementServerException,
        VirtualMachineMigrationException;

    boolean setupVmForPvlan(boolean add, Long hostId, NicProfile nic);

    UserVm updateVirtualMachine(long id, String displayName, String group, Boolean ha,
                                Boolean isDisplayVmEnabled, Boolean deleteProtection,
                                Long osTypeId, String userData, Long userDataId,
                                String userDataDetails, Boolean isDynamicallyScalable,
                                HTTPMethod httpMethod, String customId, String hostName,
                                String instanceName, List<Long> securityGroupIdList,
                                Map<String, Map<Integer, String>> extraDhcpOptionsMap
    ) throws ResourceUnavailableException, InsufficientCapacityException;

    //the validateCustomParameters, save and remove CustomOfferingDetils functions can be removed from the interface once we can
    //find a common place for all the scaling and upgrading code of both user and systemvms.
    void validateCustomParameters(ServiceOfferingVO serviceOffering, Map<String, String> customParameters);

    void generateUsageEvent(VirtualMachine vm, boolean isDisplay, String eventType);

    static Set<String> getStrictHostTags() {
        String strictHostTags = StrictHostTags.value();
        Set<String> strictHostTagsSet = new HashSet<>();
        if (StringUtils.isNotEmpty(strictHostTags)) {
            strictHostTagsSet.addAll(List.of(strictHostTags.split(",")));
        }
        if (EnforceStrictResourceLimitHostTagCheck.value() && StringUtils.isNotEmpty(ResourceLimitHostTags.value())) {
            strictHostTagsSet.addAll(List.of(ResourceLimitHostTags.value().split(",")));
        }
        return strictHostTagsSet;
    }

    void persistDeviceBusInfo(UserVmVO paramUserVmVO, String paramString);

    boolean checkIfDynamicScalingCanBeEnabled(VirtualMachine vm, ServiceOffering offering, VirtualMachineTemplate template, Long zoneId);

    Boolean getDestroyRootVolumeOnVmDestruction(Long domainId);

    /**
     * @return true if the VM is part of a CKS cluster, false otherwise.
     */
    boolean isVMPartOfAnyCKSCluster(VMInstanceVO vm);

    UserVm allocateVMForValidation(long backupId, HypervisorType hypervisor) throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException;

    /**
     * @throws com.cloud.exception.InvalidParameterValueException when a user Instance without a vGPU profile would get a vCPU
     *         count that is not a multiple of the domain's {@link #VmPoolVcpuStep}
     */
    void validatePoolVcpuStep(VirtualMachine.Type vmType, ServiceOffering offering, Integer vcpus, long domainId);

    /**
     * @param boundTemplateRoot whether the root disk default and minimum apply: the root volume is created from the template with a size a
     *        caller chooses, rather than being an existing volume, made from a snapshot, or re-created at a recorded size
     * @param domainId the owner's domain, whose {@link #VmRootDiskSizeDefault} and {@link #VmRootDiskSizeMin} bound a root created from a
     *        disk image template
     * @return the size in bytes of the root volume of a new Instance; sets the root disk size in the custom parameters when the root disk
     *         offering fixes it or the default applies
     */
    long configureCustomRootDiskSize(Map<String, String> customParameters, VMTemplateVO template, HypervisorType hypervisorType, DiskOfferingVO rootDiskOffering,
            boolean boundTemplateRoot, long domainId);

    /**
     * A stopped Instance's change of offering, checked and reserved, still to be applied.
     */
    interface PreparedUpgrade {
        /**
         * Changes the Instance's offering, the disk offering of its root where the new offering gives one, and the resource counts that
         * follow. Only the database is touched and nothing is reserved, so it may run inside the caller's transaction.
         */
        void apply();

        /**
         * Tells the storage of the root's new disk offering; called after the transaction that applied the change has committed.
         */
        void complete();
    }

    /**
     * Checks a stopped Instance's change to the offering, reserves its resources into the given reservations, and makes the change of
     * the root volume that a change to an offering with a sized root disk offering makes. The caller applies the returned change,
     * completes it after its transaction commits, and releases the reservations whatever happens.
     */
    PreparedUpgrade prepareStoppedVirtualMachineUpgrade(long vmId, long serviceOfferingId, Map<String, String> customParameters, List<Reserver> reservations)
            throws ResourceAllocationException;
}
