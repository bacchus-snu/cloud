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
package org.apache.cloudstack.api.command.user.account;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.AccountResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.Resource;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.serializer.Param;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.google.gson.annotations.SerializedName;

@RunWith(MockitoJUnitRunner.class)
public class ListAccountsCmdTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Mock
    ResourceLimitService resourceLimitService;

    @Mock
    AccountService accountService;


    @Test
    public void testGetShowIcon() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        ReflectionTestUtils.setField(cmd, "showIcon", null);
        Assert.assertFalse(cmd.getShowIcon());
        ReflectionTestUtils.setField(cmd, "showIcon", false);
        Assert.assertFalse(cmd.getShowIcon());
        ReflectionTestUtils.setField(cmd, "showIcon", true);
        Assert.assertTrue(cmd.getShowIcon());
    }

    @Test
    public void testGetTag() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        ReflectionTestUtils.setField(cmd, "tag", null);
        Assert.assertNull(cmd.getTag());
        String tag = "ABC";
        ReflectionTestUtils.setField(cmd, "tag", tag);
        Assert.assertEquals(tag, cmd.getTag());
    }

    @Test
    public void testUpdateAccountResponseNoAccounts() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        cmd._resourceLimitService = resourceLimitService;
        cmd.updateAccountResponse(null);
        Mockito.verify(resourceLimitService, Mockito.never()).updateTaggedResourceLimitsAndCountsForAccounts(Mockito.anyList(), Mockito.anyString());
    }

    @Test
    public void testUpdateDomainResponseWithAccounts() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        cmd._resourceLimitService = resourceLimitService;
        ReflectionTestUtils.setField(cmd, "tag", "abc");
        cmd.updateAccountResponse(List.of(Mockito.mock(AccountResponse.class)));
        Mockito.verify(resourceLimitService, Mockito.times(1)).updateTaggedResourceLimitsAndCountsForAccounts(Mockito.any(), Mockito.any());
    }

    @Test
    public void testUpdateDomainResponseWithAccountsMinDetails() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        ReflectionTestUtils.setField(cmd, "viewDetails", List.of(ApiConstants.DomainDetails.min.toString()));
        cmd._resourceLimitService = resourceLimitService;
        ReflectionTestUtils.setField(cmd, "tag", "abc");
        cmd.updateAccountResponse(List.of(Mockito.mock(AccountResponse.class)));
        Mockito.verify(resourceLimitService, Mockito.never()).updateTaggedResourceLimitsAndCountsForAccounts(Mockito.any(), Mockito.any());
    }

    private AccountResponse accountResponse(String uuid) {
        AccountResponse response = new AccountResponse();
        response.setId(uuid);
        return response;
    }

    @Test
    public void remainingStorageQuotaOnlyWhenRequested() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        cmd._resourceLimitService = resourceLimitService;
        cmd._accountService = accountService;
        AccountResponse plain = accountResponse("a-1");

        // the default and every stock details value leave it out, so listing many accounts costs no quota computation
        cmd.updateAccountResponse(List.of(plain));
        ReflectionTestUtils.setField(cmd, "viewDetails", List.of("all", "resource", "min"));
        cmd.updateAccountResponse(List.of(plain));
        Assert.assertNull(plain.getRemainingStorageQuota());
        Mockito.verify(resourceLimitService, Mockito.never()).getRemainingStorageQuota(Mockito.any());
        Assert.assertFalse(cmd.isRemainingStorageQuotaRequested());
    }

    @Test
    public void remainingStorageQuotaExposedOnListAccounts() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        cmd._resourceLimitService = resourceLimitService;
        cmd._accountService = accountService;
        ReflectionTestUtils.setField(cmd, "viewDetails", List.of("all", "storagequota"));
        Account account = Mockito.mock(Account.class);
        Mockito.when(accountService.getAccountByUuid("a-1")).thenReturn(account);
        AccountResponse response = accountResponse("a-1");

        Mockito.when(resourceLimitService.getRemainingStorageQuota(account)).thenReturn(85 * GIB + GIB / 2);
        cmd.updateAccountResponse(List.of(response));
        Assert.assertEquals("whole GiB, rounded down", Long.valueOf(85), response.getRemainingStorageQuota());

        Mockito.when(resourceLimitService.getRemainingStorageQuota(account)).thenReturn(GIB - 1);
        cmd.updateAccountResponse(List.of(response));
        Assert.assertEquals(Long.valueOf(0), response.getRemainingStorageQuota());

        Mockito.when(resourceLimitService.getRemainingStorageQuota(account)).thenReturn((long) Resource.RESOURCE_UNLIMITED);
        cmd.updateAccountResponse(List.of(response));
        Assert.assertEquals(Long.valueOf(Resource.RESOURCE_UNLIMITED), response.getRemainingStorageQuota());

        // the extra value does not change the stock details it comes with
        Assert.assertEquals(EnumSet.of(ApiConstants.DomainDetails.all), cmd.getDetails());
        ReflectionTestUtils.setField(cmd, "viewDetails", List.of("storagequota"));
        Assert.assertEquals(EnumSet.of(ApiConstants.DomainDetails.min), cmd.getDetails());
        Assert.assertTrue(cmd.isRemainingStorageQuotaRequested());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void unknownDetailsStillRefused() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        ReflectionTestUtils.setField(cmd, "viewDetails", List.of("all", "quota"));
        cmd.getDetails();
    }

    @Test
    public void remainingStorageQuotaIsAFieldOfTheAccountResponse() throws NoSuchFieldException {
        Field field = AccountResponse.class.getDeclaredField("remainingStorageQuota");
        Assert.assertEquals(ApiConstants.REMAINING_STORAGE_QUOTA, field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals("remainingstoragequota", ApiConstants.REMAINING_STORAGE_QUOTA);
        Assert.assertEquals("4.23.0.100", field.getAnnotation(Param.class).since());
    }

    @Test
    public void storageQuotaCombinedComesWithTheRemainingStorageQuota() {
        ListAccountsCmd cmd = new ListAccountsCmd();
        cmd._resourceLimitService = resourceLimitService;
        cmd._accountService = accountService;
        Account combined = Mockito.mock(Account.class);
        Account separate = Mockito.mock(Account.class);
        Mockito.when(accountService.getAccountByUuid("a-1")).thenReturn(combined);
        Mockito.when(accountService.getAccountByUuid("a-2")).thenReturn(separate);
        Mockito.when(resourceLimitService.isStorageQuotaCombined(combined)).thenReturn(true);
        Mockito.when(resourceLimitService.isStorageQuotaCombined(separate)).thenReturn(false);
        AccountResponse first = accountResponse("a-1");
        AccountResponse second = accountResponse("a-2");

        cmd.updateAccountResponse(List.of(first, second));
        Assert.assertNull("not asked for", first.getStorageQuotaCombined());
        Mockito.verify(resourceLimitService, Mockito.never()).isStorageQuotaCombined(Mockito.any());

        ReflectionTestUtils.setField(cmd, "viewDetails", List.of("storagequota"));
        cmd.updateAccountResponse(List.of(first, second));
        Assert.assertEquals(Boolean.TRUE, first.getStorageQuotaCombined());
        Assert.assertEquals(Boolean.FALSE, second.getStorageQuotaCombined());
    }

    @Test
    public void storageQuotaCombinedIsAFieldOfTheAccountResponse() throws NoSuchFieldException {
        Field field = AccountResponse.class.getDeclaredField("storageQuotaCombined");
        Assert.assertEquals("storagequotacombined", field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals(ApiConstants.STORAGE_QUOTA_COMBINED, field.getAnnotation(SerializedName.class).value());
        Assert.assertEquals("4.23.0.100", field.getAnnotation(Param.class).since());
    }
}
