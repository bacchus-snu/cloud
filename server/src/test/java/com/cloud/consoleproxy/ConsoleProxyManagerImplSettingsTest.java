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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.security.keystore.KeystoreDao;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.apache.cloudstack.framework.security.keystore.KeystoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.StoragePoolStatus;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.ConsoleProxyDao;

@RunWith(MockitoJUnitRunner.class)
public class ConsoleProxyManagerImplSettingsTest {
    private static final String STATE = ConsoleProxyManager.ConsoleProxyServiceManagementState.key();
    private static final String LAST_STATE = ConsoleProxyManager.ConsoleProxyManagementLastState.key();
    private static final long ZONE_ID = 1L;

    @InjectMocks
    private ConsoleProxyManagerImpl consoleProxyManager;

    @Mock
    private ConsoleProxyDao consoleProxyDao;
    @Mock
    private ConfigurationDao configurationDao;
    @Mock
    private ConfigDepot configDepot;
    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private KeystoreDao keystoreDao;
    @Mock
    private KeystoreManager keystoreManager;
    @Mock
    private AgentHook agentHook;

    private final Map<String, String> globalSettings = new HashMap<>();
    private final Map<String, String> zoneSettings = new HashMap<>();

    @Before
    public void setUp() throws ReflectiveOperationException {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Global), eq(null)))
                .thenAnswer(invocation -> globalSettings.get(invocation.<String>getArgument(0)));
        lenient().when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Zone), eq(ZONE_ID)))
                .thenAnswer(invocation -> zoneSettings.get(invocation.<String>getArgument(0)));
        ConfigKey.init(depot);
        forgetCachedStates();
        lenient().when(configurationDao.update(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> globalSettings.put(invocation.getArgument(0), invocation.getArgument(2)) != null);
        ReflectionTestUtils.setField(consoleProxyManager, "agentHook", agentHook);
    }

    @After
    public void tearDown() throws ReflectiveOperationException {
        ConfigKey.init(null);
        forgetCachedStates();
    }

    private static void forgetCachedStates() throws ReflectiveOperationException {
        Field value = ConfigKey.class.getDeclaredField("_value");
        value.setAccessible(true);
        value.set(ConsoleProxyManager.ConsoleProxyServiceManagementState, null);
        value.set(ConsoleProxyManager.ConsoleProxyManagementLastState, null);
    }

    private ConsoleProxyVO runningProxy() {
        ConsoleProxyVO proxy = mock(ConsoleProxyVO.class);
        when(proxy.getUuid()).thenReturn("proxy-uuid");
        when(consoleProxyDao.findById(7L)).thenReturn(proxy);
        when(proxy.getId()).thenReturn(7L);
        when(consoleProxyDao.getProxyListInStates(State.Running)).thenReturn(List.of(proxy), List.of());
        return proxy;
    }

    private void upPrimaryStorage() {
        when(primaryDataStoreDao.listByStatus(StoragePoolStatus.Up)).thenReturn(List.of(mock(StoragePoolVO.class)));
    }

    @Test
    public void aRunningServerCarriesAResetThroughAndReturnsToTheLastState() throws Exception {
        globalSettings.put(STATE, "Auto");
        globalSettings.put(LAST_STATE, "Auto");
        upPrimaryStorage();
        Assert.assertTrue(consoleProxyManager.canScan());

        globalSettings.put(STATE, "ResetSuspending");
        runningProxy();

        Assert.assertTrue(consoleProxyManager.canScan());
        verify(virtualMachineManager).stop("proxy-uuid");
        Assert.assertEquals("Auto", globalSettings.get(STATE));
        verify(configDepot).invalidateConfigCache(STATE, ConfigKey.Scope.Global, null);
    }

    @Test
    public void aServerStartedDuringAResetCarriesItThroughAndReservesCapacityAgain() throws Exception {
        globalSettings.put(STATE, "ResetSuspending");
        globalSettings.put(LAST_STATE, "Auto");
        runningProxy();
        upPrimaryStorage();

        Assert.assertTrue(consoleProxyManager.canScan());
        Assert.assertTrue(consoleProxyManager.canScan());
        verify(virtualMachineManager, times(1)).stop("proxy-uuid");
        Assert.assertEquals("Auto", globalSettings.get(STATE));
    }

    @Test
    public void aResetIsNeverTheStateToReturnTo() {
        globalSettings.put(STATE, "ResetSuspending");
        globalSettings.put(LAST_STATE, "ResetSuspending");

        consoleProxyManager.canScan();
        Assert.assertEquals("Auto", globalSettings.get(STATE));

        consoleProxyManager.setManagementState(ConsoleProxyManagementState.ResetSuspending);
        consoleProxyManager.setManagementState(ConsoleProxyManagementState.Manual);
        Assert.assertEquals("Manual", globalSettings.get(STATE));
        Assert.assertEquals("Auto", globalSettings.get(LAST_STATE));
    }

    private void sslZone() {
        zoneSettings.put(ConsoleProxyManager.ConsoleProxySslEnabled.key(), "true");
        zoneSettings.put(ConsoleProxyManager.ConsoleProxyUrlDomain.key(), "console.example.org");
    }

    @Test
    public void theConsoleWebSocketPortIsTheZoneSetting() {
        sslZone();
        when(keystoreDao.findByName(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn(mock(KeystoreVO.class));
        Assert.assertEquals(8443, consoleProxyManager.getVncPort(ZONE_ID));

        zoneSettings.put(ConsoleProxyManager.NoVncConsoleWebSocketPort.key(), "443");
        Assert.assertEquals(443, consoleProxyManager.getVncPort(ZONE_ID));

        zoneSettings.put(ConsoleProxyManager.ConsoleProxySslEnabled.key(), "false");
        Assert.assertEquals(8080, consoleProxyManager.getVncPort(ZONE_ID));
    }

    @Test
    public void theStoredCertificateIsSentToTheConnectedProxiesOfZonesThatServeIt() {
        when(agentHook.startAgentHttpHandlers()).thenReturn(List.of("v-1-VM"));
        Assert.assertEquals(List.of("v-1-VM"), consoleProxyManager.propagateServerCertificate());

        Assert.assertNull(consoleProxyManager.getServerCertificateFingerprint(ZONE_ID));
        verify(keystoreManager, never()).getCertificateFingerprint(anyString());

        sslZone();
        when(keystoreManager.getCertificateFingerprint(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn("AB:CD");
        Assert.assertEquals("AB:CD", consoleProxyManager.getServerCertificateFingerprint(ZONE_ID));
    }
}
