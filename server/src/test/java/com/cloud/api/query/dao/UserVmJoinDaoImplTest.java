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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.MockitoAnnotations.openMocks;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import com.cloud.storage.dao.VMTemplateDao;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VnfTemplateDetailVO;
import com.cloud.storage.VnfTemplateNicVO;
import com.cloud.storage.dao.VnfTemplateDetailsDao;
import com.cloud.storage.dao.VnfTemplateNicDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.dao.VmIsoMapDao;

@RunWith(MockitoJUnitRunner.class)
public class UserVmJoinDaoImplTest extends GenericDaoBaseWithTagInformationBaseTest<UserVmJoinVO, UserVmResponse> {

    @InjectMocks
    private UserVmJoinDaoImpl _userVmJoinDaoImpl;

    @Mock
    private UserDao userDao;

    @Mock
    private AnnotationDao annotationDao;

    @Mock
    private AccountManager accountMgr;

    @Mock
    private VMInstanceDetailsDao _vmInstanceDetailsDao;

    @Mock
    private UserStatisticsDao userStatsDao;

    @Mock
    private VnfTemplateNicDao vnfTemplateNicDao;

    @Mock
    private VnfTemplateDetailsDao vnfTemplateDetailsDao;

    @Mock
    private VMTemplateDao vmTemplateDao;

    @Mock
    private VmIsoMapDao vmIsoMapDao;

    @Mock
    private HostDetailsDao hostDetailsDao;

    @Mock
    ExtensionHelper extensionHelper;

    @Mock
    ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    private UserVmJoinVO userVm = new UserVmJoinVO();
    private UserVmResponse userVmResponse = new UserVmResponse();

    @Mock
    Account caller;

    @Mock
    UserVmJoinVO userVmMock;

    private Long vmId = 100L;

    private Long templateId = 101L;
    private AutoCloseable closeable;

    @Before
    public void setup() {
        closeable = openMocks(this);
        Mockito.lenient().when(vmIsoMapDao.listByVmId(anyLong())).thenReturn(Collections.emptyList());
        prepareSetup();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        closeable.close();
        super.tearDown();
    }

    @Test
    public void testUpdateUserVmTagInfo(){
        testUpdateTagInformation(_userVmJoinDaoImpl, userVm, userVmResponse);
    }

    private void prepareNewUserVmResponseForVnfAppliance() {
        Mockito.when(userVmMock.getId()).thenReturn(vmId);
        Mockito.when(userVmMock.getTemplateId()).thenReturn(templateId);
        Mockito.when(userVmMock.getTemplateType()).thenReturn(Storage.TemplateType.VNF);
        Mockito.when(userVmMock.getTemplateFormat()).thenReturn(Storage.ImageFormat.OVA);

        Mockito.when(caller.getId()).thenReturn(2L);
        Mockito.when(accountMgr.isRootAdmin(nullable(Long.class))).thenReturn(true);

        SearchBuilder<UserStatisticsVO> searchBuilderMock = Mockito.mock(SearchBuilder.class);
        Mockito.doReturn(searchBuilderMock).when(userStatsDao).createSearchBuilder();
        UserStatisticsVO userStatisticsVOMock = Mockito.mock(UserStatisticsVO.class);
        Mockito.when(searchBuilderMock.entity()).thenReturn(userStatisticsVOMock);
        SearchCriteria<UserStatisticsVO> searchCriteriaMock = Mockito.mock(SearchCriteria.class);
        Mockito.doReturn(searchCriteriaMock).when(searchBuilderMock).create();
        Mockito.doReturn(Arrays.asList()).when(userStatsDao).search(searchCriteriaMock, null);

        VnfTemplateNicVO vnfNic1 = new VnfTemplateNicVO(templateId, 0L, "eth0", true, true, "first");
        VnfTemplateNicVO vnfNic2 = new VnfTemplateNicVO(templateId, 1L, "eth1", true, true, "second");
        Mockito.doReturn(Arrays.asList(vnfNic1, vnfNic2)).when(vnfTemplateNicDao).listByTemplateId(templateId);

        VnfTemplateDetailVO detail1 = new VnfTemplateDetailVO(templateId, "name1", "value1", true);
        VnfTemplateDetailVO detail2 = new VnfTemplateDetailVO(templateId, "name2", "value2", true);
        VnfTemplateDetailVO detail3 = new VnfTemplateDetailVO(templateId, "name3", "value3", true);
        Mockito.doReturn(Arrays.asList(detail1, detail2, detail3)).when(vnfTemplateDetailsDao).listDetails(templateId);
    }

    @Test
    public void testNewUserVmResponseForVnfAppliance() {
        prepareNewUserVmResponseForVnfAppliance();

        UserVmResponse response = _userVmJoinDaoImpl.newUserVmResponse(ResponseObject.ResponseView.Full, "virtualmachine", userVmMock,
                EnumSet.of(ApiConstants.VMDetails.all), null, null, caller);

        Assert.assertEquals(2, response.getVnfNics().size());
        Assert.assertEquals(3, response.getVnfDetails().size());
    }

