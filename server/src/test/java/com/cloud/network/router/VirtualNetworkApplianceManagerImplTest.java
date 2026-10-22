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

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckS2SVpnConnectionsAnswer;
import com.cloud.agent.api.CheckS2SVpnConnectionsCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.uservm.UserVm;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.bgp.BGPService;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.MonitoringServiceDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OpRouterMonitorServiceDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.Site2SiteCustomerGatewayDao;
import com.cloud.network.dao.Site2SiteCustomerGatewayVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.dao.VirtualRouterProviderDao;
import com.cloud.network.dao.VpnUserDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.UserStatsLogDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.BgpPeer;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;



@RunWith(MockitoJUnitRunner.class)
public class VirtualNetworkApplianceManagerImplTest {
    @Mock
    private ClusterDao _clusterDao;

    @Mock
    private ConfigurationDao _configDao;

    @Mock
    private DataCenterDao _dcDao;

    @Mock
    private GuestOSDao _guestOSDao;

    @Mock
    private HostDao _hostDao;

    @Mock
    private IPAddressDao _ipAddressDao;

    @Mock
    private UserIpv6AddressDao _ipv6Dao;

    @Mock
    private LoadBalancerDao _loadBalancerDao;

    @Mock
    private LoadBalancerVMMapDao _loadBalancerVMMapDao;

    @Mock
    private MonitoringServiceDao _monitorServiceDao;

    @Mock
    private ManagementServerHostDao _msHostDao;

    @Mock
    private NetworkDao _networkDao;

    @Mock
    private NetworkOfferingDao _networkOfferingDao;

    @Mock
    private NicDao _nicDao;

    @Mock
    private NicIpAliasDao _nicIpAliasDao;

    @Mock
    private OpRouterMonitorServiceDao _opRouterMonitorServiceDao;

    @Mock
    private PortForwardingRulesDao _pfRulesDao;

    @Mock
    private PhysicalNetworkServiceProviderDao _physicalProviderDao;

    @Mock
    private HostPodDao _podDao;

    @Mock
    private DomainRouterDao _routerDao;

    @Mock
    private FirewallRulesDao _rulesDao;

    @Mock
    private Site2SiteCustomerGatewayDao _s2sCustomerGatewayDao;

    @Mock
    private Site2SiteVpnConnectionDao _s2sVpnConnectionDao;

    @Mock
    private Site2SiteVpnGatewayDao _s2sVpnGatewayDao;

    @Mock
    private ServiceOfferingDao _serviceOfferingDao;

    @Mock
    private VMTemplateDao _templateDao;

    @Mock
    private UserDao _userDao;

    @Mock
    private UserStatisticsDao _userStatsDao;

    @Mock
    private UserStatsLogDao _userStatsLogDao;

    @Mock
    private UserVmDao _userVmDao;

    @Mock
    private VlanDao _vlanDao;

    @Mock
    private VMInstanceDao _vmDao;

    @Mock
    private VMInstanceDetailsDao _vmDetailsDao;

    @Mock
    private VolumeDao _volumeDao;

    @Mock
    private RemoteAccessVpnDao _vpnDao;

    @Mock
    private VpnUserDao _vpnUsersDao;

    @Mock
    private VirtualRouterProviderDao _vrProviderDao;

    //@Mock private AccountManager _accountMgr;

    @Mock private VirtualMachineManager _itMgr;

    @Mock
    private AgentManager _agentMgr;

    @Mock
    private CheckS2SVpnConnectionsCommand _s2sConnCommand;

    @Mock
    private CheckS2SVpnConnectionsAnswer _s2sVpnAnswer;

    @Mock
    private DomainRouterVO _router;

    @Mock
    private AlertManager _alertMgr;

    @Mock
    private Site2SiteVpnManager _s2sVpnMgr;

    @Mock
    private RouterControlHelper _routerControlHelper;

    @InjectMocks
    private VirtualNetworkApplianceManagerImpl virtualNetworkApplianceManagerImpl;

    @Mock
    private NetworkModel _networkModel;

    @Mock
    private RoutedIpv4Manager routedIpv4Manager;

    @Mock
    private CommandSetupHelper _commandSetupHelper;

    @Mock
    private VpcDao _vpcDao;

    @Mock
    private BGPService bgpService;

    @Mock
    private LoadBalancingRulesManager _lbMgr;

    @Mock
    private NetworkHelper _nwHelper;

    @Mock
    private NicSecondaryIpDao _nicSecondaryIpDao;

    //    @InjectMocks
    //    private VirtualNetworkApplianceManagerImpl virtualNetworkApplianceManagerImpl;

    @Test
    public void testDestroyRouter() throws Exception {
        //        VirtualRouter r = new DomainRouterVO(1L, 0L, 0L, "router", 0L, HypervisorType.Any, 0L, 0L,
        //                1L, false, 0, false, RedundantState.UNKNOWN, false, false, null);
        //        when(_routerDao.findById(1L)).thenReturn((DomainRouterVO)r);
        //        VirtualRouter vr = virtualNetworkApplianceManagerImpl.destroyRouter(1L, new AccountVO(1L), 0L);
        //        assertNotEquals(vr, null);
    }

