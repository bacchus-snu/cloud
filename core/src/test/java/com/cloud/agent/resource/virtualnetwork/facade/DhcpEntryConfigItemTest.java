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

package com.cloud.agent.resource.virtualnetwork.facade;

import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.cloud.agent.api.routing.DhcpEntryCommand;
import com.cloud.agent.resource.virtualnetwork.ConfigItem;
import com.cloud.agent.resource.virtualnetwork.FileConfigItem;
import com.cloud.agent.resource.virtualnetwork.VRScripts;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class DhcpEntryConfigItemTest {

    @Test
    public void theRoutersDhcpEntryNamesTheVmsAccount() {
        DhcpEntryCommand command = new DhcpEntryCommand("02:00:00:00:00:0a", "10.1.1.10", "alpha", null, true);
        command.setVmAccountId(7L);
        command.setVmSecondaryAddresses(Arrays.asList("10.1.1.12", "fd00:1::12"));

        List<ConfigItem> items = new DhcpEntryConfigItem().generateConfig(command);

        FileConfigItem file = (FileConfigItem) items.get(0);
        Assert.assertTrue(file.getFileName().startsWith(VRScripts.VM_DHCP_CONFIG));
        JsonObject entry = new Gson().fromJson(file.getFileContents(), JsonObject.class);
        Assert.assertEquals("alpha", entry.get("host_name").getAsString());
        Assert.assertTrue("the entry names no owner: " + entry, entry.has("account_id"));
        Assert.assertEquals(7L, entry.get("account_id").getAsLong());
        Assert.assertTrue("the entry lists no secondary address: " + entry, entry.has("secondary_addresses"));
        Assert.assertEquals("[\"10.1.1.12\",\"fd00:1::12\"]", entry.get("secondary_addresses").toString());
    }
}
