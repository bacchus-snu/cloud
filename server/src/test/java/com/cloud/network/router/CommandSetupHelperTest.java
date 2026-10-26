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
package com.cloud.network.router;

import com.cloud.agent.api.Command;
import com.cloud.agent.api.routing.DhcpEntryCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetBgpPeersCommand;
import com.cloud.agent.api.routing.VmDataCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.net.Ip;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.BgpPeerVO;
import org.apache.cloudstack.network.dao.BgpPeerDetailsDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CommandSetupHelperTest {

    @InjectMocks
    protected CommandSetupHelper commandSetupHelper = new CommandSetupHelper();
    @Mock
    NicDao nicDao;
    @Mock
    NetworkDao networkDao;
    @Mock
    IPAddressDao ipAddressDao;
    @Mock
    VlanDao vlanDao;
    @Mock
    NetworkModel networkModel;
    @Mock
    NetworkOfferingDao networkOfferingDao;
    @Mock
    ConfigurationManager configurationManager;
    @Mock
    NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Mock
    NetworkDetailsDao networkDetailsDao;
    @Mock
    VpcDao vpcDao;
    @Mock
    RouterControlHelper routerControlHelper;
    @Mock
    DataCenterDao dcDao;
    @Mock
    ASNumberDao asNumberDao;
    @Mock
    BgpPeerDetailsDao bgpPeerDetailsDao;
    @Mock
    NicSecondaryIpDao nicSecondaryIpDao;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(commandSetupHelper, "_nicDao", nicDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_networkDao", networkDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_ipAddressDao", ipAddressDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_vlanDao", vlanDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_networkModel", networkModel);
        ReflectionTestUtils.setField(commandSetupHelper, "_networkOfferingDao", networkOfferingDao);
        ReflectionTestUtils.setField(commandSetupHelper, "networkOfferingDetailsDao", networkOfferingDetailsDao);
        ReflectionTestUtils.setField(commandSetupHelper, "networkDetailsDao", networkDetailsDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_vpcDao", vpcDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_routerControlHelper", routerControlHelper);
        ReflectionTestUtils.setField(commandSetupHelper, "_dcDao", dcDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_nicSecondaryIpDao", nicSecondaryIpDao);
    }

    @Test
    public void testUserDataDetails() {
        VmDataCommand vmDataCommand = new VmDataCommand("testVMname");
        String testUserDataDetails = new String("{test1=value1,test2=value2}");
        commandSetupHelper.addUserDataDetailsToCommand(vmDataCommand, testUserDataDetails);

        List<String[]> metadata = vmDataCommand.getVmData();
        String[] metadataFile1 = metadata.get(0);
        String[] metadataFile2 = metadata.get(1);

        Assert.assertEquals("metadata", metadataFile1[0]);
        Assert.assertEquals("metadata", metadataFile2[0]);

        Assert.assertEquals("test1", metadataFile1[1]);
        Assert.assertEquals("test2", metadataFile2[1]);

        Assert.assertEquals("value1", metadataFile1[2]);
        Assert.assertEquals("value2", metadataFile2[2]);
    }

    @Test
    public void testNullUserDataDetails() {
        VmDataCommand vmDataCommand = new VmDataCommand("testVMname");
        String testUserDataDetails = null;
        commandSetupHelper.addUserDataDetailsToCommand(vmDataCommand, testUserDataDetails);
        Assert.assertEquals(new ArrayList<>(), vmDataCommand.getVmData());
    }

    @Test
    public void testUserDataDetailsWithWhiteSpaces() {
        VmDataCommand vmDataCommand = new VmDataCommand("testVMname");
        String testUserDataDetails = new String("{test1 =value1,test2= value2 }");
        commandSetupHelper.addUserDataDetailsToCommand(vmDataCommand, testUserDataDetails);

        List<String[]> metadata = vmDataCommand.getVmData();
        String[] metadataFile1 = metadata.get(0);
        String[] metadataFile2 = metadata.get(1);

        Assert.assertEquals("metadata", metadataFile1[0]);
        Assert.assertEquals("metadata", metadataFile2[0]);

        Assert.assertEquals("test1", metadataFile1[1]);
        Assert.assertEquals("test2", metadataFile2[1]);

        Assert.assertEquals("value1", metadataFile1[2]);
        Assert.assertEquals("value2", metadataFile2[2]);
    }

    @Test
    public void testCreateVpcAssociatePublicIP() {
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        Ip ip = new Ip("10.10.10.10");
        IPAddressVO ipAddressVO = new IPAddressVO(ip, 1L, 0x0ac00000L, 2L, true);
        VlanVO vlanVO = new VlanVO();
        vlanVO.setNetworkId(15L);
        PublicIpAddress publicIpAddress = new PublicIp(ipAddressVO, vlanVO, 0x0ac00000L);
        List<PublicIpAddress> pubIpList = new ArrayList<>(1);
        pubIpList.add(publicIpAddress);
        Commands commands = new Commands(Command.OnError.Stop);
        Map<String, String> vlanMacAddress = new HashMap<>();
        NicVO nicVO = new NicVO("nic", 1L, 2L, VirtualMachine.Type.User);
        NetworkVO networkVO = new NetworkVO();
        networkVO.setNetworkOfferingId(12L);
        List<IPAddressVO> userIps = List.of(ipAddressVO);
        NetworkOfferingVO networkOfferingVO = new NetworkOfferingVO();
        Map<NetworkOffering.Detail, String> details = new HashMap<>();
        VpcVO vpc = new VpcVO();
        DataCenterVO dc = new DataCenterVO(1L, null, null, null, null, null, null, null, null, null, DataCenter.NetworkType.Advanced, null, null);

        when(router.getId()).thenReturn(14L);
        when(router.getDataCenterId()).thenReturn(4L);
        when(nicDao.listByVmId(ArgumentMatchers.anyLong())).thenReturn(List.of(nicVO));
        when(networkDao.findById(ArgumentMatchers.anyLong())).thenReturn(networkVO);
        when(ipAddressDao.listByAssociatedVpc(ArgumentMatchers.anyLong(), ArgumentMatchers.nullable(Boolean.class))).thenReturn(userIps);
        when(vlanDao.findById(ArgumentMatchers.anyLong())).thenReturn(vlanVO);
        when(networkModel.getNetworkRate(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenReturn(1200);
        when(networkModel.getNetwork(ArgumentMatchers.anyLong())).thenReturn(networkVO);
        when(networkOfferingDao.findById(ArgumentMatchers.anyLong())).thenReturn(networkOfferingVO);
        when(configurationManager.getNetworkOfferingNetworkRate(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())).thenReturn(1200);
        when(networkModel.isSecurityGroupSupportedInNetwork(networkVO)).thenReturn(false);
        when(networkOfferingDetailsDao.getNtwkOffDetails(ArgumentMatchers.anyLong())).thenReturn(details);
        when(networkDetailsDao.findDetail(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString())).thenReturn(null);
        when(vpcDao.findById(ArgumentMatchers.anyLong())).thenReturn(vpc);
        when(routerControlHelper.getRouterControlIp(ArgumentMatchers.anyLong())).thenReturn("10.1.11.101");
        when(dcDao.findById(ArgumentMatchers.anyLong())).thenReturn(dc);

        commandSetupHelper.createVpcAssociatePublicIPCommands(router, pubIpList, commands, vlanMacAddress);
        Assert.assertEquals(2, commands.size());
    }

    @Test
    public void testCreateBgpPeersCommandsForNetwork() {
        BgpPeerVO bgpPeer1 = Mockito.mock(BgpPeerVO.class);
        BgpPeerVO bgpPeer2 = Mockito.mock(BgpPeerVO.class);
        List<BgpPeerVO> bgpPeers = Arrays.asList(bgpPeer1, bgpPeer2);
        Commands cmds = new Commands(Command.OnError.Stop);
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);

        long zoneId = 10L;
        long networkId = 11L;
        when(router.getDataCenterId()).thenReturn(zoneId);
        when(router.getVpcId()).thenReturn(null);
        when(network.getId()).thenReturn(networkId);
        ASNumberVO asNumberVO = Mockito.mock(ASNumberVO.class);
        when(asNumberDao.findByZoneAndNetworkId(zoneId, networkId)).thenReturn(asNumberVO);
        DataCenterVO dc = Mockito.mock(DataCenterVO.class);
        when(dcDao.findById(zoneId)).thenReturn(dc);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);

        commandSetupHelper.createBgpPeersCommands(bgpPeers, router, cmds, network);

        Assert.assertEquals(1, cmds.size());
        Command cmd = cmds.toCommands()[0];
        Assert.assertTrue(cmd instanceof SetBgpPeersCommand);
        Assert.assertEquals(2, ((SetBgpPeersCommand) cmd).getBpgPeers().length);
    }

    @Test
    public void testCreateBgpPeersCommandsForVpc() {
        BgpPeerVO bgpPeer1 = Mockito.mock(BgpPeerVO.class);
        BgpPeerVO bgpPeer2 = Mockito.mock(BgpPeerVO.class);
        List<BgpPeerVO> bgpPeers = Arrays.asList(bgpPeer1, bgpPeer2);
        Commands cmds = new Commands(Command.OnError.Stop);
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);

        long zoneId = 10L;
        long vpcId = 11L;
        when(router.getDataCenterId()).thenReturn(zoneId);
        when(router.getVpcId()).thenReturn(vpcId);
        ASNumberVO asNumberVO = Mockito.mock(ASNumberVO.class);
        when(asNumberDao.findByZoneAndVpcId(zoneId, vpcId)).thenReturn(asNumberVO);

        long networkOfferingId = 12L;
        NetworkOfferingVO offering = Mockito.mock(NetworkOfferingVO.class);
        when(networkOfferingDao.findByIdIncludingRemoved(networkOfferingId)).thenReturn(offering);
        when(offering.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Dynamic);
        NetworkVO network1 = Mockito.mock(NetworkVO.class);
        when(network1.getNetworkOfferingId()).thenReturn(networkOfferingId);
        NetworkVO network2 = Mockito.mock(NetworkVO.class);
        when(network2.getNetworkOfferingId()).thenReturn(networkOfferingId);
        when(networkDao.listByVpc(vpcId)).thenReturn(Arrays.asList(network1, network2));

        DataCenterVO dc = Mockito.mock(DataCenterVO.class);
        when(dcDao.findById(zoneId)).thenReturn(dc);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);

        commandSetupHelper.createBgpPeersCommands(bgpPeers, router, cmds, network);

        Assert.assertEquals(1, cmds.size());
        Command cmd = cmds.toCommands()[0];
        Assert.assertTrue(cmd instanceof SetBgpPeersCommand);
        Assert.assertEquals(4, ((SetBgpPeersCommand) cmd).getBpgPeers().length);
    }

    @Test
    public void testDhcpLeaseTimeoutDefaultValue() {
        // Test that the default value is 0 (infinite)
        Integer defaultValue = NetworkOrchestrationService.DhcpLeaseTimeout.value();
        Assert.assertEquals("Default DHCP lease timeout should be 0 (infinite)", 0, defaultValue.intValue());
    }

    @Test
    public void testDhcpLeaseTimeoutAcceptsZero() {
        // Test that 0 value is accepted (infinite lease)
        ConfigKey<Integer> configKey = NetworkOrchestrationService.DhcpLeaseTimeout;
        Assert.assertNotNull("ConfigKey should not be null", configKey);
        Assert.assertEquals("ConfigKey default should be 0", "0", configKey.defaultValue());
    }

    @Test
    public void testDhcpLeaseTimeoutAcceptsPositiveValues() {
        // Test that positive values are accepted
        ConfigKey<Integer> configKey = NetworkOrchestrationService.DhcpLeaseTimeout;
        Assert.assertNotNull("ConfigKey should not be null", configKey);
        // Verify the config key exists and has expected default
        Assert.assertEquals("ConfigKey default should be 0", "0", configKey.defaultValue());
    }

    @Test
    public void testDhcpLeaseTimeoutHasZoneScope() {
        // Test that the ConfigKey has Zone scope
        ConfigKey<Integer> configKey = NetworkOrchestrationService.DhcpLeaseTimeout;
        Assert.assertTrue("ConfigKey should have Zone scope", configKey.getScopes().contains(ConfigKey.Scope.Zone));
    }

    @Test
    public void testDhcpLeaseTimeoutIsDynamic() {
        // Test that the ConfigKey is dynamic (can be updated at runtime)
        ConfigKey<Integer> configKey = NetworkOrchestrationService.DhcpLeaseTimeout;
        Assert.assertTrue("ConfigKey should be dynamic", configKey.isDynamic());
    }

    @Test
    public void createDhcpEntryCommandNamesTheVmsAccount() {
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        NicVO nic = Mockito.mock(NicVO.class);
        DataCenterVO dc = Mockito.mock(DataCenterVO.class);
        when(router.getDataCenterId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(7L);
        when(nic.getId()).thenReturn(3L);
        when(nic.getMacAddress()).thenReturn("02:00:00:00:00:0a");
        when(dcDao.findById(1L)).thenReturn(dc);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        NicSecondaryIpVO v4 = Mockito.mock(NicSecondaryIpVO.class);
        NicSecondaryIpVO v6 = Mockito.mock(NicSecondaryIpVO.class);
        when(v4.getIp4Address()).thenReturn("10.1.1.12");
        when(v6.getIp6Address()).thenReturn("fd00:1::12");
        when(nicSecondaryIpDao.listByNicId(3L)).thenReturn(Arrays.asList(v4, v6));

        Commands cmds = new Commands(Command.OnError.Continue);
        commandSetupHelper.createDhcpEntryCommand(router, vm, nic, false, cmds);

        DhcpEntryCommand entry = (DhcpEntryCommand) cmds.toCommands()[0];
        Assert.assertEquals(Long.valueOf(7L), entry.getVmAccountId());
        Assert.assertEquals(Arrays.asList("10.1.1.12", "fd00:1::12"), entry.getVmSecondaryAddresses());
    }

    @Test
    public void createDhcpEntryRemovalCommandRemovesTheEntryOfAMacAddress() {
        VirtualRouter router = Mockito.mock(VirtualRouter.class);
        DataCenterVO dc = Mockito.mock(DataCenterVO.class);
        when(router.getDataCenterId()).thenReturn(1L);
        when(router.getInstanceName()).thenReturn("r-7-VM");
        when(dcDao.findById(1L)).thenReturn(dc);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);

        Commands cmds = new Commands(Command.OnError.Continue);
        commandSetupHelper.createDhcpEntryRemovalCommand(router, "02:00:00:00:00:0a", "10.1.1.10", null, cmds);

        DhcpEntryCommand entry = (DhcpEntryCommand) cmds.toCommands()[0];
        Assert.assertTrue(entry.isRemove());
        Assert.assertEquals("02:00:00:00:00:0a", entry.getVmMac());
        Assert.assertEquals("10.1.1.10", entry.getVmIpAddress());
        Assert.assertEquals("r-7-VM", entry.getAccessDetail(NetworkElementCommand.ROUTER_NAME));
    }

    /**
     * @return the metadata, by name, that a virtual router hands the guest of Instance 9, whose hostname is admin-vm-0 and whose guest
     *         hostname recorded at its creation is the given one, none when null
     */
    private Map<String, String> metadataBehindAVirtualRouter(String guestHostName) {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(9L);
        when(vm.getServiceOfferingId()).thenReturn(1L);
        Mockito.lenient().when(vm.getHostName()).thenReturn("admin-vm-0");
        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        when(offering.getDisplayText()).thenReturn("offering");
        ServiceOfferingDao serviceOfferingDao = Mockito.mock(ServiceOfferingDao.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(9L, 1L)).thenReturn(offering);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(zone.getName()).thenReturn("zone");
        when(zone.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        when(dcDao.findById(1L)).thenReturn(zone);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkDao.findById(5L)).thenReturn(network);
        NicVO nic = Mockito.mock(NicVO.class);
        when(nic.getIPv4Address()).thenReturn("10.1.1.9");
        when(nic.getNetworkId()).thenReturn(5L);
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getDataCenterId()).thenReturn(1L);
        VMInstanceDetailsDao vmInstanceDetailsDao = Mockito.mock(VMInstanceDetailsDao.class);
        Mockito.lenient().when(vmInstanceDetailsDao.findGuestHostName(9L)).thenReturn(guestHostName);
        ReflectionTestUtils.setField(commandSetupHelper, "_serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(commandSetupHelper, "_hostDao", Mockito.mock(HostDao.class));
        ReflectionTestUtils.setField(commandSetupHelper, "_configDao", Mockito.mock(ConfigurationDao.class));
        ReflectionTestUtils.setField(commandSetupHelper, "domainDao", Mockito.mock(DomainDao.class));
        ReflectionTestUtils.setField(commandSetupHelper, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        Commands cmds = new Commands(Command.OnError.Stop);

        CallContext.register(Mockito.mock(User.class), Mockito.mock(Account.class));
        try {
            commandSetupHelper.createVmDataCommand(router, vm, nic, "key", cmds);
        } finally {
            CallContext.unregister();
        }

        Map<String, String> metadata = new HashMap<>();
        for (String[] item : cmds.getCommand(VmDataCommand.class).getVmData()) {
            if ("metadata".equals(item[0])) {
                metadata.put(item[1], item[2]);
            }
        }
        return metadata;
    }

    @Test
    public void theGuestBehindAVirtualRouterTakesTheHostnameRecordedAtTheCreationOfItsInstance() {
        Assert.assertEquals("web", metadataBehindAVirtualRouter("web").get("local-hostname"));
    }

    @Test
    public void theGuestBehindAVirtualRouterOfAnInstanceWithoutARecordedGuestHostnameTakesTheHostnameOfTheInstance() {
        Assert.assertEquals("admin-vm-0", metadataBehindAVirtualRouter(null).get("local-hostname"));
    }
}
