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

import com.cloud.capacity.HostPoolCoreVO;
import com.cloud.utils.db.GenericDao;

public interface HostPoolCoreDao extends GenericDao<HostPoolCoreVO, Long> {

    /**
     * @return the host's registered pool cores ordered by core
     */
    List<HostPoolCoreVO> listByHostId(long hostId);

    /**
     * Removes the host's whole registration; the host's assignments must be removed first.
     */
    int removeByHostId(long hostId);

    /**
     * Makes the given cores the host's whole registration, in one transaction: rows equal to a given core are kept,
     * others are removed, and missing ones are added. A held core cannot be removed (the assignment's foreign key
     * refuses it), so the caller keeps held cores unchanged.
     */
    void replace(long hostId, List<HostPoolCoreVO> cores);
}
