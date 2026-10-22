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
package org.apache.cloudstack.framework.security.keystore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.cert.X509Certificate;

import org.apache.cloudstack.utils.security.CertUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.security.CertificateHelper;

@RunWith(MockitoJUnitRunner.class)
public class KeystoreManagerImplTest {

    @Mock
    private KeystoreDao keystoreDao;

    @InjectMocks
    private KeystoreManagerImpl keystoreManager;

    private static X509Certificate certificate(KeyPair keyPair) throws Exception {
        return CertUtils.generateV1Certificate(keyPair, "CN=console.example.org", "CN=console.example.org", 1, "SHA256WithRSA");
    }

    private static String privateKeyPem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.encodeBase64String(keyPair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
    }

    @Test
    public void theFingerprintIsTheStoredCertificatesSha256() throws Exception {
        X509Certificate certificate = certificate(CertUtils.generateRandomKeyPair(2048));
        KeystoreVO stored = mock(KeystoreVO.class);
        when(stored.getCertificate()).thenReturn(CertUtils.x509CertificateToPem(certificate));
        when(keystoreDao.findByName("CPVMCertificate")).thenReturn(stored);

        Assert.assertEquals(CertificateHelper.generateFingerPrint(certificate), keystoreManager.getCertificateFingerprint("CPVMCertificate"));
        Assert.assertNull(keystoreManager.getCertificateFingerprint("missing"));
    }

    @Test
    public void aKeyThatDoesNotBelongToTheCertificateIsRefused() throws Exception {
        KeyPair keyPair = CertUtils.generateRandomKeyPair(2048);
        KeyPair otherKeyPair = CertUtils.generateRandomKeyPair(2048);
        String certificatePem = CertUtils.x509CertificateToPem(certificate(keyPair));

        Assert.assertTrue(keystoreManager.validateCertificate(certificatePem, privateKeyPem(keyPair), "console.example.org").first());
        Assert.assertFalse(keystoreManager.validateCertificate(certificatePem, privateKeyPem(otherKeyPair), "console.example.org").first());
    }
}
