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
package org.apache.cloudstack.oidc.dao;

import java.util.List;

import org.apache.cloudstack.oidc.vo.OidcUserLinkVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class OidcUserLinkDaoImpl extends GenericDaoBase<OidcUserLinkVO, Long> implements OidcUserLinkDao {

    private final SearchBuilder<OidcUserLinkVO> subjectSearch;
    private final SearchBuilder<OidcUserLinkVO> userSearch;
    private final SearchBuilder<OidcUserLinkVO> providerSearch;

    public OidcUserLinkDaoImpl() {
        subjectSearch = createSearchBuilder();
        subjectSearch.and("providerId", subjectSearch.entity().getProviderId(), SearchCriteria.Op.EQ);
        subjectSearch.and("subject", subjectSearch.entity().getSubject(), SearchCriteria.Op.EQ);
        subjectSearch.done();
        userSearch = createSearchBuilder();
        userSearch.and("userId", userSearch.entity().getUserId(), SearchCriteria.Op.EQ);
        userSearch.done();
        providerSearch = createSearchBuilder();
        providerSearch.and("providerId", providerSearch.entity().getProviderId(), SearchCriteria.Op.EQ);
        providerSearch.done();
    }

    @Override
    public OidcUserLinkVO findByProviderAndSubject(long providerId, String subject) {
        SearchCriteria<OidcUserLinkVO> sc = subjectSearch.create();
        sc.setParameters("providerId", providerId);
        sc.setParameters("subject", subject);
        return findOneBy(sc);
    }

    @Override
    public OidcUserLinkVO findByUserId(long userId) {
        SearchCriteria<OidcUserLinkVO> sc = userSearch.create();
        sc.setParameters("userId", userId);
        return findOneBy(sc);
    }

    @Override
    public List<OidcUserLinkVO> listByProvider(long providerId) {
        SearchCriteria<OidcUserLinkVO> sc = providerSearch.create();
        sc.setParameters("providerId", providerId);
        return listBy(sc);
    }
}
