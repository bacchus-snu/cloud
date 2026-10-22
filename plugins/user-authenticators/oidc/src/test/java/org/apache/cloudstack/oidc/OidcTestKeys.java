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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonObject;

/** Keys, key sets and signed tokens as a provider makes them. */
final class OidcTestKeys {

    private OidcTestKeys() {
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] unsigned(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        byte[] out = new byte[length];
        if (bytes.length > length) {
            System.arraycopy(bytes, bytes.length - length, out, 0, length);
        } else {
            System.arraycopy(bytes, 0, out, length - bytes.length, bytes.length);
        }
        return out;
    }

    static JsonObject jwk(String kid, PublicKey key) {
        JsonObject jwk = new JsonObject();
        if (kid != null) {
            jwk.addProperty("kid", kid);
        }
        jwk.addProperty("use", "sig");
        if (key instanceof RSAPublicKey) {
            RSAPublicKey rsa = (RSAPublicKey) key;
            jwk.addProperty("kty", "RSA");
            byte[] n = rsa.getModulus().toByteArray();
            if (n[0] == 0) {
                n = Arrays.copyOfRange(n, 1, n.length);
            }
            jwk.addProperty("n", b64(n));
            jwk.addProperty("e", b64(rsa.getPublicExponent().toByteArray()));
        } else {
            ECPublicKey ec = (ECPublicKey) key;
            jwk.addProperty("kty", "EC");
            jwk.addProperty("crv", "P-256");
            jwk.addProperty("x", b64(unsigned(ec.getW().getAffineX(), 32)));
            jwk.addProperty("y", b64(unsigned(ec.getW().getAffineY(), 32)));
        }
        return jwk;
    }

    static JsonObject header(String alg, String kid) {
        JsonObject header = new JsonObject();
        header.addProperty("alg", alg);
        header.addProperty("typ", "JWT");
        if (kid != null) {
            header.addProperty("kid", kid);
        }
        return header;
    }

    static String sign(String alg, String kid, PrivateKey key, JsonObject claims) throws Exception {
        String input = b64(header(alg, kid).toString()) + "." + b64(claims.toString());
        Signature signature;
        switch (alg) {
            case "RS256":
                signature = Signature.getInstance("SHA256withRSA");
                break;
            case "PS256":
                signature = Signature.getInstance("RSASSA-PSS");
                signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                break;
            case "ES256":
                signature = Signature.getInstance("SHA256withECDSAinP1363Format");
                break;
            default:
                throw new IllegalArgumentException(alg);
        }
        signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signature.sign());
    }

    static String none(JsonObject claims) {
        return b64(header("none", null).toString()) + "." + b64(claims.toString()) + ".";
    }

    static String hs256(String kid, byte[] secret, JsonObject claims) throws Exception {
        String input = b64(header("HS256", kid).toString()) + "." + b64(claims.toString());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return input + "." + b64(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }
}
