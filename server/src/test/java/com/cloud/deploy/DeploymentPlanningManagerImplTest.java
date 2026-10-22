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


import com.cloud.agent.AgentManager;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanner.PlannerResourceUsage;
import com.cloud.deploy.dao.PlannerHostReservationDao;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.gpu.GPU;
import com.cloud.gpu.dao.HostGpuGroupsDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.affinity.AffinityGroupProcessor;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupDomainMapDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMReservationDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.test.utils.SpringUtils;
import org.apache.commons.collections.CollectionUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
public class DeploymentPlanningManagerImplTest {

    @Spy
    @InjectMocks
    DeploymentPlanningManagerImpl _dpm;

    @Inject
    PlannerHostReservationDao _plannerHostReserveDao;

    @Inject
    VirtualMachineProfileImpl vmProfile;

    @Inject
    private AccountDao accountDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    AffinityGroupVMMapDao _affinityGroupVMMapDao;

    @Inject
    ExcludeList avoids;

    @Inject
    DataCenterVO dc;

    @Inject
    DataCenterDao _dcDao;

    @Mock
    FirstFitPlanner _planner;

    @Inject
    ClusterDao _clusterDao;

    @Inject
    DedicatedResourceDao _dedicatedDao;

    @Inject
    VMInstanceDetailsDao vmDetailsDao;

    @Inject
    VMTemplateDao templateDao;

    @Inject
    HostPodDao hostPodDao;

    @Inject
    VolumeDao volDao;

    @Inject
    HostDao hostDao;

    @Inject
    CapacityManager capacityMgr;

    @Inject
    ResourceManager resourceManager;

    @Inject
    ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Inject
    ClusterDetailsDao clusterDetailsDao;

    @Inject
    PrimaryDataStoreDao primaryDataStoreDao;

    @Mock
    Host host;

    @Mock
    ConfigurationDao configDao;

    @Mock
    AccountManager _accountMgr;

    @Inject
    DiskOfferingDao _diskOfferingDao;

    @Mock
    DataStoreManager _dataStoreManager;

    @Inject
    HostPodDao _podDao;

    private static final long dataCenterId = 1L;
    private static final long instanceId = 123L;
    private static final long hostId = 0L;
    private static final long podId = 2L;
    private static final long clusterId = 3L;
    private static final long ADMIN_ACCOUNT_ROLE_ID = 1L;
    private AutoCloseable closeable;

    @BeforeClass
    public static void setUp() throws ConfigurationException {
    }

