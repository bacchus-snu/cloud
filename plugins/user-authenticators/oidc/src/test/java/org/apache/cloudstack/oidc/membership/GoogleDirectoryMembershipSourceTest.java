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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.oidc.MockIdp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

public class GoogleDirectoryMembershipSourceTest {

    private static final String SERVICE_ACCOUNT = "reconcile@project.iam.gserviceaccount.com";
    private static final String GROUP = "regular-members@members.example.org";

    private MockIdp google;
    private KeyPair key;
    private final Map<String, Boolean> members = new HashMap<>();
    private final List<JsonObject> assertions = new ArrayList<>();
    private final List<String> directoryCalls = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

    @Before
    public void setUp() throws Exception {
        google = new MockIdp();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        google.handle("/google/token", this::token);
        google.handle("/admin/directory/v1/groups/", this::hasMember);
        members.put("alice@members.example.org", true);
        members.put("bob@members.example.org", false);
    }

    @After
    public void tearDown() {
        google.close();
    }

    private JsonObject serviceAccountKey() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "service_account");
        json.addProperty("client_email", SERVICE_ACCOUNT);
        json.addProperty("private_key_id", "kid-1");
        json.addProperty("private_key", "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        json.addProperty("token_uri", google.issuer() + "/google/token");
        return json;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form;
        try (InputStream in = ex.getRequestBody()) {
            form = MockIdp.query(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        String[] jwt = form.get("assertion").split("\\.");
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key.getPublic());
            signature.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
            if (!"urn:ietf:params:oauth:grant-type:jwt-bearer".equals(form.get("grant_type")) || !signature.verify(Base64.getUrlDecoder().decode(jwt[2]))) {
                respond(ex, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        JsonObject header = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(jwt[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        claims.add("header", header);
        assertions.add(claims);
        respond(ex, 200, "{\"access_token\":\"ga-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
    }

    private void hasMember(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        directoryCalls.add(path);
        String[] parts = path.split("/");
        String group = URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
        String member = URLDecoder.decode(parts[7], StandardCharsets.UTF_8);
        if (!"Bearer ga-token".equals(ex.getRequestHeaders().getFirst("Authorization")) || !GROUP.equals(group)) {
            respond(ex, 403, "{\"error\":{\"code\":403}}");
            return;
        }
        Boolean isMember = members.get(member);
        if (isMember == null) {
            respond(ex, 404, "{\"error\":{\"code\":404,\"message\":\"Resource Not Found: memberKey\"}}");
            return;
        }
        respond(ex, 200, "{\"isMember\":" + isMember + "}");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private GoogleDirectoryMembershipSource source(String subject) {
        return GoogleDirectoryMembershipSource.create(google.httpClient(), clock, serviceAccountKey(), subject, google.issuer());
    }

    @Test
    public void asksTheDirectoryWithTheServiceAccountsToken() {
        GoogleDirectoryMembershipSource source = source("admin@members.example.org");
        assertTrue(source.isMember("alice@members.example.org", GROUP));
        assertFalse(source.isMember("bob@members.example.org", GROUP));
        assertEquals("the access token is used until it expires", 1, assertions.size());
        JsonObject claims = assertions.get(0);
        assertEquals(SERVICE_ACCOUNT, claims.get("iss").getAsString());
        assertEquals("admin@members.example.org", claims.get("sub").getAsString());
        assertEquals(GoogleDirectoryMembershipSource.SCOPE, claims.get("scope").getAsString());
        assertEquals(google.issuer() + "/google/token", claims.get("aud").getAsString());
        assertEquals(3600, claims.get("exp").getAsLong() - claims.get("iat").getAsLong());
        assertEquals("RS256", claims.getAsJsonObject("header").get("alg").getAsString());
        assertEquals("kid-1", claims.getAsJsonObject("header").get("kid").getAsString());
        assertEquals("/admin/directory/v1/groups/regular-members%40members.example.org/hasMember/alice%40members.example.org", directoryCalls.get(0));
    }

    @Test
    public void anAnswerItCannotReadIsUnknown() {
        GoogleDirectoryMembershipSource source = source(null);
        assertNull("an unknown member is not a lost membership", source.isMember("carol@members.example.org", GROUP));
        assertNull("a group it may not read is not a lost membership", source.isMember("alice@members.example.org", "other@members.example.org"));
        int calls = directoryCalls.size();
        assertNull(source.isMember("", GROUP));
        assertNull(source.isMember(null, GROUP));
        assertEquals("nobody is asked about without a name", calls, directoryCalls.size());
        assertFalse("without a subject the service account acts as itself", assertions.get(0).has("sub"));
    }

    @Test
    public void sourceConfigurationIsValidated() {
        OidcMembershipSources.validate(null, null);
        OidcMembershipSources.validate("{\"type\":\"google-directory\",\"subject\":\"admin@members.example.org\"}", serviceAccountKey().toString());
        String key = serviceAccountKey().toString();
        for (String[] invalid : new String[][] {
                {"{\"type\":\"google-directory\"}", null},
                {"{\"type\":\"scim\"}", key},
                {"{\"type\":\"google-directory\",\"group\":\"x\"}", key},
                {"not json", key},
                {"{\"type\":\"google-directory\"}", "{\"client_email\":\"x\"}"},
                {"{\"type\":\"google-directory\"}", "{\"client_email\":\"x\",\"private_key\":\"garbage\"}"},
                {"{\"type\":\"google-directory\"}", key.replace("https://", "http://")}}) {
            assertThrows(invalid[0], InvalidParameterValueException.class, () -> OidcMembershipSources.validate(invalid[0], invalid[1]));
        }
        assertNull(OidcMembershipSources.create("", null, google.httpClient(), clock));
    }
}
