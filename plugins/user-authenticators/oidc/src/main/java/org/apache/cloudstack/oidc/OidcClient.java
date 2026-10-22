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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The provider endpoints sign-in talks to. Discovery documents and key sets are cached; a document is fetched again
 * after an hour or after a failure, and a key set also when a token names a key it lacks, at most once a minute.
 */
class OidcClient {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final Duration CACHE_TTL = Duration.ofHours(1);
    static final Duration KEY_REFRESH_INTERVAL = Duration.ofMinutes(1);

    private static final class Cached<T> {
        final T value;
        final Instant fetched;

        Cached(T value, Instant fetched) {
            this.value = value;
            this.fetched = fetched;
        }
    }

    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Cached<OidcProviderMetadata>> metadata = new ConcurrentHashMap<>();
    private final Map<String, Cached<JsonArray>> keys = new ConcurrentHashMap<>();

    OidcClient(HttpClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    /**
     * @param caPem PEM certificates of the only authorities to trust, or null for the CA certificates the JDK ships.
     *        Neither the JVM's default TLS context nor the trust store javax.net.ssl.trustStore names is used: other
     *        components of the process change both.
     */
    static HttpClient httpClient(String caPem) {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(caPem == null ? jdkAuthorities() : authorities(caPem));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(context).connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is not available", e);
        }
    }

    /** @return the CA certificates the running JDK ships, read from the runtime itself */
    static KeyStore jdkAuthorities() {
        File cacerts = Paths.get(System.getProperty("java.home"), "lib", "security", "cacerts").toFile();
        try {
            return KeyStore.getInstance(cacerts, (char[]) null);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(String.format("the CA certificates of the JDK at %s cannot be read", cacerts), e);
        }
    }

    /** @throws IllegalArgumentException when the text holds no PEM certificate */
    static KeyStore authorities(String pem) {
        try {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("it holds no certificate");
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate certificate : certificates) {
                store.setCertificateEntry("authority-" + i++, certificate);
            }
            return store;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("it holds no PEM certificates", e);
        }
    }

    /**
     * @throws OidcLoginException with {@link OidcLoginError#UNAVAILABLE} when the document cannot be fetched or is not
     *         the issuer's
     */
    OidcProviderMetadata metadata(String issuer) throws OidcLoginException {
        Cached<OidcProviderMetadata> cached = metadata.get(issuer);
        if (cached != null && cached.fetched.plus(CACHE_TTL).isAfter(clock.instant())) {
            return cached.value;
        }
        try {
            OidcProviderMetadata fetched = discover(issuer);
            metadata.put(issuer, new Cached<>(fetched, clock.instant()));
            return fetched;
        } catch (OidcLoginException e) {
            metadata.remove(issuer);
            throw new OidcLoginException(OidcLoginError.UNAVAILABLE, e.getMessage(), e);
        }
    }

    /** Drops the cached document of the issuer, so that the next sign-in fetches it again. */
    void forget(String issuer) {
        metadata.remove(issuer);
    }

    private OidcProviderMetadata discover(String issuer) throws OidcLoginException {
        JsonObject doc = getJson(URI.create(StringUtils.stripEnd(issuer, "/") + "/.well-known/openid-configuration"), null);
        if (!issuer.equals(string(doc, "issuer"))) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("discovery document of %s names issuer %s", issuer, string(doc, "issuer")));
        }
        String userinfo = string(doc, "userinfo_endpoint");
        List<String> authMethods = new ArrayList<>();
        JsonElement methods = doc.get("token_endpoint_auth_methods_supported");
        if (methods != null && methods.isJsonArray()) {
            for (JsonElement m : methods.getAsJsonArray()) {
                if (m.isJsonPrimitive()) {
                    authMethods.add(m.getAsString());
                }
            }
        }
        JsonElement iss = doc.get("authorization_response_iss_parameter_supported");
        return new OidcProviderMetadata(issuer, httpsUrl(doc, "authorization_endpoint"), httpsUrl(doc, "token_endpoint"), httpsUrl(doc, "jwks_uri"),
                userinfo == null ? null : httpsUrl(doc, "userinfo_endpoint"), iss != null && iss.isJsonPrimitive() && iss.getAsBoolean(), authMethods);
    }

    /**
     * @return the keys of the provider's key set
     */
    JsonArray keys(OidcProviderMetadata provider, boolean refresh) throws OidcLoginException {
        String uri = provider.getJwksUri();
        Cached<JsonArray> cached = keys.get(uri);
        Instant now = clock.instant();
        if (cached != null) {
            boolean fresh = cached.fetched.plus(CACHE_TTL).isAfter(now);
            boolean recentlyFetched = cached.fetched.plus(KEY_REFRESH_INTERVAL).isAfter(now);
            if (fresh && (!refresh || recentlyFetched)) {
                return cached.value;
            }
        }
        JsonElement set = getJson(URI.create(uri), null).get("keys");
        if (set == null || !set.isJsonArray()) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("key set %s has no keys", uri));
        }
        keys.put(uri, new Cached<>(set.getAsJsonArray(), now));
        return set.getAsJsonArray();
    }

    /**
     * Redeems an authorization code (OpenID Connect Core 1.0, 3.1.3.1) with the client's secret and the PKCE verifier.
     *
     * @return the token response, which has an ID token
     */
    JsonObject redeem(OidcProviderMetadata provider, String clientId, String clientSecret, String code, String redirectUri, String verifier)
            throws OidcLoginException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(provider.getTokenEndpoint())).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");
        List<String> methods = provider.getTokenEndpointAuthMethods();
        if (methods.isEmpty() || methods.contains("client_secret_basic")) {
            String credentials = formEncode(clientId) + ":" + formEncode(clientSecret);
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else if (methods.contains("client_secret_post")) {
            form.put("client_id", clientId);
            form.put("client_secret", clientSecret);
        } else {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("token endpoint of %s takes no client secret: %s", provider.getIssuer(), methods));
        }
        String body = form.entrySet().stream().map(e -> formEncode(e.getKey()) + "=" + formEncode(e.getValue())).collect(Collectors.joining("&"));
        JsonObject response = send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), "token endpoint");
        if (string(response, "id_token") == null) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("token response of %s has no ID token", provider.getIssuer()));
        }
        return response;
    }

    JsonObject userinfo(OidcProviderMetadata provider, String accessToken) throws OidcLoginException {
        return getJson(URI.create(provider.getUserinfoEndpoint()), accessToken);
    }

    private JsonObject getJson(URI uri, String bearer) throws OidcLoginException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json").GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build(), uri.toString());
    }

    private JsonObject send(HttpRequest request, String what) throws OidcLoginException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s: %s", what, e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s: interrupted", what), e);
        }
        if (response.statusCode() != 200) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s answered HTTP %d", what, response.statusCode()));
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase();
        if (!mediaType.equals("application/json") && !(mediaType.startsWith("application/") && mediaType.endsWith("+json"))) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s answered %s, not JSON", what, contentType));
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("%s answered no JSON object", what), e);
        }
    }

    static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static String httpsUrl(JsonObject doc, String name) throws OidcLoginException {
        String value = string(doc, name);
        if (value == null || !value.startsWith("https://")) {
            throw new OidcLoginException(OidcLoginError.FAILED, String.format("discovery document has no https %s", name));
        }
        return value;
    }

    static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
