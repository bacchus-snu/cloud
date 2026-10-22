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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cloud.capacity.HostCoreAssignmentVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;

@Component
public class HostCoreAssignmentDaoImpl extends GenericDaoBase<HostCoreAssignmentVO, Long> implements HostCoreAssignmentDao {

    private static final String STATES_WITHOUT_CORES_SQL = STATES_WITHOUT_CORES.stream().map(state -> "'" + state.name() + "'")
            .sorted().collect(Collectors.joining(", "));

    /**
     * A VM between the stop and the start of a restart within its running session keeps its cores.
     */
    private static final String NO_RESTART_IN_PROGRESS_SQL = "NOT EXISTS (SELECT 1 FROM `cloud`.`vm_instance_details` r WHERE r.vm_id = v.id AND r.name = '"
            + VmDetailConstants.RESTART_IN_PROGRESS + "')";

    static final String RELEASE_SQL = "DELETE a FROM `cloud`.`host_core_assignment` a JOIN `cloud`.`vm_instance` v ON v.id = a.vm_id "
            + "WHERE a.vm_id = ? AND (v.removed IS NOT NULL OR (v.state IN (" + STATES_WITHOUT_CORES_SQL + ") AND " + NO_RESTART_IN_PROGRESS_SQL + "))";

    static final String REMOVE_STALE_SQL = "DELETE a FROM `cloud`.`host_core_assignment` a JOIN `cloud`.`vm_instance` v ON v.id = a.vm_id "
            + "WHERE a.host_id = ? AND (v.removed IS NOT NULL OR ((v.host_id IS NULL OR v.host_id <> a.host_id OR v.state IN ("
            + STATES_WITHOUT_CORES_SQL + ")) AND " + NO_RESTART_IN_PROGRESS_SQL + "))";

    private final SearchBuilder<HostCoreAssignmentVO> hostSearch;
    private final SearchBuilder<HostCoreAssignmentVO> vmSearch;

    public HostCoreAssignmentDaoImpl() {
        hostSearch = createSearchBuilder();
        hostSearch.and("hostId", hostSearch.entity().getHostId(), SearchCriteria.Op.EQ);
        hostSearch.done();

        vmSearch = createSearchBuilder();
        vmSearch.and("vmId", vmSearch.entity().getVmId(), SearchCriteria.Op.EQ);
        vmSearch.done();
    }

    @Override
    public List<HostCoreAssignmentVO> listByHostId(long hostId) {
        SearchCriteria<HostCoreAssignmentVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return listBy(sc);
    }

    @Override
    public int removeByHostId(long hostId) {
        SearchCriteria<HostCoreAssignmentVO> sc = hostSearch.create();
        sc.setParameters("hostId", hostId);
        return expunge(sc);
    }

    @Override
    public void assign(long hostId, long vmId, List<Integer> cores) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                SearchCriteria<HostCoreAssignmentVO> sc = vmSearch.create();
                sc.setParameters("vmId", vmId);
                expunge(sc);
                for (int core : cores) {
                    persist(new HostCoreAssignmentVO(hostId, core, vmId));
                }
            }
        });
    }

    @Override
    public int releaseIfNotRunning(long vmId) {
        return executeDelete(RELEASE_SQL, vmId);
    }

    @Override
    public int removeStale(long hostId) {
        return executeDelete(REMOVE_STALE_SQL, hostId);
    }

    private int executeDelete(String sql, long id) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, id);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException(String.format("Unable to remove pool core assignments for %d", id), e);
        }
    }
}
