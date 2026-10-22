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
package com.cloud.vm.dao;


import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.resourcedetail.ResourceDetailsDaoBase;

import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VmDetailConstants;

/**
 * The saves and removals of an Instance's whole detail set leave its lifecycle state
 * ({@link VmDetailConstants#LIFECYCLE_STATE_DETAILS}) as it is: they neither remove it nor write it back.
 */
@Component
public class VMInstanceDetailsDaoImpl extends ResourceDetailsDaoBase<VMInstanceDetailVO> implements VMInstanceDetailsDao {
    private static final String LIFECYCLE_STATE = "lifecycleState";

    private final SearchBuilder<VMInstanceDetailVO> detailsOutsideLifecycleStateSearch;

    public VMInstanceDetailsDaoImpl() {
        detailsOutsideLifecycleStateSearch = createSearchBuilder();
        detailsOutsideLifecycleStateSearch.and("vmId", detailsOutsideLifecycleStateSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        detailsOutsideLifecycleStateSearch.and(LIFECYCLE_STATE, detailsOutsideLifecycleStateSearch.entity().getName(), SearchCriteria.Op.NIN);
        detailsOutsideLifecycleStateSearch.done();
    }

    @Override
    public void addDetail(long resourceId, String key, String value, boolean display) {
        super.addDetail(new VMInstanceDetailVO(resourceId, key, value, display));
    }

    @Override
    public void saveDetails(List<VMInstanceDetailVO> details) {
        if (details.isEmpty()) {
            return;
        }
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        expunge(detailsOutsideLifecycleState(details.get(0).getResourceId()));
        for (VMInstanceDetailVO detail : details) {
            if (!VmDetailConstants.LIFECYCLE_STATE_DETAILS.contains(detail.getName())) {
                persist(detail);
            }
        }
        txn.commit();
    }

    @Override
    public void removeDetails(long vmId) {
        remove(detailsOutsideLifecycleState(vmId));
    }

    @Override
    public int removeDetailsWithPrefix(long vmId, String prefix) {
        if (StringUtils.isBlank(prefix)) {
            return 0;
        }
        SearchBuilder<VMInstanceDetailVO> sb = createSearchBuilder();
        sb.and("vmId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
        sb.and("prefix", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and(LIFECYCLE_STATE, sb.entity().getName(), SearchCriteria.Op.NIN);
        sb.done();
        SearchCriteria<VMInstanceDetailVO> sc = sb.create();
        sc.setParameters("vmId", vmId);
        sc.setParameters("prefix", prefix + "%");
        sc.setParameters(LIFECYCLE_STATE, VmDetailConstants.LIFECYCLE_STATE_DETAILS.toArray());
        return super.remove(sc);
    }

    private SearchCriteria<VMInstanceDetailVO> detailsOutsideLifecycleState(long vmId) {
        SearchCriteria<VMInstanceDetailVO> sc = detailsOutsideLifecycleStateSearch.create();
        sc.setParameters("vmId", vmId);
        sc.setParameters(LIFECYCLE_STATE, VmDetailConstants.LIFECYCLE_STATE_DETAILS.toArray());
        return sc;
    }
}
