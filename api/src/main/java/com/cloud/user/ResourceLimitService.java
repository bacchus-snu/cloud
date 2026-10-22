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
package com.cloud.user;

import java.util.List;

import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.domain.Domain;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import org.apache.cloudstack.resourcelimit.Reserver;

public interface ResourceLimitService {

    static final ConfigKey<Long> MaxAccountSecondaryStorage = new ConfigKey<>("Account Defaults", Long.class, "max.account.secondary.storage", "400",
            "The default maximum secondary storage space (in GiB) that can be used for an Account", false);
    static final ConfigKey<Long> MaxProjectSecondaryStorage = new ConfigKey<>("Project Defaults", Long.class, "max.project.secondary.storage", "400",
            "The default maximum secondary storage space (in GiB) that can be used for a project", false);
    static final ConfigKey<Long> ResourceCountCheckInterval = new ConfigKey<>("Advanced", Long.class, "resourcecount.check.interval", "300",
            "Time (in seconds) to wait before running resource recalculation and fixing tasks like stale resource reservation cleanup" +
                    ". Default is 300 seconds, Setting this to 0 disables execution of the task", true);
    static final ConfigKey<Long> ResourceReservationCleanupDelay = new ConfigKey<>("Advanced", Long.class, "resource.reservation.cleanup.delay", "3600",
            "Time (in seconds) after which a resource reservation gets deleted. Default is 3600 seconds, Setting this to 0 disables execution of the task", true);
    static final ConfigKey<String> ResourceLimitHostTags = new ConfigKey<>("Advanced", String.class, "resource.limit.host.tags", "",
            "A comma-separated list of tags for host resource limits", true);
    static final ConfigKey<String> ResourceLimitStorageTags = new ConfigKey<>("Advanced", String.class, "resource.limit.storage.tags", "",
            "A comma-separated list of tags for storage resource limits", true);
    static final ConfigKey<Boolean> ResourceLimitStorageCombined = new ConfigKey<>("Advanced", Boolean.class, "resource.limit.storage.combined", "true",
            "Whether primary and secondary storage share one quota. When true, the checks of an account's or domain's primary_storage and " +
                    "secondary_storage limits compare the sum of both usages (resource counts and reservations) with its primary_storage limit, " +
                    "and its secondary_storage limit is not consulted. When false, the two limits are checked separately. " +
                    "Storage limits with a tag are always checked separately.",
            true, List.of(ConfigKey.Scope.Account, ConfigKey.Scope.Domain));
    static final ConfigKey<Long> DefaultMaxAccountProjects = new ConfigKey<>("Account Defaults",Long.class,"max.account.projects","10",
                "The default maximum number of projects that can be created for an account",false);
    static final ConfigKey<Long> DefaultMaxDomainProjects = new ConfigKey<>("Domain Defaults",Long.class,"max.domain.projects","50",
                        "The default maximum number of projects that can be created for a domain",false);
    static final ConfigKey<Long> DefaultMaxAccountGpus = new ConfigKey<>("Account Defaults",Long.class,"max.account.gpus","20",
            "The default maximum number of GPU devices that can be used for an account", false);
    static final ConfigKey<Long> DefaultMaxDomainGpus = new ConfigKey<>("Domain Defaults",Long.class,"max.domain.gpus","20",
            "The default maximum number of GPU devices that can be used for a domain", false);
    static final ConfigKey<Long> DefaultMaxProjectGpus = new ConfigKey<>("Project Defaults",Long.class,"max.project.gpus","20",
            "The default maximum number of GPU devices that can be used for a project", false);
    static final ConfigKey<Boolean> ResourceRequestEnforceQuota = new ConfigKey<>("Advanced", Boolean.class, "resource.request.enforce.quota", "true",
            "Whether an Instance start and an Instance lease renewal are refused while the owner's cpu, memory or gpu count exceeds the owner's limit " +
                    "for the Instance's resource limit host tags (the untagged limits when it has none). Running Instances, and the restart within a forced reboot or a reinstall of a running Instance, are not affected.", true);

