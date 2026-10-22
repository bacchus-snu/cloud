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
package org.apache.cloudstack.framework.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the request parameters that carry the value of a setting stored encrypted, and masks those values in the
 * forms a request is logged in. Such a parameter is the {@code value} of a setting written by its {@code name}, the
 * {@code x[i].value} of a map entry whose {@code x[i].key} or {@code x[i].name} names such a setting, or a map entry
 * field named after such a setting.
 */
public final class EncryptedSettingParameters {
    public static final String MASK = "*****";
    private static final Pattern MAP_ENTRY_PARAM = Pattern.compile("^([^\\[\\]]+)\\[(\\d+)\\]\\.(.+)$");

    private EncryptedSettingParameters() {
    }

    /**
     * @param encryptedSetting whether the setting of a name is stored encrypted, as {@link ConfigDepot#isEncryptedSetting} decides
     */
    public static Set<String> in(Map<String, String[]> params, Predicate<String> encryptedSetting) {
        Set<String> masked = new HashSet<>();
        if (params == null || params.isEmpty()) {
            return masked;
        }
        String name = null;
        String valueParam = null;
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            String key = param.getKey();
            if (key == null) {
                continue;
            }
            if ("name".equalsIgnoreCase(key)) {
                name = firstValue(param.getValue());
            } else if ("value".equalsIgnoreCase(key)) {
                valueParam = key;
            }
            Matcher entry = MAP_ENTRY_PARAM.matcher(key);
            if (!entry.matches()) {
                continue;
            }
            String field = entry.group(3);
            if ("key".equalsIgnoreCase(field) || "name".equalsIgnoreCase(field)) {
                if (encryptedSetting.test(firstValue(param.getValue()))) {
                    String value = entry.group(1) + "[" + entry.group(2) + "].value";
                    params.keySet().stream().filter(value::equalsIgnoreCase).forEach(masked::add);
                }
            } else if (!"value".equalsIgnoreCase(field) && encryptedSetting.test(field)) {
                masked.add(key);
            }
        }
        if (valueParam != null && encryptedSetting.test(name)) {
            masked.add(valueParam);
        }
        return masked;
    }

    /**
     * As {@link #in(Map, Predicate)} for parameters of one value each, as an async job records them.
     */
    public static Set<String> inSingleValued(Map<String, String> params, Predicate<String> encryptedSetting) {
        Map<String, String[]> multi = new HashMap<>();
        if (params != null) {
            params.forEach((key, value) -> multi.put(key, new String[] {value}));
        }
        return in(multi, encryptedSetting);
    }

    private static String firstValue(String[] values) {
        return values == null || values.length == 0 ? null : values[0];
    }

    /**
     * @return the query string with the values of the given parameters masked, under their plain or URL-encoded names
     */
    public static String maskQueryString(String queryString, Set<String> names) {
        if (queryString == null || names == null) {
            return queryString;
        }
        String masked = queryString;
        for (String param : names) {
            for (String name : new String[] {param, URLEncoder.encode(param, StandardCharsets.UTF_8)}) {
                masked = Pattern.compile("(^|[?&])(" + Pattern.quote(name) + ")=[^&]*", Pattern.CASE_INSENSITIVE).matcher(masked)
                        .replaceAll("$1$2=" + Matcher.quoteReplacement(MASK));
            }
        }
        return masked;
    }
}
