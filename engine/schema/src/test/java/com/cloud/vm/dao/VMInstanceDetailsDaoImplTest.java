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

package com.cloud.vm.dao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VmDetailConstants;

@RunWith(MockitoJUnitRunner.class)
public class VMInstanceDetailsDaoImplTest {
    @Spy
    @InjectMocks
    private VMInstanceDetailsDaoImpl vmInstanceDetailsDaoImpl;

    private static Set<Object> valuesOf(SearchCriteria<VMInstanceDetailVO> sc, String column) {
        return sc.getValues().stream().filter(value -> column.equals(value.first().getColumnName())).map(value -> value.second())
                .collect(Collectors.toSet());
    }

    private static void assertSparesTheLifecycleState(SearchCriteria<VMInstanceDetailVO> sc, long vmId) {
        String where = sc.getWhereClause();
        Assert.assertTrue(where, where.contains("vm_id") && where.contains("name NOT IN"));
        Assert.assertEquals(Set.of(vmId), valuesOf(sc, "vm_id"));
        Assert.assertEquals(VmDetailConstants.LIFECYCLE_STATE_DETAILS, valuesOf(sc, "name"));
    }

    @Test
    public void wholeSaveLeavesTheLifecycleStateOfTheInstance() {
        List<String> persisted = new ArrayList<>();
        doAnswer(i -> {
            persisted.add(((VMInstanceDetailVO) i.getArgument(0)).getName());
            return i.getArgument(0);
        }).when(vmInstanceDetailsDaoImpl).persist(any(VMInstanceDetailVO.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SearchCriteria<VMInstanceDetailVO>> deleted = ArgumentCaptor.forClass(SearchCriteria.class);
        doReturn(1).when(vmInstanceDetailsDaoImpl).expunge(deleted.capture());

        try (MockedStatic<TransactionLegacy> txn = Mockito.mockStatic(TransactionLegacy.class)) {
            txn.when(TransactionLegacy::currentTxn).thenReturn(mock(TransactionLegacy.class));
            vmInstanceDetailsDaoImpl.saveDetails(List.of(new VMInstanceDetailVO(1L, "cpuNumber", "4", true),
                    new VMInstanceDetailVO(1L, VmDetailConstants.RESTART_IN_PROGRESS, "job:3", false),
                    new VMInstanceDetailVO(1L, VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, "2027-01-15 08:00:00", false),
                    new VMInstanceDetailVO(1L, VmDetailConstants.INSTANCE_LEASE_EXECUTION, "PENDING", false)));
        }

        Assert.assertEquals("a copy of the set read earlier writes none of the lifecycle state back", List.of("cpuNumber"), persisted);
        assertSparesTheLifecycleState(deleted.getValue(), 1L);
    }

    @Test
    public void wholeSaveWritesNoLifecycleStateInAnyCase() {
        List<String> persisted = new ArrayList<>();
        doAnswer(i -> {
            persisted.add(((VMInstanceDetailVO) i.getArgument(0)).getName());
            return i.getArgument(0);
        }).when(vmInstanceDetailsDaoImpl).persist(any(VMInstanceDetailVO.class));
        doReturn(1).when(vmInstanceDetailsDaoImpl).expunge(any(SearchCriteria.class));

        try (MockedStatic<TransactionLegacy> txn = Mockito.mockStatic(TransactionLegacy.class)) {
            txn.when(TransactionLegacy::currentTxn).thenReturn(mock(TransactionLegacy.class));
            vmInstanceDetailsDaoImpl.saveDetails(List.of(new VMInstanceDetailVO(1L, "cpuNumber", "4", true),
                    new VMInstanceDetailVO(1L, "LEASEEXPIRYDATE", "2099-01-01 00:00:00", true),
                    new VMInstanceDetailVO(1L, "RestartInProgress", "job:3", true)));
        }

        Assert.assertEquals(List.of("cpuNumber"), persisted);
    }

    @Test
    public void wholeRemovalLeavesTheLifecycleStateOfTheInstance() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<SearchCriteria<VMInstanceDetailVO>> removed = ArgumentCaptor.forClass(SearchCriteria.class);
        doReturn(2).when(vmInstanceDetailsDaoImpl).remove(removed.capture());

        vmInstanceDetailsDaoImpl.removeDetails(1L);

        assertSparesTheLifecycleState(removed.getValue(), 1L);
    }

    @Test
    public void removeDetailsWithPrefixReturnsZeroWhenPrefixIsBlank() {
        Assert.assertEquals(0, vmInstanceDetailsDaoImpl.removeDetailsWithPrefix(1L, ""));
        Assert.assertEquals(0, vmInstanceDetailsDaoImpl.removeDetailsWithPrefix(1L, "   "));
        Assert.assertEquals(0, vmInstanceDetailsDaoImpl.removeDetailsWithPrefix(1L, null));
    }

    @Test
    public void removeDetailsWithPrefixRemovesMatchingDetails() {
        SearchBuilder<VMInstanceDetailVO> sb = mock(SearchBuilder.class);
        VMInstanceDetailVO entity = mock(VMInstanceDetailVO.class);
        when(sb.entity()).thenReturn(entity);
        when(sb.and(anyString(), any(), any(SearchCriteria.Op.class))).thenReturn(sb);
        SearchCriteria<VMInstanceDetailVO> sc = mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDetailsDaoImpl.createSearchBuilder()).thenReturn(sb);
        doReturn(3).when(vmInstanceDetailsDaoImpl).remove(sc);
        int removedCount = vmInstanceDetailsDaoImpl.removeDetailsWithPrefix(1L, "testPrefix");
        Assert.assertEquals(3, removedCount);
        Mockito.verify(sc).setParameters("vmId", 1L);
        Mockito.verify(sc).setParameters("prefix", "testPrefix%");
        Mockito.verify(sb).and(Mockito.eq("lifecycleState"), any(), Mockito.eq(SearchCriteria.Op.NIN));
        Mockito.verify(sc).setParameters("lifecycleState", VmDetailConstants.LIFECYCLE_STATE_DETAILS.toArray());
        Mockito.verify(vmInstanceDetailsDaoImpl, Mockito.times(1)).remove(sc);
    }

