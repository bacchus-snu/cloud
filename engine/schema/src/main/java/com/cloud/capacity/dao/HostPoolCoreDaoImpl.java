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
package com.cloud.capacity.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.capacity.HostPoolCoreVO;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

@Component
public class HostPoolCoreDaoImpl extends GenericDaoBase<HostPoolCoreVO, Long> implements HostPoolCoreDao {

    private final SearchBuilder<HostPoolCoreVO> hostSearch;

    public HostPoolCoreDaoImpl() {
        hostSearch = createSearchBuilder();
        hostSearch.and("hostId", hostSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostSearch.done();
    }

    @Override
    public List<HostPoolCoreVO> listByHostId(long hostId) {
        SearchCriteria<HostPoolCoreVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return listBy(sc, new Filter(HostPoolCoreVO.class, "core", true, null, null));
    }

    @Override
    public int removeByHostId(long hostId) {
        SearchCriteria<HostPoolCoreVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return expunge(sc);
    }

    @Override
    public void replace(long hostId, List<HostPoolCoreVO> cores) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                List<HostPoolCoreVO> registered = listByHostId(hostId);
                for (HostPoolCoreVO row : registered) {
                    if (cores.stream().noneMatch(row::sameCore)) {
                        expunge(row.getId());
                    }
                }
                for (HostPoolCoreVO core : cores) {
                    if (registered.stream().noneMatch(core::sameCore)) {
                        persist(new HostPoolCoreVO(hostId, core.getCore(), core.getSibling(), core.getNode()));
                    }
                }
            }
        });
    }
}
