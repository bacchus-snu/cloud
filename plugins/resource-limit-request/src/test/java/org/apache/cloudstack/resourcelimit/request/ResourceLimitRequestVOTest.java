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

package org.apache.cloudstack.resourcelimit.request;

import static org.junit.Assert.assertEquals;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.cloudstack.resourcelimit.request.dao.ResourceLimitRequestDaoImpl;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;
import org.junit.Test;

import com.cloud.utils.Ternary;
import com.cloud.utils.db.Attribute;
import com.cloud.utils.db.GenericDaoBase;

/**
 * A decision on a request reaches the database only as changes the DAO recorded on an entity it produced, and the
 * DAO records only setter calls.
 */
public class ResourceLimitRequestVOTest {

    /** @return the columns an update of the entity writes, with their values */
    private static Map<String, Object> changes(ResourceLimitRequestVO entity) {
        Map<String, Object> changed = new TreeMap<>();
        for (Ternary<Attribute, Boolean, Object> change : GenericDaoBase.getUpdateBuilder(entity).getChanges()) {
            changed.put(change.first().getColumnName(), change.third());
        }
        return changed;
    }

    @Test
    public void anApprovalWritesTheGrantAndTheDecision() {
        ResourceLimitRequestVO request = new ResourceLimitRequestDaoImpl().createForUpdate(7L);
        Date expiry = new Date(1_800_000_000_000L);
        Date decided = new Date(1_790_000_000_000L);
        request.approve(4, 8192, 50, Map.of("a6000", 1L), expiry);
        request.decide(2L, decided, "ok");

        Map<String, Object> changed = changes(request);
        assertEquals(Set.of("state", "approved_cpu", "approved_memory_mib", "approved_storage_gib", "approved_gpus", "approved_expiry",
                "approver_id", "decided_at", "comment"), changed.keySet());
        assertEquals(ResourceLimitRequestVO.State.Approved, changed.get("state"));
        assertEquals(4L, changed.get("approved_cpu"));
        assertEquals("{\"a6000\":1}", changed.get("approved_gpus"));
        assertEquals(expiry, changed.get("approved_expiry"));
        assertEquals(2L, changed.get("approver_id"));
        assertEquals(decided, changed.get("decided_at"));
    }

    @Test
    public void aRejectionWritesItsDecider() {
        ResourceLimitRequestVO request = new ResourceLimitRequestDaoImpl().createForUpdate(8L);
        request.setState(ResourceLimitRequestVO.State.Rejected);
        request.decide(2L, new Date(), null);

        Map<String, Object> expected = new HashMap<>();
        expected.put("state", ResourceLimitRequestVO.State.Rejected);
        expected.put("approver_id", 2L);
        expected.put("comment", null);
        Map<String, Object> changed = changes(request);
        assertEquals(Set.of("state", "approver_id", "decided_at", "comment"), changed.keySet());
        changed.remove("decided_at");
        assertEquals(expected, changed);
    }
}
