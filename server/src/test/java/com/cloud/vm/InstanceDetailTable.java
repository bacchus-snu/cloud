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

package com.cloud.vm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.dao.UserVmDaoImpl;
import com.cloud.vm.dao.VMInstanceDetailsDaoImpl;

/**
 * The Instance detail table in memory, behind the real detail and Instance DAOs: only the statements the DAOs issue
 * are simulated, each by the conditions of its search criteria.
 */
public class InstanceDetailTable {
    private final List<VMInstanceDetailVO> rows = new ArrayList<>();
    private long nextId = 1;
    public final VMInstanceDetailsDaoImpl detailsDao = Mockito.spy(new VMInstanceDetailsDaoImpl());
    public final UserVmDaoImpl userVmDao = Mockito.mock(UserVmDaoImpl.class, Mockito.CALLS_REAL_METHODS);

    @SuppressWarnings("unchecked")
    public InstanceDetailTable() {
        ReflectionTestUtils.setField(userVmDao, "_detailsDao", detailsDao);
        lenient().doAnswer(i -> select(i.getArgument(0))).when(detailsDao).search(any(SearchCriteria.class), any());
        lenient().doAnswer(i -> select(i.getArgument(0))).when(detailsDao).listBy(any(SearchCriteria.class));
        lenient().doAnswer(i -> select(i.getArgument(0)).stream().findFirst().orElse(null)).when(detailsDao).findOneBy(any(SearchCriteria.class));
        lenient().doAnswer(i -> delete(i.getArgument(0))).when(detailsDao).remove(any(SearchCriteria.class));
        lenient().doAnswer(i -> delete(i.getArgument(0))).when(detailsDao).expunge(any(SearchCriteria.class));
        lenient().doAnswer(i -> rows.removeIf(row -> row.getId() == (Long) i.getArgument(0))).when(detailsDao).remove(anyLong());
        lenient().doAnswer(i -> {
            VMInstanceDetailVO row = i.getArgument(0);
            ReflectionTestUtils.setField(row, "id", nextId++);
            rows.add(row);
            return row;
        }).when(detailsDao).persist(any(VMInstanceDetailVO.class));
    }

    public synchronized void put(long vmId, String name, String value, boolean display) {
        detailsDao.persist(new VMInstanceDetailVO(vmId, name, value, display));
    }

    public synchronized String get(long vmId, String name) {
        return rows.stream().filter(row -> row.getResourceId() == vmId && row.getName().equals(name)).map(VMInstanceDetailVO::getValue)
                .findFirst().orElse(null);
    }

    private synchronized List<VMInstanceDetailVO> select(SearchCriteria<VMInstanceDetailVO> sc) {
        return rows.stream().filter(matches(sc)).collect(Collectors.toList());
    }

    private synchronized int delete(SearchCriteria<VMInstanceDetailVO> sc) {
        List<VMInstanceDetailVO> deleted = select(sc);
        rows.removeAll(deleted);
        return deleted.size();
    }

    private static java.util.function.Predicate<VMInstanceDetailVO> matches(SearchCriteria<VMInstanceDetailVO> sc) {
        String where = sc.getWhereClause();
        Set<Object> vmIds = valuesOf(sc, "vm_id");
        Set<Object> names = valuesOf(sc, "name");
        Set<Object> values = valuesOf(sc, "value");
        Set<Object> display = valuesOf(sc, "display");
        boolean namesExcluded = where.contains("name NOT IN");
        return row -> (vmIds.isEmpty() || vmIds.contains(row.getResourceId()))
                && (names.isEmpty() || names.contains(row.getName()) != namesExcluded)
                && (values.isEmpty() || values.contains(row.getValue()))
                && (display.isEmpty() || display.stream().anyMatch(value -> Objects.equals(value, row.isDisplay())));
    }

    private static Set<Object> valuesOf(SearchCriteria<VMInstanceDetailVO> sc, String column) {
        return sc.getValues().stream().filter(value -> column.equals(value.first().getColumnName())).map(value -> value.second())
                .collect(Collectors.toSet());
    }
}
