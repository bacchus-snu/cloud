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
package com.cloud.network.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.net.NetUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The rules of {@link SecurityGroupManager#DefaultGroupIngressRules}.
 */
public final class SecurityGroupRuleTemplate {

    private static final Set<String> FIELDS = Set.of("protocol", "startport", "endport", "icmptype", "icmpcode", "cidrlist", "owngroup");

    private SecurityGroupRuleTemplate() {
    }

    /** One rule of the template, in the form a security group rule is stored. */
    public static final class Rule {
        private final String protocol;
        private final int startPortOrType;
        private final int endPortOrCode;
        private final List<String> cidrs;
        private final boolean ownGroup;

        Rule(String protocol, int startPortOrType, int endPortOrCode, List<String> cidrs, boolean ownGroup) {
            this.protocol = protocol;
            this.startPortOrType = startPortOrType;
            this.endPortOrCode = endPortOrCode;
            this.cidrs = cidrs;
            this.ownGroup = ownGroup;
        }

        public String getProtocol() {
            return protocol;
        }

        public int getStartPortOrType() {
            return startPortOrType;
        }

        public int getEndPortOrCode() {
            return endPortOrCode;
        }

        /** @return the source CIDRs, empty for a rule that admits the own group */
        public List<String> getCidrs() {
            return cidrs;
        }

        public boolean isOwnGroup() {
            return ownGroup;
        }
    }

    /**
     * @throws InvalidParameterValueException if the value is not a template
     */
    public static void validate(String value) {
        parse(value);
    }

    /**
     * @return the rules of the template; none for an empty value
     * @throws InvalidParameterValueException if the value is not a template
     */
    public static List<Rule> parse(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        JsonArray array;
        try {
            array = JsonParser.parseString(value).getAsJsonArray();
        } catch (RuntimeException e) {
            throw new InvalidParameterValueException("The default security group rules must be a JSON array of rules");
        }
        List<Rule> rules = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new InvalidParameterValueException("Each default security group rule must be a JSON object");
            }
            rules.add(rule(element.getAsJsonObject()));
        }
        return Collections.unmodifiableList(rules);
    }

    private static Rule rule(JsonObject rule) {
        for (String field : rule.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new InvalidParameterValueException(String.format("Unknown field %s in default security group rule %s", field, rule));
            }
        }
        String protocol = StringUtils.lowerCase(StringUtils.trimToNull(string(rule, "protocol")));
        if (protocol == null) {
            throw new InvalidParameterValueException(String.format("Default security group rule %s has no protocol", rule));
        }
        if (StringUtils.isNumeric(protocol)) {
            int number = Integer.parseInt(protocol);
            if (number == NetUtils.ICMP_PROTO_NUMBER) {
                protocol = NetUtils.ICMP_PROTO;
            } else if (number > 255) {
                throw new InvalidParameterValueException(String.format("Invalid protocol number in default security group rule %s", rule));
            }
        } else if (!NetUtils.isValidSecurityGroupProto(protocol)) {
            throw new InvalidParameterValueException(String.format("Invalid protocol in default security group rule %s", rule));
        }
        Integer start = integer(rule, "startport");
        Integer end = integer(rule, "endport");
        Integer icmpType = integer(rule, "icmptype");
        Integer icmpCode = integer(rule, "icmpcode");
        int startPortOrType;
        int endPortOrCode;
        if (NetUtils.TCP_PROTO.equals(protocol) || NetUtils.UDP_PROTO.equals(protocol)) {
            if (start == null || end == null || start < 0 || end > 65535 || start > end || icmpType != null || icmpCode != null) {
                throw new InvalidParameterValueException(String.format("Default security group rule %s needs a port range from 0 to 65535", rule));
            }
            startPortOrType = start;
            endPortOrCode = end;
        } else if (NetUtils.ICMP_PROTO.equals(protocol)) {
            if (start != null || end != null) {
                throw new InvalidParameterValueException(String.format("Default security group rule %s takes icmptype and icmpcode, not ports", rule));
            }
            startPortOrType = icmpType == null ? -1 : icmpType;
            endPortOrCode = icmpCode == null ? -1 : icmpCode;
            try {
                NetUtils.validateIcmpTypeAndCode(startPortOrType, endPortOrCode);
            } catch (RuntimeException e) {
                throw new InvalidParameterValueException(String.format("Invalid ICMP type or code in default security group rule %s", rule));
            }
        } else {
            if (start != null || end != null || icmpType != null || icmpCode != null) {
                throw new InvalidParameterValueException(String.format("Default security group rule %s takes no ports", rule));
            }
            startPortOrType = 0;
            endPortOrCode = 0;
        }
        List<String> cidrs = new ArrayList<>();
        JsonElement cidrList = rule.get("cidrlist");
        if (cidrList != null) {
            if (!cidrList.isJsonArray()) {
                throw new InvalidParameterValueException(String.format("cidrlist of default security group rule %s must be an array", rule));
            }
            for (JsonElement cidr : cidrList.getAsJsonArray()) {
                String value = cidr.isJsonPrimitive() ? cidr.getAsString().trim() : "";
                if (!NetUtils.isValidIp4Cidr(value) && !NetUtils.isValidIp6Cidr(value)) {
                    throw new InvalidParameterValueException(String.format("Invalid CIDR %s in default security group rule %s", value, rule));
                }
                cidrs.add(value);
            }
        }
        JsonElement ownGroup = rule.get("owngroup");
        boolean own = ownGroup != null && ownGroup.isJsonPrimitive() && ownGroup.getAsJsonPrimitive().isBoolean() && ownGroup.getAsBoolean();
        if (cidrs.isEmpty() == !own) {
            throw new InvalidParameterValueException(String.format("Default security group rule %s needs either a cidrlist or \"owngroup\": true", rule));
        }
        return new Rule(protocol, startPortOrType, endPortOrCode, Collections.unmodifiableList(cidrs), own);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static Integer integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InvalidParameterValueException(String.format("%s of a default security group rule must be a number", name));
        }
        return value.getAsInt();
    }
}
