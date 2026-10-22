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

import java.util.Date;

import org.apache.cloudstack.oidc.vo.OidcLoginRequestVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class OidcLoginRequestDaoImpl extends GenericDaoBase<OidcLoginRequestVO, Long> implements OidcLoginRequestDao {

    private final SearchBuilder<OidcLoginRequestVO> stateSearch;
    private final SearchBuilder<OidcLoginRequestVO> createdBeforeSearch;
    private final SearchBuilder<OidcLoginRequestVO> idSearch;

    public OidcLoginRequestDaoImpl() {
        idSearch = createSearchBuilder();
        idSearch.and("id", idSearch.entity().getId(), SearchCriteria.Op.EQ);
        idSearch.done();
        stateSearch = createSearchBuilder();
        stateSearch.and("stateHash", stateSearch.entity().getStateHash(), SearchCriteria.Op.EQ);
        stateSearch.done();
        createdBeforeSearch = createSearchBuilder();
        createdBeforeSearch.and("created", createdBeforeSearch.entity().getCreated(), SearchCriteria.Op.LT);
        createdBeforeSearch.done();
    }

    @Override
    public OidcLoginRequestVO findByStateHash(String stateHash) {
        SearchCriteria<OidcLoginRequestVO> sc = stateSearch.create();
        sc.setParameters("stateHash", stateHash);
        return findOneBy(sc);
    }

    @Override
    public boolean consume(long id) {
        SearchCriteria<OidcLoginRequestVO> sc = idSearch.create();
        sc.setParameters("id", id);
        return expunge(sc) == 1;
    }

    @Override
    public int removeCreatedBefore(Date cutoff) {
        SearchCriteria<OidcLoginRequestVO> sc = createdBeforeSearch.create();
        sc.setParameters("created", cutoff);
        return expunge(sc);
    }
}
