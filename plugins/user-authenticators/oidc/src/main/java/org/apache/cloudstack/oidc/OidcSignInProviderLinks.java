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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.cloudstack.auth.SignInProviderLinks;
import org.apache.cloudstack.oidc.dao.OidcProviderDao;
import org.apache.cloudstack.oidc.dao.OidcUserLinkDao;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;

import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.component.AdapterBase;

/**
 * The OpenID providers as sign-in providers: a user is linked to the provider it signs in through.
 */
public class OidcSignInProviderLinks extends AdapterBase implements SignInProviderLinks {

    @Inject
    protected OidcProviderDao providerDao;
    @Inject
    protected OidcUserLinkDao linkDao;
    @Inject
    protected UserDao userDao;

    /**
     * @return the link of the account's linked user with the lowest ID
     */
    @Override
    public Link findAccountLink(long accountId) {
        List<UserVO> users = new ArrayList<>(userDao.listByAccount(accountId));
        users.sort(Comparator.comparingLong(UserVO::getId));
        for (UserVO user : users) {
            OidcUserLinkVO link = linkDao.findByUserId(user.getId());
            OidcProviderVO provider = link == null ? null : providerDao.findById(link.getProviderId());
            if (provider != null) {
                return new Link(provider.getName(), user.getUsername());
            }
        }
        return null;
    }

    @Override
    public List<String> listProviderNames() {
        return providerDao.listAll().stream().map(OidcProviderVO::getName).collect(Collectors.toList());
    }
}
