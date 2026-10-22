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
package com.cloud.gpu.dao;

import org.springframework.stereotype.Component;

import com.cloud.gpu.HostGpuGroupRuleVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class HostGpuGroupRuleDaoImpl extends GenericDaoBase<HostGpuGroupRuleVO, Long> implements HostGpuGroupRuleDao {

    private final SearchBuilder<HostGpuGroupRuleVO> hostSearch;

    public HostGpuGroupRuleDaoImpl() {
        hostSearch = createSearchBuilder();
        hostSearch.and("hostId", hostSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostSearch.done();
    }

    @Override
    public HostGpuGroupRuleVO findByHostId(long hostId) {
        SearchCriteria<HostGpuGroupRuleVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return findOneBy(sc);
    }

    @Override
    public int removeByHostId(long hostId) {
        SearchCriteria<HostGpuGroupRuleVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return expunge(sc);
    }
}
