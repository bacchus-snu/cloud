//
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
//

package org.apache.cloudstack.utils.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ManagementServerNodeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path hostNameFile(String content) throws IOException {
        Path file = folder.newFile().toPath();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void theNodeIdIsTheIdOfTheKernelHostName() throws IOException {
        Assume.assumeTrue(Files.isReadable(ManagementServerNode.HOST_NAME_FILE));
        String hostName = new String(Files.readAllBytes(ManagementServerNode.HOST_NAME_FILE), StandardCharsets.UTF_8).trim();
        Assert.assertEquals(ManagementServerNode.nodeIdOf(hostName), ManagementServerNode.getManagementServerId());
    }

    @Test
    public void theHostNameIsReadAsSetWithoutResolvingIt() throws IOException {
        Assert.assertEquals(ManagementServerNode.nodeIdOf("ms-0.unresolvable.invalid"),
                ManagementServerNode.nodeIdOfHostNameIn(hostNameFile("ms-0.unresolvable.invalid\n")));
        Assert.assertEquals(0, ManagementServerNode.nodeIdOfHostNameIn(hostNameFile(" \n")));
        Assert.assertEquals(0, ManagementServerNode.nodeIdOfHostNameIn(folder.getRoot().toPath().resolve("missing")));
    }

    @Test
    public void theSameHostNameGivesTheSameIdAndOthersDiffer() {
        long id = ManagementServerNode.nodeIdOf("cloudstack-management-0");
        Assert.assertEquals(id, ManagementServerNode.nodeIdOf("cloudstack-management-0"));
        Assert.assertNotEquals(id, ManagementServerNode.nodeIdOf("cloudstack-management-1"));
        for (String hostName : new String[] {"cloudstack-management-0", "cloudstack-management-1", "ms1.example.org", "localhost"}) {
            long nodeId = ManagementServerNode.nodeIdOf(hostName);
            Assert.assertTrue(hostName, nodeId > 0 && nodeId < (1L << 48));
        }
    }
}
