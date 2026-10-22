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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HostCoreAssignmentDaoImplTest {

    @Test
    public void releaseAndSweepDecideByTheSharedStatesInOneStatement() {
        assertEquals("DELETE a FROM `cloud`.`host_core_assignment` a JOIN `cloud`.`vm_instance` v ON v.id = a.vm_id "
                + "WHERE a.vm_id = ? AND (v.removed IS NOT NULL OR (v.state IN ('Destroyed', 'Error', 'Expunging', 'Stopped') "
                + "AND NOT EXISTS (SELECT 1 FROM `cloud`.`vm_instance_details` r WHERE r.vm_id = v.id AND r.name = 'restartinprogress')))",
                HostCoreAssignmentDaoImpl.RELEASE_SQL);
        assertEquals("DELETE a FROM `cloud`.`host_core_assignment` a JOIN `cloud`.`vm_instance` v ON v.id = a.vm_id "
                + "WHERE a.host_id = ? AND (v.removed IS NOT NULL OR ((v.host_id IS NULL OR v.host_id <> a.host_id "
                + "OR v.state IN ('Destroyed', 'Error', 'Expunging', 'Stopped')) "
                + "AND NOT EXISTS (SELECT 1 FROM `cloud`.`vm_instance_details` r WHERE r.vm_id = v.id AND r.name = 'restartinprogress')))",
                HostCoreAssignmentDaoImpl.REMOVE_STALE_SQL);
    }
}
