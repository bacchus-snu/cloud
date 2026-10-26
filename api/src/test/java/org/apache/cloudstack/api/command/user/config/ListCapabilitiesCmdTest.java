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
package org.apache.cloudstack.api.command.user.config;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.CapabilitiesResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.serializer.Param;
import com.cloud.server.ManagementService;
import com.google.gson.annotations.SerializedName;

@RunWith(MockitoJUnitRunner.class)
public class ListCapabilitiesCmdTest {

    private Object responseField(String name, String key, Object value) throws Exception {
        ListCapabilitiesCmd cmd = new ListCapabilitiesCmd();
        cmd._mgr = Mockito.mock(ManagementService.class);
        cmd.roleService = Mockito.mock(RoleService.class);
        Map<String, Object> capabilities = new HashMap<>();
        for (String flag : new String[] {"securityGroupsEnabled", "userPublicTemplateEnabled", "regionSecondaryEnabled", "KVMSnapshotEnabled",
                "SnapshotShowChainSize", "allowUserViewDestroyedVM", "allowUserExpungeRecoverVM", "allowUserExpungeRecoverVolume",
                "allowUserViewAllDomainAccounts", ApiConstants.ALLOW_USER_FORCE_STOP_VM, "kubernetesServiceEnabled",
                "kubernetesClusterExperimentalFeaturesEnabled"}) {
            capabilities.put(flag, false);
        }
        capabilities.put(key, value);
        Mockito.when(cmd._mgr.listCapabilities(cmd)).thenReturn(capabilities);

        cmd.execute();

        Field field = CapabilitiesResponse.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(cmd.getResponseObject());
    }

    @Test
    public void quotaGpuTypesAreReturned() throws Exception {
        Assert.assertEquals(List.of("a100-40g", "h100"), responseField("quotaGpuTypes", ApiConstants.QUOTA_GPU_TYPES, List.of("a100-40g", "h100")));
    }

    @Test
    public void whetherADestroyExpungesIsReturned() throws Exception {
        Assert.assertEquals(Boolean.TRUE, responseField("expungeOnDestroy", ApiConstants.EXPUNGE_ON_DESTROY, true));
        Assert.assertEquals(Boolean.FALSE, responseField("expungeOnDestroy", ApiConstants.EXPUNGE_ON_DESTROY, false));
        Field field = CapabilitiesResponse.class.getDeclaredField("expungeOnDestroy");
        Assert.assertEquals("expungeondestroy", field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals("4.23.0.100", field.getAnnotation(Param.class).since());
    }

    @Test
    public void quotaGpuTypesAreAFieldOfTheCapabilitiesResponse() throws NoSuchFieldException {
        Field field = CapabilitiesResponse.class.getDeclaredField("quotaGpuTypes");
        Assert.assertEquals("quotagputypes", field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals("4.23.0.100", field.getAnnotation(Param.class).since());
    }

    private Object automigrateAllowedOf(Boolean allowed) throws Exception {
        ListCapabilitiesCmd cmd = new ListCapabilitiesCmd();
        cmd._mgr = Mockito.mock(ManagementService.class);
        cmd.roleService = Mockito.mock(RoleService.class);
        Map<String, Object> capabilities = new HashMap<>();
        for (String flag : new String[] {"securityGroupsEnabled", "userPublicTemplateEnabled", "regionSecondaryEnabled", "KVMSnapshotEnabled",
                "SnapshotShowChainSize", "allowUserViewDestroyedVM", "allowUserExpungeRecoverVM", "allowUserExpungeRecoverVolume",
                "allowUserViewAllDomainAccounts", ApiConstants.ALLOW_USER_FORCE_STOP_VM, "kubernetesServiceEnabled",
                "kubernetesClusterExperimentalFeaturesEnabled"}) {
            capabilities.put(flag, false);
        }
        capabilities.put(ApiConstants.AUTOMIGRATE_ALLOWED, allowed);
        Mockito.when(cmd._mgr.listCapabilities(cmd)).thenReturn(capabilities);

        cmd.execute();

        Field field = CapabilitiesResponse.class.getDeclaredField("automigrateAllowed");
        field.setAccessible(true);
        return field.get(cmd.getResponseObject());
    }

    @Test
    public void automigrateAllowedIsReturned() throws Exception {
        Assert.assertEquals(Boolean.TRUE, automigrateAllowedOf(true));
        Assert.assertEquals(Boolean.FALSE, automigrateAllowedOf(false));
    }

    @Test
    public void automigrateAllowedIsAFieldOfTheCapabilitiesResponse() throws NoSuchFieldException {
        Field field = CapabilitiesResponse.class.getDeclaredField("automigrateAllowed");
        Assert.assertEquals("automigrateallowed", field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals(ApiConstants.AUTOMIGRATE_ALLOWED, field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals("4.23.0.100", field.getAnnotation(Param.class).since());
    }
}
