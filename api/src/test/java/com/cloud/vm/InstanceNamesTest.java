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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;

public class InstanceNamesTest {

    private static final List<String> NO_PROVIDERS = Collections.emptyList();

    private static String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }

    @Test
    public void anIdOfALetterOtherThanXOrADigitFollowedByLettersAndDigitsIsItsOwnEncoding() {
        assertEquals("baneling100", InstanceNames.encodeId("baneling100"));
        assertEquals("whnbaek", InstanceNames.encodeId("whnbaek"));
        assertEquals("a", InstanceNames.encodeId("a"));
        assertEquals("2019", InstanceNames.encodeId("2019"));
        assertEquals("yz9", InstanceNames.encodeId("yz9"));
        assertEquals("ax", InstanceNames.encodeId("ax"));
    }

    @Test
    public void anyOtherIdIsXFollowedByTheIdWithItsSpecialCharactersEscaped() {
        assertEquals("xaxdb", InstanceNames.encodeId("a.b"));
        assertEquals("xxxenon", InstanceNames.encodeId("xenon"));
        assertEquals("xxx", InstanceNames.encodeId("x"));
        assertEquals("xaxub", InstanceNames.encodeId("a_b"));
        assertEquals("xaxhb", InstanceNames.encodeId("a-b"));
        assertEquals("xxdxuxhxx", InstanceNames.encodeId("._-x"));
        assertEquals("xxha", InstanceNames.encodeId("-a"));
    }

    @Test
    public void encodingsAreHyphenFreeAndDistinct() {
        List<String> ids = List.of("a", "x", "xa", "ax", "a.b", "axdb", "xaxdb", "a_b", "a-b", "a.b.c", "xx", "xd", ".", "_", "-", "a..b", "a.xb",
                "2019", "x2019", "baneling100", "xenon", "xxenon");
        Set<String> encodings = new HashSet<>();
        for (String id : ids) {
            String encoded = InstanceNames.encodeId(id);
            assertFalse(encoded, encoded.contains("-"));
            assertTrue(encoded, encoded.matches("[a-z0-9]+"));
            encodings.add(encoded);
        }
        assertEquals(ids.size(), encodings.size());
    }

    @Test
    public void anIdWithAnyOtherCharacterOrNoCharacterIsRefused() {
        for (String id : List.of("", "a b", "a+b", "a/b", "Ab", "가나", "a@b", "a:b")) {
            assertThrows(id, InvalidParameterValueException.class, () -> InstanceNames.encodeId(id));
        }
    }

    @Test
    public void theIdOfAUsernameIsItsPartBeforeTheFirstAtSignLowerCased() {
        assertEquals("whnbaek", InstanceNames.idOf("whnbaek@bacchus.snucse.org"));
        assertEquals("baneling100", InstanceNames.idOf("baneling100"));
        assertEquals("mixedcase", InstanceNames.idOf("MixedCase@Example.org"));
        assertEquals("a", InstanceNames.idOf("a@b@c"));
        assertEquals("", InstanceNames.idOf("@b"));
    }

    @Test
    public void aLinkedAccountsPrefixIsTheProviderAndTheEncodedIdOfItsUser() {
        assertEquals("snucse-baneling100", InstanceNames.providerPrefix("snucse", "baneling100"));
        assertEquals("bacchus-whnbaek", InstanceNames.providerPrefix("bacchus", "whnbaek@bacchus.snucse.org"));
        assertEquals("bacchus-xaxdb", InstanceNames.providerPrefix("bacchus", "A.B@bacchus.snucse.org"));
        assertEquals("bacchus-xxxenon", InstanceNames.providerPrefix("bacchus", "xenon@bacchus.snucse.org"));
    }

    @Test
    public void aProviderWhoseNameIsNoHyphenFreeLowerCaseWordIsRefusedByName() {
        for (String provider : List.of("snu-cse", "SNUCSE", "1snucse", "", "snu_cse", "snu.cse")) {
            InvalidParameterValueException e = assertThrows(provider, InvalidParameterValueException.class,
                    () -> InstanceNames.providerPrefix(provider, "baneling100"));
            assertTrue(e.getMessage(), e.getMessage().contains("provider " + provider + ":"));
        }
    }

    @Test
    public void aUsernameWhoseIdCannotBeEncodedIsRefusedByName() {
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class,
                () -> InstanceNames.providerPrefix("bacchus", "a+b@bacchus.snucse.org"));
        assertTrue(e.getMessage(), e.getMessage().contains("a+b@bacchus.snucse.org"));
        assertThrows(InvalidParameterValueException.class, () -> InstanceNames.providerPrefix("bacchus", "@bacchus.snucse.org"));
    }

    @Test
    public void aLocalAccountsPrefixIsItsNameLowerCased() {
        assertEquals("admin", InstanceNames.localPrefix("admin", List.of("snucse", "bacchus"), false));
        assertEquals("admin", InstanceNames.localPrefix("Admin", NO_PROVIDERS, false));
        assertEquals("ops2", InstanceNames.localPrefix("ops2", NO_PROVIDERS, false));
    }

    @Test
    public void aLocalAccountWhoseNameIsNoHyphenFreeWordIsRefusedByName() {
        for (String account : List.of("whnbaek@bacchus.snucse.org", "ops-team", "2admin", "a_b", "a.b", "", "관리자")) {
            InvalidParameterValueException e = assertThrows(account, InvalidParameterValueException.class,
                    () -> InstanceNames.localPrefix(account, NO_PROVIDERS, false));
            assertTrue(e.getMessage(), e.getMessage().contains("Account " + account + " "));
        }
    }

    @Test
    public void aLocalAccountNamedAsAProviderIsRefusedByName() {
        for (String account : List.of("snucse", "Bacchus")) {
            InvalidParameterValueException e = assertThrows(account, InvalidParameterValueException.class,
                    () -> InstanceNames.localPrefix(account, List.of("snucse", "bacchus"), false));
            assertTrue(e.getMessage(), e.getMessage().contains("Account " + account + " "));
            assertTrue(e.getMessage(), e.getMessage().contains("provider"));
        }
    }

    @Test
    public void aLocalAccountWhoseNameAnotherAccountSharesIsRefusedByName() {
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> InstanceNames.localPrefix("admin", NO_PROVIDERS, true));
        assertTrue(e.getMessage(), e.getMessage().contains("Account admin "));
        assertTrue(e.getMessage(), e.getMessage().contains("another account"));
    }

    @Test
    public void aNameIsOneToThirtyLettersDigitsAndInnerHyphens() {
        for (String name : List.of("a", "web", "Web-1", "2nd", "a-b-c", "A1", "a--b", repeat('a', 30), "VM-0")) {
            InstanceNames.checkName(name);
        }
        for (String name : List.of("", "-web", "web-", "-", "we b", "web_1", "web.1", "웹", repeat('a', 31), "a" + repeat('-', 29) + "b")) {
            InvalidParameterValueException e = assertThrows(name, InvalidParameterValueException.class, () -> InstanceNames.checkName(name));
            assertTrue(e.getMessage(), e.getMessage().contains(name));
        }
        assertThrows(InvalidParameterValueException.class, () -> InstanceNames.checkName(null));
    }

    @Test
    public void aNameIsTakenRegardlessOfCase() {
        List<String> names = List.of("Web", "db");
        InstanceNames.checkNameFree("api", names);
        for (String name : List.of("web", "WEB", "Web", "DB")) {
            InvalidParameterValueException e = assertThrows(name, InvalidParameterValueException.class, () -> InstanceNames.checkNameFree(name, names));
            assertTrue(e.getMessage(), e.getMessage().contains(name));
        }
    }

    @Test
    public void theDefaultNameIsTheFirstFreeVmNumber() {
        assertEquals("VM-0", InstanceNames.defaultName(Collections.emptyList()));
        assertEquals("VM-1", InstanceNames.defaultName(List.of("VM-0", "vm-2")));
        assertEquals("VM-0", InstanceNames.defaultName(List.of("VM-1", "web")));
        assertEquals("VM-3", InstanceNames.defaultName(List.of("vm-0", "Vm-1", "VM-2")));
        assertEquals("VM-0", InstanceNames.defaultName(List.of("VM-00", "VM-")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add("VM-" + i);
        }
        assertEquals("VM-1000", InstanceNames.defaultName(many));
    }

    @Test
    public void theSshNameOfANewInstanceIsThePrefixAndTheNameLowerCased() {
        assertEquals("snucse-baneling100-web-1", InstanceNames.checkedSshName("snucse-baneling100", "Web-1"));
        assertEquals("admin-vm-0", InstanceNames.checkedSshName("admin", "VM-0"));
    }

    @Test
    public void anSshNameOfSixtyThreeCharactersIsTheLongest() {
        String prefix = "bacchus-" + repeat('p', 24);
        assertEquals(32, prefix.length());
        String name = repeat('n', 30);
        String sshName = InstanceNames.checkedSshName(prefix, name);
        assertEquals(63, sshName.length());
        assertEquals(30, InstanceNames.maxNameLength(prefix));

        String longerPrefix = prefix + "q";
        assertEquals(29, InstanceNames.maxNameLength(longerPrefix));
        assertEquals(63, InstanceNames.checkedSshName(longerPrefix, repeat('n', 29)).length());
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> InstanceNames.checkedSshName(longerPrefix, name));
        assertTrue(e.getMessage(), e.getMessage().contains(name));
        assertTrue(e.getMessage(), e.getMessage().contains(" 29 "));
    }

    @Test
    public void theLongestNameIsThirtyCharactersUnlessThePrefixLeavesLess() {
        assertEquals(30, InstanceNames.maxNameLength("admin"));
        assertEquals(30, InstanceNames.maxNameLength(repeat('p', 32)));
        assertEquals(10, InstanceNames.maxNameLength(repeat('p', 52)));
        assertEquals(1, InstanceNames.maxNameLength(repeat('p', 61)));
        assertEquals(0, InstanceNames.maxNameLength(repeat('p', 62)));
        assertEquals(0, InstanceNames.maxNameLength(repeat('p', 70)));
    }

    @Test
    public void distinctPrefixesGiveDistinctSshNames() {
        assertNotEquals(InstanceNames.checkedSshName("snucse-foo", "bar"), InstanceNames.checkedSshName(InstanceNames.localPrefix("snucsefoo", List.of("snucse"), false), "bar"));
        assertNotEquals(InstanceNames.checkedSshName(InstanceNames.providerPrefix("snucse", "a.b"), "c"),
                InstanceNames.checkedSshName(InstanceNames.providerPrefix("snucse", "a"), "b-c"));
    }

    @Test
    public void theSshNameIsThePrefixAndTheCurrentNameLowerCasedWhateverItsLength() {
        assertEquals("snucse-baneling100-web-1", InstanceNames.sshName("snucse-baneling100", "Web-1"));
        String prefix = repeat('p', 40);
        assertEquals(prefix + "-" + repeat('n', 30), InstanceNames.sshName(prefix, repeat('N', 30)));
    }

    @Test
    public void aProjectsPrefixIsTheReservedWordProjectAndItsId() {
        assertEquals("project-12", InstanceNames.projectPrefix(12L));
        assertEquals("project-12-web", InstanceNames.checkedSshName(InstanceNames.projectPrefix(12L), "Web"));
    }

    @Test
    public void neitherALocalAccountNorAProviderMayBeNamedProject() {
        for (String account : List.of("project", "Project", "PROJECT")) {
            InvalidParameterValueException e = assertThrows(account, InvalidParameterValueException.class,
                    () -> InstanceNames.localPrefix(account, NO_PROVIDERS, false));
            assertTrue(e.getMessage(), e.getMessage().contains("Account " + account + " "));
            assertTrue(e.getMessage(), e.getMessage().contains("reserved"));
        }
        InvalidParameterValueException e = assertThrows(InvalidParameterValueException.class, () -> InstanceNames.providerPrefix("project", "a"));
        assertTrue(e.getMessage(), e.getMessage().contains("provider project:"));
        assertEquals("projects", InstanceNames.localPrefix("projects", NO_PROVIDERS, false));
    }

    @Test
    public void theRootVolumeIsNamedAfterTheInstance() {
        assertEquals("Web-1-ROOT", InstanceNames.rootVolumeName("Web-1"));
        assertEquals("VM-0-ROOT", InstanceNames.rootVolumeName("VM-0"));
    }

    @Test
    public void aNewHostnameIsThePrefixVmAndOneMoreThanTheHighestNumberTheAccountUsed() {
        assertEquals("admin-vm-0", InstanceNames.newHostName("admin", List.of(), h -> false));
        assertEquals("admin-vm-1", InstanceNames.newHostName("admin", List.of("admin-vm-0"), h -> false));
        assertEquals("the number of a removed Instance is not used again", "admin-vm-3",
                InstanceNames.newHostName("admin", List.of("admin-vm-0", "ADMIN-VM-2"), h -> false));
        assertEquals("numbers compare as numbers", "admin-vm-11", InstanceNames.newHostName("admin", List.of("admin-vm-9", "admin-vm-10"), h -> false));
        assertEquals("other hostnames do not count", "admin-vm-0", InstanceNames.newHostName("admin",
                List.of("admin-web", "admin-vm-", "admin-vm-01", "admin-vm-1-1", "admin-vm-x", "other-vm-5", "xadmin-vm-4"), h -> false));
        assertEquals("project-12-vm-0", InstanceNames.newHostName(InstanceNames.projectPrefix(12L), List.of(), h -> false));
        assertEquals("admin-vm-", InstanceNames.hostNameStem("admin"));
    }

    @Test
    public void aNewHostnameAnotherInstanceHasTakesTheNextNumber() {
        Set<String> taken = new HashSet<>(Set.of("admin-vm-1", "admin-vm-2"));
        assertEquals("admin-vm-3", InstanceNames.newHostName("admin", List.of("admin-vm-0"), taken::contains));
    }

    @Test
    public void aPrefixLeavingNoRoomForAHostnameIsRefused() {
        String prefix = "bacchus-" + repeat('p', 50);
        assertEquals(63, InstanceNames.newHostName(prefix, List.of(), h -> false).length());
        assertThrows(InvalidParameterValueException.class, () -> InstanceNames.newHostName(prefix, List.of(prefix + "-vm-9"), h -> false));
    }

    @Test
    public void aNameTooLongForItsSshNameIsStillRefused() {
        String prefix = "bacchus-" + repeat('p', 25);
        assertThrows(InvalidParameterValueException.class, () -> InstanceNames.checkedSshName(prefix, repeat('n', 30)));
    }

    @Test
    public void theNamePrefixDetailIsNamedRegardlessOfCase() {
        for (String name : List.of("ssh.name.prefix", "SSH.NAME.PREFIX", "Ssh.Name.Prefix")) {
            assertTrue(name, VmDetailConstants.isSshNamePrefix(name));
        }
        assertFalse(VmDetailConstants.isSshNamePrefix("ssh.name.prefixes"));
        assertFalse(VmDetailConstants.isSshNamePrefix(null));
    }

    @Test
    public void aPrefixARootAdminGivesIsOneOrTwoLowerCaseWordsNotStartingWithProject() {
        for (String prefix : List.of("team", "team-a1", "ops-alice", "projects")) {
            InstanceNames.checkPrefix(prefix);
        }
        for (String prefix : List.of("Team", "a-b-c", "project", "project-3", "-a", "a-", "a_b", "", "1team", "team--a")) {
            InvalidParameterValueException e = assertThrows(prefix, InvalidParameterValueException.class, () -> InstanceNames.checkPrefix(prefix));
            assertTrue(e.getMessage(), e.getMessage().contains(prefix));
        }
    }

    @Test
    public void anSshNameIsUnderItsFirstOneOrTwoParts() {
        assertEquals(Map.of("ops", "alice-web", "ops-alice", "web"), InstanceNames.prefixesOf("ops-alice-web"));
        assertEquals(List.of("ops", "ops-alice"), new ArrayList<>(InstanceNames.prefixesOf("ops-alice-web").keySet()));
        assertEquals(Map.of("admin", "web"), InstanceNames.prefixesOf("admin-web"));
        assertEquals(Map.of("a", "b-c-d", "a-b", "c-d"), InstanceNames.prefixesOf("a-b-c-d"));
        assertTrue(InstanceNames.prefixesOf("admin").isEmpty());
        assertTrue(InstanceNames.prefixesOf("admin-").isEmpty());
    }
}
