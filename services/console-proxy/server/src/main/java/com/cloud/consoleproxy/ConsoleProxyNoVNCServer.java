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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

import javax.net.ssl.KeyManagerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import com.cloud.utils.security.CertificateHelper;

/**
 * Serves the console pages and the noVNC WebSocket. Every port serves both: a request that asks for a WebSocket upgrade
 * opens a console session and any other goes to the page handler, so the WebSocket may share the page's port.
 */
public class ConsoleProxyNoVNCServer {

    protected static Logger LOGGER = LogManager.getLogger(ConsoleProxyNoVNCServer.class);
    public static final int WS_PORT = 8080;
    private static final String VNC_CONF_FILE_LOCATION = "/root/vncport";

    private final Server server;
    private final SslContextFactory.Server sslContextFactory;
    private KeyStore keyStore;
    private String keyStorePassword;
    private String certificateFingerprint;

    public static int getVNCPort() {
        String portStr;
        try {
            portStr = Files.readString(Path.of(VNC_CONF_FILE_LOCATION)).trim();
        } catch (IOException e) {
            LOGGER.error("Cannot read the VNC port from the file " + VNC_CONF_FILE_LOCATION + " setting it to 8080", e);
            return WS_PORT;
        }
        return Integer.parseInt(portStr);
    }

    /**
     * @param ports    the ports to serve, each once however often it is named
     * @param keyStore the key store whose private key and certificate every port serves over TLS, or null to serve
     *                 plain HTTP
     */
    public ConsoleProxyNoVNCServer(Collection<Integer> ports, Handler pageHandler, KeyStore keyStore, String keyStorePassword) throws KeyStoreException {
        this.server = new Server();
        ConsoleProxyNoVNCHandler handler = new ConsoleProxyNoVNCHandler();
        handler.setHandler(pageHandler);
        this.server.setHandler(handler);
        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(false);
        errorHandler.setShowServlet(false);
        this.server.setErrorHandler(errorHandler);

        final HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        if (keyStore == null) {
            sslContextFactory = null;
        } else {
            httpConfig.setSecureScheme("https");
            httpConfig.addCustomizer(new SecureRequestCustomizer());
            certificateFingerprint = getCertificateFingerprint(keyStore);
            sslContextFactory = new SslContextFactory.Server();
            useKeyStore(sslContextFactory, keyStore, keyStorePassword);
            this.keyStore = keyStore;
            this.keyStorePassword = keyStorePassword;
        }

        for (int port : new LinkedHashSet<>(ports)) {
            final ServerConnector connector = sslContextFactory == null ? new ServerConnector(server, new HttpConnectionFactory(httpConfig))
                    : new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpConfig));
            connector.setPort(port);
            server.addConnector(connector);
        }
    }

    public void start() throws Exception {
        this.server.start();
    }

    public void stop() throws Exception {
        this.server.stop();
    }

    public boolean isSecure() {
        return sslContextFactory != null;
    }

    /**
     * @return the SHA-256 fingerprint of the certificate presented to new connections, or null when serving plain HTTP
     */
    public synchronized String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    /**
     * Presents the key store's certificate to new connections; established connections keep the one they began with.
     * A key store that cannot be served leaves the current certificate in place.
     */
    public synchronized void loadKeyStore(KeyStore newKeyStore, String newKeyStorePassword) throws Exception {
        if (!isSecure()) {
            throw new IllegalStateException("The console proxy serves plain HTTP");
        }
        String fingerprint = getCertificateFingerprint(newKeyStore);
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).init(newKeyStore,
                newKeyStorePassword == null ? null : newKeyStorePassword.toCharArray());
        try {
            sslContextFactory.reload(factory -> useKeyStore(factory, newKeyStore, newKeyStorePassword));
        } catch (Exception e) {
            sslContextFactory.reload(factory -> useKeyStore(factory, keyStore, keyStorePassword));
            throw e;
        }
        keyStore = newKeyStore;
        keyStorePassword = newKeyStorePassword;
        certificateFingerprint = fingerprint;
    }

    private static void useKeyStore(SslContextFactory factory, KeyStore keyStore, String keyStorePassword) {
        factory.setKeyStore(keyStore);
        factory.setKeyStorePassword(keyStorePassword);
        factory.setKeyManagerPassword(keyStorePassword);
    }

    /**
     * @return the fingerprint of the certificate of the key store's private key; the management server builds key
     * stores with a single one
     */
    static String getCertificateFingerprint(KeyStore keyStore) throws KeyStoreException {
        for (String alias : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(alias)) {
                Certificate[] chain = keyStore.getCertificateChain(alias);
                if (chain != null && chain.length > 0) {
                    return CertificateHelper.generateFingerPrint(chain[0]);
                }
            }
        }
        throw new KeyStoreException("The key store holds no private key with a certificate");
    }
}
