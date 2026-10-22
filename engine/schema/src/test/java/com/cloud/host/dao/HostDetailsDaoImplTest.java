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
package com.cloud.host.dao;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.host.DetailVO;

@RunWith(MockitoJUnitRunner.class)
public class HostDetailsDaoImplTest {

    @Spy
    HostDetailsDaoImpl hostDetailsDao = new HostDetailsDaoImpl();

    @Test
    public void aValueIsWrittenAndNoValueRemovesTheDetail() {
        doNothing().when(hostDetailsDao).persist(anyLong(), anyMap());
        hostDetailsDao.setDetail(1L, "name", "value");
        verify(hostDetailsDao).persist(1L, Map.of("name", "value"));

        DetailVO detail = new DetailVO(1L, "name", "value");
        ReflectionTestUtils.setField(detail, "id", 5L);
        doReturn(detail).when(hostDetailsDao).findDetail(1L, "name");
        doReturn(true).when(hostDetailsDao).remove(5L);
        hostDetailsDao.setDetail(1L, "name", null);
        verify(hostDetailsDao).remove(5L);

        doReturn(null).when(hostDetailsDao).findDetail(2L, "name");
        hostDetailsDao.setDetail(2L, "name", null);
        verify(hostDetailsDao).remove(anyLong());
    }
}