    @Test
    public void testNewUserVmResponseForVnfApplianceVnfNics() {
        prepareNewUserVmResponseForVnfAppliance();

        UserVmResponse response = _userVmJoinDaoImpl.newUserVmResponse(ResponseObject.ResponseView.Full, "virtualmachine", userVmMock,
                EnumSet.of(ApiConstants.VMDetails.vnfnics), null, null, caller);

        Assert.assertEquals(2, response.getVnfNics().size());
        Assert.assertEquals(3, response.getVnfDetails().size());
    }

    @Test
    public void advertisedCdromCapReturnsDefaultWhenHostIdNull() {
        Assert.assertEquals(com.cloud.template.TemplateManager.DEFAULT_CDROM_MAX_PER_VM,
                _userVmJoinDaoImpl.advertisedCdromCap(null));
    }

    @Test
    public void advertisedCdromCapReturnsParsedValue() {
        com.cloud.host.DetailVO detail = Mockito.mock(com.cloud.host.DetailVO.class);
        Mockito.when(detail.getValue()).thenReturn("2");
        Mockito.when(hostDetailsDao.findDetail(7L, com.cloud.host.Host.HOST_CDROM_MAX_COUNT)).thenReturn(detail);
        Assert.assertEquals(2, _userVmJoinDaoImpl.advertisedCdromCap(7L));
    }

    @Test
    public void advertisedCdromCapFallsBackOnInvalidValue() {
        com.cloud.host.DetailVO detail = Mockito.mock(com.cloud.host.DetailVO.class);
        Mockito.when(detail.getValue()).thenReturn("xyz");
        Mockito.when(hostDetailsDao.findDetail(7L, com.cloud.host.Host.HOST_CDROM_MAX_COUNT)).thenReturn(detail);
        Assert.assertEquals(com.cloud.template.TemplateManager.DEFAULT_CDROM_MAX_PER_VM,
                _userVmJoinDaoImpl.advertisedCdromCap(7L));
    }

    @Test
    public void effectiveCdromMaxCountClampsToHypervisorCap() {
        UserVmJoinVO userVm = Mockito.mock(UserVmJoinVO.class);
        Mockito.when(userVm.getHostId()).thenReturn(7L);
        Mockito.when(userVm.getClusterId()).thenReturn(5L);
        com.cloud.host.DetailVO detail = Mockito.mock(com.cloud.host.DetailVO.class);
        Mockito.when(detail.getValue()).thenReturn("2");
        Mockito.when(hostDetailsDao.findDetail(7L, com.cloud.host.Host.HOST_CDROM_MAX_COUNT)).thenReturn(detail);
        // Configured cap defaults to 1 (no cluster override mocked); host advertises 2; clamps to 1.
        Assert.assertEquals(1, _userVmJoinDaoImpl.effectiveCdromMaxCount(userVm));
    }

    @Test
    public void responseCarriesTheTimeLeftOfTheLeaseAndTheOfferingsLeaseHours() throws Exception {
        java.lang.reflect.Field enabled = org.apache.cloudstack.framework.config.ConfigKey.class.getDeclaredField("_defaultValue");
        enabled.setAccessible(true);
        String original = org.apache.cloudstack.vm.lease.VMLeaseManager.InstanceLeaseEnabled.defaultValue();
        enabled.set(org.apache.cloudstack.vm.lease.VMLeaseManager.InstanceLeaseEnabled, "true");
        try {
            java.util.Date now = new java.util.Date(1_800_000_000_000L);
            Mockito.when(userVmMock.getLeaseExpiryDate()).thenReturn(new java.util.Date(now.getTime() + 35L * 3_600_000L + 30_000L));
            Mockito.when(userVmMock.getLeaseActionExecution()).thenReturn("PENDING");
            Mockito.when(userVmMock.getLeaseExpiryAction()).thenReturn("STOP");
            Mockito.when(userVmMock.getLeaseHours()).thenReturn("36");
            UserVmResponse response = new UserVmResponse();

            _userVmJoinDaoImpl.setLeaseResponse(userVmMock, response, now);

            Assert.assertEquals(Long.valueOf(35L * 3600 + 30), response.getLeaseRemainingSeconds());
            Assert.assertEquals(Integer.valueOf(36), response.getLeaseHours());
            Assert.assertEquals("STOP", response.getLeaseExpiryAction());
            Assert.assertEquals(userVmMock.getLeaseExpiryDate(), response.getLeaseExpiryDate());

            Mockito.when(userVmMock.getLeaseExpiryDate()).thenReturn(new java.util.Date(now.getTime() - 60_000L));
            UserVmResponse ended = new UserVmResponse();
            _userVmJoinDaoImpl.setLeaseResponse(userVmMock, ended, now);
            Assert.assertEquals("an ended lease has no time left", Long.valueOf(0), ended.getLeaseRemainingSeconds());

            // a malformed lease of the offering lists as no lease hours
            Mockito.when(userVmMock.getLeaseHours()).thenReturn("36h");
            UserVmResponse unreadable = new UserVmResponse();
            _userVmJoinDaoImpl.setLeaseResponse(userVmMock, unreadable, now);
            Assert.assertNull(unreadable.getLeaseHours());
            Assert.assertEquals(userVmMock.getLeaseExpiryDate(), unreadable.getLeaseExpiryDate());

            Mockito.when(userVmMock.getLeaseActionExecution()).thenReturn("DISABLED");
            UserVmResponse disabled = new UserVmResponse();
            _userVmJoinDaoImpl.setLeaseResponse(userVmMock, disabled, now);
            Assert.assertNull(disabled.getLeaseExpiryDate());
            Assert.assertNull(disabled.getLeaseRemainingSeconds());

            enabled.set(org.apache.cloudstack.vm.lease.VMLeaseManager.InstanceLeaseEnabled, "false");
            UserVmResponse off = new UserVmResponse();
            _userVmJoinDaoImpl.setLeaseResponse(userVmMock, off, now);
            Assert.assertNull(off.getLeaseRemainingSeconds());
            Mockito.verifyNoInteractions(serviceOfferingDetailsDao);
        } finally {
            enabled.set(org.apache.cloudstack.vm.lease.VMLeaseManager.InstanceLeaseEnabled, original);
        }
    }

