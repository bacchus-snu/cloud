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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.cloudstack.framework.config.ScopedConfigStorage;
import org.apache.cloudstack.resourcedetail.dao.LBStickinessPolicyDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.NetworkACLItemDetailsDao;
import org.apache.cloudstack.resourcedetail.dao.VpcGatewayDetailsDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.LBStickinessPolicyVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.network.vpc.VpcGatewayVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.server.ResourceManagerUtil;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.NicDetailsDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class ResourceMetaDataManagerImplTest {

    @Mock
    ConfigurationManager configurationManager;
    @Mock
    ResourceManagerUtil resourceManagerUtil;
    @Mock
    VolumeDetailsDao volumeDetailsDao;
    @Mock
    VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock
    NicDetailsDao nicDetailsDao;
    @Mock
    VpcGatewayDetailsDao vpcGatewayDetailsDao;
    @Mock
    LBStickinessPolicyDetailsDao stickinessPolicyDetailsDao;
    @Mock
    NetworkACLItemDetailsDao networkACLItemDetailsDao;
    @Mock
    AccountManager accountManager;
    @Mock
    EntityManager entityManager;
    @Mock
    Account caller;

    MockedStatic<CallContext> callContext;

    DataCenterDetailsDao zoneDetailsDao = Mockito.mock(DataCenterDetailsDao.class, Mockito.withSettings().extraInterfaces(ScopedConfigStorage.class));

    @InjectMocks
    ResourceMetaDataManagerImpl resourceMetaDataManager = new ResourceMetaDataManagerImpl();

    @Before
    public void setUp() throws ConfigurationException {
        resourceMetaDataManager._dcDetailsDao = zoneDetailsDao;
        resourceMetaDataManager._volumeDetailDao = volumeDetailsDao;
        resourceMetaDataManager._userVmDetailDao = vmInstanceDetailsDao;
        resourceMetaDataManager._serviceOfferingDetailsDao = serviceOfferingDetailsDao;
        resourceMetaDataManager._nicDetailDao = nicDetailsDao;
        resourceMetaDataManager._vpcGatewayDetailsDao = vpcGatewayDetailsDao;
        resourceMetaDataManager._stickinessPolicyDetailsDao = stickinessPolicyDetailsDao;
        resourceMetaDataManager._networkACLDetailsDao = networkACLItemDetailsDao;
        resourceMetaDataManager.configure(null, null);
        CallContext context = Mockito.mock(CallContext.class);
        Mockito.lenient().when(context.getCallingAccount()).thenReturn(caller);
        Mockito.lenient().when(caller.getId()).thenReturn(2L);
        Mockito.lenient().when(accountManager.isRootAdmin(2L)).thenReturn(true);
        callContext = Mockito.mockStatic(CallContext.class);
        callContext.when(CallContext::current).thenReturn(context);
    }

    @After
    public void tearDown() {
        callContext.close();
    }

    private void asCallerOtherThanARootAdmin() {
        Mockito.when(accountManager.isRootAdmin(2L)).thenReturn(false);
    }

    /** A resource of the class whose UUID names the ID, and whose access the account manager grants or refuses. */
    private <T> T resource(Class<T> type, ResourceObjectType resourceType, String uuid, long id, boolean accessible) {
        T resource = Mockito.mock(type);
        Mockito.lenient().when(resourceManagerUtil.getResourceId(uuid, resourceType)).thenReturn(id);
        Mockito.lenient().when(entityManager.findById(type, id)).thenReturn(resource);
        if (!accessible && resource instanceof ControlledEntity) {
            Mockito.lenient().doThrow(new PermissionDeniedException("not yours")).when(accountManager)
                    .checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, (ControlledEntity) resource);
        }
        return resource;
    }

    @Test
    public void leaseOfAnOfferingIsNoFreeFormDetail() {
        for (String name : List.of("leasehours", "leaseexpiryaction", "leaseduration")) {
            Assert.assertThrows(name, InvalidParameterValueException.class, () -> resourceMetaDataManager.addResourceMetaData("so",
                    ResourceObjectType.ServiceOffering, new HashMap<>(Map.of(name, "36h")), false));
            Assert.assertThrows(name, InvalidParameterValueException.class,
                    () -> resourceMetaDataManager.deleteResourceMetaData("so", ResourceObjectType.ServiceOffering, name));
        }
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void lifecycleStateOfAnInstanceIsNoDetailCallersSetOrRemove() {
        for (String name : VmDetailConstants.LIFECYCLE_STATE_DETAILS) {
            Assert.assertThrows(name, InvalidParameterValueException.class,
                    () -> resourceMetaDataManager.addResourceMetaData("vm", ResourceObjectType.UserVm, new HashMap<>(Map.of(name, "x")), false));
            Assert.assertThrows(name, InvalidParameterValueException.class,
                    () -> resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm, name));
        }
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());

        // removing every detail of an Instance is allowed: the whole removal leaves its lifecycle state
        Mockito.when(resourceManagerUtil.getResourceId("vm", ResourceObjectType.UserVm)).thenReturn(3L);
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm, null));
        Mockito.verify(vmInstanceDetailsDao).removeDetails(3L);
    }

    @Test
    public void zoneDetailsCarryAccessControlSettingsOnlyThroughTheSharedCheck() {
        Map<String, String> details = new HashMap<>();
        details.put("some.setting", "value");
        Mockito.doThrow(new PermissionDeniedException("refused")).when(configurationManager).checkCallerMayChangeAccessControlSettings(Set.of("some.setting"));
        Mockito.doThrow(new PermissionDeniedException("refused")).when(configurationManager).checkCallerMayChangeAccessControlSettings(List.of("some.setting"));
        Mockito.when(resourceManagerUtil.getResourceId("zone", ResourceObjectType.Zone)).thenReturn(1L);
        Mockito.when(zoneDetailsDao.listDetailsKeyPairs(1L)).thenReturn(details);

        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.addResourceMetaData("zone", ResourceObjectType.Zone, details, true));
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone, "some.setting"));
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone, null));

        Mockito.verify(zoneDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(zoneDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(zoneDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void detailsOfOtherResourcesAreNotSettings() {
        Map<String, String> details = new HashMap<>();
        details.put("some.setting", "value");
        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenReturn(true);
            Assert.assertTrue(resourceMetaDataManager.addResourceMetaData("volume", ResourceObjectType.Volume, details, true));
        }
        Mockito.when(resourceManagerUtil.getResourceId("volume", ResourceObjectType.Volume)).thenReturn(2L);

        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("volume", ResourceObjectType.Volume, null));

        Mockito.verify(volumeDetailsDao).removeDetails(2L);
        Mockito.verify(volumeDetailsDao, Mockito.never()).listDetailsKeyPairs(Mockito.anyLong());
        Mockito.verifyNoInteractions(configurationManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void onlyARootAdminSetsOrRemovesTheNamePrefixOfAnInstance() {
        asCallerOtherThanARootAdmin();
        resource(UserVmVO.class, ResourceObjectType.UserVm, "vm", 3L, true);
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(3L)).thenReturn(Map.of(VmDetailConstants.SSH_NAME_PREFIX, "admin", "keyboard", "us"));
        String prefix = VmDetailConstants.SSH_NAME_PREFIX;
        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenReturn(true);

            Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.addResourceMetaData("vm", ResourceObjectType.UserVm,
                    new HashMap<>(Map.of(prefix, "snucse-baneling100")), false));
            Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm,
                    prefix));
            Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm,
                    null));
            transaction.verifyNoInteractions();
            Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
            Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());

            Mockito.when(accountManager.isRootAdmin(2L)).thenReturn(true);
            Assert.assertTrue(resourceMetaDataManager.addResourceMetaData("vm", ResourceObjectType.UserVm, new HashMap<>(Map.of(prefix, "admin")), false));
            Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm, prefix));
            Mockito.verify(vmInstanceDetailsDao).removeDetail(3L, prefix);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aCallerOtherThanARootAdminChangesOnlyTheDetailsOfResourcesItMayAccess() {
        asCallerOtherThanARootAdmin();
        resource(UserVmVO.class, ResourceObjectType.UserVm, "own-vm", 5L, true);
        resource(UserVmVO.class, ResourceObjectType.UserVm, "other-vm", 6L, false);
        resource(VolumeVO.class, ResourceObjectType.Volume, "other-volume", 7L, false);
        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenReturn(true);

            Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.addResourceMetaData("other-vm", ResourceObjectType.UserVm,
                    new HashMap<>(Map.of("keyboard", "us")), true));
            Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.addResourceMetaData("other-volume", ResourceObjectType.Volume,
                    new HashMap<>(Map.of("key", "value")), true));
            transaction.verifyNoInteractions();
            Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("other-vm", ResourceObjectType.UserVm,
                    "keyboard"));
            Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("other-volume", ResourceObjectType.Volume,
                    null));
            Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
            Mockito.verify(volumeDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());

            Assert.assertTrue(resourceMetaDataManager.addResourceMetaData("own-vm", ResourceObjectType.UserVm, new HashMap<>(Map.of("keyboard", "us")), true));
            Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("own-vm", ResourceObjectType.UserVm, "keyboard"));
            Mockito.verify(vmInstanceDetailsDao).removeDetail(5L, "keyboard");
        }
    }

    @Test
    public void aRootAdminChangesTheDetailsOfAnyResourceWithoutAnAccessCheck() {
        resource(UserVmVO.class, ResourceObjectType.UserVm, "other-vm", 6L, false);

        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("other-vm", ResourceObjectType.UserVm, "keyboard"));

        Mockito.verify(vmInstanceDetailsDao).removeDetail(6L, "keyboard");
        Mockito.verify(accountManager, Mockito.never()).checkAccess(Mockito.any(Account.class), Mockito.any(), Mockito.anyBoolean(),
                Mockito.any(ControlledEntity[].class));
    }

    @Test
    public void theDetailsOfAResourceWithoutAnOwnerAreChangedByRootAdminsAlone() {
        asCallerOtherThanARootAdmin();
        resource(DataCenterVO.class, ResourceObjectType.Zone, "zone", 1L, true);

        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone,
                "some.setting"));
        Mockito.verify(zoneDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());

        Mockito.when(accountManager.isRootAdmin(2L)).thenReturn(true);
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("zone", ResourceObjectType.Zone, "some.setting"));
        Mockito.verify(zoneDetailsDao).removeDetail(1L, "some.setting");
    }

    @Test
    public void theDetailsOfANicFollowTheAccessToItsInstance() {
        asCallerOtherThanARootAdmin();
        NicVO nic = resource(NicVO.class, ResourceObjectType.Nic, "nic", 8L, true);
        Mockito.when(nic.getInstanceId()).thenReturn(5L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(entityManager.findById(VMInstanceVO.class, 5L)).thenReturn(vm);
        Mockito.doThrow(new PermissionDeniedException("not yours")).when(accountManager).checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, vm);

        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("nic", ResourceObjectType.Nic, "key"));

        Mockito.verify(nicDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void theDetailsOfAPrivateGatewayFollowTheAccessToTheGateway() {
        asCallerOtherThanARootAdmin();
        resource(VpcGatewayVO.class, ResourceObjectType.PrivateGateway, "gateway", 9L, true);

        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("gateway", ResourceObjectType.PrivateGateway, "key"));

        Mockito.verify(vpcGatewayDetailsDao).removeDetail(9L, "key");
    }

    @Test
    public void theDetailsOfAnAclRuleOrALoadBalancerPolicyFollowTheAccessToWhatOwnsIt() {
        asCallerOtherThanARootAdmin();
        NetworkACLItemVO rule = resource(NetworkACLItemVO.class, ResourceObjectType.NetworkACL, "rule", 10L, true);
        Mockito.when(rule.getAclId()).thenReturn(11L);
        NetworkACLVO acl = Mockito.mock(NetworkACLVO.class);
        Mockito.when(acl.getVpcId()).thenReturn(12L);
        Mockito.when(entityManager.findById(NetworkACLVO.class, 11L)).thenReturn(acl);
        VpcVO vpc = Mockito.mock(VpcVO.class);
        Mockito.when(entityManager.findById(VpcVO.class, 12L)).thenReturn(vpc);
        Mockito.doThrow(new PermissionDeniedException("not yours")).when(accountManager).checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, vpc);
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("rule", ResourceObjectType.NetworkACL, "key"));

        NetworkACLItemVO ownRule = resource(NetworkACLItemVO.class, ResourceObjectType.NetworkACL, "own-rule", 16L, true);
        Mockito.when(ownRule.getAclId()).thenReturn(17L);
        NetworkACLVO ownAcl = Mockito.mock(NetworkACLVO.class);
        Mockito.when(ownAcl.getVpcId()).thenReturn(18L);
        Mockito.when(entityManager.findById(NetworkACLVO.class, 17L)).thenReturn(ownAcl);
        Mockito.when(entityManager.findById(VpcVO.class, 18L)).thenReturn(Mockito.mock(VpcVO.class));
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("own-rule", ResourceObjectType.NetworkACL, "key"));
        Mockito.verify(networkACLItemDetailsDao).removeDetail(16L, "key");

        NetworkACLVO defaultAcl = resource(NetworkACLVO.class, ResourceObjectType.NetworkACLList, "default-acl", 15L, true);
        Mockito.when(defaultAcl.getVpcId()).thenReturn(0L);
        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("default-acl",
                ResourceObjectType.NetworkACLList, "key"));

        LBStickinessPolicyVO policy = resource(LBStickinessPolicyVO.class, ResourceObjectType.LBStickinessPolicy, "policy", 13L, true);
        Mockito.when(policy.getLoadBalancerId()).thenReturn(14L);
        LoadBalancerVO loadBalancer = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(entityManager.findById(LoadBalancerVO.class, 14L)).thenReturn(loadBalancer);
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("policy", ResourceObjectType.LBStickinessPolicy, "key"));
        Mockito.verify(accountManager).checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, loadBalancer);
        Mockito.verify(stickinessPolicyDetailsDao).removeDetail(13L, "key");
    }

    @Test
    public void aCallerMayOnlyUseCannotChangeTheDetailsOfATemplate() {
        asCallerOtherThanARootAdmin();
        VMTemplateVO publicTemplate = resource(VMTemplateVO.class, ResourceObjectType.Template, "public-template", 20L, false);

        Assert.assertThrows(PermissionDeniedException.class, () -> resourceMetaDataManager.deleteResourceMetaData("public-template",
                ResourceObjectType.Template, "key"));

        Mockito.verify(accountManager).checkAccess(caller, SecurityChecker.AccessType.OperateEntry, false, publicTemplate);
        Mockito.verify(accountManager, Mockito.never()).checkAccess(caller, null, false, publicTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aCallerOtherThanARootAdminCannotWriteTheNamePrefixInAnyCase() {
        asCallerOtherThanARootAdmin();
        resource(UserVmVO.class, ResourceObjectType.UserVm, "vm", 3L, true);
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(3L)).thenReturn(Map.of("SSH.NAME.PREFIX", "shown", "keyboard", "us"));
        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            for (String key : List.of("SSH.NAME.PREFIX", "Ssh.Name.Prefix")) {
                Assert.assertThrows(key, InvalidParameterValueException.class, () -> resourceMetaDataManager.addResourceMetaData("vm", ResourceObjectType.UserVm,
                        new HashMap<>(Map.of(key, "snucse-victim")), false));
                Assert.assertThrows(key, InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("vm",
                        ResourceObjectType.UserVm, key));
            }
            Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm,
                    null));
            transaction.verifyNoInteractions();
        }
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());
    }

    @Test
    public void removingEveryDetailOfAResourceRemovesNoneOfItsLifecycleState() {
        resource(ServiceOfferingVO.class, ResourceObjectType.ServiceOffering, "leased-offering", 5L, true);
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(5L)).thenReturn(Map.of("leasehours", "36", "keyboard", "us"));

        Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("leased-offering",
                ResourceObjectType.ServiceOffering, null));
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());

        resource(ServiceOfferingVO.class, ResourceObjectType.ServiceOffering, "offering", 6L, true);
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(6L)).thenReturn(Map.of("keyboard", "us"));
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("offering", ResourceObjectType.ServiceOffering, null));
        Mockito.verify(serviceOfferingDetailsDao).removeDetails(6L);

        resource(UserVmVO.class, ResourceObjectType.UserVm, "leased-vm", 7L, true);
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(7L)).thenReturn(Map.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, "2027-01-15 08:00:00"));
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("leased-vm", ResourceObjectType.UserVm, null));
        Mockito.verify(vmInstanceDetailsDao).removeDetails(7L);
    }

    private static List<String> otherCasesOf(String name) {
        return List.of(name.toUpperCase(java.util.Locale.ROOT), Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    @Test
    public void lifecycleStateOfAnInstanceIsNoDetailCallersSetOrRemoveInAnyCase() {
        for (String name : VmDetailConstants.LIFECYCLE_STATE_DETAILS) {
            for (String spelling : otherCasesOf(name)) {
                Assert.assertThrows(spelling, InvalidParameterValueException.class, () -> resourceMetaDataManager.addResourceMetaData("vm",
                        ResourceObjectType.UserVm, new HashMap<>(Map.of(spelling, "x")), false));
                Assert.assertThrows(spelling, InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("vm",
                        ResourceObjectType.UserVm, spelling));
            }
        }
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());

        // the whole removal leaves the lifecycle state in any case, as its names compare in the database
        Mockito.when(resourceManagerUtil.getResourceId("vm", ResourceObjectType.UserVm)).thenReturn(3L);
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(3L)).thenReturn(Map.of("LeaseExpiryDate", "x", "keyboard", "us"));
        Assert.assertTrue(resourceMetaDataManager.deleteResourceMetaData("vm", ResourceObjectType.UserVm, null));
        Mockito.verify(vmInstanceDetailsDao).removeDetails(3L);
    }

    @Test
    public void leaseOfAnOfferingIsNoFreeFormDetailInAnyCase() {
        for (String name : VMLeaseManager.OFFERING_LEASE_DETAILS) {
            for (String spelling : otherCasesOf(name)) {
                Assert.assertThrows(spelling, InvalidParameterValueException.class, () -> resourceMetaDataManager.addResourceMetaData("so",
                        ResourceObjectType.ServiceOffering, new HashMap<>(Map.of(spelling, "36")), false));
                Assert.assertThrows(spelling, InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("so",
                        ResourceObjectType.ServiceOffering, spelling));
            }
        }
        Mockito.when(resourceManagerUtil.getResourceId("so", ResourceObjectType.ServiceOffering)).thenReturn(5L);
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(5L)).thenReturn(Map.of("LeaseHours", "36", "keyboard", "us"));
        Assert.assertThrows(InvalidParameterValueException.class, () -> resourceMetaDataManager.deleteResourceMetaData("so", ResourceObjectType.ServiceOffering,
                null));
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean());
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
        Mockito.verify(serviceOfferingDetailsDao, Mockito.never()).removeDetails(Mockito.anyLong());
    }
}
