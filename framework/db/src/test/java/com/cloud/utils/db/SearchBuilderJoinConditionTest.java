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
package com.cloud.utils.db;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchBuilderJoinConditionTest {

    @Test
    public void conditionsOnOneJoinUseTheirOwnAttributes() {
        SearchBuilder<DbTestVO> sb = new DbTestDao().createSearchBuilder();
        SearchBuilder<DbTestVO> joined = new DbTestDao().createSearchBuilder();
        sb.join("j", joined, sb.entity().getFieldInt(), joined.entity().getFieldInt(), JoinBuilder.JoinType.INNER);
        sb.and("s", sb.entity().getFieldString(), SearchCriteria.Op.EQ);
        sb.and().op("j", "byString", joined.entity().getFieldString(), SearchCriteria.Op.LIKE);
        sb.or("j", "byLong", joined.entity().getFieldLong(), SearchCriteria.Op.EQ);
        sb.cp();
        sb.and("j", "byInt", joined.entity().getFieldInt(), SearchCriteria.Op.GT);
        sb.done();

        SearchCriteria<DbTestVO> sc = sb.create();
        sc.setParameters("s", "x");
        sc.setParameters("byString", "%a%");
        sc.setParameters("byLong", 2L);
        sc.setParameters("byInt", 3);

        assertEquals("test.fld_string = ? AND ( j.fld_string LIKE ? OR j.fld_long = ? ) AND j.fld_int > ?",
                sc.getWhereClause().trim().replaceAll("\\s+", " "));
        assertEquals(4, sc.getValues().size());
    }
}
