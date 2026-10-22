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
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.gpu.HostGpuGroupsVO;
import com.cloud.gpu.VGPUTypesVO;

@RunWith(MockitoJUnitRunner.class)
public class VGPUTypesDaoImplTest {

    @Mock
    HostGpuGroupsDao hostGpuGroupsDao;

    @Spy
    @InjectMocks
    VGPUTypesDaoImpl vgpuTypesDao = new VGPUTypesDaoImpl();

    private static VGPUTypesVO type(long id, String name) {
        VGPUTypesVO type = new VGPUTypesVO(11L, name, 0L, 0L, 0L, 0L, 1L, 1L, 1L);
        ReflectionTestUtils.setField(type, "id", id);
        return type;
    }

    @Test
    public void aHostReportReplacesTheVgpuTypesOfEachGroup() {
        HostGpuGroupsVO group = new HostGpuGroupsVO(1L, "A100");
        ReflectionTestUtils.setField(group, "id", 11L);
        when(hostGpuGroupsDao.findByHostIdGroupName(1L, "A100")).thenReturn(group);
        VGPUTypesVO kept = type(21L, "passthrough");
        VGPUTypesVO gone = type(22L, "A100-1-5C");
        doReturn(List.of(kept, gone)).when(vgpuTypesDao).listByGroupId(11L);
        doReturn(kept).when(vgpuTypesDao).findByGroupIdVGPUType(11L, "passthrough");
        doReturn(true).when(vgpuTypesDao).remove(anyLong());
        doReturn(true).when(vgpuTypesDao).update(anyLong(), any(VGPUTypesVO.class));
        HashMap<String, VgpuTypesInfo> types = new HashMap<>();
        types.put("passthrough", new VgpuTypesInfo("A100", "passthrough", 0L, 0L, 0L, 0L, 1L, 1L, 1L));
        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails = new HashMap<>();
        groupDetails.put("A100", types);

        vgpuTypesDao.persist(1L, groupDetails);

        verify(vgpuTypesDao).remove(22L);
        verify(vgpuTypesDao, never()).remove(21L);
    }
}
