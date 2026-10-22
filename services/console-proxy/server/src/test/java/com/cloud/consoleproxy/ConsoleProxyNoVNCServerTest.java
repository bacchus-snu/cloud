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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.utils.security.CertUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.cloud.utils.security.CertificateHelper;

public class ConsoleProxyNoVNCServerTest {
    private static final String PASSWORD = "password";

    private ConsoleProxyNoVNCServer server;
    private int port;

    private static final class KeyStoreBits {
        private final byte[] bits;
        private final String fingerprint;

        private KeyStoreBits(String name) throws Exception {
            KeyPair keyPair = CertUtils.generateRandomKeyPair(2048);
            X509Certificate certificate = CertUtils.generateV1Certificate(keyPair, "CN=" + name, "CN=" + name, 1, "SHA256WithRSA");
            bits = CertificateHelper.buildAndSaveKeystore("CPVMCertificate", CertUtils.x509CertificateToPem(certificate),
                    Base64.encodeBase64String(keyPair.getPrivate().getEncoded()), PASSWORD);
            fingerprint = CertificateHelper.generateFingerPrint(certificate);
        }
    }

    @Before
    public void choosePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
    }

    @After
    public void stopServer() throws Exception {
        ConsoleProxy.noVNCServer = null;
        if (server != null) {
            server.stop();
        }
    }

    private void startServer(KeyStoreBits keyStore) throws Exception {
        ConsoleProxyHttpHandlerAdapter pages = new ConsoleProxyHttpHandlerAdapter();
        pages.createContext("/resource/", exchange -> {
            byte[] body = "page".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        pages.createContext("/ajax", exchange -> {
            throw new IOException("client gone");
        });
        server = new ConsoleProxyNoVNCServer(List.of(port, port), pages, CertificateHelper.loadKeystore(keyStore.bits, PASSWORD), PASSWORD);
        server.start();
        ConsoleProxy.noVNCServer = server;
    }

    private SSLSocket connect() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        SSLSocket socket = (SSLSocket)context.getSocketFactory().createSocket("localhost", port);
        socket.startHandshake();
        return socket;
    }

    private static String fingerprint(SSLSocket socket) throws Exception {
        return CertificateHelper.generateFingerPrint(socket.getSession().getPeerCertificates()[0]);
    }

    private static String statusLine(SSLSocket socket, String request) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
    }

    @Test
    public void thePagesAndTheWebSocketShareOnePort() throws Exception {
        startServer(new KeyStoreBits("console.example.org"));

        try (SSLSocket socket = connect()) {
            Assert.assertEquals("HTTP/1.1 200 OK", statusLine(socket, "GET /resource/noVNC/vnc.html HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        }
        try (SSLSocket socket = connect()) {
            String status = statusLine(socket, "GET /websockify?token=abc HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n");
            Assert.assertTrue(status, status.startsWith("HTTP/1.1 101"));
        }
    }

    @Test
    public void aLoadedCertificateIsServedToNewConnectionsWhileEstablishedOnesContinue() throws Exception {
        KeyStoreBits first = new KeyStoreBits("console.example.org");
        KeyStoreBits second = new KeyStoreBits("console.example.org");
        startServer(first);

        try (SSLSocket established = connect()) {
            Assert.assertEquals(first.fingerprint, fingerprint(established));

            ConsoleProxy.loadServerCertificate(second.bits, PASSWORD);

            try (SSLSocket fresh = connect()) {
                Assert.assertEquals(second.fingerprint, fingerprint(fresh));
            }
            Assert.assertEquals(second.fingerprint, ConsoleProxy.getServerCertificateFingerprint());
            Assert.assertEquals("HTTP/1.1 200 OK", statusLine(established, "GET /resource/noVNC/vnc.html HTTP/1.1\r\nHost: localhost\r\n\r\n"));
            Assert.assertEquals(first.fingerprint, fingerprint(established));
        }
    }

    @Test
    public void aFailingPageAnswersWithoutAStackTrace() throws Exception {
        startServer(new KeyStoreBits("console.example.org"));

        try (SSLSocket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /ajax?token=abc HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            Assert.assertTrue(response, response.startsWith("HTTP/1.1 500"));
            Assert.assertFalse(response, response.contains("ConsoleProxyHttpHandlerAdapter"));
        }
    }

    @Test
    public void aKeyStoreWithoutAPrivateKeyLeavesTheServedCertificateInPlace() throws Exception {
        KeyStoreBits first = new KeyStoreBits("console.example.org");
        startServer(first);
        KeyStore certificateOnly = KeyStore.getInstance("JKS");
        certificateOnly.load(null, PASSWORD.toCharArray());
        certificateOnly.setCertificateEntry("chain", CertificateHelper.loadKeystore(first.bits, PASSWORD).getCertificate("CPVMCertificate"));
        ByteArrayOutputStream bits = new ByteArrayOutputStream();
        certificateOnly.store(bits, PASSWORD.toCharArray());

        Assert.assertThrows(Exception.class, () -> ConsoleProxy.loadServerCertificate(bits.toByteArray(), PASSWORD));

        try (SSLSocket socket = connect()) {
            Assert.assertEquals(first.fingerprint, fingerprint(socket));
        }
        Assert.assertEquals(first.fingerprint, ConsoleProxy.getServerCertificateFingerprint());
    }
}