    @Test
    public void testDeployRouterNotRedundant() throws Exception {
        //        DataCenter dc = new DataCenterVO(1L, "name", "description", "dns", null, null, null, "cidr", "domain", null,
        //                NetworkType.Basic, "zoneToken", "domainSuffix");
        //        when(_routerDao.getNextInSequence(Long.class, "id")).thenReturn(1L);
        //        when(_resourceMgr.getDefaultHypervisor(1L)).thenReturn(HypervisorType.Any);
        //        DeploymentPlan plan = new DataCenterDeployment(1L);
        //        VirtualRouter vr = virtualNetworkApplianceManagerImpl.deployRouter(new AccountVO(1L), new DeployDestination(dc,null,null,null), plan, null, false,
        //                new VirtualRouterProviderVO(), 0L, null, new LinkedHashMap<Network, List<? extends NicProfile>> (), true /* start the router */,
        //                null);
        //        // TODO: more elaborate mocking needed to have a vr returned
        //assertEquals(vr, null);
    }

    @Test
    public void testUpdateSite2SiteVpnConnectionState() throws Exception{

        DomainRouterVO router = new DomainRouterVO(1L, 1L, 1L, "First testing router", 1L, Hypervisor.HypervisorType.XenServer, 1L, 1L, 1L, 1L, false, VirtualRouter.RedundantState.PRIMARY, true, true, 1L);
        router.setState(VirtualMachine.State.Running);
        router.setPrivateIpAddress("192.168.50.15");

        List<DomainRouterVO> routers = new ArrayList<DomainRouterVO>();
        routers.add(router);

        Site2SiteVpnConnectionVO conn = new Site2SiteVpnConnectionVO(1L, 1L, 1L, 1L, false);
        Site2SiteVpnConnectionVO conn1 = new Site2SiteVpnConnectionVO(1L, 1L, 1L, 1L, false);
        conn.setState(Site2SiteVpnConnection.State.Disconnected);
        conn1.setState(Site2SiteVpnConnection.State.Disconnected);
        List<Site2SiteVpnConnectionVO> conns = new ArrayList<Site2SiteVpnConnectionVO>();
        conns.add(conn);
        conns.add(conn1);

        Site2SiteCustomerGatewayVO gw = new Site2SiteCustomerGatewayVO("Testing gateway", 1L, 1L, "192.168.50.15", "Guest List", "ipsecPsk", "ikePolicy", "espPolicy", 1L, 1L, true, true, false, "ike");
        HostVO hostVo = new HostVO(1L, "Testing host", Host.Type.Routing, "192.168.50.15", "privateNetmask", "privateMacAddress", "publicIpAddress", "publicNetmask", "publicMacAddress", "storageIpAddress", "storageNetmask", "storageMacAddress", "deuxStorageIpAddress", "duxStorageNetmask", "deuxStorageMacAddress", "guid", Status.Up, "version", "iqn", new Date() , 1L, 1L, 1L, 1L, "parent", 20L, Storage.StoragePoolType.Gluster);
        hostVo.setManagementServerId(ManagementServerNode.getManagementServerId());

        ArrayList<String> ipList = new ArrayList<>();
        ipList.add("192.168.50.15");

        _s2sConnCommand = new CheckS2SVpnConnectionsCommand(ipList);

        when(_s2sVpnMgr.getConnectionsForRouter(router)).thenReturn(conns);
        when(_s2sVpnConnectionDao.persist(conn)).thenReturn(null);
        when(_s2sCustomerGatewayDao.findById(conn.getCustomerGatewayId())).thenReturn(gw);
        when(_hostDao.findById(router.getHostId())).thenReturn(hostVo);
        when(_routerControlHelper.getRouterControlIp(router.getId())).thenReturn("192.168.50.15");
        doReturn(_s2sVpnAnswer).when(_agentMgr).easySend(nullable(Long.class), nullable(CheckS2SVpnConnectionsCommand.class));
        when(_s2sVpnAnswer.getResult()).thenReturn(true);
        when(_s2sVpnConnectionDao.acquireInLockTable(conn.getId())).thenReturn(conn);
        when(_s2sVpnAnswer.isIPPresent("192.168.50.15")).thenReturn(true);
        when(_s2sVpnAnswer.isConnected("192.168.50.15")).thenReturn(true);
        lenient().doNothing().when(_alertMgr).sendAlert(any(AlertManager.AlertType.class), anyLong(), anyLong(), anyString(), anyString());

        virtualNetworkApplianceManagerImpl.updateSite2SiteVpnConnectionState(routers);

        for(Site2SiteVpnConnection connection : conns){
            assertEquals(Site2SiteVpnConnection.State.Connected, connection.getState());
        }
    }

    @Test
    public void checkLogrotateTimerPatternTestDoNotMatchWithRegex(){
        String foo = "non-sense";
        boolean result = virtualNetworkApplianceManagerImpl.checkLogrotateTimerPattern(foo);
        Assert.assertFalse(result);
        foo = "*";
        result = virtualNetworkApplianceManagerImpl.checkLogrotateTimerPattern(foo);
        Assert.assertFalse(result);
    }

    @Test
    public void checkLogrotateTimerPatternTestMatchesWithRegex(){
        String foo = "hourly";
        boolean result = virtualNetworkApplianceManagerImpl.checkLogrotateTimerPattern(foo);
        Assert.assertTrue(result);
        foo = "*:00:00";
        result = virtualNetworkApplianceManagerImpl.checkLogrotateTimerPattern(foo);
        Assert.assertTrue(result);
        foo = "*:*:00";
        result = virtualNetworkApplianceManagerImpl.checkLogrotateTimerPattern(foo);
        Assert.assertTrue(result);
    }

