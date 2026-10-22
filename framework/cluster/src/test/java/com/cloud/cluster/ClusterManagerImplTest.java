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

package com.cloud.cluster;

import java.util.Date;
import java.util.List;

import javax.naming.ConfigurationException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.cluster.dao.ManagementServerHostDao;

@RunWith(MockitoJUnitRunner.class)
public class ClusterManagerImplTest {
    @Mock
    ManagementServerHostDao mshostDao;

    @Spy
    @InjectMocks
    ClusterManagerImpl clusterManager = new ClusterManagerImpl();

    @Test
    public void testGetSelfNodeIP() {
        String ip = "1.2.3.4";
        ReflectionTestUtils.setField(clusterManager, "_clusterNodeIP", ip);
        Assert.assertEquals(ip, clusterManager.getSelfNodeIP());
    }

    private ManagementServerHostVO peer(long msid, String serviceIp, Date lastUpdate) {
        ManagementServerHostVO peer = new ManagementServerHostVO(msid, 1L, serviceIp, 9090, lastUpdate);
        peer.setLastUpdateTime(lastUpdate);
        return peer;
    }

    @Test
    public void aRunningNodeWithTheSameNodeIdStopsTheStartOnly() throws ConfigurationException {
        ReflectionTestUtils.setField(clusterManager, "_clusterNodeIP", "10.0.0.2");
        ManagementServerHostVO sameId = peer(clusterManager.getManagementNodeId(), "10.0.0.1", new Date());
        Mockito.when(mshostDao.getActiveList(Mockito.any(Date.class))).thenReturn(List.of(sameId));

        Mockito.doReturn(true).when(clusterManager).isHeartbeatAdvancing(sameId);
        Assert.assertThrows(ConfigurationException.class, () -> clusterManager.checkConflicts());

        Mockito.doReturn(false).when(clusterManager).isHeartbeatAdvancing(sameId);
        clusterManager.checkConflicts();
    }

    @Test
    public void aStoppedNodeLeavesItsHeartbeatBehind() {
        long msid = clusterManager.getManagementNodeId();
        Date last = new Date(System.currentTimeMillis() - 1000);
        ManagementServerHostVO seen = peer(msid, "10.0.0.1", last);
        Mockito.when(mshostDao.findByMsid(msid)).thenReturn(peer(msid, "10.0.0.1", last), peer(msid, "10.0.0.1", new Date()));

        Assert.assertFalse(clusterManager.isHeartbeatAdvancing(seen));
        Assert.assertTrue(clusterManager.isHeartbeatAdvancing(seen));
    }
}
