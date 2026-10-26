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

import static org.mockito.Mockito.doReturn;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.storage.SnapshotVO;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotDaoImplTest {

    @Spy
    SnapshotDaoImpl snapshotDao = new SnapshotDaoImpl();

    @Test
    @SuppressWarnings("unchecked")
    public void theSnapshotsOfAnAccountWithANamePrefixAreListedRemovedOnesIncluded() {
        snapshotDao.initAccountNamePrefixSearch();
        ArgumentCaptor<SearchCriteria<SnapshotVO>> search = ArgumentCaptor.forClass(SearchCriteria.class);
        List<SnapshotVO> snapshots = List.of(Mockito.mock(SnapshotVO.class));
        doReturn(snapshots).when(snapshotDao).searchIncludingRemoved(search.capture(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.eq(false));

        Assert.assertEquals(snapshots, snapshotDao.listByAccountAndNamePrefixIncludingRemoved(5L, "web-ROOT"));

        Assert.assertTrue(search.getValue().getValues().stream()
                .anyMatch(value -> "account_id".equals(value.first().getColumnName()) && Long.valueOf(5L).equals(value.second())));
        Assert.assertTrue(search.getValue().getValues().stream()
                .anyMatch(value -> "name".equals(value.first().getColumnName()) && "web-ROOT%".equals(value.second())));
    }
}
