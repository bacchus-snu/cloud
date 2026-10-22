/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.cloud.gpu.dao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.gpu.HostGpuGroupsVO;

@RunWith(MockitoJUnitRunner.class)
public class HostGpuGroupsDaoImplTest {

    @Spy
    HostGpuGroupsDaoImpl hostGpuGroupsDao = new HostGpuGroupsDaoImpl();

    private static HostGpuGroupsVO group(long id, String name) {
        HostGpuGroupsVO group = new HostGpuGroupsVO(1L, name);
        ReflectionTestUtils.setField(group, "id", id);
        return group;
    }

    @Test
    public void aHostReportReplacesTheHostGpuGroups() {
        HostGpuGroupsVO kept = group(11L, "A100");
        HostGpuGroupsVO gone = group(12L, "Display controller");
        doReturn(List.of(kept, gone)).when(hostGpuGroupsDao).listByHostId(1L);
        doReturn(kept).when(hostGpuGroupsDao).findByHostIdGroupName(1L, "A100");
        doReturn(null).when(hostGpuGroupsDao).findByHostIdGroupName(1L, "A6000");
        doReturn(true).when(hostGpuGroupsDao).remove(anyLong());
        doReturn(null).when(hostGpuGroupsDao).persist(any(HostGpuGroupsVO.class));

        hostGpuGroupsDao.persist(1L, List.of("A100", "A6000"));

        verify(hostGpuGroupsDao).remove(12L);
        verify(hostGpuGroupsDao, never()).remove(11L);
        ArgumentCaptor<HostGpuGroupsVO> added = ArgumentCaptor.forClass(HostGpuGroupsVO.class);
        verify(hostGpuGroupsDao).persist(added.capture());
        Assert.assertEquals("A6000", added.getValue().getGroupName());
    }
}