    /** A response for an Instance with the display name, named under the prefix, or under none when it is null. */
    private void prepareNamedInstance(String displayName, String prefix) {
        Mockito.lenient().when(userVmMock.getId()).thenReturn(vmId);
        Mockito.lenient().when(userVmMock.getDisplayName()).thenReturn(displayName);
        Mockito.lenient().when(userVmMock.getName()).thenReturn("legacy-host");
        Mockito.lenient().when(userVmMock.getTemplateType()).thenReturn(Storage.TemplateType.USER);
        Mockito.lenient().when(userVmMock.getTemplateFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.lenient().when(caller.getId()).thenReturn(2L);
        SearchBuilder<UserStatisticsVO> searchBuilderMock = Mockito.mock(SearchBuilder.class);
        Mockito.lenient().doReturn(searchBuilderMock).when(userStatsDao).createSearchBuilder();
        Mockito.lenient().when(searchBuilderMock.entity()).thenReturn(Mockito.mock(UserStatisticsVO.class));
        SearchCriteria<UserStatisticsVO> searchCriteriaMock = Mockito.mock(SearchCriteria.class);
        Mockito.lenient().doReturn(searchCriteriaMock).when(searchBuilderMock).create();
        Mockito.lenient().doReturn(Arrays.asList()).when(userStatsDao).search(searchCriteriaMock, null);
        Mockito.lenient().when(_vmInstanceDetailsDao.findSshNamePrefix(vmId)).thenReturn(prefix);
    }

    private UserVmResponse responseFor(Account.Type callerType, ApiConstants.VMDetails detail) {
        Mockito.when(caller.getType()).thenReturn(callerType);
        return _userVmJoinDaoImpl.newUserVmResponse(callerType == Account.Type.ADMIN ? ResponseObject.ResponseView.Full : ResponseObject.ResponseView.Restricted,
                "virtualmachine", userVmMock, EnumSet.of(detail), null, null, caller);
    }

    @Test
    public void everyResponseOfAnInstanceNamedUnderAPrefixCarriesItsSshName() {
        prepareNamedInstance("Web-1", "snucse-baneling100");
        for (Account.Type type : Arrays.asList(Account.Type.ADMIN, Account.Type.NORMAL)) {
            for (ApiConstants.VMDetails detail : ApiConstants.VMDetails.values()) {
                Assert.assertEquals(type + " " + detail, "snucse-baneling100-web-1", responseFor(type, detail).getSshName());
            }
        }
    }

    @Test
    public void theSshNameFollowsTheCurrentNameOfTheInstance() {
        prepareNamedInstance("Renamed", "admin");
        Assert.assertEquals("admin-renamed", responseFor(Account.Type.NORMAL, ApiConstants.VMDetails.min).getSshName());
    }

    @Test
    public void anInstanceCreatedBeforePrefixesHasNoSshName() {
        prepareNamedInstance("My VM", null);
        for (Account.Type type : Arrays.asList(Account.Type.ADMIN, Account.Type.NORMAL)) {
            for (ApiConstants.VMDetails detail : ApiConstants.VMDetails.values()) {
                Assert.assertNull(type + " " + detail, responseFor(type, detail).getSshName());
            }
        }
    }

    @Test
    public void aResponseGivenThePrefixOfTheInstanceNamesReadsNoDetailForIt() {
        prepareNamedInstance("Web", null);
        Mockito.when(caller.getType()).thenReturn(Account.Type.NORMAL);

        UserVmResponse response = _userVmJoinDaoImpl.newUserVmResponse(ResponseObject.ResponseView.Restricted, "virtualmachine", userVmMock,
                EnumSet.of(ApiConstants.VMDetails.min), null, null, caller, "admin");

        Assert.assertEquals("admin-web", response.getSshName());
        Mockito.verify(_vmInstanceDetailsDao, Mockito.never()).findSshNamePrefix(Mockito.anyLong());
    }
}
