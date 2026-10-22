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
package org.apache.cloudstack.secondarystorage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.SecStorageCertificateCommand;
import com.cloud.agent.api.SecStorageSetupAnswer;
import com.cloud.agent.api.SecStorageSetupCommand;
import com.cloud.agent.api.ServerCertificateFingerprint;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.storage.ImageStoreDetailsUtil;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.dao.SecondaryStorageVmDao;

@RunWith(MockitoJUnitRunner.class)
public class SecondaryStorageManagerImplCertificateTest {

    @Mock
    private AgentManager agentManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private KeystoreManager keystoreManager;
    @Mock
    private SecondaryStorageVmDao secondaryStorageVmDao;
    @Mock
    private DataStoreManager dataStoreManager;
    @Mock
    private ImageStoreDetailsUtil imageStoreDetailsUtil;
    @Mock
    private ConfigurationDao configurationDao;

    @Spy
    @InjectMocks
    private SecondaryStorageManagerImpl secondaryStorageManager;

    private static HostVO ssvmHost(long id) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        lenient().when(host.getName()).thenReturn("s-" + id + "-VM");
        return host;
    }

    @Test
    public void theStoredCertificateIsSentToEveryConnectedSecondaryStorageVmThatServesIt() {
        ReflectionTestUtils.setField(secondaryStorageManager, "_useSSlCopy", true);
        KeystoreManager.Certificates certs = new KeystoreManager.Certificates("key", "certificate", null, null);
        when(keystoreManager.getCertificates(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn(certs);
        when(keystoreManager.getCertificateFingerprint(ConsoleProxyManager.CERTIFICATE_NAME)).thenReturn("AA:BB");
        HostVO first = ssvmHost(21L);
        HostVO second = ssvmHost(22L);
        HostVO third = ssvmHost(23L);
        doReturn(List.of(first, second, third)).when(secondaryStorageManager).listUpAndConnectingSecondaryStorageVmHost(null);
        when(agentManager.easySend(eq(21L), any(SecStorageCertificateCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), true, null, "AA:BB"));
        when(agentManager.easySend(eq(22L), any(SecStorageCertificateCommand.class))).thenReturn(null);
        when(agentManager.easySend(eq(23L), any(SecStorageCertificateCommand.class)))
                .thenAnswer(invocation -> ServerCertificateFingerprint.answer(invocation.getArgument(1), false, "reload failed", null));

        Assert.assertEquals(List.of("s-22-VM", "s-23-VM"), secondaryStorageManager.propagateServerCertificate());

        ArgumentCaptor<Command> sent = ArgumentCaptor.forClass(Command.class);
        verify(agentManager).easySend(eq(21L), sent.capture());
        Assert.assertSame(certs, ((SecStorageCertificateCommand)sent.getValue()).getCerts());
        verify(hostDetailsDao).setDetail(21L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "AA:BB");
        verify(hostDetailsDao, never()).setDetail(eq(22L), anyString(), any());
        verify(hostDetailsDao).setDetail(23L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, null);
        Assert.assertEquals("AA:BB", secondaryStorageManager.getServerCertificateFingerprint(1L));
    }

    @Test
    public void secondaryStorageVmsThatDoNotServeTheStoredCertificateAreLeftAlone() {
        ReflectionTestUtils.setField(secondaryStorageManager, "_useSSlCopy", false);

        Assert.assertEquals(List.of(), secondaryStorageManager.propagateServerCertificate());

        verify(agentManager, never()).easySend(anyLong(), any(Command.class));
        Assert.assertNull(secondaryStorageManager.getServerCertificateFingerprint(1L));
    }

    @Test
    public void theCertificateAConnectingSecondaryStorageVmReportsServingIsRecorded() {
        HostVO host = mock(HostVO.class);
        when(host.getType()).thenReturn(Host.Type.SecondaryStorageVM);
        when(host.getName()).thenReturn("s-2-VM");
        when(host.getDataCenterId()).thenReturn(1L);
        when(hostDao.findById(21L)).thenReturn(host);
        when(secondaryStorageVmDao.findByInstanceName("s-2-VM")).thenReturn(mock(SecondaryStorageVmVO.class));
        DataStore store = mock(DataStore.class);
        when(store.getTO()).thenReturn(mock(NfsTO.class));
        when(store.getUri()).thenReturn("nfs://storage/export");
        when(dataStoreManager.getImageStoresByScope(any())).thenReturn(List.of(store));
        SecStorageSetupAnswer answer = new SecStorageSetupAnswer(null);
        answer.setCertificateFingerprint("EE:FF");
        when(agentManager.easySend(eq(21L), any(SecStorageSetupCommand.class))).thenReturn(answer);

        Assert.assertTrue(secondaryStorageManager.generateSetupCommand(21L));

        verify(hostDetailsDao).setDetail(21L, Host.HOST_SERVER_CERTIFICATE_FINGERPRINT, "EE:FF");
    }
}
