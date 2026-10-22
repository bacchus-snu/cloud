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

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import javax.sql.DataSource;

import org.apache.commons.lang3.reflect.FieldUtils;

import com.cloud.utils.db.TransactionLegacy;

/**
 * A database driver for the real DAO layer: the statements the DAOs build are recorded with their parameters, and
 * the row count of each update or delete comes from a function standing in for the database. Installed as the data
 * source of every transaction, on every thread.
 */
final class FakeJdbc implements AutoCloseable {

    /** One executed statement: its SQL and its parameters by index. */
    static final class Statement {
        final String sql;
        final Map<Integer, Object> parameters = new TreeMap<>();

        Statement(String sql) {
            this.sql = sql;
        }

        List<Object> values() {
            return new ArrayList<>(parameters.values());
        }
    }

    final List<Statement> executed = Collections.synchronizedList(new ArrayList<>());
    private final Function<Statement, Integer> rowCount;
    private final Object previous;

    FakeJdbc(Function<Statement, Integer> rowCount) throws Exception {
        this.rowCount = rowCount;
        previous = FieldUtils.readStaticField(TransactionLegacy.class, "s_ds", true);
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> "getConnection".equals(method.getName()) ? connection() : defaultValue(method.getReturnType()));
        FieldUtils.writeStaticField(TransactionLegacy.class, "s_ds", dataSource, true);
    }

    @Override
    public void close() throws Exception {
        FieldUtils.writeStaticField(TransactionLegacy.class, "s_ds", previous, true);
    }

    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("prepareStatement")) {
                return statement(new Statement((String) args[0]));
            }
            if ("getAutoCommit".equals(method.getName())) {
                return true;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement statement(Statement statement) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                statement.parameters.put((Integer) args[0], "setNull".equals(name) ? null : args[1]);
                return null;
            }
            if ("executeUpdate".equals(name)) {
                executed.add(statement);
                return rowCount.apply(statement);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        return null;
    }
}