    @Test
    public void removeDetailsWithPrefixDoesNotRemoveWhenNoMatch() {
        SearchBuilder<VMInstanceDetailVO> sb = mock(SearchBuilder.class);
        VMInstanceDetailVO entity = mock(VMInstanceDetailVO.class);
        when(sb.entity()).thenReturn(entity);
        when(sb.and(anyString(), any(), any(SearchCriteria.Op.class))).thenReturn(sb);
        SearchCriteria<VMInstanceDetailVO> sc = mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDetailsDaoImpl.createSearchBuilder()).thenReturn(sb);
        doReturn(0).when(vmInstanceDetailsDaoImpl).remove(sc);

        int removedCount = vmInstanceDetailsDaoImpl.removeDetailsWithPrefix(1L, "nonExistentPrefix");

        Assert.assertEquals(0, removedCount);
        Mockito.verify(sc).setParameters("vmId", 1L);
        Mockito.verify(sc).setParameters("prefix", "nonExistentPrefix%");
        Mockito.verify(vmInstanceDetailsDaoImpl, Mockito.times(1)).remove(sc);
    }

    private static List<VMInstanceDetailVO> rowsNamedAsThePrefix(long vmId, String genuine) {
        List<VMInstanceDetailVO> rows = new ArrayList<>();
        rows.add(new VMInstanceDetailVO(vmId, "SSH.NAME.PREFIX", "snucse-victim", true));
        rows.add(new VMInstanceDetailVO(vmId, "Ssh.Name.Prefix", "snucse-victim", false));
        rows.add(new VMInstanceDetailVO(vmId, VmDetailConstants.SSH_NAME_PREFIX, "snucse-victim", true));
        if (genuine != null) {
            rows.add(new VMInstanceDetailVO(vmId, VmDetailConstants.SSH_NAME_PREFIX, genuine, false));
        }
        return rows;
    }

    @Test
    public void thePrefixOfAnInstanceIsOnlyItsHiddenDetailOfExactlyThatName() {
        doReturn(rowsNamedAsThePrefix(1L, "admin")).when(vmInstanceDetailsDaoImpl).findDetails(1L, VmDetailConstants.SSH_NAME_PREFIX);
        doReturn(rowsNamedAsThePrefix(2L, null)).when(vmInstanceDetailsDaoImpl).findDetails(2L, VmDetailConstants.SSH_NAME_PREFIX);

        Assert.assertEquals("admin", vmInstanceDetailsDaoImpl.findSshNamePrefix(1L));
        Assert.assertNull(vmInstanceDetailsDaoImpl.findSshNamePrefix(2L));
    }

    @Test
    public void theInstancesUnderAPrefixAreThoseWithItAsTheirHiddenDetailOfExactlyThatName() {
        List<VMInstanceDetailVO> rows = new ArrayList<>(rowsNamedAsThePrefix(1L, null));
        rows.add(new VMInstanceDetailVO(2L, VmDetailConstants.SSH_NAME_PREFIX, "OPS", false));
        rows.add(new VMInstanceDetailVO(3L, VmDetailConstants.SSH_NAME_PREFIX, "ops", false));
        doReturn(rows).when(vmInstanceDetailsDaoImpl).findDetails(VmDetailConstants.SSH_NAME_PREFIX, "ops", false);

        Assert.assertEquals(List.of(2L, 3L), vmInstanceDetailsDaoImpl.listBySshNamePrefix("ops").stream().map(VMInstanceDetailVO::getResourceId)
                .collect(Collectors.toList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void thePrefixesOfInstancesAreReadAtOnce() {
        List<VMInstanceDetailVO> rows = new ArrayList<>(rowsNamedAsThePrefix(1L, "admin"));
        rows.addAll(rowsNamedAsThePrefix(2L, null));
        rows.add(new VMInstanceDetailVO(3L, VmDetailConstants.SSH_NAME_PREFIX, "ops", false));
        ArgumentCaptor<SearchCriteria<VMInstanceDetailVO>> search = ArgumentCaptor.forClass(SearchCriteria.class);
        doReturn(rows).when(vmInstanceDetailsDaoImpl).listBy(search.capture());

        Map<Long, String> prefixes = vmInstanceDetailsDaoImpl.findSshNamePrefixes(List.of(1L, 2L, 3L));

        Assert.assertEquals(Map.of(1L, "admin", 3L, "ops"), prefixes);
        Mockito.verify(vmInstanceDetailsDaoImpl, Mockito.times(1)).listBy(any(SearchCriteria.class));
        Assert.assertEquals(Set.of(1L, 2L, 3L), valuesOf(search.getValue(), "vm_id"));
        Assert.assertTrue(vmInstanceDetailsDaoImpl.findSshNamePrefixes(List.of()).isEmpty());
    }
}