    @Test
    public void testFinalizeNetworkRulesForNetwork() {
        Long guestNetworkId = 10L;
        Commands cmds = new Commands(Command.OnError.Stop);
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);

        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(guestNetworkId)).thenReturn(network);
        when(network.getVpcId()).thenReturn(null);
        when(routedIpv4Manager.isDynamicRoutedNetwork(network)).thenReturn(true);
        List<? extends BgpPeer> bgpPeers = Mockito.mock(List.class);
        doReturn(bgpPeers).when(bgpService).getBgpPeersForNetwork(network);
        virtualNetworkApplianceManagerImpl.finalizeNetworkRulesForNetwork(cmds, router, Network.Provider.VirtualRouter, guestNetworkId);

        Mockito.verify(_commandSetupHelper).createBgpPeersCommands(bgpPeers, router, cmds, network);
    }

    @Test
    public void testFinalizeNetworkRulesForVpcNetwork() {
        Long guestNetworkId = 10L;
        Long vpcId = 11L;
        Commands cmds = new Commands(Command.OnError.Stop);
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);

        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(guestNetworkId)).thenReturn(network);
        when(network.getVpcId()).thenReturn(vpcId);
        VpcVO vpc = Mockito.mock(VpcVO.class);
        when(_vpcDao.findById(vpcId)).thenReturn(vpc);
        when(routedIpv4Manager.isDynamicRoutedVpc(vpc)).thenReturn(true);
        List<? extends BgpPeer> bgpPeers = Mockito.mock(List.class);
        doReturn(bgpPeers).when(bgpService).getBgpPeersForVpc(vpc);

        virtualNetworkApplianceManagerImpl.finalizeNetworkRulesForNetwork(cmds, router, Network.Provider.VirtualRouter, guestNetworkId);

        Mockito.verify(_commandSetupHelper).createBgpPeersCommands(bgpPeers, router, cmds, network);
    }

    @Test
    public void testUpdateWithLbRuleSslCertificates() {
        StringBuilder loadBalancingData = new StringBuilder();
        LoadBalancerVO loadBalancer = Mockito.mock(LoadBalancerVO.class);
        when(loadBalancer.getLbProtocol()).thenReturn(NetUtils.SSL_PROTO);
        when(loadBalancer.getId()).thenReturn(1L);
        when(loadBalancer.getSourcePortStart()).thenReturn(443);
        LoadBalancingRule.LbSslCert lbSslCert = Mockito.mock(LoadBalancingRule.LbSslCert.class);
        when(lbSslCert.isRevoked()).thenReturn(false);
        when(_lbMgr.getLbSslCert(1L)).thenReturn(lbSslCert);
        String sourceIp = "1.2.3.4";

        virtualNetworkApplianceManagerImpl.updateWithLbRuleSslCertificates(loadBalancingData, loadBalancer, sourceIp);

        Assert.assertEquals(",sslcert=1_2_3_4-443.pem",  loadBalancingData.toString());
    }

    private String guestBootArgs(Network.GuestType guestType, boolean routerDns) {
        NicProfile guestNic = Mockito.mock(NicProfile.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        DataCenterVO dc = Mockito.mock(DataCenterVO.class);
        when(guestNic.getNetworkId()).thenReturn(10L);
        when(_networkDao.findById(10L)).thenReturn(network);
        lenient().when(network.getId()).thenReturn(10L);
        lenient().when(_networkModel.isProviderSupportServiceInNetwork(10L, Network.Service.Dns, Network.Provider.VirtualRouter)).thenReturn(routerDns);
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getNetworkOfferingId()).thenReturn(5L);
        lenient().when(network.getGuestType()).thenReturn(guestType);
        when(_dcDao.findById(1L)).thenReturn(dc);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        when(_networkOfferingDao.findById(5L)).thenReturn(Mockito.mock(NetworkOfferingVO.class));
        when(_networkModel.getValidNetworkCidr(network)).thenReturn("10.1.1.0/24");
        return virtualNetworkApplianceManagerImpl.createGuestBootLoadArgs(guestNic, _router).toString();
    }

    @Test
    public void routerOfASharedNetworkAnswersGuestNamesPerAccount() {
        String args = guestBootArgs(Network.GuestType.Shared, true);
        Assert.assertTrue(args, args.contains(" dnsperaccount=true"));
    }

    @Test
    public void routerOfASharedNetworkWhoseDnsIsNotTheRoutersKeepsOneViewOfGuestNames() {
        String args = guestBootArgs(Network.GuestType.Shared, false);
        Assert.assertFalse(args, args.contains("dnsperaccount"));
    }

    @Test
    public void routerOfAnIsolatedNetworkKeepsOneViewOfGuestNames() {
        String args = guestBootArgs(Network.GuestType.Isolated, true);
        Assert.assertFalse(args, args.contains("dnsperaccount"));
    }

    private static final String MAC = "02:00:00:00:00:0a";

    private DomainRouterVO router(long id, VirtualMachine.State state) {
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        lenient().when(router.getId()).thenReturn(id);
        lenient().when(router.getState()).thenReturn(state);
        lenient().when(router.getInstanceName()).thenReturn("r-" + id + "-VM");
        lenient().when(router.getDataCenterId()).thenReturn(1L);
        return router;
    }

    private NicVO guestNic() {
        NicVO nic = Mockito.mock(NicVO.class);
        lenient().when(nic.getNetworkId()).thenReturn(9L);
        lenient().when(nic.getMacAddress()).thenReturn(MAC);
        lenient().when(nic.getIPv4Address()).thenReturn("10.1.1.10");
        lenient().when(nic.getInstanceId()).thenReturn(5L);
        lenient().when(nic.getVmType()).thenReturn(VirtualMachine.Type.User);
        return nic;
    }

    private VMInstanceDetailVO detail(long routerId, String name, String value) {
        return new VMInstanceDetailVO(routerId, name, value, false);
    }

    private NetworkVO guestNetwork(long id, Network.GuestType guestType) {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        lenient().when(network.getId()).thenReturn(id);
        lenient().when(network.getGuestType()).thenReturn(guestType);
        lenient().when(network.getDataCenterId()).thenReturn(1L);
        lenient().when(_networkDao.findById(id)).thenReturn(network);
        lenient().when(_networkModel.isProviderSupportServiceInNetwork(id, Network.Service.Dhcp, Network.Provider.VirtualRouter)).thenReturn(true);
        lenient().when(_networkModel.isProviderSupportServiceInNetwork(id, Network.Service.Dns, Network.Provider.VirtualRouter)).thenReturn(true);
        return network;
    }

    @Test
    public void onlyRoutersThatAreTheDnsOfASharedNetworkAnswerGuestNamesPerAccount() {
        Assert.assertTrue(virtualNetworkApplianceManagerImpl.answersGuestNamesPerAccount(guestNetwork(9L, Network.GuestType.Shared)));
        Assert.assertFalse(virtualNetworkApplianceManagerImpl.answersGuestNamesPerAccount(guestNetwork(12L, Network.GuestType.Isolated)));
        NetworkVO withoutRouterDns = guestNetwork(13L, Network.GuestType.Shared);
        when(_networkModel.isProviderSupportServiceInNetwork(13L, Network.Service.Dns, Network.Provider.VirtualRouter)).thenReturn(false);
        Assert.assertFalse(virtualNetworkApplianceManagerImpl.answersGuestNamesPerAccount(withoutRouterDns));
    }

    @Test
    public void aRouterThatMissesADhcpEntryChangeKeepsItPendingAndRaisesAnAlert() throws Exception {
        UserVm vm = Mockito.mock(UserVm.class);
        NicVO nic = guestNic();
        guestNetwork(9L, Network.GuestType.Shared);
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        DomainRouterVO stopped = router(8L, VirtualMachine.State.Stopped);
        DomainRouterVO starting = router(10L, VirtualMachine.State.Starting);
        DomainRouterVO stopping = router(11L, VirtualMachine.State.Stopping);
        when(_routerDao.findByNetwork(9L)).thenReturn(List.of(running, stopped, starting, stopping));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(false);

        virtualNetworkApplianceManagerImpl.refreshDhcpEntry(vm, nic);

        Mockito.verify(_commandSetupHelper).createDhcpEntryCommand(Mockito.eq(running), Mockito.eq(vm), Mockito.eq(nic), Mockito.eq(false), any(Commands.class));
        Mockito.verifyNoMoreInteractions(_commandSetupHelper);
        for (long missed : new long[] {7L, 10L, 11L}) {
            Mockito.verify(_vmDetailsDao).addDetail(Mockito.eq(missed), Mockito.eq("dhcp.pending." + MAC), Mockito.startsWith("10.1.1.10,,"), Mockito.eq(false));
            Mockito.verify(_vmDetailsDao).addDetail(missed, "dhcp.pending", "true", false);
            Mockito.verify(_alertMgr).sendAlert(Mockito.eq(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER), Mockito.eq(1L), nullable(Long.class),
                    Mockito.contains("r-" + missed + "-VM"), anyString());
        }
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(Mockito.eq(8L), anyString(), anyString(), Mockito.anyBoolean());
    }

    @Test
    public void aRouterWhoseEntryCannotBeBuiltKeepsItPendingWhileTheOthersGetIt() throws Exception {
        UserVm vm = Mockito.mock(UserVm.class);
        NicVO nic = guestNic();
        guestNetwork(9L, Network.GuestType.Shared);
        DomainRouterVO broken = router(7L, VirtualMachine.State.Running);
        DomainRouterVO healthy = router(12L, VirtualMachine.State.Running);
        when(_routerDao.findByNetwork(9L)).thenReturn(List.of(broken, healthy));
        Mockito.doThrow(new CloudRuntimeException("no control address")).when(_commandSetupHelper)
                .createDhcpEntryCommand(Mockito.eq(broken), any(), any(), Mockito.anyBoolean(), any(Commands.class));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(healthy), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.refreshDhcpEntry(vm, nic);

        Mockito.verify(_commandSetupHelper).createDhcpEntryCommand(Mockito.eq(healthy), Mockito.eq(vm), Mockito.eq(nic), Mockito.eq(false), any(Commands.class));
        Mockito.verify(_vmDetailsDao).addDetail(7L, "dhcp.pending", "true", false);
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(Mockito.eq(12L), anyString(), anyString(), Mockito.anyBoolean());
    }

    @Test
    public void aRouterThatTakesTheDhcpEntryChangeHasNothingPending() throws Exception {
        guestNetwork(9L, Network.GuestType.Shared);
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        when(_routerDao.findByNetwork(9L)).thenReturn(List.of(running));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.refreshDhcpEntry(Mockito.mock(UserVm.class), guestNic());

        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), anyString(), anyString(), Mockito.anyBoolean());
        Mockito.verifyNoInteractions(_alertMgr);
    }

    @Test
    public void routersThatKeepOneViewOfGuestNamesAreLeftAsTheyAre() throws Exception {
        guestNetwork(9L, Network.GuestType.Isolated);
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        DomainRouterVO starting = router(10L, VirtualMachine.State.Starting);
        lenient().when(_routerDao.findByNetwork(9L)).thenReturn(List.of(running, starting));

        virtualNetworkApplianceManagerImpl.refreshDhcpEntry(Mockito.mock(UserVm.class), guestNic());

        Mockito.verifyNoInteractions(_commandSetupHelper, _nwHelper, _alertMgr);
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(Mockito.anyLong(), anyString(), anyString(), Mockito.anyBoolean());
    }

    private Object addStandIn(Commands cmds) {
        cmds.addCommand(new CheckS2SVpnConnectionsCommand(new ArrayList<>()));
        return null;
    }

    private void entriesBecomeCommands(DomainRouterVO router) {
        lenient().doAnswer(invocation -> addStandIn(invocation.getArgument(4))).when(_commandSetupHelper)
                .createDhcpEntryCommand(Mockito.eq(router), any(), any(), Mockito.anyBoolean(), any(Commands.class));
        lenient().doAnswer(invocation -> addStandIn(invocation.getArgument(4))).when(_commandSetupHelper)
                .createDhcpEntryRemovalCommand(Mockito.eq(router), anyString(), nullable(String.class), nullable(String.class), any(Commands.class));
    }

    private void pendingOn(DomainRouterVO router, VMInstanceDetailVO... records) {
        List<VMInstanceDetailVO> details = new ArrayList<>(List.of(detail(router.getId(), "Message.ReservedCapacityFreed.Flag", "false")));
        details.addAll(List.of(records));
        when(_vmDetailsDao.listDetails(router.getId())).thenReturn(details);
        lenient().when(_routerDao.findById(router.getId())).thenReturn(router);
        lenient().when(_routerDao.getRouterNetworks(router.getId())).thenReturn(List.of(9L));
        entriesBecomeCommands(router);
    }

    private void runningHere(DomainRouterVO... routers) {
        when(_routerDao.listByStateAndManagementServer(VirtualMachine.State.Running, ManagementServerNode.getManagementServerId())).thenReturn(List.of(routers));
    }

    @Test
    public void pendingDhcpEntriesAreReconciledFromTheDatabaseInOneShortBatch() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        NicVO present = guestNic();
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        String gone = "02:00:00:00:00:0b";
        VMInstanceDetailVO goneDetail = detail(7L, "dhcp.pending." + gone, "10.1.1.11,,1");
        VMInstanceDetailVO presentDetail = detail(7L, "dhcp.pending." + MAC, "10.1.1.10,,2");
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true"), detail(99L, "dhcp.pending", "true")));
        runningHere(running);
        when(_routerDao.findById(99L)).thenReturn(null);
        pendingOn(running, goneDetail, presentDetail);
        when(_nicDao.findByNetworkIdAndMacAddress(9L, gone)).thenReturn(null);
        when(_nicDao.findByNetworkIdAndMacAddress(9L, MAC)).thenReturn(present);
        when(_userVmDao.findById(5L)).thenReturn(vm);
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        Mockito.verify(_commandSetupHelper).createDhcpEntryRemovalCommand(Mockito.eq(running), Mockito.eq(gone), Mockito.eq("10.1.1.11"), Mockito.isNull(), any(Commands.class));
        Mockito.verify(_commandSetupHelper).createDhcpEntryCommand(Mockito.eq(running), Mockito.eq(vm), Mockito.eq(present), Mockito.eq(false), any(Commands.class));
        ArgumentCaptor<Commands> batch = ArgumentCaptor.forClass(Commands.class);
        Mockito.verify(_nwHelper).sendCommandsToRouter(Mockito.eq(running), batch.capture());
        Assert.assertEquals(2, batch.getValue().size());
        Assert.assertTrue(batch.getValue().stopOnError());
        for (Command cmd : batch.getValue()) {
            Assert.assertEquals(VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_RECONCILE_WAIT, cmd.getWait());
        }
        Mockito.verify(_vmDetailsDao).removeDetail(7L, "dhcp.pending");
        Mockito.verify(_vmDetailsDao).removeDetail(7L, goneDetail.getName(), goneDetail.getValue());
        Mockito.verify(_vmDetailsDao).removeDetail(7L, presentDetail.getName(), presentDetail.getValue());
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(7L, "dhcp.pending", "true", false);
        Mockito.verify(_vmDetailsDao).removeDetailsWithPrefix(99L, "dhcp.pending");
    }

    @Test
    public void aRouterThatGivesNoAnswersKeepsEveryPendingEntryAsItIs() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        NicVO present = guestNic();
        String gone = "02:00:00:00:00:0b";
        VMInstanceDetailVO goneDetail = detail(7L, "dhcp.pending." + gone, "10.1.1.11,,1");
        VMInstanceDetailVO presentDetail = detail(7L, "dhcp.pending." + MAC, "10.1.1.10,,2");
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true")));
        runningHere(running);
        pendingOn(running, goneDetail, presentDetail);
        when(_nicDao.findByNetworkIdAndMacAddress(9L, gone)).thenReturn(null);
        when(_nicDao.findByNetworkIdAndMacAddress(9L, MAC)).thenReturn(present);
        when(_userVmDao.findById(5L)).thenReturn(Mockito.mock(UserVmVO.class));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(false);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        ArgumentCaptor<Commands> batch = ArgumentCaptor.forClass(Commands.class);
        Mockito.verify(_nwHelper, Mockito.times(1)).sendCommandsToRouter(Mockito.eq(running), batch.capture());
        Assert.assertTrue(batch.getValue().stopOnError());
        Mockito.verify(_vmDetailsDao, Mockito.never()).removeDetail(Mockito.eq(7L), anyString(), anyString());
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(Mockito.eq(7L), Mockito.startsWith("dhcp.pending."), anyString(), Mockito.anyBoolean());
        Mockito.verify(_vmDetailsDao).addDetail(7L, "dhcp.pending", "true", false);
        Mockito.verifyNoInteractions(_alertMgr);
    }

    @Test
    public void aRouterIsGivenABoundedBatchAndTheRestStaysPending() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        int pendingCount = VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_RECONCILE_BATCH + 2;
        VMInstanceDetailVO[] records = new VMInstanceDetailVO[pendingCount];
        for (int i = 0; i < pendingCount; i++) {
            records[i] = detail(7L, String.format("dhcp.pending.02:00:00:00:01:%02x", i), String.format("10.1.2.%d,,%d", i + 1, i));
        }
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true")));
        runningHere(running);
        pendingOn(running, records);
        when(_nicDao.findByNetworkIdAndMacAddress(Mockito.eq(9L), anyString())).thenReturn(null);
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        ArgumentCaptor<Commands> batch = ArgumentCaptor.forClass(Commands.class);
        Mockito.verify(_nwHelper).sendCommandsToRouter(Mockito.eq(running), batch.capture());
        Assert.assertEquals(VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_RECONCILE_BATCH, batch.getValue().size());
        for (int i = 0; i < pendingCount; i++) {
            Mockito.verify(_vmDetailsDao, Mockito.times(i < VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_RECONCILE_BATCH ? 1 : 0))
                    .removeDetail(7L, records[i].getName(), records[i].getValue());
        }
        Mockito.verify(_vmDetailsDao).addDetail(7L, "dhcp.pending", "true", false);
    }

    private NicVO holder(String ipv4, String ipv6, VirtualMachine.Type type, String mac, Long vmId) {
        NicVO nic = Mockito.mock(NicVO.class);
        lenient().when(nic.getMacAddress()).thenReturn(mac);
        lenient().when(nic.getIPv4Address()).thenReturn(ipv4);
        lenient().when(nic.getIPv6Address()).thenReturn(ipv6);
        lenient().when(nic.getVmType()).thenReturn(type);
        lenient().when(nic.getInstanceId()).thenReturn(vmId);
        if (ipv4 != null) {
            lenient().when(_nicDao.findByIp4AddressAndNetworkId(ipv4, 9L)).thenReturn(nic);
        }
        return nic;
    }

    private UserVmVO guestIn(long id, VirtualMachine.State state) {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(_userVmDao.findById(id)).thenReturn(vm);
        return vm;
    }

    @Test
    public void aLateRemovalLeavesTheAddressToTheRunningGuestThatHoldsItNow() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        String gone = "02:00:00:00:00:a1";
        VMInstanceDetailVO goneDetail = detail(7L, "dhcp.pending." + gone, "10.1.1.50,,1");
        NicVO taken = holder("10.1.1.50", null, VirtualMachine.Type.User, "02:00:00:00:00:b2", 15L);
        guestIn(15L, VirtualMachine.State.Running);
        lenient().when(_nicDao.listByNetworkId(9L)).thenReturn(List.of(taken));
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true")));
        runningHere(running);
        pendingOn(running, goneDetail);
        when(_nicDao.findByNetworkIdAndMacAddress(Mockito.eq(9L), anyString())).thenReturn(null);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        Mockito.verify(_commandSetupHelper, Mockito.never()).createDhcpEntryRemovalCommand(any(), anyString(), nullable(String.class), nullable(String.class), any());
        Mockito.verify(_nwHelper, Mockito.never()).sendCommandsToRouter(any(), any());
        Mockito.verify(_vmDetailsDao).removeDetail(7L, goneDetail.getName(), goneDetail.getValue());
        Mockito.verify(_vmDetailsDao, Mockito.never()).addDetail(7L, "dhcp.pending", "true", false);
    }

    @Test
    public void aLateRemovalIsSentWhenNoRunningGuestHoldsTheAddress() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        VMInstanceDetailVO bySystemVm = detail(7L, "dhcp.pending.02:00:00:00:00:a1", "10.1.1.51,,1");
        VMInstanceDetailVO byIpv6Only = detail(7L, "dhcp.pending.02:00:00:00:00:a2", "10.1.1.52,fd00:1::52,2");
        VMInstanceDetailVO byStoppedGuest = detail(7L, "dhcp.pending.02:00:00:00:00:a3", "10.1.1.53,,3");
        NicVO consoleProxy = holder("10.1.1.51", null, VirtualMachine.Type.ConsoleProxy, "1e:00:00:00:00:c1", 21L);
        NicVO ipv6Holder = holder(null, "fd00:1::52", VirtualMachine.Type.User, "02:00:00:00:00:b2", 22L);
        NicVO stoppedHolder = holder("10.1.1.53", null, VirtualMachine.Type.User, "02:00:00:00:00:b3", 23L);
        guestIn(22L, VirtualMachine.State.Running);
        guestIn(23L, VirtualMachine.State.Stopped);
        lenient().when(_nicDao.listByNetworkId(9L)).thenReturn(List.of(consoleProxy, ipv6Holder, stoppedHolder));
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true")));
        runningHere(running);
        pendingOn(running, bySystemVm, byIpv6Only, byStoppedGuest);
        when(_nicDao.findByNetworkIdAndMacAddress(Mockito.eq(9L), anyString())).thenReturn(null);
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        Mockito.verify(_commandSetupHelper).createDhcpEntryRemovalCommand(Mockito.eq(running), Mockito.eq("02:00:00:00:00:a1"), Mockito.eq("10.1.1.51"), Mockito.isNull(), any(Commands.class));
        Mockito.verify(_commandSetupHelper).createDhcpEntryRemovalCommand(Mockito.eq(running), Mockito.eq("02:00:00:00:00:a2"), Mockito.eq("10.1.1.52"), Mockito.eq("fd00:1::52"), any(Commands.class));
        Mockito.verify(_commandSetupHelper).createDhcpEntryRemovalCommand(Mockito.eq(running), Mockito.eq("02:00:00:00:00:a3"), Mockito.eq("10.1.1.53"), Mockito.isNull(), any(Commands.class));
        for (VMInstanceDetailVO removed : List.of(bySystemVm, byIpv6Only, byStoppedGuest)) {
            Mockito.verify(_vmDetailsDao).removeDetail(7L, removed.getName(), removed.getValue());
        }
    }

    /** The router's details as rows in id order; a detail added again takes a new id, as the database gives it. */
    private Map<String, VMInstanceDetailVO> detailRows(long routerId) {
        Map<String, VMInstanceDetailVO> rows = new LinkedHashMap<>();
        long[] nextId = {1};
        lenient().doAnswer(invocation -> {
            String name = invocation.getArgument(1);
            VMInstanceDetailVO row = detail(routerId, name, invocation.getArgument(2));
            ReflectionTestUtils.setField(row, "id", nextId[0]++);
            rows.remove(name);
            rows.put(name, row);
            return null;
        }).when(_vmDetailsDao).addDetail(Mockito.eq(routerId), anyString(), anyString(), Mockito.anyBoolean());
        lenient().doAnswer(invocation -> {
            VMInstanceDetailVO row = rows.get((String) invocation.getArgument(1));
            if (row != null && row.getValue().equals(invocation.getArgument(2))) {
                rows.remove(row.getName());
            }
            return null;
        }).when(_vmDetailsDao).removeDetail(Mockito.eq(routerId), anyString(), anyString());
        lenient().doAnswer(invocation -> rows.remove((String) invocation.getArgument(1))).when(_vmDetailsDao).removeDetail(Mockito.eq(routerId), anyString());
        lenient().when(_vmDetailsDao.listDetails(routerId)).thenAnswer(invocation -> new ArrayList<>(rows.values()));
        lenient().when(_vmDetailsDao.findDetail(Mockito.eq(routerId), anyString())).thenAnswer(invocation -> rows.get((String) invocation.getArgument(1)));
        lenient().when(_vmDetailsDao.findDetails("dhcp.pending")).thenAnswer(invocation -> rows.containsKey("dhcp.pending") ? List.of(rows.get("dhcp.pending")) : List.of());
        return rows;
    }

    private int attemptsOf(VMInstanceDetailVO row) {
        String[] fields = row.getValue().split(",", -1);
        return fields.length > 3 ? Integer.parseInt(fields[3]) : 0;
    }

    @Test
    public void anEntryThatAlwaysFailsLetsTheOthersDrainAndIsLeftToTheRoutersNextStart() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        runningHere(running);
        lenient().when(_routerDao.getRouterNetworks(7L)).thenReturn(List.of(9L));
        Map<String, VMInstanceDetailVO> rows = detailRows(7L);
        String failing = "02:00:00:00:02:00";
        _vmDetailsDao.addDetail(7L, "dhcp.pending." + failing, "10.1.3.100,,0", false);
        for (int i = 1; i <= 11; i++) {
            _vmDetailsDao.addDetail(7L, String.format("dhcp.pending.02:00:00:00:02:%02x", i), "10.1.3." + i + ",,0", false);
        }
        _vmDetailsDao.addDetail(7L, "dhcp.pending", "true", false);
        when(_nicDao.findByNetworkIdAndMacAddress(Mockito.eq(9L), anyString())).thenReturn(null);
        List<String> batchMacs = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            batchMacs.add(invocation.getArgument(1));
            return addStandIn(invocation.getArgument(4));
        }).when(_commandSetupHelper).createDhcpEntryRemovalCommand(Mockito.eq(running), anyString(), nullable(String.class), nullable(String.class), any(Commands.class));
        int[] sends = {0};
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenAnswer(invocation -> {
            sends[0]++;
            Commands cmds = invocation.getArgument(1);
            Command[] sent = cmds.toCommands();
            Answer[] answers = new Answer[sent.length];
            boolean stopped = false;
            for (int i = 0; i < sent.length; i++) {
                boolean fails = !stopped && failing.equals(batchMacs.get(i));
                answers[i] = new Answer(sent[i], !stopped && !fails, stopped ? "Stopped by previous failure" : null);
                stopped = stopped || fails;
            }
            batchMacs.clear();
            cmds.setAnswers(answers);
            return !stopped;
        });

        for (int run = 0; run < 3; run++) {
            virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();
        }

        Assert.assertEquals(Set.of("dhcp.pending", "dhcp.pending." + failing), rows.keySet());
        Assert.assertEquals(2, attemptsOf(rows.get("dhcp.pending." + failing)));
        Mockito.verifyNoInteractions(_alertMgr);

        for (int run = 0; run < VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_ATTEMPTS; run++) {
            virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();
        }

        Assert.assertEquals(Set.of("dhcp.pending." + failing), rows.keySet());
        Assert.assertEquals(VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_ATTEMPTS, attemptsOf(rows.get("dhcp.pending." + failing)));
        Assert.assertEquals(VirtualNetworkApplianceManagerImpl.DHCP_ENTRY_ATTEMPTS + 1, sends[0]);
        Mockito.verify(_alertMgr, Mockito.times(1)).sendAlert(Mockito.eq(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER), Mockito.eq(1L), nullable(Long.class),
                Mockito.contains(failing), anyString());

        virtualNetworkApplianceManagerImpl.forgetAbandonedDhcpEntries(7L);

        Assert.assertEquals(Set.of(), rows.keySet());
    }

    @Test
    public void anEntryThatCannotBeBuiltIsSkippedAndAlertedOnceWhileTheOthersAreSent() throws Exception {
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        runningHere(running);
        lenient().when(_routerDao.getRouterNetworks(7L)).thenReturn(List.of(9L));
        Map<String, VMInstanceDetailVO> rows = detailRows(7L);
        _vmDetailsDao.addDetail(7L, "dhcp.pending." + MAC, "10.1.1.10,,0", false);
        _vmDetailsDao.addDetail(7L, "dhcp.pending.02:00:00:00:00:0b", "10.1.1.11,,0", false);
        _vmDetailsDao.addDetail(7L, "dhcp.pending.02:00:00:00:00:0c", "10.1.1.12,,0", false);
        _vmDetailsDao.addDetail(7L, "dhcp.pending", "true", false);
        NicVO present = guestNic();
        when(_nicDao.findByNetworkIdAndMacAddress(Mockito.eq(9L), anyString())).thenAnswer(invocation -> MAC.equals(invocation.getArgument(1)) ? present : null);
        when(_userVmDao.findById(5L)).thenReturn(Mockito.mock(UserVmVO.class));
        entriesBecomeCommands(running);
        Mockito.doThrow(new CloudRuntimeException("no control address")).when(_commandSetupHelper)
                .createDhcpEntryCommand(Mockito.eq(running), any(), Mockito.eq(present), Mockito.anyBoolean(), any(Commands.class));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();
        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        ArgumentCaptor<Commands> batch = ArgumentCaptor.forClass(Commands.class);
        Mockito.verify(_nwHelper, Mockito.times(1)).sendCommandsToRouter(Mockito.eq(running), batch.capture());
        Assert.assertEquals(2, batch.getValue().size());
        Assert.assertEquals(Set.of("dhcp.pending", "dhcp.pending." + MAC), rows.keySet());
        Assert.assertEquals(2, attemptsOf(rows.get("dhcp.pending." + MAC)));
        Mockito.verify(_alertMgr, Mockito.times(1)).sendAlert(Mockito.eq(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER), Mockito.eq(1L), nullable(Long.class),
                Mockito.contains(MAC), anyString());
    }

    @Test
    public void onlyRunningRoutersOnThisManagementServersHostsAreReconciled() throws Exception {
        when(_vmDetailsDao.findDetails("dhcp.pending")).thenReturn(List.of(detail(7L, "dhcp.pending", "true"), detail(8L, "dhcp.pending", "true")));
        DomainRouterVO stopped = router(7L, VirtualMachine.State.Stopped);
        DomainRouterVO elsewhere = router(8L, VirtualMachine.State.Running);
        lenient().when(_routerDao.findById(7L)).thenReturn(stopped);
        lenient().when(_routerDao.findById(8L)).thenReturn(elsewhere);
        runningHere();

        virtualNetworkApplianceManagerImpl.reconcileDhcpEntries();

        Mockito.verify(_vmDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), anyString());
        Mockito.verify(_vmDetailsDao, Mockito.never()).removeDetailsWithPrefix(Mockito.anyLong(), anyString());
        Mockito.verifyNoInteractions(_commandSetupHelper, _nwHelper);
    }

    @Test
    public void addingOrRemovingASecondaryAddressGivesTheRoutersTheNicsEntryAgain() throws Exception {
        NicSecondaryIpVO secondaryIp = Mockito.mock(NicSecondaryIpVO.class);
        when(secondaryIp.getNicId()).thenReturn(3L);
        NicVO nic = guestNic();
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(_nicDao.findById(3L)).thenReturn(nic);
        when(_userVmDao.findById(5L)).thenReturn(vm);
        guestNetwork(9L, Network.GuestType.Shared);
        DomainRouterVO running = router(7L, VirtualMachine.State.Running);
        when(_routerDao.findByNetwork(9L)).thenReturn(List.of(running));
        when(_nwHelper.sendCommandsToRouter(Mockito.eq(running), any(Commands.class))).thenReturn(true);

        virtualNetworkApplianceManagerImpl.refreshDhcpEntryOfSecondaryIp(secondaryIp);

        Mockito.verify(_commandSetupHelper).createDhcpEntryCommand(Mockito.eq(running), Mockito.eq(vm), Mockito.eq(nic), Mockito.eq(false), any(Commands.class));
    }
}
