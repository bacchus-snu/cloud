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
package com.cloud.storage.dao;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.db.Merovingian2;

@RunWith(MockitoJUnitRunner.class)
public class VMTemplatePoolDaoImplTest {

    @Test
    public void lockIsHeldElsewhereOnlyWhenAnotherThreadOrServerOwnsIt() {
        VMTemplatePoolDaoImpl dao = new VMTemplatePoolDaoImpl();
        Merovingian2 locks = mock(Merovingian2.class);
        try (MockedStatic<Merovingian2> controller = Mockito.mockStatic(Merovingian2.class)) {
            controller.when(Merovingian2::getLockController).thenReturn(locks);
            // the key of acquireInLockTable: the table name followed by the id
            when(locks.owns("template_spool_ref300")).thenReturn(-1, 0, 2);

            assertTrue(dao.isLockHeldElsewhere(300L));
            assertFalse("a free lock", dao.isLockHeldElsewhere(300L));
            assertFalse("a lock this thread holds", dao.isLockHeldElsewhere(300L));
        }
    }
}
