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
package com.cloud.storage;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;

/**
 * Names of volumes and snapshots: 1 to {@value #MAX_NAME_LENGTH} letters, digits, '.', '_' and '-', not unique. A snapshot
 * given no name is named after its volume, {@code <volume name>-snapshot-<n>}.
 */
public final class StorageNames {

    public static final int MAX_NAME_LENGTH = 64;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_NAME_LENGTH + "}");
    private static final Pattern NOT_OF_A_NAME = Pattern.compile("[^A-Za-z0-9._-]");
    private static final String SNAPSHOT_INFIX = "-snapshot-";
    private static final int MAX_NUMBER_DIGITS = 18;
    private static final Pattern NUMBERED_SNAPSHOT = Pattern.compile("(.*)" + SNAPSHOT_INFIX + "(0|[1-9][0-9]{0," + (MAX_NUMBER_DIGITS - 1) + "})",
            Pattern.CASE_INSENSITIVE);
    private static final String UNNAMED_VOLUME = "volume";

    private StorageNames() {
    }

    /**
     * @param kind what the name names, such as "volume", for the refusal
     * @throws InvalidParameterValueException unless the name follows the rule
     */
    public static void checkName(String name, String kind) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidParameterValueException(String.format("Invalid %s name '%s': a name has 1 to %d characters, each a letter, a digit, '.', '_' or '-'",
                    kind, name, MAX_NAME_LENGTH));
        }
    }

    /**
     * @param namesOfSnapshots the names the numbering continues from, those of deleted snapshots included
     * @return {@code <volume name>-snapshot-<n>}: n is one more than the highest number among the names of exactly that form, compared
     *         regardless of case, or 0 when there is none, so no number is used twice. The volume name has each
     *         character a name may not have written as '-', and is cut so that the whole stays within {@value #MAX_NAME_LENGTH} characters.
     */
    public static String defaultSnapshotName(String volumeName, Collection<String> namesOfSnapshots) {
        String base = nameMadeOf(volumeName);
        long next = 0;
        for (String name : namesOfSnapshots) {
            Matcher numbered = name == null ? null : NUMBERED_SNAPSHOT.matcher(name);
            if (numbered == null || !numbered.matches()) {
                continue;
            }
            long n = Long.parseLong(numbered.group(2));
            if (n >= next && snapshotName(base, n).equalsIgnoreCase(name)) {
                next = n + 1;
            }
        }
        return snapshotName(base, next);
    }

    /**
     * @return a name made of the text: each character a name may not have written as '-', cut to {@value #MAX_NAME_LENGTH}
     *         characters; "volume" for an empty text
     */
    public static String nameMadeOf(String text) {
        String name = StringUtils.isEmpty(text) ? UNNAMED_VOLUME : NOT_OF_A_NAME.matcher(text).replaceAll("-");
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /**
     * @return the start of every default snapshot name of a volume of that name, whatever its number
     */
    public static String defaultSnapshotNameStem(String volumeName) {
        String base = nameMadeOf(volumeName);
        int room = MAX_NAME_LENGTH - SNAPSHOT_INFIX.length() - MAX_NUMBER_DIGITS;
        return base.length() > room ? base.substring(0, room) : base;
    }

    private static String snapshotName(String base, long n) {
        String suffix = SNAPSHOT_INFIX + n;
        int room = MAX_NAME_LENGTH - suffix.length();
        return (base.length() > room ? base.substring(0, room) : base) + suffix;
    }
}
