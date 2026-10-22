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
package com.cloud.cluster.dao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.Executor;

import org.apache.cloudstack.management.ManagementServerHost.State;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class ManagementServerHostDaoImplTest {
    private static final int PREVIOUS_NETWORK_TIMEOUT = 0;

    @Mock
    TransactionLegacy txn;
    @Mock
    PreparedStatement pstmt;
    @Mock
    Connection connection;
    @Mock
    ResultSet resultSet;

    private final ManagementServerHostDaoImpl dao = new ManagementServerHostDaoImpl();

    @Before
    public void setUp() throws Exception {
        when(pstmt.getConnection()).thenReturn(connection);
        when(connection.getNetworkTimeout()).thenReturn(PREVIOUS_NETWORK_TIMEOUT);
    }

    @Test
    public void theStateOfARunIsReadByOneStatementUnderTheStatementAndNetworkTimeouts() throws Exception {
        try (MockedStatic<TransactionLegacy> ignored = Mockito.mockStatic(TransactionLegacy.class)) {
            Mockito.when(TransactionLegacy.currentTxn()).thenReturn(txn);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            when(txn.prepareStatement(sql.capture())).thenReturn(pstmt);
            when(pstmt.executeQuery()).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getString(1)).thenReturn("Maintenance");

            Assert.assertEquals(State.Maintenance, dao.findStateOfRun(11L, 22L, 3));
            Assert.assertNull("no record of that run", dao.findStateOfRun(11L, 23L, 3));

            Assert.assertEquals("SELECT state FROM mshost WHERE msid=? AND runid=?", sql.getValue());
            verify(pstmt, times(2)).setQueryTimeout(3);
            verify(pstmt, times(2)).setLong(1, 11L);
            verify(pstmt).setLong(2, 22L);
            verify(pstmt).setLong(2, 23L);
            InOrder order = Mockito.inOrder(connection, pstmt);
            order.verify(connection).setNetworkTimeout(any(Executor.class), eq(4000));
            order.verify(pstmt).executeQuery();
            order.verify(connection).setNetworkTimeout(any(Executor.class), eq(PREVIOUS_NETWORK_TIMEOUT));
            verify(pstmt, times(2)).close();
            verify(resultSet, times(2)).close();
        }
    }

    @Test
    public void aStatementThatTimesOutFailsTheRead() throws Exception {
        try (MockedStatic<TransactionLegacy> ignored = Mockito.mockStatic(TransactionLegacy.class)) {
            Mockito.when(TransactionLegacy.currentTxn()).thenReturn(txn);
            when(txn.prepareStatement(Mockito.anyString())).thenReturn(pstmt);
            when(pstmt.executeQuery()).thenThrow(new SQLTimeoutException("Statement cancelled due to timeout or client request"));

            Assert.assertThrows(CloudRuntimeException.class, () -> dao.findStateOfRun(11L, 22L, 3));
            verify(connection).setNetworkTimeout(any(Executor.class), eq(PREVIOUS_NETWORK_TIMEOUT));
            verify(pstmt).close();
        }
    }

    @Test
    public void aServerThatStopsAnsweringFailsTheReadAndItsConnectionIsLeftClosed() throws Exception {
        try (MockedStatic<TransactionLegacy> ignored = Mockito.mockStatic(TransactionLegacy.class)) {
            Mockito.when(TransactionLegacy.currentTxn()).thenReturn(txn);
            when(txn.prepareStatement(Mockito.anyString())).thenReturn(pstmt);
            // a driver closes the connection whose read ran into the network timeout
            when(pstmt.executeQuery()).thenThrow(new SQLException("Communications link failure", "08S01", new SocketTimeoutException("Read timed out")));
            when(connection.isClosed()).thenReturn(true);

            Assert.assertThrows(CloudRuntimeException.class, () -> dao.findStateOfRun(11L, 22L, 3));
            verify(connection).setNetworkTimeout(any(Executor.class), eq(4000));
            verify(connection, never()).setNetworkTimeout(any(Executor.class), eq(PREVIOUS_NETWORK_TIMEOUT));
        }
    }
}
