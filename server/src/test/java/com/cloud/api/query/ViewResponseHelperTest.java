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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.ApiConstants.VMDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.user.Account;

public class ViewResponseHelperTest {

    private static UserVmJoinVO rowOf(long vmId) {
        UserVmJoinVO row = mock(UserVmJoinVO.class);
        when(row.getId()).thenReturn(vmId);
        return row;
    }

    @Test
    public void thePrefixesOfTheListedInstancesAreReadAtOnce() {
        UserVmJoinVO first = rowOf(1L);
        UserVmJoinVO firstAgain = rowOf(1L);
        UserVmJoinVO second = rowOf(2L);
        Account caller = mock(Account.class);
        CallContext context = mock(CallContext.class);
        when(context.getCallingAccount()).thenReturn(caller);
        UserVmResponse firstResponse = new UserVmResponse();
        UserVmResponse secondResponse = new UserVmResponse();
        try (MockedStatic<CallContext> current = mockStatic(CallContext.class); MockedStatic<ApiDBUtils> api = mockStatic(ApiDBUtils.class)) {
            current.when(CallContext::current).thenReturn(context);
            api.when(() -> ApiDBUtils.findSshNamePrefixes(Set.of(1L, 2L))).thenReturn(Map.of(1L, "admin"));
            api.when(() -> ApiDBUtils.newUserVmResponse(any(), any(), eq(first), any(), any(), any(), eq(caller), eq("admin"))).thenReturn(firstResponse);
            api.when(() -> ApiDBUtils.newUserVmResponse(any(), any(), eq(second), any(), any(), any(), eq(caller), isNull())).thenReturn(secondResponse);
            api.when(() -> ApiDBUtils.fillVmDetails(any(), eq(firstResponse), eq(firstAgain))).thenReturn(firstResponse);

            List<UserVmResponse> responses = ViewResponseHelper.createUserVmResponse(ResponseView.Full, "virtualmachine", EnumSet.of(VMDetails.all),
                    (Boolean) null, (Boolean) null, first, firstAgain, second);

            assertEquals(List.of(firstResponse, secondResponse), responses);
            api.verify(() -> ApiDBUtils.findSshNamePrefixes(any()), times(1));
            api.verify(() -> ApiDBUtils.newUserVmResponse(any(), any(), any(), any(), any(), any(), any(Account.class)), never());
        }
    }
}
