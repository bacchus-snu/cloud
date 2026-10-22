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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Verifies an ID token (OpenID Connect Core 1.0, 3.1.3.7) against the provider's published keys: an asymmetric
 * signature by an allowed algorithm, then issuer, audience, authorized party, expiry, issue time, nonce and subject.
 */
final class IdTokenVerifier {

    static final Set<String> ALGORITHMS = Set.of("ES256", "RS256", "PS256");

    static final long CLOCK_SKEW_SECONDS = 60;

    private static final int MIN_RSA_BITS = 2048;
    private static final int MAX_SUBJECT_LENGTH = 255;

    interface KeySource {
        /**
         * @param refresh true to fetch the key set again, after a token named a key the cached set lacks
         * @return the provider's JSON Web Key Set keys
         */
        JsonArray keys(boolean refresh) throws OidcLoginException;
    }

    private IdTokenVerifier() {
    }

    private static OidcLoginException invalid(String reason) {
        return new OidcLoginException(OidcLoginError.FAILED, "ID token: " + reason);
    }

    /**
     * @return the token's claims
     * @throws OidcLoginException with {@link OidcLoginError#FAILED} when the token fails any check
     */
    static JsonObject verify(String token, KeySource keys, String issuer, String clientId, String nonce, Instant now) throws OidcLoginException {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw invalid("not a signed JWT");
        }
        JsonObject header = jsonPart(parts[0], "header");
        String alg = OidcClient.string(header, "alg");
        if (!ALGORITHMS.contains(alg)) {
            throw invalid("algorithm " + alg + " is not allowed");
        }
        if (header.has("crit")) {
            throw invalid("critical header parameters are not understood");
        }
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] signature = decode(parts[2], "signature");
        String kid = OidcClient.string(header, "kid");
        List<PublicKey> candidates = candidates(keys.keys(false), alg, kid);
        if (candidates.isEmpty() && kid != null) {
            candidates = candidates(keys.keys(true), alg, kid);
        }
        if (candidates.isEmpty()) {
            throw invalid("no key of the provider fits " + alg + (kid == null ? "" : " key " + kid));
        }
        boolean verified = false;
        for (PublicKey key : candidates) {
            if (verifies(alg, key, signed, signature)) {
                verified = true;
                break;
            }
        }
        if (!verified) {
            throw invalid("the signature does not verify");
        }
        JsonObject claims = jsonPart(parts[1], "claims");
        checkClaims(claims, issuer, clientId, nonce, now);
        return claims;
    }

    private static void checkClaims(JsonObject claims, String issuer, String clientId, String nonce, Instant now) throws OidcLoginException {
        if (!issuer.equals(OidcClient.string(claims, "iss"))) {
            throw invalid("issuer " + OidcClient.string(claims, "iss") + " is not " + issuer);
        }
        List<String> audiences = new ArrayList<>();
        JsonElement aud = claims.get("aud");
        if (aud != null && aud.isJsonArray()) {
            for (JsonElement a : aud.getAsJsonArray()) {
                if (a.isJsonPrimitive()) {
                    audiences.add(a.getAsString());
                }
            }
        } else if (aud != null && aud.isJsonPrimitive()) {
            audiences.add(aud.getAsString());
        }
        if (!audiences.contains(clientId)) {
            throw invalid("the client is not an audience");
        }
        String azp = OidcClient.string(claims, "azp");
        if (azp != null && !azp.equals(clientId)) {
            throw invalid("authorized party " + azp + " is not the client");
        }
        if (azp == null && audiences.size() > 1) {
            throw invalid("several audiences and no authorized party");
        }
        long nowSeconds = now.getEpochSecond();
        Long exp = number(claims, "exp");
        if (exp == null || exp + CLOCK_SKEW_SECONDS <= nowSeconds) {
            throw invalid("expired");
        }
        Long iat = number(claims, "iat");
        if (iat == null || iat > nowSeconds + CLOCK_SKEW_SECONDS) {
            throw invalid("no issue time, or one in the future");
        }
        if (claims.has("nbf")) {
            Long nbf = number(claims, "nbf");
            if (nbf == null || nbf > nowSeconds + CLOCK_SKEW_SECONDS) {
                throw invalid("not valid yet");
            }
        }
        String tokenNonce = OidcClient.string(claims, "nonce");
        if (tokenNonce == null || !MessageDigest.isEqual(tokenNonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("the nonce is not the sign-in's");
        }
        String sub = OidcClient.string(claims, "sub");
        if (sub == null || sub.isEmpty() || sub.length() > MAX_SUBJECT_LENGTH) {
            throw invalid("no usable subject");
        }
    }

    private static Long number(JsonObject claims, String name) {
        JsonElement value = claims.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return value.getAsJsonPrimitive().getAsBigDecimal().longValue();
    }

    private static byte[] decode(String part, String what) throws OidcLoginException {
        try {
            return Base64.getUrlDecoder().decode(part);
        } catch (IllegalArgumentException e) {
            throw invalid(what + " is not base64url");
        }
    }

    private static JsonObject jsonPart(String part, String what) throws OidcLoginException {
        try {
            return JsonParser.parseString(new String(decode(part, what), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw invalid(what + " is not a JSON object");
        }
    }

    private static List<PublicKey> candidates(JsonArray keys, String alg, String kid) {
        List<PublicKey> candidates = new ArrayList<>();
        for (JsonElement element : keys) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject jwk = element.getAsJsonObject();
            String use = OidcClient.string(jwk, "use");
            String keyAlg = OidcClient.string(jwk, "alg");
            if ((kid != null && !kid.equals(OidcClient.string(jwk, "kid"))) || (use != null && !"sig".equals(use))
                    || (keyAlg != null && !keyAlg.equals(alg))) {
                continue;
            }
            PublicKey key = publicKey(jwk, alg);
            if (key != null) {
                candidates.add(key);
            }
        }
        return candidates;
    }

    /** @return the key if the JWK is one the algorithm signs with, else null */
    static PublicKey publicKey(JsonObject jwk, String alg) {
        String kty = OidcClient.string(jwk, "kty");
        try {
            if ("ES256".equals(alg) && "EC".equals(kty) && "P-256".equals(OidcClient.string(jwk, "crv"))) {
                byte[] x = Base64.getUrlDecoder().decode(OidcClient.string(jwk, "x"));
                byte[] y = Base64.getUrlDecoder().decode(OidcClient.string(jwk, "y"));
                if (x.length != 32 || y.length != 32) {
                    return null;
                }
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
            }
            if (("RS256".equals(alg) || "PS256".equals(alg)) && "RSA".equals(kty)) {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(OidcClient.string(jwk, "n")));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(OidcClient.string(jwk, "e")));
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                return key.getModulus().bitLength() >= MIN_RSA_BITS ? key : null;
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean verifies(String alg, PublicKey key, byte[] signed, byte[] signature) {
        try {
            Signature verifier;
            switch (alg) {
                case "RS256":
                    verifier = Signature.getInstance("SHA256withRSA");
                    break;
                case "PS256":
                    verifier = Signature.getInstance("RSASSA-PSS");
                    verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                    break;
                case "ES256":
                    if (signature.length != 64) {
                        return false;
                    }
                    verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                    break;
                default:
                    return false;
            }
            verifier.initVerify(key);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
