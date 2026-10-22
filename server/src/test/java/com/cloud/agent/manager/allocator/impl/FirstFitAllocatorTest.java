/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloud.agent.manager.allocator.impl;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.gpu.GpuService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public class FirstFitAllocatorTest {
  private static final double TOLERANCE = 0.0001;

  @Mock
  HostDao hostDaoMock;

  @Mock
  ResourceManager resourceManagerMock;

  @Mock
  VMInstanceDetailsDao userVmDetailsDaoMock;

  @Mock
  ServiceOfferingDetailsDao serviceOfferingDetailsDao;

  @Mock
  CapacityManager capacityMgr;

  @Mock
  ConfigurationDao configDao;

  @Mock
  CapacityDao capacityDao;

  @Mock
  GpuService gpuService;

  @Spy
  @InjectMocks
  FirstFitAllocator firstFitAllocatorSpy;

  private final Host.Type type = Host.Type.Routing;

  private final Long clusterId = 1L;

  private final Long podId = 2L;

  private final Long dcId = 3L;

  private final List<HostVO> emptyList = new ArrayList<>();

  private final String hostTag = "hostTag";

  private final String templateTag = "templateTag";

  private final HostVO host1 = mock(HostVO.class);

  private final HostVO host2 = mock(HostVO.class);

  private final HostVO host3 = mock(HostVO.class);

  private final ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);

  private final DeploymentPlanner.ExcludeList excludeList = mock(DeploymentPlanner.ExcludeList.class);

  private final VirtualMachineProfile virtualMachineProfile = mock(VirtualMachineProfile.class);

  private final VMTemplateVO vmTemplateVO = mock(VMTemplateVO.class);

  private final Account account = mock(Account.class);

  private final DeploymentPlan deploymentPlan = mock(DeploymentPlan.class);

  private final DeploymentPlanner.ExcludeList avoid = mock(DeploymentPlanner.ExcludeList.class);

  private final ServiceOffering offering = mock(ServiceOffering.class);

  private final boolean considerReservedCapacity = true;

  @Test
  public void testConfigure() throws Exception {
    when(configDao.getConfiguration(Mockito.anyMap())).thenReturn(new HashMap<>());
    Assert.assertTrue(firstFitAllocatorSpy._checkHvm);
    Assert.assertTrue(firstFitAllocatorSpy.configure("test", new HashMap<>()));
  }

  @Test
  public void testAllocateTo_SuccessfulMatch() {
    List<Host> inputHosts = Arrays.asList(host1, host2);

    // All hosts are allowed
    when(avoid.shouldAvoid(host1)).thenReturn(false);
    when(avoid.shouldAvoid(host2)).thenReturn(false);

    // CPU capability and capacity is met
    when(capacityMgr.checkIfHostReachMaxGuestLimit(Mockito.any(Host.class))).thenReturn(false);
    when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(host1, offering, true))
        .thenReturn(new Pair<>(true, true));
    when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(host2, offering,true))
        .thenReturn(new Pair<>(true, false));

    when(resourceManagerMock.isGPUDeviceAvailable(offering, host1, virtualMachineProfile.getId())).thenReturn(true);
    when(resourceManagerMock.isGPUDeviceAvailable(offering, host2, virtualMachineProfile.getId())).thenReturn(true);

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, offering, null, avoid, inputHosts, 2, true, account);

    // Only host1 should be returned
    Assert.assertEquals(1, result.size());
    Assert.assertTrue(result.contains(host1));
    Assert.assertFalse(result.contains(host2));
  }

  @Test
  public void testAllocateTo_AvoidSetAndGuestLimit() {
    List<Host> inputHosts = Arrays.asList(host1, host2);

    when(avoid.shouldAvoid(host1)).thenReturn(true); // Avoided
    when(avoid.shouldAvoid(host2)).thenReturn(false);

    when(capacityMgr.checkIfHostReachMaxGuestLimit(host2)).thenReturn(true); // Reached limit

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, offering, null, avoid, inputHosts, 2, true, account);

    Assert.assertTrue(result.isEmpty());
  }

  @Test
  public void testAllocateTo_GPUNotAvailable() {
    List<Host> inputHosts = Arrays.asList(host1);
    when(avoid.shouldAvoid(host1)).thenReturn(false);
    when(resourceManagerMock.isGPUDeviceAvailable(offering, host1, virtualMachineProfile.getId())).thenReturn(false);

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, offering, null, avoid, inputHosts, 1, true, account);

    Assert.assertTrue(result.isEmpty());
  }

  @Test
  public void allocateToTestHostTypeStorageShouldReturnNull() {
    List<Host> suitableHosts = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, Host.Type.Storage, excludeList, null, HostAllocator.RETURN_UPTO_ALL, considerReservedCapacity);

    Assert.assertNull(suitableHosts);
  }

  @Test
  public void allocateToTestSuitableHostsEmptyShouldReturnNull() {
    Mockito.doReturn(serviceOffering).when(virtualMachineProfile).getServiceOffering();
    Mockito.doReturn(vmTemplateVO).when(virtualMachineProfile).getTemplate();
    Mockito.doReturn(account).when(virtualMachineProfile).getOwner();
    Mockito.doReturn(hostTag).when(serviceOffering).getHostTag();
    Mockito.doReturn(templateTag).when(vmTemplateVO).getTemplateTag();
    Mockito.doReturn(emptyList).when(firstFitAllocatorSpy).retrieveHosts(Mockito.any(VirtualMachineProfile.class), Mockito.any(Host.Type.class), Mockito.nullable(List.class), Mockito.any(VMTemplateVO.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    List<Host> suitableHosts = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, type, excludeList, null, HostAllocator.RETURN_UPTO_ALL, considerReservedCapacity);

    Assert.assertNull(suitableHosts);
  }

  @Test
  public void allocateToTestSuitableHostsNotEmptyShouldCallAllocateToMethod() {
    List<HostVO> hosts = new ArrayList<>(Arrays.asList(host1, host2));

    Mockito.doReturn(serviceOffering).when(virtualMachineProfile).getServiceOffering();
    Mockito.doReturn(vmTemplateVO).when(virtualMachineProfile).getTemplate();
    Mockito.doReturn(account).when(virtualMachineProfile).getOwner();
    Mockito.doReturn(hostTag).when(serviceOffering).getHostTag();
    Mockito.doReturn(templateTag).when(vmTemplateVO).getTemplateTag();
    Mockito.doReturn(hosts).when(firstFitAllocatorSpy).retrieveHosts(Mockito.any(VirtualMachineProfile.class), Mockito.any(Host.Type.class), Mockito.nullable(List.class), Mockito.any(VMTemplateVO.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    Mockito.doReturn(hosts).when(firstFitAllocatorSpy).allocateTo(Mockito.any(VirtualMachineProfile.class), Mockito.any(DeploymentPlan.class), Mockito.any(ServiceOffering.class), Mockito.any(VMTemplateVO.class), Mockito.any(DeploymentPlanner.ExcludeList.class), Mockito.anyList(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any(Account.class));
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsToAvoidSet(Mockito.any(Host.Type.class), Mockito.any(DeploymentPlanner.ExcludeList.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    List<Host> suitableHosts = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, type, excludeList, null, HostAllocator.RETURN_UPTO_ALL, considerReservedCapacity);

    Mockito.verify(firstFitAllocatorSpy, Mockito.times(1)).allocateTo(virtualMachineProfile, deploymentPlan, serviceOffering, vmTemplateVO, excludeList, hosts, HostAllocator.RETURN_UPTO_ALL, considerReservedCapacity, account);
    Assert.assertEquals(2, suitableHosts.size());
  }

  @Test
  public void allocateToTestProvidedHostsNotNullShouldCallAddHostsToAvoidSetMethod() {
    List<HostVO> hosts = new ArrayList<>(Arrays.asList(host1, host2));

    Mockito.doReturn(serviceOffering).when(virtualMachineProfile).getServiceOffering();
    Mockito.doReturn(vmTemplateVO).when(virtualMachineProfile).getTemplate();
    Mockito.doReturn(account).when(virtualMachineProfile).getOwner();
    Mockito.doReturn(hostTag).when(serviceOffering).getHostTag();
    Mockito.doReturn(templateTag).when(vmTemplateVO).getTemplateTag();
    Mockito.doReturn(hosts).when(firstFitAllocatorSpy).retrieveHosts(Mockito.any(VirtualMachineProfile.class), Mockito.any(Host.Type.class), Mockito.nullable(List.class), Mockito.any(VMTemplateVO.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    Mockito.doReturn(hosts).when(firstFitAllocatorSpy).allocateTo(Mockito.any(VirtualMachineProfile.class), Mockito.any(DeploymentPlan.class), Mockito.any(ServiceOffering.class), Mockito.any(VMTemplateVO.class), Mockito.any(DeploymentPlanner.ExcludeList.class), Mockito.anyList(), Mockito.anyInt(), Mockito.anyBoolean(), Mockito.any(Account.class));
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsToAvoidSet(Mockito.any(Host.Type.class), Mockito.any(DeploymentPlanner.ExcludeList.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, type, excludeList, null, HostAllocator.RETURN_UPTO_ALL, considerReservedCapacity);

    Mockito.verify(firstFitAllocatorSpy, Mockito.times(1)).addHostsToAvoidSet(Mockito.any(Host.Type.class), Mockito.any(DeploymentPlanner.ExcludeList.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
  }

  @Test
  public void retrieveHostsTestHostsToFilterIsNullAndHaTagNotNullShouldReturnOnlyHostsWithHaTag() {
    List<HostVO> allUpAndEnabledHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> hostsWithHaTag = new ArrayList<>(Arrays.asList(host1, host2));
    String hostVmTag = "haVmTag";

    Mockito.doReturn(hostVmTag).when(virtualMachineProfile).getParameter(Mockito.any(VirtualMachineProfile.Param.class));
    Mockito.doReturn(allUpAndEnabledHosts).when(resourceManagerMock).listAllUpAndEnabledHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.doReturn(hostsWithHaTag).when(hostDaoMock).listByHostTag(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsWithUefiEnabled(Mockito.any(Host.Type.class), Mockito.any(VirtualMachineProfile.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsBasedOnTagRules(Mockito.anyString(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsBasedOnGuestOsRules(Mockito.any(VMTemplateVO.class), Mockito.anyList());
    List<HostVO> resultHosts = firstFitAllocatorSpy.retrieveHosts(virtualMachineProfile, type, emptyList, vmTemplateVO, clusterId, podId, dcId, hostTag, templateTag);

    Assert.assertEquals(2, resultHosts.size());
    Assert.assertEquals(host1, resultHosts.get(0));
    Assert.assertEquals(host2, resultHosts.get(1));
  }

  @Test
  public void retrieveHostsTestHostsToFilterIsNotNullAndHaTagNotNullShouldReturnOnlyHostsToFilterWithHaTag() {
    List<HostVO> hostsToFilter = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> hostsWithHaTag = new ArrayList<>(Arrays.asList(host1, host2));
    String hostVmTag = "haVmTag";

    Mockito.doReturn(hostVmTag).when(virtualMachineProfile).getParameter(Mockito.any(VirtualMachineProfile.Param.class));
    Mockito.doReturn(hostsWithHaTag).when(hostDaoMock).listByHostTag(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsWithUefiEnabled(Mockito.any(Host.Type.class), Mockito.any(VirtualMachineProfile.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsBasedOnTagRules(Mockito.anyString(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsBasedOnGuestOsRules(Mockito.any(VMTemplateVO.class), Mockito.anyList());
    List<HostVO> resultHosts = firstFitAllocatorSpy.retrieveHosts(virtualMachineProfile, type, hostsToFilter, vmTemplateVO, clusterId, podId, dcId, hostTag, templateTag);

    Assert.assertEquals(2, resultHosts.size());
    Assert.assertEquals(host1, resultHosts.get(0));
    Assert.assertEquals(host2, resultHosts.get(1));
  }

  @Test
  public void retrieveHostsTestHostsToFilterIsNullAndNoHaTagAndNoHostTagShouldReturnOnlyAllUpAndEnabledNonHaHosts() {
    List<HostVO> allUpAndEnabledHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> upAndEnabledHostsWithNoHa = new ArrayList<>(Arrays.asList(host1, host2));

    Mockito.doReturn(allUpAndEnabledHosts).when(resourceManagerMock).listAllUpAndEnabledHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.doReturn(upAndEnabledHostsWithNoHa).when(resourceManagerMock).listAllUpAndEnabledNonHAHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsWithUefiEnabled(Mockito.any(Host.Type.class), Mockito.any(VirtualMachineProfile.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsBasedOnTagRules(Mockito.nullable(String.class), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsBasedOnGuestOsRules(Mockito.any(VMTemplateVO.class), Mockito.anyList());
    List<HostVO> resultHosts = firstFitAllocatorSpy.retrieveHosts(virtualMachineProfile, type, emptyList, vmTemplateVO, clusterId, podId, dcId, null, null);

    Assert.assertEquals(2, resultHosts.size());
    Assert.assertEquals(host1, resultHosts.get(0));
    Assert.assertEquals(host2, resultHosts.get(1));
  }

  @Test
  public void retrieveHostsTestHostsToFilterIsNullAndNoHaTagWithHostTagShouldCallRetainHostsMatchingServiceOfferingAndTemplateTags() {
    List<HostVO> allUpAndEnabledHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(allUpAndEnabledHosts).when(resourceManagerMock).listAllUpAndEnabledHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    Mockito.doNothing().when(firstFitAllocatorSpy).retainHostsMatchingServiceOfferingAndTemplateTags(Mockito.anyList(), Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsWithUefiEnabled(Mockito.any(Host.Type.class), Mockito.any(VirtualMachineProfile.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).addHostsBasedOnTagRules(Mockito.anyString(), Mockito.anyList());
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsBasedOnGuestOsRules(Mockito.any(VMTemplateVO.class), Mockito.anyList());
    firstFitAllocatorSpy.retrieveHosts(virtualMachineProfile, type, emptyList, vmTemplateVO, clusterId, podId, dcId, hostTag, templateTag);

    Mockito.verify(firstFitAllocatorSpy, Mockito.times(1)).retainHostsMatchingServiceOfferingAndTemplateTags(Mockito.anyList(), Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString());
  }

  @Test
  public void addHostsToAvoidSetTestAllHostsWereConsideredForAllocationShouldNotAddAnyHostToTheAvoidSet() {
    List<HostVO> suitableHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(suitableHosts).when(hostDaoMock).listAllUpAndEnabledNonHAHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.nullable(String.class));
    firstFitAllocatorSpy.addHostsToAvoidSet(type, excludeList, clusterId, podId, dcId, suitableHosts);

    Assert.assertTrue(excludeList.getHostsToAvoid().isEmpty());
  }

  @Test
  public void addHostsToAvoidSetTestNotAllHostsWereConsideredForAllocationShouldAddHostToTheAvoidSet() {
    List<HostVO> allUpAndEnabledNonHAHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> consideredHosts = new ArrayList<>(Arrays.asList(host2, host3));

    Mockito.doReturn(1L).when(host1).getId();
    Mockito.doCallRealMethod().when(excludeList).addHost(Mockito.anyLong());
    Mockito.doCallRealMethod().when(excludeList).getHostsToAvoid();
    Mockito.doReturn(allUpAndEnabledNonHAHosts).when(hostDaoMock).listAllUpAndEnabledNonHAHosts(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.nullable(String.class));
    firstFitAllocatorSpy.addHostsToAvoidSet(type, excludeList, clusterId, podId, dcId, consideredHosts);

    Assert.assertEquals(1, excludeList.getHostsToAvoid().size());
    Assert.assertTrue(excludeList.getHostsToAvoid().contains(1L));
  }

  @Test
  public void filterHostsWithUefiEnabledTestNoDetailWithUefiShouldNotFilterAnyHost() {
    List<HostVO> suitableHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    VMInstanceDetailVO userVmDetailVO = null;

    Mockito.doReturn(userVmDetailVO).when(userVmDetailsDaoMock).findDetail(Mockito.anyLong(), Mockito.anyString());
    firstFitAllocatorSpy.filterHostsWithUefiEnabled(type, virtualMachineProfile, clusterId, podId, dcId, suitableHosts);

    Assert.assertEquals(3, suitableHosts.size());
    Assert.assertEquals(host1, suitableHosts.get(0));
    Assert.assertEquals(host2, suitableHosts.get(1));
    Assert.assertEquals(host3, suitableHosts.get(2));
  }

  @Test
  public void filterHostsWithUefiEnabledTestDetailWithUefiWithInvalidModeShouldNotFilterAnyHost() {
    List<HostVO> suitableHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    VMInstanceDetailVO userVmDetailVO = mock(VMInstanceDetailVO.class);
    String bootMode = "Invalid mode";

    Mockito.doReturn(bootMode).when(userVmDetailVO).getValue();
    Mockito.doReturn(userVmDetailVO).when(userVmDetailsDaoMock).findDetail(Mockito.anyLong(), Mockito.anyString());
    firstFitAllocatorSpy.filterHostsWithUefiEnabled(type, virtualMachineProfile, clusterId, podId, dcId, suitableHosts);

    Assert.assertEquals(3, suitableHosts.size());
    Assert.assertEquals(host1, suitableHosts.get(0));
    Assert.assertEquals(host2, suitableHosts.get(1));
    Assert.assertEquals(host3, suitableHosts.get(2));
  }

  @Test
  public void filterHostsWithUefiEnabledTestDetailWithUefiWithLegacyModeShouldFilterHost() {
    List<HostVO> suitableHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> uefiHosts = new ArrayList<>(Arrays.asList(host2, host3));
    VMInstanceDetailVO userVmDetailVO = mock(VMInstanceDetailVO.class);
    String bootMode = ApiConstants.BootMode.LEGACY.toString();

    Mockito.doReturn(bootMode).when(userVmDetailVO).getValue();
    Mockito.doReturn(userVmDetailVO).when(userVmDetailsDaoMock).findDetail(Mockito.anyLong(), Mockito.anyString());
    Mockito.doReturn(uefiHosts).when(hostDaoMock).listByHostCapability(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    firstFitAllocatorSpy.filterHostsWithUefiEnabled(type, virtualMachineProfile, clusterId, podId, dcId, suitableHosts);

    Assert.assertEquals(2, suitableHosts.size());
    Assert.assertEquals(host2, suitableHosts.get(0));
    Assert.assertEquals(host3, suitableHosts.get(1));
  }

  @Test
  public void filterHostsWithUefiEnabledTestDetailWithUefiWithSecureModeShouldFilterHost() {
    List<HostVO> suitableHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<HostVO> uefiHosts = new ArrayList<>(Arrays.asList(host2, host3));
    VMInstanceDetailVO userVmDetailVO = mock(VMInstanceDetailVO.class);
    String bootMode = ApiConstants.BootMode.SECURE.toString();

    Mockito.doReturn(bootMode).when(userVmDetailVO).getValue();
    Mockito.doReturn(userVmDetailVO).when(userVmDetailsDaoMock).findDetail(Mockito.anyLong(), Mockito.anyString());
    Mockito.doReturn(uefiHosts).when(hostDaoMock).listByHostCapability(Mockito.any(Host.Type.class), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyString());
    firstFitAllocatorSpy.filterHostsWithUefiEnabled(type, virtualMachineProfile, clusterId, podId, dcId, suitableHosts);

    Assert.assertEquals(2, suitableHosts.size());
    Assert.assertEquals(host2, suitableHosts.get(0));
    Assert.assertEquals(host3, suitableHosts.get(1));
  }

  @Test
  public void offeringRequestedVGpuAndHostDoesNotHaveItTestVGpuRequestedButHostDoesNotHaveItShouldReturnTrue() {
    Mockito.doReturn(1L).when(host1).getId();
    Mockito.doCallRealMethod().when(excludeList).addHost(Mockito.anyLong());
    Mockito.doCallRealMethod().when(excludeList).getHostsToAvoid();
    Mockito.doReturn(false).when(resourceManagerMock).isGPUDeviceAvailable(Mockito.any(ServiceOffering.class), Mockito.any(Host.class),  Mockito.any(Long.class));
    boolean result = firstFitAllocatorSpy.offeringRequestedVGpuAndHostDoesNotHaveIt(serviceOffering, virtualMachineProfile, excludeList, host1);

    Assert.assertTrue(result);
    Assert.assertEquals(1, excludeList.getHostsToAvoid().size());
    Assert.assertTrue(excludeList.getHostsToAvoid().contains(1L));
  }

  @Test
  public void offeringRequestedVGpuAndHostDoesNotHaveItTestVGpuRequestedAndHostDoesHaveItShouldReturnFalse() {
    Mockito.doReturn(true).when(resourceManagerMock).isGPUDeviceAvailable(Mockito.any(ServiceOffering.class), Mockito.any(Host.class),  Mockito.any(Long.class));
    boolean result = firstFitAllocatorSpy.offeringRequestedVGpuAndHostDoesNotHaveIt(serviceOffering, virtualMachineProfile, excludeList, host1);

    Assert.assertFalse(result);
    Mockito.verify(excludeList, Mockito.never()).addHost(Mockito.anyLong());
  }

  @Test
  public void filterHostWithNoHvmIfTemplateRequestedTestTemplateDoesNotRequireHvm() {
    List<HostVO> hosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(false).when(vmTemplateVO).isRequiresHvm();
    List<Host> suitableHosts = firstFitAllocatorSpy.filterHostWithNoHvmIfTemplateRequested(vmTemplateVO, hosts);

    Assert.assertEquals(3, suitableHosts.size());
    Assert.assertEquals(host1, suitableHosts.get(0));
    Assert.assertEquals(host2, suitableHosts.get(1));
    Assert.assertEquals(host3, suitableHosts.get(2));
  }

  @Test
  public void filterHostWithNoHvmIfTemplateRequestedTestTemplateRequiresHvmShouldReturnOnlyHvmHosts() {
    List<HostVO> hosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(true).when(vmTemplateVO).isRequiresHvm();
    Mockito.doReturn(true).when(firstFitAllocatorSpy).hostSupportsHVM(host1);
    Mockito.doReturn(false).when(firstFitAllocatorSpy).hostSupportsHVM(host2);
    Mockito.doReturn(true).when(firstFitAllocatorSpy).hostSupportsHVM(host3);
    List<Host> suitableHosts = firstFitAllocatorSpy.filterHostWithNoHvmIfTemplateRequested(vmTemplateVO, hosts);

    Assert.assertEquals(2, suitableHosts.size());
    Assert.assertEquals(host1, suitableHosts.get(0));
    Assert.assertEquals(host3, suitableHosts.get(1));
  }

  @Test
  public void prioritizeHostsByGpuEnabledTestServiceOfferingRequestedVGpuViaDetailShouldDoNothing() {
    List<Host> hosts = new ArrayList<>(Arrays.asList(host1, host2, host3));
    ServiceOfferingDetailsVO requestedVGpuType = mock(ServiceOfferingDetailsVO.class);

    Mockito.doReturn(requestedVGpuType).when(serviceOfferingDetailsDao).findDetail(Mockito.anyLong(), Mockito.anyString());
    firstFitAllocatorSpy.prioritizeHostsByGpuEnabled(serviceOffering, hosts);

    Assert.assertEquals(3, hosts.size());
    Assert.assertEquals(host1, hosts.get(0));
    Assert.assertEquals(host2, hosts.get(1));
    Assert.assertEquals(host3, hosts.get(2));
  }

  @Test
  public void prioritizeHostsByGpuEnabledTestServiceOfferingRequestedVGpuViaProfileIdShouldDoNothing() {
    List<Host> hosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(1L).when(serviceOffering).getVgpuProfileId();
    firstFitAllocatorSpy.prioritizeHostsByGpuEnabled(serviceOffering, hosts);

    Assert.assertEquals(3, hosts.size());
    Assert.assertEquals(host1, hosts.get(0));
    Assert.assertEquals(host2, hosts.get(1));
    Assert.assertEquals(host3, hosts.get(2));
  }

  @Test
  public void prioritizeHostsByGpuEnabledTestServiceOfferingDidNotRequestVGpuShouldReorderList() {
    List<Host> allHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(null).when(serviceOfferingDetailsDao).findDetail(Mockito.anyLong(), Mockito.anyString());
    Mockito.doReturn(null).when(serviceOffering).getVgpuProfileId();
    Mockito.doReturn(1L).when(host1).getId();
    Mockito.doReturn(2L).when(host2).getId();
    Mockito.doReturn(3L).when(host3).getId();
    Mockito.doReturn(true).when(resourceManagerMock).isHostGpuEnabled(1L);
    Mockito.doReturn(false).when(resourceManagerMock).isHostGpuEnabled(2L);
    Mockito.doReturn(false).when(resourceManagerMock).isHostGpuEnabled(3L);
    firstFitAllocatorSpy.prioritizeHostsByGpuEnabled(serviceOffering, allHosts);

    Assert.assertEquals(3, allHosts.size());
    Assert.assertEquals(host2, allHosts.get(0));
    Assert.assertEquals(host3, allHosts.get(1));
    Assert.assertEquals(host1, allHosts.get(2));
  }

  @Test
  public void prioritizeHostsByGpuEnabledTestServiceOfferingDidNotRequestVGpuShouldNotReorderListIfThereIsNoHostWithVGpu() {
    List<Host> allHosts = new ArrayList<>(Arrays.asList(host1, host2, host3));

    Mockito.doReturn(null).when(serviceOfferingDetailsDao).findDetail(Mockito.anyLong(), Mockito.anyString());
    Mockito.doReturn(null).when(serviceOffering).getVgpuProfileId();
    Mockito.doReturn(1L).when(host1).getId();
    Mockito.doReturn(2L).when(host2).getId();
    Mockito.doReturn(3L).when(host3).getId();
    Mockito.doReturn(false).when(resourceManagerMock).isHostGpuEnabled(Mockito.anyLong());
    firstFitAllocatorSpy.prioritizeHostsByGpuEnabled(serviceOffering, allHosts);

    Assert.assertEquals(3, allHosts.size());
    Assert.assertEquals(host1, allHosts.get(0));
    Assert.assertEquals(host2, allHosts.get(1));
    Assert.assertEquals(host3, allHosts.get(2));
  }

  @Test
  public void prioritizeHostsByHvmCapabilityTestTemplateDidNotRequestedHvmShouldPutHostThatDoesNotSupportHvmInStartOfThePriorityList() {
    List<Host> hostsToCheck = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<Host> prioritizedHosts = new ArrayList<>();

    Mockito.doReturn(false).when(vmTemplateVO).isRequiresHvm();
    Mockito.doReturn(true).when(firstFitAllocatorSpy).hostSupportsHVM(host1);
    Mockito.doReturn(false).when(firstFitAllocatorSpy).hostSupportsHVM(host2);
    Mockito.doReturn(true).when(firstFitAllocatorSpy).hostSupportsHVM(host3);
    firstFitAllocatorSpy.prioritizeHostsByHvmCapability(vmTemplateVO, hostsToCheck, prioritizedHosts);

    Assert.assertEquals(3, prioritizedHosts.size());
    Assert.assertEquals(host2, prioritizedHosts.get(0));
    Assert.assertEquals(host1, prioritizedHosts.get(1));
    Assert.assertEquals(host3, prioritizedHosts.get(2));
  }

  @Test
  public void prioritizeHostsByHvmCapabilityTestTemplateRequiresHvmShouldNotReorderList() {
    List<Host> hostsToCheck = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<Host> prioritizedHosts = new ArrayList<>();

    Mockito.doReturn(true).when(vmTemplateVO).isRequiresHvm();
    firstFitAllocatorSpy.prioritizeHostsByHvmCapability(vmTemplateVO, hostsToCheck, prioritizedHosts);

    Assert.assertEquals(3, prioritizedHosts.size());
    Assert.assertEquals(host1, prioritizedHosts.get(0));
    Assert.assertEquals(host2, prioritizedHosts.get(1));
    Assert.assertEquals(host3, prioritizedHosts.get(2));
  }

  @Test
  public void prioritizeHostsWithMatchingGuestOsTestShouldPutMatchingHostInHighPriorityAndHostsThatDoesNotMatchInLowPriorityList() {
    List<Host> hostsToCheck = new ArrayList<>(Arrays.asList(host1, host2, host3));
    List<Host> highPriorityHosts = new ArrayList<>();
    List<Host> lowPriorityHosts = new ArrayList<>();
    String guestOsCategory1 = "guestOsCategory1";
    String guestOsCategory2 = "guestOsCategory2";

    Mockito.doReturn(guestOsCategory1).when(firstFitAllocatorSpy).getTemplateGuestOSCategory(vmTemplateVO);
    Mockito.doReturn(guestOsCategory1).when(firstFitAllocatorSpy).getHostGuestOSCategory(host1);
    Mockito.doReturn(guestOsCategory2).when(firstFitAllocatorSpy).getHostGuestOSCategory(host2);
    Mockito.doReturn(null).when(firstFitAllocatorSpy).getHostGuestOSCategory(host3);
    firstFitAllocatorSpy.prioritizeHostsWithMatchingGuestOs(vmTemplateVO,hostsToCheck, highPriorityHosts, lowPriorityHosts);

    Assert.assertEquals(1, highPriorityHosts.size());
    Assert.assertEquals(host1, highPriorityHosts.get(0));
    Assert.assertEquals(1, lowPriorityHosts.size());
    Assert.assertEquals(host2, lowPriorityHosts.get(0));
  }

  @Test
  public void testHostByCombinedCapacityOrder() {
    // Test scenario 1: Default capacity usage (0.5 weight)
    List<CapacityVO> mockCapacity = getHostCapacities();
    Map<Long, Double> hostByCombinedCapacity = FirstFitAllocator.getHostByCombinedCapacities(mockCapacity, 0.5);

    // Verify host ordering and capacity values
    Long firstHostId = hostByCombinedCapacity.keySet().iterator().next();
    Assert.assertEquals("Host with ID 1 should be first in ordering", Long.valueOf(1L), firstHostId);
    Assert.assertEquals("Host 1 combined capacity should match expected value",
        0.9609375, hostByCombinedCapacity.get(1L), TOLERANCE);
    Assert.assertEquals("Host 2 combined capacity should match expected value",
        0.9296875, hostByCombinedCapacity.get(2L), TOLERANCE);

    // Test scenario 2: Modified capacity usage (0.7 weight)
    when(mockCapacity.get(0).getUsedCapacity()).thenReturn(1500L);
    hostByCombinedCapacity = FirstFitAllocator.getHostByCombinedCapacities(mockCapacity, 0.7);

    // Verify new ordering after capacity change
    firstHostId = hostByCombinedCapacity.keySet().iterator().next();
    Assert.assertEquals("Host with ID 2 should be first after capacity change", Long.valueOf(2L), firstHostId);
    Assert.assertEquals("Host 2 combined capacity should match expected value after change",
        0.9515625, hostByCombinedCapacity.get(2L), TOLERANCE);
    Assert.assertEquals("Host 1 combined capacity should match expected value after change",
        0.9484375, hostByCombinedCapacity.get(1L), TOLERANCE);
  }

  @Test
  public void zeroPoolHostNeverChosenForCpuOnly() {
    HostVO zeroPoolHost = mock(HostVO.class);
    when(zeroPoolHost.getId()).thenReturn(7L);
    List<Host> inputHosts = Arrays.asList(zeroPoolHost, host1);
    when(capacityMgr.hasNoComputeCapacity(zeroPoolHost)).thenReturn(true);
    when(capacityMgr.hasNoComputeCapacity(host1)).thenReturn(false);
    Mockito.lenient().when(avoid.shouldAvoid(zeroPoolHost)).thenReturn(false);
    Mockito.lenient().when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(zeroPoolHost, offering, true)).thenReturn(new Pair<>(true, true));
    Mockito.lenient().when(resourceManagerMock.isGPUDeviceAvailable(offering, zeroPoolHost, virtualMachineProfile.getId())).thenReturn(true);
    when(avoid.shouldAvoid(host1)).thenReturn(false);
    when(capacityMgr.checkIfHostReachMaxGuestLimit(Mockito.any(Host.class))).thenReturn(false);
    when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(host1, offering, true)).thenReturn(new Pair<>(true, true));
    when(resourceManagerMock.isGPUDeviceAvailable(offering, host1, virtualMachineProfile.getId())).thenReturn(true);

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, offering, null, avoid, inputHosts, 2, true, account);

    Assert.assertEquals(List.of(host1), result);
    Mockito.verify(avoid).addHost(7L);
  }

  @Test
  public void zeroPoolHostStillTakesGpuVm() {
    HostVO zeroPoolHost = mock(HostVO.class);
    ServiceOffering gpuOffering = mock(ServiceOffering.class);
    when(gpuOffering.getVgpuProfileId()).thenReturn(1L);
    when(avoid.shouldAvoid(zeroPoolHost)).thenReturn(false);
    when(capacityMgr.checkIfHostReachMaxGuestLimit(zeroPoolHost)).thenReturn(false);
    when(resourceManagerMock.isGPUDeviceAvailable(gpuOffering, zeroPoolHost, virtualMachineProfile.getId())).thenReturn(true);
    when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(zeroPoolHost, gpuOffering, true)).thenReturn(new Pair<>(true, true));

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, gpuOffering, null, avoid, Arrays.asList(zeroPoolHost), 1, true, account);

    Assert.assertEquals(List.of(zeroPoolHost), result);
  }

  @Test
  public void zeroPoolHostOrderingNoNaN() {
    List<CapacityVO> capacities = List.of(
        new CapacityVO(7L, dcId, podId, clusterId, 0L, 0L, Capacity.CAPACITY_TYPE_CPU),
        new CapacityVO(7L, dcId, podId, clusterId, 0L, 0L, Capacity.CAPACITY_TYPE_MEMORY),
        new CapacityVO(8L, dcId, podId, clusterId, 24000L, 32000L, Capacity.CAPACITY_TYPE_CPU),
        new CapacityVO(8L, dcId, podId, clusterId, 6442450944L, 8589934592L, Capacity.CAPACITY_TYPE_MEMORY));

    Map<Long, Double> hostByCombinedCapacity = FirstFitAllocator.getHostByCombinedCapacities(capacities, 0.5);

    Assert.assertEquals(List.of(8L, 7L), new ArrayList<>(hostByCombinedCapacity.keySet()));
    for (Double value : hostByCombinedCapacity.values()) {
      Assert.assertFalse(value.isNaN());
    }
    Assert.assertEquals(0.25, hostByCombinedCapacity.get(8L), TOLERANCE);
    Assert.assertEquals(0.0, hostByCombinedCapacity.get(7L), TOLERANCE);
  }

  List<CapacityVO> getHostCapacities() {
    CapacityVO cpuCapacity1 = mock(CapacityVO.class);
    when(cpuCapacity1.getHostOrPoolId()).thenReturn(1L);
    when(cpuCapacity1.getTotalCapacity()).thenReturn(32000L);
    when(cpuCapacity1.getReservedCapacity()).thenReturn(0L);
    when(cpuCapacity1.getUsedCapacity()).thenReturn(500L);
    when(cpuCapacity1.getCapacityType()).thenReturn(CapacityVO.CAPACITY_TYPE_CPU);

    CapacityVO cpuCapacity2 = mock(CapacityVO.class);
    when(cpuCapacity2.getHostOrPoolId()).thenReturn(2L);
    when(cpuCapacity2.getTotalCapacity()).thenReturn(32000L);
    when(cpuCapacity2.getReservedCapacity()).thenReturn(0L);
    when(cpuCapacity2.getUsedCapacity()).thenReturn(500L);
    when(cpuCapacity2.getCapacityType()).thenReturn(CapacityVO.CAPACITY_TYPE_CPU);

    CapacityVO memCapacity1 = mock(CapacityVO.class);
    when(memCapacity1.getHostOrPoolId()).thenReturn(1L);
    when(memCapacity1.getTotalCapacity()).thenReturn(8589934592L);
    when(memCapacity1.getReservedCapacity()).thenReturn(0L);
    when(memCapacity1.getUsedCapacity()).thenReturn(536870912L);
    when(memCapacity1.getCapacityType()).thenReturn(CapacityVO.CAPACITY_TYPE_MEMORY);

    CapacityVO memCapacity2 = mock(CapacityVO.class);
    when(memCapacity2.getHostOrPoolId()).thenReturn(2L);
    when(memCapacity2.getTotalCapacity()).thenReturn(8589934592L);
    when(memCapacity2.getReservedCapacity()).thenReturn(0L);
    when(memCapacity2.getUsedCapacity()).thenReturn(1073741824L);
    when(memCapacity1.getCapacityType()).thenReturn(CapacityVO.CAPACITY_TYPE_MEMORY);
    return Arrays.asList(cpuCapacity1, memCapacity1, cpuCapacity2, memCapacity2);
  }

  private ServiceOffering gpuOffering(int gpuCount) {
    ServiceOffering gpuOffering = mock(ServiceOffering.class);
    Mockito.lenient().when(gpuOffering.getVgpuProfileId()).thenReturn(11L);
    Mockito.lenient().when(gpuOffering.getGpuCount()).thenReturn(gpuCount);
    return gpuOffering;
  }

  private ServiceOffering cpuOffering(int vcpus) {
    ServiceOffering cpuOffering = mock(ServiceOffering.class);
    Mockito.lenient().when(cpuOffering.getCpu()).thenReturn(vcpus);
    Mockito.lenient().when(cpuOffering.getVgpuProfileId()).thenReturn(null);
    return cpuOffering;
  }

  private HostVO host(long id) {
    HostVO host = mock(HostVO.class);
    Mockito.lenient().when(host.getId()).thenReturn(id);
    return host;
  }

  private void hostsFit(ServiceOffering offering, Host... hosts) {
    for (Host host : hosts) {
      Mockito.lenient().when(avoid.shouldAvoid(host)).thenReturn(false);
      Mockito.lenient().when(capacityMgr.checkIfHostReachMaxGuestLimit(host)).thenReturn(false);
      Mockito.lenient().when(resourceManagerMock.isGPUDeviceAvailable(offering, host, virtualMachineProfile.getId())).thenReturn(true);
      Mockito.lenient().when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(host, offering, true)).thenReturn(new Pair<>(true, true));
    }
  }

  private List<Host> allocate(ServiceOffering offering, int returnUpTo, Host... hosts) {
    return firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, offering, null, avoid, Arrays.asList(hosts), returnUpTo, true, account);
  }

  @Test
  public void allocatorOrdersGpuHostsByBestFit() {
    ServiceOffering oneGpu = gpuOffering(1);
    HostVO emptyEightGpuHost = host(1L);
    HostVO oneFreeGpuHost = host(2L);
    hostsFit(oneGpu, emptyEightGpuHost, oneFreeGpuHost);
    when(gpuService.bestFitScore(1L, 11L, 1)).thenReturn(7 * 1024 + 1);
    when(gpuService.bestFitScore(2L, 11L, 1)).thenReturn(0);

    Assert.assertEquals(List.of(oneFreeGpuHost, emptyEightGpuHost), allocate(oneGpu, HostAllocator.RETURN_UPTO_ALL, emptyEightGpuHost, oneFreeGpuHost));
    Assert.assertEquals(List.of(oneFreeGpuHost), allocate(oneGpu, 1, emptyEightGpuHost, oneFreeGpuHost));
  }

  @Test
  public void gpuOrderingIgnoresAllocationAlgorithm() {
    ServiceOffering twoGpus = gpuOffering(2);
    HostVO first = host(1L);
    HostVO second = host(2L);
    HostVO third = host(3L);
    hostsFit(twoGpus, first, second, third);
    when(gpuService.bestFitScore(1L, 11L, 2)).thenReturn(3 * 1024);
    when(gpuService.bestFitScore(2L, 11L, 2)).thenReturn(0);
    when(gpuService.bestFitScore(3L, 11L, 2)).thenReturn(3 * 1024);

    for (String algorithm : List.of("firstfit", "firstfitmostconsumed", "firstfitleastconsumed", "userdispersing", "random")) {
      Mockito.lenient().doReturn(algorithm).when(firstFitAllocatorSpy).getVmAllocationAlgorithm();
      Assert.assertEquals(algorithm, List.of(second, first, third), allocate(twoGpus, HostAllocator.RETURN_UPTO_ALL, third, first, second));
    }
    Mockito.verifyNoInteractions(capacityDao);
  }

  @Test
  public void gpuAllocationAlgorithmFirstfitOrdersHostsById() {
    ServiceOffering oneGpu = gpuOffering(1);
    HostVO first = host(1L);
    HostVO second = host(2L);
    HostVO third = host(3L);
    hostsFit(oneGpu, first, second, third);

    doReturn("firstfit").when(firstFitAllocatorSpy).getGpuAllocationAlgorithm();
    Assert.assertEquals(List.of(first, second, third), allocate(oneGpu, HostAllocator.RETURN_UPTO_ALL, third, first, second));

    doReturn("random").when(firstFitAllocatorSpy).getGpuAllocationAlgorithm();
    List<Host> shuffled = allocate(oneGpu, HostAllocator.RETURN_UPTO_ALL, third, first, second);
    Assert.assertEquals(3, shuffled.size());
    Assert.assertEquals(Set.of(first, second, third), new HashSet<>(shuffled));
    Mockito.verify(gpuService, Mockito.never()).bestFitScore(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
  }

  @Test
  public void allocatorAvoidsHostWithoutUsableGroup() {
    ServiceOffering twoGpus = gpuOffering(2);
    HostVO noGroup = host(1L);
    HostVO withGroup = host(2L);
    hostsFit(twoGpus, withGroup);
    when(avoid.shouldAvoid(noGroup)).thenReturn(false);
    when(capacityMgr.checkIfHostReachMaxGuestLimit(noGroup)).thenReturn(false);
    when(resourceManagerMock.isGPUDeviceAvailable(twoGpus, noGroup, virtualMachineProfile.getId())).thenReturn(false);
    when(gpuService.bestFitScore(1L, 11L, 2)).thenReturn(Integer.MAX_VALUE);
    when(gpuService.bestFitScore(2L, 11L, 2)).thenReturn(5);

    Assert.assertEquals(List.of(withGroup), allocate(twoGpus, HostAllocator.RETURN_UPTO_ALL, noGroup, withGroup));
    Mockito.verify(avoid).addHost(1L);
  }

  @Test
  public void classTagKeepsMultiGpuOfferingOnItsClass() {
    String nvlinkOfferingTags = "a100-40g,a100-40g-nvlink";
    HostVO nvlinkHost = host(1L);
    HostVO nvswitchHost = host(2L);
    when(resourceManagerMock.listAllUpAndEnabledHosts(type, clusterId, podId, dcId)).thenReturn(new ArrayList<>(List.of(nvlinkHost, nvswitchHost)));
    when(hostDaoMock.listByHostTag(type, clusterId, podId, dcId, nvlinkOfferingTags)).thenReturn(new ArrayList<>(List.of(nvlinkHost)));
    Mockito.doNothing().when(firstFitAllocatorSpy).filterHostsBasedOnGuestOsRules(Mockito.any(), Mockito.anyList());

    List<HostVO> candidates = firstFitAllocatorSpy.retrieveHosts(virtualMachineProfile, type, null, vmTemplateVO, clusterId, podId, dcId,
        nvlinkOfferingTags, null);

    Mockito.verify(hostDaoMock).listByHostTag(type, clusterId, podId, dcId, nvlinkOfferingTags);
    Assert.assertEquals(List.of(nvlinkHost), candidates);
  }

  private CapacityVO capacity(long hostId, long used, long total, short capacityType) {
    return new CapacityVO(hostId, dcId, podId, clusterId, used, total, capacityType);
  }

  /**
   * Orders by firstfitmostconsumed over the COMBINED free fraction: host 1 is 82.5 % consumed (CPU 90 %, memory 75 %)
   * and has one free pool core, host 2 is 25 % consumed and has five. A host fits when ceil(vCPUs / 2) pool cores are free.
   */
  private void mostConsumedFixture(Map<Host, Integer> freeCores, CapacityVO... extraCapacities) {
    doReturn("firstfitmostconsumed").when(firstFitAllocatorSpy).getVmAllocationAlgorithm();
    when(deploymentPlan.getDataCenterId()).thenReturn(dcId);
    when(deploymentPlan.getClusterId()).thenReturn(clusterId);
    when(configDao.getValue(Config.HostCapacityTypeToOrderClusters.key())).thenReturn("COMBINED");
    List<CapacityVO> capacities = new ArrayList<>(List.of(
        capacity(1L, 28800L, 32000L, Capacity.CAPACITY_TYPE_CPU), capacity(1L, 48L << 30, 64L << 30, Capacity.CAPACITY_TYPE_MEMORY),
        capacity(2L, 8000L, 32000L, Capacity.CAPACITY_TYPE_CPU), capacity(2L, 16L << 30, 64L << 30, Capacity.CAPACITY_TYPE_MEMORY)));
    capacities.addAll(Arrays.asList(extraCapacities));
    when(capacityDao.listHostCapacityByCapacityTypes(dcId, clusterId, List.of(Capacity.CAPACITY_TYPE_CPU, Capacity.CAPACITY_TYPE_MEMORY)))
        .thenReturn(capacities);
    for (Host host : freeCores.keySet()) {
      Mockito.lenient().when(avoid.shouldAvoid(host)).thenReturn(false);
      Mockito.lenient().when(capacityMgr.checkIfHostReachMaxGuestLimit(host)).thenReturn(false);
      Mockito.lenient().when(resourceManagerMock.isGPUDeviceAvailable(Mockito.any(), Mockito.eq(host), Mockito.anyLong())).thenReturn(true);
    }
    Mockito.lenient().when(capacityMgr.checkIfHostHasCpuCapabilityAndCapacity(Mockito.any(Host.class), Mockito.any(ServiceOffering.class), Mockito.eq(true)))
        .thenAnswer(invocation -> {
          Host host = invocation.getArgument(0);
          ServiceOffering offering = invocation.getArgument(1);
          return new Pair<>(true, (offering.getCpu() + 1) / 2 <= freeCores.get(host));
        });
  }

  @Test
  public void cpuOnlyPrefersMostConsumedHostThatFits() {
    HostVO consumed = host(1L);
    HostVO roomy = host(2L);
    mostConsumedFixture(Map.of(consumed, 1, roomy, 5));

    Assert.assertEquals(List.of(consumed, roomy), allocate(cpuOffering(2), HostAllocator.RETURN_UPTO_ALL, roomy, consumed));
    Assert.assertEquals(List.of(consumed), allocate(cpuOffering(2), 1, roomy, consumed));
    Mockito.verify(avoid, Mockito.never()).addHost(1L);

    Assert.assertEquals("the 4-vCPU VM skips the most consumed host, whose one free core does not fit it",
        List.of(roomy), allocate(cpuOffering(4), 1, roomy, consumed));
    Mockito.verify(avoid).addHost(1L);
  }

  @Test
  public void mostConsumedOrderingIgnoresZeroPoolHosts() {
    HostVO consumed = host(1L);
    HostVO roomy = host(2L);
    HostVO zeroPool = host(3L);
    mostConsumedFixture(Map.of(consumed, 1, roomy, 5, zeroPool, 0),
        capacity(3L, 0L, 0L, Capacity.CAPACITY_TYPE_CPU), capacity(3L, 0L, 0L, Capacity.CAPACITY_TYPE_MEMORY));
    when(capacityMgr.hasNoComputeCapacity(zeroPool)).thenReturn(true);

    Assert.assertEquals(List.of(consumed, roomy), allocate(cpuOffering(2), HostAllocator.RETURN_UPTO_ALL, zeroPool, roomy, consumed));
    Mockito.verify(avoid).addHost(3L);
  }

  @Test
  public void cpuOnlyFillsNonGpuHostsBeforeGpuHostPools() {
    HostVO gpuHost = host(1L);
    HostVO plainHost = host(2L);
    mostConsumedFixture(Map.of(gpuHost, 5, plainHost, 5));
    when(resourceManagerMock.isHostGpuEnabled(1L)).thenReturn(true);
    when(resourceManagerMock.isHostGpuEnabled(2L)).thenReturn(false);
    when(gpuHost.getCapabilities()).thenReturn("hvm");
    when(plainHost.getCapabilities()).thenReturn("hvm");
    Mockito.doNothing().when(firstFitAllocatorSpy).prioritizeHostsWithMatchingGuestOs(Mockito.any(), Mockito.anyList(), Mockito.anyList(), Mockito.anyList());
    ServiceOffering cpuOnly = cpuOffering(2);

    List<Host> result = firstFitAllocatorSpy.allocateTo(virtualMachineProfile, deploymentPlan, cpuOnly, vmTemplateVO, avoid,
        Arrays.asList(gpuHost, plainHost), HostAllocator.RETURN_UPTO_ALL, true, account);

    Assert.assertEquals("the GPU host is more consumed, yet its pool is used last", List.of(plainHost, gpuHost), result);
  }
}
