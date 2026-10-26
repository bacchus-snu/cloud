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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import com.cloud.agent.api.UnmanageInstanceAnswer;
import com.cloud.agent.api.UnmanageInstanceCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.api.ApiDBUtils;
import com.cloud.event.ActionEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.clvm.ClvmPoolManager;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.extensions.vo.ExtensionDetailsVO;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.collections.MapUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineMetadataTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.capacity.HostPoolCoreManager;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.VolumeMoveRequiredException;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.serializer.GsonHelper;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.deploy.PlacementVerdict;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import org.apache.cloudstack.gpu.GpuService;
import org.apache.cloudstack.backup.BackupManager;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.configuration.Resource;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Journal;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VirtualMachineManagerImplTest {

    @Spy
    @InjectMocks
    private VirtualMachineManagerImpl virtualMachineManagerImpl = new VirtualMachineManagerImpl();
    @Mock
    private AgentManager agentManagerMock;
    @Mock
    private VMInstanceDao vmInstanceDaoMock;
    @Mock
    private ServiceOfferingDao serviceOfferingDaoMock;
    @Mock
    private VolumeDao volumeDaoMock;
    @Mock
    private PrimaryDataStoreDao storagePoolDaoMock;
    @Mock
    private VMInstanceVO vmInstanceMock;
    @Mock
    private VmWorkJobDao _workJobDao;
    @Mock
    private ResourceLimitService resourceLimitServiceMock;

    private long vmInstanceVoMockId = 1L;

    @Mock
    private ServiceOfferingVO serviceOfferingMock;

    @Mock
    private SnapshotManager snapshotManagerMock;

    @Mock
    private DiskOfferingVO diskOfferingMock;

    private long hostMockId = 1L;
    private long clusterMockId = 2L;
    private long zoneMockId = 3L;
    private final String vmMockUuid = UUID.randomUUID().toString();
    private final String hostUuid = UUID.randomUUID().toString();

    @Mock
    private HostVO hostMock;
    @Mock
    private DataCenterDeployment dataCenterDeploymentMock;

    @Mock
    private VirtualMachineProfile virtualMachineProfileMock;
    @Mock
    private StoragePoolVO storagePoolVoMock;
    private long storagePoolVoMockId = 11L;
    private long storagePoolVoMockClusterId = 234L;
    private String vmName = "vm1";

    @Mock
    private VolumeVO volumeVoMock;
    private long volumeMockId = 1111L;

    @Mock
    private StoragePoolHostDao storagePoolHostDaoMock;

    @Mock
    private StoragePoolAllocator storagePoolAllocatorMock;

    @Mock
    private DiskOfferingDao diskOfferingDaoMock;
    @Mock
    VMTemplateDao templateDao;
    @Mock
    VMTemplateZoneDao templateZoneDao;

    @Mock
    private HostDao hostDaoMock;
    @Mock
    private UserVmJoinDao userVmJoinDaoMock;
    @Mock
    private UserVmDao userVmDaoMock;
    @Mock
    private UserVmVO userVmMock;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private VpcDao vpcDao;
    @Mock
    private EntityManager _entityMgr;
    @Mock
    private ResourceManager _resourceMgr;
    @Mock
    private DeploymentPlanningManager _dpMgr;
    @Mock
    private HypervisorGuruManager _hvGuruMgr;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private ClusterDetailsDao _clusterDetailsDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private ItWorkDao _workDao;
    @Mock
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;
    @Mock
    ExtensionsManager extensionsManager;
    @Mock
    ExtensionDetailsDao extensionDetailsDao;
    @Mock
    NicDao _nicsDao;
    @Mock
    NetworkService networkService;
    @Mock
    NetworkModel networkModel;
    @Mock
    VolumeDataFactory volumeDataFactoryMock;
    @Mock
    StorageManager storageManager;
    @Mock
    private HighAvailabilityManager _haMgr;
    @Mock
    VirtualMachineGuru guru;

    private ConfigDepotImpl configDepotImpl;
    private boolean updatedConfigKeyDepot = false;

    @Before
    public void setup() {
        ReflectionTestUtils.getField(VirtualMachineManager.VmMetadataManufacturer, "s_depot");
        virtualMachineManagerImpl.setHostAllocators(new ArrayList<>());

        when(vmInstanceMock.getName()).thenReturn(vmName);
        when(vmInstanceMock.getId()).thenReturn(vmInstanceVoMockId);
        when(vmInstanceMock.getServiceOfferingId()).thenReturn(2L);
        when(hostMock.getId()).thenReturn(hostMockId);
        when(dataCenterDeploymentMock.getHostId()).thenReturn(hostMockId);
        when(dataCenterDeploymentMock.getClusterId()).thenReturn(clusterMockId);

        when(hostMock.getDataCenterId()).thenReturn(zoneMockId);
        when(hostDaoMock.findById(any())).thenReturn(hostMock);

        when(userVmJoinDaoMock.searchByIds(any())).thenReturn(new ArrayList<>());
        when(userVmDaoMock.findById(any())).thenReturn(userVmMock);

        Mockito.doReturn(vmInstanceVoMockId).when(virtualMachineProfileMock).getId();

        Mockito.doReturn(storagePoolVoMockId).when(storagePoolVoMock).getId();

        Mockito.doReturn(volumeMockId).when(volumeVoMock).getId();
        Mockito.doReturn(storagePoolVoMockId).when(volumeVoMock).getPoolId();

        Mockito.doReturn(volumeVoMock).when(volumeDaoMock).findById(volumeMockId);
        Mockito.doReturn(storagePoolVoMock).when(storagePoolDaoMock).findById(storagePoolVoMockId);

        ArrayList<StoragePoolAllocator> storagePoolAllocators = new ArrayList<>();
        storagePoolAllocators.add(storagePoolAllocatorMock);
        virtualMachineManagerImpl.setStoragePoolAllocators(storagePoolAllocators);
    }

    @After
    public void cleanup() {
        if (updatedConfigKeyDepot) {
            ReflectionTestUtils.setField(VirtualMachineManager.VmMetadataManufacturer, "s_depot", configDepotImpl);
        }
    }

    private ClvmPoolManager injectMockedClvmPoolManager() {
        ClvmPoolManager clvmPoolManagerMock = mock(ClvmPoolManager.class);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "clvmPoolManager", clvmPoolManagerMock);
        return clvmPoolManagerMock;
    }

    private Method getUpdateClvmLockHostForVmVolumesMethod() throws NoSuchMethodException {
        Method method = VirtualMachineManagerImpl.class.getDeclaredMethod(
            "updateClvmLockHostForVmVolumes", long.class, long.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    public void testaddHostIpToCertDetailsIfConfigAllows() {
        Host vmHost = mock(Host.class);
        ConfigKey testConfig = mock(ConfigKey.class);

        Long dataCenterId = 5L;
        String hostIp = "1.1.1.1";
        String routerIp = "2.2.2.2";
        Map<String, String> ipAddresses = new HashMap<>();
        ipAddresses.put(NetworkElementCommand.ROUTER_IP, routerIp);

        when(testConfig.valueIn(dataCenterId)).thenReturn(true);
        when(vmHost.getDataCenterId()).thenReturn(dataCenterId);
        when(vmHost.getPrivateIpAddress()).thenReturn(hostIp);

        virtualMachineManagerImpl.addHostIpToCertDetailsIfConfigAllows(vmHost, ipAddresses, testConfig);
        assertTrue(ipAddresses.containsKey(NetworkElementCommand.HYPERVISOR_HOST_PRIVATE_IP));
        assertEquals(hostIp, ipAddresses.get(NetworkElementCommand.HYPERVISOR_HOST_PRIVATE_IP));
        assertTrue(ipAddresses.containsKey(NetworkElementCommand.ROUTER_IP));
        assertEquals(routerIp, ipAddresses.get(NetworkElementCommand.ROUTER_IP));
    }

    @Test
    public void testaddHostIpToCertDetailsIfConfigAllowsWhenConfigFalse() {
        Host vmHost = mock(Host.class);
        ConfigKey testConfig = mock(ConfigKey.class);

        Long dataCenterId = 5L;
        String hostIp = "1.1.1.1";
        String routerIp = "2.2.2.2";
        Map<String, String> ipAddresses = new HashMap<>();
        ipAddresses.put(NetworkElementCommand.ROUTER_IP, routerIp);

        when(testConfig.valueIn(dataCenterId)).thenReturn(false);
        when(vmHost.getDataCenterId()).thenReturn(dataCenterId);

        virtualMachineManagerImpl.addHostIpToCertDetailsIfConfigAllows(vmHost, ipAddresses, testConfig);
        assertFalse(ipAddresses.containsKey(NetworkElementCommand.HYPERVISOR_HOST_PRIVATE_IP));
        assertTrue(ipAddresses.containsKey(NetworkElementCommand.ROUTER_IP));
        assertEquals(routerIp, ipAddresses.get(NetworkElementCommand.ROUTER_IP));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testScaleVM3() throws Exception {
        DeploymentPlanner.ExcludeList excludeHostList = new DeploymentPlanner.ExcludeList();
        virtualMachineManagerImpl.findHostAndMigrate(vmInstanceMock.getUuid(), 2l, null, excludeHostList);
    }

    @Test
    public void testSendStopWithOkAnswer() throws Exception {
        VirtualMachineGuru guru = mock(VirtualMachineGuru.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        StopAnswer answer = new StopAnswer(new StopCommand(vm, false, false), "ok", true);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(1L);
        when(agentManagerMock.send(anyLong(), (Command)any())).thenReturn(answer);

        boolean actual = virtualMachineManagerImpl.sendStop(guru, profile, false, false);

        Assert.assertTrue(actual);
    }

    @Test
    public void testSendStopWithFailAnswer() throws Exception {
        VirtualMachineGuru guru = mock(VirtualMachineGuru.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        StopAnswer answer = new StopAnswer(new StopCommand(vm, false, false), "fail", false);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(1L);
        when(agentManagerMock.send(anyLong(), (Command)any())).thenReturn(answer);

        boolean actual = virtualMachineManagerImpl.sendStop(guru, profile, false, false);

        assertFalse(actual);
    }

    @Test
    public void testSendStopWithNullAnswer() throws Exception {
        VirtualMachineGuru guru = mock(VirtualMachineGuru.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getHostId()).thenReturn(1L);
        when(agentManagerMock.send(anyLong(), (Command)any())).thenReturn(null);

        boolean actual = virtualMachineManagerImpl.sendStop(guru, profile, false, false);

        assertFalse(actual);
    }

    @Test
    public void testExeceuteInSequence() {
        assertTrue(virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.XenServer) == false);
        assertTrue(virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.KVM) == false);
        assertTrue(virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.Ovm3) == VirtualMachineManager.ExecuteInSequence.value());
    }

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String value) throws IllegalAccessException, NoSuchFieldException {
        final Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(configKey, value);
    }

    @Test
    public void testExeceuteInSequenceVmware() throws IllegalAccessException, NoSuchFieldException {
        overrideDefaultConfigValue(StorageManager.VmwareCreateCloneFull, "false");
        overrideDefaultConfigValue(StorageManager.VmwareAllowParallelExecution, "false");
        assertFalse("no full clones so no need to execute in sequence", virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.VMware));
        overrideDefaultConfigValue(StorageManager.VmwareCreateCloneFull, "true");
        overrideDefaultConfigValue(StorageManager.VmwareAllowParallelExecution, "false");
        assertTrue("full clones and no explicit parallel execution allowed, should execute in sequence", virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.VMware));
        overrideDefaultConfigValue(StorageManager.VmwareCreateCloneFull, "true");
        overrideDefaultConfigValue(StorageManager.VmwareAllowParallelExecution, "true");
        assertFalse("execute in sequence should not be needed as parallel is allowed", virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.VMware));
        overrideDefaultConfigValue(StorageManager.VmwareCreateCloneFull, "false");
        overrideDefaultConfigValue(StorageManager.VmwareAllowParallelExecution, "true");
        assertFalse("double reasons to allow parallel execution", virtualMachineManagerImpl.getExecuteInSequence(HypervisorType.VMware));
    }

    @Test
    public void testCheckIfCanUpgrade() throws Exception {
        when(vmInstanceMock.getState()).thenReturn(State.Stopped);
        when(serviceOfferingMock.isDynamic()).thenReturn(true);
        when(vmInstanceMock.getServiceOfferingId()).thenReturn(1l);

        ServiceOfferingVO mockCurrentServiceOffering = mock(ServiceOfferingVO.class);
        DiskOfferingVO mockCurrentDiskOffering = mock(DiskOfferingVO.class);

        when(serviceOfferingDaoMock.findByIdIncludingRemoved(anyLong(), anyLong())).thenReturn(mockCurrentServiceOffering);
        when(diskOfferingDaoMock.findByIdIncludingRemoved(anyLong())).thenReturn(mockCurrentDiskOffering);
        when(diskOfferingDaoMock.findById(anyLong())).thenReturn(diskOfferingMock);
        when(diskOfferingMock.isUseLocalStorage()).thenReturn(false);
        when(mockCurrentServiceOffering.isSystemUse()).thenReturn(true);
        when(serviceOfferingMock.isSystemUse()).thenReturn(true);
        String[] oldDOStorageTags = {"x","y"};
        String[] newDOStorageTags = {"z","x","y"};
        when(mockCurrentDiskOffering.getTagsArray()).thenReturn(oldDOStorageTags);
        when(diskOfferingMock.getTagsArray()).thenReturn(newDOStorageTags);

        virtualMachineManagerImpl.checkIfCanUpgrade(vmInstanceMock, serviceOfferingMock);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckIfCanUpgradeFail() {
        when(serviceOfferingMock.getState()).thenReturn(ServiceOffering.State.Inactive);

        virtualMachineManagerImpl.checkIfCanUpgrade(vmInstanceMock, serviceOfferingMock);
    }

    @Test
    public void isStorageCrossClusterMigrationTestStorageTypeEqualsCluster() {
        Mockito.doReturn(2L).when(storagePoolVoMock).getClusterId();
        Mockito.doReturn(ScopeType.CLUSTER).when(storagePoolVoMock).getScope();

        boolean returnedValue = virtualMachineManagerImpl.isStorageCrossClusterMigration(1L, storagePoolVoMock);

        Assert.assertTrue(returnedValue);
    }

    @Test
    public void isStorageCrossClusterMigrationTestStorageSameCluster() {
        Mockito.doReturn(1L).when(storagePoolVoMock).getClusterId();
        Mockito.doReturn(ScopeType.CLUSTER).when(storagePoolVoMock).getScope();

        boolean returnedValue = virtualMachineManagerImpl.isStorageCrossClusterMigration(1L, storagePoolVoMock);

        assertFalse(returnedValue);
    }

    @Test
    public void isStorageCrossClusterMigrationTestStorageTypeEqualsZone() {
        Mockito.doReturn(ScopeType.ZONE).when(storagePoolVoMock).getScope();

        boolean returnedValue = virtualMachineManagerImpl.isStorageCrossClusterMigration(1L, storagePoolVoMock);

        assertFalse(returnedValue);
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolProvidedTestCurrentStoragePoolNotManaged() {
        Mockito.doReturn(false).when(storagePoolVoMock).isManaged();

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolProvided(storagePoolVoMock, volumeVoMock, Mockito.mock(StoragePoolVO.class));

        verify(storagePoolVoMock).isManaged();
        verify(storagePoolVoMock, Mockito.times(0)).getId();
    }

    @Test
    public void allowVolumeMigrationsForPowerFlexStorage() {
        Mockito.doReturn(true).when(storagePoolVoMock).isManaged();
        Mockito.doReturn(Storage.StoragePoolType.PowerFlex).when(storagePoolVoMock).getPoolType();

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolProvided(storagePoolVoMock, volumeVoMock, Mockito.mock(StoragePoolVO.class));

        verify(storagePoolVoMock).isManaged();
        verify(storagePoolVoMock, Mockito.times(0)).getId();
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolProvidedTestCurrentStoragePoolEqualsTargetPool() {
        Mockito.doReturn(true).when(storagePoolVoMock).isManaged();
        // return any storage type except powerflex/scaleio
        List<Storage.StoragePoolType> values = Arrays.asList(Storage.StoragePoolType.values());
        when(storagePoolVoMock.getPoolType()).thenAnswer((Answer<Storage.StoragePoolType>) invocation -> {
            List<Storage.StoragePoolType> filteredValues = values.stream().filter(v -> v != Storage.StoragePoolType.PowerFlex).collect(Collectors.toList());
            int randomIndex = new Random().nextInt(filteredValues.size());
            return filteredValues.get(randomIndex); });

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolProvided(storagePoolVoMock, volumeVoMock, storagePoolVoMock);

        verify(storagePoolVoMock).isManaged();
        verify(storagePoolVoMock, Mockito.times(2)).getId();
    }

    @Test(expected = CloudRuntimeException.class)
    public void executeManagedStorageChecksWhenTargetStoragePoolProvidedTestCurrentStoragePoolNotEqualsTargetPool() {
        Mockito.doReturn(true).when(storagePoolVoMock).isManaged();
        // return any storage type except powerflex/scaleio
        List<Storage.StoragePoolType> values = Arrays.asList(Storage.StoragePoolType.values());
        when(storagePoolVoMock.getPoolType()).thenAnswer((Answer<Storage.StoragePoolType>) invocation -> {
            List<Storage.StoragePoolType> filteredValues = values.stream().filter(v -> v != Storage.StoragePoolType.PowerFlex).collect(Collectors.toList());
            int randomIndex = new Random().nextInt(filteredValues.size());
            return filteredValues.get(randomIndex); });

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolProvided(storagePoolVoMock, volumeVoMock, Mockito.mock(StoragePoolVO.class));
    }

    @Test
    public void buildMapUsingUserInformationTestUserDefinedMigrationMapEmpty() {
        HashMap<Long, Long> userDefinedVolumeToStoragePoolMap = Mockito.spy(new HashMap<>());

        Map<Volume, StoragePool> volumeToPoolObjectMap = virtualMachineManagerImpl.buildMapUsingUserInformation(virtualMachineProfileMock, hostMock, userDefinedVolumeToStoragePoolMap);

        Assert.assertTrue(volumeToPoolObjectMap.isEmpty());

        verify(userDefinedVolumeToStoragePoolMap, times(0)).keySet();
    }

    @Test(expected = CloudRuntimeException.class)
    public void buildMapUsingUserInformationTestTargetHostDoesNotHaveAccessToPool() {
        HashMap<Long, Long> userDefinedVolumeToStoragePoolMap = new HashMap<>();
        userDefinedVolumeToStoragePoolMap.put(volumeMockId, storagePoolVoMockId);

        Mockito.doNothing().when(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolProvided(any(StoragePoolVO.class), any(VolumeVO.class), any(StoragePoolVO.class));
        Mockito.doReturn(null).when(storagePoolHostDaoMock).findByPoolHost(storagePoolVoMockId, hostMockId);

        virtualMachineManagerImpl.buildMapUsingUserInformation(virtualMachineProfileMock, hostMock, userDefinedVolumeToStoragePoolMap);

    }

    @Test
    public void buildMapUsingUserInformationTestTargetHostHasAccessToPool() {
        HashMap<Long, Long> userDefinedVolumeToStoragePoolMap = Mockito.spy(new HashMap<>());
        userDefinedVolumeToStoragePoolMap.put(volumeMockId, storagePoolVoMockId);

        Mockito.doNothing().when(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolProvided(any(StoragePoolVO.class), any(VolumeVO.class),
                any(StoragePoolVO.class));
        Mockito.doReturn(Mockito.mock(StoragePoolHostVO.class)).when(storagePoolHostDaoMock).findByPoolHost(storagePoolVoMockId, hostMockId);

        Map<Volume, StoragePool> volumeToPoolObjectMap = virtualMachineManagerImpl.buildMapUsingUserInformation(virtualMachineProfileMock, hostMock, userDefinedVolumeToStoragePoolMap);

        assertFalse(volumeToPoolObjectMap.isEmpty());
        assertEquals(storagePoolVoMock, volumeToPoolObjectMap.get(volumeVoMock));

        verify(userDefinedVolumeToStoragePoolMap, times(1)).keySet();
    }

    @Test
    public void findVolumesThatWereNotMappedByTheUserTest() {
        Map<Volume, StoragePool> volumeToStoragePoolObjectMap = Mockito.spy(new HashMap<>());
        volumeToStoragePoolObjectMap.put(volumeVoMock, storagePoolVoMock);

        Volume volumeVoMock2 = Mockito.mock(Volume.class);

        List<Volume> volumesOfVm = new ArrayList<>();
        volumesOfVm.add(volumeVoMock);
        volumesOfVm.add(volumeVoMock2);

        Mockito.doReturn(volumesOfVm).when(volumeDaoMock).findUsableVolumesForInstance(vmInstanceVoMockId);
        List<Volume> volumesNotMapped = virtualMachineManagerImpl.findVolumesThatWereNotMappedByTheUser(virtualMachineProfileMock, volumeToStoragePoolObjectMap);

        assertEquals(1, volumesNotMapped.size());
        assertEquals(volumeVoMock2, volumesNotMapped.get(0));
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvidedTestCurrentStoragePoolNotManaged() {
        Mockito.doReturn(false).when(storagePoolVoMock).isManaged();

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);

        verify(storagePoolVoMock).isManaged();
        verify(storagePoolHostDaoMock, Mockito.times(0)).findByPoolHost(anyLong(), anyLong());
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvidedTestCurrentStoragePoolManagedIsConnectedToHost() {
        Mockito.doReturn(true).when(storagePoolVoMock).isManaged();
        Mockito.doReturn(Mockito.mock(StoragePoolHostVO.class)).when(storagePoolHostDaoMock).findByPoolHost(storagePoolVoMockId, hostMockId);

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);

        verify(storagePoolVoMock).isManaged();
        verify(storagePoolHostDaoMock, Mockito.times(1)).findByPoolHost(storagePoolVoMockId, hostMockId);
    }

    @Test(expected = CloudRuntimeException.class)
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvidedTestCurrentStoragePoolManagedIsNotConnectedToHost() {
        Mockito.doReturn(true).when(storagePoolVoMock).isManaged();
        Mockito.doReturn(null).when(storagePoolHostDaoMock).findByPoolHost(storagePoolVoMockId, hostMockId);

        virtualMachineManagerImpl.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
    }

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolumeTestLocalVolume() {
        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());

        Mockito.doReturn(true).when(storagePoolVoMock).isLocal();

        List<StoragePool> poolListMock = new ArrayList<>();
        poolListMock.add(storagePoolVoMock);

        Mockito.doReturn(poolListMock).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        List<StoragePool> poolList = virtualMachineManagerImpl.getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        assertEquals(1, poolList.size());
        assertEquals(storagePoolVoMock, poolList.get(0));
    }

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolumeTestCrossClusterMigration() {
        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());

        Mockito.doReturn(false).when(storagePoolVoMock).isLocal();

        List<StoragePool> poolListMock = new ArrayList<>();
        poolListMock.add(storagePoolVoMock);

        Mockito.doReturn(poolListMock).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        Mockito.doReturn(true).when(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);
        List<StoragePool> poolList = virtualMachineManagerImpl.getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        assertEquals(1, poolList.size());
        assertEquals(storagePoolVoMock, poolList.get(0));
    }

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolumeTestWithinClusterMigration() {
        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());

        Mockito.doReturn(false).when(storagePoolVoMock).isLocal();

        List<StoragePool> poolListMock = new ArrayList<>();
        poolListMock.add(storagePoolVoMock);

        Mockito.doReturn(poolListMock).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        Mockito.doReturn(false).when(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);
        List<StoragePool> poolList = virtualMachineManagerImpl.getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        Assert.assertTrue(poolList.isEmpty());
    }

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolumeTestMoreThanOneAllocator() {
        StoragePoolAllocator storagePoolAllocatorMock2 = Mockito.mock(StoragePoolAllocator.class);
        StoragePoolAllocator storagePoolAllocatorMock3 = Mockito.mock(StoragePoolAllocator.class);

        List<StoragePoolAllocator> storagePoolAllocatorsMock = new ArrayList<>();
        storagePoolAllocatorsMock.add(storagePoolAllocatorMock);
        storagePoolAllocatorsMock.add(storagePoolAllocatorMock2);
        storagePoolAllocatorsMock.add(storagePoolAllocatorMock3);

        virtualMachineManagerImpl.setStoragePoolAllocators(storagePoolAllocatorsMock);

        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());

        Mockito.doReturn(false).when(storagePoolVoMock).isLocal();

        List<StoragePool> poolListMock = new ArrayList<>();
        poolListMock.add(storagePoolVoMock);

        Mockito.doReturn(poolListMock).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        Mockito.doReturn(null).when(storagePoolAllocatorMock2).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        Mockito.doReturn(new ArrayList<>()).when(storagePoolAllocatorMock3).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));

        Mockito.doReturn(false).when(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);
        List<StoragePool> poolList = virtualMachineManagerImpl.getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        Assert.assertTrue(poolList.isEmpty());

        verify(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));
        verify(storagePoolAllocatorMock2).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));
        verify(storagePoolAllocatorMock3).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));
    }

    @Test(expected = CloudRuntimeException.class)
    public void createVolumeToStoragePoolMappingIfPossibleTestNotStoragePoolsAvailable() {
        Mockito.doReturn(null).when(virtualMachineManagerImpl).getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        virtualMachineManagerImpl.createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, new HashMap<>(), volumeVoMock, storagePoolVoMock);
    }

    @Test
    public void createVolumeToStoragePoolMappingIfPossibleTestTargetHostAccessCurrentStoragePool() {
        List<StoragePool> storagePoolList = new ArrayList<>();
        storagePoolList.add(storagePoolVoMock);

        Mockito.doReturn(storagePoolList).when(virtualMachineManagerImpl).getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        HashMap<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();
        virtualMachineManagerImpl.createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock, storagePoolVoMock);

        Assert.assertTrue(volumeToPoolObjectMap.isEmpty());
    }

    @Test
    public void createVolumeToStoragePoolMappingIfPossibleTestTargetHostDoesNotAccessCurrentStoragePool() {
        StoragePoolVO storagePoolMockOther = Mockito.mock(StoragePoolVO.class);
        String storagePoolMockOtherUuid = "storagePoolMockOtherUuid";
        Mockito.doReturn(storagePoolMockOtherUuid).when(storagePoolMockOther).getUuid();
        Mockito.doReturn(storagePoolMockOther).when(storagePoolDaoMock).findByUuid(storagePoolMockOtherUuid);

        List<StoragePool> storagePoolList = new ArrayList<>();
        storagePoolList.add(storagePoolMockOther);

        Mockito.doReturn(storagePoolList).when(virtualMachineManagerImpl).getCandidateStoragePoolsToMigrateLocalVolume(virtualMachineProfileMock, dataCenterDeploymentMock, volumeVoMock);

        HashMap<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();
        virtualMachineManagerImpl.createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock, storagePoolVoMock);

        assertFalse(volumeToPoolObjectMap.isEmpty());
        assertEquals(storagePoolMockOther, volumeToPoolObjectMap.get(volumeVoMock));
    }

    @Test
    public void createStoragePoolMappingsForVolumesTestLocalStoragevolume() {
        ArrayList<Volume> allVolumes = new ArrayList<>();
        allVolumes.add(volumeVoMock);

        HashMap<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();

        Mockito.doReturn(ScopeType.HOST).when(storagePoolVoMock).getScope();
        Mockito.doNothing().when(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
        Mockito.doNothing().when(virtualMachineManagerImpl).createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock,
                storagePoolVoMock);

        virtualMachineManagerImpl.createStoragePoolMappingsForVolumes(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, allVolumes);

        Assert.assertTrue(volumeToPoolObjectMap.isEmpty());
        verify(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
        verify(virtualMachineManagerImpl).createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock, storagePoolVoMock);
    }

    @Test
    public void createStoragePoolMappingsForVolumesTestCrossCluterMigration() {
        ArrayList<Volume> allVolumes = new ArrayList<>();
        allVolumes.add(volumeVoMock);

        HashMap<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();

        Mockito.doReturn(ScopeType.CLUSTER).when(storagePoolVoMock).getScope();
        Mockito.doNothing().when(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
        Mockito.doNothing().when(virtualMachineManagerImpl).createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock, storagePoolVoMock);
        Mockito.doReturn(true).when(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);

        virtualMachineManagerImpl.createStoragePoolMappingsForVolumes(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, allVolumes);

        Assert.assertTrue(volumeToPoolObjectMap.isEmpty());
        verify(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
        verify(virtualMachineManagerImpl).createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock, storagePoolVoMock);
        verify(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);
    }

    @Test
    public void createStoragePoolMappingsForVolumesTestNotCrossCluterMigrationWithClusterStorage() {
        ArrayList<Volume> allVolumes = new ArrayList<>();
        allVolumes.add(volumeVoMock);

        HashMap<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();

        Mockito.doReturn(ScopeType.CLUSTER).when(storagePoolVoMock).getScope();
        Mockito.doNothing().when(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(any(), any(), any());
        Mockito.doReturn(false).when(virtualMachineManagerImpl).isStorageCrossClusterMigration(anyLong(), any());

        virtualMachineManagerImpl.createStoragePoolMappingsForVolumes(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, allVolumes);

        assertFalse(volumeToPoolObjectMap.isEmpty());
        assertEquals(storagePoolVoMock, volumeToPoolObjectMap.get(volumeVoMock));

        verify(virtualMachineManagerImpl).executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, storagePoolVoMock, volumeVoMock);
        verify(virtualMachineManagerImpl).isStorageCrossClusterMigration(clusterMockId, storagePoolVoMock);
        verify(virtualMachineManagerImpl, Mockito.times(0)).createVolumeToStoragePoolMappingIfPossible(virtualMachineProfileMock, dataCenterDeploymentMock, volumeToPoolObjectMap, volumeVoMock,
                storagePoolVoMock);
    }

    @Test
    public void createMappingVolumeAndStoragePoolTest() {
        Map<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();
        List<Volume> volumesNotMapped = new ArrayList<>();

        Mockito.doReturn(volumeToPoolObjectMap).when(virtualMachineManagerImpl).buildMapUsingUserInformation(Mockito.eq(virtualMachineProfileMock), Mockito.eq(hostMock),
                Mockito.anyMap());

        Mockito.doReturn(volumesNotMapped).when(virtualMachineManagerImpl).findVolumesThatWereNotMappedByTheUser(virtualMachineProfileMock, volumeToPoolObjectMap);
        Mockito.doNothing().when(virtualMachineManagerImpl).createStoragePoolMappingsForVolumes(Mockito.eq(virtualMachineProfileMock),
                any(DataCenterDeployment.class), Mockito.eq(volumeToPoolObjectMap), Mockito.eq(volumesNotMapped));

        Map<Volume, StoragePool> mappingVolumeAndStoragePool = virtualMachineManagerImpl.createMappingVolumeAndStoragePool(virtualMachineProfileMock, hostMock, new HashMap<>());

        assertEquals(mappingVolumeAndStoragePool, volumeToPoolObjectMap);

        InOrder inOrder = Mockito.inOrder(virtualMachineManagerImpl);
        inOrder.verify(virtualMachineManagerImpl).buildMapUsingUserInformation(Mockito.eq(virtualMachineProfileMock), Mockito.eq(hostMock), Mockito.anyMap());
        inOrder.verify(virtualMachineManagerImpl).findVolumesThatWereNotMappedByTheUser(virtualMachineProfileMock, volumeToPoolObjectMap);
        inOrder.verify(virtualMachineManagerImpl).createStoragePoolMappingsForVolumes(Mockito.eq(virtualMachineProfileMock),
                any(DataCenterDeployment.class), Mockito.eq(volumeToPoolObjectMap), Mockito.eq(volumesNotMapped));
    }

    @Test
    public void matchesOfSorts() {
        List<String> nothing = null;
        List<String> empty = new ArrayList<>();
        List<String> tag = Arrays.asList("bla");
        List<String> tags = Arrays.asList("bla", "blob");
        List<String> others = Arrays.asList("bla", "blieb");
        List<String> three = Arrays.asList("bla", "blob", "blieb");

        // single match
        assertTrue(VirtualMachineManagerImpl.matches(tag,tags));
        assertTrue(VirtualMachineManagerImpl.matches(tag,others));

        // no requirements
        assertTrue(VirtualMachineManagerImpl.matches(nothing,tags));
        assertTrue(VirtualMachineManagerImpl.matches(empty,tag));

        // mis(sing)match
        assertFalse(VirtualMachineManagerImpl.matches(tags,tag));
        assertFalse(VirtualMachineManagerImpl.matches(tag,nothing));
        assertFalse(VirtualMachineManagerImpl.matches(tag,empty));

        // disjunct sets
        assertFalse(VirtualMachineManagerImpl.matches(tags,others));
        assertFalse(VirtualMachineManagerImpl.matches(others,tags));

        // everything matches the larger set
        assertTrue(VirtualMachineManagerImpl.matches(nothing,three));
        assertTrue(VirtualMachineManagerImpl.matches(empty,three));
        assertTrue(VirtualMachineManagerImpl.matches(tag,three));
        assertTrue(VirtualMachineManagerImpl.matches(tags,three));
        assertTrue(VirtualMachineManagerImpl.matches(others,three));
    }

    @Test
    public void isRootVolumeOnLocalStorageTestOnLocal() {
        prepareAndTestIsRootVolumeOnLocalStorage(ScopeType.HOST, true);
    }

    @Test
    public void isRootVolumeOnLocalStorageTestCluster() {
        prepareAndTestIsRootVolumeOnLocalStorage(ScopeType.CLUSTER, false);
    }

    @Test
    public void isRootVolumeOnLocalStorageTestZone() {
        prepareAndTestIsRootVolumeOnLocalStorage(ScopeType.ZONE, false);
    }

    private void prepareAndTestIsRootVolumeOnLocalStorage(ScopeType scope, boolean expected) {
        StoragePoolVO storagePoolVoMock = Mockito.mock(StoragePoolVO.class);
        Mockito.doReturn(storagePoolVoMock).when(storagePoolDaoMock).findById(anyLong());
        Mockito.doReturn(scope).when(storagePoolVoMock).getScope();
        List<VolumeVO> mockedVolumes = new ArrayList<>();
        mockedVolumes.add(volumeVoMock);
        Mockito.doReturn(mockedVolumes).when(volumeDaoMock).findByInstanceAndType(anyLong(), any());

        boolean result = virtualMachineManagerImpl.isRootVolumeOnLocalStorage(0l);

        assertEquals(expected, result);
    }

    @Test
    public void checkIfNewOfferingStorageScopeMatchesStoragePoolTestLocalLocal() {
        prepareAndRunCheckIfNewOfferingStorageScopeMatchesStoragePool(true, true);
    }

    @Test
    public void checkIfNewOfferingStorageScopeMatchesStoragePoolTestSharedShared() {
        prepareAndRunCheckIfNewOfferingStorageScopeMatchesStoragePool(false, false);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void checkIfNewOfferingStorageScopeMatchesStoragePoolTestLocalShared() {
        prepareAndRunCheckIfNewOfferingStorageScopeMatchesStoragePool(true, false);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void checkIfNewOfferingStorageScopeMatchesStoragePoolTestSharedLocal() {
        prepareAndRunCheckIfNewOfferingStorageScopeMatchesStoragePool(false, true);
    }

    private void prepareAndRunCheckIfNewOfferingStorageScopeMatchesStoragePool(boolean isRootOnLocal, boolean isOfferingUsingLocal) {
        Mockito.doReturn(isRootOnLocal).when(virtualMachineManagerImpl).isRootVolumeOnLocalStorage(anyLong());
        Mockito.doReturn("vmInstanceMockedToString").when(vmInstanceMock).toString();
        Mockito.doReturn(isOfferingUsingLocal).when(diskOfferingMock).isUseLocalStorage();
        virtualMachineManagerImpl.checkIfNewOfferingStorageScopeMatchesStoragePool(vmInstanceMock, diskOfferingMock);
    }

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumesExistingRootVolumes() {
        long vmId = 1L;
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(volumeDaoMock.findReadyRootVolumesByInstance(vmId)).thenReturn(List.of(Mockito.mock(VolumeVO.class)));
        virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkIfTemplateNeededForCreatingVmVolumesMissingTemplate() {
        long vmId = 1L;
        long templateId = 1L;
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getTemplateId()).thenReturn(templateId);
        Mockito.when(volumeDaoMock.findReadyRootVolumesByInstance(vmId)).thenReturn(null);
        Mockito.when(templateDao.findById(templateId)).thenReturn(null);
        virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkIfTemplateNeededForCreatingVmVolumesMissingZoneTemplate() {
        long vmId = 1L;
        long templateId = 1L;
        long dcId = 1L;
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getTemplateId()).thenReturn(templateId);
        Mockito.when(vm.getDataCenterId()).thenReturn(dcId);
        Mockito.when(volumeDaoMock.findReadyRootVolumesByInstance(vmId)).thenReturn(null);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(vm.getId()).thenReturn(templateId);
        Mockito.when(templateDao.findById(templateId)).thenReturn(template);
        virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumesTemplateAvailable() {
        long vmId = 1L;
        long templateId = 1L;
        long dcId = 1L;
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getTemplateId()).thenReturn(templateId);
        Mockito.when(vm.getDataCenterId()).thenReturn(dcId);
        Mockito.when(volumeDaoMock.findReadyRootVolumesByInstance(vmId)).thenReturn(new ArrayList<>());
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getId()).thenReturn(templateId);
        Mockito.when(templateDao.findById(templateId)).thenReturn(template);
        Mockito.when(templateZoneDao.findByZoneTemplate(dcId, templateId)).thenReturn(Mockito.mock(VMTemplateZoneVO.class));
        virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    /** An Instance whose ROOT volume, not created yet, is created from a snapshot whose Template is gone; what the snapshot does is given. */
    private VMInstanceVO anInstanceWhoseRootIsCreatedFromASnapshot(java.util.function.Consumer<VolumeOrchestrationService> snapshotOfTheRoot) {
        long vmId = 1L;
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.lenient().when(vm.getTemplateId()).thenReturn(30L);
        Mockito.when(volumeDaoMock.findReadyRootVolumesByInstance(vmId)).thenReturn(new ArrayList<>());
        VolumeVO root = Mockito.mock(VolumeVO.class);
        Mockito.lenient().when(volumeDaoMock.findByInstanceAndType(vmId, Volume.Type.ROOT)).thenReturn(List.of(root));
        Mockito.lenient().when(templateDao.findById(30L)).thenReturn(null);
        VolumeOrchestrationService volumes = mock(VolumeOrchestrationService.class);
        snapshotOfTheRoot.accept(volumes);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "volumeMgr", volumes);
        return vm;
    }

    @Test
    public void anInstanceWhoseRootIsCreatedFromASnapshotNeedsNoTemplate() {
        VMInstanceVO vm = anInstanceWhoseRootIsCreatedFromASnapshot(volumes -> Mockito.lenient().when(volumes.snapshotToCreateFrom(any()))
                .thenReturn(Mockito.mock(Snapshot.class)));

        virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    @Test
    public void anInstanceWhoseRootSnapshotIsGoneDoesNotStart() {
        VMInstanceVO vm = anInstanceWhoseRootIsCreatedFromASnapshot(volumes -> Mockito.lenient().when(volumes.snapshotToCreateFrom(any()))
                .thenThrow(new CloudRuntimeException("The snapshot from which volume web-ROOT is to be created no longer exists")));

        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.checkIfTemplateNeededForCreatingVmVolumes(vm));

        assertTrue(e.getMessage(), e.getMessage().contains("snapshot"));
    }

    @Test
    public void checkAndAttemptMigrateVmAcrossClusterNonValid() {
        // Below scenarios shouldn't result in VM migration

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        virtualMachineManagerImpl.checkAndAttemptMigrateVmAcrossCluster(vm, 1L, new HashMap<>());

        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        virtualMachineManagerImpl.checkAndAttemptMigrateVmAcrossCluster(vm, 1L, new HashMap<>());

        Long destinationClusterId = 10L;
        Mockito.when(vm.getLastHostId()).thenReturn(1L);
        HostVO hostVO = Mockito.mock(HostVO.class);
        Mockito.when(hostVO.getClusterId()).thenReturn(destinationClusterId);
        Mockito.when(hostDaoMock.findById(1L)).thenReturn(hostVO);
        virtualMachineManagerImpl.checkAndAttemptMigrateVmAcrossCluster(vm, destinationClusterId, new HashMap<>());

        destinationClusterId = 20L;
        Map<Volume, StoragePool> map = new HashMap<>();
        StoragePool pool1 = Mockito.mock(StoragePool.class);
        Mockito.when(pool1.getClusterId()).thenReturn(10L);
        map.put(Mockito.mock(Volume.class), pool1);
        StoragePool pool2 = Mockito.mock(StoragePool.class);
        Mockito.when(pool2.getClusterId()).thenReturn(null);
        map.put(Mockito.mock(Volume.class), pool2);
        virtualMachineManagerImpl.checkAndAttemptMigrateVmAcrossCluster(vm, destinationClusterId, map);
    }

    @Test
    public void checkIfVmNetworkDetailsReturnedIsCorrect() {
        VMInstanceVO vm = new VMInstanceVO(1L, 1L, "VM1", "i-2-2-VM",
                VirtualMachine.Type.User, 1L, HypervisorType.KVM, 1L, 1L, 1L,
                1L, false, false);

        VirtualMachineTO vmTO = new VirtualMachineTO() {
        };
        UserVmJoinVO userVm = new UserVmJoinVO();
        NetworkVO networkVO = mock(NetworkVO.class);
        AccountVO accountVO = mock(AccountVO.class);
        DomainVO domainVO = mock(DomainVO.class);
        domainVO.setName("testDomain");
        DataCenterVO dataCenterVO = mock(DataCenterVO.class);
        VpcVO vpcVO = mock(VpcVO.class);

        networkVO.setAccountId(1L);
        networkVO.setName("testNet");
        networkVO.setVpcId(1L);

        accountVO.setAccountName("testAcc");

        vpcVO.setName("VPC1");


        List<UserVmJoinVO> userVms = List.of(userVm);
        Mockito.when(userVmJoinDaoMock.searchByIds(anyLong())).thenReturn(userVms);
        Mockito.when(networkDao.findById(anyLong())).thenReturn(networkVO);
        Mockito.when(accountDao.findById(anyLong())).thenReturn(accountVO);
        Mockito.when(domainDao.findById(anyLong())).thenReturn(domainVO);
        Mockito.when(dcDao.findById(anyLong())).thenReturn(dataCenterVO);
        Mockito.when(vpcDao.findById(anyLong())).thenReturn(vpcVO);
        Mockito.when(dataCenterVO.getId()).thenReturn(1L);
        when(accountVO.getId()).thenReturn(2L);
        Mockito.when(domainVO.getId()).thenReturn(3L);
        Mockito.when(vpcVO.getId()).thenReturn(4L);
        Mockito.when(networkVO.getId()).thenReturn(5L);
        virtualMachineManagerImpl.setVmNetworkDetails(vm, vmTO);
        assertEquals(1, vmTO.getNetworkIdToNetworkNameMap().size());
        assertEquals("D3-A2-Z1-V4-S5", vmTO.getNetworkIdToNetworkNameMap().get(5L));
    }

    @Test
    public void testOrchestrateStartNonNullPodId() throws Exception {
        VMInstanceVO vmInstance = new VMInstanceVO();
        ReflectionTestUtils.setField(vmInstance, "id", 1L);
        ReflectionTestUtils.setField(vmInstance, "accountId", 1L);
        ReflectionTestUtils.setField(vmInstance, "uuid", "vm-uuid");
        ReflectionTestUtils.setField(vmInstance, "serviceOfferingId", 2L);
        ReflectionTestUtils.setField(vmInstance, "instanceName", "myVm");
        ReflectionTestUtils.setField(vmInstance, "hostId", 2L);
        ReflectionTestUtils.setField(vmInstance, "type", VirtualMachine.Type.User);
        ReflectionTestUtils.setField(vmInstance, "dataCenterId", 1L);
        ReflectionTestUtils.setField(vmInstance, "hypervisorType", HypervisorType.KVM);

        VirtualMachineGuru vmGuru = mock(VirtualMachineGuru.class);

        User user = mock(User.class);

        Account account = mock(Account.class);
        Account owner = mock(Account.class);

        ReservationContext ctx = mock(ReservationContext.class);

        ItWorkVO work = mock(ItWorkVO.class);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);

        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.isDeployAsIs()).thenReturn(false);

        DataCenterDeployment plan = mock(DataCenterDeployment.class);
        when(plan.getDataCenterId()).thenReturn(1L);
        when(plan.getPodId()).thenReturn(1L);

        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();

        DeploymentPlanner planner = mock(DeploymentPlanner.class);

        when(vmInstanceDaoMock.findByUuid("vm-uuid")).thenReturn(vmInstance);

        doReturn(vmGuru).when(virtualMachineManagerImpl).getVmGuru(vmInstance);

        Ternary<VMInstanceVO, ReservationContext, ItWorkVO> start = new Ternary<>(vmInstance, ctx, work);
        Mockito.doReturn(start).when(virtualMachineManagerImpl).changeToStartState(vmGuru, vmInstance, user, account, owner, serviceOffering, template);

        when(ctx.getJournal()).thenReturn(Mockito.mock(Journal.class));

        when(serviceOfferingDaoMock.findById(vmInstance.getId(), vmInstance.getServiceOfferingId())).thenReturn(serviceOffering);

        when(_entityMgr.findById(Account.class, vmInstance.getAccountId())).thenReturn(owner);
        when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, vmInstance.getTemplateId())).thenReturn(template);

        Host destHost = mock(Host.class);
        Pod destPod = mock(Pod.class);
        DeployDestination dest = mock(DeployDestination.class);
        when(dest.getHost()).thenReturn(destHost);
        when(dest.getPod()).thenReturn(destPod);
        when(dest.getCluster()).thenReturn(mock(Cluster.class));
        when(destHost.getId()).thenReturn(1L);
        when(destPod.getId()).thenReturn(2L);
        when(_dpMgr.planDeployment(any(VirtualMachineProfileImpl.class), any(DataCenterDeployment.class), any(ExcludeList.class), any(DeploymentPlanner.class))).thenReturn(dest);

        doNothing().when(virtualMachineManagerImpl).checkIfTemplateNeededForCreatingVmVolumes(vmInstance);

        when(_workDao.updateStep(any(), any())).thenReturn(true);
        when(_stateMachine.transitTo(vmInstance, VirtualMachine.Event.OperationRetry, new Pair(vmInstance.getHostId(), 1L), vmInstanceDaoMock)).thenThrow(new CloudRuntimeException("Error while transitioning"));
        when(_stateMachine.transitTo(vmInstance, VirtualMachine.Event.OperationFailed, new Pair(vmInstance.getHostId(), null), vmInstanceDaoMock)).thenReturn(true);


        Cluster cluster = mock(Cluster.class);
        when(dest.getCluster()).thenReturn(cluster);
        ClusterDetailsVO cluster_detail_cpu = mock(ClusterDetailsVO.class);
        ClusterDetailsVO cluster_detail_ram = mock(ClusterDetailsVO.class);
        when(cluster.getId()).thenReturn(1L);
        when(_clusterDetailsDao.findDetail(1L, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(cluster_detail_cpu);
        when(_clusterDetailsDao.findDetail(1L, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(cluster_detail_ram);
        when(vmInstanceDetailsDao.findDetail(anyLong(), Mockito.anyString())).thenReturn(null);
        when(cluster_detail_cpu.getValue()).thenReturn("1.0");
        when(cluster_detail_ram.getValue()).thenReturn("1.0");
        doReturn(false).when(virtualMachineManagerImpl).areAllVolumesAllocated(Mockito.anyLong());

        CallContext callContext = mock(CallContext.class);
        when(callContext.getCallingAccount()).thenReturn(account);
        when(callContext.getCallingUser()).thenReturn(user);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);

            try {
                virtualMachineManagerImpl.orchestrateStart("vm-uuid", params, plan, planner);
            } catch (CloudRuntimeException e) {
                assertEquals(e.getMessage(), "Error while transitioning");
            }
        }

        assertEquals(vmInstance.getPodIdToDeployIn(), (Long) destPod.getId());
    }

    @Test
    public void testOrchestrateStartNullPodId() throws Exception {
        VMInstanceVO vmInstance = new VMInstanceVO();
        ReflectionTestUtils.setField(vmInstance, "id", 1L);
        ReflectionTestUtils.setField(vmInstance, "accountId", 1L);
        ReflectionTestUtils.setField(vmInstance, "uuid", "vm-uuid");
        ReflectionTestUtils.setField(vmInstance, "serviceOfferingId", 2L);
        ReflectionTestUtils.setField(vmInstance, "instanceName", "myVm");
        ReflectionTestUtils.setField(vmInstance, "hostId", 2L);
        ReflectionTestUtils.setField(vmInstance, "type", VirtualMachine.Type.User);
        ReflectionTestUtils.setField(vmInstance, "dataCenterId", 1L);
        ReflectionTestUtils.setField(vmInstance, "hypervisorType", HypervisorType.KVM);

        VirtualMachineGuru vmGuru = mock(VirtualMachineGuru.class);

        User user = mock(User.class);

        Account account = mock(Account.class);
        Account owner = mock(Account.class);

        ReservationContext ctx = mock(ReservationContext.class);

        ItWorkVO work = mock(ItWorkVO.class);

        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);

        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.isDeployAsIs()).thenReturn(false);

        DataCenterDeployment plan = mock(DataCenterDeployment.class);
        when(plan.getDataCenterId()).thenReturn(1L);
        when(plan.getPodId()).thenReturn(1L);

        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();

        DeploymentPlanner planner = mock(DeploymentPlanner.class);

        when(vmInstanceDaoMock.findByUuid("vm-uuid")).thenReturn(vmInstance);

        doReturn(vmGuru).when(virtualMachineManagerImpl).getVmGuru(vmInstance);

        Ternary<VMInstanceVO, ReservationContext, ItWorkVO> start = new Ternary<>(vmInstance, ctx, work);
        Mockito.doReturn(start).when(virtualMachineManagerImpl).changeToStartState(vmGuru, vmInstance, user, account, owner, serviceOffering, template);

        when(ctx.getJournal()).thenReturn(Mockito.mock(Journal.class));

        when(serviceOfferingDaoMock.findById(vmInstance.getId(), vmInstance.getServiceOfferingId())).thenReturn(serviceOffering);

        when(_entityMgr.findById(Account.class, vmInstance.getAccountId())).thenReturn(owner);
        when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, vmInstance.getTemplateId())).thenReturn(template);

        Host destHost = mock(Host.class);
        Pod destPod = mock(Pod.class);
        DeployDestination dest = mock(DeployDestination.class);
        when(dest.getHost()).thenReturn(destHost);
        when(dest.getPod()).thenReturn(destPod);
        when(dest.getCluster()).thenReturn(mock(Cluster.class));
        when(destHost.getId()).thenReturn(1L);
        when(destPod.getId()).thenReturn(2L);
        when(_dpMgr.planDeployment(any(VirtualMachineProfileImpl.class), any(DataCenterDeployment.class), any(ExcludeList.class), any(DeploymentPlanner.class))).thenReturn(dest);

        doNothing().when(virtualMachineManagerImpl).checkIfTemplateNeededForCreatingVmVolumes(vmInstance);

        when(_workDao.updateStep(any(), any())).thenReturn(true);
        when(_stateMachine.transitTo(vmInstance, VirtualMachine.Event.OperationRetry, new Pair(vmInstance.getHostId(), 1L), vmInstanceDaoMock)).thenThrow(new CloudRuntimeException("Error while transitioning"));
        when(_stateMachine.transitTo(vmInstance, VirtualMachine.Event.OperationFailed, new Pair(vmInstance.getHostId(), null), vmInstanceDaoMock)).thenReturn(true);


        Cluster cluster = mock(Cluster.class);
        when(dest.getCluster()).thenReturn(cluster);
        ClusterDetailsVO cluster_detail_cpu = mock(ClusterDetailsVO.class);
        ClusterDetailsVO cluster_detail_ram = mock(ClusterDetailsVO.class);
        when(cluster.getId()).thenReturn(1L);
        when(_clusterDetailsDao.findDetail(1L, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(cluster_detail_cpu);
        when(_clusterDetailsDao.findDetail(1L, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(cluster_detail_ram);
        when(vmInstanceDetailsDao.findDetail(anyLong(), Mockito.anyString())).thenReturn(null);
        when(cluster_detail_cpu.getValue()).thenReturn("1.0");
        when(cluster_detail_ram.getValue()).thenReturn("1.0");
        doReturn(true).when(virtualMachineManagerImpl).areAllVolumesAllocated(Mockito.anyLong());

        CallContext callContext = mock(CallContext.class);
        when(callContext.getCallingAccount()).thenReturn(account);
        when(callContext.getCallingUser()).thenReturn(user);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);

            try {
                virtualMachineManagerImpl.orchestrateStart("vm-uuid", params, plan, planner);
            } catch (CloudRuntimeException e) {
                assertEquals(e.getMessage(), "Error while transitioning");
            }
        }

        assertNull(vmInstance.getPodIdToDeployIn());
    }

    @Test
    public void testIsDiskOfferingSuitableForVmSuccess() {
        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());
        List<StoragePool> poolListMock = new ArrayList<>();
        poolListMock.add(storagePoolVoMock);
        Mockito.doReturn(poolListMock).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(1));
        boolean result = virtualMachineManagerImpl.isDiskOfferingSuitableForVm(vmInstanceMock, virtualMachineProfileMock, 1L, 1L, 1L, 1L);
        assertTrue(result);
    }

    @Test
    public void testIsDiskOfferingSuitableForVmNegative() {
        Mockito.doReturn(Mockito.mock(DiskOfferingVO.class)).when(diskOfferingDaoMock).findById(anyLong());
        Mockito.doReturn(new ArrayList<>()).when(storagePoolAllocatorMock).allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class),
                any(ExcludeList.class), Mockito.eq(1));
        boolean result = virtualMachineManagerImpl.isDiskOfferingSuitableForVm(vmInstanceMock, virtualMachineProfileMock, 1L, 1L, 1L, 1L);
        assertFalse(result);
    }

    @Test
    public void testGetDiskOfferingSuitabilityForVm() {
        Mockito.doReturn(vmInstanceMock).when(vmInstanceDaoMock).findById(1L);
        Mockito.when(vmInstanceMock.getHostId()).thenReturn(1L);
        Mockito.doReturn(hostMock).when(hostDaoMock).findById(1L);
        Mockito.when(hostMock.getClusterId()).thenReturn(1L);
        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        Mockito.when(cluster.getPodId()).thenReturn(1L);
        Mockito.doReturn(cluster).when(clusterDao).findById(1L);
        List<Long> diskOfferingIds = List.of(1L, 2L);
        Mockito.doReturn(false).when(virtualMachineManagerImpl)
                .isDiskOfferingSuitableForVm(eq(vmInstanceMock), any(VirtualMachineProfile.class),
                        eq(1L), eq(1L), eq(1L), eq(1L));
        Mockito.doReturn(true).when(virtualMachineManagerImpl)
                .isDiskOfferingSuitableForVm(eq(vmInstanceMock), any(VirtualMachineProfile.class),
                        eq(1L), eq(1L), eq(1L), eq(2L));
        Map<Long, Boolean> result = virtualMachineManagerImpl.getDiskOfferingSuitabilityForVm(1L, diskOfferingIds);
        assertTrue(MapUtils.isNotEmpty(result));
        assertEquals(2, result.keySet().size());
        assertFalse(result.get(1L));
        assertTrue(result.get(2L));
    }

    private void overrideVmMetadataConfigValue(final String manufacturer, final String product) {
        ConfigKey configKey = VirtualMachineManager.VmMetadataManufacturer;
        this.configDepotImpl = (ConfigDepotImpl)ReflectionTestUtils.getField(configKey, "s_depot");
        ConfigDepotImpl configDepot = Mockito.mock(ConfigDepotImpl.class);
        Mockito.when(configDepot.getConfigStringValue(Mockito.eq(configKey.key()),
                Mockito.eq(ConfigKey.Scope.Zone), Mockito.anyLong())).thenReturn(manufacturer);
        Mockito.when(configDepot.getConfigStringValue(Mockito.eq(VirtualMachineManager.VmMetadataProductName.key()),
                Mockito.eq(ConfigKey.Scope.Zone), Mockito.anyLong())).thenReturn(product);
        ReflectionTestUtils.setField(configKey, "s_depot", configDepot);
        updatedConfigKeyDepot = true;
    }

    private Pair<VirtualMachineTO, VMInstanceVO> getDummyVmTOAndVm() {
        VirtualMachineTO virtualMachineTO = new VirtualMachineTO(1L, "VM", VirtualMachine.Type.User, 1,
                1000, 256, 512, VirtualMachineTemplate.BootloaderType.HVM, "OS",
                false, false, "Pass");
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return new Pair<>(virtualMachineTO, vm);
    }

    @Test
    public void testUpdateVmMetadataManufacturerAndProductDefaultManufacturer() {
        overrideVmMetadataConfigValue("", "");
        Pair<VirtualMachineTO, VMInstanceVO> pair = getDummyVmTOAndVm();
        VirtualMachineTO to = pair.first();
        virtualMachineManagerImpl.updateVmMetadataManufacturerAndProduct(to, pair.second());
        Assert.assertEquals(VirtualMachineManager.VmMetadataManufacturer.defaultValue(), to.getMetadataManufacturer());
    }

    @Test
    public void testUpdateVmMetadataManufacturerAndProductCustomManufacturerDefaultProduct() {
        String manufacturer = "Custom";
        overrideVmMetadataConfigValue(manufacturer, "");
        Pair<VirtualMachineTO, VMInstanceVO> pair = getDummyVmTOAndVm();
        VirtualMachineTO to = pair.first();
        virtualMachineManagerImpl.updateVmMetadataManufacturerAndProduct(to, pair.second());
        Assert.assertEquals(manufacturer, to.getMetadataManufacturer());
        Assert.assertEquals("CloudStack KVM Hypervisor", to.getMetadataProductName());
    }

    @Test
    public void testUpdateVmMetadataManufacturerAndProductCustomManufacturer() {
        String manufacturer = UUID.randomUUID().toString();
        String product = UUID.randomUUID().toString();
        overrideVmMetadataConfigValue(manufacturer, product);
        Pair<VirtualMachineTO, VMInstanceVO> pair = getDummyVmTOAndVm();
        VirtualMachineTO to = pair.first();
        virtualMachineManagerImpl.updateVmMetadataManufacturerAndProduct(to, pair.second());
        Assert.assertEquals(manufacturer, to.getMetadataManufacturer());
        Assert.assertEquals(product, to.getMetadataProductName());
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationTestReturnIfNotKvm() {
        Mockito.doReturn(HypervisorType.VMware).when(vmInstanceMock).getHypervisorType();

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(volumeDaoMock, never()).findByInstance(Mockito.anyLong());
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationTestReturnIfVolumesDoNotHaveCheckpoints() throws OperationTimedoutException, AgentUnavailableException {
        Mockito.doReturn(HypervisorType.KVM).when(vmInstanceMock).getHypervisorType();
        Mockito.doReturn(new ArrayList<VolumeObjectTO>()).when(virtualMachineManagerImpl).getVmVolumesWithCheckpointsToRecreate(Mockito.any());

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(agentManagerMock, never()).send(Mockito.anyLong(), (Command) any());
    }

    @Test (expected = CloudRuntimeException.class)
    public void recreateCheckpointsKvmOnVmAfterMigrationTestAgentUnavailableThrowsCloudRuntimeExceptionAndEndsSnapshotChains() throws OperationTimedoutException, AgentUnavailableException {
        Mockito.doReturn(HypervisorType.KVM).when(vmInstanceMock).getHypervisorType();
        Mockito.doReturn(List.of(new VolumeObjectTO())).when(virtualMachineManagerImpl).getVmVolumesWithCheckpointsToRecreate(Mockito.any());

        doThrow(new AgentUnavailableException(0)).when(agentManagerMock).send(Mockito.anyLong(), (Command) any());
        Mockito.doNothing().when(snapshotManagerMock).endSnapshotChainForVolume(Mockito.anyLong(), Mockito.any());

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(snapshotManagerMock, Mockito.times(1)).endSnapshotChainForVolume(Mockito.anyLong(),any());
    }

    @Test (expected = CloudRuntimeException.class)
    public void recreateCheckpointsKvmOnVmAfterMigrationTestOperationTimedoutExceptionThrowsCloudRuntimeExceptionAndEndsSnapshotChains() throws OperationTimedoutException, AgentUnavailableException {
        Mockito.doReturn(HypervisorType.KVM).when(vmInstanceMock).getHypervisorType();
        Mockito.doReturn(List.of(new VolumeObjectTO())).when(virtualMachineManagerImpl).getVmVolumesWithCheckpointsToRecreate(Mockito.any());

        doThrow(new OperationTimedoutException(null, 0, 0, 0, false)).when(agentManagerMock).send(Mockito.anyLong(), (Command) any());
        Mockito.doNothing().when(snapshotManagerMock).endSnapshotChainForVolume(Mockito.anyLong(), Mockito.any());

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(snapshotManagerMock, Mockito.times(1)).endSnapshotChainForVolume(Mockito.anyLong(),any());
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationTestRecreationFails() throws OperationTimedoutException, AgentUnavailableException {
        Mockito.doReturn(HypervisorType.KVM).when(vmInstanceMock).getHypervisorType();
        Mockito.doReturn(List.of(new VolumeObjectTO())).when(virtualMachineManagerImpl).getVmVolumesWithCheckpointsToRecreate(Mockito.any());

        Mockito.doReturn(new com.cloud.agent.api.Answer(null, false, null)).when(agentManagerMock).send(Mockito.anyLong(), (Command) any());
        Mockito.doNothing().when(snapshotManagerMock).endSnapshotChainForVolume(Mockito.anyLong(), Mockito.any());

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(snapshotManagerMock, Mockito.times(1)).endSnapshotChainForVolume(Mockito.anyLong(),any());
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationTestRecreationSucceeds() throws OperationTimedoutException, AgentUnavailableException {
        Mockito.doReturn(HypervisorType.KVM).when(vmInstanceMock).getHypervisorType();
        Mockito.doReturn(List.of(new VolumeObjectTO())).when(virtualMachineManagerImpl).getVmVolumesWithCheckpointsToRecreate(Mockito.any());

        Mockito.doReturn(new com.cloud.agent.api.Answer(null, true, null)).when(agentManagerMock).send(Mockito.anyLong(), (Command) any());

        virtualMachineManagerImpl.recreateCheckpointsKvmOnVmAfterMigration(vmInstanceMock, 0);

        verify(snapshotManagerMock, never()).endSnapshotChainForVolume(Mockito.anyLong(),any());
    }

    @Test
    public void updateStartCommandWithExternalDetails_nonExternalHypervisor_noAction() {
        Host host = mock(Host.class);
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        StartCommand command = mock(StartCommand.class);

        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);

        virtualMachineManagerImpl.updateStartCommandWithExternalDetails(host, vmTO, command);

        verify(command, never()).setExternalDetails(any());
    }

    @Test
    public void updateStartCommandWithExternalDetails_externalHypervisor_setsExternalDetails() {
        Host host = mock(Host.class);
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        StartCommand command = mock(StartCommand.class);
        NicTO nic = mock(NicTO.class);

        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vmTO.getExternalDetails()).thenReturn(new HashMap<>());
        when(vmTO.getNics()).thenReturn(new NicTO[]{nic});
        when(nic.isDefaultNic()).thenReturn(true);
        when(networkService.getNicVlanValueForExternalVm(nic)).thenReturn("segmentName");
        when(extensionsManager.getExternalAccessDetails(eq(host), any())).thenReturn(new HashMap<>());

        virtualMachineManagerImpl.updateStartCommandWithExternalDetails(host, vmTO, command);

        verify(command).setExternalDetails(any());
    }

    @Test
    public void updateStopCommandForExternalHypervisorType_nonExternalHypervisor_noAction() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        StopCommand stopCommand = mock(StopCommand.class);

        virtualMachineManagerImpl.updateStopCommandForExternalHypervisorType(HypervisorType.KVM, vmProfile, stopCommand);

        verify(stopCommand, never()).setExternalDetails(any());
    }

    @Test
    public void updateStopCommandForExternalHypervisorType_externalHypervisor_setsExternalDetails() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        StopCommand stopCommand = mock(StopCommand.class);
        HostVO host = mock(HostVO.class);
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        when(vmProfile.getHostId()).thenReturn(1L);
        when(hostDaoMock.findById(1L)).thenReturn(host);
        when(stopCommand.getVirtualMachine()).thenReturn(vmTO);
        when(vmTO.getExternalDetails()).thenReturn(new HashMap<>());
        when(extensionsManager.getExternalAccessDetails(eq(host), any())).thenReturn(new HashMap<>());
        doReturn(mock(VirtualMachineTO.class)).when(virtualMachineManagerImpl).toVmTO(any());
        virtualMachineManagerImpl.updateStopCommandForExternalHypervisorType(HypervisorType.External, vmProfile, stopCommand);
        verify(stopCommand).setExternalDetails(any());
    }

    @Test
    public void updateRebootCommandWithExternalDetails_nonExternalHypervisor_noAction() {
        Host host = mock(Host.class);
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        RebootCommand rebootCmd = mock(RebootCommand.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        virtualMachineManagerImpl.updateRebootCommandWithExternalDetails(host, vmTO, rebootCmd);
        verify(rebootCmd, never()).setExternalDetails(any());
    }

    @Test
    public void updateRebootCommandWithExternalDetails_externalHypervisor_setsExternalDetails() {
        Host host = mock(Host.class);
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        RebootCommand rebootCmd = mock(RebootCommand.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(vmTO.getExternalDetails()).thenReturn(new HashMap<>());
        when(extensionsManager.getExternalAccessDetails(eq(host), any())).thenReturn(new HashMap<>());
        virtualMachineManagerImpl.updateRebootCommandWithExternalDetails(host, vmTO, rebootCmd);
        verify(rebootCmd).setExternalDetails(any());
    }

    @Test
    public void updateExternalVmDetailsFromPrepareAnswer_nullDetails_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        UserVmVO userVmVO = mock(UserVmVO.class);
        virtualMachineManagerImpl.updateExternalVmDetailsFromPrepareAnswer(vmTO, userVmVO, null);
        verify(vmTO, never()).setDetails(any());
        verify(userVmVO, never()).setDetails(any());
        verify(userVmDaoMock, never()).saveDetails(any());
    }

    @Test
    public void updateExternalVmDetailsFromPrepareAnswer_sameDetails_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        UserVmVO userVmVO = mock(UserVmVO.class);
        Map<String, String> details = new HashMap<>();
        when(vmTO.getDetails()).thenReturn(details);
        virtualMachineManagerImpl.updateExternalVmDetailsFromPrepareAnswer(vmTO, userVmVO, details);
        verify(vmTO, never()).setDetails(any());
        verify(userVmVO, never()).setDetails(any());
        verify(userVmDaoMock, never()).saveDetails(any());
    }

    @Test
    public void updateExternalVmDataFromPrepareAnswer_vncPasswordUpdated_updatesPassword() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        UserVmVO userVmVO = mock(UserVmVO.class);
        when(updatedTO.getVncPassword()).thenReturn("newPassword");
        when(vmTO.getVncPassword()).thenReturn("oldPassword");
        when(userVmDaoMock.findById(anyLong())).thenReturn(userVmVO);
        virtualMachineManagerImpl.updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);
        verify(userVmVO).setVncPassword("newPassword");
        verify(vmTO).setVncPassword("newPassword");
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_nullNics_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(vmTO.getNics()).thenReturn(null);
        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);
        verify(_nicsDao, never()).findByUuid(anyString());
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_updatesNicsSuccessfully() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        NicTO nicTO = mock(NicTO.class);
        NicTO updatedNicTO = mock(NicTO.class);

        when(vmTO.getNics()).thenReturn(new NicTO[]{nicTO});
        when(updatedTO.getNics()).thenReturn(new NicTO[]{updatedNicTO});
        when(nicTO.getNicUuid()).thenReturn("nic-uuid");
        when(nicTO.getMac()).thenReturn("mac-a");
        when(updatedNicTO.getNicUuid()).thenReturn("nic-uuid");
        when(updatedNicTO.getMac()).thenReturn("mac-b");
        when(_nicsDao.findByUuid("nic-uuid")).thenReturn(mock(NicVO.class));

        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(_nicsDao).findByUuid("nic-uuid");
        verify(_nicsDao).update(anyLong(), any(NicVO.class));
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_noMatchingNicUuid_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        NicTO nicTO = mock(NicTO.class);
        NicTO updatedNicTO = mock(NicTO.class);

        when(vmTO.getNics()).thenReturn(new NicTO[]{nicTO});
        when(updatedTO.getNics()).thenReturn(new NicTO[]{updatedNicTO});
        when(nicTO.getNicUuid()).thenReturn("nic-uuid");
        when(updatedNicTO.getNicUuid()).thenReturn("different-uuid");

        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(_nicsDao, never()).findByUuid(anyString());
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_nullUpdatedNics_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);

        when(vmTO.getNics()).thenReturn(new NicTO[]{mock(NicTO.class)});
        when(updatedTO.getNics()).thenReturn(null);

        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(_nicsDao, never()).findByUuid(anyString());
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_nullVmNics_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);

        when(vmTO.getNics()).thenReturn(null);
        when(updatedTO.getNics()).thenReturn(new NicTO[]{mock(NicTO.class)});

        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(_nicsDao, never()).findByUuid(anyString());
    }

    @Test
    public void updateExternalVmNicsFromPrepareAnswer_emptyNics_noAction() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);

        when(vmTO.getNics()).thenReturn(new NicTO[]{});
        when(updatedTO.getNics()).thenReturn(new NicTO[]{});

        virtualMachineManagerImpl.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(_nicsDao, never()).findByUuid(anyString());
    }

    @Test
    public void processPrepareExternalProvisioning_nonExternalHypervisor_noAction() throws OperationTimedoutException, AgentUnavailableException {
        Host host = mock(Host.class);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(vmProfile.getTemplate()).thenReturn(template);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        virtualMachineManagerImpl.processPrepareExternalProvisioning(true, host, vmProfile, mock(DataCenter.class));
        verify(agentManagerMock, never()).send(anyLong(), any(Command.class));
    }

    @Test
    public void processPrepareExternalProvisioning_externalHypervisor_sendsCommand() throws OperationTimedoutException, AgentUnavailableException {
        Host host = mock(Host.class);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(vmProfile.getTemplate()).thenReturn(template);
        NicTO[] nics = new NicTO[]{mock(NicTO.class)};
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        when(vmTO.getNics()).thenReturn(nics);
        doReturn(vmTO).when(virtualMachineManagerImpl).toVmTO(vmProfile);
        ExtensionDetailsVO detailsVO = mock(ExtensionDetailsVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.External);
        when(template.getExtensionId()).thenReturn(1L);
        when(extensionDetailsDao.findDetail(eq(1L), eq(ApiConstants.ORCHESTRATOR_REQUIRES_PREPARE_VM))).thenReturn(detailsVO);
        when(detailsVO.getValue()).thenReturn("true");
        PrepareExternalProvisioningAnswer answer = mock(PrepareExternalProvisioningAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVirtualMachineTO()).thenReturn(vmTO);
        when(agentManagerMock.send(anyLong(), any(Command.class))).thenReturn(answer);
        virtualMachineManagerImpl.processPrepareExternalProvisioning(true, host, vmProfile, mock(DataCenter.class));
        verify(agentManagerMock).send(anyLong(), any(Command.class));
    }

    @Test
    public void testPrepVMSpecForUnmanageInstance() {
        // Arrange
        final Long accountId = 1L;
        final Long offeringId = 1L;
        final Long templateId = 1L;

        // Mock vm
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(vmInstanceVoMockId);
        when(vm.getAccountId()).thenReturn(accountId);
        when(vm.getServiceOfferingId()).thenReturn(offeringId);
        when(vm.getTemplateId()).thenReturn(templateId);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmInstanceDaoMock.findById(vmInstanceVoMockId)).thenReturn(vm);

        // Mock owner
        AccountVO owner = Mockito.mock(AccountVO.class);
        when(_entityMgr.findById(Account.class, accountId)).thenReturn(owner);

        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        when(serviceOfferingDaoMock.findById(vmInstanceVoMockId, offeringId)).thenReturn(offering);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId)).thenReturn(template);

        when(hostMock.getClusterId()).thenReturn(clusterMockId);

        // Mock cpuOvercommitRatio and ramOvercommitRatio
        ClusterDetailsVO cpuOvercommitRatio = Mockito.mock(ClusterDetailsVO.class);
        when(cpuOvercommitRatio.getValue()).thenReturn("1.0");
        when(_clusterDetailsDao.findDetail(clusterMockId, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(cpuOvercommitRatio);
        ClusterDetailsVO ramOvercommitRatio = Mockito.mock(ClusterDetailsVO.class);
        when(ramOvercommitRatio.getValue()).thenReturn("1.0");
        when(_clusterDetailsDao.findDetail(clusterMockId, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(ramOvercommitRatio);

        // Mock NICs
        List<NicVO> nics = new ArrayList<>();
        NicVO nic1 = Mockito.mock(NicVO.class);
        when(nic1.getDeviceId()).thenReturn(1);
        nics.add(nic1);
        NicVO nic2 = Mockito.mock(NicVO.class);
        when(nic2.getDeviceId()).thenReturn(0);
        nics.add(nic2);
        when(_nicsDao.listByVmId(vmInstanceVoMockId)).thenReturn(nics);

        Network networkMock = Mockito.mock(Network.class);
        when(networkModel.getNetwork(anyLong())).thenReturn(networkMock);

        when(volumeVoMock.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumeVoMock.getDeviceId()).thenReturn(0L);
        when(volumeVoMock.getPath()).thenReturn("/");
        when(volumeVoMock.getDiskOfferingId()).thenReturn(1L);
        when(volumeDaoMock.findUsableVolumesForInstance(vmInstanceVoMockId)).thenReturn(List.of(volumeVoMock));

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        DataTO dataTO = mock(DataTO.class);
        when(volumeInfo.getTO()).thenReturn(dataTO);
        when(volumeDataFactoryMock.getVolume(anyLong())).thenReturn(volumeInfo);
        when(storageManager.getDiskWithThrottling(any(), any(), anyLong(), anyString(), anyLong(), anyLong())).thenReturn(Mockito.mock(DiskTO.class));

        Map<String, String> details = new HashMap<>();
        details.put(VirtualMachineProfile.Param.BootType.getName(), "BIOS");
        details.put(VirtualMachineProfile.Param.BootMode.getName(), "LEGACY");
        details.put(VirtualMachineProfile.Param.UefiFlag.getName(), "Yes");
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), anyList())).thenReturn(details);

        com.cloud.hypervisor.HypervisorGuru guru = Mockito.mock(com.cloud.hypervisor.HypervisorGuru.class);
        when(_hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(guru);
        VirtualMachineTO vmTO = new VirtualMachineTO() {};
        when(guru.implement(any(VirtualMachineProfile.class))).thenAnswer((Answer<VirtualMachineTO>) invocation -> {
            VirtualMachineProfile profile = invocation.getArgument(0);
            assertEquals("BIOS", profile.getParameter(VirtualMachineProfile.Param.BootType));
            return vmTO;
        });

        // Act
        VirtualMachineTO result = virtualMachineManagerImpl.prepVmSpecForUnmanageCmd(vmInstanceVoMockId, hostMockId);

        // Assert
        assertNotNull(result);
        assertEquals(vmTO, result);
        verify(_clusterDetailsDao, times(2)).findDetail(eq(clusterMockId), anyString());
        verify(vmInstanceDetailsDao).listDetailsKeyPairs(anyLong(), anyList());
    }

    @Test
    public void testPersistDomainForKvmForRunningVmSuccess() throws AgentUnavailableException, OperationTimedoutException {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        UnmanageInstanceAnswer successAnswer = new UnmanageInstanceAnswer(null, true, "success");
        when(agentManagerMock.send(anyLong(), any(Command.class))).thenReturn(successAnswer);
        virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null);
        ArgumentCaptor<Long> hostIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<UnmanageInstanceCommand> commandCaptor = ArgumentCaptor.forClass(UnmanageInstanceCommand.class);
        verify(agentManagerMock).send(hostIdCaptor.capture(), commandCaptor.capture());
        assertEquals(hostMockId, hostIdCaptor.getValue().longValue());
    }

    @Test
    public void testPersistDomainForKvmForStoppedVmSuccess() throws AgentUnavailableException, OperationTimedoutException {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Stopped);
        VirtualMachineTO vmTO = new VirtualMachineTO() {};
        vmTO.setName(vmName);
        doReturn(vmTO).when(virtualMachineManagerImpl).prepVmSpecForUnmanageCmd(vmInstanceVoMockId, 1L);
        UnmanageInstanceAnswer successAnswer = new UnmanageInstanceAnswer(null, true, "success");
        when(agentManagerMock.send(anyLong(), any(UnmanageInstanceCommand.class))).thenReturn(successAnswer);
        when(virtualMachineManagerImpl.findClusterAndHostIdForVm(vmInstanceMock, false)).thenReturn(new Pair<>(clusterMockId, hostMockId));
        virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null);
        ArgumentCaptor<Long> hostIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<UnmanageInstanceCommand> commandCaptor = ArgumentCaptor.forClass(UnmanageInstanceCommand.class);
        verify(agentManagerMock).send(hostIdCaptor.capture(), commandCaptor.capture());
        assertEquals(1L, hostIdCaptor.getValue().longValue());
        UnmanageInstanceCommand sentCommand = commandCaptor.getValue();
        assertNotNull(sentCommand.getVm());
        assertEquals(vmTO, sentCommand.getVm());
        assertEquals(vmName, sentCommand.getInstanceName());
        verify(virtualMachineManagerImpl).prepVmSpecForUnmanageCmd(vmInstanceVoMockId, 1L);
    }


    @Test
    public void testPersistDomainForKvmForStoppedVmNoHost() {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Stopped);
        VirtualMachineTO vmTO = new VirtualMachineTO() {};
        vmTO.setName(vmName);
        when(virtualMachineManagerImpl.findClusterAndHostIdForVm(vmInstanceMock, false)).thenReturn(new Pair<>(clusterMockId, null));
        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null));
        assertEquals("No available host to persist domain XML for Instance: " + vmName, exception.getMessage());
    }

    @Test
    public void testPersistDomainForKvmForRunningVmAgentFailure() throws AgentUnavailableException, OperationTimedoutException {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        UnmanageInstanceAnswer failureAnswer = new UnmanageInstanceAnswer(null, false, "failure");
        when(agentManagerMock.send(anyLong(), any(UnmanageInstanceCommand.class))).thenReturn(failureAnswer);
        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null));
        assertEquals("Failed to persist domain XML for Instance: " + vmName + " on host ID: " + hostMockId, exception.getMessage());
    }

    @Test
    public void testPersistDomainForKvmAgentUnavailable() throws AgentUnavailableException, OperationTimedoutException {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        doThrow(new AgentUnavailableException("Agent down", hostMockId)).when(agentManagerMock).send(anyLong(), any(UnmanageInstanceCommand.class));
        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null));
        assertEquals("Failed to send command to persist domain XML for Instance: " + vmName + " on host ID: " + hostMockId, exception.getMessage());
    }

    @Test(expected = ConcurrentOperationException.class)
    public void testUnmanagePendingHaWork() {
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        when(_workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, vmInstanceVoMockId)).thenReturn(Collections.emptyList());
        when(_haMgr.hasPendingHaWork(vmInstanceVoMockId)).thenReturn(true);
        virtualMachineManagerImpl.unmanage(vmMockUuid, null);
    }

    @Test
    public void testPersistDomainForKvmOperationTimedOut() throws AgentUnavailableException, OperationTimedoutException {
        when(vmInstanceMock.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        doThrow(new OperationTimedoutException(null, hostMockId, 123L, 60, false)).when(agentManagerMock).send(anyLong(), any(UnmanageInstanceCommand.class));
        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.persistDomainForKVM(vmInstanceMock, null));
        assertEquals("Failed to send command to persist domain XML for Instance: " + vmName + " on host ID: " + hostMockId, exception.getMessage());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testUnmanageVmRemoved() {
        when(vmInstanceMock.getRemoved()).thenReturn(new Date());
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        virtualMachineManagerImpl.unmanage(vmMockUuid, null);
    }

    @Test(expected = ConcurrentOperationException.class)
    public void testUnmanagePendingWorkJobs() {
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        List<VmWorkJobVO> pendingJobs = new ArrayList<>();
        VmWorkJobVO vmWorkJobVO = mock(VmWorkJobVO.class);
        pendingJobs.add(vmWorkJobVO);
        when(_workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, vmInstanceVoMockId)).thenReturn(pendingJobs);
        virtualMachineManagerImpl.unmanage(vmMockUuid, null);
    }

    @Test
    public void testUnmanageHostNotFoundAfterTransaction() {
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        when(_workJobDao.listPendingWorkJobs(any(), anyLong())).thenReturn(Collections.emptyList());
        when(_haMgr.hasPendingHaWork(anyLong())).thenReturn(false);
        doReturn(guru).when(virtualMachineManagerImpl).getVmGuru(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMSnapshots(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMNics(any(VirtualMachineProfile.class), any(VMInstanceVO.class));
        doNothing().when(virtualMachineManagerImpl).unmanageVMVolumes(vmInstanceMock);
        doNothing().when(guru).finalizeUnmanage(vmInstanceMock);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(hostMockId)).thenReturn(null);
            Pair<Boolean, String> result = virtualMachineManagerImpl.unmanage(vmMockUuid, null);
            assertNull(result.second());
        }
    }

    @Test
    public void testUnmanageSuccessNonKvm() {
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        when(hostMock.getUuid()).thenReturn(hostUuid);
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        when(_workJobDao.listPendingWorkJobs(any(), anyLong())).thenReturn(Collections.emptyList());
        when(_haMgr.hasPendingHaWork(anyLong())).thenReturn(false);
        doReturn(guru).when(virtualMachineManagerImpl).getVmGuru(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMSnapshots(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMNics(any(VirtualMachineProfile.class), any(VMInstanceVO.class));
        doNothing().when(virtualMachineManagerImpl).unmanageVMVolumes(vmInstanceMock);
        doNothing().when(guru).finalizeUnmanage(vmInstanceMock);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(hostMockId)).thenReturn(hostMock);
            try (MockedStatic<ActionEventUtils> actionUtil = Mockito.mockStatic(ActionEventUtils.class)) {
                actionUtil.when(() -> ActionEventUtils.onActionEvent(
                        anyLong(), anyLong(), anyLong(),
                        anyString(), anyString(),
                        anyLong(), anyString()
                )).thenReturn(1L);

                Pair<Boolean, String> result = virtualMachineManagerImpl.unmanage(vmMockUuid, null);
                assertNotNull(result);
                assertTrue(result.first());
                assertEquals(hostUuid, result.second());

                verify(virtualMachineManagerImpl, never()).persistDomainForKVM(any(VMInstanceVO.class), anyLong());
                verify(virtualMachineManagerImpl, times(1)).unmanageVMSnapshots(vmInstanceMock);
                verify(virtualMachineManagerImpl, times(1)).unmanageVMNics(any(VirtualMachineProfile.class), any(VMInstanceVO.class));
                verify(virtualMachineManagerImpl, times(1)).unmanageVMVolumes(vmInstanceMock);
                verify(guru, times(1)).finalizeUnmanage(vmInstanceMock);
            }
        }
    }

    @Test
    public void testUnmanageSuccessKvm() throws Exception {
        when(vmInstanceMock.getHostId()).thenReturn(hostMockId);
        when(hostMock.getUuid()).thenReturn(hostUuid);
        when(vmInstanceMock.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmInstanceDaoMock.findByUuid(vmMockUuid)).thenReturn(vmInstanceMock);
        when(_workJobDao.listPendingWorkJobs(any(), anyLong())).thenReturn(Collections.emptyList());
        when(_haMgr.hasPendingHaWork(anyLong())).thenReturn(false);
        doReturn(guru).when(virtualMachineManagerImpl).getVmGuru(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMSnapshots(vmInstanceMock);
        doNothing().when(virtualMachineManagerImpl).unmanageVMNics(any(VirtualMachineProfile.class), any(VMInstanceVO.class));
        doNothing().when(virtualMachineManagerImpl).unmanageVMVolumes(vmInstanceMock);
        doNothing().when(guru).finalizeUnmanage(vmInstanceMock);
        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findHostById(hostMockId)).thenReturn(hostMock);
            try (MockedStatic<ActionEventUtils> actionUtil = Mockito.mockStatic(ActionEventUtils.class)) {
                actionUtil.when(() -> ActionEventUtils.onActionEvent(
                        anyLong(), anyLong(), anyLong(),
                        anyString(), anyString(),
                        anyLong(), anyString()
                )).thenReturn(1L);
                UnmanageInstanceAnswer successAnswer = new UnmanageInstanceAnswer(null, true, "success");
                when(agentManagerMock.send(anyLong(), any(UnmanageInstanceCommand.class))).thenReturn(successAnswer);
                Pair<Boolean, String> result = virtualMachineManagerImpl.unmanage(vmMockUuid, null);
                assertNotNull(result);
                assertTrue(result.first());
                assertEquals(hostUuid, result.second());
                verify(virtualMachineManagerImpl, times(1)).persistDomainForKVM(vmInstanceMock, null);
                verify(virtualMachineManagerImpl, times(1)).unmanageVMSnapshots(vmInstanceMock);
                verify(virtualMachineManagerImpl, times(1)).unmanageVMNics(any(VirtualMachineProfile.class), any(VMInstanceVO.class));
                verify(virtualMachineManagerImpl, times(1)).unmanageVMVolumes(vmInstanceMock);
                verify(guru, times(1)).finalizeUnmanage(vmInstanceMock);
            }
        }
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithClvmVolumes() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;
        long poolId = 10L;

        VolumeVO clvmVolume1 = mock(VolumeVO.class);
        VolumeVO clvmVolume2 = mock(VolumeVO.class);

        when(clvmVolume1.getId()).thenReturn(1L);
        when(clvmVolume1.getPoolId()).thenReturn(poolId);
        when(clvmVolume2.getId()).thenReturn(2L);
        when(clvmVolume2.getPoolId()).thenReturn(poolId);

        StoragePoolVO clvmPool = mock(StoragePoolVO.class);
        when(clvmPool.getPoolType()).thenReturn(Storage.StoragePoolType.CLVM);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(clvmVolume1, clvmVolume2));
        when(storagePoolDaoMock.findById(poolId)).thenReturn(clvmPool);

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(1L, destHostId);
        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(2L, destHostId);
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithNonClvmVolumes() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;
        long poolId = 10L;

        VolumeVO nfsVolume = mock(VolumeVO.class);
        when(nfsVolume.getPoolId()).thenReturn(poolId);

        StoragePoolVO nfsPool = mock(StoragePoolVO.class);
        when(nfsPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(nfsVolume));
        when(storagePoolDaoMock.findById(poolId)).thenReturn(nfsPool);

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, never()).setClvmLockHostId(anyLong(), anyLong());
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithMixedVolumes() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;
        long clvmPoolId = 10L;
        long nfsPoolId = 20L;

        VolumeVO clvmVolume = mock(VolumeVO.class);
        VolumeVO nfsVolume = mock(VolumeVO.class);

        when(clvmVolume.getId()).thenReturn(1L);
        when(clvmVolume.getPoolId()).thenReturn(clvmPoolId);
        when(nfsVolume.getPoolId()).thenReturn(nfsPoolId);

        StoragePoolVO clvmPool = mock(StoragePoolVO.class);
        when(clvmPool.getPoolType()).thenReturn(Storage.StoragePoolType.CLVM);

        StoragePoolVO nfsPool = mock(StoragePoolVO.class);
        when(nfsPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(clvmVolume, nfsVolume));
        when(storagePoolDaoMock.findById(clvmPoolId)).thenReturn(clvmPool);
        when(storagePoolDaoMock.findById(nfsPoolId)).thenReturn(nfsPool);

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(1L, destHostId);
        verify(clvmPoolManagerMock, never()).setClvmLockHostId(2L, destHostId);
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithNoVolumes() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Collections.emptyList());

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, never()).setClvmLockHostId(anyLong(), anyLong());
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithNullPoolId() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;

        VolumeVO volumeWithoutPool = mock(VolumeVO.class);
        when(volumeWithoutPool.getPoolId()).thenReturn(null);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(volumeWithoutPool));

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(storagePoolDaoMock, never()).findById(anyLong());
        verify(clvmPoolManagerMock, never()).setClvmLockHostId(anyLong(), anyLong());
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_WithNullPool() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;
        long poolId = 10L;

        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(poolId);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(volume));
        when(storagePoolDaoMock.findById(poolId)).thenReturn(null);

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, never()).setClvmLockHostId(anyLong(), anyLong());
    }

    @Test
    public void testUpdateClvmLockHostForVmVolumes_MultipleClvmPools() throws Exception {
        long vmId = 100L;
        long destHostId = 2L;
        long pool1Id = 10L;
        long pool2Id = 20L;

        VolumeVO volume1 = mock(VolumeVO.class);
        VolumeVO volume2 = mock(VolumeVO.class);
        VolumeVO volume3 = mock(VolumeVO.class);

        when(volume1.getId()).thenReturn(1L);
        when(volume1.getPoolId()).thenReturn(pool1Id);
        when(volume2.getId()).thenReturn(2L);
        when(volume2.getPoolId()).thenReturn(pool2Id);
        when(volume3.getId()).thenReturn(3L);
        when(volume3.getPoolId()).thenReturn(pool1Id);

        StoragePoolVO clvmPool1 = mock(StoragePoolVO.class);
        when(clvmPool1.getPoolType()).thenReturn(Storage.StoragePoolType.CLVM);

        StoragePoolVO clvmPool2 = mock(StoragePoolVO.class);
        when(clvmPool2.getPoolType()).thenReturn(Storage.StoragePoolType.CLVM);

        when(volumeDaoMock.findByInstance(vmId)).thenReturn(Arrays.asList(volume1, volume2, volume3));
        when(storagePoolDaoMock.findById(pool1Id)).thenReturn(clvmPool1);
        when(storagePoolDaoMock.findById(pool2Id)).thenReturn(clvmPool2);

        ClvmPoolManager clvmPoolManagerMock = injectMockedClvmPoolManager();

        Method method = getUpdateClvmLockHostForVmVolumesMethod();
        method.invoke(virtualMachineManagerImpl, vmId, destHostId);

        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(1L, destHostId);
        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(2L, destHostId);
        verify(clvmPoolManagerMock, times(1)).setClvmLockHostId(3L, destHostId);
    }

    private HostVO kvmHost(long id) {
        HostVO host = mock(HostVO.class);
        Mockito.lenient().when(host.getId()).thenReturn(id);
        Mockito.lenient().when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return host;
    }

    private final GpuService startGpuService = mock(GpuService.class);
    private final Map<VirtualMachineProfile.Param, Object> startParams = new HashMap<>();
    private boolean transitionsSetHost;
    private HypervisorGuru startHvGuru;

    private ServiceOfferingVO startOffering(int cpu, Long vgpuProfileId) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        Mockito.lenient().when(offering.getCpu()).thenReturn(cpu);
        Mockito.lenient().when(offering.getVgpuProfileId()).thenReturn(vgpuProfileId);
        return offering;
    }

    /**
     * Starts a KVM user VM through orchestrateStart, planning the given hosts in turn, with a start command that
     * succeeds on the agent.
     *
     * @return the commands sent to the agents, in order, and the avoid set the start left in the plan
     */
    private Pair<List<Commands>, ExcludeList> startWithPoolCores(ServiceOfferingVO offering, HostPoolCoreManager poolCores, HostVO... hosts)
            throws Exception {
        VMInstanceVO vmInstance = new VMInstanceVO();
        ReflectionTestUtils.setField(vmInstance, "id", 1L);
        ReflectionTestUtils.setField(vmInstance, "accountId", 1L);
        ReflectionTestUtils.setField(vmInstance, "uuid", "vm-uuid");
        ReflectionTestUtils.setField(vmInstance, "serviceOfferingId", 2L);
        ReflectionTestUtils.setField(vmInstance, "instanceName", "myVm");
        ReflectionTestUtils.setField(vmInstance, "type", VirtualMachine.Type.User);
        ReflectionTestUtils.setField(vmInstance, "dataCenterId", 1L);
        ReflectionTestUtils.setField(vmInstance, "hypervisorType", HypervisorType.KVM);

        VirtualMachineGuru vmGuru = mock(VirtualMachineGuru.class);
        Mockito.lenient().when(vmGuru.finalizeStart(any(), anyLong(), any(), any())).thenReturn(true);
        User user = mock(User.class);
        Account account = mock(Account.class);
        Account owner = mock(Account.class);
        ReservationContext ctx = mock(ReservationContext.class);
        Mockito.lenient().when(ctx.getJournal()).thenReturn(mock(Journal.class));
        ItWorkVO work = mock(ItWorkVO.class);
        Mockito.lenient().when(work.getStep()).thenReturn(ItWorkVO.Step.Prepare);
        Mockito.lenient().when(_workDao.findById(any())).thenReturn(work);
        Mockito.lenient().when(_workDao.updateStep(any(), any())).thenReturn(true);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);

        Mockito.lenient().when(vmInstanceDaoMock.findByUuid("vm-uuid")).thenReturn(vmInstance);
        Mockito.lenient().doReturn(vmGuru).when(virtualMachineManagerImpl).getVmGuru(vmInstance);
        Mockito.lenient().doReturn(new Ternary<>(vmInstance, ctx, work)).when(virtualMachineManagerImpl)
                .changeToStartState(vmGuru, vmInstance, user, account, owner, offering, template);
        Mockito.lenient().when(serviceOfferingDaoMock.findById(1L, 2L)).thenReturn(offering);
        Mockito.lenient().when(_entityMgr.findById(Account.class, 1L)).thenReturn(owner);
        Mockito.lenient().when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, vmInstance.getTemplateId())).thenReturn(template);
        Mockito.lenient().doNothing().when(virtualMachineManagerImpl).checkIfTemplateNeededForCreatingVmVolumes(vmInstance);

        Cluster cluster = mock(Cluster.class);
        Mockito.lenient().when(cluster.getId()).thenReturn(1L);
        Pod pod = mock(Pod.class);
        Mockito.lenient().when(pod.getId()).thenReturn(2L);
        List<DeployDestination> destinations = new ArrayList<>();
        for (HostVO host : hosts) {
            DeployDestination dest = mock(DeployDestination.class);
            Mockito.lenient().when(dest.getHost()).thenReturn(host);
            Mockito.lenient().when(dest.getPod()).thenReturn(pod);
            Mockito.lenient().when(dest.getCluster()).thenReturn(cluster);
            destinations.add(dest);
        }
        Mockito.lenient().when(_dpMgr.planDeployment(any(VirtualMachineProfileImpl.class), any(DataCenterDeployment.class), any(ExcludeList.class),
                any(DeploymentPlanner.class))).thenReturn(destinations.get(0), destinations.subList(1, destinations.size()).toArray(new DeployDestination[0]));
        Mockito.lenient().when(_stateMachine.transitTo(any(), any(), any(), any())).thenAnswer(i -> {
            if (transitionsSetHost) {
                Pair<Long, Long> hostIds = i.getArgument(2);
                ((VMInstanceVO) i.getArgument(0)).setHostId(hostIds.second());
            }
            return true;
        });
        ClusterDetailsVO ratio = mock(ClusterDetailsVO.class);
        Mockito.lenient().when(ratio.getValue()).thenReturn("1.0");
        Mockito.lenient().when(_clusterDetailsDao.findDetail(eq(1L), anyString())).thenReturn(ratio);
        Mockito.lenient().doNothing().when(virtualMachineManagerImpl).checkAndAttemptMigrateVmAcrossCluster(any(), any(), any());
        Mockito.lenient().doNothing().when(virtualMachineManagerImpl).updateVmMetadataManufacturerAndProduct(any(), any());
        Mockito.lenient().doNothing().when(virtualMachineManagerImpl).setVmNetworkDetails(any(), any());
        Mockito.lenient().doReturn(true).when(virtualMachineManagerImpl).cleanup(any(), any(), any(), any(), Mockito.anyBoolean());

        HypervisorGuru hvGuru = mock(HypervisorGuru.class);
        Mockito.lenient().when(_hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(hvGuru);
        Mockito.lenient().when(hvGuru.implement(any())).thenAnswer(i -> {
            VirtualMachineTO to = new VirtualMachineTO(1L, "myVm", VirtualMachine.Type.User, offering.getCpu(), 2000, 1024L, 1024L, null, null,
                    false, false, null);
            to.setDetails(new HashMap<>());
            to.setDisks(new DiskTO[0]);
            to.setMetadata(new VirtualMachineMetadataTO(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null));
            return to;
        });
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_networkMgr", mock(NetworkOrchestrationService.class));
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "volumeMgr", mock(VolumeOrchestrationService.class));
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "hostPoolCoreManager", poolCores);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "gpuService", startGpuService);
        startHvGuru = hvGuru;

        List<Commands> sent = new ArrayList<>();
        Mockito.lenient().when(agentManagerMock.send(anyLong(), any(Commands.class))).thenAnswer(i -> {
            Commands cmds = i.getArgument(1);
            sent.add(cmds);
            com.cloud.agent.api.Answer[] answers = {new StartAnswer(cmds.getCommand(StartCommand.class))};
            cmds.setAnswers(answers);
            return answers;
        });

        DataCenterDeployment plan = mock(DataCenterDeployment.class);
        Mockito.lenient().when(plan.getDataCenterId()).thenReturn(1L);
        CallContext callContext = mock(CallContext.class);
        Mockito.lenient().when(callContext.getCallingAccount()).thenReturn(account);
        Mockito.lenient().when(callContext.getCallingUser()).thenReturn(user);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);
            virtualMachineManagerImpl.orchestrateStart("vm-uuid", startParams, plan, mock(DeploymentPlanner.class));
        }
        ArgumentCaptor<ExcludeList> avoids = ArgumentCaptor.forClass(ExcludeList.class);
        verify(plan).setAvoids(avoids.capture());
        return new Pair<>(sent, avoids.getValue());
    }

    private static List<String> coresSentIn(Commands cmds) {
        List<int[]> cores = cmds.getCommand(StartCommand.class).getVirtualMachine().getMetadata().getCores();
        return cores == null ? null : cores.stream().map(c -> c[0] + "," + c[1]).collect(Collectors.toList());
    }

    @Test
    public void coresAllocatedOnStartAreCarriedInTheStartCommand() throws Exception {
        HostVO host = kvmHost(1L);
        ServiceOfferingVO offering = startOffering(4, null);
        HostPoolCoreManager poolCores = mock(HostPoolCoreManager.class);
        when(poolCores.allocatePoolCores(any(VirtualMachine.class), eq(host), eq(4)))
                .thenReturn(List.of(new HostPoolCoreManager.PoolCore(0, 8, 24), new HostPoolCoreManager.PoolCore(0, 9, 25)));

        List<Commands> sent = startWithPoolCores(offering, poolCores, host).first();

        assertEquals(1, sent.size());
        assertEquals(List.of("8,24", "9,25"), coresSentIn(sent.get(0)));
        StartCommand delivered = GsonHelper.getGson().fromJson(GsonHelper.getGson().toJson(sent.get(0).getCommand(StartCommand.class)), StartCommand.class);
        List<int[]> deliveredCores = delivered.getVirtualMachine().getMetadata().getCores();
        assertEquals(2, deliveredCores.size());
        assertTrue(Arrays.equals(new int[] {8, 24}, deliveredCores.get(0)));
        assertTrue(Arrays.equals(new int[] {9, 25}, deliveredCores.get(1)));
        InOrder order = Mockito.inOrder(_stateMachine, poolCores, agentManagerMock);
        order.verify(_stateMachine).transitTo(any(), eq(VirtualMachine.Event.OperationRetry), eq(new Pair<>(null, 1L)), any());
        order.verify(poolCores).allocatePoolCores(any(VirtualMachine.class), eq(host), eq(4));
        order.verify(agentManagerMock).send(eq(1L), any(Commands.class));
    }

    @Test
    public void startMovesToAnotherHostWhenTheCoresWereTaken() throws Exception {
        HostVO busy = kvmHost(1L);
        HostVO free = kvmHost(2L);
        ServiceOfferingVO offering = startOffering(2, null);
        HostPoolCoreManager poolCores = mock(HostPoolCoreManager.class);
        when(poolCores.allocatePoolCores(any(VirtualMachine.class), eq(busy), eq(2)))
                .thenThrow(new InsufficientServerCapacityException("taken", Host.class, 1L));
        when(poolCores.allocatePoolCores(any(VirtualMachine.class), eq(free), eq(2))).thenReturn(List.of(new HostPoolCoreManager.PoolCore(1, 30, 62)));

        Pair<List<Commands>, ExcludeList> result = startWithPoolCores(offering, poolCores, busy, free);

        assertEquals(1, result.first().size());
        assertEquals(List.of("30,62"), coresSentIn(result.first().get(0)));
        assertTrue(result.second().getHostsToAvoid().contains(1L));
        verify(agentManagerMock, never()).send(eq(1L), any(Commands.class));
    }

    @Test
    public void gpuVmStartsWithoutCores() throws Exception {
        HostPoolCoreManager poolCores = mock(HostPoolCoreManager.class);

        List<Commands> sent = startWithPoolCores(startOffering(12, 5L), poolCores, kvmHost(1L)).first();

        assertEquals(1, sent.size());
        assertNull(coresSentIn(sent.get(0)));
        verify(poolCores, never()).allocatePoolCores(any(), any(), Mockito.anyInt());
    }

    @Test
    public void gpuStartAllocatesItsGroupBeforeTheHypervisorDescribesIt() throws Exception {
        HostVO host = kvmHost(1L);
        ServiceOfferingVO offering = startOffering(32, 5L);
        when(offering.getGpuCount()).thenReturn(2);

        List<Commands> sent = startWithPoolCores(offering, mock(HostPoolCoreManager.class), host).first();

        assertEquals(1, sent.size());
        InOrder order = Mockito.inOrder(startGpuService, startHvGuru, agentManagerMock);
        order.verify(startGpuService).allocateGpuGroup(any(VirtualMachine.class), eq(1L), eq(5L), eq(2));
        order.verify(startHvGuru).implement(any());
        order.verify(agentManagerMock).send(eq(1L), any(Commands.class));
    }

    @Test
    public void startMovesToAnotherHostWhenTheGpuGroupWasTaken() throws Exception {
        HostVO busy = kvmHost(1L);
        HostVO free = kvmHost(2L);
        ServiceOfferingVO offering = startOffering(32, 5L);
        when(offering.getGpuCount()).thenReturn(2);
        when(startGpuService.allocateGpuGroup(any(VirtualMachine.class), eq(1L), eq(5L), eq(2)))
                .thenThrow(new InsufficientServerCapacityException("taken", Host.class, 1L));

        Pair<List<Commands>, ExcludeList> result = startWithPoolCores(offering, mock(HostPoolCoreManager.class), busy, free);

        assertEquals(1, result.first().size());
        assertTrue(result.second().getHostsToAvoid().contains(1L));
        verify(agentManagerMock, never()).send(eq(1L), any(Commands.class));
        verify(agentManagerMock).send(eq(2L), any(Commands.class));
        verify(startGpuService).allocateGpuGroup(any(VirtualMachine.class), eq(2L), eq(5L), eq(2));
    }

    @Test
    public void cpuOnlyStartAllocatesNoGpuGroup() throws Exception {
        HostPoolCoreManager poolCores = mock(HostPoolCoreManager.class);
        when(poolCores.allocatePoolCores(any(VirtualMachine.class), any(Host.class), eq(2))).thenReturn(List.of(new HostPoolCoreManager.PoolCore(0, 8, 24)));

        startWithPoolCores(startOffering(2, null), poolCores, kvmHost(1L));

        verify(startGpuService, never()).allocateGpuGroup(any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    public void startThatOnlyPreparesVolumesAllocatesNoGpuGroup() throws Exception {
        ServiceOfferingVO offering = startOffering(32, 5L);
        Mockito.lenient().when(offering.getGpuCount()).thenReturn(2);
        startParams.put(VirtualMachineProfile.Param.ReturnAfterVolumePrepare, true);
        transitionsSetHost = true;

        List<Commands> sent = startWithPoolCores(offering, mock(HostPoolCoreManager.class), kvmHost(1L)).first();

        assertTrue(sent.isEmpty());
        verify(startGpuService, never()).allocateGpuGroup(any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    }

    /**
     * Starts a stopped KVM user VM whose ready root volume is on host 1's local pool, without a plan from the caller, so
     * a first pass is tied to that pool; the planner answers each pass in turn.
     */
    private void startFromLocalRoot(org.mockito.stubbing.Answer<DeployDestination> planner) throws Exception {
        VMInstanceVO vmInstance = new VMInstanceVO();
        ReflectionTestUtils.setField(vmInstance, "id", 1L);
        ReflectionTestUtils.setField(vmInstance, "accountId", 1L);
        ReflectionTestUtils.setField(vmInstance, "uuid", "vm-uuid");
        ReflectionTestUtils.setField(vmInstance, "serviceOfferingId", 2L);
        ReflectionTestUtils.setField(vmInstance, "instanceName", "myVm");
        ReflectionTestUtils.setField(vmInstance, "type", VirtualMachine.Type.User);
        ReflectionTestUtils.setField(vmInstance, "dataCenterId", 1L);
        ReflectionTestUtils.setField(vmInstance, "hypervisorType", HypervisorType.KVM);

        VirtualMachineGuru vmGuru = mock(VirtualMachineGuru.class);
        User user = mock(User.class);
        Account account = mock(Account.class);
        Account owner = mock(Account.class);
        ReservationContext ctx = mock(ReservationContext.class);
        Mockito.lenient().when(ctx.getJournal()).thenReturn(mock(Journal.class));
        ItWorkVO work = mock(ItWorkVO.class);
        Mockito.lenient().when(work.getStep()).thenReturn(ItWorkVO.Step.Prepare);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        ServiceOfferingVO offering = startOffering(2, null);
        Mockito.lenient().when(vmInstanceDaoMock.findByUuid("vm-uuid")).thenReturn(vmInstance);
        Mockito.lenient().doReturn(vmGuru).when(virtualMachineManagerImpl).getVmGuru(vmInstance);
        Mockito.lenient().doReturn(new Ternary<>(vmInstance, ctx, work)).when(virtualMachineManagerImpl)
                .changeToStartState(vmGuru, vmInstance, user, account, owner, offering, template);
        Mockito.lenient().when(serviceOfferingDaoMock.findById(1L, 2L)).thenReturn(offering);
        Mockito.lenient().when(_entityMgr.findById(Account.class, 1L)).thenReturn(owner);
        Mockito.lenient().when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, vmInstance.getTemplateId())).thenReturn(template);
        Mockito.lenient().doNothing().when(virtualMachineManagerImpl).checkIfTemplateNeededForCreatingVmVolumes(vmInstance);
        Mockito.lenient().when(_hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(mock(HypervisorGuru.class));
        Mockito.lenient().when(_stateMachine.transitTo(any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "hostPoolCoreManager", mock(HostPoolCoreManager.class));
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "gpuService", startGpuService);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_affinityGroupVMMapDao", mock(AffinityGroupVMMapDao.class));
        ClusterDetailsVO ratio = mock(ClusterDetailsVO.class);
        Mockito.lenient().when(ratio.getValue()).thenReturn("1.0");
        Mockito.lenient().when(_clusterDetailsDao.findDetail(eq(1L), anyString())).thenReturn(ratio);

        VolumeVO root = mock(VolumeVO.class);
        Mockito.lenient().when(root.getId()).thenReturn(301L);
        Mockito.lenient().when(root.getName()).thenReturn("ROOT-1");
        Mockito.lenient().when(root.getPoolId()).thenReturn(11L);
        Mockito.lenient().when(root.getSize()).thenReturn(10L << 30);
        Mockito.lenient().when(root.getState()).thenReturn(Volume.State.Ready);
        Mockito.lenient().when(volumeDaoMock.findReadyRootVolumesByInstance(1L)).thenReturn(Collections.singletonList(root));
        Mockito.lenient().when(volumeDaoMock.findUsableVolumesForInstance(1L)).thenReturn(Collections.singletonList(root));
        PrimaryDataStore store = mock(PrimaryDataStore.class);
        Mockito.lenient().when(store.getDataCenterId()).thenReturn(1L);
        Mockito.lenient().when(store.getPodId()).thenReturn(2L);
        Mockito.lenient().when(store.getClusterId()).thenReturn(1L);
        DataStoreManager stores = mock(DataStoreManager.class);
        Mockito.lenient().when(stores.getPrimaryDataStore(11L)).thenReturn(store);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "dataStoreMgr", stores);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "dataStoreProviderManager", mock(DataStoreProviderManager.class));
        StoragePoolVO pool = mock(StoragePoolVO.class);
        Mockito.lenient().when(pool.getId()).thenReturn(11L);
        Mockito.lenient().when(pool.getScope()).thenReturn(ScopeType.HOST);
        Mockito.lenient().when(storagePoolDaoMock.findById(11L)).thenReturn(pool);
        Mockito.lenient().when(storagePoolHostDaoMock.listByPoolId(11L)).thenReturn(Collections.singletonList(new StoragePoolHostVO(11L, 1L, "/var/lib/libvirt/images")));

        Mockito.lenient().when(_dpMgr.planDeployment(any(VirtualMachineProfileImpl.class), any(DataCenterDeployment.class), any(ExcludeList.class), any()))
                .thenAnswer(planner);
        CallContext callContext = mock(CallContext.class);
        Mockito.lenient().when(callContext.getCallingAccount()).thenReturn(account);
        Mockito.lenient().when(callContext.getCallingUser()).thenReturn(user);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);
            virtualMachineManagerImpl.orchestrateStart("vm-uuid", startParams, null, null);
        }
    }

    @Test
    public void secondPassUsesFreshExcludeList() {
        List<Set<Long>> hostsAvoidedAtCall = new ArrayList<>();
        List<Object> allowVolumeMoveAtCall = new ArrayList<>();
        List<Long> poolOfPlanAtCall = new ArrayList<>();

        assertThrows(InsufficientServerCapacityException.class, () -> startFromLocalRoot(invocation -> {
            VirtualMachineProfile profile = invocation.getArgument(0);
            ExcludeList avoids = invocation.getArgument(2);
            hostsAvoidedAtCall.add(avoids.getHostsToAvoid() == null ? new HashSet<>() : new HashSet<>(avoids.getHostsToAvoid()));
            allowVolumeMoveAtCall.add(profile.getParameter(VirtualMachineProfile.Param.AllowVolumeMove));
            poolOfPlanAtCall.add(((DeploymentPlan)invocation.getArgument(1)).getPoolId());
            // the first pass, tied to the root volume's pool, rules out the hosts that cannot reach that pool
            avoids.addHost(7L);
            return null;
        }));

        assertEquals(2, hostsAvoidedAtCall.size());
        assertEquals(Long.valueOf(11L), poolOfPlanAtCall.get(0));
        assertNull("the second pass is not tied to the root volume's pool", poolOfPlanAtCall.get(1));
        assertFalse("what the first pass ruled out for the pool does not carry into the second", hostsAvoidedAtCall.get(1).contains(7L));
        assertNull(allowVolumeMoveAtCall.get(0));
        assertEquals("the second pass may name a host without the local volumes", Boolean.TRUE, allowVolumeMoveAtCall.get(1));
    }

    @Test
    public void startOnAHostWithoutItsLocalVolumesNeedsAnApprovedMove() throws Exception {
        HostVO other = kvmHost(2L);
        Cluster cluster = mock(Cluster.class);
        Mockito.lenient().when(cluster.getId()).thenReturn(1L);
        Pod pod = mock(Pod.class);
        Mockito.lenient().when(pod.getId()).thenReturn(2L);
        DeployDestination elsewhere = mock(DeployDestination.class);
        Mockito.lenient().when(elsewhere.getHost()).thenReturn(other);
        Mockito.lenient().when(elsewhere.getPod()).thenReturn(pod);
        Mockito.lenient().when(elsewhere.getCluster()).thenReturn(cluster);
        List<Integer> calls = new ArrayList<>();

        VolumeMoveRequiredException e = assertThrows(VolumeMoveRequiredException.class, () -> startFromLocalRoot(invocation -> {
            calls.add(calls.size());
            return calls.size() == 1 ? null : elsewhere;
        }));

        assertEquals(Collections.singletonList(301L), e.getVerdict().getVolumeIds());
        assertEquals(PlacementVerdict.Verdict.move, e.getVerdict().getVerdict());
        assertTrue(e.getMessage(), e.getMessage().contains("ROOT-1"));
        verify(_stateMachine, never()).transitTo(any(), eq(VirtualMachine.Event.OperationRetry), any(), any());
        verify(startGpuService, never()).allocateGpuGroup(any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
        verify(_stateMachine).transitTo(any(), eq(VirtualMachine.Event.OperationFailed), any(), any());
    }

    @Test
    public void destroyWithExpungeDeallocatesEveryGpuDeviceOfTheInstance() throws Exception {
        GpuService gpuService = mock(GpuService.class);
        VMSnapshotManager vmSnapshotManager = mock(VMSnapshotManager.class);
        VMInstanceDao vmDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "gpuService", gpuService);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_vmSnapshotMgr", vmSnapshotManager);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_vmDao", vmDao);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "backupManager", mock(BackupManager.class));
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(4242L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getHostId()).thenReturn(null);
        when(vmDao.findByUuid("instance-with-gpus")).thenReturn(vm);
        when(vmSnapshotManager.deleteAllVMSnapshots(4242L, null)).thenReturn(true);
        doNothing().when(virtualMachineManagerImpl).advanceStop("instance-with-gpus", false);
        doReturn(true).when(virtualMachineManagerImpl).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), Mockito.nullable(Long.class));

        virtualMachineManagerImpl.destroy("instance-with-gpus", true);

        InOrder order = Mockito.inOrder(virtualMachineManagerImpl, gpuService);
        order.verify(virtualMachineManagerImpl).advanceStop("instance-with-gpus", false);
        order.verify(gpuService).deallocateAllGpuDevicesForVm(4242L);
        order.verify(virtualMachineManagerImpl).stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, null);
        order.verify(virtualMachineManagerImpl).stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, null);
    }

    private static final long DEPLOYED_VOLUME_ID = 5152L;

    /** Allocates an Instance from a volume with the given step failing, and returns the Instance. */
    private VMInstanceVO allocateFromAVolumeFailingAt(String failingStep, VolumeOrchestrationService volumes) throws Exception {
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "volumeMgr", volumes);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_networkMgr", mock(NetworkOrchestrationService.class));
        VMInstanceDao vmDao = mock(VMInstanceDao.class);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_vmDao", vmDao);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(5151L);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.lenient().when(vm.getUuid()).thenReturn("instance-from-volume");
        when(vmDao.findVMByInstanceName("i-2-5151-VM")).thenReturn(vm);
        when(vmDao.persist(vm)).thenReturn(vm);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        doReturn(false).when(virtualMachineManagerImpl).isBlankInstance(template);
        doReturn(true).when(virtualMachineManagerImpl).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), Mockito.nullable(Long.class));
        Volume given = mock(Volume.class);
        when(given.getId()).thenReturn(DEPLOYED_VOLUME_ID);
        DiskOffering dataDiskOffering = mock(DiskOffering.class);
        if ("root".equals(failingStep)) {
            when(volumes.allocateTemplatedVolumes(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(given), any()))
                    .thenThrow(new CloudRuntimeException("the ROOT volume could not take the disk offering"));
        } else {
            when(volumes.allocateRawVolume(eq(Volume.Type.DATADISK), any(), eq(dataDiskOffering), any(), any(), any(), any(), any(), any(), any(), any(),
                    Mockito.anyBoolean())).thenThrow(new CloudRuntimeException("the data volume could not be allocated"));
        }
        DataCenterDeployment plan = new DataCenterDeployment(1L);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> virtualMachineManagerImpl.allocate("i-2-5151-VM", template,
                    mock(ServiceOffering.class), new DiskOfferingInfo(mock(DiskOffering.class)), List.of(new DiskOfferingInfo(dataDiskOffering, 10L, null, null)),
                    Arrays.asList(1L), new LinkedHashMap<>(), plan, HypervisorType.KVM, null, null, given, null));
            assertTrue(e.getMessage(), e.getMessage().contains("could not"));
        }
        return vm;
    }

    @Test
    public void aFailedAllocationDetachesTheVolumeItWasDeployedFromBeforeTheInstanceGoesToError() throws Exception {
        for (String failingStep : List.of("root", "data")) {
            VolumeOrchestrationService volumes = mock(VolumeOrchestrationService.class);
            Mockito.clearInvocations(virtualMachineManagerImpl);

            VMInstanceVO vm = allocateFromAVolumeFailingAt(failingStep, volumes);

            InOrder order = Mockito.inOrder(volumes, virtualMachineManagerImpl);
            order.verify(volumes).detachVolumeOfFailedDeployment(5151L, DEPLOYED_VOLUME_ID);
            order.verify(virtualMachineManagerImpl).stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
        }
    }

    @Test
    public void anInstanceWhoseVolumeCouldNotBeDetachedIsNotMovedToError() throws Exception {
        VolumeOrchestrationService volumes = mock(VolumeOrchestrationService.class);
        doThrow(new CloudRuntimeException("the database is unavailable")).when(volumes).detachVolumeOfFailedDeployment(5151L, DEPLOYED_VOLUME_ID);
        AlertManager alerts = mock(AlertManager.class);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_alertMgr", alerts);

        allocateFromAVolumeFailingAt("data", volumes);

        verify(volumes).detachVolumeOfFailedDeployment(5151L, DEPLOYED_VOLUME_ID);
        verify(virtualMachineManagerImpl, never()).stateTransitTo(any(VirtualMachine.class), eq(VirtualMachine.Event.OperationFailedToError), Mockito.nullable(Long.class));
        verify(alerts).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_USERVM), anyLong(), any(), eq("Failed to deploy Instance [ID: instance-from-volume]"),
                Mockito.argThat(body -> body.contains("Mock for Volume") && !body.contains("host")));
    }

    private VMInstanceVO stoppedInstance(VirtualMachine.Type type, State state) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        Mockito.lenient().when(vm.getId()).thenReturn(7L);
        Mockito.lenient().when(vm.getType()).thenReturn(type);
        Mockito.lenient().when(vm.getState()).thenReturn(state);
        Mockito.lenient().when(vm.getAccountId()).thenReturn(2L);
        Mockito.lenient().when(vm.getServiceOfferingId()).thenReturn(3L);
        Mockito.lenient().when(vm.getTemplateId()).thenReturn(4L);
        return vm;
    }

    @Test
    public void startOfAStoppedUserInstanceIsRefusedWhileItsOwnerIsOverQuota() throws Exception {
        VMInstanceVO vm = stoppedInstance(VirtualMachine.Type.User, State.Stopped);
        when(vmInstanceDaoMock.findByUuid("vm-uuid")).thenReturn(vm);
        VirtualMachineGuru vmGuru = mock(VirtualMachineGuru.class);
        doReturn(vmGuru).when(virtualMachineManagerImpl).getVmGuru(vm);
        Account owner = mock(Account.class);
        when(_entityMgr.findById(Account.class, 2L)).thenReturn(owner);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDaoMock.findById(7L, 3L)).thenReturn(offering);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(_entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, 4L)).thenReturn(template);
        Mockito.doThrow(new ResourceAllocationException("quota expired or insufficient: the gpu count of account a is 2, above its limit of 1",
                Resource.ResourceType.gpu)).when(resourceLimitServiceMock).checkWithinQuota(owner, offering, template);
        CallContext callContext = mock(CallContext.class);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);

            CloudRuntimeException refused = assertThrows(CloudRuntimeException.class,
                    () -> virtualMachineManagerImpl.orchestrateStart("vm-uuid", new HashMap<>(), null, null));

            assertTrue(refused.getMessage(), refused.getMessage().contains("quota expired or insufficient"));
        }
        verify(virtualMachineManagerImpl, never()).changeToStartState(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void restartWithinARunningSessionAndSystemVmStartsAreNotQuotaChecked() throws Exception {
        Account owner = mock(Account.class);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);

        VMInstanceVO restarting = stoppedInstance(VirtualMachine.Type.User, State.Stopped);
        when(vmInstanceDetailsDao.findDetail(7L, VmDetailConstants.RESTART_IN_PROGRESS))
                .thenReturn(new VMInstanceDetailVO(7L, VmDetailConstants.RESTART_IN_PROGRESS, "job:3", false));
        virtualMachineManagerImpl.checkQuotaOfSessionStart(restarting, owner, offering, template);
        virtualMachineManagerImpl.checkQuotaOfSessionStart(stoppedInstance(VirtualMachine.Type.DomainRouter, State.Stopped), owner, offering, template);
        virtualMachineManagerImpl.checkQuotaOfSessionStart(stoppedInstance(VirtualMachine.Type.User, State.Running), owner, offering, template);
        verify(resourceLimitServiceMock, never()).checkWithinQuota(any(), any(), any());

        when(vmInstanceDetailsDao.findDetail(7L, VmDetailConstants.RESTART_IN_PROGRESS)).thenReturn(null);
        virtualMachineManagerImpl.checkQuotaOfSessionStart(restarting, owner, offering, template);
        verify(resourceLimitServiceMock).checkWithinQuota(owner, offering, template);
    }

    /** Allocates the ROOT volume of the Instance from a template of the format, and returns the name it was allocated with. */
    private String rootVolumeNameAllocatedFor(VMInstanceVO vm, Storage.ImageFormat format) {
        return rootVolumeNameAllocatedFor(vm, format, null);
    }

    private String rootVolumeNameAllocatedFor(VMInstanceVO vm, Storage.ImageFormat format, com.cloud.storage.Snapshot snapshot) {
        VolumeOrchestrationService volumes = mock(VolumeOrchestrationService.class);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "volumeMgr", volumes);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(format);
        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            virtualMachineManagerImpl.allocateRootVolume(vm, template, new DiskOfferingInfo(mock(DiskOffering.class)), mock(Account.class), 10L, null, snapshot);
        }
        if (snapshot != null) {
            verify(volumes).allocateTemplatedVolumes(eq(Volume.Type.ROOT), any(), any(), any(), any(), any(), eq(template), eq(vm), any(), any(), isNull(),
                    eq(snapshot));
        }
        org.mockito.ArgumentCaptor<String> name = org.mockito.ArgumentCaptor.forClass(String.class);
        if (format == Storage.ImageFormat.ISO) {
            verify(volumes).allocateRawVolume(eq(Volume.Type.ROOT), name.capture(), any(), any(), any(), any(), eq(vm), eq(template), any(), any(), any(),
                    Mockito.anyBoolean());
        } else {
            verify(volumes).allocateTemplatedVolumes(eq(Volume.Type.ROOT), name.capture(), any(), any(), any(), any(), eq(template), eq(vm), any(), any(), any(),
                    any());
        }
        return name.getValue();
    }

    /** An Instance of the type with the display name, named under the prefix of its account's Instances, or under none when it is null. */
    private VMInstanceVO instanceOfType(long id, VirtualMachine.Type type, String displayName, String prefix) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        Mockito.lenient().when(vm.getId()).thenReturn(id);
        Mockito.lenient().when(vm.getType()).thenReturn(type);
        UserVmVO userVm = mock(UserVmVO.class);
        Mockito.lenient().when(userVm.getDisplayName()).thenReturn(displayName);
        Mockito.lenient().when(userVmDaoMock.findById(id)).thenReturn(userVm);
        Mockito.lenient().when(vmInstanceDetailsDao.findSshNamePrefix(id)).thenReturn(prefix);
        return vm;
    }

    @Test
    public void theRootVolumeOfAnInstanceNamedUnderAPrefixIsNamedAfterTheInstance() {
        assertEquals("Web-1-ROOT", rootVolumeNameAllocatedFor(instanceOfType(5151L, VirtualMachine.Type.User, "Web-1", "admin"), Storage.ImageFormat.QCOW2));
        assertEquals("VM-0-ROOT", rootVolumeNameAllocatedFor(instanceOfType(5152L, VirtualMachine.Type.User, "VM-0", "admin"), Storage.ImageFormat.ISO));
    }

    @Test
    public void theRootVolumeCreatedFromASnapshotIsNamedAfterTheInstanceNamedUnderAPrefix() {
        com.cloud.storage.Snapshot snapshot = mock(com.cloud.storage.Snapshot.class);
        assertEquals("Web-2-ROOT", rootVolumeNameAllocatedFor(instanceOfType(5157L, VirtualMachine.Type.User, "Web-2", "admin"), Storage.ImageFormat.QCOW2,
                snapshot));
    }

    @Test
    public void theRootVolumeOfAnyOtherInstanceIsNamedAfterItsId() {
        assertEquals("ROOT-5153", rootVolumeNameAllocatedFor(instanceOfType(5153L, VirtualMachine.Type.DomainRouter, "r-5153-VM", null),
                Storage.ImageFormat.QCOW2));
        assertEquals("ROOT-5154", rootVolumeNameAllocatedFor(instanceOfType(5154L, VirtualMachine.Type.ConsoleProxy, null, null), Storage.ImageFormat.QCOW2));
        assertEquals("ROOT-5156", rootVolumeNameAllocatedFor(instanceOfType(5156L, VirtualMachine.Type.User, "cluster-control-18a3b0c4d5e6f", null),
                Storage.ImageFormat.QCOW2));
    }

    /**
     * The Instance detail table in memory behind the real detail DAO: the statements the DAO issues are simulated by the conditions
     * of their search criteria, names and values comparing regardless of case as the database's collation compares them.
     */
    @SuppressWarnings("unchecked")
    private static com.cloud.vm.dao.VMInstanceDetailsDaoImpl detailTableOver(List<VMInstanceDetailVO> rows) {
        com.cloud.vm.dao.VMInstanceDetailsDaoImpl dao = Mockito.spy(new com.cloud.vm.dao.VMInstanceDetailsDaoImpl());
        long[] nextId = {1};
        Mockito.lenient().doAnswer(i -> selectDetails(rows, i.getArgument(0))).when(dao).search(any(com.cloud.utils.db.SearchCriteria.class), any());
        Mockito.lenient().doAnswer(i -> selectDetails(rows, i.getArgument(0))).when(dao).listBy(any(com.cloud.utils.db.SearchCriteria.class));
        Mockito.lenient().doAnswer(i -> selectDetails(rows, i.getArgument(0)).stream().findFirst().orElse(null)).when(dao)
                .findOneBy(any(com.cloud.utils.db.SearchCriteria.class));
        Mockito.lenient().doAnswer(i -> {
            List<VMInstanceDetailVO> deleted = selectDetails(rows, i.getArgument(0));
            rows.removeAll(deleted);
            return deleted.size();
        }).when(dao).remove(any(com.cloud.utils.db.SearchCriteria.class));
        Mockito.lenient().doAnswer(i -> {
            List<VMInstanceDetailVO> deleted = selectDetails(rows, i.getArgument(0));
            rows.removeAll(deleted);
            return deleted.size();
        }).when(dao).expunge(any(com.cloud.utils.db.SearchCriteria.class));
        Mockito.lenient().doAnswer(i -> rows.removeIf(row -> row.getId() == (Long) i.getArgument(0))).when(dao).remove(anyLong());
        Mockito.lenient().doAnswer(i -> {
            VMInstanceDetailVO row = i.getArgument(0);
            ReflectionTestUtils.setField(row, "id", nextId[0]++);
            rows.add(row);
            return row;
        }).when(dao).persist(any(VMInstanceDetailVO.class));
        return dao;
    }

    private static List<VMInstanceDetailVO> selectDetails(List<VMInstanceDetailVO> rows, com.cloud.utils.db.SearchCriteria<VMInstanceDetailVO> sc) {
        Set<Object> vmIds = criteriaValues(sc, "vm_id");
        Set<Object> names = criteriaValues(sc, "name");
        Set<Object> values = criteriaValues(sc, "value");
        Set<Object> display = criteriaValues(sc, "display");
        boolean namesExcluded = sc.getWhereClause().contains("name NOT IN");
        return rows.stream().filter(row -> (vmIds.isEmpty() || vmIds.contains(row.getResourceId()))
                && (names.isEmpty() || names.stream().anyMatch(name -> row.getName().equalsIgnoreCase(String.valueOf(name))) != namesExcluded)
                && (values.isEmpty() || values.stream().anyMatch(value -> row.getValue().equalsIgnoreCase(String.valueOf(value))))
                && (display.isEmpty() || display.contains(row.isDisplay()))).collect(Collectors.toList());
    }

    private static Set<Object> criteriaValues(com.cloud.utils.db.SearchCriteria<VMInstanceDetailVO> sc, String column) {
        return sc.getValues().stream().filter(value -> column.equals(value.first().getColumnName())).map(value -> value.second())
                .collect(Collectors.toSet());
    }

    private static VMInstanceDetailVO detailNamed(List<VMInstanceDetailVO> rows, String name) {
        return rows.stream().filter(row -> row.getName().equals(name)).findFirst().orElse(null);
    }

    private static void assertTheOtherDetailsAreAsTheyWere(List<VMInstanceDetailVO> rows, com.cloud.vm.dao.VMInstanceDetailsDaoImpl details) {
        assertEquals("admin", details.findSshNamePrefix(1L));
        assertEquals("admin", detailNamed(rows, VmDetailConstants.SSH_NAME_PREFIX).getValue());
        assertEquals("i-2-1-web", detailNamed(rows, VmDetailConstants.NAME_ON_HYPERVISOR).getValue());
        assertEquals("virtio", detailNamed(rows, VmDetailConstants.ROOT_DISK_CONTROLLER).getValue());
        for (String hidden : List.of(VmDetailConstants.SSH_NAME_PREFIX, VmDetailConstants.NAME_ON_HYPERVISOR, VmDetailConstants.ROOT_DISK_CONTROLLER)) {
            assertFalse(hidden, detailNamed(rows, hidden).isDisplay());
        }
        assertTrue(detailNamed(rows, "keyboard").isDisplay());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aChangeToOrFromACustomizedOfferingWritesOnlyItsOwnDetails() {
        List<VMInstanceDetailVO> rows = new ArrayList<>();
        com.cloud.vm.dao.VMInstanceDetailsDaoImpl details = detailTableOver(rows);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "vmInstanceDetailsDao", details);
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_userVmMgr", mock(UserVmManager.class));
        ReflectionTestUtils.setField(virtualMachineManagerImpl, "_serviceOfferingDao", serviceOfferingDaoMock);
        details.persist(new VMInstanceDetailVO(1L, VmDetailConstants.SSH_NAME_PREFIX, "admin", false));
        details.persist(new VMInstanceDetailVO(1L, VmDetailConstants.NAME_ON_HYPERVISOR, "i-2-1-web", false));
        details.persist(new VMInstanceDetailVO(1L, VmDetailConstants.ROOT_DISK_CONTROLLER, "virtio", false));
        details.persist(new VMInstanceDetailVO(1L, "keyboard", "us", true));
        when(vmInstanceDaoMock.findById(1L)).thenReturn(vmInstanceMock);
        ServiceOfferingVO customized = mock(ServiceOfferingVO.class);
        when(customized.getId()).thenReturn(5L);
        when(customized.isDynamic()).thenReturn(true);
        when(customized.getCpu()).thenReturn(4);
        when(customized.getRamSize()).thenReturn(8192);
        ServiceOfferingVO unfilled = mock(ServiceOfferingVO.class);
        when(unfilled.getCpu()).thenReturn(null);
        when(unfilled.getSpeed()).thenReturn(2000);
        when(unfilled.getRamSize()).thenReturn(null);
        when(serviceOfferingDaoMock.findByIdIncludingRemoved(5L)).thenReturn(unfilled);
        ServiceOfferingVO fixed = mock(ServiceOfferingVO.class);
        when(fixed.getId()).thenReturn(6L);
        when(_entityMgr.findById(ServiceOffering.class, 5L)).thenReturn(customized);
        when(_entityMgr.findById(ServiceOffering.class, 6L)).thenReturn(fixed);

        try (MockedStatic<com.cloud.utils.db.Transaction> transaction = Mockito.mockStatic(com.cloud.utils.db.Transaction.class);
             MockedStatic<com.cloud.utils.db.TransactionLegacy> txn = Mockito.mockStatic(com.cloud.utils.db.TransactionLegacy.class)) {
            transaction.when(() -> com.cloud.utils.db.Transaction.execute(any(com.cloud.utils.db.TransactionCallback.class)))
                    .thenAnswer(i -> ((com.cloud.utils.db.TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
            txn.when(com.cloud.utils.db.TransactionLegacy::currentTxn).thenReturn(mock(com.cloud.utils.db.TransactionLegacy.class));

            virtualMachineManagerImpl.upgradeVmDb(1L, customized, fixed);

            assertEquals("4", detailNamed(rows, com.cloud.event.UsageEventVO.DynamicParameters.cpuNumber.name()).getValue());
            assertEquals("8192", detailNamed(rows, com.cloud.event.UsageEventVO.DynamicParameters.memory.name()).getValue());
            assertNull(detailNamed(rows, com.cloud.event.UsageEventVO.DynamicParameters.cpuSpeed.name()));
            assertTheOtherDetailsAreAsTheyWere(rows, details);

            virtualMachineManagerImpl.upgradeVmDb(1L, fixed, customized);

            assertNull(detailNamed(rows, com.cloud.event.UsageEventVO.DynamicParameters.cpuNumber.name()));
            assertNull(detailNamed(rows, com.cloud.event.UsageEventVO.DynamicParameters.memory.name()));
            assertTheOtherDetailsAreAsTheyWere(rows, details);
            assertEquals(4, rows.size());
        }
    }
}
