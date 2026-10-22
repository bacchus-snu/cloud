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
package org.apache.cloudstack.api.command.user.resource;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ResourceCountResponse;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.Resource;
import com.cloud.serializer.Param;

@RunWith(MockitoJUnitRunner.class)
public class UpdateResourceLimitCmdTest {

    @Test
    public void testGetTag() {
        UpdateResourceLimitCmd cmd = new UpdateResourceLimitCmd();
        ReflectionTestUtils.setField(cmd, "tag", null);
        Assert.assertNull(cmd.getTag());
        String tag = "ABC";
        ReflectionTestUtils.setField(cmd, "tag", tag);
        Assert.assertEquals(tag, cmd.getTag());
    }

    private static boolean lists(String description, Object value) {
        return Pattern.compile("(^|\\s)" + Pattern.quote(String.valueOf(value)) + " - ").matcher(description).find();
    }

    @Test
    public void resourceTypeDescriptionsListEveryResourceType() throws NoSuchFieldException {
        for (Class<?> cmd : List.of(UpdateResourceLimitCmd.class, UpdateResourceCountCmd.class, ListResourceLimitsCmd.class)) {
            String description = cmd.getDeclaredField("resourceType").getAnnotation(Parameter.class).description();
            for (Resource.ResourceType type : Resource.ResourceType.values()) {
                Assert.assertTrue(cmd.getSimpleName() + " misses " + type, lists(description, type.getOrdinal()));
            }
        }
        String names = ListResourceLimitsCmd.class.getDeclaredField("resourceTypeName").getAnnotation(Parameter.class).description();
        int highest = 0;
        for (Resource.ResourceType type : Resource.ResourceType.values()) {
            Assert.assertTrue("resourcetypename misses " + type, lists(names, type.getName()));
            highest = Math.max(highest, type.getOrdinal());
        }
        for (Class<?> response : List.of(ResourceLimitResponse.class, ResourceCountResponse.class)) {
            String description = response.getDeclaredField("resourceType").getAnnotation(Param.class).description();
            Assert.assertTrue(response.getSimpleName(), description.contains("0 to " + highest));
            String typeNames = response.getDeclaredField("resourceTypeName").getAnnotation(Param.class).description();
            for (Resource.ResourceType type : Resource.ResourceType.values()) {
                Assert.assertTrue(response.getSimpleName() + " misses " + type, Pattern.compile("[ ,]" + type.getName() + "[,.]").matcher(typeNames).find());
            }
        }
    }
}
