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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;

public class StorageNamesTest {

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }

    @Test
    public void aNameIsOneToSixtyFourLettersDigitsDotsUnderscoresAndHyphens() {
        for (String name : new String[] {"a", "Web-1-ROOT", "data_2026.10.25", "-", ".", "_", "0", repeat('x', 64), "ROOT-5151"}) {
            StorageNames.checkName(name, "volume");
        }
    }

    @Test
    public void anyOtherNameIsRefusedNamingWhatItNames() {
        for (String name : new String[] {"", " ", "my disk", "disk/1", "디스크", "a:b", "a@b", repeat('x', 65), "tab\t", "new\nline"}) {
            InvalidParameterValueException e = assertThrows(name, InvalidParameterValueException.class, () -> StorageNames.checkName(name, "snapshot"));
            assertTrue(e.getMessage(), e.getMessage().contains("snapshot"));
            assertTrue(e.getMessage(), e.getMessage().contains("64"));
        }
        assertThrows(InvalidParameterValueException.class, () -> StorageNames.checkName(null, "volume"));
    }

    @Test
    public void aNameMadeOfAnyTextSatisfiesTheNameRule() {
        assertEquals("data-1", StorageNames.nameMadeOf("data-1"));
        assertEquals("a character a name may not have becomes a hyphen", "dir-disk-1.qcow2", StorageNames.nameMadeOf("dir/disk 1.qcow2"));
        assertEquals("cut to the longest name", repeat('x', 64), StorageNames.nameMadeOf(repeat('x', 70)));
        assertEquals("volume", StorageNames.nameMadeOf(""));
        assertEquals("volume", StorageNames.nameMadeOf(null));
        StorageNames.checkName(StorageNames.nameMadeOf("디스크 /:"), "volume");
    }

    @Test
    public void everyDefaultSnapshotNameOfAVolumeNameStartsWithItsStem() {
        assertEquals("Web-1-ROOT", StorageNames.defaultSnapshotNameStem("Web-1-ROOT"));
        assertEquals("my-disk", StorageNames.defaultSnapshotNameStem("my disk"));
        String stem = StorageNames.defaultSnapshotNameStem(repeat('v', 64));
        assertEquals(repeat('v', 36), stem);
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String name = StorageNames.defaultSnapshotName(repeat('v', 64), taken);
            assertTrue(name, name.startsWith(stem));
            taken.add(name);
        }
        assertTrue(StorageNames.defaultSnapshotName(repeat('v', 64), List.of(repeat('v', 36) + "-snapshot-999999999999999998")).startsWith(stem));
    }

    @Test
    public void theFirstDefaultSnapshotNameOfAVolumeIsNumberedZero() {
        assertEquals("Web-1-ROOT-snapshot-0", StorageNames.defaultSnapshotName("Web-1-ROOT", Collections.emptyList()));
        assertEquals("other names do not count", "Web-1-ROOT-snapshot-0", StorageNames.defaultSnapshotName("Web-1-ROOT",
                List.of("nightly", "Web-1-ROOT-snapshot", "Web-1-ROOT-snapshot-", "Web-1-ROOT-snapshot-x", "Other-ROOT-snapshot-4", "Web-1-ROOT-snapshot-1a")));
    }

    @Test
    public void aDefaultSnapshotNameIsNumberedOneAboveTheHighestNumberOfThatForm() {
        assertEquals("Web-1-ROOT-snapshot-1", StorageNames.defaultSnapshotName("Web-1-ROOT", List.of("Web-1-ROOT-snapshot-0")));
        assertEquals("a lower number freed is not reused while a higher one exists", "Web-1-ROOT-snapshot-8",
                StorageNames.defaultSnapshotName("Web-1-ROOT", List.of("Web-1-ROOT-snapshot-7", "Web-1-ROOT-snapshot-2")));
        assertEquals("names compare regardless of case", "Web-1-ROOT-snapshot-4",
                StorageNames.defaultSnapshotName("Web-1-ROOT", List.of("web-1-root-SNAPSHOT-3")));
        assertEquals("a number written with a leading zero is not of the form", "Web-1-ROOT-snapshot-0",
                StorageNames.defaultSnapshotName("Web-1-ROOT", List.of("Web-1-ROOT-snapshot-03")));
        assertEquals("a name containing the form is not of it", "Web-1-ROOT-snapshot-0",
                StorageNames.defaultSnapshotName("Web-1-ROOT", List.of("x-Web-1-ROOT-snapshot-5", "Web-1-ROOT-snapshot-5-copy")));
        assertEquals("a name of the form for a volume named like the form", "a-snapshot-1-snapshot-0",
                StorageNames.defaultSnapshotName("a-snapshot-1", List.of("a-snapshot-1", "a-snapshot-2")));
        assertEquals("numbers count as numbers, not text", "v-snapshot-11", StorageNames.defaultSnapshotName("v", List.of("v-snapshot-9", "v-snapshot-10")));
    }

    @Test
    public void aDefaultSnapshotNameCutsTheVolumeNameToStayWithinSixtyFourCharacters() {
        String volume = repeat('v', 64);
        String first = StorageNames.defaultSnapshotName(volume, Collections.emptyList());
        assertEquals(repeat('v', 64 - "-snapshot-0".length()) + "-snapshot-0", first);
        StorageNames.checkName(first, "snapshot");

        List<String> taken = new ArrayList<>();
        taken.add(first);
        assertEquals("a cut name of the form counts", repeat('v', 64 - "-snapshot-1".length()) + "-snapshot-1", StorageNames.defaultSnapshotName(volume, taken));
        taken.add(repeat('v', 64 - "-snapshot-9".length()) + "-snapshot-9");
        assertEquals("more digits cut more", repeat('v', 64 - "-snapshot-10".length()) + "-snapshot-10", StorageNames.defaultSnapshotName(volume, taken));
        assertEquals("a name cut otherwise is not of the form", repeat('v', 64 - "-snapshot-0".length()) + "-snapshot-0",
                StorageNames.defaultSnapshotName(volume, List.of(repeat('v', 30) + "-snapshot-6")));
    }

    @Test
    public void aDefaultSnapshotNameAlwaysSatisfiesTheNameRule() {
        assertEquals("a character a name may not have becomes a hyphen", "my-disk-snapshot-0", StorageNames.defaultSnapshotName("my disk", Collections.emptyList()));
        assertEquals("my-disk-snapshot-3", StorageNames.defaultSnapshotName("my disk", List.of("my-disk-snapshot-2")));
        for (String volume : new String[] {"디스크", "", null, repeat(' ', 70), "a/b:c"}) {
            StorageNames.checkName(StorageNames.defaultSnapshotName(volume, Collections.emptyList()), "snapshot");
        }
        assertEquals("a volume without a name", "volume-snapshot-0", StorageNames.defaultSnapshotName(null, Collections.emptyList()));
        assertEquals("volume-snapshot-0", StorageNames.defaultSnapshotName("", Collections.emptyList()));
    }
}
