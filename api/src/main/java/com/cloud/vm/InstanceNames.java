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
package com.cloud.vm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.cloud.exception.InvalidParameterValueException;

/**
 * Names of user Instances. An account names its Instances freely, a name being unique within the account regardless of
 * case, and an Instance's SSH name is the account's prefix, a hyphen and its name in lower case. The hostname of a new
 * Instance is the prefix, "-vm-" and a number the account never used before; the name chooses none of it, and it stays
 * when the Instance is renamed. A prefix is one or two
 * hyphen-free parts: a sign-in provider's name and the encoded ID of the account's linked user, {@value #PROJECT} and a
 * project's ID, or the name of an account without a linked user, which is neither a provider's name nor {@value #PROJECT}.
 */
public final class InstanceNames {

    public static final int MAX_NAME_LENGTH = 30;
    public static final int MAX_HOSTNAME_LENGTH = 63;
    public static final String DEFAULT_NAME_PREFIX = "VM-";
    public static final String ROOT_VOLUME_SUFFIX = "-ROOT";
    public static final String PROJECT = "project";

    private static final String SEPARATOR = "-";
    private static final String HOST_NAME_PART = "-vm-";
    private static final int MAX_HOST_NAME_NUMBER_DIGITS = 18;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?");
    private static final Pattern PREFIX_PART = Pattern.compile("[a-z][a-z0-9]*");
    private static final Pattern PLAIN_ID = Pattern.compile("[a-wyz0-9][a-z0-9]*");
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]+");
    private static final Pattern GIVEN_PREFIX = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)?");

    private InstanceNames() {
    }

    /**
     * @return the part of the username before its first '@', in lower case
     */
    public static String idOf(String username) {
        int at = username.indexOf('@');
        return (at < 0 ? username : username.substring(0, at)).toLowerCase(Locale.ROOT);
    }

    /**
     * Encodes an ID as a hyphen-free part of a prefix, injectively: an ID that is a lower-case letter other than 'x', or a
     * digit, followed by lower-case letters and digits is its own encoding, and any other ID is 'x' followed by the ID
     * with 'x', '.', '_' and '-' written as "xx", "xd", "xu" and "xh".
     *
     * @throws InvalidParameterValueException for an empty ID or one with another character
     */
    public static String encodeId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new InvalidParameterValueException(String.format("The ID %s cannot name Instances: it must be one or more lower-case letters, digits, '.', '_' or '-'",
                    id));
        }
        if (PLAIN_ID.matcher(id).matches()) {
            return id;
        }
        StringBuilder encoded = new StringBuilder("x");
        for (char c : id.toCharArray()) {
            switch (c) {
                case 'x':
                    encoded.append("xx");
                    break;
                case '.':
                    encoded.append("xd");
                    break;
                case '_':
                    encoded.append("xu");
                    break;
                case '-':
                    encoded.append("xh");
                    break;
                default:
                    encoded.append(c);
            }
        }
        return encoded.toString();
    }

    /**
     * @return the prefix of an account whose user signs in through the provider with the username
     * @throws InvalidParameterValueException naming the provider whose name is no lower-case letter followed by lower-case
     *         letters and digits, or the username whose ID cannot be encoded
     */
    public static String providerPrefix(String providerName, String username) {
        if (providerName == null || !PREFIX_PART.matcher(providerName).matches()) {
            throw new InvalidParameterValueException(String.format("Unable to name Instances after the sign-in provider %s: a provider name must be a lower-case "
                    + "letter followed by lower-case letters and digits", providerName));
        }
        if (PROJECT.equals(providerName)) {
            throw new InvalidParameterValueException(String.format("Unable to name Instances after the sign-in provider %s: the name is reserved for the "
                    + "Instances of projects", providerName));
        }
        String id;
        try {
            id = encodeId(idOf(username));
        } catch (InvalidParameterValueException e) {
            throw new InvalidParameterValueException(String.format("Unable to name Instances after the username %s: its part before '@' must be one or more "
                    + "letters, digits, '.', '_' or '-'", username));
        }
        return providerName + SEPARATOR + id;
    }

    /**
     * @param providerNames the names of all sign-in providers
     * @param nameOfAnotherAccount whether another account that is not removed has the same name regardless of case
     * @return the prefix of an account none of whose users is linked to a sign-in provider: its name in lower case
     * @throws InvalidParameterValueException naming the account when that is no letter followed by letters and digits, a
     *         provider's name or another account's
     */
    public static String localPrefix(String accountName, Collection<String> providerNames, boolean nameOfAnotherAccount) {
        String prefix = accountName == null ? "" : accountName.toLowerCase(Locale.ROOT);
        if (!PREFIX_PART.matcher(prefix).matches()) {
            throw new InvalidParameterValueException(String.format("Account %s cannot name Instances: an account that signs in through no sign-in provider "
                    + "needs a name of a letter followed by letters and digits", accountName));
        }
        if (PROJECT.equals(prefix)) {
            throw new InvalidParameterValueException(String.format("Account %s cannot name Instances: its name is reserved for the Instances of projects",
                    accountName));
        }
        for (String providerName : providerNames) {
            if (prefix.equalsIgnoreCase(providerName)) {
                throw new InvalidParameterValueException(String.format("Account %s cannot name Instances: its name is that of the sign-in provider %s",
                        accountName, providerName));
            }
        }
        if (nameOfAnotherAccount) {
            throw new InvalidParameterValueException(String.format("Account %s cannot name Instances: another account has the same name regardless of case",
                    accountName));
        }
        return prefix;
    }

    /**
     * @throws InvalidParameterValueException unless the name is 1 to {@value #MAX_NAME_LENGTH} letters, digits and
     *         hyphens, neither starting nor ending with a hyphen
     */
    public static void checkName(String name) {
        if (name == null || name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new InvalidParameterValueException(String.format("Invalid Instance name %s: a name is 1 to %d letters, digits and hyphens, and does not "
                    + "start or end with a hyphen", name, MAX_NAME_LENGTH));
        }
    }

    /**
     * @param names the names of the account's Instances
     * @throws InvalidParameterValueException when one of the names is the name regardless of case
     */
    public static void checkNameFree(String name, Collection<String> names) {
        for (String taken : names) {
            if (name.equalsIgnoreCase(taken)) {
                throw new InvalidParameterValueException(String.format("The account already has an Instance named %s; names that differ only in case are "
                        + "the same name", name));
            }
        }
    }

    /**
     * @param names the names of the account's Instances
     * @return "VM-n" for the smallest n from 0 that none of the names is, regardless of case
     */
    public static String defaultName(Collection<String> names) {
        Set<String> taken = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (int n = 0; ; n++) {
            String name = DEFAULT_NAME_PREFIX + n;
            if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
    }

    /**
     * @return the length of the longest name of an Instance whose hostname has the prefix
     */
    public static int maxNameLength(String prefix) {
        return Math.max(0, Math.min(MAX_NAME_LENGTH, MAX_HOSTNAME_LENGTH - prefix.length() - SEPARATOR.length()));
    }

    /**
     * @return the {@link #sshName(String, String)} of an Instance of the name under the prefix
     * @throws InvalidParameterValueException when that is longer than {@value #MAX_HOSTNAME_LENGTH} characters
     */
    public static String checkedSshName(String prefix, String name) {
        String hostName = sshName(prefix, name);
        if (hostName.length() > MAX_HOSTNAME_LENGTH) {
            throw new InvalidParameterValueException(String.format("The Instance name %s is too long: the Instances of this account have names of at most %d "
                    + "characters", name, maxNameLength(prefix)));
        }
        return hostName;
    }

    /**
     * @return the prefix, a hyphen and the name in lower case
     */
    public static String sshName(String prefix, String name) {
        return prefix + SEPARATOR + name.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the start of the hostnames of the Instances named under the prefix
     */
    public static String hostNameStem(String prefix) {
        return prefix + HOST_NAME_PART;
    }

    /**
     * @param hostNamesUsed the hostnames used under the prefix, by Instances removed since or moved to another account included
     * @param taken whether another Instance has a hostname
     * @return {@code <prefix>-vm-n} for n one more than the highest number among the hostnames of exactly that form, compared
     *         regardless of case, or 0 when there is none; the next number while another Instance has the hostname
     * @throws InvalidParameterValueException when the hostname would be longer than {@value #MAX_HOSTNAME_LENGTH} characters
     */
    public static String newHostName(String prefix, Collection<String> hostNamesUsed, Predicate<String> taken) {
        String stem = hostNameStem(prefix);
        Pattern numbered = Pattern.compile(Pattern.quote(stem) + "(0|[1-9][0-9]{0," + (MAX_HOST_NAME_NUMBER_DIGITS - 1) + "})", Pattern.CASE_INSENSITIVE);
        long n = 0;
        for (String hostName : hostNamesUsed) {
            Matcher matcher = hostName == null ? null : numbered.matcher(hostName);
            if (matcher != null && matcher.matches()) {
                n = Math.max(n, Long.parseLong(matcher.group(1)) + 1);
            }
        }
        for (; ; n++) {
            String hostName = stem + n;
            if (hostName.length() > MAX_HOSTNAME_LENGTH) {
                throw new InvalidParameterValueException(String.format("The prefix %s leaves no room for a hostname of at most %d characters", prefix,
                        MAX_HOSTNAME_LENGTH));
            }
            if (!taken.test(hostName)) {
                return hostName;
            }
        }
    }

    public static String projectPrefix(long projectId) {
        return PROJECT + SEPARATOR + projectId;
    }

    /**
     * Checks a prefix given in place of an account's: one or two hyphen-separated parts of lower-case letters and digits, the first
     * starting with a letter and not {@value #PROJECT}, as the prefixes of accounts are.
     */
    public static void checkPrefix(String prefix) {
        if (prefix == null || !GIVEN_PREFIX.matcher(prefix).matches() || PROJECT.equals(prefix.split(SEPARATOR, 2)[0])) {
            throw new InvalidParameterValueException(String.format("Invalid prefix %s: a prefix is a lower-case letter followed by lower-case letters and "
                    + "digits, optionally followed by a hyphen and lower-case letters and digits, and does not start with %s", prefix, PROJECT));
        }
    }

    /**
     * @return the prefixes that an SSH name can be under, each with the Instance name it then has: the SSH name's first part, and
     *         its first two parts, before a hyphen
     */
    public static Map<String, String> prefixesOf(String sshName) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        int end = -1;
        for (int parts = 0; parts < 2; parts++) {
            end = sshName.indexOf(SEPARATOR, end + 1);
            if (end <= 0 || end == sshName.length() - 1) {
                break;
            }
            prefixes.put(sshName.substring(0, end), sshName.substring(end + 1));
        }
        return prefixes;
    }

    public static String rootVolumeName(String name) {
        return name + ROOT_VOLUME_SUFFIX;
    }
}
