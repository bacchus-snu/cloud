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

package org.apache.cloudstack.resourcelimit.request.dao;

import java.util.List;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class ResourceLimitRequestDaoImpl extends GenericDaoBase<ResourceLimitRequestVO, Long> implements ResourceLimitRequestDao {

    @Inject
    DomainDao domainDao;

    private SearchBuilder<ResourceLimitRequestVO> accountStatesSearch;
    private SearchBuilder<ResourceLimitRequestVO> stateSearch;
    private SearchBuilder<ResourceLimitRequestVO> approvedWithExpirySearch;
    private SearchBuilder<ResourceLimitRequestVO> listSearch;
    private SearchBuilder<ResourceLimitRequestVO> domainTreeListSearch;

    @PostConstruct
    public void init() {
        accountStatesSearch = createSearchBuilder();
        accountStatesSearch.and("accountId", accountStatesSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        accountStatesSearch.and("states", accountStatesSearch.entity().getState(), SearchCriteria.Op.IN);
        accountStatesSearch.done();

        stateSearch = createSearchBuilder();
        stateSearch.and("state", stateSearch.entity().getState(), SearchCriteria.Op.EQ);
        stateSearch.done();

        approvedWithExpirySearch = createSearchBuilder();
        approvedWithExpirySearch.and("state", approvedWithExpirySearch.entity().getState(), SearchCriteria.Op.EQ);
        approvedWithExpirySearch.and("approvedExpiry", approvedWithExpirySearch.entity().getApprovedExpiry(), SearchCriteria.Op.NNULL);
        approvedWithExpirySearch.done();

        listSearch = listSearchBuilder(false);
        domainTreeListSearch = listSearchBuilder(true);
    }

    private SearchBuilder<ResourceLimitRequestVO> listSearchBuilder(boolean domainTree) {
        SearchBuilder<ResourceLimitRequestVO> sb = createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("accountIds", sb.entity().getAccountId(), SearchCriteria.Op.IN);
        sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
        if (domainTree) {
            SearchBuilder<DomainVO> domainSearch = domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }
        sb.done();
        return sb;
    }

    @Override
    public List<ResourceLimitRequestVO> listByAccountAndStates(long accountId, ResourceLimitRequestVO.State... states) {
        SearchCriteria<ResourceLimitRequestVO> sc = accountStatesSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("states", (Object[])states);
        return listBy(sc);
    }

    @Override
    public List<ResourceLimitRequestVO> listByState(ResourceLimitRequestVO.State state) {
        SearchCriteria<ResourceLimitRequestVO> sc = stateSearch.create();
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public List<ResourceLimitRequestVO> listApprovedWithExpiry() {
        SearchCriteria<ResourceLimitRequestVO> sc = approvedWithExpirySearch.create();
        sc.setParameters("state", ResourceLimitRequestVO.State.Approved);
        return listBy(sc);
    }

    @Override
    public Pair<List<ResourceLimitRequestVO>, Integer> search(Long id, ResourceLimitRequestVO.State state, List<Long> permittedAccounts, Long domainId,
            boolean recursive, Filter filter) {
        boolean byAccount = !permittedAccounts.isEmpty();
        boolean byDomainTree = !byAccount && domainId != null && recursive;
        SearchCriteria<ResourceLimitRequestVO> sc = (byDomainTree ? domainTreeListSearch : listSearch).create();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (state != null) {
            sc.setParameters("state", state);
        }
        if (byAccount) {
            sc.setParameters("accountIds", permittedAccounts.toArray());
        } else if (byDomainTree) {
            DomainVO domain = domainDao.findById(domainId);
            sc.setJoinParameters("domainSearch", "path", domain.getPath() + "%");
        } else if (domainId != null) {
            sc.setParameters("domainId", domainId);
        }
        return searchAndCount(sc, filter);
    }
}