    static final List<ResourceType> HostTagsSupportingTypes = List.of(ResourceType.user_vm, ResourceType.cpu, ResourceType.memory, ResourceType.gpu);
    static final List<ResourceType> StorageTagsSupportingTypes = List.of(ResourceType.volume, ResourceType.primary_storage);

    /**
     * Updates an existing resource limit with the specified details. If a limit doesn't exist, will create one.
     *
     * @param accountId
     *            TODO
     * @param domainId
     *            TODO
     * @param resourceType
     *            TODO
     * @param max
     *            TODO
     * @param tag
     *            tag for the resource type
     *
     * @return the updated/created resource limit
     */
    ResourceLimit updateResourceLimit(Long accountId, Long domainId, Integer resourceType, Long max, String tag);

    /**
     * Updates an existing resource count details for the account/domain
     *
     * @param accountId
     *            Id of the account for which resource recalculation to be done
     * @param domainId
     *            Id of the domain for which resource recalculation to be doneDO
     * @param typeId
     *            type of the resource for which recalculation to be done
     * @param tag
     *            tag for the resource type for which recalculation to be done
     * @return the updated/created resource counts
     */
    List<? extends ResourceCount> recalculateResourceCount(Long accountId, Long domainId, Integer typeId, String tag);
    List<? extends ResourceCount> recalculateResourceCount(Long accountId, Long domainId, Integer typeId);

    /**
     * Search for resource limits for the given id and/or account and/or type and/or domain.
     *
     * @param id
     *            TODO
     * @param accountId
     *            TODO
     * @param domainId
     *            TODO
     * @param resourceType
     *            TODO
     * @return a list of limits that match the criteria
     */
    public List<? extends ResourceLimit> searchForLimits(Long id, Long accountId, Long domainId, ResourceType resourceType, String tag, Long startIndex, Long pageSizeVal);

    /**
     * Finds the resource limit for a specified account and type. If the account has an infinite limit, will check
     * the account's parent domain, and if that limit is also infinite, will return the ROOT domain's limit.
     *
     * @param account
     * @param type
     * @param tag
     * @return resource limit
     */
    public long findCorrectResourceLimitForAccount(Account account, ResourceType type, String tag);

    /**
     * This call should be used when we have already queried resource limit for an account. This is to handle
     * some corner cases where queried limit may be null.
     * @param accountId
     * @param limit
     * @param type
     * @return
     */
    public long findCorrectResourceLimitForAccount(long accountId, Long limit, ResourceType type);

    /**
     * Finds the resource limit for a specified domain and type. If the domain has an infinite limit, will check
     * up the domain hierarchy
     *
     * @param domain
     * @param type
     * @param tag
     * @return resource limit
     */
    public long findCorrectResourceLimitForDomain(Domain domain, ResourceType type, String tag);

    /**
     * Finds the default resource limit for a specified type.
     *
     * @param resourceType
     * @return resource limit
     */
    public long findDefaultResourceLimitForDomain(ResourceType resourceType);

    /**
     * Finds the resource limit for a specified account, domain and type.
     * For a type under the account's combined storage quota ({@link #ResourceLimitStorageCombined}) it is instead the
     * bound that the account's usage of that type may reach: {@link #getRemainingStorageQuota(Account)} plus the current
     * usage of that type, or {@link com.cloud.configuration.Resource#RESOURCE_UNLIMITED}.
     *
     * @param domain
     * @param type
     * @param tag
     * @return resource limit
     */
    public long findCorrectResourceLimitForAccountAndDomain(Account account, Domain domain, ResourceType type, String tag);

    /**
     * @return true for the untagged primary_storage and secondary_storage types, which share one quota where
     * {@link #ResourceLimitStorageCombined} is set
     */
    static boolean isStorageQuotaType(ResourceType type, String tag) {
        return (tag == null || tag.isEmpty()) && (type == ResourceType.primary_storage || type == ResourceType.secondary_storage);
    }

