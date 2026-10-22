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
package org.apache.cloudstack.gpu;

import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.gpu.GpuCardVO;
import com.cloud.gpu.GpuDeviceVO;
import com.cloud.gpu.HostGpuGroupMemberVO;
import com.cloud.gpu.HostGpuGroupRuleVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.GpuCardDao;
import com.cloud.gpu.dao.GpuDeviceDao;
import com.cloud.gpu.dao.HostGpuGroupMemberDao;
import com.cloud.gpu.dao.HostGpuGroupRuleDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.ListGpuDevicesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.gpu.UnmanageGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.DiscoverGpuDevicesCmd;
import org.apache.cloudstack.api.command.admin.gpu.ListHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.ManageGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateVgpuProfileCmd;
import org.apache.cloudstack.api.command.user.gpu.ListGpuCardsCmd;
import org.apache.cloudstack.api.command.user.gpu.ListVgpuProfilesCmd;
import org.apache.cloudstack.api.response.GpuCardResponse;
import org.apache.cloudstack.api.response.GpuDeviceResponse;
import org.apache.cloudstack.api.response.HostGpuGroupsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VgpuProfileResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GpuServiceImplTest {

    // Test Constants
    private static final String GPU_DEVICE_ID = "1234";
    private static final String GPU_DEVICE_NAME = "RTX 4090";
    private static final String GPU_CARD_NAME = "NVIDIA RTX 4090";
    private static final String GPU_VENDOR_NAME = "NVIDIA";
    private static final String GPU_VENDOR_ID = "10de";
    private static final String VGPU_PROFILE_NAME = "grid_rtx4090-8q";
    private static final String VGPU_PROFILE_DESCRIPTION = "RTX 4090 8GB profile";
    private static final String GPU_BUS_ADDRESS = "0000:01:00.0";
    private static final String GPU_BUS_ADDRESS_2 = "0000:02:00.0";
    private static final String GPU_BUS_ADDRESS_3 = "0000:03:00.0";
    private static final String GPU_CARD_UUID = "gpu-card-uuid";
    private static final String VGPU_PROFILE_UUID = "vgpu-profile-uuid";
    private static final String GPU_DEVICE_UUID = "gpu-device-uuid";
    private static final String HOST_UUID = "host-uuid";
    private static final String HOST_NAME = "test-host";
    private static final String VM_NAME = "test-vm";
    private static final String NUMA_NODE_0 = "numa0";
    private static final String TEST_PROFILE_NAME = "test-profile";
    private static final String UPDATED_PROFILE_NAME = "updated-profile";
    private static final String UPDATED_PROFILE_DESCRIPTION = "Updated description";
    private static final String UPDATED_GPU_DEVICE_NAME = "Updated RTX 4090";
    private static final String UPDATED_GPU_CARD_NAME = "Updated NVIDIA RTX 4090";
    private static final String UPDATED_GPU_VENDOR_NAME = "Updated NVIDIA";
    private static final String PASSTHROUGH_PROFILE = "passthrough";
    private static final String COMMAND_CREATE_VGPU_PROFILE = "createVgpuProfile";
    private static final String COMMAND_UPDATE_VGPU_PROFILE = "updateVgpuProfile";
    private static final String COMMAND_LIST_GPU_CARDS = "listGpuCards";
    private static final String COMMAND_LIST_VGPU_PROFILES = "listVgpuProfiles";
    private static final String COMMAND_LIST_GPU_DEVICES = "listGpuDevices";
    private static final String COMMAND_DISCOVER_GPU_DEVICES = "discoverGpuDevices";
    private static final String CONFIG_COMPONENT_NAME = "GpuService";

    // Test IDs
    private static final Long GPU_CARD_ID = 1L;
    private static final Long SERVICE_OFFERING_ID = 1L;
    private static final Long VGPU_PROFILE_ID = 1L;
    private static final Long GPU_DEVICE_ID_LONG = 1L;
    private static final Long HOST_ID = 1L;
    private static final Long VM_ID = 1L;
    private static final Long MAX_VGPU_PER_PGPU = 4L;
    private static final Long UPDATED_MAX_VGPU_PER_PGPU = 8L;
    private static final Long START_INDEX = 0L;
    private static final Long PAGE_SIZE = 20L;
    private static final Long INVALID_ID = 999L;
    private static final Long VM_ID_100 = 100L;
    private static final Long GPU_CARD_ID_2 = 2L;
    private static final Long GPU_DEVICE_ID_2 = 2L;
    private static final Long PARENT_GPU_DEVICE_ID = 2L;
    private static final Long DIFFERENT_HOST_ID = 99L;
    private static final Long DIFFERENT_CARD_ID = 99L;

    @Mock
    private GpuCardDao gpuCardDao;

    @Mock
    private VgpuProfileDao vgpuProfileDao;

    @Mock
    private GpuDeviceDao gpuDeviceDao;

    @Mock
    private ServiceOfferingDao serviceOfferingDao;

    @Mock
    private HostDao hostDao;

    @Mock
    private UserVmManager userVmManager;

    @Mock
    private VMInstanceDao vmInstanceDao;

    @Mock
    private ResourceManager resourceManager;

    @InjectMocks
    @Spy
    private GpuServiceImpl gpuService;

    @Mock
    private GpuCardVO mockGpuCard;

    @Mock
    private VgpuProfileVO mockVgpuProfile;

    @Mock
    private ServiceOfferingVO mockServiceOffering;

    @Mock
    private GpuDeviceVO mockGpuDevice;

    @Mock
    private HostVO mockHost;

    @Before
    public void setUp() {
        // Setup GPU Card mock
        when(mockGpuCard.getId()).thenReturn(GPU_CARD_ID);
        when(mockGpuCard.getUuid()).thenReturn(GPU_CARD_UUID);
        when(mockGpuCard.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(mockGpuCard.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(mockGpuCard.getName()).thenReturn(GPU_CARD_NAME);
        when(mockGpuCard.getVendorName()).thenReturn(GPU_VENDOR_NAME);
        when(mockGpuCard.getVendorId()).thenReturn(GPU_VENDOR_ID);

        // Setup vGPU Profile mock
        when(mockVgpuProfile.getId()).thenReturn(VGPU_PROFILE_ID);
        when(mockVgpuProfile.getUuid()).thenReturn(VGPU_PROFILE_UUID);
        when(mockVgpuProfile.getName()).thenReturn(VGPU_PROFILE_NAME);
        when(mockVgpuProfile.getDescription()).thenReturn(VGPU_PROFILE_DESCRIPTION);
        when(mockVgpuProfile.getCardId()).thenReturn(GPU_CARD_ID);
        when(mockVgpuProfile.getMaxVgpuPerPgpu()).thenReturn(MAX_VGPU_PER_PGPU);

        // Setup GPU Device mock
        when(mockGpuDevice.getId()).thenReturn(GPU_DEVICE_ID_LONG);
        when(mockGpuDevice.getUuid()).thenReturn(GPU_DEVICE_UUID);
        when(mockGpuDevice.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);
        when(mockGpuDevice.getHostId()).thenReturn(HOST_ID);
        when(mockGpuDevice.getCardId()).thenReturn(GPU_CARD_ID);
        when(mockGpuDevice.getVgpuProfileId()).thenReturn(VGPU_PROFILE_ID);
        when(mockGpuDevice.getType()).thenReturn(GpuDevice.DeviceType.PCI);
        when(mockGpuDevice.getState()).thenReturn(GpuDevice.State.Free);
        when(mockGpuDevice.getManagedState()).thenReturn(GpuDevice.ManagedState.Managed);
        when(mockGpuDevice.getVmId()).thenReturn(null);
        when(mockGpuDevice.getParentGpuDeviceId()).thenReturn(null);

        // Setup Host mock
        when(mockHost.getId()).thenReturn(HOST_ID);
        when(mockHost.getUuid()).thenReturn(HOST_UUID);
        when(mockHost.getName()).thenReturn(HOST_NAME);
        when(mockHost.getStatus()).thenReturn(Status.Up);

        // Setup Service Offering mock
        when(mockServiceOffering.getGpuDisplay()).thenReturn(false);

    }

    @Test
    public void testConfigure() throws ConfigurationException {
        Map<String, Object> params = new HashMap<>();
        boolean result = gpuService.configure(CONFIG_COMPONENT_NAME, params);
        assertTrue(result);
    }

    @Test
    public void testGetCommands() {
        List<Class<?>> commands = gpuService.getCommands();
        assertNotNull(commands);
        assertFalse(commands.isEmpty());
        assertTrue(commands.contains(CreateGpuCardCmd.class));
        assertTrue(commands.contains(ListGpuCardsCmd.class));
        assertTrue(commands.contains(CreateVgpuProfileCmd.class));
        assertTrue(commands.contains(ListGpuDevicesCmdByAdmin.class));
    }

    @Test
    public void testGetConfigComponentName() {
        String componentName = gpuService.getConfigComponentName();
        assertEquals(CONFIG_COMPONENT_NAME, componentName);
    }

    // GPU Card Tests
    @Test
    public void testCreateGpuCard_Success() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(cmd.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(cmd.getName()).thenReturn(GPU_CARD_NAME);
        when(cmd.getVendorName()).thenReturn(GPU_VENDOR_NAME);
        when(cmd.getVendorId()).thenReturn(GPU_VENDOR_ID);

        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(null);
        when(gpuCardDao.persist(any(GpuCardVO.class))).thenReturn(mockGpuCard);
        when(vgpuProfileDao.persist(any(VgpuProfileVO.class))).thenReturn(mockVgpuProfile);

        GpuCardVO result = gpuService.createGpuCard(cmd);

        assertNotNull(result);
        assertEquals(GPU_CARD_UUID, result.getUuid());
        assertEquals(GPU_DEVICE_ID, result.getDeviceId());
        assertEquals(GPU_DEVICE_NAME, result.getDeviceName());
        assertEquals(GPU_CARD_NAME, result.getName());
        assertEquals(GPU_VENDOR_NAME, result.getVendorName());
        assertEquals(GPU_VENDOR_ID, result.getVendorId());
        verify(gpuCardDao).persist(any(GpuCardVO.class));
        verify(vgpuProfileDao).persist(any(VgpuProfileVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuCard_BlankDeviceId() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn("");

        gpuService.createGpuCard(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuCard_NullDeviceId() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn(null);

        gpuService.createGpuCard(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuCard_DuplicateCard() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(cmd.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(cmd.getName()).thenReturn(GPU_CARD_NAME);
        when(cmd.getVendorName()).thenReturn(GPU_VENDOR_NAME);
        when(cmd.getVendorId()).thenReturn(GPU_VENDOR_ID);

        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(mockGpuCard);

        gpuService.createGpuCard(cmd);
    }

    @Test
    public void testUpdateGpuCard_Success() {
        UpdateGpuCardCmd cmd = mock(UpdateGpuCardCmd.class);
        when(cmd.getId()).thenReturn(GPU_CARD_ID);
        when(cmd.getDeviceName()).thenReturn(UPDATED_GPU_DEVICE_NAME);
        when(cmd.getName()).thenReturn(UPDATED_GPU_CARD_NAME);
        when(cmd.getVendorName()).thenReturn(UPDATED_GPU_VENDOR_NAME);

        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(gpuCardDao.update(eq(GPU_CARD_ID), any(GpuCardVO.class))).thenReturn(true);

        GpuCardVO result = gpuService.updateGpuCard(cmd);

        assertNotNull(result);
        assertEquals(GPU_CARD_UUID, result.getUuid());
        verify(gpuCardDao).update(eq(GPU_CARD_ID), any(GpuCardVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateGpuCard_NotFound() {
        UpdateGpuCardCmd cmd = mock(UpdateGpuCardCmd.class);
        when(cmd.getId()).thenReturn(INVALID_ID);

        when(gpuCardDao.findById(INVALID_ID)).thenReturn(null);

        gpuService.updateGpuCard(cmd);
    }

    @Test
    public void testDeleteGpuCard_Success() {
        DeleteGpuCardCmd cmd = mock(DeleteGpuCardCmd.class);
        when(cmd.getId()).thenReturn(GPU_CARD_ID);

        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(gpuDeviceDao.isGpuCardInUse(GPU_CARD_ID)).thenReturn(false);
        when(gpuCardDao.remove(GPU_CARD_ID)).thenReturn(true);

        boolean result = gpuService.deleteGpuCard(cmd);

        assertTrue(result);
        verify(gpuCardDao).remove(GPU_CARD_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteGpuCard_InUse() {
        DeleteGpuCardCmd cmd = mock(DeleteGpuCardCmd.class);
        when(cmd.getId()).thenReturn(GPU_CARD_ID);

        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(gpuDeviceDao.isGpuCardInUse(GPU_CARD_ID)).thenReturn(true);

        gpuService.deleteGpuCard(cmd);
    }

    // vGPU Profile Tests
    @Test
    public void testCreateVgpuProfile_Success() {
        CreateVgpuProfileCmd cmd = mock(CreateVgpuProfileCmd.class);
        when(cmd.getName()).thenReturn(VGPU_PROFILE_NAME);
        when(cmd.getDescription()).thenReturn(VGPU_PROFILE_DESCRIPTION);
        when(cmd.getCardId()).thenReturn(GPU_CARD_ID);
        when(cmd.getMaxVgpuPerPgpu()).thenReturn(MAX_VGPU_PER_PGPU);
        when(cmd.getCommandName()).thenReturn(COMMAND_CREATE_VGPU_PROFILE);

        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.persist(any(VgpuProfileVO.class))).thenReturn(mockVgpuProfile);

        VgpuProfileResponse result = gpuService.createVgpuProfile(cmd);

        assertNotNull(result);
        assertEquals(VGPU_PROFILE_UUID, result.getId());
        assertEquals(VGPU_PROFILE_NAME, result.getName());
        assertEquals(VGPU_PROFILE_DESCRIPTION, result.getDescription());
        assertEquals(GPU_CARD_UUID, result.getGpuCardId());
        verify(vgpuProfileDao).persist(any(VgpuProfileVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateVgpuProfile_BlankName() {
        CreateVgpuProfileCmd cmd = mock(CreateVgpuProfileCmd.class);
        when(cmd.getName()).thenReturn("");

        gpuService.createVgpuProfile(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateVgpuProfile_InvalidCardId() {
        CreateVgpuProfileCmd cmd = mock(CreateVgpuProfileCmd.class);
        when(cmd.getName()).thenReturn(TEST_PROFILE_NAME);
        when(cmd.getCardId()).thenReturn(INVALID_ID);

        when(gpuCardDao.findById(INVALID_ID)).thenReturn(null);

        gpuService.createVgpuProfile(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateVgpuProfile_DuplicateName() {
        CreateVgpuProfileCmd cmd = mock(CreateVgpuProfileCmd.class);
        when(cmd.getName()).thenReturn(TEST_PROFILE_NAME);
        when(cmd.getCardId()).thenReturn(GPU_CARD_ID);

        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findByNameAndCardId(TEST_PROFILE_NAME, GPU_CARD_ID)).thenReturn(mockVgpuProfile);

        gpuService.createVgpuProfile(cmd);
    }

    @Test
    public void testUpdateVgpuProfile_Success() {
        UpdateVgpuProfileCmd cmd = mock(UpdateVgpuProfileCmd.class);
        when(cmd.getId()).thenReturn(VGPU_PROFILE_ID);
        when(cmd.getProfileName()).thenReturn(UPDATED_PROFILE_NAME);
        when(cmd.getDescription()).thenReturn(UPDATED_PROFILE_DESCRIPTION);
        when(cmd.getMaxVgpuPerPgpu()).thenReturn(UPDATED_MAX_VGPU_PER_PGPU);
        when(cmd.getCommandName()).thenReturn(COMMAND_UPDATE_VGPU_PROFILE);

        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(vgpuProfileDao.findByNameAndCardId(UPDATED_PROFILE_NAME, GPU_CARD_ID)).thenReturn(null);
        when(vgpuProfileDao.update(eq(VGPU_PROFILE_ID), any(VgpuProfileVO.class))).thenReturn(true);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);

        VgpuProfileResponse result = gpuService.updateVgpuProfile(cmd);

        assertNotNull(result);
        assertEquals(VGPU_PROFILE_UUID, result.getId());
        verify(vgpuProfileDao).update(eq(VGPU_PROFILE_ID), any(VgpuProfileVO.class));
    }

    @Test
    public void testDeleteVgpuProfile_Success() {
        DeleteVgpuProfileCmd cmd = mock(DeleteVgpuProfileCmd.class);
        when(cmd.getId()).thenReturn(VGPU_PROFILE_ID);

        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.isVgpuProfileInUse(VGPU_PROFILE_ID)).thenReturn(false);
        when(vgpuProfileDao.remove(VGPU_PROFILE_ID)).thenReturn(true);

        boolean result = gpuService.deleteVgpuProfile(cmd);

        assertTrue(result);
        verify(vgpuProfileDao).remove(VGPU_PROFILE_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDeleteVgpuProfile_InUse() {
        DeleteVgpuProfileCmd cmd = mock(DeleteVgpuProfileCmd.class);
        when(cmd.getId()).thenReturn(VGPU_PROFILE_ID);

        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.isVgpuProfileInUse(VGPU_PROFILE_ID)).thenReturn(true);

        gpuService.deleteVgpuProfile(cmd);
    }

    // List Methods Tests
    @Test
    public void testListGpuCards_Success() {
        ListGpuCardsCmd cmd = mock(ListGpuCardsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getVendorName()).thenReturn(null);
        when(cmd.getVendorId()).thenReturn(null);
        when(cmd.getDeviceId()).thenReturn(null);
        when(cmd.getDeviceName()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(START_INDEX);
        when(cmd.getPageSizeVal()).thenReturn(PAGE_SIZE);
        when(cmd.getCommandName()).thenReturn(COMMAND_LIST_GPU_CARDS);

        List<GpuCardVO> gpuCards = List.of(mockGpuCard);
        Pair<List<GpuCardVO>, Integer> result = new Pair<>(gpuCards, 1);
        when(gpuCardDao.searchAndCountGpuCards(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(
                result);

        ListResponse<GpuCardResponse> response = gpuService.listGpuCards(cmd);

        assertNotNull(response);
        assertEquals(1, response.getResponses().size());
        assertEquals(GPU_CARD_UUID, response.getResponses().get(0).getId());
    }

    @Test
    public void testListVgpuProfiles_Success() {
        ListVgpuProfilesCmd cmd = mock(ListVgpuProfilesCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getCardId()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(START_INDEX);
        when(cmd.getPageSizeVal()).thenReturn(PAGE_SIZE);
        when(cmd.getCommandName()).thenReturn(COMMAND_LIST_VGPU_PROFILES);

        List<VgpuProfileVO> vgpuProfiles = List.of(mockVgpuProfile);
        Pair<List<VgpuProfileVO>, Integer> result = new Pair<>(vgpuProfiles, 1);
        when(vgpuProfileDao.searchAndCountVgpuProfiles(any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(result);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);

        ListResponse<VgpuProfileResponse> response = gpuService.listVgpuProfiles(cmd);

        assertNotNull(response);
        assertEquals(1, response.getResponses().size());
        assertEquals(VGPU_PROFILE_UUID, response.getResponses().get(0).getId());
    }

    // GPU Device Tests
    @Test
    public void testCreateGpuDevice_Success() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);
        when(cmd.getGpuCardId()).thenReturn(GPU_CARD_ID);
        when(cmd.getVgpuProfileId()).thenReturn(VGPU_PROFILE_ID);
        when(cmd.getType()).thenReturn(GpuDevice.DeviceType.PCI);
        when(cmd.getParentGpuDeviceId()).thenReturn(null);
        when(cmd.getNumaNode()).thenReturn(NUMA_NODE_0);

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(null);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenReturn(mockGpuDevice);

        GpuDeviceResponse result = gpuService.createGpuDevice(cmd);

        assertNotNull(result);
        assertEquals(GPU_DEVICE_UUID, result.getId());
        assertEquals(GPU_BUS_ADDRESS, result.getBussAddress());
        assertEquals(GPU_CARD_UUID, result.getGpuCardId());
        assertEquals(VGPU_PROFILE_UUID, result.getVgpuProfileId());
        assertEquals(HOST_UUID, result.getHostId());
        verify(gpuDeviceDao).persist(any(GpuDeviceVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuDevice_HostNotFound() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(INVALID_ID);

        when(hostDao.findById(INVALID_ID)).thenReturn(null);

        gpuService.createGpuDevice(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuDevice_DuplicateBusAddress() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(mockGpuDevice);

        gpuService.createGpuDevice(cmd);
    }

    @Test
    public void testUpdateGpuDevice_Success() {
        UpdateGpuDeviceCmd cmd = mock(UpdateGpuDeviceCmd.class);
        when(cmd.getId()).thenReturn(GPU_DEVICE_ID_LONG);
        when(cmd.getGpuCardId()).thenReturn(GPU_CARD_ID_2);
        when(cmd.getVgpuProfileId()).thenReturn(GPU_CARD_ID_2);
        when(cmd.getParentGpuDeviceId()).thenReturn(null);

        GpuDeviceVO device = mock(GpuDeviceVO.class);
        when(device.getCardId()).thenReturn(GPU_CARD_ID);
        when(device.getVgpuProfileId()).thenReturn(VGPU_PROFILE_ID);
        when(device.getVmId()).thenReturn(null);

        VgpuProfileVO newProfile = mock(VgpuProfileVO.class);
        when(newProfile.getCardId()).thenReturn(GPU_CARD_ID_2);

        when(gpuDeviceDao.findById(GPU_DEVICE_ID_LONG)).thenReturn(device);
        when(gpuCardDao.findById(GPU_CARD_ID_2)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findById(GPU_CARD_ID_2)).thenReturn(newProfile);
        when(gpuDeviceDao.update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class))).thenReturn(true);

        GpuDeviceResponse result = gpuService.updateGpuDevice(cmd);

        assertNotNull(result);
        verify(gpuDeviceDao).update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateGpuDevice_DeviceAllocated() {
        UpdateGpuDeviceCmd cmd = mock(UpdateGpuDeviceCmd.class);
        when(cmd.getId()).thenReturn(GPU_DEVICE_ID_LONG);

        GpuDeviceVO device = mock(GpuDeviceVO.class);
        when(device.getVmId()).thenReturn(VM_ID_100);

        when(gpuDeviceDao.findById(GPU_DEVICE_ID_LONG)).thenReturn(device);

        gpuService.updateGpuDevice(cmd);
    }

    @Test
    public void testListGpuDevices_Success() {
        ListGpuDevicesCmdByAdmin cmd = mock(ListGpuDevicesCmdByAdmin.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getHostId()).thenReturn(null);
        when(cmd.getGpuCardId()).thenReturn(null);
        when(cmd.getVgpuProfileId()).thenReturn(null);
        when(cmd.getVmId()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(START_INDEX);
        when(cmd.getPageSizeVal()).thenReturn(PAGE_SIZE);
        when(cmd.getCommandName()).thenReturn(COMMAND_LIST_GPU_DEVICES);
        when(cmd.getResponseView()).thenReturn(ResponseObject.ResponseView.Full);

        List<GpuDeviceVO> gpuDevices = List.of(mockGpuDevice);
        Pair<List<GpuDeviceVO>, Integer> result = new Pair<>(gpuDevices, 1);
        when(gpuDeviceDao.searchAndCountGpuDevices(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                result);

        ListResponse<GpuDeviceResponse> response = gpuService.listGpuDevices(cmd);

        assertNotNull(response);
        assertEquals(1, response.getResponses().size());
        assertEquals(GPU_DEVICE_UUID, response.getResponses().get(0).getId());
    }

    @Test
    public void testDisableGpuDevice_Success() {
        UnmanageGpuDeviceCmd cmd = mock(UnmanageGpuDeviceCmd.class);
        when(cmd.getIds()).thenReturn(List.of(GPU_DEVICE_ID_LONG));

        GpuDeviceVO device = mock(GpuDeviceVO.class);
        when(device.getId()).thenReturn(GPU_DEVICE_ID_LONG);
        when(device.getManagedState()).thenReturn(GpuDevice.ManagedState.Managed);
        when(device.getVmId()).thenReturn(null);
        when(device.getHostId()).thenReturn(HOST_ID);

        when(gpuDeviceDao.findById(GPU_DEVICE_ID_LONG)).thenReturn(device);
        when(gpuDeviceDao.update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class))).thenReturn(true);

        doReturn(null).when(gpuService).getGpuGroupDetailsFromGpuDevicesOnHost(anyLong());
        runTransactionsInline();

        boolean result = gpuService.disableGpuDevice(cmd);

        assertTrue(result);
        verify(gpuDeviceDao).update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class));
    }

    @Test
    public void testEnableGpuDevice_Success() {
        ManageGpuDeviceCmd cmd = mock(ManageGpuDeviceCmd.class);
        when(cmd.getIds()).thenReturn(List.of(GPU_DEVICE_ID_LONG));

        GpuDeviceVO device = mock(GpuDeviceVO.class);
        when(device.getId()).thenReturn(GPU_DEVICE_ID_LONG);
        when(device.getManagedState()).thenReturn(GpuDevice.ManagedState.Unmanaged);
        when(device.getVmId()).thenReturn(null);
        when(device.getHostId()).thenReturn(HOST_ID);

        when(gpuDeviceDao.findById(GPU_DEVICE_ID_LONG)).thenReturn(device);
        when(gpuDeviceDao.update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class))).thenReturn(true);

        doReturn(null).when(gpuService).getGpuGroupDetailsFromGpuDevicesOnHost(anyLong());
        runTransactionsInline();

        boolean result = gpuService.enableGpuDevice(cmd);

        assertTrue(result);
        verify(gpuDeviceDao).update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDisableGpuDevice_EmptyIds() {
        UnmanageGpuDeviceCmd cmd = mock(UnmanageGpuDeviceCmd.class);
        when(cmd.getIds()).thenReturn(new ArrayList<>());

        gpuService.disableGpuDevice(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDisableGpuDevice_DeviceInUse() {
        UnmanageGpuDeviceCmd cmd = mock(UnmanageGpuDeviceCmd.class);
        when(cmd.getIds()).thenReturn(List.of(GPU_DEVICE_ID_LONG));

        GpuDeviceVO device = mock(GpuDeviceVO.class);
        when(device.getVmId()).thenReturn(VM_ID);
        when(device.getManagedState()).thenReturn(GpuDevice.ManagedState.Managed);

        when(gpuDeviceDao.findById(GPU_DEVICE_ID_LONG)).thenReturn(device);

        gpuService.disableGpuDevice(cmd);
    }

    @Test
    public void testDeallocateAllGpuDevicesForVm_Success() {
        List<GpuDeviceVO> devices = List.of(mockGpuDevice);
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(devices);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenReturn(mockGpuDevice);

        gpuService.deallocateAllGpuDevicesForVm(VM_ID);

        verify(gpuDeviceDao).persist(any(GpuDeviceVO.class));
    }

    @Test
    public void testDeallocateAllGpuDevicesForVmOnHost_NoDevices() {
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(new ArrayList<>());

        gpuService.deallocateAllGpuDevicesForVm(VM_ID);

        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
    }

    @Test
    public void testDiscoverGpuDevices_Success() {
        DiscoverGpuDevicesCmd cmd = mock(DiscoverGpuDevicesCmd.class);
        when(cmd.getId()).thenReturn(HOST_ID);
        when(cmd.getCommandName()).thenReturn(COMMAND_DISCOVER_GPU_DEVICES);

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);
        when(resourceManager.getGPUStatistics(mockHost)).thenReturn(new HashMap<>());
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(List.of(mockGpuDevice));

        ListResponse<GpuDeviceResponse> result = gpuService.discoverGpuDevices(cmd);

        assertNotNull(result);
        verify(resourceManager).getGPUStatistics(mockHost);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDiscoverGpuDevices_HostNotFound() {
        DiscoverGpuDevicesCmd cmd = mock(DiscoverGpuDevicesCmd.class);
        when(cmd.getId()).thenReturn(INVALID_ID);

        when(hostDao.findById(INVALID_ID)).thenReturn(null);

        gpuService.discoverGpuDevices(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testDiscoverGpuDevices_HostNotUp() {
        DiscoverGpuDevicesCmd cmd = mock(DiscoverGpuDevicesCmd.class);
        when(cmd.getId()).thenReturn(HOST_ID);

        HostVO downHost = mock(HostVO.class);
        when(downHost.getStatus()).thenReturn(Status.Down);

        when(hostDao.findById(HOST_ID)).thenReturn(downHost);

        gpuService.discoverGpuDevices(cmd);
    }

    @Test
    public void testIsGPUDeviceAvailable_Sufficient() {
        VgpuProfile vgpuProfile = mock(VgpuProfile.class);
        when(vgpuProfile.getId()).thenReturn(VGPU_PROFILE_ID);

        GpuDeviceVO additionalDevice = gpu(HOST_ID, 2L, GPU_BUS_ADDRESS_2, null, GpuDevice.State.Free);
        additionalDevice.setVgpuProfileId(VGPU_PROFILE_ID);
        registerRule(HOST_ID, GpuService.GroupRule.any);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(List.of(mockGpuDevice, additionalDevice));

        boolean result = gpuService.isGPUDeviceAvailable(mockHost, VM_ID, vgpuProfile, 2);

        assertTrue(result);
    }

    @Test
    public void testIsGPUDeviceAvailable_Insufficient() {
        VgpuProfile vgpuProfile = mock(VgpuProfile.class);
        when(vgpuProfile.getId()).thenReturn(VGPU_PROFILE_ID);

        registerRule(HOST_ID, GpuService.GroupRule.any);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(List.of(mockGpuDevice));

        boolean result = gpuService.isGPUDeviceAvailable(mockHost, VM_ID, vgpuProfile, 3);

        assertFalse(result);
    }

    @Test
    public void testGetGpuGroupDetailsFromGpuDevicesOnHost_Success() {
        List<GpuDeviceVO> devices = List.of(mockGpuDevice);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(devices);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);

        HashMap<String, HashMap<String, VgpuTypesInfo>> result = gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(HOST_ID);

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    public void testAddGpuDevicesToHost_NewDevice() {
        VgpuTypesInfo deviceInfo = mock(VgpuTypesInfo.class);
        when(deviceInfo.getBusAddress()).thenReturn(GPU_BUS_ADDRESS_2);
        when(deviceInfo.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(deviceInfo.getVendorId()).thenReturn(GPU_VENDOR_ID);
        when(deviceInfo.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(deviceInfo.getVendorName()).thenReturn(GPU_VENDOR_NAME);
        when(deviceInfo.getModelName()).thenReturn(PASSTHROUGH_PROFILE);

        List<VgpuTypesInfo> newDevices = List.of(deviceInfo);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(new ArrayList<>());
        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(null);
        when(gpuCardDao.persist(any(GpuCardVO.class))).thenReturn(mockGpuCard);
        when(vgpuProfileDao.persist(any(VgpuProfileVO.class))).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenReturn(mockGpuDevice);

        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);

            runTransactionsInline();
            gpuService.addGpuDevicesToHost(mockHost, newDevices);

            verify(gpuCardDao).persist(any(GpuCardVO.class));
            verify(vgpuProfileDao, times(1)).persist(any(VgpuProfileVO.class)); // passthrough
            verify(gpuDeviceDao).persist(any(GpuDeviceVO.class));
        }
    }

    @Test
    public void testAddGpuDevicesToHost_ExistingDevice() {
        VgpuTypesInfo deviceInfo = mock(VgpuTypesInfo.class);
        when(deviceInfo.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);
        when(deviceInfo.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(deviceInfo.getVendorId()).thenReturn(GPU_VENDOR_ID);
        when(deviceInfo.getModelName()).thenReturn(PASSTHROUGH_PROFILE);

        List<VgpuTypesInfo> newDevices = List.of(deviceInfo);
        List<GpuDeviceVO> existingDevices = List.of(mockGpuDevice);

        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(existingDevices);
        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findByNameAndCardId(PASSTHROUGH_PROFILE, GPU_CARD_ID)).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class))).thenReturn(true);

        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);

            runTransactionsInline();
            gpuService.addGpuDevicesToHost(mockHost, newDevices);
            verify(gpuDeviceDao).update(eq(GPU_DEVICE_ID_LONG), any(GpuDeviceVO.class));
        }
    }

    @Test
    public void testAddGpuDevicesToHost_WithVmName() {
        VgpuTypesInfo deviceInfo = mock(VgpuTypesInfo.class);
        when(deviceInfo.getBusAddress()).thenReturn(GPU_BUS_ADDRESS_2);
        when(deviceInfo.getVmName()).thenReturn(VM_NAME);
        when(deviceInfo.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(deviceInfo.getVendorId()).thenReturn(GPU_VENDOR_ID);
        when(deviceInfo.getModelName()).thenReturn(PASSTHROUGH_PROFILE);

        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID_100);

        List<VgpuTypesInfo> newDevices = List.of(deviceInfo);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(new ArrayList<>());
        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findByNameAndCardId(PASSTHROUGH_PROFILE, GPU_CARD_ID)).thenReturn(mockVgpuProfile);
        when(vmInstanceDao.findVMByInstanceName(VM_NAME)).thenReturn(vm);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenReturn(mockGpuDevice);

        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);

            runTransactionsInline();
            gpuService.addGpuDevicesToHost(mockHost, newDevices);

            verify(vmInstanceDao).findVMByInstanceName(VM_NAME);
            verify(gpuDeviceDao).persist(any(GpuDeviceVO.class));
        }
    }

    @Test
    public void testGetGPUDevice_Success() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);

        VgpuProfile vgpuProfile = mock(VgpuProfile.class);
        when(vgpuProfile.getId()).thenReturn(VGPU_PROFILE_ID);
        when(vgpuProfile.getCardId()).thenReturn(GPU_CARD_ID);
        when(vgpuProfile.getName()).thenReturn(TEST_PROFILE_NAME);

        when(gpuDeviceDao.listByHostAndVm(HOST_ID, VM_ID)).thenReturn(List.of(mockGpuDevice));
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(List.of(mockGpuDevice));
        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(mockServiceOffering);

        GPUDeviceTO result = gpuService.getGPUDevice(vm, HOST_ID, vgpuProfile, 1);

        assertNotNull(result);
        assertEquals(List.of(GPU_BUS_ADDRESS), result.getGpuDevices().stream().map(VgpuTypesInfo::getBusAddress).collect(Collectors.toList()));
        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
        verify(gpuDeviceDao, never()).lockByHostId(anyLong());
    }

    @Test
    public void testGetGPUDevice_DescribesOnlyTheDevicesTheVmHolds() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        VgpuProfile vgpuProfile = mock(VgpuProfile.class);
        when(vgpuProfile.getCardId()).thenReturn(GPU_CARD_ID);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(mockServiceOffering);
        when(gpuDeviceDao.listByHostAndVm(HOST_ID, VM_ID)).thenReturn(new ArrayList<>());

        GPUDeviceTO result = gpuService.getGPUDevice(vm, HOST_ID, vgpuProfile, 2);

        assertTrue("a VM that holds no device on the host is given none", result.getGpuDevices().isEmpty());
        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
    }

    @Test
    public void testAllocateGpuDevicesToVmOnHost_Success() {
        VgpuTypesInfo deviceInfo = mock(VgpuTypesInfo.class);
        when(deviceInfo.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);

        List<VgpuTypesInfo> gpuDevices = List.of(deviceInfo);
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(new ArrayList<>());
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(mockGpuDevice);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenReturn(mockGpuDevice);

        try (MockedStatic<Transaction> transactionMock = Mockito.mockStatic(Transaction.class)) {
            transactionMock.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class))).thenAnswer(
                    invocation -> {
                        TransactionCallbackNoReturn callback = invocation.getArgument(0);
                        callback.doInTransactionWithoutResult(null);
                        return null;
                    });

            gpuService.allocateGpuDevicesToVmOnHost(VM_ID, HOST_ID, gpuDevices);

            verify(gpuDeviceDao).findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testAllocateGpuDevicesToVmOnHost_DeviceNotFound() {
        VgpuTypesInfo deviceInfo = mock(VgpuTypesInfo.class);
        when(deviceInfo.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);

        List<VgpuTypesInfo> gpuDevices = List.of(deviceInfo);
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(new ArrayList<>());
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(null);

        try (MockedStatic<Transaction> transactionMock = Mockito.mockStatic(Transaction.class)) {
            transactionMock.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class))).thenAnswer(
                    invocation -> {
                        TransactionCallbackNoReturn callback = invocation.getArgument(0);
                        callback.doInTransactionWithoutResult(null);
                        return null;
                    });

            gpuService.allocateGpuDevicesToVmOnHost(VM_ID, HOST_ID, gpuDevices);
        }
    }

    // Additional edge case tests
    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuCard_BlankVendorName() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(cmd.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(cmd.getName()).thenReturn(GPU_CARD_NAME);
        when(cmd.getVendorName()).thenReturn("");
        when(cmd.getVendorId()).thenReturn(GPU_VENDOR_ID);

        gpuService.createGpuCard(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuCard_BlankName() {
        CreateGpuCardCmd cmd = mock(CreateGpuCardCmd.class);
        when(cmd.getDeviceId()).thenReturn(GPU_DEVICE_ID);
        when(cmd.getDeviceName()).thenReturn(GPU_DEVICE_NAME);
        when(cmd.getName()).thenReturn("");
        when(cmd.getVendorName()).thenReturn(GPU_VENDOR_NAME);
        when(cmd.getVendorId()).thenReturn(GPU_VENDOR_ID);

        gpuService.createGpuCard(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuDevice_BlankBusAddress() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getBusAddress()).thenReturn("");

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);

        gpuService.createGpuDevice(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuDevice_InvalidVgpuProfile() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);
        when(cmd.getGpuCardId()).thenReturn(GPU_CARD_ID);
        when(cmd.getVgpuProfileId()).thenReturn(GPU_CARD_ID_2); // Different card ID

        VgpuProfileVO wrongProfile = mock(VgpuProfileVO.class);
        when(wrongProfile.getCardId()).thenReturn(DIFFERENT_CARD_ID); // Different card ID

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(null);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findById(GPU_CARD_ID_2)).thenReturn(wrongProfile);

        gpuService.createGpuDevice(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateGpuDevice_ParentDeviceOnDifferentHost() {
        CreateGpuDeviceCmd cmd = mock(CreateGpuDeviceCmd.class);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getBusAddress()).thenReturn(GPU_BUS_ADDRESS);
        when(cmd.getGpuCardId()).thenReturn(GPU_CARD_ID);
        when(cmd.getVgpuProfileId()).thenReturn(VGPU_PROFILE_ID);
        when(cmd.getParentGpuDeviceId()).thenReturn(PARENT_GPU_DEVICE_ID);

        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getHostId()).thenReturn(DIFFERENT_HOST_ID); // Different host

        when(hostDao.findById(HOST_ID)).thenReturn(mockHost);
        when(gpuDeviceDao.findByHostIdAndBusAddress(HOST_ID, GPU_BUS_ADDRESS)).thenReturn(null);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(gpuDeviceDao.findById(PARENT_GPU_DEVICE_ID)).thenReturn(parentDevice);

        gpuService.createGpuDevice(cmd);
    }

    @Test
    public void testIsGPUDeviceAvailable_WithExistingDevices() {
        VgpuProfile vgpuProfile = mock(VgpuProfile.class);
        when(vgpuProfile.getId()).thenReturn(VGPU_PROFILE_ID);

        GpuDeviceVO existingDevice = gpu(HOST_ID, 2L, GPU_BUS_ADDRESS_2, null, GpuDevice.State.Allocated);
        existingDevice.setVgpuProfileId(VGPU_PROFILE_ID);
        existingDevice.setVmId(VM_ID);
        registerRule(HOST_ID, GpuService.GroupRule.any);
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(List.of(mockGpuDevice, existingDevice));

        assertTrue("the VM's own GPUs count as free for it", gpuService.isGPUDeviceAvailable(mockHost, VM_ID, vgpuProfile, 2));
        assertFalse(gpuService.isGPUDeviceAvailable(mockHost, VM_ID_100, vgpuProfile, 2));
    }

    @Test
    public void testAddGpuDevicesToHost_DisableRemovedDevices() {
        // Setup existing device that won't be in the new list
        GpuDeviceVO existingDevice = mock(GpuDeviceVO.class);
        when(existingDevice.getId()).thenReturn(GPU_DEVICE_ID_2);
        when(existingDevice.getBusAddress()).thenReturn(GPU_BUS_ADDRESS_3);
        List<GpuDeviceVO> existingDevices = List.of(existingDevice);

        List<VgpuTypesInfo> newDevices = new ArrayList<>();

        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(existingDevices);
        when(gpuDeviceDao.update(eq(GPU_DEVICE_ID_2), any(GpuDeviceVO.class))).thenReturn(true);

        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);

            runTransactionsInline();
            gpuService.addGpuDevicesToHost(mockHost, newDevices);
            verify(gpuDeviceDao).update(eq(GPU_DEVICE_ID_2), any(GpuDeviceVO.class));
        }
    }

    @Test
    public void testUpdateVgpuProfile_SameName() {
        UpdateVgpuProfileCmd cmd = mock(UpdateVgpuProfileCmd.class);
        when(cmd.getId()).thenReturn(VGPU_PROFILE_ID);
        when(cmd.getProfileName()).thenReturn(VGPU_PROFILE_NAME); // Same as existing
        when(cmd.getDescription()).thenReturn(UPDATED_PROFILE_DESCRIPTION);
        when(cmd.getCommandName()).thenReturn(COMMAND_UPDATE_VGPU_PROFILE);

        when(vgpuProfileDao.findById(VGPU_PROFILE_ID)).thenReturn(mockVgpuProfile);
        when(vgpuProfileDao.update(eq(VGPU_PROFILE_ID), any(VgpuProfileVO.class))).thenReturn(true);
        when(gpuCardDao.findById(GPU_CARD_ID)).thenReturn(mockGpuCard);

        VgpuProfileResponse result = gpuService.updateVgpuProfile(cmd);

        assertNotNull(result);
        assertEquals(VGPU_PROFILE_UUID, result.getId());
        verify(vgpuProfileDao, never()).findByNameAndCardId(anyString(), anyLong());
    }

    // Tests for checkAndUpdateParentGpuDeviceState methods
    @Test
    public void testCheckAndUpdateParentGpuDeviceState_NullParentId() {
        // Should not throw exception and not call any DAO methods
        gpuService.checkAndUpdateParentGpuDeviceState((Long) null);

        verify(gpuDeviceDao, never()).findById(any());
        verify(gpuDeviceDao, never()).listByParentGpuDeviceId(any());
        verify(gpuDeviceDao, never()).update(anyLong(), any(GpuDeviceVO.class));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_ValidParentId() {
        Long parentDeviceId = PARENT_GPU_DEVICE_ID;
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(parentDeviceId);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        when(gpuDeviceDao.findById(parentDeviceId)).thenReturn(parentDevice);
        when(gpuDeviceDao.listByParentGpuDeviceId(parentDeviceId)).thenReturn(new ArrayList<>());

        gpuService.checkAndUpdateParentGpuDeviceState(parentDeviceId);

        verify(gpuDeviceDao).findById(parentDeviceId);
        verify(gpuDeviceDao).listByParentGpuDeviceId(parentDeviceId);
        // Should not update since no child devices and already Free
        verify(gpuDeviceDao, never()).update(eq(parentDeviceId), any(GpuDeviceVO.class));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_NullParentDevice() {
        // Should not throw exception and not call any DAO methods
        gpuService.checkAndUpdateParentGpuDeviceState((GpuDeviceVO) null);

        verify(gpuDeviceDao, never()).listByParentGpuDeviceId(any());
        verify(gpuDeviceDao, never()).update(anyLong(), any(GpuDeviceVO.class));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_NoChildDevices() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.PartiallyAllocated);

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(new ArrayList<>());
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.Free);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_AllFreeChildDevices() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.PartiallyAllocated);

        // Create child devices all in Free state
        List<GpuDeviceVO> childDevices = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            GpuDeviceVO child = mock(GpuDeviceVO.class);
            when(child.getState()).thenReturn(GpuDevice.State.Free);
            childDevices.add(child);
        }

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.Free);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_SomeAllocatedChildDevices() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        // Create child devices with mixed states - some Free, some Allocated
        List<GpuDeviceVO> childDevices = new ArrayList<>();

        GpuDeviceVO child1 = mock(GpuDeviceVO.class);
        when(child1.getState()).thenReturn(GpuDevice.State.Free);
        childDevices.add(child1);

        GpuDeviceVO child2 = mock(GpuDeviceVO.class);
        when(child2.getState()).thenReturn(GpuDevice.State.Allocated);
        childDevices.add(child2);

        GpuDeviceVO child3 = mock(GpuDeviceVO.class);
        when(child3.getState()).thenReturn(GpuDevice.State.Free);
        childDevices.add(child3);

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.PartiallyAllocated);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_ErrorChildDevices() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        // Create child devices with mixed states - Error should take priority
        List<GpuDeviceVO> childDevices = new ArrayList<>();

        GpuDeviceVO child1 = mock(GpuDeviceVO.class);
        when(child1.getState()).thenReturn(GpuDevice.State.Free);
        childDevices.add(child1);

        GpuDeviceVO child2 = mock(GpuDeviceVO.class);
        when(child2.getState()).thenReturn(GpuDevice.State.Allocated);
        childDevices.add(child2);

        GpuDeviceVO child3 = mock(GpuDeviceVO.class);
        when(child3.getState()).thenReturn(GpuDevice.State.Error);
        childDevices.add(child3);

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.Error);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_AllAllocatedChildDevices() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        // Create child devices all in Allocated state
        List<GpuDeviceVO> childDevices = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            GpuDeviceVO child = mock(GpuDeviceVO.class);
            when(child.getState()).thenReturn(GpuDevice.State.Allocated);
            childDevices.add(child);
        }

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.PartiallyAllocated);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_NoStateChange() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        // Create child devices all in Free state - should not change parent state
        List<GpuDeviceVO> childDevices = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            GpuDeviceVO child = mock(GpuDeviceVO.class);
            when(child.getState()).thenReturn(GpuDevice.State.Free);
            childDevices.add(child);
        }

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice, never()).setState(any());
        verify(gpuDeviceDao, never()).update(anyLong(), any(GpuDeviceVO.class));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_MultipleErrorStates() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.PartiallyAllocated);

        // Create child devices with multiple Error states
        List<GpuDeviceVO> childDevices = new ArrayList<>();

        GpuDeviceVO child1 = mock(GpuDeviceVO.class);
        when(child1.getState()).thenReturn(GpuDevice.State.Error);
        childDevices.add(child1);

        GpuDeviceVO child2 = mock(GpuDeviceVO.class);
        childDevices.add(child2);

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.Error);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    @Test
    public void testCheckAndUpdateParentGpuDeviceState_ErrorTakesPriority() {
        GpuDeviceVO parentDevice = mock(GpuDeviceVO.class);
        when(parentDevice.getId()).thenReturn(PARENT_GPU_DEVICE_ID);
        when(parentDevice.getState()).thenReturn(GpuDevice.State.Free);

        // Create child devices - should break on first Error state found
        List<GpuDeviceVO> childDevices = new ArrayList<>();

        GpuDeviceVO child1 = mock(GpuDeviceVO.class);
        when(child1.getState()).thenReturn(GpuDevice.State.Allocated);
        childDevices.add(child1);

        GpuDeviceVO child2 = mock(GpuDeviceVO.class);
        when(child2.getState()).thenReturn(GpuDevice.State.Error);
        childDevices.add(child2);

        // This child should not affect the final state since Error was found first
        GpuDeviceVO child3 = mock(GpuDeviceVO.class);
        childDevices.add(child3);

        when(gpuDeviceDao.listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID)).thenReturn(childDevices);
        when(gpuDeviceDao.update(eq(PARENT_GPU_DEVICE_ID), any(GpuDeviceVO.class))).thenReturn(true);

        gpuService.checkAndUpdateParentGpuDeviceState(parentDevice);

        verify(gpuDeviceDao).listByParentGpuDeviceId(PARENT_GPU_DEVICE_ID);
        verify(parentDevice).setState(GpuDevice.State.Error);
        verify(gpuDeviceDao).update(eq(PARENT_GPU_DEVICE_ID), eq(parentDevice));
    }

    private static final long GROUP_HOST_ID = 7L;
    private static final long OTHER_GROUP_HOST_ID = 8L;
    private static final long A100_PROFILE_ID = 11L;
    private static final long A100_CARD_ID = 12L;
    private static final long OTHER_CARD_ID = 13L;
    private static final long LOCAL_DISK_OFFERING_ID = 5L;

    @Mock
    private HostGpuGroupRuleDao hostGpuGroupRuleDao;

    @Mock
    private HostGpuGroupMemberDao hostGpuGroupMemberDao;

    @Mock
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Mock
    private StorageManager storageManager;

    private static String position(int index) {
        return String.format("0000:%02x:00.0", 0x10 + index);
    }

    /**
     * The address the agent reports for a position, as lspci prints devices in PCI domain 0.
     */
    private static String agentAddress(int index) {
        return String.format("%02x:00.0", 0x10 + index);
    }

    private static List<String> positions(int... indexes) {
        List<String> buses = new ArrayList<>();
        for (int index : indexes) {
            buses.add(position(index));
        }
        return buses;
    }

    private GpuDeviceVO gpu(long hostId, long id, String busAddress, String node, GpuDevice.State state) {
        GpuDeviceVO device = new GpuDeviceVO(A100_CARD_ID, A100_PROFILE_ID, busAddress, hostId, null, node, null);
        ReflectionTestUtils.setField(device, "id", id);
        device.setState(state);
        return device;
    }

    /**
     * GPUs at positions 0..n-1 of a host, positions 0-3 on NUMA node 0 and 4-7 on node 1; the given positions are
     * allocated.
     */
    private List<GpuDeviceVO> hostGpus(long hostId, int n, Integer... allocated) {
        List<Integer> taken = Arrays.asList(allocated);
        List<GpuDeviceVO> devices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GpuDeviceVO device = gpu(hostId, hostId * 100 + i, agentAddress(i), i < 4 ? "0" : "1",
                    taken.contains(i) ? GpuDevice.State.Allocated : GpuDevice.State.Free);
            if (taken.contains(i)) {
                device.setVmId(VM_ID_100);
            }
            devices.add(device);
        }
        Mockito.lenient().when(gpuDeviceDao.listByHostId(hostId)).thenReturn(devices);
        return devices;
    }

    private void registerRule(long hostId, GpuService.GroupRule rule) {
        Mockito.lenient().when(hostGpuGroupRuleDao.findByHostId(hostId)).thenReturn(new HostGpuGroupRuleVO(hostId, rule.name()));
    }

    private void registerPartitions(long hostId, List<List<String>> groups) {
        registerRule(hostId, GpuService.GroupRule.partitions);
        List<HostGpuGroupMemberVO> members = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            for (String bus : groups.get(i)) {
                members.add(new HostGpuGroupMemberVO(hostId, i, bus));
            }
        }
        Mockito.lenient().when(hostGpuGroupMemberDao.listByHostId(hostId)).thenReturn(members);
    }

    /**
     * The Fabric Manager partitions of an 8-GPU NVSwitch host: all eight, two halves, four pairs and eight singles.
     */
    private static List<List<String>> fabricManagerPartitions() {
        List<List<String>> groups = new ArrayList<>();
        groups.add(positions(0, 1, 2, 3, 4, 5, 6, 7));
        groups.add(positions(0, 1, 2, 3));
        groups.add(positions(4, 5, 6, 7));
        for (int i = 0; i < 8; i += 2) {
            groups.add(positions(i, i + 1));
        }
        for (int i = 0; i < 8; i++) {
            groups.add(positions(i));
        }
        return groups;
    }

    private HostVO eligibleHost(long hostId) {
        HostVO host = mock(HostVO.class);
        Mockito.lenient().when(host.getId()).thenReturn(hostId);
        Mockito.lenient().when(host.getType()).thenReturn(Host.Type.Routing);
        Mockito.lenient().when(host.getStatus()).thenReturn(Status.Up);
        Mockito.lenient().when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        Mockito.lenient().when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        Mockito.lenient().when(host.getDataCenterId()).thenReturn(1L);
        Mockito.lenient().when(host.getUuid()).thenReturn("host-" + hostId);
        Mockito.lenient().when(host.checkHostServiceOfferingTags(any())).thenReturn(true);
        Mockito.lenient().when(hostDao.findById(hostId)).thenReturn(host);
        return host;
    }

    private void registeredHosts(long... hostIds) {
        List<HostGpuGroupRuleVO> rules = new ArrayList<>();
        for (long hostId : hostIds) {
            rules.add(hostGpuGroupRuleDao.findByHostId(hostId));
        }
        Mockito.lenient().when(hostGpuGroupRuleDao.listAll()).thenReturn(rules);
    }

    private ServiceOffering gpuOffering(int gpuCount) {
        ServiceOffering offering = mock(ServiceOffering.class);
        Mockito.lenient().when(offering.getId()).thenReturn(30L + gpuCount);
        Mockito.lenient().when(offering.getVgpuProfileId()).thenReturn(A100_PROFILE_ID);
        Mockito.lenient().when(offering.getGpuCount()).thenReturn(gpuCount);
        return offering;
    }

    private GpuService.SlotCount slots(int gpuCount, Long hostId) {
        return gpuService.slotCount(gpuOffering(gpuCount), hostId);
    }

    private Optional<List<String>> pick(long hostId, int count) {
        return gpuService.pickGroup(hostId, A100_PROFILE_ID, count).map(GpuGroupPick::getPositions);
    }

    private void runTransactionsInline() {
        doAnswer(invocation -> ((TransactionCallbackWithException<?, ?>) invocation.getArgument(0)).doInTransaction(null))
                .when(gpuService).inTransaction(any());
    }

    private UpdateHostGpuGroupsCmd registration(long hostId, String json) {
        UpdateHostGpuGroupsCmd cmd = mock(UpdateHostGpuGroupsCmd.class);
        when(cmd.getHostId()).thenReturn(hostId);
        when(cmd.getGroups()).thenReturn(json);
        return cmd;
    }

    private static String partitionsJson(List<List<String>> groups) {
        return "{\"rule\":\"partitions\",\"groups\":[" + groups.stream()
                .map(group -> group.stream().map(bus -> "\"" + bus + "\"").collect(Collectors.joining(",", "[", "]")))
                .collect(Collectors.joining(",")) + "]}";
    }

    private void assertRefused(UpdateHostGpuGroupsCmd cmd, String expected) {
        try {
            gpuService.updateHostGpuGroups(cmd);
            Assert.fail("registration accepted: " + cmd.getGroups());
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
        verify(hostGpuGroupRuleDao, never()).persist(any());
        verify(hostGpuGroupMemberDao, never()).persist(any());
    }

    @Test
    public void groupsMustBeLaminar() {
        eligibleHost(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 4);
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(positions(0, 1), positions(1, 2)))), "laminar");
    }

    @Test
    public void groupsMustBeOneCard() {
        eligibleHost(GROUP_HOST_ID);
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 2);
        devices.get(1).setCardId(OTHER_CARD_ID);
        runTransactionsInline();
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(positions(0, 1)))), "more than one card");
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(positions(0, 1, 2, 3)))), "more than one card");
    }

    @Test
    public void groupsMayNameAPositionWithoutADevice() {
        eligibleHost(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 7);
        runTransactionsInline();

        HostGpuGroupsResponse response = gpuService.updateHostGpuGroups(registration(GROUP_HOST_ID, partitionsJson(fabricManagerPartitions())));

        assertEquals(15, response.getGroups().size());
        ArgumentCaptor<HostGpuGroupMemberVO> members = ArgumentCaptor.forClass(HostGpuGroupMemberVO.class);
        verify(hostGpuGroupMemberDao, times(8 + 2 * 4 + 4 * 2 + 8)).persist(members.capture());
        assertTrue("the position of the card under repair is registered",
                members.getAllValues().stream().anyMatch(member -> position(7).equals(member.getBusAddress())));
    }

    @Test
    public void shortAndFullBusAddressesAreOneForm() {
        assertEquals("0000:07:00.0", GpuServiceImpl.canonicalBusAddress("07:00.0"));
        assertEquals("0000:0b:00.1", GpuServiceImpl.canonicalBusAddress("0000:0b:00.1"));
        assertEquals("0000:0b:00.0", GpuServiceImpl.canonicalBusAddress("0:b:0.0"));
        assertEquals("0001:af:1f.7", GpuServiceImpl.canonicalBusAddress("1:af:1f.7"));
        for (String invalid : List.of("07:20.0", "07:00.8", "10000:07:00.0", "07:00", "zz:00.0", "")) {
            assertEquals(invalid, null, GpuServiceImpl.canonicalBusAddress(invalid));
        }
        assertEquals("0000:1a:00.0", GpuServiceImpl.normalizeBusAddress(" 1A:00.0 "));

        eligibleHost(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 2);
        runTransactionsInline();
        HostGpuGroupsResponse response = gpuService.updateHostGpuGroups(registration(GROUP_HOST_ID,
                "{\"rule\":\"partitions\",\"groups\":[[\"" + agentAddress(0) + "\",\"" + position(1).toUpperCase() + "\"]]}"));
        assertEquals(List.of(positions(0, 1)), response.getGroups());
        registerPartitions(GROUP_HOST_ID, response.getGroups());
        assertEquals("a group registered in either form matches device rows in the agent's short form", Optional.of(positions(0, 1)),
                pick(GROUP_HOST_ID, 2));
    }

    @Test
    public void updateHostGpuGroupsIsAnEvent() throws Exception {
        ActionEvent event = GpuServiceImpl.class.getMethod("updateHostGpuGroups", UpdateHostGpuGroupsCmd.class).getAnnotation(ActionEvent.class);
        assertNotNull(event);
        assertEquals(EventTypes.EVENT_HOST_GPU_GROUPS_UPDATE, event.eventType());
    }

    @Test
    public void groupsMustHaveOneTwoFourOrEightGpusAndOnlyForPartitions() {
        eligibleHost(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 4);
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(positions(0, 1, 2)))), "1, 2, 4 or 8");
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(positions(0, 1), positions(0, 1)))), "twice");
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(List.of(position(0), position(0))))), "twice");
        assertRefused(registration(GROUP_HOST_ID, "{\"rule\":\"any\",\"groups\":[[\"" + position(0) + "\"]]}"), "only for rule partitions");
        assertRefused(registration(GROUP_HOST_ID, "{\"rule\":\"partitions\"}"), "non-empty array of groups");
        assertRefused(registration(GROUP_HOST_ID, "{\"rule\":\"mesh\"}"), "the rule must be one of");
        assertRefused(registration(GROUP_HOST_ID, "[\"single\"]"), "JSON object");
        assertRefused(registration(GROUP_HOST_ID, "{\"rule\":"), "not valid JSON");
        assertRefused(registration(GROUP_HOST_ID, partitionsJson(List.of(List.of("07:20.0")))), "not a PCI bus address");
    }

    @Test
    public void registrationReplacesTheHostGroupsUnderTheDeviceLock() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        String a = "0000:1a:00.0";
        String b = "0000:1b:00.0";
        when(gpuDeviceDao.listByHostId(GROUP_HOST_ID)).thenReturn(List.of(gpu(GROUP_HOST_ID, 1L, a, "0", GpuDevice.State.Free),
                gpu(GROUP_HOST_ID, 2L, b, "0", GpuDevice.State.Allocated)));
        runTransactionsInline();

        HostGpuGroupsResponse response = gpuService.updateHostGpuGroups(registration(GROUP_HOST_ID,
                "{\"rule\":\"partitions\",\"groups\":[[\"" + b + "\"],[\"0000:1B:00.0\",\" " + a + "\"],[\"" + a + "\"]],\"note\":1}"));

        InOrder inOrder = Mockito.inOrder(gpuDeviceDao, hostGpuGroupMemberDao, hostGpuGroupRuleDao);
        inOrder.verify(gpuDeviceDao).lockByHostId(GROUP_HOST_ID);
        inOrder.verify(gpuDeviceDao).listByHostId(GROUP_HOST_ID);
        inOrder.verify(hostGpuGroupMemberDao).removeByHostId(GROUP_HOST_ID);
        ArgumentCaptor<HostGpuGroupRuleVO> rule = ArgumentCaptor.forClass(HostGpuGroupRuleVO.class);
        inOrder.verify(hostGpuGroupRuleDao).persist(rule.capture());
        assertEquals("partitions", rule.getValue().getRule());
        ArgumentCaptor<HostGpuGroupMemberVO> members = ArgumentCaptor.forClass(HostGpuGroupMemberVO.class);
        verify(hostGpuGroupMemberDao, times(4)).persist(members.capture());
        assertEquals("larger groups first, bus addresses in lower case and ascending", List.of("0:" + a, "0:" + b, "1:" + a, "2:" + b),
                members.getAllValues().stream().map(m -> m.getGroupIndex() + ":" + m.getBusAddress()).collect(Collectors.toList()));
        assertEquals(host.getUuid(), response.getHostId());
        assertEquals("partitions", response.getRule());
        assertEquals(List.of(List.of(a, b), List.of(a), List.of(b)), response.getGroups());

        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        response = gpuService.updateHostGpuGroups(registration(GROUP_HOST_ID, "{\"rule\":\"single\"}"));
        assertEquals("single", response.getRule());
        assertEquals(null, response.getGroups());
        verify(hostGpuGroupRuleDao).update(eq(0L), any(HostGpuGroupRuleVO.class));
        verify(hostGpuGroupMemberDao, times(2)).removeByHostId(GROUP_HOST_ID);
        verify(hostGpuGroupMemberDao, times(4)).persist(any());
    }

    @Test
    public void onlyKvmRoutingHostsTakeGpuGroups() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.XenServer);
        assertRefused(registration(GROUP_HOST_ID, "{\"rule\":\"single\"}"), "only KVM hosts");
    }

    @Test
    public void listHostGpuGroupsReturnsTheRegistration() {
        eligibleHost(GROUP_HOST_ID);
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(0), positions(1)));
        registeredHosts(GROUP_HOST_ID);
        ListHostGpuGroupsCmd cmd = mock(ListHostGpuGroupsCmd.class);
        when(cmd.getHostId()).thenReturn(null);

        ListResponse<HostGpuGroupsResponse> response = gpuService.listHostGpuGroups(cmd);

        assertEquals(1, response.getCount().intValue());
        HostGpuGroupsResponse groups = response.getResponses().get(0);
        assertEquals("host-" + GROUP_HOST_ID, groups.getHostId());
        assertEquals(List.of(positions(0, 1), positions(0), positions(1)), groups.getGroups());
        assertEquals(List.of(Set.copyOf(positions(0, 1)), Set.copyOf(positions(0)), Set.copyOf(positions(1))),
                gpuService.getGpuGroups(GROUP_HOST_ID));

        when(cmd.getHostId()).thenReturn(OTHER_GROUP_HOST_ID);
        eligibleHost(OTHER_GROUP_HOST_ID);
        assertEquals("a host without a registration lists nothing", 0, gpuService.listHostGpuGroups(cmd).getCount().intValue());
    }

    @Test
    public void pickGroupTakesOnlyWholeFreeGroup() {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 4, 1);

        Optional<GpuGroupPick> pick = gpuService.pickGroup(GROUP_HOST_ID, A100_PROFILE_ID, 2);

        assertTrue(pick.isPresent());
        assertEquals(GROUP_HOST_ID, pick.get().getHostId());
        assertEquals(positions(2, 3), pick.get().getPositions());
        assertEquals(List.of(devices.get(2).getId(), devices.get(3).getId()), pick.get().getDeviceIds());
    }

    @Test
    public void pickGroupBestFitPrefersFragmentedHalf() {
        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        hostGpus(GROUP_HOST_ID, 8, 2, 3);

        assertEquals("the pair left in the half that already has a pair taken", Optional.of(positions(0, 1)), pick(GROUP_HOST_ID, 2));

        hostGpus(GROUP_HOST_ID, 8, 1);
        assertEquals("the single whose pair is already half taken", Optional.of(positions(0)), pick(GROUP_HOST_ID, 1));
        assertEquals("the pair in the half with a GPU taken", Optional.of(positions(2, 3)), pick(GROUP_HOST_ID, 2));
        assertEquals(Optional.of(positions(4, 5, 6, 7)), pick(GROUP_HOST_ID, 4));
        assertEquals(Optional.empty(), pick(GROUP_HOST_ID, 8));
    }

    @Test
    public void pickGroupEmptyWhenOnlyScatteredFree() {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        hostGpus(GROUP_HOST_ID, 4, 1, 3);
        VgpuProfile profile = mock(VgpuProfile.class);
        when(profile.getId()).thenReturn(A100_PROFILE_ID);

        assertEquals(Optional.empty(), pick(GROUP_HOST_ID, 2));
        assertFalse(gpuService.isGPUDeviceAvailable(eligibleHost(GROUP_HOST_ID), VM_ID, profile, 2));
        assertEquals(Integer.MAX_VALUE, gpuService.bestFitScore(GROUP_HOST_ID, A100_PROFILE_ID, 2));
    }

    @Test
    public void slotCountExcludesGroupWithMissingDevice() {
        eligibleHost(GROUP_HOST_ID);
        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        registeredHosts(GROUP_HOST_ID);
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 8);
        devices.get(7).setState(GpuDevice.State.Error);

        assertEquals(new GpuService.SlotCount(7, 7), slots(1, null));
        assertEquals(new GpuService.SlotCount(3, 3), slots(2, null));
        assertEquals(new GpuService.SlotCount(1, 1), slots(4, null));
        assertEquals(new GpuService.SlotCount(0, 0), slots(8, null));
    }

    @Test
    public void groupWithAnUnseenCardIsUsableOnceTheSyncAddsIt() {
        eligibleHost(GROUP_HOST_ID);
        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        registeredHosts(GROUP_HOST_ID);
        List<GpuDeviceVO> devices = new ArrayList<>(hostGpus(GROUP_HOST_ID, 7));
        when(gpuDeviceDao.listByHostId(GROUP_HOST_ID)).thenReturn(devices);
        assertEquals(new GpuService.SlotCount(7, 7), slots(1, null));
        assertEquals(new GpuService.SlotCount(3, 3), slots(2, null));
        assertEquals(new GpuService.SlotCount(1, 1), slots(4, null));
        assertEquals(new GpuService.SlotCount(0, 0), slots(8, null));
        assertEquals(Optional.empty(), pick(GROUP_HOST_ID, 8));

        List<VgpuTypesInfo> reported = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            VgpuTypesInfo info = mock(VgpuTypesInfo.class);
            when(info.getBusAddress()).thenReturn(agentAddress(i));
            when(info.getDeviceId()).thenReturn(GPU_DEVICE_ID);
            when(info.getVendorId()).thenReturn(GPU_VENDOR_ID);
            when(info.getModelName()).thenReturn(PASSTHROUGH_PROFILE);
            when(info.isPassthroughEnabled()).thenReturn(true);
            when(info.getDeviceType()).thenReturn(GpuDevice.DeviceType.PCI);
            reported.add(info);
        }
        GpuCardVO a100 = mock(GpuCardVO.class);
        when(a100.getId()).thenReturn(A100_CARD_ID);
        when(a100.getUuid()).thenReturn("a100");
        VgpuProfileVO passthrough = mock(VgpuProfileVO.class);
        when(passthrough.getId()).thenReturn(A100_PROFILE_ID);
        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(a100);
        when(vgpuProfileDao.findByNameAndCardId(PASSTHROUGH_PROFILE, A100_CARD_ID)).thenReturn(passthrough);
        when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenAnswer(invocation -> {
            GpuDeviceVO row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", GROUP_HOST_ID * 100 + 7);
            devices.add(row);
            return row;
        });
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(GROUP_HOST_ID);
        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + GROUP_HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);
            runTransactionsInline();

            gpuService.addGpuDevicesToHost(host, reported, new Date());
        }

        assertEquals(agentAddress(7), devices.get(7).getBusAddress());
        assertEquals(new GpuService.SlotCount(1, 1), slots(8, null));
        assertEquals(new GpuService.SlotCount(4, 4), slots(2, null));
        assertEquals(Optional.of(positions(0, 1, 2, 3, 4, 5, 6, 7)), pick(GROUP_HOST_ID, 8));
    }

    @Test
    public void slotCountReturnsWhenDeviceRediscovered() {
        eligibleHost(GROUP_HOST_ID);
        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        registeredHosts(GROUP_HOST_ID);
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 8, 0);
        devices.get(7).setState(GpuDevice.State.Error);
        assertEquals(new GpuService.SlotCount(0, 0), slots(8, null));
        assertEquals(new GpuService.SlotCount(2, 3), slots(2, null));

        devices.get(7).setState(GpuDevice.State.Free);

        assertEquals(new GpuService.SlotCount(0, 1), slots(8, null));
        assertEquals(new GpuService.SlotCount(3, 4), slots(2, null));
        assertEquals(new GpuService.SlotCount(7, 8), slots(1, null));
    }

    @Test
    public void slotNotUsableWhenLocalPoolClosed() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        registeredHosts(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 2);
        ServiceOffering offering = gpuOffering(1);
        when(offering.getDiskOfferingId()).thenReturn(LOCAL_DISK_OFFERING_ID);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(diskOffering.isUseLocalStorage()).thenReturn(true);
        when(diskOfferingDao.findById(LOCAL_DISK_OFFERING_ID)).thenReturn(diskOffering);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(primaryDataStoreDao.findLocalStoragePoolsByHostAndTags(host.getId(), null)).thenReturn(List.of(pool));

        when(storageManager.storagePoolCrossedDisableThreshold(pool)).thenReturn(true);
        assertEquals(new GpuService.SlotCount(0, 2), gpuService.slotCount(offering, null));
        assertEquals(new GpuService.SlotCount(0, 2), gpuService.slotCount(offering, GROUP_HOST_ID));

        when(storageManager.storagePoolCrossedDisableThreshold(pool)).thenReturn(false);
        assertEquals(new GpuService.SlotCount(2, 2), gpuService.slotCount(offering, null));

        when(primaryDataStoreDao.findLocalStoragePoolsByHostAndTags(host.getId(), null)).thenReturn(List.of());
        assertEquals("no local pool for a local-storage offering", new GpuService.SlotCount(0, 2), gpuService.slotCount(offering, null));
    }

    @Test
    public void slotCountForOneHostCountsOnlyThatHost() {
        eligibleHost(GROUP_HOST_ID);
        eligibleHost(OTHER_GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        registerRule(OTHER_GROUP_HOST_ID, GpuService.GroupRule.single);
        registeredHosts(GROUP_HOST_ID, OTHER_GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 2, 1);
        hostGpus(OTHER_GROUP_HOST_ID, 3);

        assertEquals(new GpuService.SlotCount(1, 2), slots(1, GROUP_HOST_ID));
        assertEquals(new GpuService.SlotCount(3, 3), slots(1, OTHER_GROUP_HOST_ID));
        assertEquals(new GpuService.SlotCount(4, 5), slots(1, null));
    }

    @Test
    public void slotCountLeavesOutHostsThatCannotStartTheOffering() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        registeredHosts(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 2);
        assertEquals(new GpuService.SlotCount(2, 2), slots(1, null));

        when(host.getResourceState()).thenReturn(ResourceState.Maintenance);
        assertEquals(new GpuService.SlotCount(0, 0), slots(1, null));
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);

        when(host.getStatus()).thenReturn(Status.Disconnected);
        assertEquals(new GpuService.SlotCount(0, 0), slots(1, null));
        when(host.getStatus()).thenReturn(Status.Up);

        when(host.checkHostServiceOfferingTags(any())).thenReturn(false);
        assertEquals("a host without the offering's tags", new GpuService.SlotCount(0, 0), slots(1, null));
        Mockito.lenient().when(host.checkHostServiceOfferingTags(any())).thenReturn(true);

        when(serviceOfferingDetailsDao.findZoneIds(31L)).thenReturn(List.of(2L));
        assertEquals("a host outside the offering's zones", new GpuService.SlotCount(0, 0), slots(1, null));
    }

    @Test
    public void hostWithoutGroupsOffersNoGpu() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 8);
        VgpuProfile profile = mock(VgpuProfile.class);
        when(profile.getId()).thenReturn(A100_PROFILE_ID);

        assertFalse(gpuService.isGPUDeviceAvailable(host, VM_ID, profile, 1));
        assertEquals(Optional.empty(), pick(GROUP_HOST_ID, 1));
        assertEquals(Integer.MAX_VALUE, gpuService.bestFitScore(GROUP_HOST_ID, A100_PROFILE_ID, 1));
        assertEquals(new GpuService.SlotCount(0, 0), slots(1, GROUP_HOST_ID));
        assertEquals(new GpuService.SlotCount(0, 0), slots(1, null));
        assertTrue(gpuService.getGpuGroups(GROUP_HOST_ID).isEmpty());

        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getUuid()).thenReturn("vm-uuid");
        runTransactionsInline();
        try {
            gpuService.allocateGpuGroup(vm, GROUP_HOST_ID, A100_PROFILE_ID, 1);
            Assert.fail("a host without groups gave a GPU");
        } catch (InsufficientServerCapacityException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no whole free group of 1 GPUs"));
            assertEquals(GROUP_HOST_ID, e.getId());
        }
        verify(gpuDeviceDao, never()).persist(any());
    }

    @Test
    public void anyRulePicksSameNodeFirst() {
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.any);
        List<GpuDeviceVO> devices = new ArrayList<>();
        devices.add(gpu(GROUP_HOST_ID, 1L, agentAddress(0), "0", GpuDevice.State.Free));
        devices.add(gpu(GROUP_HOST_ID, 2L, agentAddress(2), "1", GpuDevice.State.Free));
        devices.add(gpu(GROUP_HOST_ID, 3L, agentAddress(3), "1", GpuDevice.State.Free));
        when(gpuDeviceDao.listByHostId(GROUP_HOST_ID)).thenReturn(devices);

        assertEquals(Optional.of(positions(2, 3)), pick(GROUP_HOST_ID, 2));
    }

    @Test
    public void anyRuleSlotCountIsFloorFreeOverN() {
        eligibleHost(GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.any);
        registeredHosts(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 4, 3);

        assertEquals(new GpuService.SlotCount(1, 2), slots(2, null));
        assertEquals(new GpuService.SlotCount(3, 4), slots(1, null));
        assertEquals(new GpuService.SlotCount(0, 1), slots(4, null));
    }

    @Test
    public void singleRuleRefusesMultiGpu() {
        HostVO host = eligibleHost(GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        registeredHosts(GROUP_HOST_ID);
        hostGpus(GROUP_HOST_ID, 2);
        VgpuProfile profile = mock(VgpuProfile.class);
        when(profile.getId()).thenReturn(A100_PROFILE_ID);

        assertFalse(gpuService.isGPUDeviceAvailable(host, VM_ID, profile, 2));
        assertEquals(Optional.empty(), pick(GROUP_HOST_ID, 2));
        assertEquals(new GpuService.SlotCount(0, 0), slots(2, null));

        assertTrue(gpuService.isGPUDeviceAvailable(host, VM_ID, profile, 1));
        assertEquals(Optional.of(positions(0)), pick(GROUP_HOST_ID, 1));
        assertEquals(new GpuService.SlotCount(2, 2), slots(1, null));
    }

    @Test
    public void multiNodeAnyHostPicksOnlyEvenSpreads() {
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.any);
        hostGpus(GROUP_HOST_ID, 8, 3, 5, 6, 7);
        assertEquals("three free on node 0 and one on node 1 hold no even spread of four", Optional.empty(), pick(GROUP_HOST_ID, 4));

        hostGpus(GROUP_HOST_ID, 8, 2, 3, 6, 7);
        assertEquals(Optional.of(positions(0, 1, 4, 5)), pick(GROUP_HOST_ID, 4));

        hostGpus(GROUP_HOST_ID, 8, 6, 7);
        assertEquals("a set on one node before a spread one", Optional.of(positions(0, 1, 2, 3)), pick(GROUP_HOST_ID, 4));
        assertEquals("the node left with fewer free GPUs", Optional.of(positions(4, 5)), pick(GROUP_HOST_ID, 2));
    }

    @Test
    public void slotCountCountsOnlyEvenSpreadSubsetsOnMultiNodeAnyHost() {
        eligibleHost(GROUP_HOST_ID);
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.any);
        registeredHosts(GROUP_HOST_ID);

        hostGpus(GROUP_HOST_ID, 8, 3, 5, 6, 7);
        assertEquals(new GpuService.SlotCount(0, 2), slots(4, null));

        hostGpus(GROUP_HOST_ID, 8, 2, 3, 6, 7);
        assertEquals(new GpuService.SlotCount(1, 2), slots(4, null));

        hostGpus(GROUP_HOST_ID, 8, 0, 1, 2);
        assertEquals("one free on node 0 and four on node 1", new GpuService.SlotCount(1, 2), slots(4, null));
        assertEquals(new GpuService.SlotCount(0, 1), slots(8, null));
    }

    @Test
    public void bestFitScorePrefersTheHostLeftWithFewestFreeGpus() {
        registerRule(GROUP_HOST_ID, GpuService.GroupRule.single);
        hostGpus(GROUP_HOST_ID, 2, 1);
        registerPartitions(OTHER_GROUP_HOST_ID, fabricManagerPartitions());
        hostGpus(OTHER_GROUP_HOST_ID, 8);

        int oneFree = gpuService.bestFitScore(GROUP_HOST_ID, A100_PROFILE_ID, 1);
        int emptyEight = gpuService.bestFitScore(OTHER_GROUP_HOST_ID, A100_PROFILE_ID, 1);

        assertEquals(0, oneFree);
        assertEquals(7 * 1024 + 1, emptyEight);
        assertTrue(oneFree < emptyEight);
        assertEquals(4 * 1024 + 4, gpuService.bestFitScore(OTHER_GROUP_HOST_ID, A100_PROFILE_ID, 4));
    }

    @Test
    public void gpuAllocationAlgorithmIsASetting() {
        ConfigKey<String> key = GpuService.GpuAllocationAlgorithm;
        assertEquals("gpu.allocation.algorithm", key.key());
        assertEquals("bestfit", key.defaultValue());
        assertEquals(ConfigKey.Kind.Select, key.kind());
        assertEquals("bestfit,firstfit,random", key.options());
        assertTrue(key.isDynamic());
        assertTrue(Arrays.asList(gpuService.getConfigKeys()).contains(key));
        for (String value : List.of("bestfit", "firstfit", "random")) {
            ((ValidatedConfigKey<String>) key).validateValue(value);
        }
        try {
            ((ValidatedConfigKey<String>) key).validateValue("worstfit");
            Assert.fail("an unknown algorithm was accepted");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("worstfit"));
        }

        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        hostGpus(GROUP_HOST_ID, 8, 4);
        assertEquals(GpuService.AllocationAlgorithm.bestfit, gpuService.getAllocationAlgorithm());
        assertEquals(Optional.of(positions(6, 7)), pick(GROUP_HOST_ID, 2));

        doReturn(GpuService.AllocationAlgorithm.firstfit).when(gpuService).getAllocationAlgorithm();
        assertEquals(Optional.of(positions(0, 1)), pick(GROUP_HOST_ID, 2));

        doReturn(GpuService.AllocationAlgorithm.random).when(gpuService).getAllocationAlgorithm();
        Set<List<String>> usable = Set.of(positions(0, 1), positions(2, 3), positions(6, 7));
        Set<List<String>> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            List<String> picked = pick(GROUP_HOST_ID, 2).orElseThrow();
            assertTrue("random picked " + picked, usable.contains(picked));
            seen.add(picked);
        }
        assertTrue("random picks vary", seen.size() > 1);
        assertEquals("random never makes a group usable", Optional.empty(), pick(GROUP_HOST_ID, 8));
    }

    @Test
    public void syncDoesNotFreeStartingVmDevices() {
        Date reportTime = new Date(1_000_000L);
        Date before = new Date(reportTime.getTime() - 2000);
        Date after = new Date(reportTime.getTime() + 1000);
        Date sameSecond = new Date(reportTime.getTime() - 500);
        String[] buses = {GPU_BUS_ADDRESS, GPU_BUS_ADDRESS_2, GPU_BUS_ADDRESS_3, "0000:04:00.0", "0000:05:00.0"};
        Object[][] vms = {
            {VirtualMachine.State.Starting, HOST_ID, before},
            {VirtualMachine.State.Running, HOST_ID, before},
            {VirtualMachine.State.Running, HOST_ID, after},
            {VirtualMachine.State.Running, DIFFERENT_HOST_ID, after},
            {VirtualMachine.State.Running, HOST_ID, sameSecond},
        };
        List<GpuDeviceVO> rows = new ArrayList<>();
        List<VgpuTypesInfo> reported = new ArrayList<>();
        for (int i = 0; i < buses.length; i++) {
            GpuDeviceVO row = gpu(HOST_ID, 21L + i, buses[i], null, GpuDevice.State.Allocated);
            row.setVmId(VM_ID_100 + i);
            rows.add(row);
            VMInstanceVO vm = mock(VMInstanceVO.class);
            when(vm.getState()).thenReturn((VirtualMachine.State) vms[i][0]);
            when(vm.getHostId()).thenReturn((Long) vms[i][1]);
            Mockito.lenient().when(vm.getUpdateTime()).thenReturn((Date) vms[i][2]);
            when(vmInstanceDao.findById(VM_ID_100 + i)).thenReturn(vm);
            VgpuTypesInfo info = mock(VgpuTypesInfo.class);
            when(info.getBusAddress()).thenReturn(buses[i]);
            when(info.getDeviceId()).thenReturn(GPU_DEVICE_ID);
            when(info.getVendorId()).thenReturn(GPU_VENDOR_ID);
            when(info.getModelName()).thenReturn(PASSTHROUGH_PROFILE);
            reported.add(info);
        }
        when(gpuDeviceDao.listByHostId(HOST_ID)).thenReturn(rows);
        when(gpuCardDao.findByVendorIdAndDeviceId(GPU_VENDOR_ID, GPU_DEVICE_ID)).thenReturn(mockGpuCard);
        when(vgpuProfileDao.findByNameAndCardId(PASSTHROUGH_PROFILE, GPU_CARD_ID)).thenReturn(mockVgpuProfile);

        try (MockedStatic<GlobalLock> ignored = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock lock = mock(GlobalLock.class);
            when(GlobalLock.getInternLock("add-gpu-devices-to-host-" + HOST_ID)).thenReturn(lock);
            when(lock.lock(30)).thenReturn(true);
            runTransactionsInline();

            gpuService.addGpuDevicesToHost(mockHost, reported, reportTime);
        }

        assertEquals("a starting VM has its devices before its domain exists", GpuDevice.State.Allocated, rows.get(0).getState());
        assertEquals(Long.valueOf(VM_ID_100), rows.get(0).getVmId());
        assertEquals("for a running VM the host's report is authoritative", GpuDevice.State.Free, rows.get(1).getState());
        assertNull(rows.get(1).getVmId());
        assertEquals("a report gathered before the VM's last state change does not judge it", GpuDevice.State.Allocated, rows.get(2).getState());
        assertEquals(Long.valueOf(VM_ID_100 + 2), rows.get(2).getVmId());
        assertEquals("a VM running on another host does not hold this host's device", GpuDevice.State.Free, rows.get(3).getState());
        assertNull(rows.get(3).getVmId());
        assertEquals("the stored update time keeps whole seconds, so a change in the report's second counts as after it",
                GpuDevice.State.Allocated, rows.get(4).getState());
        InOrder inOrder = Mockito.inOrder(gpuDeviceDao);
        inOrder.verify(gpuDeviceDao).lockByHostId(HOST_ID);
        inOrder.verify(gpuDeviceDao).listByHostId(HOST_ID);
    }

    private VirtualMachine startingVm(long vmId, long hostId) {
        VirtualMachine vm = mock(VirtualMachine.class);
        Mockito.lenient().when(vm.getId()).thenReturn(vmId);
        Mockito.lenient().when(vm.getHostId()).thenReturn(hostId);
        Mockito.lenient().when(vm.getUuid()).thenReturn("vm-" + vmId);
        Mockito.lenient().when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        return vm;
    }

    private VgpuProfile a100Profile() {
        VgpuProfile profile = mock(VgpuProfile.class);
        Mockito.lenient().when(profile.getId()).thenReturn(A100_PROFILE_ID);
        Mockito.lenient().when(profile.getCardId()).thenReturn(A100_CARD_ID);
        Mockito.lenient().when(profile.getName()).thenReturn(PASSTHROUGH_PROFILE);
        Mockito.lenient().when(gpuCardDao.findById(A100_CARD_ID)).thenReturn(mockGpuCard);
        Mockito.lenient().when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(mockServiceOffering);
        Mockito.lenient().doReturn(new HashMap<>()).when(gpuService).getGpuGroupDetailsFromGpuDevicesOnHost(Mockito.anyLong());
        return profile;
    }

    @Test
    public void concurrentAllocationLocksHostDevicesFirst() throws Exception {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 4, 0);
        runTransactionsInline();

        GpuGroupPick pick = gpuService.allocateGpuGroup(startingVm(VM_ID, GROUP_HOST_ID), GROUP_HOST_ID, A100_PROFILE_ID, 2);

        assertEquals(positions(2, 3), pick.getPositions());
        InOrder inOrder = Mockito.inOrder(gpuService, gpuDeviceDao);
        inOrder.verify(gpuDeviceDao).listByVmId(VM_ID);
        inOrder.verify(gpuService).inTransaction(any());
        inOrder.verify(gpuDeviceDao).lockByHostId(GROUP_HOST_ID);
        inOrder.verify(gpuDeviceDao).listByHostAndVm(GROUP_HOST_ID, VM_ID);
        inOrder.verify(gpuDeviceDao).listByHostId(GROUP_HOST_ID);
        inOrder.verify(gpuDeviceDao, times(2)).persist(any(GpuDeviceVO.class));
        assertEquals(GpuDevice.State.Allocated, devices.get(2).getState());
        assertEquals(Long.valueOf(VM_ID), devices.get(3).getVmId());
    }

    @Test
    public void startWithoutAUsableGroupGetsAHostScopedCapacityException() {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        hostGpus(GROUP_HOST_ID, 4, 0, 2);
        runTransactionsInline();

        try {
            gpuService.allocateGpuGroup(startingVm(VM_ID, GROUP_HOST_ID), GROUP_HOST_ID, A100_PROFILE_ID, 2);
            Assert.fail("a start without a usable group got one");
        } catch (InsufficientServerCapacityException e) {
            assertEquals(Host.class, e.getScope());
            assertEquals(GROUP_HOST_ID, e.getId());
        }
        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
    }

    @Test
    public void rebootKeepsTheGroupTheVmHolds() {
        registerPartitions(GROUP_HOST_ID, fabricManagerPartitions());
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 8, 0, 1, 4, 5);
        devices.get(4).setVmId(VM_ID_100 + 1);
        devices.get(5).setVmId(VM_ID_100 + 1);
        when(gpuDeviceDao.listByHostAndVm(GROUP_HOST_ID, VM_ID_100)).thenReturn(List.of(devices.get(1), devices.get(0)));
        VgpuProfile profile = a100Profile();
        assertEquals("a fresh pick would take another pair", Optional.of(positions(2, 3)), pick(GROUP_HOST_ID, 2));

        GPUDeviceTO to = gpuService.getGPUDevice(startingVm(VM_ID_100, GROUP_HOST_ID), GROUP_HOST_ID, profile, 2);

        assertEquals(List.of(agentAddress(0), agentAddress(1)), to.getGpuDevices().stream().map(VgpuTypesInfo::getBusAddress).collect(Collectors.toList()));
        verify(gpuDeviceDao, never()).lockByHostId(anyLong());
        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
        verify(gpuDeviceDao, never()).listByVmId(anyLong());
        assertEquals(Long.valueOf(VM_ID_100), devices.get(0).getVmId());
        assertEquals(GpuDevice.State.Free, devices.get(6).getState());
    }

    @Test
    public void managedStateChangeRechecksTheDeviceUnderItsHostLock() {
        GpuDeviceVO free = gpu(HOST_ID, 31L, GPU_BUS_ADDRESS, null, GpuDevice.State.Free);
        GpuDeviceVO takenMeanwhile = gpu(HOST_ID, 31L, GPU_BUS_ADDRESS, null, GpuDevice.State.Allocated);
        takenMeanwhile.setVmId(VM_ID);
        when(gpuDeviceDao.findById(31L)).thenReturn(free, takenMeanwhile);
        UnmanageGpuDeviceCmd cmd = mock(UnmanageGpuDeviceCmd.class);
        when(cmd.getIds()).thenReturn(List.of(31L));
        runTransactionsInline();

        try {
            gpuService.disableGpuDevice(cmd);
            Assert.fail("a device allocated meanwhile was unmanaged");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("in use"));
        }
        InOrder inOrder = Mockito.inOrder(gpuDeviceDao);
        inOrder.verify(gpuDeviceDao).findById(31L);
        inOrder.verify(gpuDeviceDao).lockByHostId(HOST_ID);
        inOrder.verify(gpuDeviceDao).findById(31L);
        verify(gpuDeviceDao, never()).update(anyLong(), any(GpuDeviceVO.class));

        DeleteGpuDeviceCmd delete = mock(DeleteGpuDeviceCmd.class);
        when(delete.getIds()).thenReturn(List.of(31L));
        when(gpuDeviceDao.listByIds(List.of(31L))).thenReturn(List.of(free), List.of(takenMeanwhile));
        try {
            gpuService.deleteGpuDevices(delete);
            Assert.fail("a device allocated meanwhile was deleted");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("allocated"));
        }
        verify(gpuDeviceDao, times(2)).lockByHostId(HOST_ID);
        verify(gpuDeviceDao, never()).remove(anyLong());
    }

    /**
     * A table of GPU device rows shared by threads, with a per-host row lock that is held until the enclosing
     * transaction ends, as SELECT ... FOR UPDATE does.
     */
    private final class DeviceTable {
        private final Map<Long, GpuDeviceVO> rows = new ConcurrentHashMap<>();
        private final ReentrantLock hostLock = new ReentrantLock(true);
        private final ThreadLocal<Integer> held = ThreadLocal.withInitial(() -> 0);

        DeviceTable(boolean locking, CyclicBarrier readBarrier) {
            Mockito.lenient().when(gpuDeviceDao.lockByHostId(GROUP_HOST_ID)).thenAnswer(invocation -> {
                if (locking) {
                    hostLock.lock();
                    held.set(held.get() + 1);
                }
                return null;
            });
            Mockito.lenient().when(gpuDeviceDao.listByHostId(GROUP_HOST_ID)).thenAnswer(invocation -> {
                List<GpuDeviceVO> copies = rows.values().stream().map(this::copy).collect(Collectors.toList());
                if (readBarrier != null) {
                    readBarrier.await(10, TimeUnit.SECONDS);
                }
                return copies;
            });
            Mockito.lenient().when(gpuDeviceDao.listByVmId(Mockito.anyLong())).thenAnswer(invocation -> rows.values().stream()
                    .filter(row -> Objects.equals(row.getVmId(), invocation.getArgument(0))).map(this::copy).collect(Collectors.toList()));
            Mockito.lenient().when(gpuDeviceDao.persist(any(GpuDeviceVO.class))).thenAnswer(invocation -> {
                GpuDeviceVO row = invocation.getArgument(0);
                rows.put(row.getId(), copy(row));
                return row;
            });
            doAnswer(invocation -> {
                try {
                    return ((TransactionCallbackWithException<?, ?>) invocation.getArgument(0)).doInTransaction(null);
                } finally {
                    for (; held.get() > 0; held.set(held.get() - 1)) {
                        hostLock.unlock();
                    }
                }
            }).when(gpuService).inTransaction(any());
        }

        void reset(int gpus) {
            rows.clear();
            for (GpuDeviceVO device : hostGpusRows(gpus)) {
                rows.put(device.getId(), device);
            }
        }

        private List<GpuDeviceVO> hostGpusRows(int n) {
            List<GpuDeviceVO> devices = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                devices.add(gpu(GROUP_HOST_ID, GROUP_HOST_ID * 100 + i, agentAddress(i), "0", GpuDevice.State.Free));
            }
            return devices;
        }

        private GpuDeviceVO copy(GpuDeviceVO row) {
            GpuDeviceVO copy = gpu(row.getHostId(), row.getId(), row.getBusAddress(), row.getNumaNode(), row.getState());
            copy.setVmId(row.getVmId());
            return copy;
        }

        Map<Long, List<String>> holders() {
            Map<Long, List<String>> holders = new TreeMap<>();
            rows.values().stream().filter(row -> row.getVmId() != null).sorted(Comparator.comparing(GpuDeviceVO::getBusAddress))
                    .forEach(row -> holders.computeIfAbsent(row.getVmId(), vm -> new ArrayList<>()).add(GpuServiceImpl.normalizeBusAddress(row.getBusAddress())));
            return holders;
        }
    }

    /**
     * Starts the given VMs at once on the host, each asking for two GPUs, and returns each VM's devices, or null for a
     * VM that got none.
     */
    private Map<Long, List<String>> raceStarts(List<Long> vmIds, VgpuProfile profile, CyclicBarrier start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(vmIds.size());
        try {
            Map<Long, Future<List<String>>> futures = new LinkedHashMap<>();
            for (Long vmId : vmIds) {
                VirtualMachine vm = startingVm(vmId, GROUP_HOST_ID);
                futures.put(vmId, pool.submit(() -> {
                    if (start != null) {
                        start.await(10, TimeUnit.SECONDS);
                    }
                    try {
                        return gpuService.allocateGpuGroup(vm, GROUP_HOST_ID, profile.getId(), 2).getPositions();
                    } catch (InsufficientServerCapacityException e) {
                        return null;
                    }
                }));
            }
            Map<Long, List<String>> results = new LinkedHashMap<>();
            for (Map.Entry<Long, Future<List<String>>> future : futures.entrySet()) {
                results.put(future.getKey(), future.getValue().get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void racingStartsTakeDisjointGroupsOrFailCleanly() throws Exception {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3), positions(4, 5), positions(6, 7)));
        VgpuProfile profile = a100Profile();
        DeviceTable table = new DeviceTable(true, null);
        List<Long> vmIds = List.of(201L, 202L, 203L, 204L, 205L, 206L);

        for (int round = 0; round < 30; round++) {
            table.reset(8);
            Map<Long, List<String>> results = raceStarts(vmIds, profile, new CyclicBarrier(vmIds.size()));

            List<List<String>> granted = results.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
            assertEquals("four pairs for six starts; the other two get a host-scoped capacity exception", 4, granted.size());
            Set<String> taken = new HashSet<>();
            for (List<String> group : granted) {
                assertTrue("a whole registered pair " + group, group.size() == 2 && group.get(0).compareTo(group.get(1)) < 0);
                for (String bus : group) {
                    assertTrue("GPU " + bus + " given twice in round " + round, taken.add(bus));
                }
            }
            Map<Long, List<String>> expected = new TreeMap<>();
            results.forEach((vmId, group) -> {
                if (group != null) {
                    expected.put(vmId, group);
                }
            });
            assertEquals("the device rows record exactly the groups handed out", expected, table.holders());
        }
    }

    @Test
    public void racingStartsWithoutTheHostLockShareAGroup() throws Exception {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        VgpuProfile profile = a100Profile();
        DeviceTable table = new DeviceTable(false, new CyclicBarrier(2));
        table.reset(4);

        Map<Long, List<String>> results = raceStarts(List.of(301L, 302L), profile, null);

        assertNotNull(results.get(301L));
        assertEquals("without the lock both starts read the same free rows and take the same group",
                results.get(301L), results.get(302L));
    }

    @Test
    public void stoppedVmDevicesAreReleasedOnlyByTheStatementThatChecksItsState() {
        GpuDeviceVO freed = gpu(HOST_ID, 41L, GPU_BUS_ADDRESS, null, GpuDevice.State.Allocated);
        freed.setVmId(VM_ID);
        GpuDeviceVO keptByALaterStart = gpu(HOST_ID, 42L, GPU_BUS_ADDRESS_2, null, GpuDevice.State.Allocated);
        keptByALaterStart.setVmId(VM_ID);
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(List.of(freed, keptByALaterStart));
        when(gpuDeviceDao.releaseIfVmHoldsNone(41L, VM_ID)).thenReturn(true);
        when(gpuDeviceDao.releaseIfVmHoldsNone(42L, VM_ID)).thenReturn(false);

        gpuService.releaseGpuDevicesOfStoppedVm(VM_ID);

        verify(gpuDeviceDao).releaseIfVmHoldsNone(41L, VM_ID);
        verify(gpuDeviceDao).releaseIfVmHoldsNone(42L, VM_ID);
        verify(gpuDeviceDao, never()).persist(any(GpuDeviceVO.class));
        verify(gpuDeviceDao, never()).update(anyLong(), any(GpuDeviceVO.class));
        verify(gpuDeviceDao, never()).lockByHostId(anyLong());
    }

    @Test
    public void startReleasesItsDevicesOnOtherHostsBeforeTakingTheTargetLock() throws Exception {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1), positions(2, 3)));
        hostGpus(GROUP_HOST_ID, 4);
        GpuDeviceVO leftOver = gpu(OTHER_GROUP_HOST_ID, 51L, agentAddress(0), "0", GpuDevice.State.Allocated);
        leftOver.setVmId(VM_ID);
        when(gpuDeviceDao.listByVmId(VM_ID)).thenReturn(List.of(leftOver));
        runTransactionsInline();

        gpuService.allocateGpuGroup(startingVm(VM_ID, GROUP_HOST_ID), GROUP_HOST_ID, A100_PROFILE_ID, 2);

        assertEquals(GpuDevice.State.Free, leftOver.getState());
        assertNull(leftOver.getVmId());
        InOrder inOrder = Mockito.inOrder(gpuService, gpuDeviceDao);
        inOrder.verify(gpuDeviceDao).persist(leftOver);
        inOrder.verify(gpuService).inTransaction(any());
        inOrder.verify(gpuDeviceDao).lockByHostId(GROUP_HOST_ID);
        verify(gpuDeviceDao, never()).lockByHostId(OTHER_GROUP_HOST_ID);
    }

    @Test
    public void startingVmWithoutItsWholeGroupFailsItsStart() {
        registerPartitions(GROUP_HOST_ID, List.of(positions(0, 1)));
        List<GpuDeviceVO> devices = hostGpus(GROUP_HOST_ID, 2, 0);
        when(gpuDeviceDao.listByHostAndVm(GROUP_HOST_ID, VM_ID_100)).thenReturn(List.of(devices.get(0)));
        VgpuProfile profile = a100Profile();
        VirtualMachine vm = startingVm(VM_ID_100, GROUP_HOST_ID);

        when(vm.getState()).thenReturn(VirtualMachine.State.Starting);
        try {
            gpuService.getGPUDevice(vm, GROUP_HOST_ID, profile, 2);
            Assert.fail("a VM started with part of its group");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("with 1 of its 2 GPU devices"));
        }

        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        assertEquals("a running VM's description keeps what it holds", 1, gpuService.getGPUDevice(vm, GROUP_HOST_ID, profile, 2).getGpuDevices().size());
    }
}
