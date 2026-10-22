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
package org.apache.cloudstack.oidc.membership;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Group membership from the Google Workspace Directory API ({@code members.hasMember}, direct or nested), asked with a
 * service account's access token (OAuth 2.0 JWT bearer grant, optionally acting for a Workspace user). The member key
 * is the person's name at the provider, which for a Workspace sign-in is their primary address or an alias.
 */
public class GoogleDirectoryMembershipSource implements OidcMembershipSource {

    static final String SCOPE = "https://www.googleapis.com/auth/admin.directory.group.member.readonly";
    static final String DIRECTORY = "https://admin.googleapis.com";
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    protected Logger logger = LogManager.getLogger(getClass());

    private final HttpClient http;
    private final Clock clock;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String keyId;
    private final String tokenUri;
    private final String subject;
    private final String directory;

    private String accessToken;
    private Instant accessTokenExpiry = Instant.EPOCH;

    GoogleDirectoryMembershipSource(HttpClient http, Clock clock, String clientEmail, PrivateKey privateKey, String keyId, String tokenUri, String subject,
            String directory) {
        this.http = http;
        this.clock = clock;
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.keyId = keyId;
        this.tokenUri = tokenUri;
        this.subject = subject;
        this.directory = directory;
    }

    /**
     * @param serviceAccountKey the service account's JSON key, with {@code client_email}, {@code private_key} and
     *        optionally {@code private_key_id} and {@code token_uri}
     * @param subject the Workspace user the service account acts for, or null to act as itself
     * @throws IllegalArgumentException if the key is not a service account key
     */
    public static GoogleDirectoryMembershipSource create(HttpClient http, Clock clock, JsonObject serviceAccountKey, String subject) {
        return create(http, clock, serviceAccountKey, subject, DIRECTORY);
    }

    static GoogleDirectoryMembershipSource create(HttpClient http, Clock clock, JsonObject serviceAccountKey, String subject, String directory) {
        String email = string(serviceAccountKey, "client_email");
        String pem = string(serviceAccountKey, "private_key");
        String tokenUri = string(serviceAccountKey, "token_uri");
        if (email == null || pem == null) {
            throw new IllegalArgumentException("a service account key needs client_email and private_key");
        }
        if (tokenUri == null) {
            tokenUri = DEFAULT_TOKEN_URI;
        }
        if (!tokenUri.startsWith("https://")) {
            throw new IllegalArgumentException("the token_uri of a service account key must be https");
        }
        return new GoogleDirectoryMembershipSource(http, clock, email, privateKey(pem), string(serviceAccountKey, "private_key_id"), tokenUri, subject,
                directory);
    }

    static PrivateKey privateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalArgumentException("private_key is not a PKCS #8 RSA key");
        }
    }

    @Override
    public Boolean isMember(String member, String group) {
        if (StringUtils.isBlank(member)) {
            return null;
        }
        try {
            URI uri = URI.create(String.format("%s/admin/directory/v1/groups/%s/hasMember/%s", directory, pathSegment(group), pathSegment(member)));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken())
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logger.warn("The directory did not tell whether {} is in {}: HTTP {}", member, group, response.statusCode());
                return null;
            }
            JsonElement isMember = JsonParser.parseString(response.body()).getAsJsonObject().get("isMember");
            if (isMember == null || !isMember.isJsonPrimitive() || !isMember.getAsJsonPrimitive().isBoolean()) {
                logger.warn("The directory answered no membership of {} in {}", member, group);
                return null;
            }
            return isMember.getAsBoolean();
        } catch (IOException | RuntimeException | GeneralSecurityException e) {
            logger.warn("Asking the directory whether {} is in {} failed: {}", member, group, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** @return an access token of the service account, fetched again shortly before the last one expires */
    synchronized String accessToken() throws IOException, InterruptedException, GeneralSecurityException {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        if (keyId != null) {
            header.addProperty("kid", keyId);
        }
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", clientEmail);
        claims.addProperty("scope", SCOPE);
        claims.addProperty("aud", tokenUri);
        claims.addProperty("iat", now.getEpochSecond());
        claims.addProperty("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
        if (subject != null) {
            claims.addProperty("sub", subject);
        }
        String input = base64Url(header.toString().getBytes(StandardCharsets.UTF_8)) + "." + base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        String assertion = input + "." + base64Url(signature.sign());
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) + "&assertion=" + assertion;
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(tokenUri)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException(String.format("the token endpoint answered HTTP %d", response.statusCode()));
        }
        JsonObject token = JsonParser.parseString(response.body()).getAsJsonObject();
        String value = string(token, "access_token");
        if (value == null) {
            throw new IOException("the token endpoint answered no access token");
        }
        long lifetime = token.has("expires_in") && token.get("expires_in").isJsonPrimitive() ? token.get("expires_in").getAsLong() : 3600;
        accessToken = value;
        accessTokenExpiry = now.plusSeconds(Math.max(0, lifetime - 60));
        return accessToken;
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : null;
    }
}
