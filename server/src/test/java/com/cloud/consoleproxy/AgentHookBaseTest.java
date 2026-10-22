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
package com.cloud.consoleproxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.consoleproxy.ConsoleAccessManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.security.keys.KeysManager;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ServerCertificateFingerprint;
import com.cloud.agent.api.StartupProxyCommand;
import com.cloud.agent.api.proxy.StartConsoleProxyAgentHttpHandlerCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class AgentHookBaseTest {
    private static final long ZONE_ID = 1L;
    private static final String SIXTEEN_BYTES = "AAAAAAAAAAAAAAAAAAAAAA==";

    @Mock
    private VMInstanceDao instanceDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private ConfigurationDao configurationDao;
    @Mock
    private KeystoreManager keystoreManager;
    @Mock
    private AgentManager agentManager;
    @Mock
    private KeysManager keysManager;
    @Mock
    private ConsoleAccessManager consoleAccessManager;

    private HostVO proxyHost;
    private AgentHookBase hook;
    private final Map<String, String> zoneSettings = new HashMap<>();

    @Before
    public void setUp() {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Zone), eq(ZONE_ID)))
                .thenAnswer(invocation -> zoneSettings.get(invocation.<String>getArgument(0)));
        ConfigKey.init(depot);
        zoneSettings.put(ConsoleProxyManager.ConsoleProxySslEnabled.key(), "true");
        zoneSettings.put(ConsoleProxyManager.ConsoleProxyUrlDomain.key(), "console.example.org");

        proxyHost = proxyHost(11L, Status.Up);
        hook = new AgentHookBase(instanceDao, hostDao, hostDetailsDao, configurationDao, keystoreManager, agentManager, keysManager, consoleAccessManager) {
            @Override
            protected HostVO findConsoleProxyHost(StartupProxyCommand cmd) {
                return proxyHost;
            }
        };
        lenient().when(keysManager.getEncryptionKey()).thenReturn(SIXTEEN_BYTES);
        lenient().when(keysManager.getEncryptionIV()).thenReturn(SIXTEEN_BYTES);
        lenient().when(keystoreManager.getKeystoreBits(eq(ConsoleProxyManager.CERTIFICATE_NAME), eq(ConsoleProxyManager.CERTIFICATE_NAME), anyString()))
                .thenReturn(new byte[] {1, 2, 3});
    }

    @After
    public void tearDown() {
        ConfigKey.init(null);
    }

    private static HostVO proxyHost(long id, Status status) {
        return proxyHost(id, status, ZONE_ID);
    }

    private static HostVO proxyHost(long id, Status status, long zoneId) {
        HostVO host = mock(HostVO.class);
        lenient().when(host.getId()).thenReturn(id);
        lenient().when(host.getName()).thenReturn("v-" + id + "-VM");
        lenient().when(host.getDataCenterId()).thenReturn(zoneId);
        lenient().when(host.getStatus()).thenReturn(status);
        return host;
    }

    @Test
    public void theCertificateAConnectingProxyReportsServingIsRecorded() throws Exception {
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "AA:BB"));

        hook.startAgentHttpHandlerInVM(mock(StartupProxyCommand.class));

        ArgumentCaptor<StartConsoleProxyAgentHttpHandlerCommand> sent = ArgumentCaptor.forClass(StartConsoleProxyAgentHttpHandlerCommand.class);
        verify(agentManager).send(eq(11L), sent.capture());
        Assert.assertArrayEquals(new byte[] {1, 2, 3}, sent.getValue().getKeystoreBits());
        verify(hostDetailsDao).setDetail(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "AA:BB");
    }

    @Test
    public void aProxyThatReportsNoCertificateLeavesItUnknown() throws Exception {
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> new Answer(invocation.getArgument(1)));

        hook.startAgentHttpHandler(proxyHost);

        verify(hostDetailsDao).setDetail(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, null);
    }

    @Test
    public void theStoredCertificateIsSentToEveryConnectedProxyThatServesItAndTheUnconfirmedAreNamed() throws Exception {
        HostVO connecting = proxyHost(12L, Status.Connecting);
        HostVO disconnected = proxyHost(13L, Status.Disconnected);
        HostVO plain = proxyHost(14L, Status.Up, 2L);
        when(hostDao.listByType(Host.Type.ConsoleProxy)).thenReturn(List.of(proxyHost, connecting, disconnected, plain));
        when(keystoreManager.getCertificateFingerprint(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn("CC:DD");
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "CC:DD"));
        when(agentManager.send(eq(12L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), false, "no private key", "AA:BB"));

        Assert.assertEquals(List.of("v-12-VM"), hook.startAgentHttpHandlers());

        verify(hostDetailsDao).setDetail(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "CC:DD");
        verify(hostDetailsDao).setDetail(12L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "AA:BB");
        verify(agentManager, never()).send(eq(13L), any(StartConsoleProxyAgentHttpHandlerCommand.class));
        verify(agentManager, never()).send(eq(14L), any(StartConsoleProxyAgentHttpHandlerCommand.class));
    }

    @Test
    public void aSendWithoutAnAnswerKeepsTheLastReport() throws Exception {
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class))).thenThrow(new AgentUnavailableException(11L));

        Assert.assertNull(hook.startAgentHttpHandler(proxyHost));

        verify(hostDetailsDao, never()).setDetail(anyLong(), anyString(), any());
    }

    @Test
    public void theHandlerCommandWaitsLongerThanTheAgentWaitsForAFirstStart() throws Exception {
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "AA:BB"));

        hook.startAgentHttpHandler(proxyHost);

        ArgumentCaptor<StartConsoleProxyAgentHttpHandlerCommand> sent = ArgumentCaptor.forClass(StartConsoleProxyAgentHttpHandlerCommand.class);
        verify(agentManager).send(eq(11L), sent.capture());
        Assert.assertTrue(sent.getValue().getWait() > StartConsoleProxyAgentHttpHandlerCommand.START_WAIT_SECONDS);
    }

    @Test
    public void aConnectThatMissedTheStoredCertificateIsSentItOnceMore() throws Exception {
        when(keystoreManager.getCertificateFingerprint(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn("NEW");
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "OLD"))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "NEW"));

        hook.startAgentHttpHandlerInVM(mock(StartupProxyCommand.class));

        verify(agentManager, times(2)).send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class));
        verify(hostDetailsDao).setDetail(11L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "NEW");
    }

    @Test
    public void aConnectThatServesTheStoredCertificateIsSentItOnce() throws Exception {
        when(keystoreManager.getCertificateFingerprint(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn("NEW");
        when(agentManager.send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "NEW"));

        hook.startAgentHttpHandlerInVM(mock(StartupProxyCommand.class));

        verify(agentManager, times(1)).send(eq(11L), any(StartConsoleProxyAgentHttpHandlerCommand.class));
    }
}