    /**
     * Where {@link #ResourceLimitStorageCombined} is set for an owner, its primary and secondary storage share one quota,
     * its untagged primary_storage limit, and the usage is the sum of both resource counts and both reservations; where it
     * is not set, the quota and usage are primary storage alone.
     *
     * @param account the owner; for a project account, the project's domain chain applies
     * @return the bytes that the account can still add: the smallest of the account's remaining quota and that of every
     *         limited domain up its domain chain, never below 0; or {@link com.cloud.configuration.Resource#RESOURCE_UNLIMITED}
     *         when neither the account nor any of those domains limits storage
     */
    long getRemainingStorageQuota(Account account);

    /**
     * @return whether {@link #ResourceLimitStorageCombined} applies to the account, evaluated at the account's scope
     */
    boolean isStorageQuotaCombined(Account account);

    /**
     * The one check behind every refusal to start, or renew the lease of, an Instance whose owner is over quota. Limits only
     * bound new allocations, and an Instance that is counted while stopped is not checked again when it starts, so a start
     * is refused while the owner's count already exceeds a limit, for example after the grant behind it expired.
     * It does nothing while {@link #ResourceRequestEnforceQuota} is false.
     *
     * @param owner the Instance's owner; its account limits are checked, not those of its domains
     * @param offering the Instance's service offering, whose resource limit host tags select the limits checked
     * @param template the Instance's Template, whose tag may also select a limit; may be null
     * @throws ResourceAllocationException "quota expired or insufficient" when the cpu, memory or gpu count of the owner exceeds
     *         its limit for one of those tags, or its untagged limit when the offering and Template have no such tag
     */
    void checkWithinQuota(Account owner, ServiceOffering offering, VirtualMachineTemplate template) throws ResourceAllocationException;

    /**
     * Increments the resource count
     *
     * @param accountId
     * @param type
     * @param delta
     */
    public void incrementResourceCount(long accountId, ResourceType type, Long... delta);
    public void incrementResourceCountWithTag(long accountId, ResourceType type, String tag, Long... delta);

    /**
     * Decrements the resource count
     *
     * @param accountId
     * @param type
     * @param delta
     */
    public void decrementResourceCount(long accountId, ResourceType type, Long... delta);
    public void decrementResourceCountWithTag(long accountId, ResourceType type, String tag, Long... delta);

    /**
     * Checks if a limit has been exceeded for an account
     *
     * @param account
     * @param type
     * @param count
     *            the number of resources being allocated, count will be added to current allocation and compared
     *            against maximum allowed allocation
     * @throws ResourceAllocationException
     */
    public void checkResourceLimit(Account account, ResourceCount.ResourceType type, long... count) throws ResourceAllocationException;
    public void checkResourceLimitWithTag(Account account, ResourceCount.ResourceType type, String tag, long... count) throws ResourceAllocationException;
    public void checkResourceLimitWithTag(Account account, Long domainId, boolean considerSystemAccount, ResourceCount.ResourceType type, String tag, long... count) throws ResourceAllocationException;

    /**
     * Gets the count of resources for a resource type and account
     *
     * @param account
     * @param type
     * @param tag
     * @return count of resources
     */
    public long getResourceCount(Account account, ResourceType type, String tag);

    /**
     * Checks if a limit has been exceeded for an account if displayResource flag is on
     *
     * @param account
     * @param type
     * @param displayResource
     * @param count
     *            the number of resources being allocated, count will be added to current allocation and compared
     *            against maximum allowed allocation
     * @throws ResourceAllocationException
     */
    void checkResourceLimit(Account account, ResourceType type, Boolean displayResource, long... count) throws ResourceAllocationException;

    /**
     * Increments the resource count  if displayResource flag is on
     *
     * @param accountId
     * @param type
     * @param displayResource
     * @param delta
     */
    void incrementResourceCount(long accountId, ResourceType type, Boolean displayResource, Long... delta);

    /**
     * Increments/Decrements the resource count  depending on the displayResource flag is turned on or off respectively
     *
     * @param accountId
     * @param type
     * @param displayResource
     * @param delta
     */
    void changeResourceCount(long accountId, ResourceType type, Boolean displayResource, Long... delta);

