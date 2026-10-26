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

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.resourcedetail.ResourceDetailsDao;

import com.cloud.utils.db.GenericDao;
import com.cloud.vm.VMInstanceDetailVO;

public interface VMInstanceDetailsDao extends GenericDao<VMInstanceDetailVO, Long>, ResourceDetailsDao<VMInstanceDetailVO> {
    int removeDetailsWithPrefix(long vmId, String prefix);

    /**
     * @return the prefix of the Instance's names: its hidden detail named exactly {@link com.cloud.vm.VmDetailConstants#SSH_NAME_PREFIX},
     *         or null when it has none
     */
    String findSshNamePrefix(long vmId);

    /**
     * @return the {@link #findSshNamePrefix(long)} of those of the Instances that have one, by the Instances' IDs
     */
    Map<Long, String> findSshNamePrefixes(Collection<Long> vmIds);

    /**
     * @return the hidden details, named exactly {@link com.cloud.vm.VmDetailConstants#SSH_NAME_PREFIX}, of the Instances named under the
     *         prefix regardless of case
     */
    List<VMInstanceDetailVO> listBySshNamePrefix(String prefix);
}