    @Before
    public void testSetUp() {
        closeable = MockitoAnnotations.openMocks(this);

        ComponentContext.initComponentsLifeCycle();

        PlannerHostReservationVO reservationVO = new PlannerHostReservationVO(hostId, dataCenterId, podId, clusterId, PlannerResourceUsage.Shared);
        Mockito.when(_plannerHostReserveDao.persist(ArgumentMatchers.any(PlannerHostReservationVO.class))).thenReturn(reservationVO);
        Mockito.when(_plannerHostReserveDao.findById(ArgumentMatchers.anyLong())).thenReturn(reservationVO);
        Mockito.when(_affinityGroupVMMapDao.countAffinityGroupsForVm(ArgumentMatchers.anyLong())).thenReturn(0L);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.isDeployAsIs()).thenReturn(false);
        Mockito.when(templateDao.findById(Mockito.anyLong())).thenReturn(template);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getType()).thenReturn(Type.Instance);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        Mockito.when(vmProfile.getVirtualMachine()).thenReturn(vm);
        Mockito.when(vmProfile.getId()).thenReturn(instanceId);

        Mockito.when(vmDetailsDao.listDetailsKeyPairs(ArgumentMatchers.anyLong())).thenReturn(null);

        Mockito.when(volDao.findByInstance(ArgumentMatchers.anyLong())).thenReturn(new ArrayList<>());

        Mockito.when(_dcDao.findById(ArgumentMatchers.anyLong())).thenReturn(dc);
        Mockito.when(dc.getId()).thenReturn(dataCenterId);

        ClusterVO clusterVO = new ClusterVO();
        clusterVO.setHypervisorType(HypervisorType.XenServer.toString());
        Mockito.when(_clusterDao.findById(ArgumentMatchers.anyLong())).thenReturn(clusterVO);

        Mockito.when(_planner.getName()).thenReturn("FirstFitPlanner");
        List<DeploymentPlanner> planners = new ArrayList<DeploymentPlanner>();
        planners.add(_planner);
        _dpm.setPlanners(planners);
        StoragePoolAllocator allocator = Mockito.mock(StoragePoolAllocator.class);
        _dpm.setStoragePoolAllocators(Arrays.asList(allocator));

        Mockito.when(host.getId()).thenReturn(hostId);
        Mockito.doNothing().when(_dpm).avoidDisabledResources(vmProfile, dc, avoids);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    public void dataCenterAvoidTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
                new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                        false, VirtualMachine.Type.User, null, "FirstFitPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);

        Mockito.when(avoids.shouldAvoid((DataCenterVO) ArgumentMatchers.any())).thenReturn(true);
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(vmProfile.getTemplate()).thenReturn(template);
        Mockito.when(_clusterDao.listClustersByArchAndZoneId(dataCenterId, CPU.CPUArch.arm64)).thenReturn(null);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("DataCenter is in avoid set, destination should be null! ", dest);
    }

    @Test
    public void plannerCannotHandleTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
                new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                        false, VirtualMachine.Type.User, null, "UserDispersingPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        Mockito.when(avoids.shouldAvoid((DataCenterVO) ArgumentMatchers.any())).thenReturn(false);

        Mockito.when(_planner.canHandle(vmProfile, plan, avoids)).thenReturn(false);
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(vmProfile.getTemplate()).thenReturn(template);
        Mockito.when(_clusterDao.listClustersByArchAndZoneId(dataCenterId, CPU.CPUArch.arm64)).thenReturn(null);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("Planner cannot handle, destination should be null! ", dest);
    }

    @Test
    public void emptyClusterListTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
                new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                        false, VirtualMachine.Type.User, null, "FirstFitPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        Mockito.when(avoids.shouldAvoid((DataCenterVO) ArgumentMatchers.any())).thenReturn(false);
        Mockito.when(_planner.canHandle(vmProfile, plan, avoids)).thenReturn(true);

        Mockito.when(((DeploymentClusterPlanner) _planner).orderClusters(vmProfile, plan, avoids)).thenReturn(null);
        VirtualMachineTemplate template = Mockito.mock(VirtualMachineTemplate.class);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(vmProfile.getTemplate()).thenReturn(template);
        Mockito.when(_clusterDao.listClustersByArchAndZoneId(dataCenterId, CPU.CPUArch.arm64)).thenReturn(null);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("Planner cannot handle, destination should be null! ", dest);
    }

    @Test
    public void testCheckAffinityEmptyPreferredHosts() {
        assertTrue(_dpm.checkAffinity(host, new ArrayList<>()));
    }

    @Test
    public void testCheckAffinityNullPreferredHosts() {
        assertTrue(_dpm.checkAffinity(host, null));
    }

    @Test
    public void testCheckAffinityNotEmptyPreferredHostsContainingHost() {
        assertTrue(_dpm.checkAffinity(host, Arrays.asList(3l, 4l, hostId, 2l)));
    }

    @Test
    public void testCheckAffinityNotEmptyPreferredHostsNotContainingHost() {
        assertFalse(_dpm.checkAffinity(host, Arrays.asList(3l, 4l, 2l)));
    }

    @Test
    public void routerInDisabledResourceAssertFalse() {
        Assert.assertFalse(DeploymentPlanningManager.allowRouterOnDisabledResource.value());
    }

    @Test
    public void adminVmInDisabledResourceAssertFalse() {
        Assert.assertFalse(DeploymentPlanningManager.allowAdminVmOnDisabledResource.value());
    }

    @Test
    public void avoidDisabledResourcesTestAdminAccount() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, ADMIN_ACCOUNT_ROLE_ID, vmTypes[i], true, false);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 1, 1, ADMIN_ACCOUNT_ROLE_ID, vmTypes[i], true, false);
            }
        }
    }

    @Test
    public void avoidDisabledResourcesTestUserAccounAdminCannotDeployOnDisabled() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            long userAccountId = ADMIN_ACCOUNT_ROLE_ID + 1;
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, userAccountId, vmTypes[i], true, false);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 0, 1, userAccountId, vmTypes[i], true, false);
            }
        }
    }

    @Test
    public void avoidDisabledResourcesTestUserAccounAdminCanDeployOnDisabled() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            long userAccountId = ADMIN_ACCOUNT_ROLE_ID + 1;
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, userAccountId, vmTypes[i], true, true);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 0, 1, userAccountId, vmTypes[i], true, true);
            }
        }
    }

    private void prepareAndVerifyAvoidDisabledResourcesTest(int timesRouter, int timesAdminVm,
            int timesDisabledResource, long roleId, Type vmType, boolean isSystemDepolyable,
            boolean isAdminVmDeployable) {
        Mockito.doReturn(isSystemDepolyable).when(_dpm).isRouterDeployableInDisabledResources();
        Mockito.doReturn(isAdminVmDeployable).when(_dpm).isAdminVmDeployableInDisabledResources();

        VirtualMachineProfile vmProfile = Mockito.mock(VirtualMachineProfile.class);
        DataCenter dc = Mockito.mock(DataCenter.class);
        ExcludeList avoids = Mockito.mock(ExcludeList.class);

        Mockito.when(vmProfile.getType()).thenReturn(vmType);
        Mockito.when(vmProfile.getId()).thenReturn(1l);

        Mockito.doNothing().when(_dpm).avoidDisabledDataCenters(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledPods(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledClusters(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledHosts(dc, avoids);

        VMInstanceVO vmInstanceVO = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vmInstanceDao.findById(Mockito.anyLong())).thenReturn(vmInstanceVO);
        AccountVO owner = Mockito.mock(AccountVO.class);
        Mockito.when(owner.getRoleId()).thenReturn(roleId);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(owner);

        _dpm.avoidDisabledResources(vmProfile, dc, avoids);

        Mockito.verify(_dpm, Mockito.times(timesRouter)).isRouterDeployableInDisabledResources();
        Mockito.verify(_dpm, Mockito.times(timesAdminVm)).isAdminVmDeployableInDisabledResources();
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledDataCenters(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledPods(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledClusters(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledHosts(dc, avoids);
        Mockito.reset(_dpm);
    }

    @Test
    public void avoidDisabledDataCentersTest() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        Mockito.when(dc.getId()).thenReturn(123l);

        ExcludeList avoids = new ExcludeList();
        AllocationState[] allocationStates = AllocationState.values();
        for (int i = 0; i < allocationStates.length - 1; ++i) {
            Mockito.when(dc.getAllocationState()).thenReturn(allocationStates[i]);

            _dpm.avoidDisabledDataCenters(dc, avoids);

            if (allocationStates[i] == AllocationState.Disabled) {
                assertAvoidIsEmpty(avoids, false, true, true, true);
                Assert.assertTrue(avoids.getDataCentersToAvoid().size() == 1);
                Assert.assertTrue(avoids.getDataCentersToAvoid().contains(dc.getId()));
            } else {
                assertAvoidIsEmpty(avoids, true, true, true, true);
            }
        }
    }

    @Test
    public void avoidDisabledPodsTestNoDisabledPod() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        List<Long> podIds = new ArrayList<>();
        long expectedPodId = 123l;
        podIds.add(expectedPodId);
        Mockito.doReturn(new ArrayList<>()).when(hostPodDao).listDisabledPods(Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledPods(dc, avoids);
        assertAvoidIsEmpty(avoids, true, true, true, true);
    }

    @Test
    public void avoidDisabledPodsTestHasDisabledPod() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        List<Long> podIds = new ArrayList<>();
        long expectedPodId = 123l;
        podIds.add(expectedPodId);
        Mockito.doReturn(podIds).when(hostPodDao).listDisabledPods(Mockito.anyLong());

        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledPods(dc, avoids);
        assertAvoidIsEmpty(avoids, true, false, true, true);
        Assert.assertTrue(avoids.getPodsToAvoid().size() == 1);
        Assert.assertTrue(avoids.getPodsToAvoid().contains(expectedPodId));
    }

    @Test
    public void avoidDisabledClustersTestNoDisabledCluster() {
        DataCenter dc = prepareAvoidDisabledTests();
        Mockito.doReturn(new ArrayList<>()).when(_clusterDao).listDisabledClusters(Mockito.anyLong(), Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledClusters(dc, avoids);
        assertAvoidIsEmpty(avoids, true, true, true, true);
    }

    @Test
    public void avoidDisabledClustersTestHasDisabledCluster() {
        DataCenter dc = prepareAvoidDisabledTests();
        long expectedClusterId = 123l;
        List<Long> disabledClusters = new ArrayList<>();
        disabledClusters.add(expectedClusterId);
        Mockito.doReturn(disabledClusters).when(_clusterDao).listDisabledClusters(Mockito.anyLong(), Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledClusters(dc, avoids);

        assertAvoidIsEmpty(avoids, true, true, false, true);
        Assert.assertTrue(avoids.getClustersToAvoid().size() == 1);
        Assert.assertTrue(avoids.getClustersToAvoid().contains(expectedClusterId));
    }

    @Test
    public void volumesRequireEncryptionTest() {
        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        VolumeVO vol2 = new VolumeVO("vol2", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.DATADISK);
        VolumeVO vol3 = new VolumeVO("vol3", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.DATADISK);
        vol2.setPassphraseId(1L);

        List<VolumeVO> volumes = List.of(vol1, vol2, vol3);
        Assert.assertTrue("Volumes require encryption, but not reporting", _dpm.anyVolumeRequiresEncryption(volumes));
    }

    @Test
    public void volumesDoNotRequireEncryptionTest() {
        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        VolumeVO vol2 = new VolumeVO("vol2", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.DATADISK);
        VolumeVO vol3 = new VolumeVO("vol3", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.DATADISK);

        List<VolumeVO> volumes = List.of(vol1, vol2, vol3);
        Assert.assertFalse("Volumes do not require encryption, but reporting they do", _dpm.anyVolumeRequiresEncryption(volumes));
    }

    /**
     * Root requires encryption, chosen host supports it
     */
    @Test
    public void passEncRootProvidedHostSupportingEncryptionTest() {
        HostVO host = new HostVO("host");
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "true");
        }};
        host.setDetails(hostDetails);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        setupMocksForPlanDeploymentHostTests(host, vol1);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, hostId, null, null);
        DeployDestination dest = planOnProvidedHost(host, plan);
        Assert.assertNotNull(dest);
        Assert.assertEquals(dest.getHost(), host);
    }

    /**
     * Root requires encryption, chosen host does not support it
     */
    @Test
    public void failEncRootProvidedHostNotSupportingEncryptionTest() {
        HostVO host = new HostVO("host");
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "false");
        }};
        host.setDetails(hostDetails);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        setupMocksForPlanDeploymentHostTests(host, vol1);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, hostId, null, null);
        DeployDestination dest = planOnProvidedHost(host, plan);
        Assert.assertNull("Destination should be null since host doesn't support encryption and root requires it", dest);
    }

    /**
     * Plans on the host the plan names, with the host's GPU check passing; the check is reset afterwards because the
     * resource manager mock is shared by the tests.
     */
    private DeployDestination planOnProvidedHost(Host host, DataCenterDeployment plan) {
        Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.eq(host), ArgumentMatchers.anyLong())).thenReturn(true);
        try {
            return _dpm.planDeployment(vmProfile, plan, avoids, null);
        } catch (InsufficientServerCapacityException | AffinityConflictException e) {
            throw new AssertionError(e);
        } finally {
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    @Test
    public void specifiedHostWithoutUsableGroupRejected() throws Exception {
        HostVO host = new HostVO("gpu-host");
        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        setupMocksForPlanDeploymentHostTests(host, vol1);
        ServiceOfferingVO gpuOffering = Mockito.spy(new ServiceOfferingVO("a100-40g-2", 16, 1024, 2000, 1, 1, false, "vm", false, Type.User, false));
        Mockito.doReturn(5L).when(gpuOffering).getVgpuProfileId();
        Mockito.doReturn(2).when(gpuOffering).getGpuCount();
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(gpuOffering);
        Mockito.doReturn(new Pair<Host, Map<Volume, StoragePool>>(host, new HashMap<>())).when(_dpm).findPotentialDeploymentResources(ArgumentMatchers.anyList(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.any(ExcludeList.class), ArgumentMatchers.any(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, hostId, null, null);

        try {
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.eq(gpuOffering), ArgumentMatchers.eq(host), ArgumentMatchers.anyLong())).thenReturn(false);
            Assert.assertNull("an admin-specified host without a usable GPU group is refused", _dpm.planDeployment(vmProfile, plan, avoids, null));

            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.eq(gpuOffering), ArgumentMatchers.eq(host), ArgumentMatchers.anyLong())).thenReturn(true);
            DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
            Assert.assertNotNull(dest);
            Assert.assertSame(host, dest.getHost());
        } finally {
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    /**
     * Root does not require encryption, chosen host does not support it
     */
    @Test
    public void passNoEncRootProvidedHostNotSupportingEncryptionTest() {
        HostVO host = new HostVO("host");
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "false");
        }};
        host.setDetails(hostDetails);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);

        setupMocksForPlanDeploymentHostTests(host, vol1);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, hostId, null, null);
        DeployDestination dest = planOnProvidedHost(host, plan);
        Assert.assertNotNull(dest);
        Assert.assertEquals(dest.getHost(), host);
    }

    /**
     * Root does not require encryption, chosen host does support it
     */
    @Test
    public void passNoEncRootProvidedHostSupportingEncryptionTest() {
        HostVO host = new HostVO("host");
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "true");
        }};
        host.setDetails(hostDetails);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);

        setupMocksForPlanDeploymentHostTests(host, vol1);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, hostId, null, null);
        DeployDestination dest = planOnProvidedHost(host, plan);
        Assert.assertNotNull(dest);
        Assert.assertEquals(dest.getHost(), host);
    }

    /**
     * Root requires encryption, last host supports it
     */
    @Test
    public void passEncRootLastHostSupportingEncryptionTest() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "true");
        }};
        host.setDetails(hostDetails);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        setupMocksForLastHostTests(host, vol1);
        Mockito.doReturn(new Pair<Host, Map<Volume, StoragePool>>(host, new HashMap<>())).when(_dpm).findPotentialDeploymentResources(ArgumentMatchers.anyList(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.any(ExcludeList.class), ArgumentMatchers.any(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        // host id is null here so we pick up last host id
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);
        try {
            DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);

            Assert.assertSame(host, dest.getHost());
            verify(_dpm).lastHostHasCapacity(ArgumentMatchers.eq(host), ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                    ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat());
        } finally {
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    /**
     * Makes the given host the VM's last host, with enough capacity and GPU devices, and no other host suitable, so
     * that a destination can only be the last host.
     */
    private void setupMocksForLastHostTests(HostVO host, VolumeVO vol1) {
        setupMocksForPlanDeploymentHostTests(host, vol1);
        Mockito.when(vmProfile.getVirtualMachine().getLastHostId()).thenReturn(hostId);
        Mockito.doReturn(List.of()).when(_dpm).findSuitableHosts(ArgumentMatchers.any(VirtualMachineProfile.class),
                ArgumentMatchers.any(DeploymentPlan.class), ArgumentMatchers.any(ExcludeList.class), ArgumentMatchers.anyInt());
        Mockito.when(clusterDetailsDao.findDetail(clusterId, "cpuOvercommitRatio")).thenReturn(new ClusterDetailsVO(clusterId, "cpuOvercommitRatio", "1"));
        Mockito.when(clusterDetailsDao.findDetail(clusterId, "memoryOvercommitRatio")).thenReturn(new ClusterDetailsVO(clusterId, "memoryOvercommitRatio", "1"));
        Mockito.doReturn(true).when(_dpm).lastHostHasCapacity(ArgumentMatchers.any(Host.class), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat());
        Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.eq(host), ArgumentMatchers.anyLong())).thenReturn(true);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(vmProfile.getTemplate()).thenReturn(template);
        Mockito.when(avoids.shouldAvoid(ArgumentMatchers.any(DataCenter.class))).thenReturn(false);
        Mockito.when(avoids.shouldAvoid(ArgumentMatchers.any(Host.class))).thenReturn(false);
        Mockito.clearInvocations(hostDao);
    }

    /**
     * Root requires encryption, last host does not support it
     */
    @Test
    public void failEncRootLastHostNotSupportingEncryptionTest() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "false");
        }};
        host.setDetails(hostDetails);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        setupMocksForLastHostTests(host, vol1);

        // host id is null here so we pick up last host id
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);
        try {
            DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);

            Assert.assertNull("Destination should be null since last host doesn't support encryption and root requires it", dest);
            verify(hostDao, Mockito.atLeastOnce()).findById(hostId);
            verify(_dpm, Mockito.never()).lastHostHasCapacity(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyLong(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat());
        } finally {
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    @Test
    public void lastHostNeedsFreePoolCores() {
        HostVO lastHost = new HostVO("last");
        ServiceOfferingVO poolOffering = new ServiceOfferingVO("pool", 4, 1024, 2000, 1, 1, false, "vm", false, Type.User, false);
        ServiceOfferingVO gpuOffering = Mockito.spy(new ServiceOfferingVO("gpu", 12, 1024, 2000, 1, 1, false, "vm", false, Type.User, false));
        Mockito.doReturn(5L).when(gpuOffering).getVgpuProfileId();
        Mockito.when(capacityMgr.checkIfHostHasCpuCapability(lastHost, 4, 2000)).thenReturn(true);
        Mockito.when(capacityMgr.checkIfHostHasCapacity(ArgumentMatchers.eq(lastHost), ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyBoolean())).thenReturn(true);
        try {
            Mockito.when(capacityMgr.checkIfHostHasPoolCores(lastHost, 4)).thenReturn(false);
            assertFalse(_dpm.lastHostHasCapacity(lastHost, poolOffering, 8000, 1L << 30, 1f, 1f));
            assertTrue(_dpm.lastHostHasCapacity(lastHost, gpuOffering, 24000, 1L << 30, 1f, 1f));

            Mockito.when(capacityMgr.checkIfHostHasPoolCores(lastHost, 4)).thenReturn(true);
            assertTrue(_dpm.lastHostHasCapacity(lastHost, poolOffering, 8000, 1L << 30, 1f, 1f));
        } finally {
            Mockito.when(capacityMgr.checkIfHostHasPoolCores(ArgumentMatchers.any(Host.class), ArgumentMatchers.anyInt())).thenReturn(true);
        }
    }

    @Test
    public void passEncRootPlannerHostSupportingEncryptionTest() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "true");
        }};
        host.setDetails(hostDetails);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        DeploymentClusterPlanner planner = setupMocksForPlanDeploymentHostTests(host, vol1);

        // host id is null here so we pick up last host id
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);

        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, planner);
        Assert.assertNotNull(dest);
        Assert.assertEquals(host, dest.getHost());
    }

    @Test
    public void failEncRootPlannerHostSupportingEncryptionTest() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Map<String, String> hostDetails = new HashMap<>() {{
            put(Host.HOST_VOLUME_ENCRYPTION, "false");
        }};
        host.setDetails(hostDetails);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);

        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        vol1.setPassphraseId(1L);

        DeploymentClusterPlanner planner = setupMocksForPlanDeploymentHostTests(host, vol1);

        // host id is null here so we pick up last host id
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);

        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, planner);
        Assert.assertNull("Destination should be null since last host doesn't support encryption and root requires it", dest);
    }

    @Test
    public void findSuitablePoolsForVolumesTest() throws Exception {
        Long diskOfferingId = 1L;
        HostVO host = Mockito.spy(new HostVO("host"));
        Map<String, String> hostDetails = new HashMap<>() {
            {
                put(Host.HOST_VOLUME_ENCRYPTION, "true");
            }
        };
        host.setDetails(hostDetails);
        Mockito.when(host.getStatus()).thenReturn(Status.Up);

        VolumeVO vol1 = Mockito.spy(new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path",
                Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT));
        Mockito.when(vol1.getId()).thenReturn(1L);
        vol1.setState(Volume.State.Allocated);
        vol1.setPassphraseId(1L);
        vol1.setPoolId(1L);
        vol1.setDiskOfferingId(diskOfferingId);

        StoragePoolVO storagePool = new StoragePoolVO();
        storagePool.setStatus(StoragePoolStatus.Maintenance);
        storagePool.setId(vol1.getPoolId());
        storagePool.setDataCenterId(dataCenterId);
        storagePool.setPodId(podId);
        storagePool.setClusterId(clusterId);

        DiskProfile diskProfile = Mockito.mock(DiskProfile.class);

        StoragePoolAllocator allocator = Mockito.mock(StoragePoolAllocator.class);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);

        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getId()).thenReturn(1L);
        Mockito.when(vmProfile.getOwner()).thenReturn(account);
        Mockito.when(_accountMgr.isRootAdmin(account.getId())).thenReturn(Boolean.FALSE);

        Mockito.when(_dcDao.findById(dataCenterId)).thenReturn(dc);
        Mockito.when(dc.getAllocationState()).thenReturn(AllocationState.Enabled);

        HostPodVO podVo = Mockito.mock(HostPodVO.class);
        Mockito.when(podVo.getAllocationState()).thenReturn(AllocationState.Enabled);
        Mockito.doReturn(podVo).when(_podDao).findById(podId);

        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        Mockito.when(cluster.getAllocationState()).thenReturn(AllocationState.Enabled);
        Mockito.when(_clusterDao.findById(clusterId)).thenReturn(cluster);

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);

        Mockito.when(_diskOfferingDao.findById(vol1.getDiskOfferingId())).thenReturn(diskOffering);
        VirtualMachineTemplate vmt = Mockito.mock(VirtualMachineTemplate.class);

        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(serviceOffering);

        PrimaryDataStore primaryDataStore = Mockito.mock(PrimaryDataStore.class);

        Mockito.when(vmt.getFormat()).thenReturn(Storage.ImageFormat.ISO);
        Mockito.when(vmProfile.getTemplate()).thenReturn(vmt);

        Mockito.when(vmProfile.getId()).thenReturn(1L);
        Mockito.when(vmProfile.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(volDao.findUsableVolumesForInstance(1L)).thenReturn(Arrays.asList(vol1));
        Mockito.when(volDao.findByInstanceAndType(1L, Volume.Type.ROOT)).thenReturn(Arrays.asList(vol1));
        Mockito.when(_dataStoreManager.getPrimaryDataStore(vol1.getPoolId())).thenReturn((DataStore) primaryDataStore);
        Mockito.when(avoids.shouldAvoid(storagePool)).thenReturn(Boolean.FALSE);

        Mockito.doReturn(Arrays.asList(storagePool)).when(allocator).allocateToPool(diskProfile, vmProfile, plan,
                avoids, 10);
        Mockito.when(volDao.update(vol1.getId(), vol1)).thenReturn(true);
        _dpm.findSuitablePoolsForVolumes(vmProfile, plan, avoids, 10);
        verify(vol1, times(1)).setPoolId(null);
        assertTrue(vol1.getPoolId() == null);

    }

    // This is so ugly but everything is so intertwined...
    private DeploymentClusterPlanner setupMocksForPlanDeploymentHostTests(HostVO host, VolumeVO vol1) {
        long diskOfferingId = 345L;
        List<VolumeVO> volumeVOs = new ArrayList<>();
        List<Volume> volumes = new ArrayList<>();
        vol1.setDiskOfferingId(diskOfferingId);
        volumes.add(vol1);
        volumeVOs.add(vol1);

        DiskOfferingVO diskOffering = new DiskOfferingVO();
        diskOffering.setEncrypt(true);

        VMTemplateVO template = new VMTemplateVO();
        template.setFormat(Storage.ImageFormat.QCOW2);

        host.setClusterId(clusterId);

        StoragePool pool = new StoragePoolVO();

        Map<Volume, List<StoragePool>> suitableVolumeStoragePools = new HashMap<>() {{
            put(vol1, List.of(pool));
        }};

        Pair<Map<Volume, List<StoragePool>>, List<Volume>> suitable = new Pair<>(suitableVolumeStoragePools, volumes);

        ServiceOfferingVO svcOffering = new ServiceOfferingVO("test", 1, 256, 1, 1, 1, false, "vm", false, Type.User, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);
        Mockito.when(vmProfile.getHypervisorType()).thenReturn(HypervisorType.KVM);
        VMTemplateVO vmTemplate = Mockito.mock(VMTemplateVO.class);
        Mockito.when(vmTemplate.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(vmProfile.getTemplate()).thenReturn(vmTemplate);
        Mockito.when(hostDao.findById(hostId)).thenReturn(host);
        Mockito.doNothing().when(hostDao).loadDetails(host);
        Mockito.doReturn(volumeVOs).when(volDao).findByInstance(ArgumentMatchers.anyLong());
        Mockito.doReturn(suitable).when(_dpm).findSuitablePoolsForVolumes(
                ArgumentMatchers.any(VirtualMachineProfile.class),
                ArgumentMatchers.any(DataCenterDeployment.class),
                ArgumentMatchers.any(ExcludeList.class),
                ArgumentMatchers.anyInt()
        );

        ClusterVO clusterVO = new ClusterVO();
        clusterVO.setHypervisorType(HypervisorType.KVM.toString());
        Mockito.when(_clusterDao.findById(ArgumentMatchers.anyLong())).thenReturn(clusterVO);

        Mockito.doReturn(List.of(host)).when(_dpm).findSuitableHosts(
                ArgumentMatchers.any(VirtualMachineProfile.class),
                ArgumentMatchers.any(DeploymentPlan.class),
                ArgumentMatchers.any(ExcludeList.class),
                ArgumentMatchers.anyInt()
        );

        Map<Volume, StoragePool> suitableVolumeStoragePoolMap = new HashMap<>() {{
            put(vol1, pool);
        }};
        Mockito.doReturn(true).when(_dpm).hostCanAccessSPool(ArgumentMatchers.any(Host.class), ArgumentMatchers.any(StoragePool.class));

        Pair<Host, Map<Volume, StoragePool>> potentialResources = new Pair<>(host, suitableVolumeStoragePoolMap);

        Mockito.when(capacityMgr.checkIfHostReachMaxGuestLimit(host)).thenReturn(false);
        Mockito.when(capacityMgr.checkIfHostHasCpuCapability(ArgumentMatchers.any(Host.class), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())).thenReturn(true);
        Mockito.when(capacityMgr.checkIfHostHasPoolCores(ArgumentMatchers.any(Host.class), ArgumentMatchers.anyInt())).thenReturn(true);
        Mockito.when(capacityMgr.checkIfHostHasCapacity(
            ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.anyFloat(),
                ArgumentMatchers.anyFloat(),
                ArgumentMatchers.anyBoolean()
        )).thenReturn(true);
        Mockito.when(serviceOfferingDetailsDao.findDetail(vmProfile.getServiceOfferingId(), GPU.Keys.vgpuType.toString())).thenReturn(null);

        Mockito.doReturn(true).when(_dpm).checkVmProfileAndHost(vmProfile, host);
        Mockito.doReturn(true).when(_dpm).checkIfHostFitsPlannerUsage(ArgumentMatchers.any(Host.class), ArgumentMatchers.nullable(PlannerResourceUsage.class));
        Mockito.when(clusterDetailsDao.findDetail(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString())).thenReturn(new ClusterDetailsVO(clusterId, "mock", "1"));

        DeploymentClusterPlanner planner = Mockito.spy(new FirstFitPlanner());
        try {
            Mockito.doReturn(List.of(clusterId), List.of()).when(planner).orderClusters(
                    ArgumentMatchers.any(VirtualMachineProfile.class),
                    ArgumentMatchers.any(DeploymentPlan.class),
                    ArgumentMatchers.any(ExcludeList.class)
            );
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return planner;
    }

    private DataCenter prepareAvoidDisabledTests() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        Mockito.when(dc.getId()).thenReturn(123l);
        List<Long> podIds = new ArrayList<>();
        podIds.add(1l);
        Mockito.doReturn(podIds).when(hostPodDao).listAllPods(Mockito.anyLong());
        return dc;
    }

    private void assertAvoidIsEmpty(ExcludeList avoids, boolean isDcEmpty, boolean isPodsEmpty, boolean isClustersEmpty,
            boolean isHostsEmpty) {
        Assert.assertEquals(isDcEmpty, CollectionUtils.isEmpty(avoids.getDataCentersToAvoid()));
        Assert.assertEquals(isPodsEmpty, CollectionUtils.isEmpty(avoids.getPodsToAvoid()));
        Assert.assertEquals(isClustersEmpty, CollectionUtils.isEmpty(avoids.getClustersToAvoid()));
        Assert.assertEquals(isHostsEmpty, CollectionUtils.isEmpty(avoids.getHostsToAvoid()));
    }

    @Configuration
    @ComponentScan(basePackageClasses = {DeploymentPlanningManagerImpl.class},
                   includeFilters = {@Filter(value = TestConfiguration.Library.class,
                                             type = FilterType.CUSTOM)}, useDefaultFilters = false)
    public static class TestConfiguration extends SpringUtils.CloudStackTestConfiguration {

        @Bean
        public FirstFitPlanner firstFitPlanner() {
            return Mockito.mock(FirstFitPlanner.class);
        }

        @Bean
        public DeploymentPlanner deploymentPlanner() {
            return Mockito.mock(DeploymentPlanner.class);
        }

        @Bean
        public DataCenterVO dataCenter() {
            return Mockito.mock(DataCenterVO.class);
        }

        @Bean
        public ExcludeList excludeList() {
            return Mockito.mock(ExcludeList.class);
        }

        @Bean
        public VirtualMachineProfileImpl virtualMachineProfileImpl() {
            return Mockito.mock(VirtualMachineProfileImpl.class);
        }

        @Bean
        public HostTagsDao hostTagsDao() {
            return Mockito.mock(HostTagsDao.class);
        }

        @Bean
        public HostDetailsDao hostDetailsDao() {
            return Mockito.mock(HostDetailsDao.class);
        }


        @Bean
        public ClusterDetailsDao clusterDetailsDao() {
            return Mockito.mock(ClusterDetailsDao.class);
        }

        @Bean
        public ResourceManager resourceManager() {
            return Mockito.mock(ResourceManager.class);
        }

        @Bean
        public ServiceOfferingDetailsDao serviceOfferingDetailsDao() {
            return Mockito.mock(ServiceOfferingDetailsDao.class);
        }

        @Bean
        public AffinityGroupDomainMapDao affinityGroupDomainMapDao() {
            return Mockito.mock(AffinityGroupDomainMapDao.class);
        }

        @Bean
        public DataStoreManager cataStoreManager() {
            return Mockito.mock(DataStoreManager.class);
        }

        @Bean
        public StorageManager storageManager() {
            return Mockito.mock(StorageManager.class);
        }

        @Bean
        public HostDao hostDao() {
            return Mockito.mock(HostDao.class);
        }

        @Bean
        public HostPodDao hostPodDao() {
            return Mockito.mock(HostPodDao.class);
        }

        @Bean
        public ClusterDao clusterDao() {
            return Mockito.mock(ClusterDao.class);
        }

        @Bean
        public DedicatedResourceDao dedicatedResourceDao() {
            return Mockito.mock(DedicatedResourceDao.class);
        }

        @Bean
        public GuestOSDao guestOSDao() {
            return Mockito.mock(GuestOSDao.class);
        }

        @Bean
        public GuestOSCategoryDao guestOSCategoryDao() {
            return Mockito.mock(GuestOSCategoryDao.class);
        }

        @Bean
        public CapacityManager capacityManager() {
            return Mockito.mock(CapacityManager.class);
        }

        @Bean
        public StoragePoolHostDao storagePoolHostDao() {
            return Mockito.mock(StoragePoolHostDao.class);
        }

        @Bean
        public VolumeDao volumeDao() {
            return Mockito.mock(VolumeDao.class);
        }

        @Bean
        public ConfigurationDao configurationDao() {
            return Mockito.mock(ConfigurationDao.class);
        }

        @Bean
        public DiskOfferingDao diskOfferingDao() {
            return Mockito.mock(DiskOfferingDao.class);
        }

        @Bean
        public PrimaryDataStoreDao primaryDataStoreDao() {
            return Mockito.mock(PrimaryDataStoreDao.class);
        }

        @Bean
        public CapacityDao capacityDao() {
            return Mockito.mock(CapacityDao.class);
        }

        @Bean
        public PlannerHostReservationDao plannerHostReservationDao() {
            return Mockito.mock(PlannerHostReservationDao.class);
        }

        @Bean
        public AffinityGroupProcessor affinityGroupProcessor() {
            return Mockito.mock(AffinityGroupProcessor.class);
        }

        @Bean
        public AffinityGroupDao affinityGroupDao() {
            return Mockito.mock(AffinityGroupDao.class);
        }

        @Bean
        public AffinityGroupVMMapDao affinityGroupVMMapDao() {
            return Mockito.mock(AffinityGroupVMMapDao.class);
        }

        @Bean
        public AccountManager accountManager() {
            return Mockito.mock(AccountManager.class);
        }

        @Bean
        public AgentManager agentManager() {
            return Mockito.mock(AgentManager.class);
        }

        @Bean
        public MessageBus messageBus() {
            return Mockito.mock(MessageBus.class);
        }

        @Bean
        public UserVmDao userVMDao() {
            return Mockito.mock(UserVmDao.class);
        }

        @Bean
        public VMInstanceDetailsDao vmInstanceDetailsDao() {
            return Mockito.mock(VMInstanceDetailsDao.class);
        }

        @Bean
        public VMInstanceDao vmInstanceDao() {
            return Mockito.mock(VMInstanceDao.class);
        }

        @Bean
        public DataCenterDao dataCenterDao() {
            return Mockito.mock(DataCenterDao.class);
        }

        @Bean
        public VMReservationDao reservationDao() {
            return Mockito.mock(VMReservationDao.class);
        }

        @Bean
        public AffinityGroupService affinityGroupService() {
            return Mockito.mock(AffinityGroupService.class);
        }

        @Bean
        public HostGpuGroupsDao hostGpuGroupsDao() {
            return Mockito.mock(HostGpuGroupsDao.class);
        }

        @Bean
        public AccountDao accountDao() {
            return Mockito.mock(AccountDao.class);
        }

        @Bean
        public VMTemplateDao vmTemplateDao() {
            return Mockito.mock(VMTemplateDao.class);
        }

        @Bean
        public VgpuProfileDao vgpuProfileDao() {
            return Mockito.mock(VgpuProfileDao.class);
        }

        public static class Library implements TypeFilter {

            @Override
            public boolean match(MetadataReader mdr, MetadataReaderFactory arg1) throws IOException {
                ComponentScan cs = TestConfiguration.class.getAnnotation(ComponentScan.class);
                return SpringUtils.includedInBasePackageClasses(mdr.getClassMetadata().getClassName(), cs);
            }
        }
    }

    @Test
    public void testReorderHostsByPriority() {
        Map<Long, Integer> priorities = new LinkedHashMap<>();
        priorities.put(1L, 3);
        priorities.put(2L, -6);
        priorities.put(3L, 5);
        priorities.put(5L, 8);
        priorities.put(6L, -1);
        priorities.put(8L, 5);
        priorities.put(9L, DataCenterDeployment.PROHIBITED_HOST_PRIORITY);

        Host host1 = Mockito.mock(Host.class);
        Mockito.when(host1.getId()).thenReturn(1L);
        Host host2 = Mockito.mock(Host.class);
        Mockito.when(host2.getId()).thenReturn(2L);
        Host host3 = Mockito.mock(Host.class);
        Mockito.when(host3.getId()).thenReturn(3L);
        Host host4 = Mockito.mock(Host.class);
        Mockito.when(host4.getId()).thenReturn(4L);
        Host host5 = Mockito.mock(Host.class);
        Mockito.when(host5.getId()).thenReturn(5L);
        Host host6 = Mockito.mock(Host.class);
        Mockito.when(host6.getId()).thenReturn(6L);
        Host host7 = Mockito.mock(Host.class);
        Mockito.when(host7.getId()).thenReturn(7L);
        Host host8 = Mockito.mock(Host.class);
        Mockito.when(host8.getId()).thenReturn(8L);
        Host host9 = Mockito.mock(Host.class);
        Mockito.when(host9.getId()).thenReturn(9L);

        List<Host> hosts = new ArrayList<>(Arrays.asList(host1, host2, host3, host4, host5, host6, host7, host8, host9));
        _dpm.reorderHostsByPriority(priorities, hosts);

        Assert.assertEquals(8, hosts.size());

        Assert.assertEquals(5, hosts.get(0).getId());
        Assert.assertEquals(3, hosts.get(1).getId());
        Assert.assertEquals(8, hosts.get(2).getId());
        Assert.assertEquals(1, hosts.get(3).getId());
        Assert.assertEquals(4, hosts.get(4).getId());
        Assert.assertEquals(7, hosts.get(5).getId());
        Assert.assertEquals(6, hosts.get(6).getId());
        Assert.assertEquals(2, hosts.get(7).getId());
    }

    private List<Long> prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(boolean configValue,
            boolean mockVolumes, boolean mockClusterStoreVolume) {
        try {
            Field f = ConfigKey.class.getDeclaredField("_defaultValue");
            f.setAccessible(true);
            f.set(ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS, String.valueOf(configValue));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        List<Long> allClusters = List.of(101L, 102L, 103L, 104L);
        Mockito.when(_clusterDao.listAllClusterIds(Mockito.anyLong())).thenReturn(allClusters);
        if (mockVolumes) {
            VolumeVO vol1 = Mockito.mock(VolumeVO.class);
            Mockito.when(vol1.getPoolId()).thenReturn(1L);
            VolumeVO vol2 = Mockito.mock(VolumeVO.class);
            Mockito.when(vol2.getPoolId()).thenReturn(2L);
            StoragePoolVO pool1 = Mockito.mock(StoragePoolVO.class);
            Mockito.when(pool1.getScope()).thenReturn(ScopeType.ZONE);
            Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(pool1);
            StoragePoolVO pool2 = Mockito.mock(StoragePoolVO.class);
            Mockito.when(pool2.getScope()).thenReturn(mockClusterStoreVolume ? ScopeType.CLUSTER : ScopeType.GLOBAL);
            Mockito.when(primaryDataStoreDao.findById(2L)).thenReturn(pool2);
            Mockito.when(volDao.findUsableVolumesForInstance(1L)).thenReturn(List.of(vol1, vol2));
        } else {
            Mockito.when(volDao.findUsableVolumesForInstance(1L)).thenReturn(new ArrayList<>());
        }
        return allClusters;
    }

    @Test
    public void avoidOtherClustersForDeploymentIfMigrationDisabledNonValidHost() {
        prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(false, false, false);
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        ExcludeList excludeList = new ExcludeList();
        _dpm.avoidOtherClustersForDeploymentIfMigrationDisabled(vm, null, excludeList);
        Assert.assertTrue(CollectionUtils.isEmpty(excludeList.getClustersToAvoid()));

        Host lastHost = Mockito.mock(Host.class);
        Mockito.when(lastHost.getClusterId()).thenReturn(null);
        _dpm.avoidOtherClustersForDeploymentIfMigrationDisabled(vm, lastHost, excludeList);
        Assert.assertTrue(CollectionUtils.isEmpty(excludeList.getClustersToAvoid()));
    }

    private Set<Long> runAvoidOtherClustersForDeploymentIfMigrationDisabledTest() {
        VirtualMachine vm = Mockito.mock(VirtualMachine.class);
        Mockito.when(vm.getId()).thenReturn(1L);
        ExcludeList excludeList = new ExcludeList();
        Host lastHost = Mockito.mock(Host.class);
        Long sourceClusterId = 101L;
        Mockito.when(lastHost.getClusterId()).thenReturn(sourceClusterId);
        _dpm.avoidOtherClustersForDeploymentIfMigrationDisabled(vm, lastHost, excludeList);
        return excludeList.getClustersToAvoid();
    }

    @Test
    public void avoidOtherClustersForDeploymentIfMigrationDisabledConfigAllows() {
        prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(true, false, false);
        Assert.assertTrue(CollectionUtils.isEmpty(runAvoidOtherClustersForDeploymentIfMigrationDisabledTest()));
    }

    @Test
    public void avoidOtherClustersForDeploymentIfMigrationDisabledNoVmVolumes() {
        prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(false, false, false);
        Assert.assertTrue(CollectionUtils.isEmpty(runAvoidOtherClustersForDeploymentIfMigrationDisabledTest()));
    }

    @Test
    public void avoidOtherClustersForDeploymentIfMigrationDisabledVmVolumesNonValidScope() {
        prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(false, true, false);
        Assert.assertTrue(CollectionUtils.isEmpty(runAvoidOtherClustersForDeploymentIfMigrationDisabledTest()));
    }

    @Test
    public void avoidOtherClustersForDeploymentIfMigrationDisabledValid() {
        List<Long> allClusters = prepareMockForAvoidOtherClustersForDeploymentIfMigrationDisabled(false, true, true);
        Set<Long> avoidedClusters = runAvoidOtherClustersForDeploymentIfMigrationDisabledTest();
        Assert.assertTrue(CollectionUtils.isNotEmpty(avoidedClusters));
        Assert.assertEquals(allClusters.size() - 1, avoidedClusters.size());
        Assert.assertFalse(avoidedClusters.contains(allClusters.get(0)));
    }

    @Test
    public void listHostsAdmittingVmIsTheHostAllocatorsAnswer() throws Exception {
        VirtualMachineProfile profile = Mockito.mock(VirtualMachineProfile.class);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getType()).thenReturn(Type.User);
        Mockito.when(vm.getId()).thenReturn(77L);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        Mockito.when(profile.getVirtualMachine()).thenReturn(vm);
        Mockito.when(profile.getServiceOffering()).thenReturn(new ServiceOfferingVO("test", 1, 256, 1, 1, 1, false, "vm", false, Type.User, false));
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(profile.getTemplate()).thenReturn(template);
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        Mockito.doNothing().when(_dpm).checkForNonDedicatedResources(Mockito.eq(profile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
        Mockito.doNothing().when(_dpm).avoidDisabledResources(Mockito.eq(profile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
        Host first = Mockito.mock(Host.class);
        Host second = Mockito.mock(Host.class);
        HostAllocator allocator = Mockito.mock(HostAllocator.class);
        Mockito.when(allocator.allocateTo(Mockito.eq(profile), Mockito.eq(plan), Mockito.eq(Host.Type.Routing), Mockito.any(ExcludeList.class),
                Mockito.eq(HostAllocator.RETURN_UPTO_ALL))).thenReturn(new ArrayList<>(Arrays.asList(second, first)));
        List<HostAllocator> previous = _dpm.getHostAllocators();
        _dpm.setHostAllocators(Arrays.asList(allocator));
        try {
            List<Host> admitting = _dpm.listHostsAdmittingVm(profile, plan);

            Assert.assertEquals("the hosts and order of the planner's host allocators", Arrays.asList(second, first), admitting);
            Mockito.verify(_dpm).checkForNonDedicatedResources(Mockito.eq(profile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
            Mockito.verify(_dpm).avoidDisabledResources(Mockito.eq(profile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
            Mockito.verify(_plannerHostReserveDao, Mockito.never()).persist(Mockito.any(PlannerHostReservationVO.class));
        } finally {
            _dpm.setHostAllocators(previous);
        }
    }

    /**
     * Planning takes a VM's last host first, from capacity reserved for the VM there or from free capacity; the hosts
     * admitting the VM are the same answer, for each combination of the two.
     */
    @Test
    public void lastHostAdmissionAgreesWithPlanning() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        setupMocksForLastHostTests(host, vol1);
        Mockito.doCallRealMethod().when(_dpm).lastHostHasCapacity(ArgumentMatchers.any(Host.class), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat());
        Mockito.doReturn(new Pair<Host, Map<Volume, StoragePool>>(host, new HashMap<>())).when(_dpm).findPotentialDeploymentResources(ArgumentMatchers.anyList(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.any(ExcludeList.class), ArgumentMatchers.any(), ArgumentMatchers.anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.doNothing().when(_dpm).avoidDisabledResources(Mockito.eq(vmProfile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        try {
            for (boolean fromReserved : new boolean[] {true, false}) {
                for (boolean fromFree : new boolean[] {true, false}) {
                    Mockito.when(capacityMgr.checkIfHostHasCapacity(ArgumentMatchers.eq(host), ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                            ArgumentMatchers.eq(true), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyBoolean())).thenReturn(fromReserved);
                    Mockito.when(capacityMgr.checkIfHostHasCapacity(ArgumentMatchers.eq(host), ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                            ArgumentMatchers.eq(false), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyBoolean())).thenReturn(fromFree);

                    DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
                    List<Host> admitting = _dpm.listHostsAdmittingVm(vmProfile, plan);

                    String combination = String.format("reserved capacity fits: %s, free capacity fits: %s", fromReserved, fromFree);
                    Assert.assertEquals(combination, fromReserved || fromFree, dest != null && dest.getHost() == host);
                    Assert.assertEquals(combination, dest != null && dest.getHost() == host, admitting.contains(host));
                }
            }
        } finally {
            Mockito.when(capacityMgr.checkIfHostHasCapacity(ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.anyFloat(), ArgumentMatchers.anyFloat(), ArgumentMatchers.anyBoolean())).thenReturn(true);
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    @Test
    public void aLastHostThatIsGoneIsSkipped() throws Exception {
        HostVO host = Mockito.spy(new HostVO("host"));
        Mockito.when(host.getStatus()).thenReturn(Status.Up);
        VolumeVO vol1 = new VolumeVO("vol1", dataCenterId, podId, 1L, 1L, instanceId, "folder", "path", Storage.ProvisioningType.THIN, (long) 10 << 30, Volume.Type.ROOT);
        setupMocksForLastHostTests(host, vol1);
        Mockito.doNothing().when(_dpm).avoidDisabledResources(Mockito.eq(vmProfile), Mockito.eq(dc), Mockito.any(ExcludeList.class));
        Mockito.when(vmProfile.getVirtualMachine().getLastHostId()).thenReturn(77L);
        Mockito.when(hostDao.findById(77L)).thenReturn(null);
        // as the host DAO does when there is no host to load the details of
        Mockito.doThrow(new NullPointerException("no host")).when(hostDao).loadDetails(ArgumentMatchers.isNull());
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        try {
            Assert.assertNull(_dpm.planDeployment(vmProfile, plan, avoids, null));
            Assert.assertTrue(_dpm.listHostsAdmittingVm(vmProfile, plan).isEmpty());
        } finally {
            Mockito.doNothing().when(hostDao).loadDetails(ArgumentMatchers.isNull());
            Mockito.when(resourceManager.isGPUDeviceAvailable(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyLong())).thenReturn(false);
        }
    }

    @Test
    public void listPoolsForDiskIsTheStorageAllocatorsAnswer() {
        StoragePoolAllocator none = Mockito.mock(StoragePoolAllocator.class);
        StoragePoolAllocator local = Mockito.mock(StoragePoolAllocator.class);
        StoragePool pool = Mockito.mock(StoragePool.class);
        DiskProfile disk = Mockito.mock(DiskProfile.class);
        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId, podId, clusterId, 5L, null, null);
        Mockito.when(local.allocateToPool(Mockito.eq(disk), Mockito.eq(vmProfile), Mockito.eq(plan), Mockito.any(ExcludeList.class),
                Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL))).thenReturn(Arrays.asList(pool));
        _dpm.setStoragePoolAllocators(Arrays.asList(none, local));

        Assert.assertEquals(Arrays.asList(pool), _dpm.listPoolsForDisk(disk, vmProfile, plan));
        Mockito.verify(none).allocateToPool(Mockito.eq(disk), Mockito.eq(vmProfile), Mockito.eq(plan), Mockito.any(ExcludeList.class),
                Mockito.eq(StoragePoolAllocator.RETURN_UPTO_ALL));
    }

    @Test
    public void allowVolumeMoveLetsPlanningPlaceLocalVolumesElsewhere() {
        VirtualMachineProfile profile = Mockito.mock(VirtualMachineProfile.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volume.getPoolId()).thenReturn(11L);
        DataStore store = Mockito.mock(DataStore.class);
        Scope scope = Mockito.mock(Scope.class);
        Mockito.when(scope.getScopeType()).thenReturn(ScopeType.HOST);
        Mockito.when(store.getScope()).thenReturn(scope);
        Mockito.when(_dataStoreManager.getPrimaryDataStore(11L)).thenReturn(store);

        Assert.assertFalse("a ready local volume ties planning to its host", _dpm.isLocalVolumeFreeToMove(profile, volume));
        Mockito.when(profile.getParameter(VirtualMachineProfile.Param.AllowVolumeMove)).thenReturn(Boolean.TRUE);
        Assert.assertTrue(_dpm.isLocalVolumeFreeToMove(profile, volume));
        Mockito.when(scope.getScopeType()).thenReturn(ScopeType.CLUSTER);
        Assert.assertFalse("a volume on shared storage is reused as before", _dpm.isLocalVolumeFreeToMove(profile, volume));
    }
}
