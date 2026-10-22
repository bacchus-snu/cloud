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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class IdTokenVerifierTest {

    private static final String ISSUER = "https://idp.example";
    private static final String CLIENT = "client-1";
    private static final String NONCE = "nonce-1";

    private final Instant now = Instant.ofEpochSecond(1_800_000_000L);
    private KeyPair rsa;
    private KeyPair ec;
    private JsonArray keys;
    private final List<Boolean> fetches = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        rsa = OidcTestKeys.rsa();
        ec = OidcTestKeys.ec();
        keys = new JsonArray();
        keys.add(OidcTestKeys.jwk("r1", rsa.getPublic()));
        keys.add(OidcTestKeys.jwk("e1", ec.getPublic()));
    }

    private JsonObject claims() {
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", ISSUER);
        claims.addProperty("aud", CLIENT);
        claims.addProperty("exp", now.getEpochSecond() + 300);
        claims.addProperty("iat", now.getEpochSecond());
        claims.addProperty("nonce", NONCE);
        claims.addProperty("sub", "alice-sub");
        return claims;
    }

    private JsonObject verify(String token) throws OidcLoginException {
        return IdTokenVerifier.verify(token, refresh -> {
            fetches.add(refresh);
            return keys;
        }, ISSUER, CLIENT, NONCE, now);
    }

    private void assertRejected(String token) {
        try {
            verify(token);
            fail("the token was accepted");
        } catch (OidcLoginException e) {
            assertEquals(OidcLoginError.FAILED, e.getError());
        }
    }

    private String rs256(JsonObject claims) throws Exception {
        return OidcTestKeys.sign("RS256", "r1", rsa.getPrivate(), claims);
    }

    @Test
    public void acceptsEveryAllowedAlgorithm() throws Exception {
        assertEquals("alice-sub", verify(rs256(claims())).get("sub").getAsString());
        assertEquals("alice-sub", verify(OidcTestKeys.sign("PS256", "r1", rsa.getPrivate(), claims())).get("sub").getAsString());
        assertEquals("alice-sub", verify(OidcTestKeys.sign("ES256", "e1", ec.getPrivate(), claims())).get("sub").getAsString());
    }

    @Test
    public void idTokenRejectsWrongIss() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("iss", "https://other.example");
        assertRejected(rs256(claims));
        claims.addProperty("iss", ISSUER + "/");
        assertRejected(rs256(claims));
        claims.remove("iss");
        assertRejected(rs256(claims));
    }

    @Test
    public void idTokenRejectsWrongAud() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("aud", "client-2");
        assertRejected(rs256(claims));
        JsonArray others = new JsonArray();
        others.add("client-2");
        others.add("client-3");
        claims.add("aud", others);
        assertRejected(rs256(claims));
        claims.remove("aud");
        assertRejected(rs256(claims));
    }

    @Test
    public void idTokenRejectsWrongAzp() throws Exception {
        JsonObject claims = claims();
        JsonArray audiences = new JsonArray();
        audiences.add(CLIENT);
        audiences.add("client-2");
        claims.add("aud", audiences);
        assertRejected(rs256(claims));
        claims.addProperty("azp", "client-2");
        assertRejected(rs256(claims));
        claims.addProperty("azp", CLIENT);
        assertEquals("alice-sub", verify(rs256(claims)).get("sub").getAsString());
        JsonObject single = claims();
        single.addProperty("azp", "client-2");
        assertRejected(rs256(single));
    }

    @Test
    public void idTokenRejectsExpired() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("exp", now.getEpochSecond() - IdTokenVerifier.CLOCK_SKEW_SECONDS - 1);
        assertRejected(rs256(claims));
        claims.addProperty("exp", now.getEpochSecond() - IdTokenVerifier.CLOCK_SKEW_SECONDS + 5);
        verify(rs256(claims));
        claims.remove("exp");
        assertRejected(rs256(claims));
    }

    @Test
    public void rejectsATokenIssuedInTheFuture() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("iat", now.getEpochSecond() + IdTokenVerifier.CLOCK_SKEW_SECONDS + 60);
        assertRejected(rs256(claims));
        claims.remove("iat");
        assertRejected(rs256(claims));
    }

    @Test
    public void rejectsATokenBeforeItsNotBeforeTime() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("nbf", now.getEpochSecond() + IdTokenVerifier.CLOCK_SKEW_SECONDS + 60);
        assertRejected(rs256(claims));
        claims.addProperty("nbf", now.getEpochSecond() + IdTokenVerifier.CLOCK_SKEW_SECONDS - 1);
        assertEquals("a not-before time within the clock skew is accepted", "alice-sub", verify(rs256(claims)).get("sub").getAsString());
        claims.addProperty("nbf", "soon");
        assertRejected(rs256(claims));
    }

    @Test
    public void idTokenRejectsWrongNonce() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("nonce", "nonce-2");
        assertRejected(rs256(claims));
        claims.remove("nonce");
        assertRejected(rs256(claims));
    }

    @Test
    public void rejectsATokenWithoutSubject() throws Exception {
        JsonObject claims = claims();
        claims.addProperty("sub", "");
        assertRejected(rs256(claims));
        claims.remove("sub");
        assertRejected(rs256(claims));
    }

    @Test
    public void idTokenRejectsAlgNone() {
        assertRejected(OidcTestKeys.none(claims()));
        String unsigned = OidcTestKeys.none(claims());
        assertRejected(unsigned + "c2ln");
    }

    @Test
    public void idTokenRejectsHs256() throws Exception {
        assertRejected(OidcTestKeys.hs256("r1", "client-secret".getBytes(StandardCharsets.UTF_8), claims()));
        assertRejected(OidcTestKeys.hs256("r1", rsa.getPublic().getEncoded(), claims()));
        assertRejected(OidcTestKeys.hs256(null, keys.get(0).toString().getBytes(StandardCharsets.UTF_8), claims()));
    }

    @Test
    public void rejectsASignatureOfAnotherKey() throws Exception {
        KeyPair other = OidcTestKeys.rsa();
        assertRejected(OidcTestKeys.sign("RS256", "r1", other.getPrivate(), claims()));
        assertRejected(OidcTestKeys.sign("ES256", "e1", OidcTestKeys.ec().getPrivate(), claims()));
        String token = rs256(claims());
        JsonObject tampered = claims();
        tampered.addProperty("sub", "mallory-sub");
        String[] parts = token.split("\\.");
        assertRejected(parts[0] + "." + OidcTestKeys.b64(tampered.toString()) + "." + parts[2]);
    }

    @Test
    public void rejectsAKeyOfAnotherType() throws Exception {
        assertRejected(OidcTestKeys.sign("ES256", "r1", ec.getPrivate(), claims()));
        assertRejected(OidcTestKeys.sign("RS256", "e1", rsa.getPrivate(), claims()));
    }

    @Test
    public void fetchesTheKeySetAgainForAnUnknownKey() throws Exception {
        KeyPair rotated = OidcTestKeys.rsa();
        JsonArray before = keys;
        JsonArray after = new JsonArray();
        after.add(OidcTestKeys.jwk("r2", rotated.getPublic()));
        String token = OidcTestKeys.sign("RS256", "r2", rotated.getPrivate(), claims());
        JsonObject verified = IdTokenVerifier.verify(token, refresh -> {
            fetches.add(refresh);
            return refresh ? after : before;
        }, ISSUER, CLIENT, NONCE, now);
        assertEquals("alice-sub", verified.get("sub").getAsString());
        assertEquals(List.of(false, true), fetches);
    }

    @Test
    public void withoutKeyIdEveryKeyOfTheTypeIsTried() throws Exception {
        JsonArray anonymous = new JsonArray();
        anonymous.add(OidcTestKeys.jwk(null, OidcTestKeys.rsa().getPublic()));
        anonymous.add(OidcTestKeys.jwk(null, rsa.getPublic()));
        keys = anonymous;
        assertEquals("alice-sub", verify(OidcTestKeys.sign("RS256", null, rsa.getPrivate(), claims())).get("sub").getAsString());
    }

    @Test
    public void rejectsMalformedTokens() {
        assertRejected("");
        assertRejected("a.b");
        assertRejected("a.b.c.d");
        assertRejected("!!!.###.$$$");
    }
}
