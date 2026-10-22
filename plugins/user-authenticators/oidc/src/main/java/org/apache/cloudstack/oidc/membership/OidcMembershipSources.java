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

import java.net.http.HttpClient;
import java.time.Clock;

import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A provider's membership source, a JSON object whose {@code type} picks the kind:
 * <ul>
 * <li>{@code google-directory}: the Google Workspace Directory; the source's secret is a service account's JSON key,
 * and the optional {@code subject} is the Workspace user the service account acts for.</li>
 * </ul>
 */
public final class OidcMembershipSources {

    public static final String TYPE = "type";
    public static final String GOOGLE_DIRECTORY = "google-directory";
    public static final String SUBJECT = "subject";

    private OidcMembershipSources() {
    }

    /**
     * @throws InvalidParameterValueException if the source or its secret is not valid
     */
    public static void validate(String source, String secret) {
        if (StringUtils.isBlank(source)) {
            return;
        }
        try {
            create(source, secret, null, null);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException(String.format("Invalid membership source: %s", e.getMessage()));
        }
    }

    /**
     * @return the source, or null when the provider has none
     * @throws IllegalArgumentException if the source or its secret is not valid
     */
    public static OidcMembershipSource create(String source, String secret, HttpClient http, Clock clock) {
        if (StringUtils.isBlank(source)) {
            return null;
        }
        JsonObject config = object(source, "the source");
        JsonElement type = config.get(TYPE);
        if (type == null || !type.isJsonPrimitive() || !GOOGLE_DIRECTORY.equals(type.getAsString())) {
            throw new IllegalArgumentException(String.format("type must be %s", GOOGLE_DIRECTORY));
        }
        for (String key : config.keySet()) {
            if (!TYPE.equals(key) && !SUBJECT.equals(key)) {
                throw new IllegalArgumentException(String.format("unknown field %s", key));
            }
        }
        JsonElement subject = config.get(SUBJECT);
        if (subject != null && (!subject.isJsonPrimitive() || StringUtils.isBlank(subject.getAsString()))) {
            throw new IllegalArgumentException("subject must be an e-mail address");
        }
        if (StringUtils.isBlank(secret)) {
            throw new IllegalArgumentException(String.format("%s needs a service account key as its secret", GOOGLE_DIRECTORY));
        }
        return GoogleDirectoryMembershipSource.create(http, clock, object(secret, "the secret"), subject == null ? null : subject.getAsString());
    }

    private static JsonObject object(String json, String what) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(String.format("%s is not a JSON object", what));
        }
    }
}
