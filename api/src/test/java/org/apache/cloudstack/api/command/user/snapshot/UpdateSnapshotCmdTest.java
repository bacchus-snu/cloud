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
package org.apache.cloudstack.api.command.user.snapshot;

import java.util.Arrays;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.Snapshot;
import com.cloud.storage.snapshot.SnapshotApiService;

public class UpdateSnapshotCmdTest {

    @Test
    public void updateSnapshotIsAUserApiThatAnswersWithTheSnapshot() throws NoSuchFieldException {
        APICommand api = UpdateSnapshotCmd.class.getAnnotation(APICommand.class);
        Assert.assertEquals("updateSnapshot", api.name());
        Assert.assertEquals(SnapshotResponse.class, api.responseObject());
        Assert.assertTrue(Arrays.asList(api.authorized()).contains(RoleType.User));
        Assert.assertTrue(UpdateSnapshotCmd.class.getDeclaredField("id").getAnnotation(Parameter.class).required());
        Parameter name = UpdateSnapshotCmd.class.getDeclaredField("name").getAnnotation(Parameter.class);
        Assert.assertEquals("name", name.name());
        Assert.assertTrue(name.required());
    }

    @Test
    public void theRenamedSnapshotIsTheResponse() {
        UpdateSnapshotCmd cmd = new UpdateSnapshotCmd();
        ReflectionTestUtils.setField(cmd, "id", 9L);
        ReflectionTestUtils.setField(cmd, "name", "before-upgrade");
        cmd._snapshotService = Mockito.mock(SnapshotApiService.class);
        cmd._responseGenerator = Mockito.mock(ResponseGenerator.class);
        Snapshot snapshot = Mockito.mock(Snapshot.class);
        SnapshotResponse response = new SnapshotResponse();
        Mockito.when(cmd._snapshotService.updateSnapshot(cmd)).thenReturn(snapshot);
        Mockito.when(cmd._responseGenerator.createSnapshotResponse(snapshot)).thenReturn(response);

        cmd.execute();

        Assert.assertSame(response, cmd.getResponseObject());
        Assert.assertEquals("updatesnapshotresponse", response.getResponseName());
    }
}
