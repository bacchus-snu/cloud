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

package com.cloud.api.query.dao;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.utils.db.GenericDaoBase;

/**
 * The lease columns of the views come from free-form offering details, so a row with a malformed value still maps.
 */
@RunWith(MockitoJUnitRunner.class)
public class LeaseViewColumnsTest {

    private static ResultSet column(String value) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getBytes(1)).thenReturn(value.getBytes(StandardCharsets.UTF_8));
        lenient().when(rs.getObject(1)).thenReturn(value);
        lenient().when(rs.getString(1)).thenReturn(value);
        lenient().when(rs.getInt(1)).thenThrow(new SQLException("Invalid value for getInt() - '" + value + "'"));
        return rs;
    }

    private static Object map(GenericDaoBase<?, ?> dao, Object entity, String fieldName, ResultSet rs) throws Exception {
        Method setField = GenericDaoBase.class.getDeclaredMethod("setField", Object.class, Field.class, ResultSet.class, int.class);
        setField.setAccessible(true);
        Field field = entity.getClass().getDeclaredField(fieldName);
        // as the DAO's own attributes are
        field.setAccessible(true);
        setField.invoke(dao, entity, field, rs, 1);
        return field.get(entity);
    }

    @Test
    public void malformedLeaseValuesMapWithoutFailingTheRow() throws Exception {
        Assert.assertEquals("36h", map(new ServiceOfferingJoinDaoImpl(), new ServiceOfferingJoinVO(), "leaseHours", column("36h")));
        Assert.assertEquals("36h", map(new UserVmJoinDaoImpl(), new UserVmJoinVO(), "leaseHours", column("36h")));
        Assert.assertNull("an action the enum does not know maps to none", map(new ServiceOfferingJoinDaoImpl(), new ServiceOfferingJoinVO(),
                "leaseExpiryAction", column("SUSPEND")));
    }
}
