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
package com.cloud.metadata;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.server.ResourceManagerUtil;
import org.apache.cloudstack.api.ResourceDetail;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ScopedConfigStorage;
import org.apache.cloudstack.resourcedetail.ResourceDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.AutoScaleVmGroupDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.AutoScaleVmProfileDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.FirewallRuleDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.NetworkACLItemDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.NetworkACLListDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.RemoteAccessVpnDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.Site2SiteCustomerGatewayDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.Site2SiteVpnConnectionDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.Site2SiteVpnGatewayDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.SnapshotPolicyDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.UserIpAddressDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.VpcDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.VpcGatewayDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.LBStickinessPolicyDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.LBHealthCheckPolicyDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.GuestOsDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.vm.lease.VMLeaseManager;

import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.rules.HealthCheckPolicy;
import com.cloud.network.rules.StickinessPolicy;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.server.ResourceMetaDataService;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.server.TaggedResourceService;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.dao.VMTemplateDetailsDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.tags.ResourceManagerUtilImpl;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.Nic;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.NicDetailsDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class ResourceMetaDataManagerImpl extends ManagerBase implements ResourceMetaDataService, ResourceMetaDataManager {
    @Inject
    AccountManager accountManager;
    @Inject
    EntityManager entityManager;
    @Inject
    ConfigurationManager configurationManager;
    @Inject
    VolumeDetailsDao _volumeDetailDao;
    @Inject
    NicDetailsDao _nicDetailDao;
    @Inject
    VMInstanceDetailsDao _userVmDetailDao;
    @Inject
    DataCenterDetailsDao _dcDetailsDao;
    @Inject
    NetworkDetailsDao _networkDetailsDao;
    @Inject
    TaggedResourceService _taggedResourceMgr;
    @Inject
    VMTemplateDetailsDao _templateDetailsDao;
    @Inject
    ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    StoragePoolDetailsDao _storageDetailsDao;
    @Inject
    FirewallRuleDetailsDao _firewallRuleDetailsDao;
    @Inject
    UserIpAddressDetailsDao _userIpAddressDetailsDao;
    @Inject
    RemoteAccessVpnDetailsDao _vpnDetailsDao;
    @Inject
    VpcDetailsDao _vpcDetailsDao;
    @Inject
    VpcGatewayDetailsDao _vpcGatewayDetailsDao;
    @Inject
    NetworkACLListDetailsDao _networkACLListDetailsDao;
    @Inject
    NetworkACLItemDetailsDao _networkACLDetailsDao;
    @Inject
    Site2SiteVpnGatewayDetailsDao _vpnGatewayDetailsDao;
    @Inject
    Site2SiteCustomerGatewayDetailsDao _customerGatewayDetailsDao;
    @Inject
    Site2SiteVpnConnectionDetailsDao _vpnConnectionDetailsDao;
    @Inject
    DiskOfferingDetailsDao _diskOfferingDetailsDao;
    @Inject
    UserDetailsDao _userDetailsDao;
    @Inject
    AutoScaleVmProfileDetailsDao _autoScaleVmProfileDetailsDao;
    @Inject
    AutoScaleVmGroupDetailsDao _autoScaleVmGroupDetailsDao;
    @Inject
    LBStickinessPolicyDetailsDao _stickinessPolicyDetailsDao;
    @Inject
    LBHealthCheckPolicyDetailsDao _healthcheckPolicyDetailsDao;
    @Inject
    SnapshotPolicyDetailsDao _snapshotPolicyDetailsDao;
    @Inject
    GuestOsDetailsDao _guestOsDetailsDao;
    @Inject
    NetworkOfferingDetailsDao _networkOfferingDetailsDao;
    @Inject
    ResourceManagerUtil resourceManagerUtil;

    private static Map<ResourceObjectType, ResourceDetailsDao<? extends ResourceDetail>> s_daoMap = new HashMap<ResourceObjectType, ResourceDetailsDao<? extends ResourceDetail>>();

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        s_daoMap.put(ResourceObjectType.UserVm, _userVmDetailDao);
        s_daoMap.put(ResourceObjectType.Volume, _volumeDetailDao);
        s_daoMap.put(ResourceObjectType.Template, _templateDetailsDao);
        s_daoMap.put(ResourceObjectType.Network, _networkDetailsDao);
        s_daoMap.put(ResourceObjectType.Nic, _nicDetailDao);
        s_daoMap.put(ResourceObjectType.ServiceOffering, _serviceOfferingDetailsDao);
        s_daoMap.put(ResourceObjectType.Zone, _dcDetailsDao);
        s_daoMap.put(ResourceObjectType.Storage, _storageDetailsDao);
        s_daoMap.put(ResourceObjectType.FirewallRule, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.PublicIpAddress, _userIpAddressDetailsDao);
        s_daoMap.put(ResourceObjectType.PortForwardingRule, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.LoadBalancer, _firewallRuleDetailsDao);
        s_daoMap.put(ResourceObjectType.RemoteAccessVpn, _vpnDetailsDao);
        s_daoMap.put(ResourceObjectType.Vpc, _vpcDetailsDao);
        s_daoMap.put(ResourceObjectType.PrivateGateway, _vpcGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.NetworkACLList, _networkACLListDetailsDao);
        s_daoMap.put(ResourceObjectType.NetworkACL, _networkACLDetailsDao);
        s_daoMap.put(ResourceObjectType.VpnGateway, _vpnGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.CustomerGateway, _customerGatewayDetailsDao);
        s_daoMap.put(ResourceObjectType.VpnConnection, _vpnConnectionDetailsDao);
        s_daoMap.put(ResourceObjectType.DiskOffering, _diskOfferingDetailsDao);
        s_daoMap.put(ResourceObjectType.User, _userDetailsDao);
        s_daoMap.put(ResourceObjectType.AutoScaleVmProfile, _autoScaleVmProfileDetailsDao);
        s_daoMap.put(ResourceObjectType.AutoScaleVmGroup, _autoScaleVmGroupDetailsDao);
        s_daoMap.put(ResourceObjectType.LBStickinessPolicy, _stickinessPolicyDetailsDao);
        s_daoMap.put(ResourceObjectType.LBHealthCheckPolicy, _healthcheckPolicyDetailsDao);
        s_daoMap.put(ResourceObjectType.SnapshotPolicy, _snapshotPolicyDetailsDao);
        s_daoMap.put(ResourceObjectType.GuestOs, _guestOsDetailsDao);
        s_daoMap.put(ResourceObjectType.NetworkOffering, _networkOfferingDetailsDao);
        return true;
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_RESOURCE_DETAILS_CREATE, eventDescription = "creating resource meta data")
    public boolean addResourceMetaData(final String resourceId, final ResourceObjectType resourceType, final Map<String, String> details, final boolean forDisplay) {
        refuseLifecycleState(resourceType, details.keySet());
        refuseInstanceNamePrefix(resourceType, details.keySet());
        long id = resourceManagerUtil.getResourceId(resourceId, resourceType);
        checkCallerMayChangeDetailsOf(resourceType, id);
        checkDetailNames(resourceType, details::keySet);
        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                for (String key : details.keySet()) {
                    String value = details.get(key);

                    if (value == null || value.isEmpty()) {
                        throw new InvalidParameterValueException("Value for the key " + key + " is either null or empty");
                    }

                    DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
                    newDetailDaoHelper.addDetail(id, key, value, forDisplay);
                }

                return true;
            }
        });
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_RESOURCE_DETAILS_DELETE, eventDescription = "deleting resource meta data")
    public boolean deleteResourceMetaData(String resourceId, ResourceObjectType resourceType, String key) {
        long id = resourceManagerUtil.getResourceId(resourceId, resourceType);
        Collection<String> names = key != null ? Collections.singletonList(key) : namesRemovedWithEveryDetail(resourceType, id);
        refuseLifecycleState(resourceType, names);
        refuseInstanceNamePrefix(resourceType, names);
        checkCallerMayChangeDetailsOf(resourceType, id);

        DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        checkDetailNames(resourceType, () -> key != null ? Collections.singletonList(key) : s_daoMap.get(resourceType).listDetailsKeyPairs(id).keySet());
        if (key != null) {
            newDetailDaoHelper.removeDetail(id, key);
        } else {
            newDetailDaoHelper.removeDetails(id);
        }

        return true;
    }

    /**
     * The details of a resource whose details table is a scoped setting store are also where that scope of a setting is
     * read from.
     */
    private void checkDetailNames(ResourceObjectType resourceType, Supplier<Collection<String>> names) {
        if (s_daoMap.get(resourceType) instanceof ScopedConfigStorage) {
            configurationManager.checkCallerMayChangeAccessControlSettings(names.get());
        }
    }

    private static void refuseLifecycleState(ResourceObjectType resourceType, Collection<String> names) {
        for (String name : names) {
            if (resourceType == ResourceObjectType.UserVm && VmDetailConstants.isLifecycleState(name)) {
                throw new InvalidParameterValueException(String.format("The detail %s of an Instance is kept by the system and cannot be set or removed", name));
            }
            if (resourceType == ResourceObjectType.ServiceOffering && VMLeaseManager.isOfferingLeaseDetail(name)) {
                throw new InvalidParameterValueException(String.format("The detail %s of a service offering is set with the offering's own parameters", name));
            }
        }
    }

    /**
     * @return the names, among those whose details callers may not remove, of the details that removing every detail of the resource
     *         removes; an Instance's whole removal leaves its lifecycle state
     */
    private Collection<String> namesRemovedWithEveryDetail(ResourceObjectType resourceType, long id) {
        if (resourceType != ResourceObjectType.UserVm && resourceType != ResourceObjectType.ServiceOffering) {
            return Collections.emptyList();
        }
        Set<String> names = new HashSet<>(s_daoMap.get(resourceType).listDetailsKeyPairs(id).keySet());
        if (resourceType == ResourceObjectType.UserVm) {
            names.removeIf(VmDetailConstants::isLifecycleState);
        }
        return names;
    }

    private boolean callerIsRootAdmin() {
        return accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId());
    }

    /**
     * A caller other than a root administrator changes the details only of a resource it has access to; the details of a resource
     * without an owner are changed by root administrators alone.
     */
    private void checkCallerMayChangeDetailsOf(ResourceObjectType resourceType, long id) {
        if (callerIsRootAdmin()) {
            return;
        }
        ControlledEntity owned = ownedResource(resourceType, id);
        if (owned == null) {
            throw new PermissionDeniedException(String.format("Only a root administrator changes the details of a resource of type %s", resourceType));
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), SecurityChecker.AccessType.OperateEntry, false, owned);
    }

    /**
     * @return what an account owns of the resource: the resource itself, the Instance of a NIC, the load balancer of a policy, or the
     *         VPC of an ACL or of its rule; null when no account owns it
     */
    private ControlledEntity ownedResource(ResourceObjectType resourceType, long id) {
        Class<?> type = ResourceManagerUtilImpl.s_typeMap.get(resourceType);
        Object resource = type == null ? null : entityManager.findById(type, id);
        if (resource instanceof Nic) {
            resource = entityManager.findById(VMInstanceVO.class, ((Nic) resource).getInstanceId());
        } else if (resource instanceof StickinessPolicy) {
            resource = entityManager.findById(LoadBalancerVO.class, ((StickinessPolicy) resource).getLoadBalancerId());
        } else if (resource instanceof HealthCheckPolicy) {
            resource = entityManager.findById(LoadBalancerVO.class, ((HealthCheckPolicy) resource).getLoadBalancerId());
        } else if (resource instanceof NetworkACLItem) {
            resource = entityManager.findById(NetworkACLVO.class, ((NetworkACLItem) resource).getAclId());
        }
        if (resource instanceof NetworkACL) {
            Long vpcId = ((NetworkACL) resource).getVpcId();
            resource = vpcId == null || vpcId == 0 ? null : entityManager.findById(VpcVO.class, vpcId);
        }
        return resource instanceof ControlledEntity ? (ControlledEntity) resource : null;
    }

    /**
     * The prefix of an Instance's names is fixed at the Instance's creation; only a root administrator sets or removes it.
     */
    private void refuseInstanceNamePrefix(ResourceObjectType resourceType, Collection<String> names) {
        if (resourceType == ResourceObjectType.UserVm && names.stream().anyMatch(VmDetailConstants::isSshNamePrefix) && !callerIsRootAdmin()) {
            throw new InvalidParameterValueException(String.format("You're not allowed to set or remove the restricted setting: %s",
                    VmDetailConstants.SSH_NAME_PREFIX));
        }
    }

    private class DetailDaoHelper {
        private ResourceObjectType resourceType;
        private ResourceDetailsDao<? super ResourceDetail> dao;

        private DetailDaoHelper(ResourceObjectType resourceType) {
            if (!resourceType.resourceMetadataSupport()) {
                throw new UnsupportedOperationException("ResourceType " + resourceType + " doesn't support metadata");
            }
            this.resourceType = resourceType;
            ResourceDetailsDao<?> dao = s_daoMap.get(resourceType);
            if (dao == null) {
                throw new UnsupportedOperationException("ResourceType " + resourceType + " doesn't support metadata");
            }
            this.dao = (ResourceDetailsDao)s_daoMap.get(resourceType);
        }

        private void removeDetail(long resourceId, String key) {
            dao.removeDetail(resourceId, key);
        }

        private void removeDetails(long resourceId) {
            dao.removeDetails(resourceId);
        }

        private ResourceDetail getDetail(long resourceId, String key) {
            return dao.findDetail(resourceId, key);
        }

        private List<? extends ResourceDetail> getDetails(String key, String value, Boolean forDisplay) {
            return dao.findDetails(key, value, forDisplay);
        }

        private void addDetail(long resourceId, String key, String value, boolean forDisplay) {
            dao.addDetail(resourceId, key, value, forDisplay);
        }

        private Map<String, String> getDetailsMap(long resourceId, Boolean forDisplay) {
            if (forDisplay == null) {
                return dao.listDetailsKeyPairs(resourceId);
            } else {
                return dao.listDetailsKeyPairs(resourceId, forDisplay);
            }
        }

        private List<? extends ResourceDetail> getDetailsList(long resourceId, Boolean forDisplay) {
            if (forDisplay == null) {
                return dao.listDetails(resourceId);
            } else {
                return dao.listDetails(resourceId, forDisplay);
            }
        }
    }

    @Override
    public List<? extends ResourceDetail> getDetailsList(long resourceId, ResourceObjectType resourceType, Boolean forDisplay) {
        DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetailsList(resourceId, forDisplay);
    }

    @Override
    public ResourceDetail getDetail(long resourceId, ResourceObjectType resourceType, String key) {
        DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetail(resourceId, key);
    }

    @Override
    public List<? extends ResourceDetail> getDetails(ResourceObjectType resourceType, String key, String value, Boolean forDisplay){
        DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetails(key, value, forDisplay);
    }

    @Override
    public Map<String, String> getDetailsMap(long resourceId, ResourceObjectType resourceType, Boolean forDisplay) {
        DetailDaoHelper newDetailDaoHelper = new DetailDaoHelper(resourceType);
        return newDetailDaoHelper.getDetailsMap(resourceId, forDisplay);
    }
}