    /**
     * Decrements the resource count  if displayResource flag is on
     *
     * @param accountId
     * @param type
     * @param displayResource
     * @param delta
     */
    void decrementResourceCount(long accountId, ResourceType type, Boolean displayResource, Long... delta);

    List<String> getResourceLimitHostTags();
    List<String> getResourceLimitHostTags(ServiceOffering serviceOffering, VirtualMachineTemplate template);
    List<String> getResourceLimitStorageTags();
    List<String> getResourceLimitStorageTags(DiskOffering diskOffering);
    void updateTaggedResourceLimitsAndCountsForAccounts(List<AccountResponse> responses, String tag);
    void updateTaggedResourceLimitsAndCountsForDomains(List<DomainResponse> responses, String tag);
    void checkVolumeResourceLimit(Account owner, Boolean display, Long size, DiskOffering diskOffering, List<Reserver> reservations) throws ResourceAllocationException;
    List<String> getResourceLimitStorageTagsForResourceCountOperation(Boolean display, DiskOffering diskOffering, Boolean enforceResourceLimitOnDisplayFalse);
    void checkVolumeResourceLimitForDiskOfferingChange(Account owner, Boolean display, Long currentSize, Long newSize,
            DiskOffering currentOffering, DiskOffering newOffering, List<Reserver> reservations) throws ResourceAllocationException;

    void checkPrimaryStorageResourceLimit(Account owner, Boolean display, Long size, DiskOffering diskOffering, List<Reserver> reservations) throws ResourceAllocationException;

    void incrementVolumeResourceCount(long accountId, Boolean display, Long size, DiskOffering diskOffering);
    void decrementVolumeResourceCount(long accountId, Boolean display, Long size, DiskOffering diskOffering, Boolean countDisplayFalseInResourceCount);

    void updateVmResourceCountForTemplateChange(long accountId, Boolean display, ServiceOffering offering, VirtualMachineTemplate currentTemplate, VirtualMachineTemplate newTemplate);

    void updateVmResourceCountForServiceOfferingChange(long accountId, Boolean display, Long currentCpu, Long newCpu, Long currentMemory,
            Long newMemory,
            ServiceOffering currentOffering, ServiceOffering newOffering,
            VirtualMachineTemplate template);

    void updateVolumeResourceCountForDiskOfferingChange(long accountId, Boolean display, Long currentSize, Long newSize,
            DiskOffering currentDiskOffering, DiskOffering newDiskOffering);

    void incrementVolumePrimaryStorageResourceCount(long accountId, Boolean display, Long size, DiskOffering diskOffering);
    void decrementVolumePrimaryStorageResourceCount(long accountId, Boolean display, Long size, DiskOffering diskOffering);
    void checkVmResourceLimit(Account owner, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, List<Reserver> reservations) throws ResourceAllocationException;
    void incrementVmResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Boolean countDisplayFalseInResourceLimit);
    void decrementVmResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Boolean countDisplayFalseInResourceCount);

    void checkVmResourceLimitsForServiceOfferingChange(Account owner, Boolean display, Long currentCpu, Long newCpu,
            Long currentMemory, Long newMemory, ServiceOffering currentOffering, ServiceOffering newOffering, VirtualMachineTemplate template, List<Reserver> reservations) throws ResourceAllocationException;

    void checkVmResourceLimitsForTemplateChange(Account owner, Boolean display, ServiceOffering offering,
            VirtualMachineTemplate currentTemplate, VirtualMachineTemplate newTemplate, List<Reserver> reservations) throws ResourceAllocationException;

    void incrementVmCpuResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long cpu);
    void decrementVmCpuResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long cpu);
    void incrementVmMemoryResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long memory);
    void decrementVmMemoryResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long memory);

    void incrementVmGpuResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long gpu);
    void decrementVmGpuResourceCount(long accountId, Boolean display, ServiceOffering serviceOffering, VirtualMachineTemplate template, Long gpu);

    long recalculateDomainResourceCount(final long domainId, final ResourceType type, String tag);
}
