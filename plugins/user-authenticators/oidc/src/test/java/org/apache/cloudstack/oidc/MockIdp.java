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
package org.apache.cloudstack.oidc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.cloudstack.utils.security.CertUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * An OpenID provider on a loopback HTTPS port: discovery, key set, token and userinfo endpoints with PKCE and client
 * authentication checks. A test plays the browser: {@link #authorize} answers an authorization URL as the provider
 * would after the person signed in.
 */
public final class MockIdp implements AutoCloseable {

    static final String CLIENT_ID = "cloud-client";
    static final String CLIENT_SECRET = "cloud-client-secret";

    /** What the provider knows of a person: the ID token claims and the userinfo claims. */
    static final class Person {
        final JsonObject idClaims = new JsonObject();
        final JsonObject userinfo = new JsonObject();

        Person(String sub) {
            idClaims.addProperty("sub", sub);
            userinfo.addProperty("sub", sub);
        }

        Person claim(String name, String value) {
            idClaims.addProperty(name, value);
            return this;
        }

        Person claim(String name, boolean value) {
            idClaims.addProperty(name, value);
            return this;
        }

        Person groups(String claim, String... groups) {
            JsonArray array = new JsonArray();
            for (String group : groups) {
                array.add(group);
            }
            idClaims.add(claim, array);
            return this;
        }

        Person userinfo(String name, String value) {
            userinfo.addProperty(name, value);
            return this;
        }
    }

    private static final class Grant {
        final String redirectUri;
        final String nonce;
        final String challenge;
        final Person person;

        Grant(String redirectUri, String nonce, String challenge, Person person) {
            this.redirectUri = redirectUri;
            this.nonce = nonce;
            this.challenge = challenge;
            this.person = person;
        }
    }

    private final HttpsServer server;
    private final SSLContext clientContext;
    private final String caPem;
    private final KeyPair signingKey;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<String, Person> accessTokens = new ConcurrentHashMap<>();
    final AtomicInteger tokenRequests = new AtomicInteger();
    final List<Map<String, String>> authorizationRequests = new ArrayList<>();

    boolean issParameterSupported;
    boolean sendIss = true;
    boolean userinfoEndpoint = true;
    String alg = "RS256";
    /** Appended to every authorization code, to try characters that a query string carries only encoded. */
    String codeSuffix = "";
    /** Changes the ID token claims after the provider filled them in. */
    Consumer<JsonObject> idTokenTamper = claims -> { };

    public MockIdp() throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        signingKey = OidcTestKeys.rsa();
        KeyPair tlsKey = OidcTestKeys.rsa();
        X509Certificate cert = CertUtils.generateV3Certificate(null, tlsKey, tlsKey.getPublic(), "CN=127.0.0.1", "SHA256WithRSAEncryption", 1,
                List.of("localhost"), List.of("127.0.0.1"));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("tls", tlsKey.getPrivate(), "pw".toCharArray(), new Certificate[] {cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "pw".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, new SecureRandom());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("idp", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, tmf.getTrustManagers(), new SecureRandom());
        caPem = CertUtils.x509CertificateToPem(cert);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/jwks", ex -> json(ex, 200, keySet()));
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::userinfo);
        server.start();
    }

    public String issuer() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Serves another endpoint on the provider's address. */
    public void handle(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler);
    }

    public HttpClient httpClient() {
        return HttpClient.newBuilder().sslContext(clientContext).build();
    }

    /** @return the certificate the provider serves TLS with, which is its own authority */
    public String caPem() {
        return caPem;
    }

    /** @return a TLS context that trusts the provider */
    public SSLContext sslContext() {
        return clientContext;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private JsonObject keySet() {
        JsonArray keys = new JsonArray();
        keys.add(OidcTestKeys.jwk("k1", signingKey.getPublic()));
        JsonObject set = new JsonObject();
        set.add("keys", keys);
        return set;
    }

    private void discovery(HttpExchange ex) throws IOException {
        JsonObject doc = new JsonObject();
        doc.addProperty("issuer", issuer());
        doc.addProperty("authorization_endpoint", issuer() + "/authorize");
        doc.addProperty("token_endpoint", issuer() + "/token");
        doc.addProperty("jwks_uri", issuer() + "/jwks");
        if (userinfoEndpoint) {
            doc.addProperty("userinfo_endpoint", issuer() + "/userinfo");
        }
        JsonArray methods = new JsonArray();
        methods.add("client_secret_basic");
        methods.add("client_secret_post");
        doc.add("token_endpoint_auth_methods_supported", methods);
        if (issParameterSupported) {
            doc.addProperty("authorization_response_iss_parameter_supported", true);
        }
        json(ex, 200, doc);
    }

    /** @return the query string the provider returns the browser with, form-encoded as a provider encodes it */
    public static String answerQuery(Map<String, String> answer) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : answer.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    public static Map<String, String> query(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    /**
     * Answers an authorization request after the person signed in: the parameters the provider returns the browser
     * with, and the redirect URI it returns it to.
     */
    Map<String, String> authorize(String authorizationUrl, Person person) {
        URI uri = URI.create(authorizationUrl);
        if (!uri.toString().startsWith(issuer() + "/authorize?")) {
            throw new IllegalStateException("not this provider's authorization endpoint: " + authorizationUrl);
        }
        Map<String, String> params = query(uri.getRawQuery());
        synchronized (authorizationRequests) {
            authorizationRequests.add(params);
        }
        if (!"code".equals(params.get("response_type")) || !CLIENT_ID.equals(params.get("client_id")) || !"S256".equals(params.get("code_challenge_method"))
                || !params.get("scope").contains("openid")) {
            throw new IllegalStateException("bad authorization request " + params);
        }
        String code = "code-" + OidcTestKeys.b64(new SecureRandom().generateSeed(16)) + codeSuffix;
        grants.put(code, new Grant(params.get("redirect_uri"), params.get("nonce"), params.get("code_challenge"), person));
        Map<String, String> answer = new LinkedHashMap<>();
        answer.put("code", code);
        answer.put("state", params.get("state"));
        if (sendIss) {
            answer.put("iss", issuer());
        }
        return answer;
    }

    private void token(HttpExchange ex) throws IOException {
        tokenRequests.incrementAndGet();
        Map<String, String> form;
        try (InputStream in = ex.getRequestBody()) {
            form = query(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        Grant grant = grants.remove(form.getOrDefault("code", ""));
        if (!expected.equals(auth) || grant == null || !"authorization_code".equals(form.get("grant_type"))
                || !grant.redirectUri.equals(form.get("redirect_uri")) || !challenge(form.get("code_verifier")).equals(grant.challenge)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "invalid_grant");
            json(ex, 400, error);
            return;
        }
        JsonObject claims = grant.person.idClaims.deepCopy();
        claims.addProperty("iss", issuer());
        claims.addProperty("aud", CLIENT_ID);
        claims.addProperty("iat", Instant.now().getEpochSecond());
        claims.addProperty("exp", Instant.now().getEpochSecond() + 300);
        claims.addProperty("nonce", grant.nonce);
        idTokenTamper.accept(claims);
        String accessToken = "at-" + OidcTestKeys.b64(new SecureRandom().generateSeed(16));
        accessTokens.put(accessToken, grant.person);
        JsonObject response = new JsonObject();
        try {
            response.addProperty("id_token", OidcTestKeys.sign(alg, "k1", signingKey.getPrivate(), claims));
        } catch (Exception e) {
            throw new IOException(e);
        }
        response.addProperty("access_token", accessToken);
        response.addProperty("token_type", "Bearer");
        json(ex, 200, response);
    }

    private void userinfo(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Person person = auth == null ? null : accessTokens.get(auth.replace("Bearer ", ""));
        if (person == null) {
            json(ex, 401, new JsonObject());
            return;
        }
        json(ex, 200, person.userinfo);
    }

    static String challenge(String verifier) {
        try {
            return OidcTestKeys.b64(MessageDigest.getInstance("SHA-256").digest(String.valueOf(verifier).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static Map<String, String> copy(Map<String, String> map) {
        return new HashMap<>(map);
    }
}
