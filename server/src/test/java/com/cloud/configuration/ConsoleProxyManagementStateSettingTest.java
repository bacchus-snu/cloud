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
package com.cloud.configuration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.consoleproxy.ConsoleProxyManagerImpl;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.user.User;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.ConsoleProxyDao;

/**
 * A console proxy reset entered through the setting, as an operator does, carried through by the console proxy manager.
 */
@RunWith(MockitoJUnitRunner.class)
public class ConsoleProxyManagementStateSettingTest {
    private static final String STATE = ConsoleProxyManager.ConsoleProxyServiceManagementState.key();
    private static final String LAST_STATE = ConsoleProxyManager.ConsoleProxyManagementLastState.key();

    @Mock
    private ConfigurationDao configurationDao;
    @Mock
    private ConfigDepot configDepot;
    @Mock
    private MessageBus messageBus;
    @Mock
    private ConsoleProxyDao consoleProxyDao;
    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;

    @Spy
    @InjectMocks
    private ConfigurationManagerImpl configurationManager = new ConfigurationManagerImpl();

    @InjectMocks
    private ConsoleProxyManagerImpl consoleProxyManager;

    private final Map<String, String> globalSettings = new HashMap<>();

    @Before
    public void setUp() throws ReflectiveOperationException {
        ConfigDepotImpl depot = mock(ConfigDepotImpl.class);
        lenient().when(depot.getConfigStringValue(anyString(), eq(ConfigKey.Scope.Global), eq(null)))
                .thenAnswer(invocation -> globalSettings.get(invocation.<String>getArgument(0)));
        ConfigKey.init(depot);
        forgetCachedStates();
        lenient().when(configurationDao.getValue(anyString())).thenAnswer(invocation -> globalSettings.get(invocation.<String>getArgument(0)));
        lenient().when(configurationDao.update(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> globalSettings.put(invocation.getArgument(0), invocation.getArgument(2)) != null);
        doReturn(null).when(configurationManager).validateConfigurationValue(STATE, "ResetSuspending", null);

        ConsoleProxyVO proxy = mock(ConsoleProxyVO.class);
        when(proxy.getId()).thenReturn(7L);
        when(proxy.getUuid()).thenReturn("proxy-uuid");
        when(consoleProxyDao.findById(7L)).thenReturn(proxy);
        when(consoleProxyDao.getProxyListInStates(State.Running)).thenReturn(List.of(proxy), List.of());
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

    private void resetThroughTheSetting() {
        try (TransactionLegacy txn = TransactionLegacy.open("resetThroughTheSetting")) {
            configurationManager.updateConfiguration(User.UID_SYSTEM, STATE, ConsoleProxyManager.ConsoleProxyServiceManagementState.category(),
                    "ResetSuspending", null, null);
        }
    }

    @Test
    public void aResetFromManualReturnsToManual() throws Exception {
        globalSettings.put(STATE, "Manual");
        globalSettings.put(LAST_STATE, "Auto");

        resetThroughTheSetting();
        Assert.assertFalse(consoleProxyManager.canScan());

        verify(virtualMachineManager).stop("proxy-uuid");
        Assert.assertEquals("Manual", globalSettings.get(STATE));
    }

    @Test
    public void aResetFromAutoReturnsToAutoWhateverTheLastStateWas() throws Exception {
        globalSettings.put(STATE, "Auto");
        globalSettings.put(LAST_STATE, "Manual");
        when(primaryDataStoreDao.listByStatus(StoragePoolStatus.Up)).thenReturn(List.of(mock(StoragePoolVO.class)));

        resetThroughTheSetting();
        Assert.assertTrue(consoleProxyManager.canScan());

        verify(virtualMachineManager).stop("proxy-uuid");
        Assert.assertEquals("Auto", globalSettings.get(STATE));
    }
}
