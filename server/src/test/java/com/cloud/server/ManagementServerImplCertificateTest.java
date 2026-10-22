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
package com.cloud.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.util.List;

import org.apache.cloudstack.api.command.admin.resource.UploadCustomCertificateCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVmCertificatesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SystemVmCertificateResponse;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.apache.cloudstack.utils.security.CertUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.utils.Pair;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.ConsoleProxyDao;
import com.cloud.vm.dao.SecondaryStorageVmDao;

@RunWith(MockitoJUnitRunner.class)
public class ManagementServerImplCertificateTest {

    @Mock
    private KeystoreManager keystoreManager;
    @Mock
    private ConsoleProxyManager consoleProxyManager;
    @Mock
    private SecondaryStorageVmManager secondaryStorageVmManager;
    @Mock
    private SecondaryStorageVmDao secondaryStorageVmDao;
    @Mock
    private ConsoleProxyDao consoleProxyDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private DataCenterDao dataCenterDao;

    @Spy
    @InjectMocks
    private ManagementServerImpl managementServer = new ManagementServerImpl();

    private static String certificatePem() throws Exception {
        KeyPair keyPair = CertUtils.generateRandomKeyPair(2048);
        return CertUtils.x509CertificateToPem(CertUtils.generateV1Certificate(keyPair, "CN=console.example.org", "CN=console.example.org", 1, "SHA256WithRSA"));
    }

    @Test
    public void aServerCertificateUploadIsLoadedByTheRunningSystemVmsWithoutAReboot() {
        UploadCustomCertificateCmd cmd = mock(UploadCustomCertificateCmd.class);
        when(cmd.getPrivateKey()).thenReturn("key");
        when(cmd.getCertificate()).thenReturn("certificate");
        when(cmd.getDomainSuffix()).thenReturn("example.org");
        when(keystoreManager.validateCertificate("certificate", "key", "example.org")).thenReturn(new Pair<>(true, null));
        SecondaryStorageVmVO runningSsvm = mock(SecondaryStorageVmVO.class);
        lenient().when(secondaryStorageVmDao.getSecStorageVmListInStates(null, State.Running, State.Migrating, State.Starting)).thenReturn(List.of(runningSsvm));
        when(consoleProxyManager.propagateServerCertificate()).thenReturn(List.of("v-1-VM"));
        when(secondaryStorageVmManager.propagateServerCertificate()).thenReturn(List.of("s-2-VM"));

        String reply = managementServer.uploadCertificate(cmd);

        verify(keystoreManager).saveCertificate(ConsoleProxyManager.CERTIFICATE_NAME, "certificate", "key", "example.org");
        verify(secondaryStorageVmManager, never()).rebootSecStorageVm(anyLong());
        verify(consoleProxyManager, never()).setManagementState(any());
        verify(consoleProxyManager).propagateServerCertificate();
        verify(secondaryStorageVmManager).propagateServerCertificate();
        Assert.assertTrue(reply, reply.contains("v-1-VM") && reply.contains("s-2-VM"));
    }

    @Test
    public void aChainCertificateIsStoredUntilTheServerCertificateCompletesTheUpload() throws Exception {
        String chainCertificate = certificatePem();
        UploadCustomCertificateCmd cmd = mock(UploadCustomCertificateCmd.class);
        when(cmd.getAlias()).thenReturn("chain-1");
        when(cmd.getCertIndex()).thenReturn(1);
        when(cmd.getCertificate()).thenReturn(chainCertificate);
        when(cmd.getDomainSuffix()).thenReturn("example.org");

        managementServer.uploadCertificate(cmd);

        verify(keystoreManager).saveCertificate("chain-1", chainCertificate, 1, "example.org");
        verify(consoleProxyManager, never()).setManagementState(any());
        verify(consoleProxyManager, never()).propagateServerCertificate();
        verify(secondaryStorageVmManager, never()).propagateServerCertificate();
    }

    @Test
    public void eachRunningSystemVmIsListedWithTheCertificateItServesAndTheOneItShould() {
        ConsoleProxyVO proxy = mock(ConsoleProxyVO.class);
        when(proxy.getUuid()).thenReturn("proxy-uuid");
        when(proxy.getHostName()).thenReturn("v-1-VM");
        when(proxy.getDataCenterId()).thenReturn(1L);
        when(consoleProxyDao.getProxyListInStates(State.Running)).thenReturn(List.of(proxy));
        SecondaryStorageVmVO ssvm = mock(SecondaryStorageVmVO.class);
        when(ssvm.getUuid()).thenReturn("ssvm-uuid");
        when(ssvm.getHostName()).thenReturn("s-2-VM");
        when(ssvm.getDataCenterId()).thenReturn(1L);
        when(secondaryStorageVmDao.getSecStorageVmListInStates(null, State.Running)).thenReturn(List.of(ssvm));
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(dataCenterDao.findById(1L)).thenReturn(zone);
        HostVO proxyAgent = mock(HostVO.class);
        when(proxyAgent.getId()).thenReturn(11L);
        when(proxyAgent.getStatus()).thenReturn(Status.Up);
        when(hostDao.findByTypeNameAndZoneId(1L, "v-1-VM", Host.Type.ConsoleProxy)).thenReturn(proxyAgent);
        when(hostDetailsDao.findDetail(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT)).thenReturn(new DetailVO(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "OLD"));
        when(consoleProxyManager.getServerCertificateFingerprint(1L)).thenReturn("NEW");
        when(secondaryStorageVmManager.getServerCertificateFingerprint(1L)).thenReturn(null);

        ListSystemVmCertificatesCmd cmd = mock(ListSystemVmCertificatesCmd.class);
        when(cmd.getZoneId()).thenReturn(null);

        ListResponse<SystemVmCertificateResponse> response = managementServer.listSystemVmCertificates(cmd);

        Assert.assertEquals(2, response.getCount().intValue());
        SystemVmCertificateResponse proxyRow = response.getResponses().get(0);
        Assert.assertEquals("proxy-uuid", proxyRow.getId());
        Assert.assertEquals("v-1-VM", proxyRow.getName());
        Assert.assertEquals("consoleproxy", proxyRow.getSystemVmType());
        Assert.assertEquals("zone-uuid", proxyRow.getZoneId());
        Assert.assertEquals("Up", proxyRow.getAgentState());
        Assert.assertEquals("OLD", proxyRow.getFingerprint());
        Assert.assertEquals("NEW", proxyRow.getStoredFingerprint());
        SystemVmCertificateResponse ssvmRow = response.getResponses().get(1);
        Assert.assertEquals("secondarystoragevm", ssvmRow.getSystemVmType());
        Assert.assertNull(ssvmRow.getAgentState());
        Assert.assertNull(ssvmRow.getFingerprint());
        Assert.assertNull(ssvmRow.getStoredFingerprint());
        verify(hostDao).findByTypeNameAndZoneId(eq(1L), eq("s-2-VM"), eq(Host.Type.SecondaryStorageVM));
    }
}
