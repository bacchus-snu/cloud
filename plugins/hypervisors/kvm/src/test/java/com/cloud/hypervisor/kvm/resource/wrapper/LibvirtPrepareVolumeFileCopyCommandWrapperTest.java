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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

import org.apache.cloudstack.utils.security.CertUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.cloud.agent.api.storage.PrepareVolumeFileCopyAnswer;
import com.cloud.agent.api.storage.PrepareVolumeFileCopyCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.FakeProcessRunner;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransfer;
import com.cloud.hypervisor.kvm.storage.VolumeFileTransferTest;

public class LibvirtPrepareVolumeFileCopyCommandWrapperTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tlsDir;
    private LibvirtComputingResource resource;
    private LibvirtPrepareVolumeFileCopyCommandWrapper wrapper;

    @BeforeClass
    public static void addProvider() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Before
    public void setUp() throws Exception {
        tlsDir = folder.newFolder("qemu").toPath();
        Files.write(tlsDir.resolve("client-cert.pem"),
                CertUtils.x509CertificateToPem(VolumeFileTransferTest.hostCertificate("dst.example.org")).getBytes(StandardCharsets.UTF_8));
        Files.write(tlsDir.resolve("client-key.pem"), new byte[] {1});
        Files.write(tlsDir.resolve("ca-cert.pem"), new byte[] {1});
        resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.isHostSecured()).thenReturn(true);
        wrapper = Mockito.spy(new LibvirtPrepareVolumeFileCopyCommandWrapper());
        Mockito.doReturn(new VolumeFileTransfer(new FakeProcessRunner(command -> null), tlsDir, tlsDir.resolve("state"))).when(wrapper).getTransfer();
    }

    @Test
    public void prepareReportsTheIdentityOfTheClientCertificate() {
        PrepareVolumeFileCopyAnswer answer = (PrepareVolumeFileCopyAnswer)wrapper.execute(new PrepareVolumeFileCopyCommand(), resource);
        assertTrue(answer.getDetails(), answer.getResult());
        assertEquals("CN=dst.example.org,OU=cloudstack,O=cloudstack,C=cloudstack", answer.getClientIdentity());
    }

    @Test
    public void nonTlsRefused() {
        Mockito.when(resource.isHostSecured()).thenReturn(false);
        PrepareVolumeFileCopyAnswer answer = (PrepareVolumeFileCopyAnswer)wrapper.execute(new PrepareVolumeFileCopyCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("only over TLS"));
    }

    @Test
    public void missingClientCertificateRefused() throws Exception {
        Files.delete(tlsDir.resolve("client-cert.pem"));
        PrepareVolumeFileCopyAnswer answer = (PrepareVolumeFileCopyAnswer)wrapper.execute(new PrepareVolumeFileCopyCommand(), resource);
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails(), answer.getDetails().contains("client-cert.pem"));
    }
}
