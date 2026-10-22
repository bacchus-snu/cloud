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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.command.admin.systemvm.MigrateSystemVMCmd;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.vm.MigrateVirtualMachineWithVolumeCmd;
import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.api.response.SystemVmResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.BackupProvider;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupVO;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.deploy.PlacementRequest;
import com.cloud.deploy.PlacementService;
import com.cloud.deploy.PlacementVerdict;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VolumeMoveRequiredException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.as.AutoScaleManager;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.security.SecurityGroupVO;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceService;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.server.ManagementService;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StorageService;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionProxyObject;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class UserVmManagerImplTest {

    @Spy
    @InjectMocks
    private UserVmManagerImpl userVmManagerImpl = new UserVmManagerImpl();

    @Mock
    private PlacementService placementService;

    @Mock
    private VolumeOrchestrationService volumeOrchestrationService;

    @Mock
    private AlertManager alertManager;

    @Mock
    private ServiceOfferingDao _serviceOfferingDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DataCenterDao _dcDao;

    @Mock
    private DataCenterVO _dcMock;

    @Mock
    protected NicDao nicDao;

    @Mock
    private NetworkDao _networkDao;

    @Mock
    private NetworkOrchestrationService _networkMgr;

    @Mock
    private NetworkVO networkMock;

    @Mock
    private GuestOSDao guestOSDao;

    @Mock
    private UserVmDao userVmDao;

    @Mock
    private UpdateVMCmd updateVmCommand;

    @Mock
    private UpdateVmNicCmd updateVmNicCmd;

    @Mock
    private AccountManager accountManager;

    @Mock
    private AccountService accountService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Mock
    private UserVmVO userVmVoMock;

    @Mock
    private NetworkModel networkModel;

    @Mock
    private Account accountMock;

    @Mock
    private AccountVO callerAccount;

    @Mock
    private UserVO callerUser;

    @Mock
    private NicVO nicMock;

    @Mock
    private VMTemplateDao templateDao;

    @Mock
    private AccountDao accountDao;

    @Mock
    private UserDao userDao;

    @Mock
    ResourceLimitService resourceLimitMgr;

    @Mock
    VolumeApiService volumeApiService;

    @Mock
    UserDataDao userDataDao;

    @Mock
    PrimaryDataStoreDao primaryDataStoreDao;

    @Mock
    BackupDao backupDao;

    @Mock
    BackupManager backupManager;

    @Mock
    VirtualMachineManager virtualMachineManager;

    @Mock
    DeploymentPlanningManager planningManager;

    @Mock
    HostDao hostDao;

    @Mock
    private VolumeVO volumeVOMock;

    @Mock
    private VolumeDao volumeDaoMock;

    @Mock
    private SnapshotDao snapshotDaoMock;

    @Mock
    private VMSnapshotDao vmSnapshotDaoMock;

    @Mock
    AccountVO account;

    @Mock
    VMTemplateVO vmTemplateVoMock;

    @Mock
    ManagementService managementServiceMock;

    @Mock
    AssignVMCmd assignVmCmdMock;

    @Mock
    PortForwardingRulesDao portForwardingRulesDaoMock;

    @Mock
    List<PortForwardingRule> portForwardingRulesListMock;

    @Mock
    FirewallRulesDao firewallRulesDaoMock;

    @Mock
    List<FirewallRuleVO> firewallRuleVoListMock;

    @Mock
    LoadBalancerVMMapDao loadBalancerVmMapDaoMock;

    @Mock
    List<LoadBalancerVMMapVO> loadBalancerVmMapVoListMock;

    @Mock
    IPAddressDao ipAddressDaoMock;

    @Mock
    IPAddressVO ipAddressVoMock;

    @Mock
    VirtualMachineTemplate virtualMachineTemplateMock;

    @Mock
    VirtualMachineProfileImpl virtualMachineProfileMock;

    @Mock
    List<NetworkVO> networkVoListMock;

    @Mock
    SecurityGroupManager securityGroupManagerMock;

    @Mock
    NetworkOfferingDao networkOfferingDaoMock;

    @Mock
    NetworkOfferingVO networkOfferingVoMock;

    @Mock
    List<NetworkOfferingVO> networkOfferingVoListMock;

    @Mock
    PhysicalNetworkDao physicalNetworkDaoMock;

    @Mock
    SecurityGroupVO securityGroupVoMock;

    @Mock
    DomainDao domainDaoMock;

    @Mock
    DomainVO domainVoMock;

    @Mock
    SnapshotVO snapshotVoMock;

    @Mock
    ServiceOfferingVO serviceOfferingVoMock;

    @Mock
    private ServiceOfferingVO serviceOffering;

    @Mock
    UserDataManager userDataManager;

    @Mock
    VirtualMachineProfile virtualMachineProfile;

    @Mock
    VirtualMachineTemplate templateMock;

    @Mock
    VnfTemplateManager vnfTemplateManager;

    @Mock
    ServiceOfferingJoinDao serviceOfferingJoinDao;

    @Mock
    SSHKeyPairDao sshKeyPairDao;

    @Mock
    private BackupProvider backupProviderMock;

    @Mock
    private VMInstanceVO vmInstanceMock;

    @Mock
    StorageManager storageManager;

    @Mock
    private VolumeDataFactory volumeDataFactory;

    @Mock
    private VolumeInfo volumeInfo;

    @Mock
    private SnapshotVO snapshotMock;

    @Mock
    private PrimaryDataStore primaryDataStore;

    @Mock
    private Scope scopeMock;

    @Mock
    private AutoScaleManager autoScaleManager;

    @Mock
    private UUIDManager uuidMgr;


    @Mock
    private SnapshotPolicyDao snapshotPolicyDao;

    @Mock
    private BackupScheduleDao backupScheduleDao;

    MockedStatic<UnmanagedVMsManager> unmanagedVMsManagerMockedStatic;

    @Mock
    ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    private static final long vmId = 1L;
    private static final long zoneId = 2L;
    private static final long accountId = 3L;
    private static final long nicId = 4L;
    private static final long networkId = 5L;
    private static final long serviceOfferingId = 10L;
    private static final long templateId = 11L;
    private static final long volumeId = 1L;
    private static final long snashotId = 1L;
    private static final String vmUuid = UUID.randomUUID().toString();

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    private Map<String, String> customParameters = new HashMap<>();

    String[] detailsConstants = {VmDetailConstants.MEMORY, VmDetailConstants.CPU_NUMBER, VmDetailConstants.CPU_SPEED};

    private DiskOfferingVO smallerDisdkOffering = prepareDiskOffering(5L * GiB_TO_BYTES, 1L, 1L, 2L);
    private DiskOfferingVO largerDisdkOffering = prepareDiskOffering(10L * GiB_TO_BYTES, 2L, 10L, 20L);
    Class<InvalidParameterValueException> expectedInvalidParameterValueException = InvalidParameterValueException.class;
    Class<CloudRuntimeException> expectedCloudRuntimeException = CloudRuntimeException.class;

    private Map<ConfigKey, Object> originalConfigValues = new HashMap<>();


    private void updateDefaultConfigValue(final ConfigKey configKey, final Object o, boolean revert) {
        try {
            final String name = "_defaultValue";
            Field f = ConfigKey.class.getDeclaredField(name);
            f.setAccessible(true);
            String stringVal = String.valueOf(o);
            if (!revert) {
                originalConfigValues.put(configKey, f.get(configKey));
            }
            f.set(configKey, stringVal);
        } catch (IllegalAccessException | NoSuchFieldException  e) {
            Assert.fail("Failed to mock config " + configKey.key() + " value due to " + e.getMessage());
        }
    }

    @Before
    public void beforeTest() {
        userVmManagerImpl.resourceLimitService = resourceLimitMgr;

        Mockito.when(updateVmCommand.getId()).thenReturn(vmId);

        when(_dcDao.findById(anyLong())).thenReturn(_dcMock);

        Mockito.when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);

        Mockito.when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);

        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, "123");
        customParameters.put(VmDetailConstants.MEMORY, "2048");
        customParameters.put(VmDetailConstants.CPU_NUMBER, "4");
        customParameters.put(VmDetailConstants.CPU_SPEED, "1000");

        lenient().doNothing().when(resourceLimitMgr).incrementResourceCount(anyLong(), any(Resource.ResourceType.class));
        lenient().doNothing().when(resourceLimitMgr).decrementResourceCount(anyLong(), any(Resource.ResourceType.class), anyLong());

        Mockito.when(virtualMachineProfile.getId()).thenReturn(vmId);
        unmanagedVMsManagerMockedStatic = mockStatic(UnmanagedVMsManager.class);
    }

    @After
    public void afterTest() {
        CallContext.unregister();
        unmanagedVMsManagerMockedStatic.close();
        for (Map.Entry<ConfigKey, Object> entry : originalConfigValues.entrySet()) {
            updateDefaultConfigValue(entry.getKey(), entry.getValue(), true);
        }
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNull() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(null);
        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeNotFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1L);

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test
    public void validateGuestOsIdForUpdateVirtualMachineCommandTestOsTypeFound() {
        Mockito.when(updateVmCommand.getOsTypeId()).thenReturn(1L);
        Mockito.when(guestOSDao.findById(1L)).thenReturn(Mockito.mock(GuestOSVO.class));

        userVmManagerImpl.validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTestVmNotFound() {
        Mockito.when(userVmDao.findById(vmId)).thenReturn(null);

        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
    }

    private ServiceOfferingVO getSvcoffering(int ramSize) {
        String name = "name";
        String displayText = "displayText";
        int cpu = 1;
        int speed = 128;

        boolean ha = false;

        ServiceOfferingVO serviceOffering = new ServiceOfferingVO(name, cpu, ramSize, speed, null, null, ha, displayText, false, null,
                false);
        serviceOffering.setDiskOfferingId(1L);
        return serviceOffering;
    }

    @Test
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandTest() {
        Mockito.doNothing().when(userVmManagerImpl).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);

        CallContext callContextMock = Mockito.mock(CallContext.class);

        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ServiceOffering offering = getSvcoffering(512);
        Mockito.lenient().when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.lenient().doNothing().when(accountManager).checkAccess(accountMock, null, true, userVmVoMock);
        userVmManagerImpl.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);

        Mockito.verify(userVmManagerImpl).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, userVmVoMock);
    }

    @Test
    public void updateVirtualMachineTestDisplayChanged() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();
        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.when(userVmVoMock.isDisplay()).thenReturn(true);
        Mockito.doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);
        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();

        Mockito.verify(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.verify(vmInstanceDetailsDao, times(0)).removeDetail(anyLong(), anyString());
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrue() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();
        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        Mockito.when(updateVmCommand.isCleanupDetails()).thenReturn(true);
        Mockito.lenient().doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);

        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);

        prepareExistingDetails(vmId, "userdetail");

        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();
        Mockito.verify(vmInstanceDetailsDao).removeDetail(vmId, "userdetail");
        Mockito.verify(vmInstanceDetailsDao, times(0)).removeDetail(vmId, "systemdetail");
        Mockito.verify(userVmManagerImpl, times(0)).updateDisplayVmFlag(false, vmId, userVmVoMock);
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrueAndDetailEmpty() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        prepareAndExecuteMethodDealingWithDetails(true, true);
    }

    @Test
    public void updateVirtualMachineTestCleanUpTrueAndDetailsNotEmpty() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        prepareAndExecuteMethodDealingWithDetails(true, false);
    }

    @Test
    public void updateVirtualMachineTestCleanUpFalseAndDetailsNotEmpty() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        prepareAndExecuteMethodDealingWithDetails(false, true);
    }

    @Test
    public void updateVirtualMachineTestCleanUpFalseAndDetailsEmpty() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Mockito.doNothing().when(userVmManagerImpl).verifyVmLimits(Mockito.any(), Mockito.anyMap());
        prepareAndExecuteMethodDealingWithDetails(false, false);
    }

    private List<VMInstanceDetailVO> prepareExistingDetails(Long vmId, String... existingDetailKeys) {
        List<VMInstanceDetailVO> existingDetails = new ArrayList<>();
        for (String detail : existingDetailKeys) {
            existingDetails.add(new VMInstanceDetailVO(vmId, detail, "foo", true));
        }
        existingDetails.add(new VMInstanceDetailVO(vmId, "systemdetail", "bar", false));
        Mockito.when(vmInstanceDetailsDao.listDetails(vmId)).thenReturn(existingDetails);
        return existingDetails;
    }

    private void prepareAndExecuteMethodDealingWithDetails(boolean cleanUpDetails, boolean isDetailsEmpty) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        configureDoNothingForMethodsThatWeDoNotWantToTest();

        ServiceOffering offering = getSvcoffering(512);
        Mockito.when(_serviceOfferingDao.findById(Mockito.anyLong(), Mockito.anyLong())).thenReturn((ServiceOfferingVO) offering);
        ServiceOfferingVO currentServiceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.lenient().when(currentServiceOffering.getCpu()).thenReturn(1);
        Mockito.lenient().when(currentServiceOffering.getRamSize()).thenReturn(512);

        List<NicVO> nics = new ArrayList<>();
        NicVO nic1 = mock(NicVO.class);
        NicVO nic2 = mock(NicVO.class);
        nics.add(nic1);
        nics.add(nic2);
        when(this.nicDao.listByVmId(Mockito.anyLong())).thenReturn(nics);
        when(_networkDao.findById(anyLong())).thenReturn(networkMock);
        lenient().doNothing().when(_networkMgr).saveExtraDhcpOptions(anyString(), anyLong(), anyMap());
        HashMap<String, String> details = new HashMap<>();
        if(!isDetailsEmpty) {
            details.put("newdetail", "foo");
        }
        prepareExistingDetails(vmId, "existingdetail");
        Mockito.when(updateVmCommand.getUserdataId()).thenReturn(null);
        Mockito.when(updateVmCommand.getDetails()).thenReturn(details);
        Mockito.when(updateVmCommand.isCleanupDetails()).thenReturn(cleanUpDetails);
        configureDoNothingForDetailsMethod();

        userVmManagerImpl.updateVirtualMachine(updateVmCommand);
        verifyMethodsThatAreAlwaysExecuted();

        Mockito.verify(userVmVoMock, times(cleanUpDetails || isDetailsEmpty ? 0 : 1)).setDetails(details);
        Mockito.verify(vmInstanceDetailsDao, times(cleanUpDetails ? 1 : 0)).removeDetail(vmId, "existingdetail");
        Mockito.verify(vmInstanceDetailsDao, times(0)).removeDetail(vmId, "systemdetail");
        Mockito.verify(userVmDao, times(cleanUpDetails || isDetailsEmpty ? 0 : 1)).saveDetails(userVmVoMock);
        Mockito.verify(userVmManagerImpl, times(0)).updateDisplayVmFlag(false, vmId, userVmVoMock);
    }

    private void configureDoNothingForDetailsMethod() {
        Mockito.lenient().doNothing().when(userVmManagerImpl).updateDisplayVmFlag(false, vmId, userVmVoMock);
        Mockito.doNothing().when(vmInstanceDetailsDao).removeDetail(anyLong(), anyString());
        Mockito.doNothing().when(userVmDao).saveDetails(userVmVoMock);
    }

    @SuppressWarnings("unchecked")
    private void verifyMethodsThatAreAlwaysExecuted() throws ResourceUnavailableException, InsufficientCapacityException {
        Mockito.verify(userVmManagerImpl).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.verify(userVmManagerImpl).getSecurityGroupIdList(updateVmCommand);

        Mockito.verify(userVmManagerImpl).updateVirtualMachine(nullable(Long.class), nullable(String.class), nullable(String.class), nullable(Boolean.class),
                nullable(Boolean.class), nullable(Boolean.class), nullable(Long.class),
                nullable(String.class), nullable(Long.class), nullable(String.class), nullable(Boolean.class), nullable(HTTPMethod.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(List.class),
                nullable(Map.class));

    }

    private void configureDoNothingForMethodsThatWeDoNotWantToTest() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Mockito.doNothing().when(userVmManagerImpl).validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);
        Mockito.doReturn(new ArrayList<Long>()).when(userVmManagerImpl).getSecurityGroupIdList(updateVmCommand);

        Mockito.lenient().doReturn(Mockito.mock(UserVm.class)).when(userVmManagerImpl).updateVirtualMachine(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean(),
                Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.any(HTTPMethod.class), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), anyList(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).validateIfVmSupportsMigration(Mockito.any(), Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).validateOldAndNewAccounts(Mockito.nullable(Account.class), Mockito.nullable(Account.class), Mockito.anyLong(), Mockito.nullable(String.class), Mockito.nullable(Long.class));
        Mockito.doNothing().when(userVmManagerImpl).validateIfVmHasNoRules(Mockito.any(), Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).removeInstanceFromInstanceGroup(Mockito.anyLong());
        Mockito.doNothing().when(userVmManagerImpl).validateIfNewOwnerHasAccessToTemplate(Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doNothing().when(userVmManagerImpl).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressValid() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(0, "01:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNull() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, null, "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressBlank() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, " ", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressEmpty() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption1() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "abcdef:gh:ij:kl", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption2() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption3() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "01:23:45:67:89:az", "01:23:45:67:89:ab");
    }

    @Test
    public void validateOrReplaceMacAddressTestMacAddressNotValidOption4() throws InsufficientAddressCapacityException {
        configureValidateOrReplaceMacAddressTest(1, "@1:23:45:67:89:ab", "01:23:45:67:89:ab");
    }

    private void configureValidateOrReplaceMacAddressTest(int times, String macAddress, String expectedMacAddress) throws InsufficientAddressCapacityException {
        Mockito.when(networkModel.getNextAvailableMacAddressInNetwork(Mockito.anyLong())).thenReturn(expectedMacAddress);

        String returnedMacAddress = userVmManagerImpl.validateOrReplaceMacAddress(macAddress, networkMock);

        Mockito.verify(networkModel, times(times)).getNextAvailableMacAddressInNetwork(Mockito.anyLong());
        assertEquals(expectedMacAddress, returnedMacAddress);
    }

    @Test
    public void testValidatekeyValuePair() {
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true\nHVM-boot-policy=\nPV-bootloader=pygrub\nPV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("is-a-template=true HVM-boot-policy= PV-bootloader=pygrub PV-args=hvc0"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("nvp.vm-uuid=34b3d5ea-1c25-4bb0-9250-8dc3388bfa9b"));
        assertFalse(userVmManagerImpl.isValidKeyValuePair("key"));
        //key-1=value1, param:key-2=value2, my.config.v0=False"
        assertTrue(userVmManagerImpl.isValidKeyValuePair("key-1=value1"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("param:key-2=value2"));
        assertTrue(userVmManagerImpl.isValidKeyValuePair("my.config.v0=False"));
    }

    @Test
    public void configureCustomRootDiskSizeTest() {
        String vmDetailsRootDiskSize = "123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 123L * GiB_TO_BYTES;
        long offeringRootDiskSize = 0L;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionZero() {
        String vmDetailsRootDiskSize = "0";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = 0L;
        long offeringRootDiskSize = 0L;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void configureCustomRootDiskSizeTestExpectExceptionNegativeNum() {
        String vmDetailsRootDiskSize = "-123";
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, vmDetailsRootDiskSize);
        long expectedRootDiskSize = -123L * GiB_TO_BYTES;
        long offeringRootDiskSize = 0L;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParameters() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 99L * GiB_TO_BYTES;
        long offeringRootDiskSize = 0L;
        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    @Test
    public void configureCustomRootDiskSizeTestEmptyParametersAndOfferingRootSize() {
        Map<String, String> customParameters = new HashMap<>();
        long expectedRootDiskSize = 10L * GiB_TO_BYTES;
        long offeringRootDiskSize = 10L * GiB_TO_BYTES;

        prepareAndRunConfigureCustomRootDiskSizeTest(customParameters, expectedRootDiskSize, 1, offeringRootDiskSize);
    }

    private void prepareAndRunConfigureCustomRootDiskSizeTest(Map<String, String> customParameters, long expectedRootDiskSize, int timesVerifyIfHypervisorSupports, Long offeringRootDiskSize) {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getSize()).thenReturn(99L * GiB_TO_BYTES);

        DiskOfferingVO diskfferingVo = Mockito.mock(DiskOfferingVO.class);

        Mockito.when(diskfferingVo.getDiskSize()).thenReturn(offeringRootDiskSize);

        Mockito.when(volumeApiService.validateVolumeSizeInBytes(Mockito.anyLong())).thenReturn(true);
        long rootDiskSize = userVmManagerImpl.configureCustomRootDiskSize(customParameters, template, Hypervisor.HypervisorType.KVM, diskfferingVo, true, 1L);

        Assert.assertEquals(expectedRootDiskSize, rootDiskSize);
        Mockito.verify(userVmManagerImpl, times(timesVerifyIfHypervisorSupports)).verifyIfHypervisorSupportsRootdiskSizeOverride(Mockito.any());
    }

    @Test
    public void verifyIfHypervisorSupportRootdiskSizeOverrideTest() {
        Hypervisor.HypervisorType[] hypervisorTypeArray = Hypervisor.HypervisorType.values();
        int exceptionCounter = 0;
        int expectedExceptionCounter = hypervisorTypeArray.length - 6;

        for(int i = 0; i < hypervisorTypeArray.length; i++) {
            if (hypervisorTypeArray[i].isFunctionalitySupported(Hypervisor.HypervisorType.Functionality.RootDiskSizeOverride)) {
                userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
            } else {
                try {
                    userVmManagerImpl.verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorTypeArray[i]);
                } catch (InvalidParameterValueException e) {
                    exceptionCounter ++;
                }
            }
        }

        Assert.assertEquals(expectedExceptionCounter, exceptionCounter);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestRootVolumeNull() {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(null, currentRootDiskOffering, newRootDiskOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestCurrentRootDiskOffering() {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, null, newRootDiskOffering);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestNewRootDiskOffering() {
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, currentRootDiskOffering, null);
    }

    @Test
    public void prepareResizeVolumeCmdTestNewOfferingLarger() {
        prepareAndRunResizeVolumeTest(2L, 10L, 20L, smallerDisdkOffering, largerDisdkOffering);
    }

    @Test
    public void prepareResizeVolumeCmdTestSameOfferingSize() {
        prepareAndRunResizeVolumeTest(null, 1L, 2L, smallerDisdkOffering, smallerDisdkOffering);
    }

    @Test
    public void prepareResizeVolumeCmdTestOfferingRootSizeZero() {
        DiskOfferingVO rootSizeZero = prepareDiskOffering(0L, 3L, 100L, 200L);
        prepareAndRunResizeVolumeTest(null, 100L, 200L, smallerDisdkOffering, rootSizeZero);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void prepareResizeVolumeCmdTestNewOfferingSmaller() {
        prepareAndRunResizeVolumeTest(2L, 10L, 20L, largerDisdkOffering, smallerDisdkOffering);
    }

    private void prepareAndRunResizeVolumeTest(Long expectedOfferingId, long expectedMinIops, long expectedMaxIops, DiskOfferingVO currentRootDiskOffering, DiskOfferingVO newRootDiskOffering) {
        long rootVolumeId = 1L;
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        Mockito.when(rootVolumeOfVm.getId()).thenReturn(rootVolumeId);

        ResizeVolumeCmd resizeVolumeCmd = userVmManagerImpl.prepareResizeVolumeCmd(rootVolumeOfVm, currentRootDiskOffering, newRootDiskOffering);

        Assert.assertEquals(rootVolumeId, resizeVolumeCmd.getId().longValue());
        Assert.assertEquals(expectedOfferingId, resizeVolumeCmd.getNewDiskOfferingId());
        Assert.assertEquals(expectedMinIops, resizeVolumeCmd.getMinIops().longValue());
        Assert.assertEquals(expectedMaxIops, resizeVolumeCmd.getMaxIops().longValue());
    }

    private DiskOfferingVO prepareDiskOffering(long rootSize, long diskOfferingId, long offeringMinIops, long offeringMaxIops) {
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(newRootDiskOffering.getDiskSize()).thenReturn(rootSize);
        Mockito.when(newRootDiskOffering.getId()).thenReturn(diskOfferingId);
        Mockito.when(newRootDiskOffering.getMinIops()).thenReturn(offeringMinIops);
        Mockito.when(newRootDiskOffering.getMaxIops()).thenReturn(offeringMaxIops);
        return newRootDiskOffering;
    }

    @Test (expected = CloudRuntimeException.class)
    public void testUserDataDenyOverride() {
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.DENYOVERRIDE);

        userVmManagerImpl.finalizeUserData(null, userDataId, template);
    }

    @Test
    public void testUserDataAllowOverride() {
        String templateUserData = "testTemplateUserdata";
        Long userDataId = 1L;

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(templateUserData, finalUserdata);
    }

    @Test
    public void testUserDataWithoutTemplate() {
        String userData = "testUserdata";
        Long userDataId = 1L;

        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(apiUserDataVO).when(userDataDao).findById(userDataId);
        when(apiUserDataVO.getUserData()).thenReturn(userData);

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, userDataId, template);

        Assert.assertEquals(userData, finalUserdata);
    }

    @Test
    public void testUserDataAllowOverrideWithoutAPIuserdata() {
        String templateUserData = "testTemplateUserdata";

        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(2L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);
        UserDataVO templateUserDataVO = Mockito.mock(UserDataVO.class);
        doReturn(templateUserDataVO).when(userDataDao).findById(2L);
        when(templateUserDataVO.getUserData()).thenReturn(templateUserData);

        String finalUserdata = userVmManagerImpl.finalizeUserData(null, null, template);

        Assert.assertEquals(templateUserData, finalUserdata);
    }

    @Test
    public void testUserDataAllowOverrideWithUserdataText() {
        String userData = "testUserdata";
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        String finalUserdata = userVmManagerImpl.finalizeUserData(userData, null, template);

        Assert.assertEquals(userData, finalUserdata);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testResetVMUserDataVMStateNotStopped() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVoMock);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(userVmVoMock.getTemplateId()).thenReturn(2L);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);


        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);

        try {
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException | InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testResetVMUserDataDontAcceptBothUserdataAndUserdataId() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVoMock);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(userVmVoMock.getTemplateId()).thenReturn(2L);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);


        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Stopped);

        when(cmd.getUserdataId()).thenReturn(1L);

        try {
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException | InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testResetVMUserDataSuccessResetWithUserdata() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(2L);
        userVmVO.setState(VirtualMachine.State.Stopped);
        userVmVO.setUserDataId(100L);
        userVmVO.setUserData("RandomUserdata");

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVO);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);
        when(template.getUserDataId()).thenReturn(null);

        String testUserData = "testUserdata";
        when(cmd.getUserData()).thenReturn(testUserData);
        when(cmd.getUserdataId()).thenReturn(null);
        when(cmd.getHttpMethod()).thenReturn(HTTPMethod.GET);

        when(userDataManager.validateUserData(testUserData, HTTPMethod.GET)).thenReturn(testUserData);

        try {
            doNothing().when(userVmManagerImpl).updateUserData(userVmVO);
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException | InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }

        Assert.assertEquals("testUserdata", userVmVO.getUserData());
        Assert.assertNull(userVmVO.getUserDataId());
    }

    @Test
    public void testResetVMUserDataSuccessResetWithUserdataId() {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(2L);
        userVmVO.setState(VirtualMachine.State.Stopped);
        userVmVO.setUserDataId(100L);
        userVmVO.setUserData("RandomUserdata");

        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(userVmDao.findById(1L)).thenReturn(userVmVO);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);
        when(template.getUserDataId()).thenReturn(null);

        String testUserData = "testUserdata";
        when(cmd.getUserdataId()).thenReturn(1L);
        UserDataVO apiUserDataVO = Mockito.mock(UserDataVO.class);
        when(userDataDao.findById(1L)).thenReturn(apiUserDataVO);
        when(apiUserDataVO.getUserData()).thenReturn(testUserData);
        when(cmd.getHttpMethod()).thenReturn(HTTPMethod.GET);

        when(userDataManager.validateUserData(testUserData, HTTPMethod.GET)).thenReturn(testUserData);

        try {
            doNothing().when(userVmManagerImpl).updateUserData(userVmVO);
            userVmManagerImpl.resetVMUserData(cmd);
        } catch (ResourceUnavailableException | InsufficientCapacityException e) {
            throw new RuntimeException(e);
        }

        Assert.assertEquals("testUserdata", userVmVO.getUserData());
        Assert.assertEquals(1L, (long)userVmVO.getUserDataId());
    }

    @Test
    public void recoverRootVolumeTestDestroyState() {
        Mockito.doReturn(Volume.State.Destroy).when(volumeVOMock).getState();

        try (MockedStatic<UsageEventUtils> ignored = Mockito.mockStatic(UsageEventUtils.class)) {
            userVmManagerImpl.recoverRootVolume(volumeVOMock, vmId);

            Mockito.verify(volumeApiService).recoverVolume(volumeVOMock.getId());
            Mockito.verify(volumeDaoMock).attachVolume(volumeVOMock.getId(), vmId, UserVmManagerImpl.ROOT_DEVICE_ID);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createVirtualMachineWithInactiveServiceOffering() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Inactive);

        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachine() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "templateId", templateId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Active);

        when(entityManager.findById(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), nullable(Map.class));

        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(svcOfferingMock);
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.createVirtualMachine(deployVMCmd);
        assertEquals(userVmVoMock, result);
        Mockito.verify(vnfTemplateManager).validateVnfApplianceNics(templateMock, null, Collections.emptyMap());
        Mockito.verify(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());
    }

    private List<VolumeVO> mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(int localVolumes, int nonLocalVolumes) {
        List<VolumeVO> volumes = new ArrayList<>();
        for (int i=0; i< localVolumes + nonLocalVolumes; ++i) {
            VolumeVO vol = Mockito.mock(VolumeVO.class);
            long index = i + 1;
            Mockito.when(vol.getDiskOfferingId()).thenReturn(index);
            Mockito.when(vol.getPoolId()).thenReturn(index);
            DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
            Mockito.when(diskOfferingDao.findById(index)).thenReturn(diskOffering);
            StoragePoolVO storagePool = Mockito.mock(StoragePoolVO.class);
            Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(storagePool);
            if (i < localVolumes) {
                if ((localVolumes + nonLocalVolumes) % 2 == 0) {
                    Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(true);
                } else {

                    Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
                    Mockito.when(storagePool.isLocal()).thenReturn(true);
                }
            } else {
                Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
                Mockito.when(storagePool.isLocal()).thenReturn(false);
            }
            volumes.add(vol);
        }
        return volumes;
    }

    @Test
    public void testIsAnyVmVolumeUsingLocalStorage() {
        try {
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(1, 0)));
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(2, 0)));
            Assert.assertTrue(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(1, 1)));
            Assert.assertFalse(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(0, 2)));
            Assert.assertFalse(userVmManagerImpl.isAnyVmVolumeUsingLocalStorage(mockVolumesForIsAnyVmVolumeUsingLocalStorageTest(0, 0)));
        }catch (NullPointerException npe) {
            npe.printStackTrace();
        }
    }

    private List<VolumeVO> mockVolumesForIsAllVmVolumesOnZoneWideStore(int nullPoolIdVolumes, int nullPoolVolumes, int zoneVolumes, int nonZoneVolumes) {
        List<VolumeVO> volumes = new ArrayList<>();
        for (int i=0; i< nullPoolIdVolumes + nullPoolVolumes + zoneVolumes + nonZoneVolumes; ++i) {
            VolumeVO vol = Mockito.mock(VolumeVO.class);
            volumes.add(vol);
            if (i < nullPoolIdVolumes) {
                Mockito.when(vol.getPoolId()).thenReturn(null);
                continue;
            }
            long index = i + 1;
            Mockito.when(vol.getPoolId()).thenReturn(index);
            if (i < nullPoolVolumes) {
                Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(null);
                continue;
            }
            StoragePoolVO storagePool = Mockito.mock(StoragePoolVO.class);
            Mockito.when(primaryDataStoreDao.findById(index)).thenReturn(storagePool);
            if (i < zoneVolumes) {
                Mockito.when(storagePool.getScope()).thenReturn(ScopeType.ZONE);
            } else {
                Mockito.when(storagePool.getScope()).thenReturn(ScopeType.CLUSTER);
            }
        }
        return volumes;
    }

    @Test
    public void testIsAllVmVolumesOnZoneWideStoreCombinations() {
        Assert.assertTrue(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 1, 0)));
        Assert.assertTrue(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 2, 0)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 1, 1)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 0, 0, 0)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(1, 0, 1, 1)));
        Assert.assertFalse(userVmManagerImpl.isAllVmVolumesOnZoneWideStore(mockVolumesForIsAllVmVolumesOnZoneWideStore(0, 1, 1, 1)));
    }

    private Pair<VMInstanceVO, Host> mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(boolean nullPlan, Host destinationHost) {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Host host = Mockito.mock(Host.class);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.when(hostDao.findById(1L)).thenReturn(Mockito.mock(HostVO.class));
        Mockito.when(virtualMachineManager.getMigrationDeployment(Mockito.any(VirtualMachine.class),
                        Mockito.any(Host.class), Mockito.nullable(Long.class),
                        Mockito.any(DeploymentPlanner.ExcludeList.class)))
                .thenReturn(Mockito.mock(DataCenterDeployment.class));
        if (!nullPlan) {
            try {
                DeployDestination destination = Mockito.mock(DeployDestination.class);
                Mockito.when(destination.getHost()).thenReturn(destinationHost);
                Mockito.when(planningManager.planDeployment(Mockito.any(VirtualMachineProfile.class),
                                Mockito.any(DataCenterDeployment.class), Mockito.any(DeploymentPlanner.ExcludeList.class),
                                Mockito.nullable(DeploymentPlanner.class)))
                        .thenReturn(destination);
            } catch (InsufficientServerCapacityException e) {
                fail("Failed to mock DeployDestination");
            }
        }
        return new Pair<>(vm, host);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testChooseVmMigrationDestinationUsingVolumePoolMapNullDestination() {
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(true, null);
        userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testChooseVmMigrationDestinationUsingVolumePoolMapNullHost() {
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(false, null);
        userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null);
    }

    @Test
    public void testChooseVmMigrationDestinationUsingVolumePoolMapValid() {
        Host destinationHost = Mockito.mock(Host.class);
        Pair<VMInstanceVO, Host> pair = mockObjectsForChooseVmMigrationDestinationUsingVolumePoolMapTest(false, destinationHost);
        Assert.assertEquals(destinationHost, userVmManagerImpl.chooseVmMigrationDestinationUsingVolumePoolMap(pair.first(), pair.second(), null));
    }

    @Test
    public void testUpdateVncPasswordIfItHasChanged() {
        String vncPassword = "12345678";
        userVmManagerImpl.updateVncPasswordIfItHasChanged(vncPassword, vncPassword, virtualMachineProfile);
        Mockito.verify(userVmDao, Mockito.never()).update(vmId, userVmVoMock);
    }

    @Test
    public void testUpdateVncPasswordIfItHasChangedNewPassword() {
        String vncPassword = "12345678";
        String newPassword = "87654321";
        Mockito.when(userVmVoMock.getId()).thenReturn(vmId);
        userVmManagerImpl.updateVncPasswordIfItHasChanged(vncPassword, newPassword, virtualMachineProfile);
        Mockito.verify(userVmDao).findById(vmId);
        Mockito.verify(userVmDao).update(vmId, userVmVoMock);
    }

    @Test
    public void testGetSecurityGroupIdList() {
        DeployVnfApplianceCmd cmd = Mockito.mock(DeployVnfApplianceCmd.class);
        Mockito.doReturn(new ArrayList<Long>()).when(userVmManagerImpl).getSecurityGroupIdList(cmd);
        SecurityGroupVO securityGroupVO = Mockito.mock(SecurityGroupVO.class);
        long securityGroupId = 100L;
        when(securityGroupVO.getId()).thenReturn(securityGroupId);
        Mockito.doReturn(securityGroupVO).when(vnfTemplateManager).createSecurityGroupForVnfAppliance(any(), any(), any(), any(DeployVnfApplianceCmd.class));

        List<Long> securityGroupIds = userVmManagerImpl.getSecurityGroupIdList(cmd, null, null, null);

        Assert.assertEquals(1, securityGroupIds.size());
        Assert.assertEquals(securityGroupId, securityGroupIds.get(0).longValue());

        Mockito.verify(userVmManagerImpl).getSecurityGroupIdList(cmd);
        Mockito.verify(vnfTemplateManager).createSecurityGroupForVnfAppliance(any(), any(), any(), any(DeployVnfApplianceCmd.class));
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPasswordTestTemplateIsNotPasswordEnabledReturnPreDefinedString() {
        String expected = "saved_password";

        Mockito.doReturn(false).when(vmTemplateVoMock).isEnablePassword();

        String result = userVmManagerImpl.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

        Assert.assertEquals(expected, result);
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPasswordTestVmHasPasswordReturnCurrentPassword() {
        String expected = "current_password";

        Mockito.doReturn(true).when(vmTemplateVoMock).isEnablePassword();
        Mockito.doReturn(expected).when(userVmVoMock).getDetail("password");

        String result = userVmManagerImpl.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

        Assert.assertEquals(expected, result);
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPasswordTestUserDefinedPasswordReturnNewPasswordAndSetVmPassword() {
        String expected = "new_password";

        Mockito.doReturn(true).when(vmTemplateVoMock).isEnablePassword();
        Mockito.doReturn(null).when(userVmVoMock).getDetail("password");
        Mockito.doCallRealMethod().when(userVmVoMock).setPassword(Mockito.any());
        Mockito.doCallRealMethod().when(userVmVoMock).getPassword();

        String result = userVmManagerImpl.getCurrentVmPasswordOrDefineNewPassword(expected, userVmVoMock, vmTemplateVoMock);

        Assert.assertEquals(expected, result);
        Assert.assertEquals(expected, userVmVoMock.getPassword());
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPasswordTestUserDefinedPasswordReturnRandomPasswordAndSetVmPassword() {
        String expected = "random_password";

        Mockito.doReturn(true).when(vmTemplateVoMock).isEnablePassword();
        Mockito.doReturn(null).when(userVmVoMock).getDetail("password");
        Mockito.doReturn(expected).when(managementServiceMock).generateRandomPassword();
        Mockito.doCallRealMethod().when(userVmVoMock).setPassword(Mockito.any());
        Mockito.doCallRealMethod().when(userVmVoMock).getPassword();

        String result = userVmManagerImpl.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

        Assert.assertEquals(expected, result);
        Assert.assertEquals(expected, userVmVoMock.getPassword());
    }

    @Test
    public void testSetVmRequiredFieldsForImportNotImport() {
        userVmManagerImpl.setVmRequiredFieldsForImport(false, userVmVoMock, _dcMock,
                Hypervisor.HypervisorType.VMware, Mockito.mock(HostVO.class), Mockito.mock(HostVO.class), VirtualMachine.PowerState.PowerOn);
        Mockito.verify(userVmVoMock, never()).setDataCenterId(anyLong());
    }


    @Test
    public void createVirtualMachineWithCloudRuntimeException() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = new DeployVMCmd();
        ReflectionTestUtils.setField(deployVMCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(deployVMCmd, "templateId", templateId);
        ReflectionTestUtils.setField(deployVMCmd, "serviceOfferingId", serviceOfferingId);
        deployVMCmd._accountService = accountService;

        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(serviceOffering.getState()).thenReturn(ServiceOffering.State.Active);

        when(entityManager.findById(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), nullable(Map.class));

        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(svcOfferingMock);
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        String vmId = "testId";
        CloudRuntimeException cre = new CloudRuntimeException("Error and CloudRuntimeException is thrown");
        cre.addProxyObject(vmId, "vmId");

        doThrow(cre).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());

        CloudRuntimeException creThrown = assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.createVirtualMachine(deployVMCmd));
        ArrayList<ExceptionProxyObject> proxyIdList = creThrown.getIdProxyList();
        assertNotNull(proxyIdList != null );
        assertTrue(proxyIdList.stream().anyMatch( p -> p.getUuid().equals(vmId)));
    }

    @Test
    public void testSetVmRequiredFieldsForImportFromLastHost() {
        HostVO lastHost = Mockito.mock(HostVO.class);
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(_dcMock.getId()).thenReturn(1L);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.when(lastHost.getId()).thenReturn(2L);
        userVmManagerImpl.setVmRequiredFieldsForImport(true, userVmVoMock, _dcMock,
                Hypervisor.HypervisorType.VMware, host, lastHost, VirtualMachine.PowerState.PowerOn);
        Mockito.verify(userVmVoMock).setLastHostId(2L);
        Mockito.verify(userVmVoMock).setState(VirtualMachine.State.Running);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVMNoVM() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();

        RestoreVMCmd cmd = Mockito.mock(RestoreVMCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getTemplateId()).thenReturn(2L);
        when(userVmDao.findById(vmId)).thenReturn(null);

        userVmManagerImpl.restoreVM(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testRestoreVMWithVolumeSnapshots() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        Mockito.lenient().doReturn(accountMock).when(callContextMock).getCallingAccount();
        Mockito.lenient().doNothing().when(accountManager).checkAccess(accountMock, null, true, userVmVoMock);

        RestoreVMCmd cmd = Mockito.mock(RestoreVMCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getTemplateId()).thenReturn(2L);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        Mockito.doReturn(false).when(userVmManagerImpl).isVMPartOfAnyCKSCluster(userVmVoMock);

        userVmManagerImpl.restoreVM(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVirtualMachineNoOwner() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(accountDao.findById(accountId)).thenReturn(null);

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testRestoreVirtualMachineOwnerDisabled() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(accountDao.findById(accountId)).thenReturn(callerAccount);
        when(callerAccount.getState()).thenReturn(Account.State.DISABLED);

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testRestoreVirtualMachineNotInRightState() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(userVmVoMock.getUuid()).thenReturn("a967643d-7633-4ab4-ac26-9c0b63f50cc1");
        when(accountDao.findById(accountId)).thenReturn(callerAccount);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Starting);

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVirtualMachineNoRootVolume() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long currentTemplateId = 1L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(userVmVoMock.getUuid()).thenReturn("a967643d-7633-4ab4-ac26-9c0b63f50cc1");
        when(accountDao.findById(accountId)).thenReturn(callerAccount);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getTemplateId()).thenReturn(currentTemplateId);

        VMTemplateVO currentTemplate = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findById(currentTemplateId)).thenReturn(currentTemplate);
        when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.ROOT)).thenReturn(new ArrayList<>());

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVirtualMachineMoreThanOneRootVolume() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long currentTemplateId = 1L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(userVmVoMock.getUuid()).thenReturn("a967643d-7633-4ab4-ac26-9c0b63f50cc1");
        when(accountDao.findById(accountId)).thenReturn(callerAccount);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getTemplateId()).thenReturn(currentTemplateId);

        VMTemplateVO currentTemplate = Mockito.mock(VMTemplateVO.class);
        when(currentTemplate.isDeployAsIs()).thenReturn(false);
        when(templateDao.findById(currentTemplateId)).thenReturn(currentTemplate);
        List<VolumeVO> volumes = new ArrayList<>();
        VolumeVO rootVolume1 = Mockito.mock(VolumeVO.class);
        volumes.add(rootVolume1);
        VolumeVO rootVolume2 = Mockito.mock(VolumeVO.class);
        volumes.add(rootVolume2);
        when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.ROOT)).thenReturn(volumes);

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testRestoreVirtualMachineWithVMSnapshots() throws ResourceUnavailableException, InsufficientCapacityException {
        long userId = 1L;
        long accountId = 2L;
        long currentTemplateId = 1L;
        long newTemplateId = 2L;
        when(accountMock.getId()).thenReturn(userId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getAccountId()).thenReturn(accountId);
        when(accountDao.findById(accountId)).thenReturn(callerAccount);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getTemplateId()).thenReturn(currentTemplateId);

        VMTemplateVO currentTemplate = Mockito.mock(VMTemplateVO.class);
        when(templateDao.findById(currentTemplateId)).thenReturn(currentTemplate);
        List<VolumeVO> volumes = new ArrayList<>();
        VolumeVO rootVolumeOfVm = Mockito.mock(VolumeVO.class);
        volumes.add(rootVolumeOfVm);
        when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.ROOT)).thenReturn(volumes);
        List<VMSnapshotVO> vmSnapshots = new ArrayList<>();
        VMSnapshotVO vmSnapshot = Mockito.mock(VMSnapshotVO.class);
        vmSnapshots.add(vmSnapshot);
        when(vmSnapshotDaoMock.findByVm(vmId)).thenReturn(vmSnapshots);

        userVmManagerImpl.restoreVirtualMachine(accountMock, vmId, newTemplateId, null, false, null);
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestDetailsConstantIsNotNullDoNothing() {
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(null, customParameters, detailsConstant, currentValue);
        }

        Assert.assertEquals("2048", customParameters.get(VmDetailConstants.MEMORY));
        Assert.assertEquals("4", customParameters.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertEquals("1000", customParameters.get(VmDetailConstants.CPU_SPEED));
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestNewValueIsNotNullDoNothing() {
        Map<String, String> details = new HashMap<>();
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(321, details, detailsConstant, currentValue);
        }

        Assert.assertNull(details.get(VmDetailConstants.MEMORY));
        Assert.assertNull(details.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertNull(details.get(VmDetailConstants.CPU_SPEED));
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestBothValuesAreNullKeepCurrentValue() {
        Map<String, String> details = new HashMap<>();
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(null, details, detailsConstant, currentValue);
        }

        Assert.assertEquals(details.get(VmDetailConstants.MEMORY), String.valueOf(currentValue));
        Assert.assertEquals(details.get(VmDetailConstants.CPU_NUMBER), String.valueOf(currentValue));
        Assert.assertEquals(details.get(VmDetailConstants.CPU_SPEED),String.valueOf(currentValue));
    }

    @Test
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecifiedTestNeitherValueIsNullDoNothing() {
        int currentValue = 123;

        for (String detailsConstant : detailsConstants) {
            userVmManagerImpl.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(321, customParameters, detailsConstant, currentValue);
        }

        Assert.assertEquals("2048", customParameters.get(VmDetailConstants.MEMORY));
        Assert.assertEquals("4", customParameters.get(VmDetailConstants.CPU_NUMBER));
        Assert.assertEquals("1000", customParameters.get(VmDetailConstants.CPU_SPEED));
    }

    @Test
    public void updateInstanceDetailsMapWithCurrentValuesForAbsentDetailsTestAllConstantsAreUpdated() {
        Mockito.doReturn(serviceOffering).when(_serviceOfferingDao).findById(Mockito.anyLong());
        Mockito.doReturn(1L).when(vmInstanceMock).getId();
        Mockito.doReturn(1L).when(vmInstanceMock).getServiceOfferingId();
        Mockito.doReturn(serviceOffering).when(_serviceOfferingDao).findByIdIncludingRemoved(Mockito.anyLong(), Mockito.anyLong());
        userVmManagerImpl.updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(null, vmInstanceMock, 0L);

        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.CPU_SPEED), Mockito.any());
        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.MEMORY), Mockito.any());
        Mockito.verify(userVmManagerImpl).addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Mockito.any(), Mockito.any(), Mockito.eq(VmDetailConstants.CPU_NUMBER), Mockito.any());
    }

    @Test
    public void testCheckVolumesLimits() {
        long diskOffId1 = 1L;
        DiskOfferingVO diskOfferingVO1 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId1)).thenReturn(diskOfferingVO1);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO1)).thenReturn(List.of("tag1", "tag2"));
        long diskOffId2 = 2L;
        DiskOfferingVO diskOfferingVO2 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId2)).thenReturn(diskOfferingVO2);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO2)).thenReturn(List.of("tag2"));
        long diskOffId3 = 3L;
        DiskOfferingVO diskOfferingVO3 = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOffId3)).thenReturn(diskOfferingVO3);
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTags(diskOfferingVO3)).thenReturn(new ArrayList<>());

        VolumeVO vol1 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol1.getDiskOfferingId()).thenReturn(diskOffId1);
        Mockito.when(vol1.getSize()).thenReturn(10L);
        Mockito.when(vol1.isDisplay()).thenReturn(true);
        VolumeVO undisplayedVolume = Mockito.mock(VolumeVO.class); // shouldn't be considered for limits
        Mockito.when(undisplayedVolume.isDisplay()).thenReturn(false);
        VolumeVO vol3 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol3.getDiskOfferingId()).thenReturn(diskOffId2);
        Mockito.when(vol3.getSize()).thenReturn(30L);
        Mockito.when(vol3.isDisplay()).thenReturn(true);
        VolumeVO vol4 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol4.getDiskOfferingId()).thenReturn(diskOffId3);
        Mockito.when(vol4.getSize()).thenReturn(40L);
        Mockito.when(vol4.isDisplay()).thenReturn(true);
        VolumeVO vol5 = Mockito.mock(VolumeVO.class);
        Mockito.when(vol5.getDiskOfferingId()).thenReturn(diskOffId1);
        Mockito.when(vol5.getSize()).thenReturn(50L);
        Mockito.when(vol5.isDisplay()).thenReturn(true);

        List<VolumeVO> volumes = List.of(vol1, undisplayedVolume, vol3, vol4, vol5);
        List<Reserver> reservations = new ArrayList<>();

        try (MockedConstruction<CheckedReservation> mockCheckedReservation = Mockito.mockConstruction(CheckedReservation.class)) {
            userVmManagerImpl.checkVolumesLimits(account, volumes, reservations);
            Assert.assertEquals(8, reservations.size());
        } catch (ResourceAllocationException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testValidateStrictHostTagCheckPass() {
        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO destinationHostVO = Mockito.mock(HostVO.class);

        Mockito.when(_serviceOfferingDao.findByIdIncludingRemoved(1L)).thenReturn(serviceOffering);
        Mockito.when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);

        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(vm.getTemplateId()).thenReturn(2L);

        Mockito.when(destinationHostVO.checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet())).thenReturn(true);

        userVmManagerImpl.validateStrictHostTagCheck(vm, destinationHostVO);

        Mockito.verify(
                destinationHostVO, times(1)
        ).checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateStrictHostTagCheckFail() {
        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO destinationHostVO = Mockito.mock(HostVO.class);

        Mockito.when(_serviceOfferingDao.findByIdIncludingRemoved(1L)).thenReturn(serviceOffering);
        Mockito.when(templateDao.findByIdIncludingRemoved(2L)).thenReturn(template);

        Mockito.when(vm.getServiceOfferingId()).thenReturn(1L);
        Mockito.when(vm.getTemplateId()).thenReturn(2L);

        Mockito.when(destinationHostVO.checkHostServiceOfferingAndTemplateTags(Mockito.any(ServiceOffering.class), Mockito.any(VirtualMachineTemplate.class), Mockito.anySet())).thenReturn(false);
        userVmManagerImpl.validateStrictHostTagCheck(vm, destinationHostVO);
    }

    public void testGetRootVolumeSizeForVmRestore() {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getSize()).thenReturn(10L * GiB_TO_BYTES);
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        Mockito.when(userVm.getId()).thenReturn(1L);
        DiskOffering diskOffering = Mockito.mock(DiskOffering.class);
        Mockito.when(diskOffering.isCustomized()).thenReturn(false);
        Mockito.when(diskOffering.getDiskSize()).thenReturn(8L * GiB_TO_BYTES);
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.ROOT_DISK_SIZE, "16");
        VMInstanceDetailVO vmRootDiskSizeDetail = Mockito.mock(VMInstanceDetailVO.class);
        Mockito.when(vmRootDiskSizeDetail.getValue()).thenReturn("20");
        Mockito.when(vmInstanceDetailsDao.findDetail(1L, VmDetailConstants.ROOT_DISK_SIZE)).thenReturn(vmRootDiskSizeDetail);
        Long actualSize = userVmManagerImpl.getRootVolumeSizeForVmRestore(null, template, userVm, diskOffering, details, false);
        Assert.assertEquals(16 * GiB_TO_BYTES, actualSize.longValue());
    }

    @Test
    public void testGetRootVolumeSizeForVmRestoreNullDiskOfferingAndEmptyDetails() {
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getSize()).thenReturn(10L * GiB_TO_BYTES);
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        Mockito.when(userVm.getId()).thenReturn(1L);
        DiskOffering diskOffering = null;
        Map<String, String> details = new HashMap<>();
        VMInstanceDetailVO vmRootDiskSizeDetail = Mockito.mock(VMInstanceDetailVO.class);
        Mockito.when(vmRootDiskSizeDetail.getValue()).thenReturn("20");
        Mockito.when(vmInstanceDetailsDao.findDetail(1L, VmDetailConstants.ROOT_DISK_SIZE)).thenReturn(vmRootDiskSizeDetail);
        Long actualSize = userVmManagerImpl.getRootVolumeSizeForVmRestore(null, template, userVm, diskOffering, details, false);
        Assert.assertEquals(20 * GiB_TO_BYTES, actualSize.longValue());
    }

    @Test
    public void checkExpungeVMPermissionTestAccountIsNotAdminConfigFalseThrowsPermissionDeniedException () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(false).when(userVmManagerImpl).getConfigAllowUserExpungeRecoverVm(Mockito.anyLong());

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsNotAdminConfigTrueNoApiAccessThrowsPermissionDeniedException () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).getConfigAllowUserExpungeRecoverVm(Mockito.anyLong());
        doThrow(PermissionDeniedException.class).when(accountManager).checkApiAccess(accountMock, "expungeVirtualMachine", null);

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsNotAdminConfigTrueHasApiAccessReturnNothing () {
        Mockito.doReturn(false).when(accountManager).isAdmin(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).getConfigAllowUserExpungeRecoverVm(Mockito.anyLong());

        userVmManagerImpl.checkExpungeVmPermission(accountMock, null);
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsAdminNoApiAccessThrowsPermissionDeniedException () {
        Mockito.doReturn(true).when(accountManager).isAdmin(Mockito.anyLong());
        doThrow(PermissionDeniedException.class).when(accountManager).checkApiAccess(accountMock, "expungeVirtualMachine", null);

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.checkExpungeVmPermission(accountMock, null));
    }
    @Test
    public void checkExpungeVmPermissionTestAccountIsAdminHasApiAccessReturnNothing () {
        Mockito.doReturn(true).when(accountManager).isAdmin(Mockito.anyLong());

        userVmManagerImpl.checkExpungeVmPermission(accountMock, null);
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("There is no VM by ID [%s].", 1L);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(null, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsRunningThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Unable to move VM [%s] in [%s] state.", userVmVoMock, VirtualMachine.State.Running);
        Mockito.doReturn(VirtualMachine.State.Running).when(userVmVoMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsSharedFileSystemInstanceThrowsInvalidParameterValueException() {
        Mockito.doReturn(UserVmManager.SHAREDFSVM).when(userVmVoMock).getUserVmType();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1L);
        });

        Assert.assertEquals("Migration is not supported for Shared FileSystem Instances.", assertThrows.getMessage());
    }

    @Test
    public void validateIfVmSupportsMigrationTestVmIsNotRunningDoesNotThrowInvalidParameterValueException() {
        userVmManagerImpl.validateIfVmSupportsMigration(userVmVoMock, 1L);
    }

    @Test
    public void validateOldAndNewAccountsTestBothAreValidDoNothing() {
        Account newAccount = Mockito.mock(Account.class);
        Mockito.doReturn(1L).when(newAccount).getAccountId();

        userVmManagerImpl.validateOldAndNewAccounts(accountMock, newAccount, 1L, "", 1L);
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Invalid old account [%s] for VM in domain [%s].", userVmVoMock.getAccountId(), assignVmCmdMock.getDomainId());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(null, accountMock, userVmVoMock.getAccountId(), "", assignVmCmdMock.getDomainId());
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestNewAccountIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Invalid new account [%s] for VM in domain [%s].", assignVmCmdMock.getAccountName(), assignVmCmdMock.getDomainId());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, null, 1L, assignVmCmdMock.getAccountName(), assignVmCmdMock.getDomainId());
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestNewAccountStateIsDisabledThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("The new account owner [%s] is disabled.", accountMock);

        Mockito.doReturn(Account.State.DISABLED).when(accountMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, accountMock, 1L, "", 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsTheSameAsNewAccountThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("The new account [%s] is the same as the old account.", accountMock);

        Mockito.doReturn(Account.State.ENABLED).when(accountMock).getState();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateOldAndNewAccounts(accountMock, accountMock, 1L, "", 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateOldAndNewAccountsTestOldAccountIsNotTheSameAsNewAccountDoesNotThrowInvalidParameterValueException() {
        AccountVO oldAccount = new AccountVO();
        Mockito.doReturn(1L).when(accountMock).getAccountId();

        userVmManagerImpl.validateOldAndNewAccounts(oldAccount, accountMock, 1L, "", 1L);
    }

    @Test
    public void checkCallerAccessToAccountsTestCallsCheckAccessToOldAccountAndNewAccount() {
        AccountVO oldAccount = new AccountVO();

        userVmManagerImpl.checkCallerAccessToAccounts(callerAccount, oldAccount, accountMock);

        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, oldAccount);
        Mockito.verify(accountManager).checkAccess(callerAccount, null, true, accountMock);
    }

    @Test
    public void validateIfVmHasNoRulesTestPortForwardingRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove any Port Forwarding rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(portForwardingRulesListMock).when(portForwardingRulesDaoMock).listByVm(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestStaticNatRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the StaticNat rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(firewallRuleVoListMock).when(firewallRulesDaoMock).listStaticNatByVmId(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestLoadBalancingRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the Load Balancing rules for VM [%s] before assigning it to another user.", userVmVoMock);

        Mockito.doReturn(loadBalancerVmMapVoListMock).when(loadBalancerVmMapDaoMock).listByInstanceId(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestOneToOneNatRulesExistThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Remove the One to One Nat rule for VM [%s] for IP [%s].", userVmVoMock, ipAddressVoMock.toString());

        LinkedList<IPAddressVO> ipAddressVoList = new LinkedList<>();

        Mockito.doReturn(ipAddressVoList).when(ipAddressDaoMock).findAllByAssociatedVmId(Mockito.anyLong());
        ipAddressVoList.add(ipAddressVoMock);
        Mockito.doReturn(true).when(ipAddressVoMock).isOneToOneNat();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1L);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVmHasNoRulesTestOneToOneNatRulesDoNotExistDoesNotThrowInvalidParameterValueException() {
        userVmManagerImpl.validateIfVmHasNoRules(userVmVoMock, 1L);
    }

    @Test
    public void verifyResourceLimitsForAccountAndStorageTestCountOnlyRunningVmsInResourceLimitationIsTrueDoesNotCallVmResourceLimitCheck() throws ResourceAllocationException {
        List<Reserver> reservations = new ArrayList<>();
        LinkedList<VolumeVO> volumeVoList = new LinkedList<>();
        Mockito.doReturn(true).when(userVmManagerImpl).countOnlyRunningVmsInResourceLimitation();

        userVmManagerImpl.verifyResourceLimitsForAccountAndStorage(accountMock, userVmVoMock, serviceOfferingVoMock, volumeVoList, virtualMachineTemplateMock, reservations);

        Mockito.verify(resourceLimitMgr, Mockito.never()).checkVmResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(resourceLimitMgr, Mockito.never()).checkVolumeResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void verifyResourceLimitsForAccountAndStorageTestCountOnlyRunningVmsInResourceLimitationIsFalseCallsVmResourceLimitCheck() throws ResourceAllocationException {
        List<Reserver> reservations = new ArrayList<>();
        LinkedList<VolumeVO> volumeVoList = new LinkedList<>();
        Mockito.doReturn(false).when(userVmManagerImpl).countOnlyRunningVmsInResourceLimitation();

        userVmManagerImpl.verifyResourceLimitsForAccountAndStorage(accountMock, userVmVoMock, serviceOfferingVoMock, volumeVoList, virtualMachineTemplateMock, reservations);

        Mockito.verify(resourceLimitMgr).checkVmResourceLimit(Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).checkVolumesLimits(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void validateIfNewOwnerHasAccessToTemplateTestTemplateIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Template for VM [%s] cannot be found.", userVmVoMock.getUuid());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfNewOwnerHasAccessToTemplate(userVmVoMock, accountMock, null);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfNewOwnerHasAccessToTemplateTestCallCheckAccessWhenTemplateIsNotPublic() {
        userVmManagerImpl.validateIfNewOwnerHasAccessToTemplate(userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(accountManager).checkAccess(accountMock, SecurityChecker.AccessType.UseEntry, true, virtualMachineTemplateMock);
    }

    @Test
    public void updateVmOwnerTestCallsSetAccountIdSetDomainIdAndPersist() {
        userVmManagerImpl.updateVmOwner(accountMock, userVmVoMock, 1L, 1L);

        Mockito.verify(userVmVoMock).setAccountId(Mockito.anyLong());
        Mockito.verify(userVmVoMock).setDomainId(Mockito.anyLong());
        Mockito.verify(userVmDao).persist(userVmVoMock);
    }

    @Test
    public void updateVmNetworkTestCallsUpdateBasicTypeNetworkForVmIfBasicTypeZone() throws InsufficientCapacityException, ResourceAllocationException {
        Mockito.doReturn(_dcMock).when(_dcDao).findById(Mockito.anyLong());
        Mockito.doReturn(DataCenter.NetworkType.Basic).when(_dcMock).getNetworkType();
        Mockito.doNothing().when(userVmManagerImpl).updateBasicTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());

        userVmManagerImpl.updateVmNetwork(assignVmCmdMock, callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(userVmManagerImpl).updateBasicTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any());
    }

    @Test
    public void updateVmNetworkTestCallsUpdateAdvancedTypeNetworkForVmIfNotBasicTypeZone() throws InsufficientCapacityException, ResourceAllocationException {
        Mockito.doReturn(_dcMock).when(_dcDao).findById(Mockito.anyLong());
        Mockito.doReturn(DataCenter.NetworkType.Advanced).when(_dcMock).getNetworkType();
        Mockito.doNothing().when(userVmManagerImpl).updateAdvancedTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());

        userVmManagerImpl.updateVmNetwork(assignVmCmdMock, callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock);

        Mockito.verify(userVmManagerImpl).updateAdvancedTypeNetworkForVm(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void cleanupOfOldOwnerNicsForNetworkTestCallsCleanupNicsAndRemoveNics() {
        userVmManagerImpl.cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);

        Mockito.verify(_networkMgr).cleanupNics(virtualMachineProfileMock);
        Mockito.verify(_networkMgr).removeNics(virtualMachineProfileMock);
    }

    @Test
    public void addDefaultNetworkToNetworkListTestDefaultNetworkIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Unable to find a default network to start a VM.";

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addDefaultNetworkToNetworkList(networkVoListMock, null);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addDefaultNetworkToNetworkListTestDefaultNetworkIsNotNullAddNetworkToNetworkList() {
        userVmManagerImpl.addDefaultNetworkToNetworkList(networkVoListMock, networkMock);

        Mockito.verify(networkVoListMock).add(Mockito.any());
    }

    @Test
    public void allocateNetworksForVmTestCallsNetworkManagerAllocate() throws InsufficientCapacityException {
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();

        Mockito.doReturn(userVmVoMock).when(virtualMachineManager).findById(Mockito.anyLong());

        userVmManagerImpl.allocateNetworksForVm(userVmVoMock, networks);

        Mockito.verify(_networkMgr).allocate(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void addSecurityGroupsToVmTestIsVmWareAndSecurityGroupIdListIsNotNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Security group feature is not supported for VMWare hypervisor.";
        LinkedList<Long> securityGroupIdList = new LinkedList<>();

        Mockito.doReturn(Hypervisor.HypervisorType.VMware).when(virtualMachineTemplateMock).getHypervisorType();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, networkMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addSecurityGroupsToVmTestIsNotVmWareDefaultNetworkIsNullAndNetworkModelCanAddDefaultSecurityGroupCallsAddDefaultSecurityGroupToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = new LinkedList<>();

        Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(virtualMachineTemplateMock).getHypervisorType();
        Mockito.doReturn(true).when(networkModel).canAddDefaultSecurityGroup();
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());

        userVmManagerImpl.addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, null);

        Mockito.verify(userVmManagerImpl).addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);
        Mockito.verify(securityGroupManagerMock).addInstanceToGroups(Mockito.any(), Mockito.any());
    }

    @Test
    public void addNetworksToNetworkIdListTestCallsKeepOldSharedNetworkForVmAndAddAdditionalNetworksToVm() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        userVmManagerImpl.addNetworksToNetworkIdList(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics,
                requestedIPv6ForNics);

        Mockito.verify(userVmManagerImpl).keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        Mockito.verify(userVmManagerImpl).addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
    }

    @Test
    public void getOfferingWithRequiredAvailabilityForNetworkCreationTestRequiredOfferingsListHasNoOfferingsThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Unable to find network offering with availability [%s] to automatically create the network as a part of VM creation.",
                NetworkOffering.Availability.Required);
        LinkedList<NetworkOfferingVO> requiredOfferings = new LinkedList<>();

        Mockito.doReturn(requiredOfferings).when(networkOfferingDaoMock).listByAvailability(NetworkOffering.Availability.Required, false);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.getOfferingWithRequiredAvailabilityForNetworkCreation();
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void getOfferingWithRequiredAvailabilityForNetworkCreationTestFirstOfferingIsNotEnabledThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Required network offering ID [%s] is not in [%s] state.", 1L, NetworkOffering.State.Enabled);

        Mockito.doReturn(networkOfferingVoListMock).when(networkOfferingDaoMock).listByAvailability(NetworkOffering.Availability.Required, false);
        Mockito.doReturn(networkOfferingVoMock).when(networkOfferingVoListMock).get(0);

        Mockito.doReturn(NetworkOffering.State.Disabled).when(networkOfferingVoMock).getState();

        Mockito.doReturn(1L).when(networkOfferingVoMock).getId();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.getOfferingWithRequiredAvailabilityForNetworkCreation();
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test(expected = CloudRuntimeException.class)
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkIsEmptyThrowsException() throws InsufficientCapacityException,
            ResourceAllocationException {

        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        LinkedList<? extends Network> virtualNetworks = new LinkedList<>();

        Mockito.doReturn(virtualNetworks).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);
    }

    @Test
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkHasMultipleNetworksThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("More than one default isolated network has been found for account [%s]; please specify networkIDs.", accountMock);
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        LinkedList<NetworkVO> virtualNetworks = new LinkedList<>();

        Mockito.doReturn(virtualNetworks).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        virtualNetworks.add(networkMock);
        virtualNetworks.add(networkMock);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void selectApplicableNetworkToCreateVmTestVirtualNetworkHasOneNetworkCallsNetworkDaoFindById() throws InsufficientCapacityException, ResourceAllocationException {
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();

        Mockito.doReturn(networkVoListMock).when(networkModel).listNetworksForAccount(Mockito.anyLong(), Mockito.anyLong(), Mockito.any());

        Mockito.doReturn(false).when(networkVoListMock).isEmpty();
        Mockito.doReturn(1).when(networkVoListMock).size();
        Mockito.doReturn(networkMock).when(networkVoListMock).get(0);

        userVmManagerImpl.selectApplicableNetworkToCreateVm(accountMock, _dcMock, applicableNetworks);

        Mockito.verify(_networkDao).findById(Mockito.anyLong());
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsNullCallsCreateSecurityGroup() {
        String expected = "";
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<>());

        Mockito.doReturn(null).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME,
                SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, 1L, 1L, expected);

        Mockito.doReturn(1L).when(accountMock).getDomainId();
        Mockito.doReturn(1L).when(accountMock).getId();
        Mockito.doReturn(expected).when(accountMock).getAccountName();
        Mockito.doReturn(1L).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupManagerMock).createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, 1L, 1L, expected);
        Mockito.verify(securityGroupIdList).add(1L);
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsPresentDoesNotCallAddIdToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<>());

        securityGroupIdList.addFirst(1L);
        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(1L).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupIdList, Mockito.never()).add(Mockito.anyLong());
    }

    @Test
    public void addDefaultSecurityGroupToSecurityGroupIdListTestDefaultGroupIsNotPresentCallsAddIdToSecurityGroupIdList() {
        LinkedList<Long> securityGroupIdList = Mockito.spy(new LinkedList<>());

        Mockito.doReturn(securityGroupVoMock).when(securityGroupManagerMock).getDefaultSecurityGroup(Mockito.anyLong());
        Mockito.doReturn(1L).when(securityGroupVoMock).getId();

        userVmManagerImpl.addDefaultSecurityGroupToSecurityGroupIdList(accountMock, securityGroupIdList);

        Mockito.verify(securityGroupIdList).add(1L);
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsNotNullOrEmptyDoesNotCallFindDefaultNicForVm() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsNullCallsFindDefaultNicForVm() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestNetworkIdListIsEmptyCallsFindDefaultNicForVm() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findDefaultNicForVM(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestDefaultNicOldIsNullDoesNotCallNetworkDaoFindById() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        Mockito.doReturn(null).when(nicDao).findDefaultNicForVM(Mockito.anyLong());

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(_networkDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestDefaultNicOldIsNotNullCallsNetworkDaoFindById() {
        HashSet<NetworkVO> applicableNetworks = new HashSet<>();
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(_networkDao).findById(Mockito.anyLong());
    }

    @Test
    public void keepOldSharedNetworkForVmTestAccountCanNotUseNetworkDoesNotAddNetworkToApplicableNetworks() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(false).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(applicableNetworks, Mockito.never()).add(Mockito.any());
    }

    @Test
    public void keepOldSharedNetworkForVmTestAccountCanUseNetworkAddsNetworkToApplicableNetworks() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.keepOldSharedNetworkForVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(applicableNetworks).add(Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIdListIsNullDoesNotCallCheckNetworkPermissions() {
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, null, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(networkModel, Mockito.never()).checkNetworkPermissions(Mockito.any(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIdListIsEmptyDoesNotCallCheckNetworkPermissions() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(networkModel, Mockito.never()).checkNetworkPermissions(Mockito.any(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNullThrowsInvalidParameterValueException() {
        String expectedMessage = "Unable to find specified Network ID.";
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkOfferingIsSystemOnlyThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Specified network [%s] is system only and cannot be used for VM deployment.", networkMock);
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(true).when(networkOfferingVoMock).isSystemOnly();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNotSharedGuestTypeDoesNotCallNicDaoFindByNtwkIdAndInstanceId() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void addAdditionalNetworksToVmTestNetworkIsNotDomainAclTypeDoesNotCallNicDaoFindByNtwkIdAndInstanceId() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Account).when(networkMock).getAclType();

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao, Mockito.never()).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void addAdditionalNetworksToVmTestOldNicIsNullDoesNotPutIpv4InRequestIpv4ForNics() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = Mockito.spy(new HashMap<>());
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(null).when(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(requestedIPv4ForNics, Mockito.never()).put(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void addAdditionalNetworksToVmTestOldNicIsNotNullPutsIpv4InRequestIpv4ForNics() {
        LinkedList<Long> networkIdList = new LinkedList<>();
        HashSet<NetworkVO> applicableNetworks = Mockito.spy(new HashSet<>());
        HashMap<Long, String> requestedIPv4ForNics = Mockito.spy(new HashMap<>());
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();

        networkIdList.add(1L);

        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(networkOfferingVoMock).when(entityManager).findById(Mockito.any(), Mockito.anyLong());
        Mockito.doReturn(false).when(networkOfferingVoMock).isSystemOnly();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(new NicVO()).when(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());

        userVmManagerImpl.addAdditionalNetworksToVm(userVmVoMock, accountMock, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);

        Mockito.verify(nicDao).findByNtwkIdAndInstanceId(Mockito.anyLong(), Mockito.anyLong());
        Mockito.verify(requestedIPv4ForNics).put(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void createApplicableNetworkToCreateVmTestPhysicalNetworkIsNullThrowsInvalidParameterValueException() {
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();

        String expectedMessage = String.format("Unable to find physical network with ID [%s] and tag [%s].", 0L, null);
        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void createApplicableNetworkToCreateVmTestFirstNetworkOfferingIsPersistentCallsImplementNetwork() throws InsufficientCapacityException, ResourceAllocationException {
        PhysicalNetworkVO physicalNetworkVo = new PhysicalNetworkVO();

        Mockito.doReturn(physicalNetworkVo).when(physicalNetworkDaoMock).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(networkOfferingVoMock).isPersistent();
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();
        Mockito.doReturn(networkMock).when(userVmManagerImpl).implementNetwork(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(networkMock).when(_networkMgr).createGuestNetwork(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);

        Mockito.verify(userVmManagerImpl).implementNetwork(callerAccount, _dcMock, networkMock);
    }

    @Test
    public void createApplicableNetworkToCreateVmTestFirstNetworkOfferingIsNotPersistentDoesNotCallImplementNetwork() throws InsufficientCapacityException,
            ResourceAllocationException {

        PhysicalNetworkVO physicalNetworkVo = new PhysicalNetworkVO();

        Mockito.doReturn(physicalNetworkVo).when(physicalNetworkDaoMock).findById(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkMgr).createGuestNetwork(Mockito.anyLong(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(networkOfferingVoMock).when(userVmManagerImpl).getOfferingWithRequiredAvailabilityForNetworkCreation();
        Mockito.doReturn(1L).when(networkMock).getId();
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());

        userVmManagerImpl.createApplicableNetworkToCreateVm(accountMock, _dcMock);

        Mockito.verify(userVmManagerImpl, Mockito.never()).implementNetwork(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void canAccountUseNetworkTestNetworkIsNullReturnFalse() {
        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, null);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkAclTypeIsNotDomainReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Account).when(networkMock).getAclType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsNotSharedOrL2ReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.Isolated).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsSharedReturnTrue() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.Shared).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Mockito.verify(networkModel).checkNetworkPermissions(accountMock, networkMock);
        Assert.assertTrue(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestNetworkGuestTypeIsL2ReturnTrue() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Mockito.verify(networkModel).checkNetworkPermissions(accountMock, networkMock);
        Assert.assertTrue(canAccountUseNetwork);
    }

    @Test
    public void canAccountUseNetworkTestPermissionDeniedExceptionThrownReturnFalse() {
        Mockito.doReturn(ControlledEntity.ACLType.Domain).when(networkMock).getAclType();
        Mockito.doReturn(Network.GuestType.L2).when(networkMock).getGuestType();

        doThrow(PermissionDeniedException.class).when(networkModel).checkNetworkPermissions(accountMock, networkMock);

        boolean canAccountUseNetwork = userVmManagerImpl.canAccountUseNetwork(accountMock, networkMock);

        Assert.assertFalse(canAccountUseNetwork);
    }

    @Test
    public void implementNetworkTestImplementedNetworkIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1L).when(callContextMock).getCallingUserId();

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(null).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkFirstIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1L).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(null).when(implementedNetwork).first();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkSecondIsNullReturnCurrentNewNetwork() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1L).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(networkMock).when(implementedNetwork).first();
            Mockito.doReturn(null).when(implementedNetwork).second();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, currentNetwork);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkSecondIsNotNullReturnImplementedNetworkSecond() throws ResourceUnavailableException, InsufficientCapacityException {
        CallContext callContextMock = Mockito.mock(CallContext.class);
        NetworkVO currentNetwork = Mockito.mock(NetworkVO.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1L).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            Mockito.doReturn(networkMock).when(implementedNetwork).first();
            Mockito.doReturn(networkMock).when(implementedNetwork).second();
            Mockito.doReturn(implementedNetwork).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            Network newNetwork = userVmManagerImpl.implementNetwork(accountMock, _dcMock, currentNetwork);

            Assert.assertEquals(newNetwork, networkMock);
        }
    }

    @Test
    public void implementNetworkTestImplementedNetworkCatchException() throws ResourceUnavailableException, InsufficientCapacityException {
        String expectedMessage = String.format("Failed to implement network [%s] elements and resources as a part of network provision.", networkMock);

        CallContext callContextMock = Mockito.mock(CallContext.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            Mockito.when(CallContext.current()).thenReturn(callContextMock);

            Mockito.doReturn(1L).when(callContextMock).getCallingUserId();

            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = Mockito.mock(Pair.class);

            Mockito.doReturn(callerUser).when(userDao).findById(Mockito.anyLong());
            doThrow(InvalidParameterValueException.class).when(_networkMgr).implementNetwork(Mockito.anyLong(), Mockito.any(), Mockito.any());

            CloudRuntimeException assertThrows = Assert.assertThrows(expectedCloudRuntimeException, () -> {
                userVmManagerImpl.implementNetwork(accountMock, _dcMock, networkMock);
            });

            Assert.assertEquals(expectedMessage, assertThrows.getMessage());
        }
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsNotEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "Cannot move VM with Network IDs; this is a basic zone VM.";
        LinkedList<Long> networkIdList = new LinkedList<>();
        LinkedList<Long> securityGroupIdList = new LinkedList<>();

        networkIdList.add(1L);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, networkIdList,
                    securityGroupIdList);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsNullCallsCleanupOfOldOwnerNicsForNetworkAddDefaultNetworkToNetworkListAllocateNetworksForVmAndAddSecurityGroupsToVm()
            throws InsufficientCapacityException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);

        Mockito.doReturn(networkMock).when(networkModel).getExclusiveGuestNetwork(Mockito.anyLong());

        userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, null,
                securityGroupIdList);

        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);
        Mockito.verify(userVmManagerImpl).addDefaultNetworkToNetworkList(anyList(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock,virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateBasicTypeNetworkForVmTestNetworkIdListIsEmptyCallsCleanupOfOldOwnerNicsForNetworkAddDefaultNetworkToNetworkListAllocateNetworksForVmAndAddSecurityGroupsToVm()
            throws InsufficientCapacityException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        Mockito.doReturn(networkMock).when(networkModel).getExclusiveGuestNetwork(Mockito.anyLong());

        userVmManagerImpl.updateBasicTypeNetworkForVm(userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock, networkIdList,
                securityGroupIdList);

        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(virtualMachineProfileMock);
        Mockito.verify(userVmManagerImpl).addDefaultNetworkToNetworkList(anyList(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock,virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsEnabledApplicableNetworksIsEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "No network is specified, please specify one when you move the VM. For now, please add a network to VM on NICs tab.";
        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        Mockito.doReturn(true).when(networkModel).checkSecurityGroupSupportForNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock,
                    _dcMock, networkIdList, securityGroupIdList);
        });

        Mockito.verify(securityGroupManagerMock).removeInstanceFromGroups(Mockito.any());
        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsEnabledApplicableNetworksIsNotEmptyCallsAllocateNetworksForVm() throws InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        Mockito.doReturn(true).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(securityGroupManagerMock).removeInstanceFromGroups(Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addSecurityGroupsToVm(accountMock, userVmVoMock, virtualMachineTemplateMock, securityGroupIdList, networkMock);
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledSecurityGroupIdListIsNotEmptyThrowsInvalidParameterValueException() {
        String expectedMessage = "Cannot move VM with security groups; security group feature is not enabled in this zone.";
        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        securityGroupIdList.add(1L);

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock,
                    _dcMock, networkIdList, securityGroupIdList);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledApplicableNetworksIsEmptyCallsSelectApplicableNetworkToCreateVm() throws InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        Mockito.doReturn(networkMock).when(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.doNothing().when(userVmManagerImpl).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);
        Mockito.doReturn(true).when(securityGroupIdList).isEmpty();

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(userVmManagerImpl).addNetworksToNetworkIdList(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyMap(), Mockito.anyMap());
        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(Mockito.any());
        Mockito.verify(userVmManagerImpl).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
    }

    @Test
    public void updateAdvancedTypeNetworkForVmTestSecurityGroupIsNotEnabledApplicableNetworksIsNotEmptyDoesNotCallSelectApplicableNetworkToCreateVm()
            throws InsufficientCapacityException, ResourceAllocationException {

        LinkedList<Long> securityGroupIdList = Mockito.mock(LinkedList.class);
        LinkedList<Long> networkIdList = new LinkedList<>();

        Mockito.doReturn(false).when(networkModel).checkSecurityGroupSupportForNetwork(accountMock, _dcMock, networkIdList, securityGroupIdList);
        Mockito.doReturn(true).when(securityGroupIdList).isEmpty();

        Mockito.doReturn(new NicVO()).when(nicDao).findDefaultNicForVM(Mockito.anyLong());
        Mockito.doReturn(networkMock).when(_networkDao).findById(Mockito.anyLong());
        Mockito.doReturn(true).when(userVmManagerImpl).canAccountUseNetwork(accountMock, networkMock);

        userVmManagerImpl.updateAdvancedTypeNetworkForVm(callerAccount, userVmVoMock, accountMock, virtualMachineTemplateMock, virtualMachineProfileMock, _dcMock,
                networkIdList, securityGroupIdList);

        Mockito.verify(userVmManagerImpl).addNetworksToNetworkIdList(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyMap(), Mockito.anyMap());
        Mockito.verify(userVmManagerImpl).cleanupOfOldOwnerNicsForNetwork(Mockito.any());
        Mockito.verify(userVmManagerImpl, Mockito.never()).selectApplicableNetworkToCreateVm(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(userVmManagerImpl).addNicsToApplicableNetworksAndReturnDefaultNetwork(Mockito.any(), Mockito.anyMap(), Mockito.anyMap(), Mockito.any());
        Mockito.verify(userVmManagerImpl).allocateNetworksForVm(Mockito.any(), Mockito.any());
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsEmptyReturnNull() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Assert.assertNull(defaultNetwork);
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsNotEmptyReturnFirstElement() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = Mockito.spy(LinkedHashMap.class);

        applicableNetworks.add(networkMock);

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Mockito.verify(networks).put(Mockito.any(), Mockito.any());
        Assert.assertEquals(defaultNetwork, networkMock);
    }

    @Test
    public void addNicsToApplicableNetworksAndReturnDefaultNetworkTestApplicableNetworkIsNotEmptyPutTwoNetworksInNetworksMapAndReturnFirst() {
        HashMap<Long, String> requestedIPv4ForNics = new HashMap<>();
        HashMap<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = Mockito.spy(LinkedHashMap.class);

        NetworkVO networkVoMock2 = Mockito.mock(NetworkVO.class);
        applicableNetworks.add(networkMock);
        applicableNetworks.add(networkVoMock2);

        NetworkVO defaultNetwork = userVmManagerImpl.addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        Mockito.verify(networks, times(2)).put(Mockito.any(), Mockito.any());
        Assert.assertEquals(defaultNetwork, networkMock);
    }

    @Test
    public void validateIfVolumesHaveNoSnapshotsTestVolumeHasSnapshotsThrowsInvalidParameterException() {
        String expectedMessage = String.format("Snapshots exist for volume [%s]. Detach volume or remove snapshots for the volume before assigning VM to another user.",
                volumeVOMock.getName());

        LinkedList<VolumeVO> volumes = new LinkedList<>();
        volumes.add(volumeVOMock);
        LinkedList<SnapshotVO> snapshots = new LinkedList<>();
        snapshots.add(snapshotVoMock);

        Mockito.doReturn(snapshots).when(snapshotDaoMock).listByStatusNotIn(Mockito.anyLong(), Mockito.any(), Mockito.any());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.validateIfVolumesHaveNoSnapshots(volumes);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfVolumesHaveNoSnapshotsTestVolumeHasNoSnapshotsDoesNotThrowInvalidParameterException() {
        LinkedList<VolumeVO> volumes = new LinkedList<>();
        volumes.add(volumeVOMock);
        LinkedList<SnapshotVO> snapshots = new LinkedList<>();

        Mockito.doReturn(snapshots).when(snapshotDaoMock).listByStatusNotIn(Mockito.anyLong(), Mockito.any(), Mockito.any());

        userVmManagerImpl.validateIfVolumesHaveNoSnapshots(volumes);
    }

    @Test
    public void moveVmToUserTestCallerIsNotRootAdminAndDomainAdminThrowsInvalidParameterValueException() {
        String expectedMessage = String.format("Only root or domain admins are allowed to assign VMs. Caller [%s] is of type [%s].", callerAccount, callerAccount.getType());

        Mockito.doReturn(false).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(false).when(accountManager).isDomainAdmin(Mockito.anyLong());

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.moveVmToUser(assignVmCmdMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void moveVmToUserTestValidateVmExistsAndIsNotRunningThrowsInvalidParameterValueException() {
        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVmSupportsMigration(Mockito.any(), Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestValidateAccountsAndCallerAccessToThemThrowsInvalidParameterValueException() {
        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestProjectIdProvidedAndDomainIdIsNullThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        String expectedMessage = "Please provide a valid domain ID; cannot assign VM to a project if domain ID is NULL.";

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(1L).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(null).when(assignVmCmdMock).getDomainId();

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedInvalidParameterValueException, () -> {
            userVmManagerImpl.moveVmToUser(assignVmCmdMock);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void moveVmToUserTestValidateIfVmHasNoRulesThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVmHasNoRules(Mockito.any(), Mockito.anyLong());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestSnapshotsForVolumeExistThrowsInvalidParameterValueException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();
        volumes.add(volumeVOMock);

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfVolumesHaveNoSnapshots(Mockito.any());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestVerifyResourceLimitsForAccountAndStorageThrowsResourceAllocationException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(ResourceAllocationException.class).when(userVmManagerImpl).verifyResourceLimitsForAccountAndStorage(Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        Assert.assertThrows(ResourceAllocationException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestVerifyValidateIfNewOwnerHasAccessToTemplateThrowsInvalidParameterValueException() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(InvalidParameterValueException.class).when(userVmManagerImpl).validateIfNewOwnerHasAccessToTemplate(Mockito.any(), Mockito.any(), Mockito.any());

        Assert.assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void moveVmToUserTestAccountManagerCheckAccessThrowsPermissionDeniedException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        Mockito.doReturn(true).when(accountManager).isRootAdmin(Mockito.anyLong());
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(Mockito.anyLong());
        Mockito.doReturn(null).when(assignVmCmdMock).getProjectId();
        Mockito.doReturn(volumes).when(volumeDaoMock).findByInstance(Mockito.anyLong());
        Mockito.doReturn(accountMock).when(accountManager).finalizeOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doReturn(domainVoMock).when(domainDaoMock).findById(Mockito.anyLong());

        configureDoNothingForMethodsThatWeDoNotWantToTest();

        doThrow(PermissionDeniedException.class).when(accountManager).checkAccess(Mockito.any(Account.class), Mockito.any(Domain.class));

        Assert.assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.moveVmToUser(assignVmCmdMock));
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestUpdateVmNetworkThrowsInsufficientCapacityException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            doThrow(InsufficientAddressCapacityException.class).when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any());

            Assert.assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock,
                    userVmVoMock, serviceOfferingVoMock, volumes, virtualMachineTemplateMock, 1L));

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestUpdateVmNetworkThrowsResourceAllocationException() throws ResourceUnavailableException, InsufficientCapacityException,
            ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            doThrow(ResourceAllocationException.class).when(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any());

            Assert.assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock,
                    userVmVoMock, serviceOfferingVoMock, volumes, virtualMachineTemplateMock, 1L));

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestResourceCountRunningVmsOnlyEnabledIsFalseCallsResourceCountIncrement() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();


        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
            Mockito.doReturn(false).when(userVmManagerImpl).isResourceCountRunningVmsOnlyEnabled();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock, userVmVoMock, serviceOfferingVoMock, volumes,
                    virtualMachineTemplateMock, 1L);

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        }
    }

    @Test
    public void executeStepsToChangeOwnershipOfVmTestResourceCountRunningVmsOnlyEnabledIsTrueDoesNotCallResourceCountIncrement() throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException {

        LinkedList<VolumeVO> volumes = new LinkedList<>();

        try (MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
            Mockito.doReturn(true).when(userVmManagerImpl).isResourceCountRunningVmsOnlyEnabled();

            configureDoNothingForMethodsThatWeDoNotWantToTest();

            userVmManagerImpl.executeStepsToChangeOwnershipOfVm(assignVmCmdMock, callerAccount, accountMock, accountMock, userVmVoMock, serviceOfferingVoMock, volumes,
                    virtualMachineTemplateMock, 1L);

            Mockito.verify(userVmManagerImpl).resourceCountDecrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl).updateVmOwner(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVolumesOwner(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong());
            Mockito.verify(userVmManagerImpl).updateVmNetwork(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(userVmManagerImpl, Mockito.never()).resourceCountIncrement(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        }
    }

    @Test
    public void validateStorageAccessGroupsOnHostsMatchingSAGsNoException() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag2", "sag3"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test(expected = CloudRuntimeException.class)
    public void validateSAGsOnHostsNonMatchingSAGsThrowsException() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag3"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);
    }

    @Test
    public void validateEmptyStorageAccessGroupOnHosts() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test
    public void validateSAGsOnHostsNullStorageAccessGroups() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(null);
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(null);

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }

    @Test(expected = CloudRuntimeException.class)
    public void validateSAGsOnDestHostNullStorageAccessGroups() {
        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(new String[]{"sag1", "sag2"});
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(null);

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);
    }

    @Test
    public void validateNullStorageAccessGroupsOnSrcHost() {

        Host srcHost = Mockito.mock(Host.class);
        Host destHost = Mockito.mock(Host.class);

        Mockito.when(srcHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, srcHost.getId())).thenReturn(null);
        when(storageManager.getStorageAccessGroups(null, null, null, destHost.getId())).thenReturn(new String[]{"sag1", "sag2"});

        userVmManagerImpl.validateStorageAccessGroupsOnHosts(srcHost, destHost);

        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, srcHost.getId());
        Mockito.verify(storageManager, times(1)).getStorageAccessGroups(null, null, null, destHost.getId());
    }
    @Test
    public void testAllocateVMFromBackupUsingCmdValues() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 4L;

        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd._accountService = accountService;
        cmd._entityMgr = entityManager;
        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);

        ReflectionTestUtils.setField(cmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(cmd, "templateId", templateId);
        ReflectionTestUtils.setField(cmd, "backupId", backupId);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(serviceOffering);

        Map<String, String> diskDetails = new HashMap<>();
        diskDetails.put(ApiConstants.DISK_OFFERING_ID, "disk-offering-uuid");
        diskDetails.put(ApiConstants.DEVICE_ID, "1");
        diskDetails.put(ApiConstants.SIZE, "5");
        diskDetails.put(ApiConstants.MIN_IOPS, "1000");
        diskDetails.put(ApiConstants.MAX_IOPS, "5000");
        Map<Integer, Map<String, String>> disksDetails = new HashMap<>();
        disksDetails.put(0, diskDetails);
        ReflectionTestUtils.setField(cmd, "dataDisksDetails", disksDetails);
        DiskOffering diskOffering = mock(DiskOffering.class);
        when(diskOffering.isCustomized()).thenReturn(true);
        when(diskOffering.isCustomizedIops()).thenReturn(true);
        when(entityManager.findByUuid(DiskOffering.class, "disk-offering-uuid")).thenReturn(diskOffering);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(backupId)).thenReturn(backup);

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(templateId);
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateDao.findById(templateId)).thenReturn(template);
        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);
        Mockito.when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        Mockito.verify(backupDao).findById(backupId);
        Mockito.verify(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());
    }

    @Test
    public void testAllocateVMFromBackupUsingBackupValues() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 5L;

        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(cmd.getBackupId()).thenReturn(backupId);
        when(cmd.getEntityOwnerId()).thenReturn(accountId);
        when(cmd.getServiceOfferingId()).thenReturn(null);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getDiskOfferingId()).thenReturn(null);
        when(cmd.getTemplateId()).thenReturn(null);
        when(cmd.getNetworkIds()).thenReturn(null);
        when(cmd.getIpToNetworkMap()).thenReturn(null);
        when(cmd.getDataDiskInfoList()).thenReturn(null);
        when(cmd.getOverrideDiskOfferingId()).thenReturn(null);

        Account owner = mock(Account.class);
        when(accountService.getActiveAccountById(accountId)).thenReturn(owner);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(_dcDao.findById(zoneId)).thenReturn(zone);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(backupId)).thenReturn(backup);

        UserVmVO userVmVO = new UserVmVO();
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(backup.getDetail(ApiConstants.TEMPLATE_ID)).thenReturn("template-uuid");
        when(templateDao.findByUuid("template-uuid")).thenReturn(template);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(backup.getDetail(ApiConstants.SERVICE_OFFERING_ID)).thenReturn("service-offering-uuid");
        when(_serviceOfferingDao.findByUuid("service-offering-uuid")).thenReturn(serviceOffering);
        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);

        NetworkVO network1 = mock(NetworkVO.class);
        NetworkVO network2 = mock(NetworkVO.class);
        when(backupManager.getDataDiskInfoListFromBackup(backup)).thenReturn(List.of(new VmDiskInfo(diskOffering, 10L, 1000L, 2000L)));
        Mockito.when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any(), any(),
                any(), any(), any(), any(), eq(false), any(), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        Mockito.verify(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any(), any(),
                any(), any(), any(), any(), eq(false), any(), any(), any(), any(), any());
    }

    @Test
    public void testResetVMSSHKey() throws ResourceUnavailableException, InsufficientCapacityException {
        Long domainId = 4L;
        Long projectId = 5L;
        Long networkId = 6L;

        List<String> names = List.of("keypair1", "keypair2");
        ResetVMSSHKeyCmd cmd = mock(ResetVMSSHKeyCmd.class);
        when(cmd.getId()).thenReturn(vmId);
        when(cmd.getAccountName()).thenReturn("testAccount");
        when(cmd.getDomainId()).thenReturn(domainId);
        when(cmd.getProjectId()).thenReturn(projectId);
        when(cmd.getNames()).thenReturn(names);

        Account owner = mock(Account.class);
        when(owner.getAccountId()).thenReturn(accountId);
        when(owner.getDomainId()).thenReturn(domainId);
        when(accountManager.finalizeOwner(callerAccount, "testAccount", domainId, projectId)).thenReturn(owner);

        UserVmVO userVm = new UserVmVO(vmId, null, null, templateId, Hypervisor.HypervisorType.KVM, 0,
                true, false, domainId, accountId, 0L, 0L, null, null, null, null);
        ReflectionTestUtils.setField(userVm, "state", VirtualMachine.State.Stopped);
        userVm.setUserVmType("User");
        when(userVmDao.findById(vmId)).thenReturn(userVm);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(templateId)).thenReturn(template);

        Nic nic = mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(networkId);
        when(networkModel.getDefaultNic(vmId)).thenReturn(nic);
        NetworkVO network = mock(NetworkVO.class);
        when(_networkDao.findById(networkId)).thenReturn(network);
        UserDataServiceProvider element = mock(UserDataServiceProvider.class);
        when(element.saveSSHKey(any(), any(), any(), any())).thenReturn(true);
        when(_networkMgr.getSSHKeyResetProvider(network)).thenReturn(element);

        SSHKeyPairVO keyPair1 = mock(SSHKeyPairVO.class);
        SSHKeyPairVO keyPair2 = mock(SSHKeyPairVO.class);
        when(keyPair1.getPublicKey()).thenReturn("ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAr...");
        when(keyPair2.getPublicKey()).thenReturn("ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAr...");
        when(sshKeyPairDao.findByNames(accountId, domainId, names)).thenReturn(Arrays.asList(keyPair1, keyPair2));

        UserVm result = userVmManagerImpl.resetVMSSHKey(cmd);

        assertNotNull(result);
        Map<String, String> details = result.getDetails();
        Assert.assertEquals("ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAr...\n" +
                "ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAr...", details.get(VmDetailConstants.SSH_PUBLIC_KEY));
        Assert.assertEquals("keypair1,keypair2", details.get(VmDetailConstants.SSH_KEY_PAIR_NAMES));
    }

    @Test
    public void testAllocateVMFromBackupUsingCmdValuesWithISO() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 4L;
        Long rootDiskOfferingId = 5L;
        Long isoId = 7L;

        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd._accountService = accountService;
        cmd._entityMgr = entityManager;
        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);

        ReflectionTestUtils.setField(cmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(cmd, "templateId", isoId);
        ReflectionTestUtils.setField(cmd, "backupId", backupId);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(cmd, "diskOfferingId", rootDiskOfferingId);
        ReflectionTestUtils.setField(cmd, "overrideDiskOfferingId", null);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        DiskOfferingVO rootDiskOffering = mock(DiskOfferingVO.class);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(serviceOffering);
        when(rootDiskOffering.getDiskSize()).thenReturn(10 * 1024 * 1024 * 1024L);
        when(diskOfferingDao.findById(rootDiskOfferingId)).thenReturn(rootDiskOffering);

        Map<String, String> diskDetails = new HashMap<>();
        diskDetails.put(ApiConstants.DISK_OFFERING_ID, "disk-offering-uuid");
        diskDetails.put(ApiConstants.DEVICE_ID, "1");
        diskDetails.put(ApiConstants.SIZE, "5");
        diskDetails.put(ApiConstants.MIN_IOPS, "1000");
        diskDetails.put(ApiConstants.MAX_IOPS, "5000");
        Map<Integer, Map<String, String>> disksDetails = new HashMap<>();
        disksDetails.put(0, diskDetails);
        ReflectionTestUtils.setField(cmd, "dataDisksDetails", disksDetails);
        DiskOffering diskOffering = mock(DiskOffering.class);
        when(diskOffering.isCustomized()).thenReturn(true);
        when(diskOffering.isCustomizedIops()).thenReturn(true);
        when(entityManager.findByUuid(DiskOffering.class, "disk-offering-uuid")).thenReturn(diskOffering);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(backupId)).thenReturn(backup);

        UserVmVO userVmVO = new UserVmVO();
        userVmVO.setTemplateId(isoId);
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO iso = mock(VMTemplateVO.class);
        when(iso.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        when(templateDao.findById(isoId)).thenReturn(iso);
        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);
        Mockito.when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        Mockito.verify(backupDao).findById(backupId);
        Mockito.verify(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());
    }

    @Test
    public void testAllocateVMFromBackupUsingBackupValuesWithISO() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 5L;
        Long isoId = 9L;

        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(cmd.getBackupId()).thenReturn(backupId);
        when(cmd.getEntityOwnerId()).thenReturn(accountId);
        when(cmd.getServiceOfferingId()).thenReturn(null);
        when(cmd.getDiskOfferingId()).thenReturn(null);
        when(cmd.getTemplateId()).thenReturn(null);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getNetworkIds()).thenReturn(null);
        when(cmd.getIpToNetworkMap()).thenReturn(null);
        when(cmd.getDataDiskInfoList()).thenReturn(null);

        Account owner = mock(Account.class);
        when(accountService.getActiveAccountById(accountId)).thenReturn(owner);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(_dcDao.findById(zoneId)).thenReturn(zone);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backup.getDetail(ApiConstants.SERVICE_OFFERING_ID)).thenReturn("service-offering-uuid");
        when(backupDao.findById(backupId)).thenReturn(backup);

        UserVmVO userVmVO = new UserVmVO();
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO iso = mock(VMTemplateVO.class);
        when(iso.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        when(backup.getDetail(ApiConstants.TEMPLATE_ID)).thenReturn("iso-uuid");
        when(templateDao.findByUuid("iso-uuid")).thenReturn(iso);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(backup.getDetail(ApiConstants.SERVICE_OFFERING_ID)).thenReturn("service-offering-uuid");
        when(_serviceOfferingDao.findByUuid("service-offering-uuid")).thenReturn(serviceOffering);

        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);

        NetworkVO network1 = mock(NetworkVO.class);
        NetworkVO network2 = mock(NetworkVO.class);
        when(backupManager.getDataDiskInfoListFromBackup(backup)).thenReturn(List.of(new VmDiskInfo(diskOffering, 10L, 1000L, 2000L)));
        Mockito.when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any(), any(),
                any(), any(), any(), any(), eq(false), any(), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        Mockito.verify(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),  eq(false), any(), any(), any(),
                any(), any(), any(), any(), eq(false), any(), any(), any(), any(), any());
    }

    @Test
    public void testRestoreVMFromBackup() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        Long backupId = 5L;
        Long templateId = 6L;

        CreateVMFromBackupCmd cmd = mock(CreateVMFromBackupCmd.class);
        when(cmd.getBackupId()).thenReturn(backupId);
        when(cmd.getStartVm()).thenReturn(true);
        when(cmd.getEntityId()).thenReturn(vmId);

        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getTemplateId()).thenReturn(templateId);

        when(backupManager.restoreBackupToVM(backupId, vmId, false)).thenReturn(true);

        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> vmPair = new Pair<>(vm, params);
        doReturn(vmPair).when(userVmManagerImpl).startVirtualMachine(anyLong(), isNull(), isNull(), isNull(), anyMap(), isNull(), anyBoolean());
        doReturn(vmPair).when(userVmManagerImpl).startVirtualMachine(anyLong(), isNull(), isNull(), anyLong(), anyMap(), isNull(), anyBoolean());
        when(userVmDao.findById(vmId)).thenReturn(vm);
        when(templateDao.findByIdIncludingRemoved(templateId)).thenReturn(mock(VMTemplateVO.class));

        UserVm result = userVmManagerImpl.restoreVMFromBackup(cmd);

        assertNotNull(result);
        assertEquals(vm, result);
        Mockito.verify(backupManager).restoreBackupToVM(backupId, vmId, false);
    }

    @Test
    public void testDestroyVm() throws ResourceUnavailableException {
        Long volumeId = 4L;
        Long accountId = 5L;
        Long userId = 6L;
        boolean expunge = true;

        ReflectionTestUtils.setField(userVmManagerImpl, "_uuidMgr", uuidMgr);
        CallContext callContext = mock(CallContext.class);
        Account callingAccount = mock(Account.class);
        when(callContext.getCallingAccount()).thenReturn(callingAccount);
        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);
            mockedCallContext.when(() -> CallContext.register(callContext, ApiCommandResourceType.Volume)).thenReturn(callContext);

            DestroyVMCmd cmd = mock(DestroyVMCmd.class);
            when(cmd.getId()).thenReturn(vmId);
            when(cmd.getExpunge()).thenReturn(expunge);
            List<Long> volumeIds = List.of(volumeId);
            when(cmd.getVolumeIds()).thenReturn(volumeIds);
            AsyncJobVO asyncJobMock = mock(AsyncJobVO.class);

            UserVmVO vm = mock(UserVmVO.class);
            when(vm.getId()).thenReturn(vmId);
            when(vm.getState()).thenReturn(VirtualMachine.State.Running);
            when(vm.getUuid()).thenReturn("vm-uuid");
            when(vm.getUserVmType()).thenReturn("User");
            when(userVmDao.findById(vmId)).thenReturn(vm);

            VolumeVO vol = Mockito.mock(VolumeVO.class);
            when(vol.getInstanceId()).thenReturn(vmId);
            when(vol.getId()).thenReturn(volumeId);
            when(vol.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            when(volumeDaoMock.findById(volumeId)).thenReturn(vol);

            List<VolumeVO> dataVolumes = new ArrayList<>();
            when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.DATADISK)).thenReturn(dataVolumes);

            doReturn(vm).when(userVmManagerImpl).stopVirtualMachine(anyLong(), anyBoolean());
            doReturn(vm).when(userVmManagerImpl).destroyVm(vmId, expunge);
            doReturn(true).when(userVmManagerImpl).expunge(vm);

            try (MockedStatic<UsageEventUtils> mockedUsageEventUtils = mockStatic(UsageEventUtils.class)) {

                UserVm result = userVmManagerImpl.destroyVm(cmd, false);

                assertNotNull(result);
                assertEquals(vm, result);
                Mockito.verify(userVmManagerImpl).stopVirtualMachine(vmId, false);
                Mockito.verify(backupManager).checkAndRemoveBackupOfferingBeforeExpunge(vm);
            }
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesInvalidDuration() {
        userVmManagerImpl.validateLeaseProperties(-2, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesNullActionValue() {
        userVmManagerImpl.validateLeaseProperties(20, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesNullDurationValue() {
        userVmManagerImpl.validateLeaseProperties(null, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesMinusOneDuration() {
        userVmManagerImpl.validateLeaseProperties(-1, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testValidateLeasePropertiesZeroDayDuration() {
        userVmManagerImpl.validateLeaseProperties(0, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesValidValues() {
        userVmManagerImpl.validateLeaseProperties(20, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void testValidateLeasePropertiesBothNUll() {
        userVmManagerImpl.validateLeaseProperties(null, null);
    }

    @Test
    public void testAddLeaseDetailsForInstance() {
        UserVm userVm = mock(UserVm.class);
        when(userVm.getId()).thenReturn(vmId);
        when(userVm.getUuid()).thenReturn(UUID.randomUUID().toString());
        userVmManagerImpl.addLeaseDetailsForInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        verify(vmInstanceDetailsDao).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION), eq(VMLeaseManager.ExpiryAction.STOP.name()), anyBoolean());
        verify(vmInstanceDetailsDao).addDetail(eq(vmId), eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE), eq(getLeaseExpiryDate(10L)), anyBoolean());
    }

    @Test
    public void testAddNullDurationLeaseDetailsForInstance() {
        UserVm userVm = mock(UserVm.class);
        userVmManagerImpl.addLeaseDetailsForInstance(userVm, null, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFeatureEnabled() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, 10, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(userVmManagerImpl, Mockito.times(1)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceNegativeLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, -1, VMLeaseManager.ExpiryAction.DESTROY, null);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFromSvcOfferingWithoutLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, null, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceFromSvcOfferingWithLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        when(svcOfferingMock.getLeaseDuration()).thenReturn(10);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, null, VMLeaseManager.ExpiryAction.DESTROY, svcOfferingMock);
        Mockito.verify(userVmManagerImpl, Mockito.times(1)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnCreateInstanceNullExpiryAction() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        ServiceOfferingJoinVO svcOfferingMock = Mockito.mock(ServiceOfferingJoinVO.class);
        userVmManagerImpl.applyLeaseOnCreateInstance(userVm, 10, null, svcOfferingMock);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForNoLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.DISABLED.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnUpdateInstanceForLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(userVmManagerImpl, Mockito.times(1)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForDisabledLeaseInstance() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(5, VMLeaseManager.LeaseActionExecution.DISABLED.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(userVmManagerImpl, Mockito.times(1)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceForLeaseExpired() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(-2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, 10, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
    }

    @Test
    public void testApplyLeaseOnUpdateInstanceToRemoveLease() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);;
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        try (MockedStatic<ActionEventUtils> ignored = mockStatic(ActionEventUtils.class)) {
            Mockito.when(ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, -1, VMLeaseManager.ExpiryAction.STOP);
        }
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(1)).
                addDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXECUTION, VMLeaseManager.LeaseActionExecution.DISABLED.name(), false);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testApplyLeaseOnUpdateInstanceToRemoveLeaseForExpired() {
        UserVmVO userVm = Mockito.mock(UserVmVO.class);
        when(userVm.getId()).thenReturn(vmId);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(getLeaseDetails(-2, VMLeaseManager.LeaseActionExecution.PENDING.name()));
        userVmManagerImpl.applyLeaseOnUpdateInstance(userVm, -1, VMLeaseManager.ExpiryAction.STOP);
        Mockito.verify(userVmManagerImpl, Mockito.times(0)).addLeaseDetailsForInstance(any(), any(), any());
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION);
        Mockito.verify(vmInstanceDetailsDao, Mockito.times(0)).removeDetail(vmId, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);
    }

    String getLeaseExpiryDate(long leaseDuration) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime leaseExpiryDateTime = now.plusDays(leaseDuration);
        Date leaseExpiryDate = Date.from(leaseExpiryDateTime.atZone(ZoneOffset.UTC).toInstant());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(leaseExpiryDate);
    }

    Map<String, String> getLeaseDetails(int leaseDuration, String leaseExecution) {

        Map<String, String> leaseDetails = new HashMap<>();
        leaseDetails.put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, getLeaseExpiryDate(leaseDuration));
        leaseDetails.put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, leaseExecution);
        return leaseDetails;
    }

    private static DeployVMCmd buildDeployVMCmd(Long volumeId, Long snapshotId) {
        DeployVMCmd deployVMCmd = mock(DeployVMCmd.class);
        when(deployVMCmd.getEntityOwnerId()).thenReturn(accountId);
        when(deployVMCmd.getZoneId()).thenReturn(zoneId);
        when(deployVMCmd.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(deployVMCmd.getVolumeId()).thenReturn(volumeId);
        when(deployVMCmd.getSnapshotId()).thenReturn(snapshotId);
        when(deployVMCmd.getTemplateId()).thenReturn(null);
        when(deployVMCmd.isVolumeOrSnapshotProvided()).thenReturn(true);
        when(deployVMCmd.getNetworkIds()).thenReturn(null);
        when(deployVMCmd.getSecurityGroupNameList()).thenReturn(null);
        // Long methods default to 0L in Mockito; null is required to match real DeployVMCmd field-unset behaviour
        when(deployVMCmd.getDiskOfferingId()).thenReturn(null);
        when(deployVMCmd.getUserdataId()).thenReturn(null);
        when(deployVMCmd.getHostId()).thenReturn(null);
        // boolean method defaults to false; real DeployVMCmd returns true when field is null
        when(deployVMCmd.isDynamicScalingEnabled()).thenReturn(true);
        return deployVMCmd;
    }

    private static DeployVMCmd getDeployVMCmdFromSnapshot() {
        return buildDeployVMCmd(null, snashotId);
    }

    private static DeployVMCmd getDeployVMCmdFromVolume() {
        return buildDeployVMCmd(volumeId, null);
    }

    @Test
    public void createVirtualMachineWithExistingVolume() throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromVolume();

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(entityManager.findById(DiskOffering.class, serviceOffering.getId())).thenReturn(smallerDisdkOffering);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(volumeInfo.getDataStore()).thenReturn(primaryDataStore);
        when(primaryDataStore.getScope()).thenReturn(scopeMock);
        when(primaryDataStore.getScope().getScopeType()).thenReturn(ScopeType.ZONE);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(false);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());


        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachineWithExistingSnapshot()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromSnapshot();                                    // mock, no field hacks

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);      // keep
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(entityManager.findById(DiskOffering.class, serviceOffering.getId())).thenReturn(smallerDisdkOffering);
        when(snapshotDaoMock.findById(snashotId)).thenReturn(snapshotMock);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(snapshotMock.getVolumeId()).thenReturn(volumeId);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(false);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());

        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachineWithSnapshotFromExpungedLocalStorageVolumeSucceeds()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromSnapshot();                                    // mock, no field hacks

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(snapshotDaoMock.findById(snashotId)).thenReturn(snapshotMock);
        when(snapshotMock.getVolumeId()).thenReturn(volumeId);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());

        // Must NOT throw "Deployment of virtual machine is supported only for Zone-wide storage pools"
        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachineWithSnapshotFromVolumeWithNullDataStoreSucceeds()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromSnapshot();                                    // mock, no field hacks

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(snapshotDaoMock.findById(snashotId)).thenReturn(snapshotMock);
        when(snapshotMock.getVolumeId()).thenReturn(volumeId);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        when(volumeInfo.getTemplateId()).thenReturn(templateId);
        when(volumeInfo.getInstanceId()).thenReturn(null);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(templateMock);
        when(templateMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        when(templateMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateMock.isDeployAsIs()).thenReturn(false);
        when(templateMock.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateMock.getUserDataId()).thenReturn(null);
        Mockito.doNothing().when(vnfTemplateManager).validateVnfApplianceNics(any(), nullable(List.class), any());
        when(_dcMock.isLocalStorageEnabled()).thenReturn(true);
        when(_dcMock.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.doReturn(userVmVoMock).when(userVmManagerImpl).createBasicSecurityGroupVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any());

        // Must NOT throw "Deployment of virtual machine is supported only for Zone-wide storage pools"
        userVmManagerImpl.createVirtualMachine(deployVMCmd);
    }

    @Test
    public void createVirtualMachineWithVolumeFromNonZoneScopedStorageFails() {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromVolume();

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        // Volume lives on cluster-scoped storage
        when(volumeInfo.getDataStore()).thenReturn(primaryDataStore);
        when(primaryDataStore.getScope()).thenReturn(scopeMock);
        when(scopeMock.getScopeType()).thenReturn(ScopeType.CLUSTER);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> userVmManagerImpl.createVirtualMachine(deployVMCmd));
        assertEquals("Deployment of virtual machine is supported only for Zone-wide and host-local storage pools", ex.getMessage());
    }

    @Test
    public void createVirtualMachineWithVolumeWithNullDataStoreFails() {
        DeployVMCmd deployVMCmd = getDeployVMCmdFromVolume();

        when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        when(entityManager.findById(DataCenter.class, zoneId)).thenReturn(_dcMock);
        when(entityManager.findById(ServiceOffering.class, serviceOfferingId)).thenReturn(serviceOffering);
        when(volumeDataFactory.getVolume(volumeId)).thenReturn(volumeInfo);
        // Volume's data store is null (pool was deleted)
        when(volumeInfo.getDataStore()).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> userVmManagerImpl.createVirtualMachine(deployVMCmd));
        assertEquals("Deployment of virtual machine is supported only for Zone-wide and host-local storage pools", ex.getMessage());
    }

    @Test
    public void testAllocateVMFromBackupWithVmSettingsRestoration() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 10L;
        Long vmId = 1L;

        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd._accountService = accountService;
        cmd._entityMgr = entityManager;
        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);

        ReflectionTestUtils.setField(cmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(cmd, "templateId", templateId);
        ReflectionTestUtils.setField(cmd, "backupId", backupId);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(serviceOffering);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(_dcDao.findById(zoneId)).thenReturn(zone);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(backupId)).thenReturn(backup);

        String vmSettingsJson = "{\"key1\":\"value1\",\"key2\":\"value2\",\"existingKey\":\"backupValue\"}";
        when(backup.getDetail(ApiConstants.VM_SETTINGS)).thenReturn(vmSettingsJson);

        UserVmVO userVmVO = new UserVmVO();
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateDao.findById(templateId)).thenReturn(template);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);
        when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        UserVmVO createdVm = mock(UserVmVO.class);
        when(createdVm.getId()).thenReturn(2L);
        Mockito.doReturn(createdVm).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());

        Map<String, String> existingDetails = new HashMap<>();
        existingDetails.put("existingKey", "existingValue");
        when(vmInstanceDetailsDao.listDetailsKeyPairs(2L)).thenReturn(existingDetails);

        UserVmVO vmVO = mock(UserVmVO.class);
        when(userVmDao.findById(2L)).thenReturn(vmVO);

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        assertEquals(2L, result.getId());

        verify(backup).getDetail(ApiConstants.VM_SETTINGS);
        verify(vmInstanceDetailsDao).listDetailsKeyPairs(2L);
        verify(userVmDao).findById(2L);
        verify(userVmDao).saveDetails(any(UserVmVO.class));
    }

    @Test
    public void testAllocateVMFromBackupWithOverrideDiskOfferingComputeOnly() throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        Long backupId = 11L;
        Long vmId = 1L;
        Long overrideDiskOfferingId = 5L;

        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd._accountService = accountService;
        cmd._entityMgr = entityManager;
        when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        when(accountService.getActiveAccountById(accountId)).thenReturn(account);

        ReflectionTestUtils.setField(cmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(cmd, "templateId", templateId);
        ReflectionTestUtils.setField(cmd, "backupId", backupId);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(cmd, "overrideDiskOfferingId", overrideDiskOfferingId);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(serviceOffering);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(_dcDao.findById(zoneId)).thenReturn(zone);

        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(backupId)).thenReturn(backup);

        UserVmVO userVmVO = new UserVmVO();
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(userVmVO);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        when(templateDao.findById(templateId)).thenReturn(template);

        DiskOfferingVO overrideDiskOffering = mock(DiskOfferingVO.class);
        when(overrideDiskOffering.isComputeOnly()).thenReturn(true);
        when(diskOfferingDao.findById(overrideDiskOfferingId)).thenReturn(overrideDiskOffering);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        VmDiskInfo rootVmDiskInfo = new VmDiskInfo(diskOffering, 10L, 1000L, 2000L);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(rootVmDiskInfo);
        when(backupManager.canCreateInstanceFromBackup(backupId)).thenReturn(true);

        UserVmVO createdVm = mock(UserVmVO.class);
        when(createdVm.getId()).thenReturn(2L);
        Mockito.doReturn(createdVm).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());

        UserVm result = userVmManagerImpl.allocateVMFromBackup(cmd);

        assertNotNull(result);
        assertEquals(2L, result.getId());

        verify(diskOfferingDao).findById(overrideDiskOfferingId);
        verify(overrideDiskOffering).isComputeOnly();
    }


    @Test
    public void testUnmanageUserVMVmNotFound() {
        when(userVmDao.findById(vmId)).thenReturn(null);
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });
        assertEquals("Unable to find a VM with ID = " + vmId, exception.getMessage());
        verify(userVmDao, never()).acquireInLockTable(anyLong());
        verify(userVmDao, never()).releaseFromLockTable(anyLong());
    }

    @Test
    public void testUnmanageUserVMAlreadyRemoved() {
        when(userVmVoMock.getRemoved()).thenReturn(new Date());
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        assertThrows(InvalidParameterValueException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });
    }

    @Test
    public void testUnmanageUserVMInvalidState() {
        when(userVmVoMock.getId()).thenReturn(vmId);
        when(userVmVoMock.getName()).thenReturn("test-vm");
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmDao.acquireInLockTable(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Starting);
        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });
        assertEquals("Instance: test-vm is not running or stopped, cannot be unmanaged", exception.getMessage());
        verify(userVmDao, times(1)).releaseFromLockTable(vmId);
    }

    @Test
    public void testUnmanageUserVMUnsupportedHypervisor() {
        when(userVmVoMock.getId()).thenReturn(vmId);
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmDao.acquireInLockTable(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Hyperv);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.Hyperv)).thenReturn(false);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });

        assertEquals("Unmanaging a VM is currently not supported on hypervisor Hyperv", exception.getMessage());
        verify(userVmDao, times(1)).releaseFromLockTable(vmId);
    }

    @Test
    public void testUnmanageUserVMItManagerReturnsFalse() {
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getId()).thenReturn(vmId);
        when(userVmVoMock.getUuid()).thenReturn(vmUuid);
        when(userVmDao.acquireInLockTable(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.KVM)).thenReturn(true);
        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Collections.emptyList());
        when(virtualMachineManager.unmanage(vmUuid, null)).thenReturn(new Pair<>(false, "Backend failure"));

        doNothing().when(userVmManagerImpl).checkUnmanagingVMOngoingVolumeSnapshots(any(UserVmVO.class));
        doNothing().when(userVmManagerImpl).checkUnmanagingVMVolumes(any(UserVmVO.class), any(List.class));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });

        assertEquals("Error while unmanaging VM: " + vmUuid, exception.getMessage());
        verify(userVmManagerImpl, never()).cleanupUnmanageVMResources(any(UserVmVO.class));
        verify(userVmManagerImpl, never()).unmanageVMFromDB(anyLong());
        verify(userVmDao, times(1)).releaseFromLockTable(vmId);
    }

    @Test
    public void testUnmanageUserVMGenericException() {
        RuntimeException testException = new RuntimeException("Something went wrong");
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getId()).thenReturn(vmId);
        when(userVmDao.acquireInLockTable(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.KVM)).thenReturn(true);
        doThrow(testException).when(userVmManagerImpl).checkUnmanagingVMOngoingVolumeSnapshots(any(UserVmVO.class));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> {
            userVmManagerImpl.unmanageUserVM(vmId, null);
        });

        assertNotNull(exception.getCause());
        assertEquals(testException, exception.getCause());
        verify(userVmDao, times(1)).releaseFromLockTable(vmId);
    }

    @Test
    public void testUnmanageUserVMSuccess() {
        when(userVmDao.findById(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getId()).thenReturn(vmId);
        when(userVmVoMock.getUuid()).thenReturn(vmUuid);
        when(userVmDao.acquireInLockTable(vmId)).thenReturn(userVmVoMock);
        when(userVmVoMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.KVM)).thenReturn(true);
        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Collections.emptyList());
        when(virtualMachineManager.unmanage(vmUuid, null)).thenReturn(new Pair<>(true, "Unmanaged successfully"));
        doNothing().when(userVmManagerImpl).checkUnmanagingVMOngoingVolumeSnapshots(any(UserVmVO.class));
        doNothing().when(userVmManagerImpl).checkUnmanagingVMVolumes(any(UserVmVO.class), any(List.class));
        doNothing().when(userVmManagerImpl).cleanupUnmanageVMResources(any(UserVmVO.class));
        doNothing().when(userVmManagerImpl).unmanageVMFromDB(anyLong());
        doNothing().when(userVmManagerImpl).publishUnmanageVMUsageEvents(any(UserVmVO.class), any(List.class));
        Pair<Boolean, String> result = userVmManagerImpl.unmanageUserVM(vmId, null);
        assertTrue(result.first());
        assertEquals("Unmanaged successfully", result.second());
        verify(userVmDao, times(1)).acquireInLockTable(vmId);
        verify(userVmManagerImpl, times(1)).checkUnmanagingVMOngoingVolumeSnapshots(userVmVoMock);
        verify(userVmManagerImpl, times(1)).checkUnmanagingVMVolumes(userVmVoMock, Collections.emptyList());
        verify(userVmManagerImpl, times(1)).cleanupUnmanageVMResources(userVmVoMock);
        verify(userVmManagerImpl, times(1)).unmanageVMFromDB(vmId);
        verify(userVmManagerImpl, times(1)).publishUnmanageVMUsageEvents(userVmVoMock, Collections.emptyList());
        verify(userVmDao, times(1)).releaseFromLockTable(vmId);
    }

    @Test
    public void updateVmExtraConfigCleansUpWhenCleanupFlagIsTrue() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getUuid()).thenReturn("test-uuid");
        when(userVm.getId()).thenReturn(1L);

        userVmManagerImpl.updateVmExtraConfig(userVm, "someConfig", true);

        verify(vmInstanceDetailsDao, times(1)).removeDetailsWithPrefix(1L, ApiConstants.EXTRA_CONFIG);
        verifyNoMoreInteractions(vmInstanceDetailsDao);
    }

    @Test
    public void updateVmExtraConfigAddsConfigWhenValidAndEnabled() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getUuid()).thenReturn("test-uuid");
        when(userVm.getAccountId()).thenReturn(1L);
        when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        doNothing().when(userVmManagerImpl).persistExtraConfigKvm(anyString(), eq(userVm));
        updateDefaultConfigValue(UserVmManagerImpl.EnableAdditionalVmConfig, true, false);

        userVmManagerImpl.updateVmExtraConfig(userVm, "validConfig", false);

        verify(vmInstanceDetailsDao, never()).removeDetailsWithPrefix(anyLong(), anyString());
        verify(userVmManagerImpl, times(1)).addExtraConfig(userVm, "validConfig");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVmExtraConfigThrowsExceptionWhenConfigDisabled() {
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getAccountId()).thenReturn(1L);
        updateDefaultConfigValue(UserVmManagerImpl.EnableAdditionalVmConfig, false, false);

        userVmManagerImpl.updateVmExtraConfig(userVm, "validConfig", false);
    }

    @Test
    public void updateVmExtraConfigDoesNothingWhenExtraConfigIsBlank() {
        UserVmVO userVm = mock(UserVmVO.class);

        userVmManagerImpl.updateVmExtraConfig(userVm, "", false);

        verify(vmInstanceDetailsDao, never()).removeDetailsWithPrefix(anyLong(), anyString());
        verify(userVmManagerImpl, never()).addExtraConfig(any(UserVmVO.class), anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicThrowInvalidParameterValueException() {
        Long invalidId = -1L;
        Mockito.doReturn(invalidId).when(updateVmNicCmd).getNicId();

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicUserVmThrowInvalidParameterValueException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateVirtualMachineNicTestInvalidNicNetworkThrowInvalidParameterValueException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateVirtualMachineNicTestInvalidNicNetworkThrowCloudRuntimeException() {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);

        userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);
    }

    @Test
    public void updateVirtualMachineNicTestValidInputReturnNicUserVm() throws ResourceUnavailableException {
        Mockito.doReturn(nicId).when(updateVmNicCmd).getNicId();
        Mockito.doReturn(true).when(updateVmNicCmd).isEnabled();
        Mockito.doReturn(nicMock).when(nicDao).findById(nicId);
        Mockito.doReturn(vmId).when(nicMock).getInstanceId();
        Mockito.doReturn(userVmVoMock).when(userVmDao).findById(vmId);
        Mockito.doReturn(Hypervisor.HypervisorType.KVM).when(userVmVoMock).getHypervisorType();
        Mockito.doReturn(networkId).when(nicMock).getNetworkId();
        Mockito.doReturn(networkMock).when(_networkDao).findById(networkId);
        Mockito.doReturn(true).when(virtualMachineManager).updateVmNic(Mockito.any(), Mockito.any(), Mockito.any());

        UserVm result = userVmManagerImpl.updateVirtualMachineNic(updateVmNicCmd);

        Assert.assertNotNull(result);
    }

    @Test
    public void testTransitionExpungingToErrorVmInExpungingState() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Expunging);
        when(vm.getUuid()).thenReturn("test-uuid");
        when(userVmDao.findById(vmId)).thenReturn(vm);
        when(virtualMachineManager.stateTransitTo(eq(vm), eq(VirtualMachine.Event.OperationFailedToError), eq(null))).thenReturn(true);

        java.lang.reflect.Method method = UserVmManagerImpl.class.getDeclaredMethod("transitionExpungingToError", long.class);
        method.setAccessible(true);
        method.invoke(userVmManagerImpl, vmId);

        Mockito.verify(virtualMachineManager).stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
    }

    @Test
    public void testTransitionExpungingToErrorVmNotInExpungingState() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(userVmDao.findById(vmId)).thenReturn(vm);

        java.lang.reflect.Method method = UserVmManagerImpl.class.getDeclaredMethod("transitionExpungingToError", long.class);
        method.setAccessible(true);
        method.invoke(userVmManagerImpl, vmId);

        Mockito.verify(virtualMachineManager, Mockito.never()).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), any());
    }

    @Test
    public void testTransitionExpungingToErrorVmNotFound() throws Exception {
        when(userVmDao.findById(vmId)).thenReturn(null);

        java.lang.reflect.Method method = UserVmManagerImpl.class.getDeclaredMethod("transitionExpungingToError", long.class);
        method.setAccessible(true);
        method.invoke(userVmManagerImpl, vmId);

        Mockito.verify(virtualMachineManager, Mockito.never()).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), any());
    }

    @Test
    public void testTransitionExpungingToErrorHandlesNoTransitionException() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Expunging);
        when(userVmDao.findById(vmId)).thenReturn(vm);
        when(virtualMachineManager.stateTransitTo(eq(vm), eq(VirtualMachine.Event.OperationFailedToError), eq(null)))
                .thenThrow(new NoTransitionException("no transition"));

        java.lang.reflect.Method method = UserVmManagerImpl.class.getDeclaredMethod("transitionExpungingToError", long.class);
        method.setAccessible(true);
        method.invoke(userVmManagerImpl, vmId);
    }

    private ServiceOfferingVO getMockedServiceOffering(boolean custom, boolean customSpeed) {
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(serviceOffering.getUuid()).thenReturn("offering-uuid");
        when(serviceOffering.isDynamic()).thenReturn(custom);
        when(serviceOffering.isCustomCpuSpeedSupported()).thenReturn(customSpeed);
        if (custom) {
            when(serviceOffering.getCpu()).thenReturn(null);
            when(serviceOffering.getRamSize()).thenReturn(null);
        }
        if (customSpeed) {
            when(serviceOffering.getSpeed()).thenReturn(null);
        } else {
            when(serviceOffering.isCustomCpuSpeedSupported()).thenReturn(false);
            when(serviceOffering.getSpeed()).thenReturn(1000);
        }
        return serviceOffering;
    }

    @Test
    public void customOfferingNeedsCustomizationThrowsException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, true);
        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.validateCustomParameters(serviceOffering, Collections.emptyMap()));
        assertEquals("Need to specify custom parameter values cpu, cpu speed and memory when using custom offering", ex.getMessage());
    }

    @Test
    public void cpuSpeedCustomizationNotAllowedThrowsException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, false);

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.validateCustomParameters(serviceOffering, customParameters));
        Assert.assertTrue(ex.getMessage().startsWith("The CPU speed of this offering"));
    }

    @Test
    public void cpuSpeedCustomizationAllowedDoesNotThrowException() {
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, true);

        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(
                Map.of(ApiConstants.MIN_CPU_NUMBER, "1",
                        ApiConstants.MAX_CPU_NUMBER, "4",
                        ApiConstants.MIN_MEMORY, "256",
                        ApiConstants.MAX_MEMORY, "8192"));

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");
        customParameters.put(VmDetailConstants.MEMORY, "256");

        userVmManagerImpl.validateCustomParameters(serviceOffering, customParameters);
    }

    @Test
    public void verifyVmLimits_fixedOffering_throwsException() {
        when(userVmVoMock.getId()).thenReturn(1L);
        when(userVmVoMock.getServiceOfferingId()).thenReturn(1L);
        when(accountDao.findById(anyLong())).thenReturn(callerAccount);
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(false, false);
        when(_serviceOfferingDao.findById(anyLong())).thenReturn(serviceOffering);
        when(_serviceOfferingDao.findByIdIncludingRemoved(anyLong(), anyLong())).thenReturn(serviceOffering);

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.verifyVmLimits(userVmVoMock, customParameters));
        assertEquals("CPU number, Memory and CPU speed cannot be updated for a non-dynamic offering", ex.getMessage());
    }

    @Test
    public void verifyVmLimits_constrainedOffering_throwsException() {
        when(userVmVoMock.getId()).thenReturn(1L);
        when(userVmVoMock.getServiceOfferingId()).thenReturn(1L);
        when(accountDao.findById(anyLong())).thenReturn(callerAccount);
        ServiceOfferingVO serviceOffering = getMockedServiceOffering(true, false);
        when(_serviceOfferingDao.findById(anyLong())).thenReturn(serviceOffering);
        when(_serviceOfferingDao.findByIdIncludingRemoved(anyLong(), anyLong())).thenReturn(serviceOffering);

        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.CPU_NUMBER, "1");
        customParameters.put(VmDetailConstants.CPU_SPEED, "2500");

        InvalidParameterValueException ex = Assert.assertThrows(InvalidParameterValueException.class, () ->
                userVmManagerImpl.verifyVmLimits(userVmVoMock, customParameters));
        Assert.assertTrue(ex.getMessage().startsWith("The CPU speed of this offering"));
    }

    private ServiceOfferingVO poolOffering(Integer cpu, Long vgpuProfileId) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        lenient().when(offering.getId()).thenReturn(serviceOfferingId);
        lenient().when(offering.getCpu()).thenReturn(cpu);
        lenient().when(offering.getRamSize()).thenReturn(cpu == null ? null : 1024);
        lenient().when(offering.getSpeed()).thenReturn(2000);
        lenient().when(offering.getVgpuProfileId()).thenReturn(vgpuProfileId);
        lenient().when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        lenient().when(offering.isDynamic()).thenReturn(cpu == null);
        return offering;
    }

    private void deployInBasicZone(ServiceOfferingVO offering) throws Exception {
        DataCenter zone = mock(DataCenter.class);
        when(zone.getId()).thenReturn(zoneId);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        NetworkVO defaultNetwork = mock(NetworkVO.class);
        when(networkModel.getExclusiveGuestNetwork(zoneId)).thenReturn(defaultNetwork);
        when(_networkDao.findById(anyLong())).thenReturn(defaultNetwork);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getId()).thenReturn(templateId);
        when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(templateDao.findByIdIncludingRemoved(templateId)).thenReturn(template);
        ReflectionTestUtils.setField(userVmManagerImpl, "_dedicatedDao", mock(DedicatedResourceDao.class));
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(offering);
        userVmManagerImpl.createBasicSecurityGroupVirtualMachine(zone, offering, template, null, accountMock, "vm", "vm", null, null, null, null,
                null, HTTPMethod.GET, null, null, null, null, null, null, true, null, null, new HashMap<>(), null, null, null, null, false, null,
                null, null, null);
    }

    private static void assertVcpuStepRefused(org.junit.function.ThrowingRunnable operation) {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class, operation);
        assertEquals("The number of vCPUs of an Instance without a vGPU profile must be a multiple of 2; 3 is not", ex.getMessage());
    }

    @Test
    public void oddVcpuRejectedForCpuOnly() {
        assertVcpuStepRefused(() -> userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(3, null), 3, 1L));
        userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(4, null), 4, 1L);
        userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(3, 5L), 3, 1L);

        assertVcpuStepRefused(() -> deployInBasicZone(poolOffering(3, null)));

        VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_vmInstanceDao", vmInstanceDao);
        VMInstanceVO stopped = mock(VMInstanceVO.class);
        when(stopped.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(stopped.getType()).thenReturn(VirtualMachine.Type.User);
        when(stopped.getDomainId()).thenReturn(1L);
        when(vmInstanceDao.findById(vmId)).thenReturn(stopped);
        ServiceOfferingVO threeVcpus = poolOffering(3, null);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(threeVcpus);
        assertVcpuStepRefused(() -> userVmManagerImpl.upgradeVirtualMachine(vmId, serviceOfferingId, new HashMap<>()));

        ServiceOfferingVO custom = poolOffering(null, null);
        when(userVmVoMock.getType()).thenReturn(VirtualMachine.Type.User);
        when(userVmVoMock.getDomainId()).thenReturn(1L);
        when(accountDao.findById(anyLong())).thenReturn(callerAccount);
        when(_serviceOfferingDao.findById(anyLong())).thenReturn(custom);
        when(_serviceOfferingDao.findByIdIncludingRemoved(anyLong(), anyLong())).thenReturn(custom);
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(Map.of(ApiConstants.MIN_CPU_NUMBER, "1",
                ApiConstants.MAX_CPU_NUMBER, "8", ApiConstants.MIN_MEMORY, "256", ApiConstants.MAX_MEMORY, "8192"));
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.CPU_NUMBER, "3");
        details.put(VmDetailConstants.MEMORY, "1024");
        assertVcpuStepRefused(() -> userVmManagerImpl.verifyVmLimits(userVmVoMock, details));
    }

    @Test
    public void oddVcpuRejectedOnRunningScale() {
        VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_vmInstanceDao", vmInstanceDao);
        VMInstanceVO running = mock(VMInstanceVO.class);
        when(running.getState()).thenReturn(VirtualMachine.State.Running);
        when(running.getType()).thenReturn(VirtualMachine.Type.User);
        when(running.getDomainId()).thenReturn(1L);
        when(running.getHostId()).thenReturn(7L);
        when(running.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(vmInstanceDao.findById(vmId)).thenReturn(running);
        HostVO host = mock(HostVO.class);
        when(hostDao.findById(7L)).thenReturn(host);
        when(host.checkHostServiceOfferingAndTemplateTags(any(), any(), any())).thenReturn(true);
        ServiceOfferingVO threeVcpus = poolOffering(3, null);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(threeVcpus);

        assertVcpuStepRefused(() -> userVmManagerImpl.upgradeVirtualMachine(vmId, serviceOfferingId, new HashMap<>()));
    }

    @Test
    public void vcpuStepIsASetting() {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(UserVmManager.VmPoolVcpuStep.key(), ConfigKey.Scope.Domain, 7L)).thenReturn("1");
        lenient().when(depot.getConfigStringValue(UserVmManager.VmPoolVcpuStep.key(), ConfigKey.Scope.Domain, 8L)).thenReturn("4");
        lenient().when(depot.getConfigStringValue(UserVmManager.VmPoolVcpuStep.key(), ConfigKey.Scope.Domain, 10L)).thenReturn("0");
        ConfigKey.init(depot);
        try {
            assertEquals(1, UserVmManager.poolVcpuStep(7L));
            assertEquals(4, UserVmManager.poolVcpuStep(8L));
            assertEquals(2, UserVmManager.poolVcpuStep(9L));
            assertEquals(1, UserVmManager.poolVcpuStep(10L));

            userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(3, null), 3, 7L);
            for (int vcpus : new int[] {2, 6}) {
                assertThrows(InvalidParameterValueException.class,
                        () -> userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(vcpus, null), vcpus, 8L));
            }
            userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(8, null), 8, 8L);
            assertThrows(InvalidParameterValueException.class,
                    () -> userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(3, null), 3, 9L));
            userVmManagerImpl.validatePoolVcpuStep(VirtualMachine.Type.User, poolOffering(3, null), 3, 10L);

            for (VirtualMachine.Type systemType : new VirtualMachine.Type[] {VirtualMachine.Type.ConsoleProxy,
                    VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.DomainRouter}) {
                userVmManagerImpl.validatePoolVcpuStep(systemType, poolOffering(1, null), 1, 8L);
            }
        } finally {
            ConfigKey.init(null);
        }
    }

    private static final long MOVING_VM_ID = 4242L;

    private UserVmVO stoppedKvmVm(VirtualMachine.State state) {
        UserVmVO vm = mock(UserVmVO.class);
        lenient().when(vm.getId()).thenReturn(MOVING_VM_ID);
        lenient().when(vm.getUuid()).thenReturn("moving-vm");
        lenient().when(vm.getAccountId()).thenReturn(5L);
        lenient().when(vm.getDataCenterId()).thenReturn(1L);
        lenient().when(vm.getServiceOfferingId()).thenReturn(20L);
        lenient().when(vm.getTemplateId()).thenReturn(30L);
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        AccountVO vmOwner = mock(AccountVO.class);
        lenient().when(vmOwner.getState()).thenReturn(Account.State.ENABLED);
        lenient().when(accountDao.findById(5L)).thenReturn(vmOwner);
        return vm;
    }

    private PlacementVerdict approvedMoveToHostB() {
        List<PlacementVerdict.MovedVolume> volumes = Arrays.asList(new PlacementVerdict.MovedVolume(201L, "root-uuid", "ROOT-4242", 10L << 30, 4L << 30),
                new PlacementVerdict.MovedVolume(202L, "data-uuid", "DATA-202", 50L << 30, 20L << 30));
        HostVO hostB = mock(HostVO.class);
        lenient().when(hostB.getId()).thenReturn(2L);
        lenient().when(hostB.getDataCenterId()).thenReturn(1L);
        lenient().when(hostB.getPodId()).thenReturn(3L);
        lenient().when(hostB.getClusterId()).thenReturn(4L);
        lenient().when(hostB.getName()).thenReturn("hostB");
        lenient().when(hostDao.findById(2L)).thenReturn(hostB);
        return PlacementVerdict.move(volumes, 300, true, "approval", 2L, 12L);
    }

    private StartVMCmd startCmd(boolean migrateVolumes, String token) {
        StartVMCmd cmd = mock(StartVMCmd.class);
        lenient().when(cmd.getId()).thenReturn(MOVING_VM_ID);
        lenient().when(cmd.isMigrateVolumes()).thenReturn(migrateVolumes);
        lenient().when(cmd.getPlacementToken()).thenReturn(token);
        lenient().when(cmd.getPodId()).thenReturn(null);
        lenient().when(cmd.getClusterId()).thenReturn(null);
        lenient().when(cmd.getHostId()).thenReturn(null);
        lenient().when(cmd.getBootIntoSetup()).thenReturn(null);
        return cmd;
    }

    @Test
    public void approvedStartRelocatesAsOwnQueueStepThenStartsPinned() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict move = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(move);
        doReturn(new Pair<>(vm, new HashMap<>())).when(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(),
                any(DataCenterDeployment.class), anyMap(), nullable(String.class), eq(false), eq(false));

        userVmManagerImpl.startVirtualMachine(startCmd(true, "approval"));

        ArgumentCaptor<PlacementRequest> request = ArgumentCaptor.forClass(PlacementRequest.class);
        ArgumentCaptor<DataCenterDeployment> plan = ArgumentCaptor.forClass(DataCenterDeployment.class);
        InOrder order = Mockito.inOrder(placementService, virtualMachineManager, userVmManagerImpl);
        order.verify(placementService).approvedMove(request.capture(), eq(true), eq("approval"));
        order.verify(virtualMachineManager).relocateStoppedVm("moving-vm", Arrays.asList(201L, 202L), 12L);
        order.verify(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(), plan.capture(), anyMap(), nullable(String.class),
                eq(false), eq(false));
        assertEquals(PlacementRequest.Kind.START, request.getValue().getKind());
        assertEquals(Long.valueOf(MOVING_VM_ID), request.getValue().getApprovalInstanceId());
        assertEquals("the start is pinned to the host the volumes moved to", Long.valueOf(2L), plan.getValue().getHostId());
        assertEquals(Long.valueOf(4L), plan.getValue().getClusterId());
    }

    @Test
    public void startWithoutMoveStartsAsBefore() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        when(placementService.approvedMove(any(PlacementRequest.class), eq(false), isNull())).thenReturn(null);
        doReturn(new Pair<>(vm, new HashMap<>())).when(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(), anyMap(),
                nullable(String.class), eq(false));

        userVmManagerImpl.startVirtualMachine(startCmd(false, null));

        verify(virtualMachineManager, never()).relocateStoppedVm(anyString(), anyList(), anyLong());
    }

    @Test
    public void relocateFailureLeavesVmStoppedVolumesUnchanged() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict approved = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);
        doThrow(new CloudRuntimeException("Host hostB does not have the capacity to start Instance moving-vm.")).when(virtualMachineManager)
                .relocateStoppedVm("moving-vm", Arrays.asList(201L, 202L), 12L);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.startVirtualMachine(startCmd(true, "approval")));

        assertTrue(e.getMessage(), e.getMessage().contains("failed, and nothing was moved"));
        assertFalse("a user is not told the hosts", e.getMessage().contains("hostB"));
        verify(userVmManagerImpl, never()).startVirtualMachine(Mockito.anyLong(), any(), any(), any(), any(DataCenterDeployment.class), anyMap(), any(),
                Mockito.anyBoolean(), Mockito.anyBoolean());
    }

    @Test
    public void startFailureAfterRelocateLeavesVmStoppedOnDestination() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict approved = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);
        doThrow(new InsufficientServerCapacityException("Unable to create a deployment for VM instance", DataCenter.class, 1L)).when(userVmManagerImpl)
                .startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(), any(DataCenterDeployment.class), anyMap(), nullable(String.class), eq(false),
                        eq(false));

        InsufficientServerCapacityException e = assertThrows(InsufficientServerCapacityException.class,
                () -> userVmManagerImpl.startVirtualMachine(startCmd(true, "approval")));

        assertTrue(e.getMessage(), e.getMessage().contains("were moved to another host and are kept there, but the Instance did not start"));
        assertTrue(e.getMessage(), e.getMessage().contains("Unable to create a deployment"));
        verify(virtualMachineManager, times(1)).relocateStoppedVm("moving-vm", Arrays.asList(201L, 202L), 12L);
        verifyNoMoreInteractions(virtualMachineManager);
    }

    private DeployVMCmd deployFromVolumeCmd() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        lenient().when(cmd.getEntityId()).thenReturn(MOVING_VM_ID);
        lenient().when(cmd.getStartVm()).thenReturn(true);
        lenient().when(cmd.getVolumeId()).thenReturn(201L);
        lenient().when(cmd.isMigrateVolumes()).thenReturn(true);
        lenient().when(cmd.getPlacementToken()).thenReturn("approval");
        lenient().when(cmd.getDataDiskTemplateToDiskOfferingMap()).thenReturn(new HashMap<>());
        lenient().when(cmd.getHostId()).thenReturn(null);
        lenient().when(cmd.getBootIntoSetup()).thenReturn(null);
        return cmd;
    }

    @Test
    public void deployFromVolumeWithTokenRelocatesThenDeploys() throws Exception {
        UserVmVO stopped = stoppedKvmVm(VirtualMachine.State.Stopped);
        UserVmVO running = stoppedKvmVm(VirtualMachine.State.Running);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(stopped, running);
        PlacementVerdict approved = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);
        doReturn(new Pair<>(running, new HashMap<>())).when(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(),
                any(DataCenterDeployment.class), anyMap(), nullable(String.class), eq(false), eq(false));
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(30L)).thenReturn(template);

        userVmManagerImpl.startVirtualMachine(deployFromVolumeCmd());

        ArgumentCaptor<PlacementRequest> request = ArgumentCaptor.forClass(PlacementRequest.class);
        InOrder order = Mockito.inOrder(placementService, virtualMachineManager, userVmManagerImpl);
        order.verify(placementService).approvedMove(request.capture(), eq(true), eq("approval"));
        order.verify(virtualMachineManager).relocateStoppedVm("moving-vm", Arrays.asList(201L, 202L), 12L);
        order.verify(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), isNull(), isNull(), isNull(), any(DataCenterDeployment.class), anyMap(),
                nullable(String.class), eq(false), eq(false));
        assertNull("the move was approved before the Instance existed", request.getValue().getApprovalInstanceId());
        verify(volumeDaoMock, never()).detachVolume(Mockito.anyLong());
        verify(volumeOrchestrationService, never()).detachVolumeOfFailedDeployment(anyLong(), anyLong());
    }

    @Test
    public void failedDeployFromVolumeKeepsTheVolume() throws Exception {
        UserVmVO stopped = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(stopped);
        PlacementVerdict verdict = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval")))
                .thenThrow(new VolumeMoveRequiredException("The approved move has expired", 1L, verdict));
        VolumeVO kept = mock(VolumeVO.class);
        lenient().when(kept.getId()).thenReturn(201L);
        lenient().when(kept.getState()).thenReturn(Volume.State.Ready);
        VolumeVO data = mock(VolumeVO.class);
        lenient().when(data.getId()).thenReturn(203L);
        lenient().when(data.getState()).thenReturn(Volume.State.Allocated);
        when(volumeDaoMock.findUsableVolumesForInstance(MOVING_VM_ID)).thenReturn(Arrays.asList(kept, data));

        assertThrows(VolumeMoveRequiredException.class, () -> userVmManagerImpl.startVirtualMachine(deployFromVolumeCmd()));

        InOrder order = Mockito.inOrder(volumeOrchestrationService, virtualMachineManager);
        order.verify(volumeOrchestrationService).detachVolumeOfFailedDeployment(MOVING_VM_ID, 201L);
        order.verify(virtualMachineManager).stateTransitTo(stopped, VirtualMachine.Event.OperationFailedToError, null);
        verify(volumeOrchestrationService, never()).destroyVolume(kept);
        verify(volumeOrchestrationService).destroyVolume(data);
        verify(virtualMachineManager, never()).relocateStoppedVm(anyString(), anyList(), anyLong());
    }

    @Test
    public void aFailedDeploymentWhoseVolumeCouldNotBeDetachedLeavesTheInstanceOutOfError() throws Exception {
        UserVmVO stopped = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(stopped);
        PlacementVerdict verdict = approvedMoveToHostB();
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval")))
                .thenThrow(new VolumeMoveRequiredException("The approved move has expired", 1L, verdict));
        doThrow(new CloudRuntimeException("the database is unavailable")).when(volumeOrchestrationService).detachVolumeOfFailedDeployment(MOVING_VM_ID, 201L);

        VolumeMoveRequiredException e = assertThrows(VolumeMoveRequiredException.class, () -> userVmManagerImpl.startVirtualMachine(deployFromVolumeCmd()));

        assertTrue(e.getMessage(), e.getMessage().contains("expired"));
        verify(virtualMachineManager, never()).stateTransitTo(any(), eq(VirtualMachine.Event.OperationFailedToError), any());
        verify(volumeOrchestrationService, never()).destroyVolume(any());
        verify(alertManager).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_USERVM), eq(1L), any(), eq("Failed to deploy Instance [ID: moving-vm]"),
                Mockito.argThat(body -> body.contains("volume [201]") && !body.contains("host")));
    }

    @Test
    public void startRefusesItsOwnParametersBeforeAnyVolumeMoves() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict approved = approvedMoveToHostB();
        lenient().when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);
        StartVMCmd cmd = startCmd(true, "approval");
        when(cmd.getBootIntoSetup()).thenReturn(true);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.startVirtualMachine(cmd));

        assertTrue(e.getMessage(), e.getMessage().contains(ApiConstants.BOOT_INTO_SETUP));
        verify(placementService, never()).approvedMove(any(), Mockito.anyBoolean(), any());
        verify(virtualMachineManager, never()).relocateStoppedVm(anyString(), anyList(), anyLong());
    }

    @Test
    public void startRefusesTheOwnersRunningLimitsBeforeAnyVolumeMoves() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict approved = approvedMoveToHostB();
        lenient().when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getCpu()).thenReturn(4);
        lenient().when(offering.getRamSize()).thenReturn(8192);
        when(_serviceOfferingDao.findById(MOVING_VM_ID, 20L)).thenReturn(offering);
        AccountVO owner = accountDao.findById(5L);
        doThrow(new ResourceAllocationException("Maximum number of resources of type 'cpu' for account has been exceeded.", Resource.ResourceType.cpu))
                .when(resourceLimitMgr).checkResourceLimitWithTag(owner, Resource.ResourceType.cpu, null, 4L);
        updateDefaultConfigValue(VirtualMachineManager.ResourceCountRunningVMsonly, true, false);

        assertThrows(ResourceAllocationException.class, () -> userVmManagerImpl.startVirtualMachine(startCmd(true, "approval")));

        verify(placementService, never()).approvedMove(any(), Mockito.anyBoolean(), any());
        verify(virtualMachineManager, never()).relocateStoppedVm(anyString(), anyList(), anyLong());
    }

    @Test
    public void deploymentFromVolumeMovesOnlyWhenPlanningPlacesIt() {
        assertTrue(UserVmManagerImpl.startsFromVolumeByPlacement(201L, true, null, null, null));
        assertFalse("no volume", UserVmManagerImpl.startsFromVolumeByPlacement(null, true, null, null, null));
        assertFalse("not started", UserVmManagerImpl.startsFromVolumeByPlacement(201L, false, null, null, null));
        assertFalse("a given pod", UserVmManagerImpl.startsFromVolumeByPlacement(201L, true, 3L, null, null));
        assertFalse("a given cluster", UserVmManagerImpl.startsFromVolumeByPlacement(201L, true, null, 4L, null));
        assertFalse("a given host", UserVmManagerImpl.startsFromVolumeByPlacement(201L, true, null, null, 2L));
    }

    @Test
    public void deploymentFromVolumeOnAGivenPodStartsWithoutPlacement() throws Exception {
        UserVmVO stopped = stoppedKvmVm(VirtualMachine.State.Stopped);
        UserVmVO running = stoppedKvmVm(VirtualMachine.State.Running);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(stopped, running);
        DeployVMCmdByAdmin cmd = mock(DeployVMCmdByAdmin.class);
        when(cmd.getEntityId()).thenReturn(MOVING_VM_ID);
        when(cmd.getStartVm()).thenReturn(true);
        when(cmd.getVolumeId()).thenReturn(201L);
        when(cmd.getPodId()).thenReturn(3L);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getBootIntoSetup()).thenReturn(null);
        when(cmd.getDataDiskTemplateToDiskOfferingMap()).thenReturn(new HashMap<>());
        doReturn(new Pair<>(running, new HashMap<>())).when(userVmManagerImpl).startVirtualMachine(eq(MOVING_VM_ID), eq(3L), isNull(), isNull(), anyMap(),
                nullable(String.class), eq(false));
        when(templateDao.findByIdIncludingRemoved(30L)).thenReturn(mock(VMTemplateVO.class));

        userVmManagerImpl.startVirtualMachine(cmd);

        verify(placementService, never()).approvedMove(any(), Mockito.anyBoolean(), any());
    }

    @Test
    public void approvedMoveToAHostThatIsGoneMovesNothing() throws Exception {
        UserVmVO vm = stoppedKvmVm(VirtualMachine.State.Stopped);
        when(userVmDao.findById(MOVING_VM_ID)).thenReturn(vm);
        PlacementVerdict approved = approvedMoveToHostB();
        when(hostDao.findById(2L)).thenReturn(null);
        when(placementService.approvedMove(any(PlacementRequest.class), eq(true), eq("approval"))).thenReturn(approved);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.startVirtualMachine(startCmd(true, "approval")));

        assertTrue(e.getMessage(), e.getMessage().contains("nothing was moved"));
        verify(virtualMachineManager, never()).relocateStoppedVm(anyString(), anyList(), anyLong());
    }

    private static final long KEEP_VM_ID = 5151L;
    private static final long KEEP_ROOT_ID = 5152L;
    private static final long KEEP_DOMAIN_ID = 7L;

    private UserVmVO vmToDestroy(VirtualMachine.State state) throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        lenient().when(vm.getId()).thenReturn(KEEP_VM_ID);
        lenient().when(vm.getUuid()).thenReturn("keep-vm");
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getUserVmType()).thenReturn("User");
        lenient().when(vm.getDomainId()).thenReturn(KEEP_DOMAIN_ID);
        lenient().when(vm.getAccountId()).thenReturn(5L);
        lenient().when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(vm.getServiceOfferingId()).thenReturn(20L);
        lenient().when(userVmDao.findById(KEEP_VM_ID)).thenReturn(vm);
        lenient().doReturn(vm).when(userVmManagerImpl).stopVirtualMachine(anyLong(), anyBoolean());
        lenient().doReturn(vm).when(userVmManagerImpl).destroyVm(eq(KEEP_VM_ID), anyBoolean());
        lenient().doReturn(true).when(userVmManagerImpl).expunge(vm);
        return vm;
    }

    /** The detach answers with what the volume DAO does to a detached ROOT volume. */
    private VolumeVO rootToKeep() {
        VolumeVO root = new VolumeVO(Volume.Type.ROOT, "ROOT-5151", 1L, KEEP_DOMAIN_ID, 5L, 40L, Storage.ProvisioningType.THIN, 20L * GiB_TO_BYTES,
                null, null, null);
        ReflectionTestUtils.setField(root, "id", KEEP_ROOT_ID);
        root.setInstanceId(KEEP_VM_ID);
        root.setDeviceId(0L);
        root.setState(Volume.State.Ready);
        root.setTemplateId(30L);
        root.setPoolId(12L);
        lenient().when(volumeDaoMock.getInstanceRootVolume(KEEP_VM_ID)).thenReturn(root);
        lenient().when(volumeDaoMock.findById(KEEP_ROOT_ID)).thenReturn(root);
        lenient().doAnswer(invocation -> {
            root.setInstanceId(null);
            root.setDeviceId(null);
            root.setVolumeType(Volume.Type.DATADISK);
            return root;
        }).when(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
        return root;
    }

    private DestroyVMCmd destroyCmd(Boolean keepRootVolume) {
        DestroyVMCmd cmd = mock(DestroyVMCmd.class);
        lenient().when(cmd.getId()).thenReturn(KEEP_VM_ID);
        lenient().when(cmd.getExpunge()).thenReturn(false);
        lenient().when(cmd.getKeepRootVolume()).thenReturn(keepRootVolume);
        return cmd;
    }

    private UserVm destroyAsTheApiDoes(DestroyVMCmd cmd) throws Exception {
        CallContext callContext = mock(CallContext.class);
        lenient().when(callContext.getCallingAccount()).thenReturn(callerAccount);
        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class);
             MockedStatic<UsageEventUtils> ignored = mockStatic(UsageEventUtils.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);
            mockedCallContext.when(() -> CallContext.register(callContext, ApiCommandResourceType.Volume)).thenReturn(callContext);
            return userVmManagerImpl.destroyVm(cmd, true);
        }
    }

    @Test
    public void destroyKeepsRootAsStandaloneVolumeByDefault() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Running);
        VolumeVO root = rootToKeep();
        VolumeVO data = mock(VolumeVO.class);
        lenient().when(data.getId()).thenReturn(5153L);
        lenient().when(data.getInstanceId()).thenReturn(KEEP_VM_ID);
        when(volumeDaoMock.findByInstanceAndType(KEEP_VM_ID, Volume.Type.DATADISK)).thenReturn(List.of(data));

        UserVm result = destroyAsTheApiDoes(destroyCmd(null));

        assertEquals(vm, result);
        InOrder order = Mockito.inOrder(userVmManagerImpl, volumeApiService, backupManager);
        order.verify(volumeApiService).checkRootVolumeDetachSupported(root, vm);
        order.verify(userVmManagerImpl).stopVirtualMachine(KEEP_VM_ID, false);
        order.verify(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
        order.verify(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, 5153L);
        order.verify(backupManager).checkAndRemoveBackupOfferingBeforeExpunge(vm);
        order.verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, true);
        order.verify(userVmManagerImpl).expunge(vm);
        verify(userVmManagerImpl, never()).checkExpungeVmPermission(any(), any());
        verify(volumeApiService, never()).destroyVolume(eq(KEEP_ROOT_ID), any(), anyBoolean(), anyBoolean(), any());

        when(volumeDataFactory.getVolume(KEEP_ROOT_ID)).thenReturn(volumeInfo);
        when(volumeInfo.getDataStore()).thenReturn(primaryDataStore);
        when(primaryDataStore.getScope()).thenReturn(scopeMock);
        when(scopeMock.getScopeType()).thenReturn(ScopeType.HOST);
        when(volumeInfo.getTemplateId()).thenAnswer(invocation -> root.getTemplateId());
        when(volumeInfo.getInstanceId()).thenAnswer(invocation -> root.getInstanceId());
        assertEquals("an Instance is deployed from the kept root", volumeInfo, userVmManagerImpl.getVolume(KEEP_ROOT_ID, null, false));
    }

    @Test
    public void destroyWithKeepFalseUsesStockPath() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Running);
        VolumeVO root = rootToKeep();

        destroyAsTheApiDoes(destroyCmd(false));

        verify(userVmManagerImpl).stopVirtualMachine(KEEP_VM_ID, false);
        verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, false);
        verify(userVmManagerImpl, never()).expunge(vm);
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
        verify(volumeApiService, never()).checkRootVolumeDetachSupported(any(), any());
        assertEquals("the root stays with the destroyed Instance", Long.valueOf(KEEP_VM_ID), root.getInstanceId());
    }

    @Test
    public void keepRootRefusedWithVmSnapshots() throws Exception {
        vmToDestroy(VirtualMachine.State.Running);
        rootToKeep();
        when(vmSnapshotDaoMock.findByVm(KEEP_VM_ID)).thenReturn(List.of(mock(VMSnapshotVO.class)));

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(null)));

        assertTrue(e.getMessage(), e.getMessage().contains("Instance Snapshots"));
        assertTrue(e.getMessage(), e.getMessage().contains(ApiConstants.KEEP_ROOT_VOLUME + "=false"));
        verify(userVmManagerImpl, never()).stopVirtualMachine(anyLong(), anyBoolean());
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(anyLong(), anyLong());
        verify(userVmManagerImpl, never()).destroyVm(eq(KEEP_VM_ID), anyBoolean());
    }

    @Test
    public void keepRootRefusedForAnInstanceBetweenStates() throws Exception {
        vmToDestroy(VirtualMachine.State.Migrating);
        rootToKeep();

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(true)));

        assertTrue(e.getMessage(), e.getMessage().contains("Migrating"));
        verify(userVmManagerImpl, never()).stopVirtualMachine(anyLong(), anyBoolean());
    }

    @Test
    public void anInstanceWithoutDataInItsRootIsDestroyedWithItsRoot() throws Exception {
        UserVmVO failed = vmToDestroy(VirtualMachine.State.Error);
        VolumeVO root = rootToKeep();

        destroyAsTheApiDoes(destroyCmd(null));

        verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, false);
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);

        when(failed.getState()).thenReturn(VirtualMachine.State.Stopped);
        root.setState(Volume.State.Allocated);
        destroyAsTheApiDoes(destroyCmd(null));

        verify(userVmManagerImpl, times(2)).destroyVm(KEEP_VM_ID, false);
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
        verify(userVmManagerImpl, never()).expunge(failed);
    }

    @Test
    public void aRootThatStaysAttachedIsNeverExpungedWithTheInstance() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Running);
        VolumeVO root = rootToKeep();
        doReturn(null).when(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> destroyAsTheApiDoes(destroyCmd(true)));

        assertTrue(e.getMessage(), e.getMessage().contains(root.getUuid()));
        verify(userVmManagerImpl, never()).destroyVm(eq(KEEP_VM_ID), anyBoolean());
        verify(userVmManagerImpl, never()).expunge(vm);
        verify(backupManager, never()).checkAndRemoveBackupOfferingBeforeExpunge(any());
    }

    @Test
    public void keptRootStaysInPrimaryStorageCount() throws Exception {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(UserVmManager.DestroyRootVolumeOnVmDestruction.key(), ConfigKey.Scope.Domain, KEEP_DOMAIN_ID)).thenReturn("true");
        ConfigKey.init(depot);
        try {
            VolumeService engineVolumeService = mock(VolumeService.class);
            ReflectionTestUtils.setField(userVmManagerImpl, "_volService", engineVolumeService);
            UserVmVO vm = vmToDestroy(VirtualMachine.State.Stopped);
            VolumeVO root = rootToKeep();

            destroyAsTheApiDoes(destroyCmd(true));

            verify(engineVolumeService, never()).destroyVolume(anyLong());
            verify(volumeApiService, never()).destroyVolume(eq(KEEP_ROOT_ID), any(), anyBoolean(), anyBoolean(), any());
            verify(volumeOrchestrationService, never()).destroyVolume(root);
            verify(resourceLimitMgr, never()).decrementVolumeResourceCount(anyLong(), any(), any(), any(), any());
            verify(resourceLimitMgr, never()).decrementVolumePrimaryStorageResourceCount(anyLong(), any(), any(), any());
            InOrder order = Mockito.inOrder(volumeApiService, userVmManagerImpl);
            order.verify(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
            order.verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, true);
            order.verify(userVmManagerImpl).expunge(vm);
        } finally {
            ConfigKey.init(null);
        }
    }

    @Test
    public void keepingTheRootOfAnInstanceAlreadyDestroyedIsRefused() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Destroyed);
        rootToKeep();

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(true)));

        assertTrue(e.getMessage(), e.getMessage().contains("already destroyed"));
        verify(userVmManagerImpl, never()).destroyVm(eq(KEEP_VM_ID), anyBoolean());
        verify(userVmManagerImpl, never()).expunge(vm);
        assertEquals("without asking, a destroyed Instance stays as it is", vm, destroyAsTheApiDoes(destroyCmd(null)));
    }

    @Test
    public void aRootThatCannotBeDetachedGoesWithTheInstanceUnlessKeepingWasAsked() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Running);
        rootToKeep();
        doThrow(new InvalidParameterValueException("Root volume detach is not supported for hypervisor type Hyperv")).when(volumeApiService)
                .checkRootVolumeDetachSupported(any(), any());

        destroyAsTheApiDoes(destroyCmd(null));

        verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, false);
        verify(userVmManagerImpl, never()).expunge(vm);
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(true)));

        assertTrue(e.getMessage(), e.getMessage().contains("Hyperv"));
        assertTrue(e.getMessage(), e.getMessage().contains(ApiConstants.KEEP_ROOT_VOLUME + "=false"));
        verify(userVmManagerImpl, times(1)).stopVirtualMachine(anyLong(), anyBoolean());
    }

    @Test
    public void anInstanceWithoutARootIsExpungedWhenItsRootIsKept() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Stopped);
        lenient().when(volumeDaoMock.getInstanceRootVolume(KEEP_VM_ID)).thenReturn(null);

        destroyAsTheApiDoes(destroyCmd(null));

        verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, true);
        verify(userVmManagerImpl).expunge(vm);
        verify(volumeApiService, never()).detachVolumeViaDestroyVM(anyLong(), anyLong());
    }

    @Test
    public void backupsBlockKeepingTheRootBeforeAnythingChanges() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Running);
        rootToKeep();
        lenient().when(vm.getBackupOfferingId()).thenReturn(9L);
        lenient().when(vm.getDataCenterId()).thenReturn(zoneId);
        when(backupDao.listByVmIdAndOffering(zoneId, KEEP_VM_ID, 9L)).thenReturn(List.of(mock(Backup.class)));

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(null)));

        assertTrue(e.getMessage(), e.getMessage().startsWith("Unable to keep the ROOT volume of Instance "));
        assertTrue(e.getMessage(), e.getMessage().contains(ApiConstants.KEEP_ROOT_VOLUME + "=false"));
        verify(userVmManagerImpl, never()).stopVirtualMachine(anyLong(), anyBoolean());
        verify(backupManager, never()).checkAndRemoveBackupOfferingBeforeExpunge(any());
    }

    @Test
    public void backupsBlockExpungingAnInstanceWithoutARoot() throws Exception {
        UserVmVO vm = vmToDestroy(VirtualMachine.State.Stopped);
        lenient().when(volumeDaoMock.getInstanceRootVolume(KEEP_VM_ID)).thenReturn(null);
        lenient().when(vm.getBackupOfferingId()).thenReturn(9L);
        lenient().when(vm.getDataCenterId()).thenReturn(zoneId);
        when(backupDao.listByVmIdAndOffering(zoneId, KEEP_VM_ID, 9L)).thenReturn(List.of(mock(Backup.class)));

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> destroyAsTheApiDoes(destroyCmd(null)));

        assertTrue(e.getMessage(), e.getMessage().startsWith("Unable to expunge Instance "));
        assertTrue(e.getMessage(), e.getMessage().contains(ApiConstants.KEEP_ROOT_VOLUME + "=false"));
        verify(userVmManagerImpl, never()).stopVirtualMachine(anyLong(), anyBoolean());
    }

    @Test
    public void keepRootDefaultFollowsDestroyRootSetting() throws Exception {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(UserVmManager.DestroyRootVolumeOnVmDestruction.key(), ConfigKey.Scope.Domain, KEEP_DOMAIN_ID)).thenReturn("true");
        ConfigKey.init(depot);
        try {
            assertFalse(UserVmManager.keepRootVolumeByDefault(KEEP_DOMAIN_ID));
            assertTrue(UserVmManager.keepRootVolumeByDefault(9L));
            VolumeService engineVolumeService = mock(VolumeService.class);
            ReflectionTestUtils.setField(userVmManagerImpl, "_volService", engineVolumeService);
            vmToDestroy(VirtualMachine.State.Running);
            VolumeVO root = rootToKeep();

            destroyAsTheApiDoes(destroyCmd(null));

            verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, false);
            verify(engineVolumeService).destroyVolume(KEEP_ROOT_ID);
            verify(volumeApiService, never()).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);

            destroyAsTheApiDoes(destroyCmd(true));

            verify(volumeApiService).detachVolumeViaDestroyVM(KEEP_VM_ID, KEEP_ROOT_ID);
            verify(userVmManagerImpl).destroyVm(KEEP_VM_ID, true);
            verify(engineVolumeService, times(1)).destroyVolume(KEEP_ROOT_ID);
            assertNull(root.getInstanceId());
        } finally {
            ConfigKey.init(null);
        }
    }

    private VMTemplateVO templateOfSize(long bytes) {
        VMTemplateVO template = mock(VMTemplateVO.class);
        lenient().when(template.getSize()).thenReturn(bytes);
        lenient().when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        return template;
    }

    private DiskOfferingVO computeOnlyRootOffering(long fixedSizeBytes) {
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        lenient().when(offering.isComputeOnly()).thenReturn(true);
        lenient().when(offering.getDiskSize()).thenReturn(fixedSizeBytes);
        return offering;
    }

    private long rootSize(Map<String, String> parameters, VMTemplateVO template, long domainId) {
        return userVmManagerImpl.configureCustomRootDiskSize(parameters, template, Hypervisor.HypervisorType.KVM, computeOnlyRootOffering(0L), true, domainId);
    }

    private static Map<String, String> rootDiskSizeGiB(String size) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(VmDetailConstants.ROOT_DISK_SIZE, size);
        return parameters;
    }

    @Test
    public void rootDefaultsTo20GiBWhenAbsent() {
        Map<String, String> parameters = new HashMap<>();

        assertEquals(20L * GiB_TO_BYTES, rootSize(parameters, templateOfSize(2362232013L), 1L));
        assertEquals("20", parameters.get(VmDetailConstants.ROOT_DISK_SIZE));
    }

    @Test
    public void rootDefaultUsesTemplateSizeWhenLarger() {
        Map<String, String> parameters = new HashMap<>();

        assertEquals(25L * GiB_TO_BYTES, rootSize(parameters, templateOfSize(24L * GiB_TO_BYTES + GiB_TO_BYTES / 2), 1L));
        assertEquals("25", parameters.get(VmDetailConstants.ROOT_DISK_SIZE));
    }

    @Test
    public void rootBelowFloorRejectedNamingFloor() {
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class,
                () -> rootSize(rootDiskSizeGiB("9"), templateOfSize(2L * GiB_TO_BYTES), 1L));

        assertTrue(e.getMessage(), e.getMessage().contains("10"));
        assertEquals(10L * GiB_TO_BYTES, rootSize(rootDiskSizeGiB("10"), templateOfSize(2L * GiB_TO_BYTES), 1L));
    }

    @Test
    public void rootBelowTemplateRejected() {
        VMTemplateVO template = templateOfSize(12L * GiB_TO_BYTES + GiB_TO_BYTES / 2);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> rootSize(rootDiskSizeGiB("12"), template, 1L));

        assertTrue(e.getMessage(), e.getMessage().contains("13"));
        assertEquals(13L * GiB_TO_BYTES, rootSize(rootDiskSizeGiB("13"), template, 1L));
    }

    @Test
    public void rootBoundsAreTheOwnersDomainSettings() {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(UserVmManager.VmRootDiskSizeDefault.key(), ConfigKey.Scope.Domain, 7L)).thenReturn("40");
        lenient().when(depot.getConfigStringValue(UserVmManager.VmRootDiskSizeMin.key(), ConfigKey.Scope.Domain, 7L)).thenReturn("30");
        lenient().when(depot.getConfigStringValue(UserVmManager.VmRootDiskSizeDefault.key(), ConfigKey.Scope.Domain, 8L)).thenReturn("5");
        ConfigKey.init(depot);
        try {
            assertEquals(40L * GiB_TO_BYTES, rootSize(new HashMap<>(), templateOfSize(2L * GiB_TO_BYTES), 7L));
            InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class,
                    () -> rootSize(rootDiskSizeGiB("29"), templateOfSize(2L * GiB_TO_BYTES), 7L));
            assertTrue(e.getMessage(), e.getMessage().contains("30"));
            assertEquals("a default below the minimum gives the minimum", 10L * GiB_TO_BYTES, rootSize(new HashMap<>(), templateOfSize(2L * GiB_TO_BYTES), 8L));
            assertEquals(20L * GiB_TO_BYTES, rootSize(new HashMap<>(), templateOfSize(2L * GiB_TO_BYTES), 9L));
        } finally {
            ConfigKey.init(null);
        }
    }

    @Test
    public void isoVolumeAndSnapshotRootsKeepTheirSizes() {
        VMTemplateVO iso = templateOfSize(GiB_TO_BYTES / 2);
        when(iso.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        Map<String, String> isoParameters = new HashMap<>();

        assertEquals(GiB_TO_BYTES / 2, rootSize(isoParameters, iso, 1L));
        assertFalse(isoParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE));
        assertEquals(5L * GiB_TO_BYTES, rootSize(rootDiskSizeGiB("5"), iso, 1L));

        Map<String, String> fromVolume = new HashMap<>();
        assertEquals(2L * GiB_TO_BYTES, userVmManagerImpl.configureCustomRootDiskSize(fromVolume, templateOfSize(2L * GiB_TO_BYTES), Hypervisor.HypervisorType.KVM,
                computeOnlyRootOffering(0L), false, 1L));
        assertFalse(fromVolume.containsKey(VmDetailConstants.ROOT_DISK_SIZE));
    }

    @Test
    public void dataDiskRangeUnchanged() {
        VolumeApiServiceImpl volumes = new VolumeApiServiceImpl() { };
        ReflectionTestUtils.setField(userVmManagerImpl, "_volumeService", volumes);
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        ConfigKey.init(depot);
        try {
            volumes.validateCustomDiskOfferingSizeRange(1L);
            assertThrows(InvalidParameterValueException.class, () -> volumes.validateCustomDiskOfferingSizeRange(2048L));
            assertThrows(InvalidParameterValueException.class, () -> volumes.validateVolumeSizeInBytes(2048L * GiB_TO_BYTES));
            assertThrows(InvalidParameterValueException.class, () -> rootSize(rootDiskSizeGiB("2048"), templateOfSize(2L * GiB_TO_BYTES), 1L));

            lenient().when(depot.getConfigStringValue(VolumeOrchestrationService.CustomDiskOfferingMaxSize.key(), ConfigKey.Scope.Global, null)).thenReturn("65536");
            lenient().when(depot.getConfigStringValue(VolumeOrchestrationService.MaxVolumeSize.key(), ConfigKey.Scope.Global, null)).thenReturn("65536");

            volumes.validateCustomDiskOfferingSizeRange(1L);
            volumes.validateCustomDiskOfferingSizeRange(2048L);
            assertTrue("a data disk and a resize take the same check", volumes.validateVolumeSizeInBytes(2048L * GiB_TO_BYTES));
            assertEquals(2048L * GiB_TO_BYTES, rootSize(rootDiskSizeGiB("2048"), templateOfSize(2L * GiB_TO_BYTES), 1L));
        } finally {
            ConfigKey.init(null);
        }
    }

    @Test
    public void restoreWithRootBelowFloorRejected() throws Exception {
        RestoreVMCmd cmd = mock(RestoreVMCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getTemplateId()).thenReturn(null);
        when(cmd.getRootDiskOfferingId()).thenReturn(null);
        when(cmd.getDetails()).thenReturn(rootDiskSizeGiB("9"));
        when(userVmVoMock.getTemplateId()).thenReturn(templateId);
        lenient().when(userVmVoMock.getDomainId()).thenReturn(1L);
        doReturn(false).when(userVmManagerImpl).isVMPartOfAnyCKSCluster(userVmVoMock);
        VMTemplateVO template = templateOfSize(2L * GiB_TO_BYTES);
        when(templateDao.findById(templateId)).thenReturn(template);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.restoreVM(cmd));

        assertTrue(e.getMessage(), e.getMessage().contains("10"));
        verify(virtualMachineManager, never()).restoreVirtualMachine(anyLong(), any(), any(), anyBoolean(), any());

        when(cmd.getDetails()).thenReturn(rootDiskSizeGiB("10"));
        userVmManagerImpl.restoreVM(cmd);
        verify(virtualMachineManager).restoreVirtualMachine(anyLong(), isNull(), isNull(), eq(false), any());
    }

    private static final long OFFERING_CHANGE_VM_ID = 6161L;

    private ServiceOfferingVO offeringWithRoot(long id, boolean strict, long diskOfferingId, Long vgpuProfileId) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        lenient().when(offering.getId()).thenReturn(id);
        lenient().when(offering.getCpu()).thenReturn(4);
        lenient().when(offering.getRamSize()).thenReturn(4096);
        lenient().when(offering.getSpeed()).thenReturn(2000);
        lenient().when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        lenient().when(offering.getDiskOfferingStrictness()).thenReturn(strict);
        lenient().when(offering.getDiskOfferingId()).thenReturn(diskOfferingId);
        lenient().when(offering.getVgpuProfileId()).thenReturn(vgpuProfileId);
        lenient().when(_serviceOfferingDao.findById(id)).thenReturn(offering);
        return offering;
    }

    private DiskOfferingVO rootDiskOffering(long id, long fixedSizeBytes) {
        DiskOfferingVO offering = computeOnlyRootOffering(fixedSizeBytes);
        lenient().when(offering.getId()).thenReturn(id);
        lenient().when(diskOfferingDao.findById(id)).thenReturn(offering);
        return offering;
    }

    private VolumeVO stoppedInstanceOn(ServiceOfferingVO current) {
        long currentId = current.getId();
        long currentDiskOfferingId = current.getDiskOfferingId();
        VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_vmInstanceDao", vmInstanceDao);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(OFFERING_CHANGE_VM_ID);
        lenient().when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        lenient().when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        lenient().when(vm.getDomainId()).thenReturn(1L);
        lenient().when(vm.getAccountId()).thenReturn(5L);
        lenient().when(vm.getDataCenterId()).thenReturn(zoneId);
        lenient().when(vm.getServiceOfferingId()).thenReturn(currentId);
        lenient().when(vm.getTemplateId()).thenReturn(templateId);
        lenient().when(vm.isDisplay()).thenReturn(true);
        when(vmInstanceDao.findById(OFFERING_CHANGE_VM_ID)).thenReturn(vm);
        lenient().when(_serviceOfferingDao.findByIdIncludingRemoved(OFFERING_CHANGE_VM_ID, currentId)).thenReturn(current);
        lenient().when(accountManager.getActiveAccountById(5L)).thenReturn(account);
        VolumeVO root = new VolumeVO(Volume.Type.ROOT, "ROOT-6161", zoneId, 1L, 5L, currentDiskOfferingId, Storage.ProvisioningType.THIN,
                20L * GiB_TO_BYTES, null, null, null);
        root.setState(Volume.State.Ready);
        lenient().when(volumeDaoMock.findReadyAndAllocatedRootVolumesByInstance(OFFERING_CHANGE_VM_ID)).thenReturn(List.of(root));
        return root;
    }

    @Test
    public void changeToComputeOnlyRootOfferingFromNonStrictAllowed() throws Exception {
        ServiceOfferingVO nonStrictOffering = offeringWithRoot(21L, false, 41L, null);
        rootDiskOffering(41L, 0L);
        ServiceOfferingVO targetOffering = offeringWithRoot(22L, true, 42L, null);
        DiskOfferingVO targetRoot = rootDiskOffering(42L, 0L);
        VolumeVO root = stoppedInstanceOn(nonStrictOffering);

        assertTrue(userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, new HashMap<>()));

        InOrder order = Mockito.inOrder(volumeApiService, virtualMachineManager);
        order.verify(volumeApiService).reserveRootDiskOfferingChange(eq(root), eq(targetRoot), anyList());
        order.verify(volumeApiService).assignDiskOfferingKeepingSize(root, targetRoot);
        order.verify(virtualMachineManager).upgradeVmDb(OFFERING_CHANGE_VM_ID, targetOffering, nonStrictOffering);
    }

    @Test
    public void changeBetweenComputeOnlyRootOfferingsAllowed() throws Exception {
        ServiceOfferingVO vgpuOffering = offeringWithRoot(23L, true, 43L, 3L);
        rootDiskOffering(43L, 0L);
        ServiceOfferingVO targetOffering = offeringWithRoot(22L, true, 42L, null);
        DiskOfferingVO targetRoot = rootDiskOffering(42L, 0L);
        VolumeVO root = stoppedInstanceOn(vgpuOffering);

        assertTrue(userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, new HashMap<>()));

        verify(volumeApiService).reserveRootDiskOfferingChange(eq(root), eq(targetRoot), anyList());
        verify(volumeApiService).assignDiskOfferingKeepingSize(root, targetRoot);
        verify(virtualMachineManager).upgradeVmDb(OFFERING_CHANGE_VM_ID, targetOffering, vgpuOffering);
    }

    @Test
    public void offeringChangeRootKeepsSizeTakesNewDiskOffering() throws Exception {
        updateDefaultConfigValue(UserVmManager.AllowDiskOfferingChangeDuringScaleVm, true, false);
        ServiceOfferingVO nonStrictOffering = offeringWithRoot(21L, false, 41L, null);
        rootDiskOffering(41L, 0L);
        offeringWithRoot(22L, true, 42L, null);
        DiskOfferingVO targetRoot = rootDiskOffering(42L, 0L);
        VolumeVO root = stoppedInstanceOn(nonStrictOffering);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class,
                () -> userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, rootDiskSizeGiB("30")));
        assertTrue(e.getMessage(), e.getMessage().contains("keeps its size"));
        verify(volumeApiService, never()).assignDiskOfferingKeepingSize(any(), any());

        assertTrue(userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, rootDiskSizeGiB("20")));

        verify(volumeApiService).assignDiskOfferingKeepingSize(root, targetRoot);
        verify(volumeApiService, never()).changeDiskOfferingForVolume(any());
    }

    /** Records, for each step of an offering change, whether it ran inside the transaction; the change fails inside when asked. */
    private List<String> changeOfferingRecordingTheTransaction(Reserver reservation, boolean failInside) throws Exception {
        ServiceOfferingVO nonStrictOffering = offeringWithRoot(21L, false, 41L, null);
        rootDiskOffering(41L, 0L);
        offeringWithRoot(22L, true, 42L, null);
        rootDiskOffering(42L, 0L);
        stoppedInstanceOn(nonStrictOffering);
        boolean[] inTransaction = {false};
        List<String> calls = new ArrayList<>();
        lenient().doAnswer(invocation -> {
            calls.add("reserve:" + inTransaction[0]);
            ((List<Reserver>) invocation.getArgument(2)).add(reservation);
            return null;
        }).when(volumeApiService).reserveRootDiskOfferingChange(any(), any(), anyList());
        lenient().doAnswer(invocation -> {
            calls.add("assign:" + inTransaction[0]);
            return invocation.getArgument(0);
        }).when(volumeApiService).assignDiskOfferingKeepingSize(any(), any());
        lenient().doAnswer(invocation -> {
            calls.add("upgradeVmDb:" + inTransaction[0]);
            if (failInside) {
                throw new CloudRuntimeException("the Instance row could not be updated");
            }
            return true;
        }).when(virtualMachineManager).upgradeVmDb(anyLong(), any(), any());
        lenient().doAnswer(invocation -> {
            calls.add("inform:" + inTransaction[0]);
            return null;
        }).when(volumeApiService).informStorageOfDiskOfferingChange(any(), any());

        try (MockedStatic<Transaction> transaction = mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
                inTransaction[0] = true;
                try {
                    return ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null);
                } finally {
                    inTransaction[0] = false;
                }
            });
            userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, new HashMap<>());
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage(), failInside);
        }
        return calls;
    }

    @Test
    public void offeringChangeRelabelsTheRootAndChangesTheOfferingInOneTransaction() throws Exception {
        Reserver reservation = mock(Reserver.class);

        List<String> calls = changeOfferingRecordingTheTransaction(reservation, false);

        assertEquals("the storage tag change is reserved before the transaction, the root and the Instance change inside it, and the storage is "
                + "told once it has committed", List.of("reserve:false", "assign:true", "upgradeVmDb:true", "inform:false"), calls);
        verify(reservation).close();
    }

    @Test
    public void aFailedOfferingChangeTellsTheStorageNothingAndReleasesItsReservations() throws Exception {
        Reserver reservation = mock(Reserver.class);

        List<String> calls = changeOfferingRecordingTheTransaction(reservation, true);

        assertEquals(List.of("reserve:false", "assign:true", "upgradeVmDb:true"), calls);
        verify(volumeApiService, never()).informStorageOfDiskOfferingChange(any(), any());
        verify(reservation).close();
    }

    @Test
    public void aFailedCountOfANewInstanceDetachesTheVolumeItWasDeployedFrom() {
        UserVmVO created = mock(UserVmVO.class);
        when(created.getId()).thenReturn(KEEP_VM_ID);
        when(created.getUuid()).thenReturn("new-instance");
        Volume given = mock(Volume.class);
        when(given.getId()).thenReturn(KEEP_ROOT_ID);
        doThrow(new CloudRuntimeException("the resource count could not be updated")).when(userVmManagerImpl)
                .resourceCountIncrement(anyLong(), any(), any(), any(), any());

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> userVmManagerImpl.incrementResourceCountForNewInstance(created, accountId, true, serviceOffering, templateMock, given));

        verify(volumeOrchestrationService).detachVolumeOfFailedDeployment(KEEP_VM_ID, KEEP_ROOT_ID);
        assertTrue(e.getIdProxyList().stream().anyMatch(proxy -> "new-instance".equals(proxy.getUuid())));
        assertThrows(CloudRuntimeException.class,
                () -> userVmManagerImpl.incrementResourceCountForNewInstance(created, accountId, true, serviceOffering, templateMock, null));
        doNothing().when(userVmManagerImpl).resourceCountIncrement(anyLong(), any(), any(), any(), any());
        userVmManagerImpl.incrementResourceCountForNewInstance(created, accountId, true, serviceOffering, templateMock, given);
        verify(volumeOrchestrationService, times(1)).detachVolumeOfFailedDeployment(anyLong(), anyLong());
    }

    @Test
    public void changeToFixedRootDiskOfferingStillRefused() throws Exception {
        offeringWithRoot(24L, true, 44L, null);
        rootDiskOffering(44L, 20L * GiB_TO_BYTES);

        stoppedInstanceOn(offeringWithRoot(23L, true, 43L, 3L));
        assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 24L, new HashMap<>()));

        stoppedInstanceOn(offeringWithRoot(21L, false, 41L, null));
        assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 24L, new HashMap<>()));

        verify(volumeApiService, never()).reserveRootDiskOfferingChange(any(), any(), anyList());
        verify(volumeApiService, never()).assignDiskOfferingKeepingSize(any(), any());
        verify(virtualMachineManager, never()).upgradeVmDb(anyLong(), any(), any());
    }

    @Test
    public void offeringChangeStillChecksResourceLimits() throws Exception {
        ServiceOfferingVO nonStrictOffering = offeringWithRoot(21L, false, 41L, null);
        rootDiskOffering(41L, 0L);
        ServiceOfferingVO targetOffering = offeringWithRoot(22L, true, 42L, null);
        rootDiskOffering(42L, 0L);
        stoppedInstanceOn(nonStrictOffering);
        doThrow(new ResourceAllocationException("Maximum number of resources of type 'cpu' for account has been exceeded.", Resource.ResourceType.cpu))
                .when(resourceLimitMgr).checkVmResourceLimitsForServiceOfferingChange(eq(account), eq(true), eq(4L), eq(4L), eq(4096L), eq(4096L),
                        eq(nonStrictOffering), eq(targetOffering), any(), anyList());

        assertThrows(ResourceAllocationException.class, () -> userVmManagerImpl.upgradeVirtualMachine(OFFERING_CHANGE_VM_ID, 22L, new HashMap<>()));

        verify(volumeApiService, never()).assignDiskOfferingKeepingSize(any(), any());
        verify(virtualMachineManager, never()).upgradeVmDb(anyLong(), any(), any());
    }

    @Test
    public void deployFromVolumeReservesOnlyTheRootsStorageTagChange() throws Exception {
        ServiceOfferingVO targetOffering = offeringWithRoot(22L, true, 42L, null);
        DiskOfferingVO targetRoot = rootDiskOffering(42L, 0L);
        VolumeVO kept = new VolumeVO(Volume.Type.DATADISK, "ROOT-5151", zoneId, 1L, 5L, 43L, Storage.ProvisioningType.THIN, 20L * GiB_TO_BYTES,
                null, null, null);
        ReflectionTestUtils.setField(kept, "id", KEEP_ROOT_ID);
        kept.setState(Volume.State.Ready);
        when(volumeDaoMock.findById(KEEP_ROOT_ID)).thenReturn(kept);
        when(volumeApiService.takesRootDiskOffering(kept, targetRoot)).thenReturn(true);
        List<Reserver> reservations = new ArrayList<>();

        try (MockedConstruction<CheckedReservation> created = Mockito.mockConstruction(CheckedReservation.class)) {
            userVmManagerImpl.reserveStorageResourcesForVm(reservations, account, null, null, null, 42L, targetOffering, 2L * GiB_TO_BYTES, null, kept);

            assertTrue("the volume is counted already, so no new root volume or storage is reserved", created.constructed().isEmpty());
            verify(volumeApiService).reserveRootDiskOfferingChange(kept, targetRoot, reservations);

            when(volumeApiService.takesRootDiskOffering(kept, targetRoot)).thenReturn(false);
            userVmManagerImpl.reserveStorageResourcesForVm(reservations, account, null, null, null, 42L, targetOffering, 2L * GiB_TO_BYTES, null, kept);

            assertTrue("a volume that keeps its disk offering reserves nothing", created.constructed().isEmpty());
            verify(volumeApiService, times(1)).reserveRootDiskOfferingChange(any(), any(), anyList());
        }
    }

    private static final long BACKUP_ID = 4L;

    /**
     * Deploys an Instance from a backup whose root was 5 GiB, and returns what the root disk size computation gives for the details the
     * deployment is handed: the size in bytes, or the refusal.
     */
    private Object rootOfAnInstanceFromABackup(Long callerRootDiskSizeGiB, Long overrideDiskOfferingId, DiskOfferingVO overrideDiskOffering) throws Exception {
        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd._accountService = accountService;
        cmd._entityMgr = entityManager;
        lenient().when(accountService.finalizeAccountId(nullable(String.class), nullable(Long.class), nullable(Long.class), eq(true))).thenReturn(accountId);
        lenient().when(accountService.getActiveAccountById(accountId)).thenReturn(account);
        ReflectionTestUtils.setField(cmd, "serviceOfferingId", serviceOfferingId);
        ReflectionTestUtils.setField(cmd, "templateId", templateId);
        ReflectionTestUtils.setField(cmd, "backupId", BACKUP_ID);
        ReflectionTestUtils.setField(cmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(cmd, "rootdisksize", callerRootDiskSizeGiB);
        ReflectionTestUtils.setField(cmd, "overrideDiskOfferingId", overrideDiskOfferingId);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        lenient().when(offering.getDiskOfferingId()).thenReturn(40L);
        when(_serviceOfferingDao.findById(serviceOfferingId)).thenReturn(offering);
        DiskOfferingVO offeringRoot = rootDiskOffering(40L, 0L);
        if (overrideDiskOfferingId != null) {
            lenient().when(diskOfferingDao.findById(overrideDiskOfferingId)).thenReturn(overrideDiskOffering);
        }
        BackupVO backup = mock(BackupVO.class);
        when(backup.getZoneId()).thenReturn(zoneId);
        when(backup.getVmId()).thenReturn(vmId);
        when(backupDao.findById(BACKUP_ID)).thenReturn(backup);
        UserVmVO backedUp = new UserVmVO();
        backedUp.setTemplateId(templateId);
        when(userVmDao.findByIdIncludingRemoved(vmId)).thenReturn(backedUp);
        VMTemplateVO template = templateOfSize(2L * GiB_TO_BYTES);
        when(templateDao.findById(templateId)).thenReturn(template);
        when(backupManager.getRootDiskInfoFromBackup(backup)).thenReturn(new VmDiskInfo(mock(DiskOffering.class), 5L, null, null));
        when(backupManager.canCreateInstanceFromBackup(BACKUP_ID)).thenReturn(true);
        DiskOfferingVO rootOffering = overrideDiskOffering != null ? overrideDiskOffering : offeringRoot;
        Object[] outcome = {null};
        Mockito.doAnswer(invocation -> {
            Map<String, String> details = invocation.getArgument(22);
            try {
                outcome[0] = userVmManagerImpl.configureCustomRootDiskSize(new HashMap<>(details), template, Hypervisor.HypervisorType.KVM, rootOffering,
                        userVmManagerImpl.boundsTemplateRoot(null, null), 1L);
            } catch (InvalidParameterValueException e) {
                outcome[0] = e;
            }
            return userVmVoMock;
        }).when(userVmManagerImpl).createAdvancedVirtualMachine(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), nullable(Boolean.class), any(), any(), any(),
                any(), any(), any(), any(), eq(true), any(), any(), any(), any(), any());

        userVmManagerImpl.allocateVMFromBackup(cmd);

        assertTrue("the next deployment is bounded again", userVmManagerImpl.boundsTemplateRoot(null, null));
        return outcome[0];
    }

    @Test
    public void aBackupsRecordedRootIsDeployedAtItsSize() throws Exception {
        assertEquals("neither the floor nor the default", 5L * GiB_TO_BYTES, rootOfAnInstanceFromABackup(null, null, null));
    }

    @Test
    public void aCallersRootSizeIsReplacedByTheRecordedOne() throws Exception {
        assertEquals(5L * GiB_TO_BYTES, rootOfAnInstanceFromABackup(30L, null, null));
    }

    @Test
    public void aCallersRootSizeOnACustomizedOverrideIsBoundedByTheFloor() throws Exception {
        DiskOfferingVO customized = mock(DiskOfferingVO.class);
        lenient().when(customized.isCustomized()).thenReturn(true);
        lenient().when(customized.isComputeOnly()).thenReturn(false);
        lenient().when(customized.getDiskSize()).thenReturn(0L);

        Object outcome = rootOfAnInstanceFromABackup(5L, 45L, customized);

        assertTrue(String.valueOf(outcome), outcome instanceof InvalidParameterValueException);
        assertTrue(((InvalidParameterValueException) outcome).getMessage(), ((InvalidParameterValueException) outcome).getMessage().contains("10"));
    }

    private static final long MIGRATION_VM_ID = 9201L;
    private static final long MIGRATION_HOST_ID = 9202L;
    private static final String LIVE_MIGRATION_DISABLED = "live migration is disabled";
    private static final String PAST_THE_STATE_CHECK = "the request got past the live-migration state check";
    private static final List<VirtualMachine.State> MIGRATION_REFUSED_STATES = List.of(VirtualMachine.State.Running, VirtualMachine.State.Migrating);

    private VMInstanceVO vmToMigrate(VirtualMachine.State state, VirtualMachine.Type type) {
        VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_vmInstanceDao", vmInstanceDao);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(MIGRATION_VM_ID);
        lenient().when(vm.getUuid()).thenReturn("migration-vm-uuid");
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getType()).thenReturn(type);
        lenient().when(vm.getHostId()).thenReturn(MIGRATION_HOST_ID);
        lenient().when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(vmInstanceDao.findById(MIGRATION_VM_ID)).thenReturn(vm);
        lenient().when(accountManager.isRootAdmin(anyLong())).thenReturn(true);
        ResourceManager resourceManager = mock(ResourceManager.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_resourceMgr", resourceManager);
        lenient().doThrow(new IllegalStateException(PAST_THE_STATE_CHECK)).when(resourceManager).getHost(MIGRATION_HOST_ID);
        lenient().doThrow(new IllegalStateException(PAST_THE_STATE_CHECK)).when(hostDao).findById(MIGRATION_HOST_ID);
        return vm;
    }

    private static void assertLiveMigrationRefused(org.junit.function.ThrowingRunnable migration) {
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, migration);
        assertEquals(LIVE_MIGRATION_DISABLED, e.getMessage());
    }

    @Test
    public void migrateRunningVmRefused() throws Exception {
        for (VirtualMachine.State state : MIGRATION_REFUSED_STATES) {
            vmToMigrate(state, VirtualMachine.Type.User);

            assertLiveMigrationRefused(() -> userVmManagerImpl.migrateVirtualMachine(MIGRATION_VM_ID, mock(Host.class)));
            assertLiveMigrationRefused(() -> userVmManagerImpl.migrateVirtualMachine(MIGRATION_VM_ID, null));
        }

        verify(virtualMachineManager, never()).migrate(anyString(), anyLong(), any());
        verify(virtualMachineManager, never()).migrateWithStorage(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    public void migrateWithVolumeRunningVmRefused() throws Exception {
        for (VirtualMachine.State state : MIGRATION_REFUSED_STATES) {
            vmToMigrate(state, VirtualMachine.Type.User);

            assertLiveMigrationRefused(() -> userVmManagerImpl.migrateVirtualMachineWithVolume(MIGRATION_VM_ID, mock(Host.class), new HashMap<>()));
            assertLiveMigrationRefused(() -> userVmManagerImpl.migrateVirtualMachineWithVolume(MIGRATION_VM_ID, null, Map.of("volume-uuid", "pool-uuid")));
        }

        verify(virtualMachineManager, never()).migrate(anyString(), anyLong(), any());
        verify(virtualMachineManager, never()).migrateWithStorage(anyString(), anyLong(), anyLong(), any());
    }

    private MigrateSystemVMCmd systemVmMigration(ResourceService resourceService, Long hostId, Boolean autoSelect) {
        MigrateSystemVMCmd cmd = new MigrateSystemVMCmd();
        cmd._userVmService = userVmManagerImpl;
        cmd._resourceService = resourceService;
        ReflectionTestUtils.setField(cmd, "virtualMachineId", MIGRATION_VM_ID);
        ReflectionTestUtils.setField(cmd, "hostId", hostId);
        ReflectionTestUtils.setField(cmd, "autoSelect", autoSelect);
        return cmd;
    }

    @Test
    public void migrateSystemVmRunningRefused() throws Exception {
        long destinationHostId = MIGRATION_HOST_ID + 1;
        Host destination = mock(Host.class);
        lenient().when(destination.getType()).thenReturn(Host.Type.Routing);
        ResourceService resourceService = mock(ResourceService.class);
        lenient().when(resourceService.getHost(destinationHostId)).thenReturn(destination);

        for (VirtualMachine.State state : MIGRATION_REFUSED_STATES) {
            for (VirtualMachine.Type type : List.of(VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.DomainRouter)) {
                vmToMigrate(state, type);
                MigrateSystemVMCmd toAHost = systemVmMigration(resourceService, destinationHostId, null);
                MigrateSystemVMCmd anyHost = systemVmMigration(resourceService, null, true);

                assertLiveMigrationRefused(toAHost::execute);
                assertLiveMigrationRefused(anyHost::execute);
            }
        }

        verify(virtualMachineManager, never()).migrate(anyString(), anyLong(), any());
        verify(virtualMachineManager, never()).migrateWithStorage(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    public void liveMigrationRefusalFollowsThePermissionCheck() throws Exception {
        vmToMigrate(VirtualMachine.State.Running, VirtualMachine.Type.User);
        when(accountManager.isRootAdmin(anyLong())).thenReturn(false);

        assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.migrateVirtualMachine(MIGRATION_VM_ID, null));
        assertThrows(PermissionDeniedException.class, () -> userVmManagerImpl.migrateVirtualMachineWithVolume(MIGRATION_VM_ID, null, new HashMap<>()));
    }

    @Test
    public void aVmThatIsNotRunningKeepsTheStockRefusal() throws Exception {
        vmToMigrate(VirtualMachine.State.Stopped, VirtualMachine.Type.User);

        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.migrateVirtualMachine(MIGRATION_VM_ID, null));
        assertTrue(e.getMessage(), e.getMessage().contains("not Running"));
        CloudRuntimeException withVolume = assertThrows(CloudRuntimeException.class, () -> userVmManagerImpl.migrateVirtualMachineWithVolume(MIGRATION_VM_ID, null, new HashMap<>()));
        assertTrue(withVolume.getMessage(), withVolume.getMessage().contains("not in Running state"));
    }

    private StoragePool poolForTheRootOfAStoppedVm() {
        VolumeVO root = mock(VolumeVO.class);
        lenient().when(root.getId()).thenReturn(9301L);
        lenient().when(root.getUuid()).thenReturn("root-uuid");
        lenient().when(root.getPoolId()).thenReturn(9400L);
        StoragePoolVO source = mock(StoragePoolVO.class);
        lenient().when(source.getScope()).thenReturn(ScopeType.HOST);
        lenient().when(source.getPodId()).thenReturn(1L);
        lenient().when(primaryDataStoreDao.findById(9400L)).thenReturn(source);
        when(volumeDaoMock.findByInstance(MIGRATION_VM_ID)).thenReturn(List.of(root));
        lenient().when(volumeDaoMock.findByUuid("root-uuid")).thenReturn(root);
        lenient().when(volumeDaoMock.getHypervisorType(9301L)).thenReturn(Hypervisor.HypervisorType.KVM);
        StoragePool destination = mock(StoragePool.class);
        lenient().when(destination.getId()).thenReturn(9401L);
        lenient().when(destination.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        StoragePoolVO destinationVo = mock(StoragePoolVO.class);
        lenient().when(destinationVo.getId()).thenReturn(9401L);
        lenient().when(destinationVo.getHypervisor()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(destinationVo.getScope()).thenReturn(ScopeType.HOST);
        lenient().when(destinationVo.getPodId()).thenReturn(1L);
        when(primaryDataStoreDao.findById(9401L)).thenReturn(destinationVo);
        lenient().when(primaryDataStoreDao.findPoolByUUID("pool-uuid")).thenReturn(destinationVo);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(any(), any())).thenReturn(new Pair<>(true, null));
        userVmManagerImpl.snapshotHelper = mock(SnapshotHelper.class);
        return destination;
    }

    @Test
    public void stoppedVmStorageMigrationStillAllowed() throws Exception {
        VMInstanceVO vm = vmToMigrate(VirtualMachine.State.Stopped, VirtualMachine.Type.User);
        lenient().when(vm.getHostId()).thenReturn(null);
        StoragePool destination = poolForTheRootOfAStoppedVm();

        userVmManagerImpl.vmStorageMigration(MIGRATION_VM_ID, destination);
        userVmManagerImpl.vmStorageMigration(MIGRATION_VM_ID, Map.of("root-uuid", "pool-uuid"));

        verify(virtualMachineManager, times(2)).storageMigration("migration-vm-uuid", Map.of(9301L, 9401L));
        verify(virtualMachineManager, never()).migrate(anyString(), anyLong(), any());
        verify(virtualMachineManager, never()).migrateWithStorage(anyString(), anyLong(), anyLong(), any());

        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.vmStorageMigration(MIGRATION_VM_ID, destination));
        verify(virtualMachineManager, times(2)).storageMigration(anyString(), anyMap());
    }

    private static final long RECOVER_VM_ID = 9501L;

    private RecoverVMCmd recoveryOfADestroyedVm(boolean rootStillOfTheVm) {
        UserVmVO vm = mock(UserVmVO.class);
        lenient().when(vm.getId()).thenReturn(RECOVER_VM_ID);
        lenient().when(vm.getUuid()).thenReturn("recover-vm-uuid");
        lenient().when(vm.getState()).thenReturn(VirtualMachine.State.Destroyed);
        lenient().when(userVmDao.findById(RECOVER_VM_ID)).thenReturn(vm);
        lenient().when(accountManager.isAdmin(anyLong())).thenReturn(true);
        VolumeVO root = mock(VolumeVO.class);
        when(volumeDaoMock.findByInstanceAndType(RECOVER_VM_ID, Volume.Type.ROOT)).thenReturn(rootStillOfTheVm ? List.of(root) : Collections.emptyList());
        RecoverVMCmd cmd = mock(RecoverVMCmd.class);
        when(cmd.getId()).thenReturn(RECOVER_VM_ID);
        return cmd;
    }

    @Test
    public void recoverRefusedForAnInstanceWhoseRootIsGone() throws Exception {
        RecoverVMCmd cmd = recoveryOfADestroyedVm(false);

        try (MockedStatic<Transaction> transaction = mockStatic(Transaction.class)) {
            InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.recoverVirtualMachine(cmd));

            assertTrue(e.getMessage(), e.getMessage().contains("ROOT volume"));
            transaction.verify(() -> Transaction.execute(any(TransactionCallbackWithExceptionNoReturn.class)), never());
        }
    }

    @Test
    public void recoverProceedsForAnInstanceWithItsRoot() throws Exception {
        RecoverVMCmd cmd = recoveryOfADestroyedVm(true);

        try (MockedStatic<Transaction> transaction = mockStatic(Transaction.class)) {
            userVmManagerImpl.recoverVirtualMachine(cmd);

            transaction.verify(() -> Transaction.execute(any(TransactionCallbackWithExceptionNoReturn.class)));
        }
    }

    @Test
    public void storageMigrationOfAStoppedVmIsRoutedByTheCommandsToVmStorageMigration() throws Exception {
        VMInstanceVO vm = vmToMigrate(VirtualMachine.State.Stopped, VirtualMachine.Type.User);
        lenient().when(vm.getHostId()).thenReturn(null);
        StoragePool destination = poolForTheRootOfAStoppedVm();
        UserVmVO userVm = mock(UserVmVO.class);
        lenient().when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(userVmDao.findById(MIGRATION_VM_ID)).thenReturn(userVm);
        ResponseGenerator responseGenerator = mock(ResponseGenerator.class);
        lenient().when(responseGenerator.createUserVmResponse(any(), anyString(), any(UserVm.class))).thenReturn(List.of(new UserVmResponse()));
        lenient().when(responseGenerator.createSystemVmResponse(any())).thenReturn(new SystemVmResponse());
        StorageService storageService = mock(StorageService.class);
        lenient().when(storageService.getStoragePool(9401L)).thenReturn(destination);

        MigrateVMCmd migrate = new MigrateVMCmd();
        migrate._userVmService = userVmManagerImpl;
        migrate._storageService = storageService;
        migrate._responseGenerator = responseGenerator;
        ReflectionTestUtils.setField(migrate, "virtualMachineId", MIGRATION_VM_ID);
        ReflectionTestUtils.setField(migrate, "storageId", 9401L);
        migrate.execute();

        MigrateVirtualMachineWithVolumeCmd migrateWithVolume = new MigrateVirtualMachineWithVolumeCmd();
        migrateWithVolume._userVmService = userVmManagerImpl;
        migrateWithVolume._responseGenerator = responseGenerator;
        ReflectionTestUtils.setField(migrateWithVolume, "virtualMachineId", MIGRATION_VM_ID);
        ReflectionTestUtils.setField(migrateWithVolume, "migrateVolumeTo", Map.of("0", new HashMap<>(Map.of("volume", "root-uuid", "pool", "pool-uuid"))));
        migrateWithVolume.execute();

        VMInstanceVO systemVm = vmToMigrate(VirtualMachine.State.Stopped, VirtualMachine.Type.SecondaryStorageVm);
        lenient().when(systemVm.getHostId()).thenReturn(null);
        MigrateSystemVMCmd migrateSystemVm = new MigrateSystemVMCmd();
        migrateSystemVm._userVmService = userVmManagerImpl;
        migrateSystemVm._storageService = storageService;
        migrateSystemVm._responseGenerator = responseGenerator;
        ReflectionTestUtils.setField(migrateSystemVm, "virtualMachineId", MIGRATION_VM_ID);
        ReflectionTestUtils.setField(migrateSystemVm, "storageId", 9401L);
        migrateSystemVm.execute();

        verify(virtualMachineManager, times(3)).storageMigration("migration-vm-uuid", Map.of(9301L, 9401L));
        verify(userVmManagerImpl, never()).migrateVirtualMachine(any(), any());
        verify(userVmManagerImpl, never()).migrateVirtualMachineWithVolume(any(), any(), any());
    }

    private static final long SCALE_VM_ID = 9601L;
    private static final long SCALE_HOST_ID = 9602L;

    private ServiceOfferingVO scaleOffering(long id, int cpu, int ramMb) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        lenient().when(offering.getId()).thenReturn(id);
        lenient().when(offering.getCpu()).thenReturn(cpu);
        lenient().when(offering.getRamSize()).thenReturn(ramMb);
        lenient().when(offering.getSpeed()).thenReturn(1000);
        lenient().when(offering.isDynamicScalingEnabled()).thenReturn(true);
        return offering;
    }

    @Test
    public void liveScaleThatNeedsAnotherHostIsRefused() throws Exception {
        VMInstanceDao vmInstanceDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(userVmManagerImpl, "_vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(userVmManagerImpl, "_scaleRetry", 2);
        ReflectionTestUtils.setField(userVmManagerImpl, "_capacityMgr", mock(CapacityManager.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        updateDefaultConfigValue(UserVmManager.AutoMigrateVmOnLiveScaleInsufficientCapacity, true, false);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(SCALE_VM_ID);
        lenient().when(vm.getUuid()).thenReturn("scale-vm-uuid");
        lenient().when(vm.getInstanceName()).thenReturn("i-scale");
        lenient().when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        lenient().when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        lenient().when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        lenient().when(vm.getDomainId()).thenReturn(1L);
        lenient().when(vm.getHostId()).thenReturn(SCALE_HOST_ID);
        lenient().when(vm.getServiceOfferingId()).thenReturn(10L);
        lenient().when(vm.getDataCenterId()).thenReturn(1L);
        lenient().when(vm.isDynamicallyScalable()).thenReturn(true);
        lenient().when(vmInstanceDao.findById(SCALE_VM_ID)).thenReturn(vm);
        HostVO host = mock(HostVO.class);
        lenient().when(host.getId()).thenReturn(SCALE_HOST_ID);
        lenient().when(host.getClusterId()).thenReturn(3L);
        lenient().when(host.getName()).thenReturn("scale-host");
        lenient().when(host.checkHostServiceOfferingAndTemplateTags(any(), any(), any())).thenReturn(true);
        lenient().when(hostDao.findById(SCALE_HOST_ID)).thenReturn(host);
        ServiceOfferingVO current = scaleOffering(10L, 2, 2048);
        ServiceOfferingVO bigger = scaleOffering(11L, 4, 4096);
        lenient().when(_serviceOfferingDao.findById(11L)).thenReturn(bigger);
        lenient().when(_serviceOfferingDao.findByIdIncludingRemoved(SCALE_VM_ID, 10L)).thenReturn(current);
        doNothing().when(userVmManagerImpl).validateDiskOfferingChecks(any(), any());
        lenient().doThrow(new IllegalStateException("a live migration was attempted")).when(virtualMachineManager).findHostAndMigrate(any(), any(), any(), any());

        try (MockedStatic<ApiDBUtils> apiDbUtils = mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.findHostById(SCALE_HOST_ID)).thenReturn(host);

            InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> userVmManagerImpl.upgradeVirtualMachine(SCALE_VM_ID, 11L, new HashMap<>()));

            assertTrue(e.getMessage(), e.getMessage().startsWith("live migration is disabled"));
            assertTrue(e.getMessage(), e.getMessage().contains("compute capacity"));
            assertTrue(e.getMessage(), e.getMessage().contains("i-scale"));
            assertFalse(e.getMessage(), e.getMessage().contains("scale-host"));
        }

        verify(virtualMachineManager, never()).findHostAndMigrate(any(), any(), any(), any());
        verify(virtualMachineManager, never()).reConfigureVm(any(), any(), any(), any(), anyBoolean());
        verify(resourceLimitMgr, times(2)).updateVmResourceCountForServiceOfferingChange(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
