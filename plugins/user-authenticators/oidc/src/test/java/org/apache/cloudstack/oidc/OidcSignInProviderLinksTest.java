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
package org.apache.cloudstack.oidc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.auth.SignInProviderLinks;
import org.apache.cloudstack.oidc.dao.OidcProviderDao;
import org.apache.cloudstack.oidc.dao.OidcUserLinkDao;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.vm.InstanceNames;

@RunWith(MockitoJUnitRunner.class)
public class OidcSignInProviderLinksTest {

    @Mock
    OidcProviderDao providerDao;
    @Mock
    OidcUserLinkDao linkDao;
    @Mock
    UserDao userDao;

    @InjectMocks
    OidcSignInProviderLinks links = new OidcSignInProviderLinks();

    private static OidcProviderVO provider(String name, boolean enabled) {
        OidcProviderVO provider = new OidcProviderVO();
        provider.setName(name);
        provider.setEnabled(enabled);
        return provider;
    }

    private static UserVO user(long id, String username) {
        UserVO user = new UserVO(id);
        user.setUsername(username);
        return user;
    }

    private String prefixOf(long accountId) {
        SignInProviderLinks.Link link = links.findAccountLink(accountId);
        return InstanceNames.providerPrefix(link.getProviderName(), link.getUsername());
    }

    @Test
    public void anAccountIsLinkedThroughTheProviderItsUserSignsInWith() {
        when(userDao.listByAccount(5L)).thenReturn(List.of(user(11L, "baneling100")));
        when(linkDao.findByUserId(11L)).thenReturn(new OidcUserLinkVO(1L, "subject-1", 11L));
        when(providerDao.findById(1L)).thenReturn(provider("snucse", true));

        SignInProviderLinks.Link link = links.findAccountLink(5L);

        assertEquals("snucse", link.getProviderName());
        assertEquals("baneling100", link.getUsername());
        assertEquals("snucse-baneling100", prefixOf(5L));
    }

    @Test
    public void theIdOfAnAddressUsernameIsItsLocalPart() {
        when(userDao.listByAccount(6L)).thenReturn(List.of(user(12L, "whnbaek@bacchus.snucse.org")));
        when(linkDao.findByUserId(12L)).thenReturn(new OidcUserLinkVO(2L, "subject-2", 12L));
        when(providerDao.findById(2L)).thenReturn(provider("bacchus", true));

        assertEquals("bacchus-whnbaek", prefixOf(6L));
    }

    @Test
    public void theLinkedUserWithTheLowestIdNamesAnAccountOfSeveralUsers() {
        when(userDao.listByAccount(7L)).thenReturn(List.of(user(30L, "later"), user(20L, "unlinked"), user(25L, "a.b@bacchus.snucse.org")));
        when(linkDao.findByUserId(20L)).thenReturn(null);
        when(linkDao.findByUserId(25L)).thenReturn(new OidcUserLinkVO(2L, "subject-25", 25L));
        when(providerDao.findById(2L)).thenReturn(provider("bacchus", true));

        assertEquals("bacchus-xaxdb", prefixOf(7L));
    }

    @Test
    public void anAccountWithoutALinkedUserHasNoLink() {
        when(userDao.listByAccount(8L)).thenReturn(List.of(user(40L, "admin")));
        when(linkDao.findByUserId(40L)).thenReturn(null);

        assertNull(links.findAccountLink(8L));
    }

    @Test
    public void theProviderNamesAreThoseOfEveryProviderEnabledOrNot() {
        when(providerDao.listAll()).thenReturn(List.of(provider("snucse", true), provider("bacchus", false)));

        assertEquals(List.of("snucse", "bacchus"), links.listProviderNames());
    }
}
