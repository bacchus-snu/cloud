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
package org.apache.cloudstack.oidc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.cloudstack.oidc.api.command.UpdateOidcProviderCmd;
import org.apache.cloudstack.oidc.dao.OidcLoginRequestDaoImpl;
import org.apache.cloudstack.oidc.dao.OidcProviderDaoImpl;
import org.apache.cloudstack.oidc.vo.OidcProviderVO;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;

import com.cloud.utils.db.TransactionLegacy;

/**
 * Persistence through the real DAO layer, whose update writes only what was set on an entity it produced and whose
 * delete reports the rows it removed; the database below it is a recording driver.
 */
public class OidcDaoPathTest extends OidcTestBase {

    /** @return the columns an UPDATE sets, with the values bound to them */
    private static Map<String, Object> assignments(FakeJdbc.Statement statement) {
        String sql = statement.sql;
        String set = sql.substring(sql.indexOf(" SET ") + 5, sql.indexOf(" WHERE "));
        Map<String, Object> assigned = new LinkedHashMap<>();
        int index = 1;
        for (String assignment : set.split(", ")) {
            String column = assignment.substring(assignment.indexOf('.') + 1, assignment.indexOf('='));
            assigned.put(column, statement.parameters.get(index++));
        }
        return assigned;
    }

    @Test
    public void providerUpdateWritesTheChangedColumnsOfTheDaoEntity() throws Exception {
        OidcProviderVO row = students();
        OidcProviderDaoImpl dao = spy(new OidcProviderDaoImpl());
        OidcProviderVO stored = dao.createForUpdate(row.getId());
        for (Field field : FieldUtils.getAllFieldsList(OidcProviderVO.class)) {
            if (!Modifier.isStatic(field.getModifiers())) {
                FieldUtils.writeField(field, stored, FieldUtils.readField(field, row, true), true);
            }
        }
        doReturn(stored).when(dao).findById(row.getId());
        manager.providerDao = dao;
        UpdateOidcProviderCmd disable = parameters(UpdateOidcProviderCmd.class);
        when(disable.getId()).thenReturn(row.getId());
        when(disable.getEnabled()).thenReturn(false);
        when(disable.getEndSessionUrl()).thenReturn("");
        when(disable.getEndSessionLabel()).thenReturn("");

        try (FakeJdbc db = new FakeJdbc(statement -> 1); TransactionLegacy txn = TransactionLegacy.open("providerUpdate")) {
            manager.updateProvider(disable);
            List<FakeJdbc.Statement> updates = db.executed.stream().filter(s -> s.sql.startsWith("UPDATE")).collect(Collectors.toList());
            assertEquals(1, updates.size());
            Map<String, Object> written = assignments(updates.get(0));
            assertEquals("only what the command set is written", List.of("end_session_url", "end_session_label", "enabled"),
                    written.keySet().stream().sorted((a, b) -> order(a) - order(b)).collect(Collectors.toList()));
            assertEquals(false, written.get("enabled"));
            assertTrue("a cleared field is written as NULL", written.containsKey("end_session_url") && written.get("end_session_url") == null);
            assertTrue(written.containsKey("end_session_label") && written.get("end_session_label") == null);
            assertFalse("the secrets are not rewritten", written.containsKey("client_secret") || written.containsKey("membership_secret"));
        }
    }

    private static int order(String column) {
        return List.of("end_session_url", "end_session_label", "enabled").indexOf(column);
    }

    @Test
    public void loginRequestIsConsumedByOneOfConcurrentCallers() throws Exception {
        OidcLoginRequestDaoImpl dao = new OidcLoginRequestDaoImpl();
        AtomicBoolean present = new AtomicBoolean(true);
        int callers = 2;
        try (FakeJdbc db = new FakeJdbc(statement -> statement.sql.startsWith("DELETE") && present.getAndSet(false) ? 1 : 0)) {
            CyclicBarrier together = new CyclicBarrier(callers);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    results.add(pool.submit(() -> {
                        together.await(10, TimeUnit.SECONDS);
                        try (TransactionLegacy txn = TransactionLegacy.open("consume")) {
                            return dao.consume(7L);
                        }
                    }));
                }
                int consumed = 0;
                for (Future<Boolean> result : results) {
                    consumed += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertEquals("the database removes the row once, and only that caller consumes it", 1, consumed);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(callers, db.executed.size());
            for (FakeJdbc.Statement statement : db.executed) {
                assertTrue(statement.sql, statement.sql.startsWith("DELETE FROM oidc_login_request WHERE"));
                assertEquals(List.of(7L), statement.values());
            }
        }
    }
}
