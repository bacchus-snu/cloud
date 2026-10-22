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
import com.cloud.hypervisor.Hypervisor.HypervisorType;
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
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.CreateVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.DeleteVgpuProfileCmd;
import org.apache.cloudstack.api.command.admin.gpu.DiscoverGpuDevicesCmd;
import org.apache.cloudstack.api.command.admin.gpu.ListGpuDevicesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.gpu.ListHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.ManageGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UnmanageGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuCardCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateGpuDeviceCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateHostGpuGroupsCmd;
import org.apache.cloudstack.api.command.admin.gpu.UpdateVgpuProfileCmd;
import org.apache.cloudstack.api.command.user.gpu.ListGpuCardsCmd;
import org.apache.cloudstack.api.command.user.gpu.ListGpuDevicesCmd;
import org.apache.cloudstack.api.command.user.gpu.ListVgpuProfilesCmd;
import org.apache.cloudstack.api.response.GpuCardResponse;
import org.apache.cloudstack.api.response.GpuDeviceResponse;
import org.apache.cloudstack.api.response.HostGpuGroupsResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VgpuProfileResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class GpuServiceImpl extends ManagerBase implements GpuService, PluggableService, Configurable {

    @Inject
    private GpuCardDao gpuCardDao;

    @Inject
    private VgpuProfileDao vgpuProfileDao;

    @Inject
    private GpuDeviceDao gpuDeviceDao;

    @Inject
    private ServiceOfferingDao serviceOfferingDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private UserVmManager userVmManager;

    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Inject
    private ResourceManager resourceManager;

    @Inject
    private HostGpuGroupRuleDao hostGpuGroupRuleDao;

    @Inject
    private HostGpuGroupMemberDao hostGpuGroupMemberDao;

    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    private StorageManager storageManager;

    private static final Pattern FULL_BUS_ADDRESS = Pattern.compile("([0-9a-f]{1,4}):([0-9a-f]{1,2}):([0-9a-f]{1,2})\\.([0-9a-f])");
    private static final Pattern SHORT_BUS_ADDRESS = Pattern.compile("([0-9a-f]{1,2}):([0-9a-f]{1,2})\\.([0-9a-f])");
    private static final Set<Integer> GROUP_SIZES = Set.of(1, 2, 4, 8);

    /**
     * The resolution of the stored VM update time, which keeps whole seconds.
     */
    private static final long REPORT_TIME_RESOLUTION_MS = 1000;

    private final Random random = new Random();

    @Override
    public boolean configure(String name, java.util.Map<String, Object> params) throws ConfigurationException {
        logger.info("Configuring GpuServiceImpl: {}", name);
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        // GPU Card Commands
        cmdList.add(CreateGpuCardCmd.class);
        cmdList.add(UpdateGpuCardCmd.class);
        cmdList.add(DeleteGpuCardCmd.class);
        cmdList.add(ListGpuCardsCmd.class);

        // vGPU Profile Commands
        cmdList.add(CreateVgpuProfileCmd.class);
        cmdList.add(UpdateVgpuProfileCmd.class);
        cmdList.add(DeleteVgpuProfileCmd.class);
        cmdList.add(ListVgpuProfilesCmd.class);

        // GPU Device Commands
        cmdList.add(ListGpuDevicesCmd.class);
        cmdList.add(ListGpuDevicesCmdByAdmin.class);
        cmdList.add(UnmanageGpuDeviceCmd.class);
        cmdList.add(ManageGpuDeviceCmd.class);
        cmdList.add(DiscoverGpuDevicesCmd.class);
        cmdList.add(CreateGpuDeviceCmd.class);
        cmdList.add(UpdateGpuDeviceCmd.class);
        cmdList.add(DeleteGpuDeviceCmd.class);
        cmdList.add(UpdateHostGpuGroupsCmd.class);
        cmdList.add(ListHostGpuGroupsCmd.class);

        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return GpuService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{GpuDetachOnStop, GpuAllocationAlgorithm};
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GPU_CARD_CREATE, eventDescription = "creating GPU Card")
    public GpuCardVO createGpuCard(CreateGpuCardCmd cmd) {
        final String deviceId = cmd.getDeviceId();
        final String deviceName = cmd.getDeviceName();
        final String name = cmd.getName();
        final String vendorName = cmd.getVendorName();
        final String vendorId = cmd.getVendorId();
        final Long videoRam = cmd.getVideoRam();

        // Validate inputs
        validateCreateGpuCardParams(deviceId, deviceName, name, vendorName, vendorId);

        GpuCardVO gpuCard = new GpuCardVO(deviceId, deviceName, name, vendorName, vendorId);
        gpuCard = gpuCardDao.persist(gpuCard);

        // Create passthrough vGPU profile with optional display parameters
        VgpuProfileVO passthroughProfile = new VgpuProfileVO("passthrough", "passthrough", gpuCard.getId(), 1L);
        passthroughProfile.setVideoRam(videoRam);
        vgpuProfileDao.persist(passthroughProfile);

        return gpuCard;
    }

    private void validateCreateGpuCardParams(String deviceId, String deviceName, String name, String vendorName, String vendorId) {
        if (StringUtils.isBlank(deviceId)) {
            throw new InvalidParameterValueException("Device ID cannot be blank");
        } else if (!deviceId.matches("^[a-zA-Z0-9]+$")) {
            throw new InvalidParameterValueException("Device ID must be alphanumeric and in hexadecimal format");
        }
        if (StringUtils.isBlank(deviceName)) {
            throw new InvalidParameterValueException("Device name cannot be blank");
        }
        if (StringUtils.isBlank(name)) {
            throw new InvalidParameterValueException("Display name cannot be blank");
        }
        if (StringUtils.isBlank(vendorName)) {
            throw new InvalidParameterValueException("Vendor name cannot be blank");
        }
        if (StringUtils.isBlank(vendorId)) {
            throw new InvalidParameterValueException("Vendor ID cannot be blank");
        } else if (!vendorId.matches("^[a-zA-Z0-9]+$")) {
            throw new InvalidParameterValueException("Vendor ID must be alphanumeric and in hexadecimal format");
        }

        // Check if a GPU card with the same vendor ID and device ID already exists
        GpuCardVO existingGpuCard = gpuCardDao.findByVendorIdAndDeviceId(vendorId, deviceId);
        if (existingGpuCard != null) {
            throw new InvalidParameterValueException(
                    String.format("GPU card with vendor ID %s and device ID %s already exists", vendorId, deviceId));
        }
    }


    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GPU_CARD_EDIT, eventDescription = "Updating GPU Card")
    public GpuCardVO updateGpuCard(UpdateGpuCardCmd cmd) {
        final Long id = cmd.getId();
        final String deviceName = cmd.getDeviceName();
        final String name = cmd.getName();
        final String vendorName = cmd.getVendorName();

        // Validate inputs
        GpuCardVO gpuCard = gpuCardDao.findById(id);
        if (gpuCard == null) {
            throw new InvalidParameterValueException("GPU card with ID " + id + " not found");
        }

        if (deviceName != null) {
            gpuCard.setDeviceName(deviceName);
        }
        if (name != null) {
            gpuCard.setName(name);
        }
        if (vendorName != null) {
            gpuCard.setVendorName(vendorName);
        }
        gpuCardDao.update(id, gpuCard);
        return gpuCard;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GPU_CARD_DELETE, eventDescription = "deleting the GPU Card")
    public boolean deleteGpuCard(DeleteGpuCardCmd cmd) {
        final Long id = cmd.getId();

        // Validate inputs
        GpuCardVO gpuCard = gpuCardDao.findById(id);
        if (gpuCard == null) {
            throw new InvalidParameterValueException("GPU card with ID " + id + " not found");
        }

        // Check if a GPU card is in use
        if (gpuDeviceDao.isGpuCardInUse(id)) {
            throw new InvalidParameterValueException(
                    "Cannot delete GPU card " + gpuCard + " as it is in use by one or more GPU devices");
        }

        // delete gpu profiles associated with this GPU card
        int rowsRemoved = vgpuProfileDao.removeByCardId(id);
        logger.info("Removed {} vGPU profiles associated with GPU card {}", rowsRemoved, gpuCard);

        return gpuCardDao.remove(id);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VGPU_PROFILE_CREATE, eventDescription = "creating vGPU profile")
    public VgpuProfileResponse createVgpuProfile(CreateVgpuProfileCmd cmd) {
        final String profileName = cmd.getName();
        final String profileDescription = cmd.getDescription();
        final Long gpuCardId = cmd.getCardId();
        final Long maxVgpuPerPgpu = cmd.getMaxVgpuPerPgpu();
        final Long videoRam = cmd.getVideoRam();
        final Long maxHeads = cmd.getMaxHeads();
        final Long maxResolutionX = cmd.getMaxResolutionX();
        final Long maxResolutionY = cmd.getMaxResolutionY();

        // Validate inputs
        if (StringUtils.isBlank(profileName)) {
            throw new InvalidParameterValueException("vGPU profile name cannot be empty");
        }

        // Check if the GPU card ID is valid
        GpuCardVO gpuCard = gpuCardDao.findById(gpuCardId);
        if (gpuCard == null) {
            throw new InvalidParameterValueException(String.format("GPU card with ID %d not found", gpuCardId));
        }

        // Check if a vGPU profile with the same name already exists
        VgpuProfileVO existingProfile = vgpuProfileDao.findByNameAndCardId(profileName, gpuCardId);
        if (existingProfile != null) {
            throw new InvalidParameterValueException(
                    String.format("vGPU profile with name %s already exists", profileName));
        }

        VgpuProfileVO vgpuProfile = new VgpuProfileVO(profileName, profileDescription, gpuCardId, maxVgpuPerPgpu,
                videoRam, maxHeads, maxResolutionX, maxResolutionY);
        vgpuProfile = vgpuProfileDao.persist(vgpuProfile);

        VgpuProfileResponse response = new VgpuProfileResponse(vgpuProfile, gpuCard);
        response.setResponseName(cmd.getCommandName());
        return response;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VGPU_PROFILE_EDIT, eventDescription = "updating vGPU profile")
    public VgpuProfileResponse updateVgpuProfile(UpdateVgpuProfileCmd cmd) {
        final Long id = cmd.getId();
        final String profileName = cmd.getProfileName();
        final String profileDescription = cmd.getDescription();
        final Long maxVgpuPerPgpu = cmd.getMaxVgpuPerPgpu();
        final Long videoRam = cmd.getVideoRam();
        final Long maxHeads = cmd.getMaxHeads();
        final Long maxResolutionX = cmd.getMaxResolutionX();
        final Long maxResolutionY = cmd.getMaxResolutionY();

        // Validate inputs
        VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(id);
        if (vgpuProfile == null) {
            throw new InvalidParameterValueException(String.format("vGPU profile with ID %d not found", id));
        }

        // Check if a vGPU profile with the same name already exists (if the name is being updated)
        if (profileName != null && !profileName.equals(vgpuProfile.getName())) {
            VgpuProfileVO existingProfile = vgpuProfileDao.findByNameAndCardId(profileName, vgpuProfile.getCardId());
            if (existingProfile != null) {
                throw new InvalidParameterValueException(
                        String.format("vGPU profile with name %s already exists", profileName));
            }
        }

        if (profileName != null) {
            vgpuProfile.setName(profileName);
        }
        if (profileDescription != null) {
            vgpuProfile.setDescription(profileDescription);
        }
        if (maxVgpuPerPgpu != null) {
            vgpuProfile.setMaxVgpuPerPgpu(maxVgpuPerPgpu);
        }
        if (videoRam != null) {
            vgpuProfile.setVideoRam(videoRam);
        }
        if (maxHeads != null) {
            vgpuProfile.setMaxHeads(maxHeads);
        }
        if (maxResolutionX != null) {
            vgpuProfile.setMaxResolutionX(maxResolutionX);
        }
        if (maxResolutionY != null) {
            vgpuProfile.setMaxResolutionY(maxResolutionY);
        }
        vgpuProfileDao.update(id, vgpuProfile);

        VgpuProfileResponse response = new VgpuProfileResponse(vgpuProfile,
                gpuCardDao.findById(vgpuProfile.getCardId()));
        response.setResponseName(cmd.getCommandName());
        return response;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VGPU_PROFILE_DELETE, eventDescription = "Deleting vGPU profile")
    public boolean deleteVgpuProfile(DeleteVgpuProfileCmd cmd) {
        final Long id = cmd.getId();

        // Validate inputs
        VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(id);
        if (vgpuProfile == null) {
            throw new InvalidParameterValueException(String.format("vGPU profile with ID %d not found", id));
        }

        // Check if vGPU profile is in use
        if (gpuDeviceDao.isVgpuProfileInUse(id)) {
            throw new InvalidParameterValueException(String.format(
                    "Cannot delete vGPU profile with ID %d as it is in use by one or more GPU " + "devices", id));
        }

        return vgpuProfileDao.remove(id);
    }

    @Override
    public ListResponse<GpuCardResponse> listGpuCards(ListGpuCardsCmd cmd) {
        Long id = cmd.getId();
        String keyword = cmd.getKeyword();
        String vendorName = cmd.getVendorName();
        String vendorId = cmd.getVendorId();
        String deviceId = cmd.getDeviceId();
        String deviceName = cmd.getDeviceName();
        boolean activeOnly = cmd.getActiveOnly();

        Pair<List<GpuCardVO>, Integer> gpuCardsAndCount = gpuCardDao.searchAndCountGpuCards(id, keyword, vendorId,
                vendorName, deviceId, deviceName, activeOnly, cmd.getStartIndex(), cmd.getPageSizeVal());

        return getGpuCardResponseListResponse(cmd, gpuCardsAndCount.first(), gpuCardsAndCount.second());
    }

    private static ListResponse<GpuCardResponse> getGpuCardResponseListResponse(ListGpuCardsCmd cmd,
            List<GpuCardVO> gpuCards, Integer count) {
        ListResponse<GpuCardResponse> response = new ListResponse<>();
        List<GpuCardResponse> gpuCardResponses = new ArrayList<>();

        for (GpuCardVO gpuCard : gpuCards) {
            GpuCardResponse gpuCardResponse = new GpuCardResponse(gpuCard);
            response.setResponseName(cmd.getCommandName());
            gpuCardResponses.add(gpuCardResponse);
        }

        response.setResponses(gpuCardResponses, count);
        response.setResponseName(cmd.getCommandName());
        return response;
    }

    @Override
    public ListResponse<VgpuProfileResponse> listVgpuProfiles(ListVgpuProfilesCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        String keyword = cmd.getKeyword();
        Long gpuCardId = cmd.getCardId();
        boolean activeOnly = cmd.getActiveOnly();

        Pair<List<VgpuProfileVO>, Integer> vgpuProfilesAndCount = vgpuProfileDao.searchAndCountVgpuProfiles(id, name,
                keyword, gpuCardId, activeOnly, cmd.getStartIndex(), cmd.getPageSizeVal());

        return getVgpuProfileResponseListResponse(cmd, vgpuProfilesAndCount.first(), vgpuProfilesAndCount.second());
    }

    private ListResponse<VgpuProfileResponse> getVgpuProfileResponseListResponse(ListVgpuProfilesCmd cmd,
            List<VgpuProfileVO> vgpuProfiles, Integer count) {
        ListResponse<VgpuProfileResponse> response = new ListResponse<>();
        List<VgpuProfileResponse> vgpuProfileResponses = new ArrayList<>();

        Map<Long, GpuCardVO> cardMap = new HashMap<>();
        for (VgpuProfileVO vgpuProfile : vgpuProfiles) {
            GpuCardVO gpuCard = cardMap.get(vgpuProfile.getCardId());
            if (gpuCard == null) {
                gpuCard = gpuCardDao.findById(vgpuProfile.getCardId());
                cardMap.put(vgpuProfile.getCardId(), gpuCard);
            }
            VgpuProfileResponse vgpuProfileResponse = new VgpuProfileResponse(vgpuProfile, gpuCard);
            vgpuProfileResponse.setResponseName(cmd.getCommandName());
            vgpuProfileResponses.add(vgpuProfileResponse);
        }

        response.setResponses(vgpuProfileResponses, count);
        response.setResponseName(cmd.getCommandName());
        return response;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GPU_CARD_DELETE, eventDescription = "creating GPU device")
    public GpuDeviceResponse createGpuDevice(CreateGpuDeviceCmd cmd) {
        final Long hostId = cmd.getHostId();
        String busAddress = cmd.getBusAddress();
        final Long gpuCardId = cmd.getGpuCardId();
        final Long vgpuProfileId = cmd.getVgpuProfileId();
        final GpuDevice.DeviceType type = cmd.getType();
        final Long parentGpuDeviceId = cmd.getParentGpuDeviceId();
        final String numaNode = cmd.getNumaNode();

        // Validate inputs
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new InvalidParameterValueException(String.format("Host with ID %d not found", hostId));
        }

        if (StringUtils.isBlank(busAddress)) {
            throw new InvalidParameterValueException("Bus address cannot be empty");
        }
        busAddress = busAddress.trim();

        // Check if a GPU device with the same bus address already exists on this host
        GpuDeviceVO existingDevice = gpuDeviceDao.findByHostIdAndBusAddress(hostId, busAddress);
        if (existingDevice != null) {
            throw new InvalidParameterValueException(
                    String.format("GPU device with bus address %s already exists on host %s", busAddress,
                            host.getName()));
        }

        // Validate GPU card
        GpuCardVO gpuCard = gpuCardDao.findById(gpuCardId);
        if (gpuCard == null) {
            throw new InvalidParameterValueException(String.format("GPU card with ID %d not found", gpuCardId));
        }

        // Validate vGPU profile
        VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(vgpuProfileId);
        if (vgpuProfile == null) {
            throw new InvalidParameterValueException(String.format("vGPU profile with ID %d not found", vgpuProfileId));
        }

        // Validate that the vGPU profile belongs to the specified GPU card
        if (!vgpuProfile.getCardId().equals(gpuCardId)) {
            throw new InvalidParameterValueException(
                    String.format("vGPU profile %s does not belong to GPU card %s", vgpuProfile.getName(),
                            gpuCard.getName()));
        }

        // Validate parent GPU device if specified
        if (parentGpuDeviceId != null) {
            GpuDeviceVO parentDevice = gpuDeviceDao.findById(parentGpuDeviceId);
            if (parentDevice == null) {
                throw new InvalidParameterValueException(
                        String.format("Parent GPU device with ID %d not found", parentGpuDeviceId));
            }
            if (!hostId.equals(parentDevice.getHostId())) {
                throw new InvalidParameterValueException("Parent GPU device must be on the same host");
            }
        }

        // Create the GPU device
        GpuDeviceVO gpuDevice = new GpuDeviceVO(gpuCardId, vgpuProfileId, busAddress, hostId, parentGpuDeviceId,
                numaNode, null);
        gpuDevice.setType(type);
        gpuDevice.setState(GpuDevice.State.Free);
        gpuDevice.setManagedState(GpuDevice.ManagedState.Managed);

        gpuDevice = gpuDeviceDao.persist(gpuDevice);

        logger.info("Successfully created GPU device {} on host {}", gpuDevice.getUuid(), host.getName());
        return createGpuDeviceResponse(gpuDevice, ResponseObject.ResponseView.Full);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GPU_DEVICE_EDIT, eventDescription = "updating GPU device")
    public GpuDeviceResponse updateGpuDevice(UpdateGpuDeviceCmd cmd) {
        final Long id = cmd.getId();
        final Long gpuCardId = cmd.getGpuCardId();
        final Long vgpuProfileId = cmd.getVgpuProfileId();
        final GpuDevice.DeviceType type = cmd.getType();
        final Long parentGpuDeviceId = cmd.getParentGpuDeviceId();
        final String numaNode = cmd.getNumaNode();

        // Validate inputs
        GpuDeviceVO gpuDevice = gpuDeviceDao.findById(id);
        if (gpuDevice == null) {
            throw new InvalidParameterValueException(String.format("GPU device with ID %d not found", id));
        }

        // Check if device is currently allocated to a VM
        if (gpuDevice.getVmId() != null) {
            throw new InvalidParameterValueException(
                    String.format("Cannot update GPU device %s as it is currently allocated to VM %d",
                            gpuDevice.getUuid(), gpuDevice.getVmId()));
        }

        // Validate GPU card if specified
        if (gpuCardId != null) {
            GpuCardVO gpuCard = gpuCardDao.findById(gpuCardId);
            if (gpuCard == null) {
                throw new InvalidParameterValueException(String.format("GPU card with ID %d not found", gpuCardId));
            }
        }

        // Validate vGPU profile if specified
        VgpuProfileVO vgpuProfile = null;
        if (vgpuProfileId != null) {
            vgpuProfile = vgpuProfileDao.findById(vgpuProfileId);
            if (vgpuProfile == null) {
                throw new InvalidParameterValueException(
                        String.format("vGPU profile with ID %d not found", vgpuProfileId));
            }

            // Check if vGPU profile belongs to the GPU card (either current or new)
            Long targetCardId = gpuCardId != null ? gpuCardId : gpuDevice.getCardId();
            if (!vgpuProfile.getCardId().equals(targetCardId)) {
                GpuCardVO targetCard = gpuCardDao.findById(targetCardId);
                throw new InvalidParameterValueException(
                        String.format("vGPU profile %s does not belong to GPU card %s", vgpuProfile.getName(),
                                targetCard.getName()));
            }
        }

        // Validate parent GPU device if specified
        if (parentGpuDeviceId != null) {
            GpuDeviceVO parentDevice = gpuDeviceDao.findById(parentGpuDeviceId);
            if (parentDevice == null) {
                throw new InvalidParameterValueException(
                        String.format("Parent GPU device with ID %d not found", parentGpuDeviceId));
            }
            if (parentDevice.getHostId() != gpuDevice.getHostId()) {
                throw new InvalidParameterValueException("Parent GPU device must be on the same host");
            }
            if (parentDevice.getId() == gpuDevice.getId()) {
                throw new InvalidParameterValueException("GPU device cannot be its own parent");
            }
        }

        // Update the GPU device
        if (gpuCardId != null) {
            gpuDevice.setCardId(gpuCardId);
        }
        if (vgpuProfileId != null) {
            gpuDevice.setVgpuProfileId(vgpuProfileId);
        }
        if (type != null) {
            gpuDevice.setType(type);
        }
        if (parentGpuDeviceId != null) {
            gpuDevice.setParentGpuDeviceId(parentGpuDeviceId);
        }
        if (numaNode != null) {
            gpuDevice.setNumaNode(numaNode);
        }
        gpuDeviceDao.update(id, gpuDevice);

        logger.info("Successfully updated GPU device {}", gpuDevice.getUuid());
        return createGpuDeviceResponse(gpuDevice, ResponseObject.ResponseView.Full);
    }

    @Override
    public ListResponse<GpuDeviceResponse> listGpuDevices(ListGpuDevicesCmd cmd) {
        Long id = null;
        Long hostId = null;
        Long gpuCardId = null;
        Long vgpuProfileId = null;
        if (cmd instanceof ListGpuDevicesCmdByAdmin) {
            ListGpuDevicesCmdByAdmin adminCmd = (ListGpuDevicesCmdByAdmin) cmd;
            id = adminCmd.getId();
            hostId = adminCmd.getHostId();
            gpuCardId = adminCmd.getGpuCardId();
            vgpuProfileId = adminCmd.getVgpuProfileId();
        }
        String keyword = cmd.getKeyword();
        Long vmId = cmd.getVmId();

        Pair<List<GpuDeviceVO>, Integer> gpuDevicesAndCount = gpuDeviceDao.searchAndCountGpuDevices(id, keyword, hostId,
                vmId, gpuCardId, vgpuProfileId, cmd.getStartIndex(), cmd.getPageSizeVal());

        return getGpuDeviceResponseListResponse(cmd, gpuDevicesAndCount.first(), gpuDevicesAndCount.second());
    }

    @Override
    public boolean disableGpuDevice(UnmanageGpuDeviceCmd cmd) {
        return updateGpuDeviceManagedState(cmd.getIds(), GpuDevice.ManagedState.Unmanaged);
    }

    @Override
    public boolean enableGpuDevice(ManageGpuDeviceCmd cmd) {
        return updateGpuDeviceManagedState(cmd.getIds(), GpuDevice.ManagedState.Managed);
    }

    @Override
    public void deallocateAllGpuDevicesForVm(long vmId) {
        List<GpuDeviceVO> devices = gpuDeviceDao.listByVmId(vmId);
        deallocateGpuDevices(devices);
    }

    @Override
    public void deallocateGpuDevicesForVmOnHost(long vmId, long hostId) {
        List<GpuDeviceVO> devices = gpuDeviceDao.listByHostAndVm(hostId, vmId);
        deallocateGpuDevices(devices);
    }

    /**
     * Frees the VM's devices on hosts other than the given one, each in its own statement, so that no host's device
     * lock is ever waited for while another host's device rows are held.
     */
    private void releaseDevicesOnOtherHosts(long vmId, long hostId) {
        deallocateGpuDevices(gpuDeviceDao.listByVmId(vmId).stream().filter(device -> device.getHostId() != hostId).collect(Collectors.toList()));
    }

    @Override
    public void releaseGpuDevicesOfStoppedVm(long vmId) {
        for (GpuDeviceVO device : gpuDeviceDao.listByVmId(vmId)) {
            if (gpuDeviceDao.releaseIfVmHoldsNone(device.getId(), vmId)) {
                logger.debug("Released GPU device {} of VM {}", device, vmId);
                checkAndUpdateParentGpuDeviceState(device.getParentGpuDeviceId());
            }
        }
    }

    /**
     * A device in error stays so: only the host's report of its devices clears that state.
     */
    private void deallocateGpuDevices(List<GpuDeviceVO> devices) {
        if (CollectionUtils.isNotEmpty(devices)) {
            for (GpuDeviceVO device : devices) {
                if (!GpuDevice.State.Error.equals(device.getState())) {
                    device.setState(GpuDevice.State.Free);
                }
                device.setVmId(null);
                gpuDeviceDao.persist(device);
                checkAndUpdateParentGpuDeviceState(device.getParentGpuDeviceId());
            }
        }
    }

    protected void checkAndUpdateParentGpuDeviceState(Long parentGpuDeviceId) {
        if (parentGpuDeviceId != null) {
            GpuDeviceVO parentGpuDevice = gpuDeviceDao.findById(parentGpuDeviceId);
            checkAndUpdateParentGpuDeviceState(parentGpuDevice);
        }
    }

    protected void checkAndUpdateParentGpuDeviceState(GpuDeviceVO parentDevice) {
        if (parentDevice != null) {
            List<GpuDeviceVO> childDevices = gpuDeviceDao.listByParentGpuDeviceId(parentDevice.getId());
            GpuDevice.State finalState = GpuDevice.State.Free;
            for (GpuDeviceVO childDevice : childDevices) {
                if (childDevice.getState().equals(GpuDevice.State.Allocated)) {
                    finalState = GpuDevice.State.PartiallyAllocated;
                } else if (childDevice.getState().equals(GpuDevice.State.Error)) {
                    finalState = GpuDevice.State.Error;
                    break;
                }
            }
            if (!finalState.equals(parentDevice.getState())) {
                parentDevice.setState(finalState);
                gpuDeviceDao.update(parentDevice.getId(), parentDevice);
            }
        }
    }

    @Override
    public void allocateGpuDevicesToVmOnHost(long vmId, long hostId, List<VgpuTypesInfo> gpuDevices) {
        releaseDevicesOnOtherHosts(vmId, hostId);
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                gpuDeviceDao.lockByHostId(hostId);
                deallocateGpuDevices(gpuDeviceDao.listByHostAndVm(hostId, vmId));

                // Allocate new GPU devices to the VM on the host
                for (VgpuTypesInfo gpuDevice : gpuDevices) {
                    GpuDeviceVO device = gpuDeviceDao.findByHostIdAndBusAddress(hostId, gpuDevice.getBusAddress());
                    if (device != null) {
                        device.setState(GpuDevice.State.Allocated);
                        device.setVmId(vmId);
                        gpuDeviceDao.persist(device);
                        checkAndUpdateParentGpuDeviceState(device.getParentGpuDeviceId());
                    } else {
                        throw new CloudRuntimeException(
                                String.format("GPU device not found for VM %d on host %d", vmId, hostId));
                    }
                }
            }
        });
    }

    @Override
    public ListResponse<GpuDeviceResponse> discoverGpuDevices(DiscoverGpuDevicesCmd cmd) {
        final Long hostId = cmd.getId();
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            throw new InvalidParameterValueException(String.format("Host with ID %d not found", hostId));
        }
        if (!Status.Up.equals(host.getStatus())) {
            throw new InvalidParameterValueException(String.format("Host [%s] is not in Up status", host));
        }

        // Get GPU stats on the host and update GPU details
        // getGPUStatistics() fetches the stats
        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails = resourceManager.getGPUStatistics(host);
        if (groupDetails != null) {
            resourceManager.updateGPUDetails(host.getId(), groupDetails);
        }

        // Return the list of GPU devices for the host
        List<GpuDeviceVO> gpuDevices = gpuDeviceDao.listByHostId(hostId);
        return getGpuDeviceResponseListResponse(cmd, gpuDevices, gpuDevices.size());
    }

    @Override
    public boolean isGPUDeviceAvailable(Host host, Long vmId, VgpuProfile vgpuProfile, int gpuCount) {
        HostGpus gpus = loadHostGpus(host.getId(), vgpuProfile.getId(), vmId);
        return gpus != null && !gpus.selector.candidates(gpus.free, gpuCount).isEmpty();
    }

    protected <T, E extends Throwable> T inTransaction(TransactionCallbackWithException<T, E> callback) throws E {
        return Transaction.execute(callback);
    }

    @Override
    public GPUDeviceTO getGPUDevice(VirtualMachine vm, long hostId, VgpuProfile vgpuProfile, int gpuCount) {
        List<GpuDeviceVO> held = gpuDeviceDao.listByHostAndVm(hostId, vm.getId()).stream()
                .filter(device -> device.getVgpuProfileId() == vgpuProfile.getId())
                .sorted(Comparator.comparing(device -> normalizeBusAddress(device.getBusAddress())))
                .collect(Collectors.toList());
        if (held.size() != gpuCount) {
            if (VirtualMachine.State.Starting.equals(vm.getState())) {
                throw new CloudRuntimeException(String.format("VM %s is starting on host %d with %d of its %d GPU devices", vm.getUuid(), hostId,
                        held.size(), gpuCount));
            }
            logger.warn("VM {} holds {} of its {} GPU devices on host {}", vm, held.size(), gpuCount, hostId);
        }

        GpuCardVO gpuCard = gpuCardDao.findById(vgpuProfile.getCardId());
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        List<VgpuTypesInfo> vgpuInfoList = new ArrayList<>();
        for (GpuDeviceVO gpuDevice : held) {
            VgpuTypesInfo vgpuInfo = new VgpuTypesInfo(gpuDevice.getType(), gpuCard.getName(),
                    vgpuProfile.getName(), gpuDevice.getBusAddress(), gpuCard.getVendorId(),
                    gpuCard.getVendorName(), gpuCard.getDeviceId(), gpuCard.getDeviceName());
            vgpuInfo.setDisplay(serviceOffering.getGpuDisplay());
            if (gpuDevice.getParentGpuDeviceId() != null) {
                GpuDeviceVO parentGpuDevice = gpuDeviceDao.findById(gpuDevice.getParentGpuDeviceId());
                if (parentGpuDevice != null) {
                    vgpuInfo.setParentBusAddress(parentGpuDevice.getBusAddress());
                }
            }
            vgpuInfoList.add(vgpuInfo);
        }

        HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails = getGpuGroupDetailsFromGpuDevicesOnHost(hostId);
        return new GPUDeviceTO(gpuCard.getName(), vgpuProfile.getName(), gpuCount, groupDetails, vgpuInfoList);
    }

    @Override
    @DB
    public GpuGroupPick allocateGpuGroup(VirtualMachine vm, long hostId, long vgpuProfileId, int count) throws InsufficientServerCapacityException {
        releaseDevicesOnOtherHosts(vm.getId(), hostId);
        return inTransaction(new TransactionCallbackWithException<GpuGroupPick, InsufficientServerCapacityException>() {
            @Override
            public GpuGroupPick doInTransaction(TransactionStatus status) throws InsufficientServerCapacityException {
                gpuDeviceDao.lockByHostId(hostId);
                List<GpuDeviceVO> held = gpuDeviceDao.listByHostAndVm(hostId, vm.getId());
                if (held.size() == count && held.stream().allMatch(device -> device.getVgpuProfileId() == vgpuProfileId
                        && GpuDevice.ManagedState.Managed.equals(device.getManagedState()) && GpuDevice.State.Allocated.equals(device.getState()))) {
                    logger.debug("VM {} keeps its GPU group {} of host {}", vm, held, hostId);
                    return toPick(hostId, held.stream().sorted(Comparator.comparing(device -> normalizeBusAddress(device.getBusAddress())))
                            .collect(Collectors.toList()));
                }
                deallocateGpuDevices(held);
                List<GpuDeviceVO> group = chooseGroup(hostId, vgpuProfileId, count).orElseThrow(() -> new InsufficientServerCapacityException(
                        String.format("Host %d has no whole free group of %d GPUs for VM %s", hostId, count, vm.getUuid()), Host.class, hostId));
                for (GpuDeviceVO device : group) {
                    device.setState(GpuDevice.State.Allocated);
                    device.setVmId(vm.getId());
                    gpuDeviceDao.persist(device);
                    checkAndUpdateParentGpuDeviceState(device.getParentGpuDeviceId());
                }
                logger.debug("Allocated GPU group {} of host {} to VM {}", group, hostId, vm);
                return toPick(hostId, group);
            }
        });
    }

    @Override
    public HashMap<String, HashMap<String, VgpuTypesInfo>> getGpuGroupDetailsFromGpuDevicesOnHost(final long hostId) {
        HashMap<String, HashMap<String, VgpuTypesInfo>> gpuGroupDetails = new HashMap<>();
        List<GpuDeviceVO> gpuDevices = gpuDeviceDao.listByHostId(hostId);
        for (final GpuDeviceVO device : gpuDevices) {
            // Calculate GPU capacity and update gpuGroupDetails
            GpuCardVO card = gpuCardDao.findById(device.getCardId());
            String groupName = card.getName();
            if (!gpuGroupDetails.containsKey(groupName)) {
                gpuGroupDetails.put(groupName, new HashMap<>());
            }
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(device.getVgpuProfileId());

            VgpuTypesInfo gpuDeviceInfo = gpuGroupDetails.get(groupName).get(vgpuProfile.getName());
            long remainingCapacity = 0L;
            long maxCapacity = 1L;
            if (GpuDevice.State.Free.equals(device.getState()) && GpuDevice.ManagedState.Managed.equals(
                    device.getManagedState())) {
                remainingCapacity = 1L;
            }
            if (GpuDevice.DeviceType.VGPUOnly.equals(device.getType()) ||
                GpuDevice.ManagedState.Unmanaged.equals(device.getManagedState()) ||
                GpuDevice.State.Error.equals(device.getState())
            ) {
                maxCapacity = 0L;
                remainingCapacity = 0L;
            }
            if (gpuDeviceInfo == null) {
                gpuDeviceInfo = new VgpuTypesInfo(card.getName(), vgpuProfile.getName(), vgpuProfile.getVideoRam(), vgpuProfile.getMaxHeads(),
                        vgpuProfile.getMaxResolutionX(), vgpuProfile.getMaxResolutionY(),
                        vgpuProfile.getMaxVgpuPerPgpu(), remainingCapacity, maxCapacity);
                gpuDeviceInfo.setDeviceName(card.getDeviceName());
                gpuDeviceInfo.setVendorId(card.getVendorId());
                gpuDeviceInfo.setVendorName(card.getVendorName());
                gpuDeviceInfo.setDeviceId(card.getDeviceId());
                gpuGroupDetails.get(groupName).put(vgpuProfile.getName(), gpuDeviceInfo);
            } else {
                // Update the existing VgpuTypesInfo with the new device's information
                gpuDeviceInfo.setRemainingCapacity(gpuDeviceInfo.getRemainingCapacity() + remainingCapacity);
                gpuDeviceInfo.setMaxVmCapacity(gpuDeviceInfo.getMaxCapacity() + maxCapacity);
            }
        }
        return gpuGroupDetails;
    }

    /*
     * For the devices in newGpuDevicesInfo, create the GPU card and vGPU profile if they don't exist.
     * For the devices in existingGpuDevices but not in newGpuDevicesInfo, disable the device.
     * For the devices in newGpuDevicesInfo but not in existingGpuDevices, add them to the host.
     * For the devices in both, update the device's info.
     */
    @Override
    @DB
    public void addGpuDevicesToHost(final Host host, final List<VgpuTypesInfo> newGpuDevicesInfo, final Date reportTime) {
        GlobalLock lock = GlobalLock.getInternLock("add-gpu-devices-to-host-" + host.getId());
        try {
            if (lock.lock(30)) {
                try {
                    inTransaction(new TransactionCallbackWithException<Void, RuntimeException>() {
                        @Override
                        public Void doInTransaction(TransactionStatus status) {
                            syncGpuDevicesOfHost(host, newGpuDevicesInfo, reportTime);
                            return null;
                        }
                    });
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            lock.releaseRef();
        }
    }

    /**
     * Brings the host's GPU device rows in line with the devices its agent reports. The rows are locked and read in
     * one transaction, so that no allocation on the host interleaves with the update.
     */
    private void syncGpuDevicesOfHost(final Host host, final List<VgpuTypesInfo> newGpuDevicesInfo, final Date reportTime) {
        gpuDeviceDao.lockByHostId(host.getId());
        List<GpuDeviceVO> existingGpuDevices = gpuDeviceDao.listByHostId(host.getId());
        Map<String, GpuDeviceVO> existingGpuDevicesMap = new HashMap<>();
        Map<String, GpuDeviceVO> gpuDevicesToDisableMap = new HashMap<>();
        for (final GpuDeviceVO device : existingGpuDevices) {
            existingGpuDevicesMap.put(device.getBusAddress(), device);
            gpuDevicesToDisableMap.put(device.getBusAddress(), device);
        }

        Map<String, GpuCardVO> cardMap = new HashMap<>();
        Map<String, VgpuProfileVO> vgpuProfileMap = new HashMap<>();

        for (final VgpuTypesInfo deviceInfo : newGpuDevicesInfo) {
            GpuCardVO card = getGpuCardAndUpdateMap(deviceInfo, cardMap, vgpuProfileMap);
            VgpuProfileVO vgpuProfile = getVgpuProfileAndUpdateMap(deviceInfo, card, vgpuProfileMap);

            GpuDeviceVO existingDevice = existingGpuDevicesMap.get(deviceInfo.getBusAddress());
            if (existingDevice == null) {
                createAndAddGpuDeviceToHost(deviceInfo, host, card, vgpuProfile, reportTime);
            } else {
                // Update the device's info
                GpuDeviceVO parentGpuDevice = null;
                if (deviceInfo.getParentBusAddress() != null) {
                    parentGpuDevice = gpuDeviceDao.findByHostIdAndBusAddress(host.getId(),
                            deviceInfo.getParentBusAddress());
                    if (parentGpuDevice != null) {
                        existingDevice.setParentGpuDeviceId(parentGpuDevice.getId());
                        parentGpuDevice.setType(GpuDevice.DeviceType.VGPUOnly);
                        gpuDeviceDao.persist(parentGpuDevice);
                    }
                }
                if (deviceInfo.isPassthroughEnabled()) {
                    existingDevice.setType(deviceInfo.getDeviceType());
                } else {
                    existingDevice.setType(GpuDevice.DeviceType.VGPUOnly);
                }
                if (existingDevice.getPciRoot() == null) {
                    existingDevice.setPciRoot(deviceInfo.getPciRoot());
                }
                setStateAndVmName(deviceInfo, existingDevice, parentGpuDevice, reportTime);
                gpuDeviceDao.update(existingDevice.getId(), existingDevice);
                checkAndUpdateParentGpuDeviceState(existingDevice.getParentGpuDeviceId());
            }
            gpuDevicesToDisableMap.remove(deviceInfo.getBusAddress());
        }

        // Disable the devices that are not in the new list
        for (final GpuDeviceVO device : gpuDevicesToDisableMap.values()) {
            logger.info("Disabling GPU device {} on host {} due to missing address in the new devices on the host.", device, host);
            device.setState(GpuDevice.State.Error);
            gpuDeviceDao.update(device.getId(), device);
            checkAndUpdateParentGpuDeviceState(device.getParentGpuDeviceId());
        }
    }

    @Override
    public boolean deleteGpuDevices(DeleteGpuDeviceCmd deleteGpuDeviceCmd) {
        List<Long> gpuDeviceIds = deleteGpuDeviceCmd.getIds();
        if (CollectionUtils.isEmpty(gpuDeviceIds)) {
            throw new InvalidParameterValueException("GPU device IDs cannot be empty");
        }

        List<GpuDeviceVO> gpuDevices = gpuDeviceDao.listByIds(gpuDeviceIds);
        if (gpuDevices.isEmpty()) {
            throw new InvalidParameterValueException("No GPU devices found for the provided IDs");
        }

        inTransaction(new TransactionCallbackWithException<Void, RuntimeException>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                lockHostsOf(gpuDevices);
                for (GpuDeviceVO gpuDevice : gpuDeviceDao.listByIds(gpuDeviceIds)) {
                    if (gpuDevice.getVmId() != null) {
                        throw new InvalidParameterValueException(
                                String.format("Cannot delete GPU device %s as it is currently allocated to VM %d",
                                        gpuDevice.getUuid(), gpuDevice.getVmId()));
                    }
                    gpuDeviceDao.remove(gpuDevice.getId());
                }
                return null;
            }
        });

        for (Long hostId : gpuDevices.stream().map(GpuDeviceVO::getHostId).collect(Collectors.toSet())) {
            resourceManager.updateGPUDetails(hostId, getGpuGroupDetailsFromGpuDevicesOnHost(hostId));
        }
        return true;
    }

    /**
     * Locks the GPU device rows of the hosts of the given devices, in host id order.
     */
    private void lockHostsOf(List<GpuDeviceVO> devices) {
        devices.stream().map(GpuDeviceVO::getHostId).distinct().sorted().forEach(gpuDeviceDao::lockByHostId);
    }

    private GpuCardVO getGpuCardAndUpdateMap(VgpuTypesInfo deviceInfo, Map<String, GpuCardVO> cardMap,
            Map<String, VgpuProfileVO> vgpuProfileMap) {
        String cardMapKey = deviceInfo.getDeviceId() + " - " + deviceInfo.getVendorId();
        GpuCardVO card = cardMap.get(cardMapKey);
        if (card == null) {
            card = gpuCardDao.findByVendorIdAndDeviceId(deviceInfo.getVendorId(), deviceInfo.getDeviceId());
            if (card == null) {
                // Create GPU card if it doesn't exist
                logger.info("Creating new GPU card for vendor ID: {} and device ID: {}", deviceInfo.getVendorId(),
                        deviceInfo.getDeviceId());

                String deviceName = StringUtils.isNotBlank(deviceInfo.getDeviceName()) ?
                        deviceInfo.getDeviceName() :
                        deviceInfo.getGroupName();
                String vendorName = StringUtils.isNotBlank(deviceInfo.getVendorName()) ?
                        deviceInfo.getVendorName() :
                        "Unknown Vendor";
                String cardDisplayName = vendorName + " " + deviceName;

                card = new GpuCardVO(deviceInfo.getDeviceId(), deviceName, cardDisplayName, vendorName,
                        deviceInfo.getVendorId());
                card = gpuCardDao.persist(card);

                // Create default passthrough profile for the new card
                VgpuProfileVO passthroughProfile = new VgpuProfileVO("passthrough", "passthrough", card.getId(), 1L);
                passthroughProfile.setVideoRam(deviceInfo.getVideoRam());
                passthroughProfile.setMaxResolutionX(deviceInfo.getMaxResolutionX());
                passthroughProfile.setMaxResolutionY(deviceInfo.getMaxResolutionY());
                passthroughProfile.setMaxHeads(deviceInfo.getMaxHeads());
                passthroughProfile = vgpuProfileDao.persist(passthroughProfile);

                String vgpuProfileKey = card.getUuid() + " | " + deviceInfo.getModelName();
                vgpuProfileMap.put(vgpuProfileKey, passthroughProfile);
                logger.info("Created GPU card: {} with passthrough profile: {}", card, passthroughProfile);
            }
            cardMap.put(cardMapKey, card);
        }
        return card;
    }

    private VgpuProfileVO getVgpuProfileAndUpdateMap(VgpuTypesInfo deviceInfo, GpuCardVO card,
            Map<String, VgpuProfileVO> vgpuProfileMap) {
        String vgpuProfileKey = card.getUuid() + " | " + deviceInfo.getModelName();
        VgpuProfileVO vgpuProfile = vgpuProfileMap.get(vgpuProfileKey);
        if (vgpuProfile == null) {
            vgpuProfile = vgpuProfileDao.findByNameAndCardId(deviceInfo.getModelName(), card.getId());
            if (vgpuProfile == null) {
                // Create vGPU profile if it doesn't exist
                logger.info("Creating new vGPU profile: {} for GPU card: {}", deviceInfo.getModelName(),
                        card.getName());

                vgpuProfile = new VgpuProfileVO(deviceInfo.getModelName(), deviceInfo.getModelName(), card.getId(),
                        deviceInfo.getMaxVpuPerGpu() != null ? deviceInfo.getMaxVpuPerGpu() : 1L);
                vgpuProfile.setVideoRam(deviceInfo.getVideoRam());
                vgpuProfile.setMaxResolutionX(deviceInfo.getMaxResolutionX());
                vgpuProfile.setMaxResolutionY(deviceInfo.getMaxResolutionY());
                vgpuProfile.setMaxHeads(deviceInfo.getMaxHeads());
                vgpuProfile = vgpuProfileDao.persist(vgpuProfile);

                logger.info("Created vGPU profile: {}", vgpuProfile);
            }
            vgpuProfileMap.put(vgpuProfileKey, vgpuProfile);
        }
        return vgpuProfile;
    }

    private void createAndAddGpuDeviceToHost(VgpuTypesInfo deviceInfo, Host host, GpuCardVO card,
            VgpuProfileVO vgpuProfile, Date reportTime) {
        Long parentGpuDeviceId = null;
        GpuDeviceVO parentGpuDevice = null;
        if (deviceInfo.getParentBusAddress() != null) {
            parentGpuDevice = gpuDeviceDao.findByHostIdAndBusAddress(host.getId(),
                    deviceInfo.getParentBusAddress());
            if (parentGpuDevice != null) {
                parentGpuDeviceId = parentGpuDevice.getId();
                parentGpuDevice.setType(GpuDevice.DeviceType.VGPUOnly);
                gpuDeviceDao.persist(parentGpuDevice);
            }
        }
        GpuDeviceVO gpuDevice = new GpuDeviceVO(card.getId(), vgpuProfile.getId(), deviceInfo.getBusAddress(),
                host.getId(), parentGpuDeviceId, deviceInfo.getNumaNode(), deviceInfo.getPciRoot());
        gpuDevice.setHostId(host.getId());
        gpuDevice.setType(deviceInfo.getDeviceType());
        gpuDevice.setBusAddress(deviceInfo.getBusAddress());
        gpuDevice.setCardId(card.getId());
        setStateAndVmName(deviceInfo, gpuDevice, parentGpuDevice, reportTime);
        if (!deviceInfo.isPassthroughEnabled()) {
            gpuDevice.setType(GpuDevice.DeviceType.VGPUOnly);
        }

        gpuDevice = gpuDeviceDao.persist(gpuDevice);
        checkAndUpdateParentGpuDeviceState(parentGpuDevice);
        logger.info("Added new GPU device {} to host {}", gpuDevice, host);
    }

    private void setStateAndVmName(VgpuTypesInfo deviceInfo, GpuDeviceVO device, GpuDeviceVO parentGpuDevice, Date reportTime) {
        if (StringUtils.isNotBlank(deviceInfo.getVmName())) {
            VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(deviceInfo.getVmName());
            if (vm != null) {
                device.setVmId(vm.getId());
                device.setState(GpuDevice.State.Allocated);
            } else {
                device.setState(GpuDevice.State.Error);
                logger.warn("VM with name {} not found for GPU device {}. Setting state to Error.",
                        deviceInfo.getVmName(), device);
            }
        } else {
            // If no VM name is provided, it's possible that the device is allocated to a stopped VM or not allocated at all.
            if (device.getVmId() == null && !device.getState().equals(GpuDevice.State.PartiallyAllocated)) {
                device.setState(GpuDevice.State.Free);
            } else {
                VMInstanceVO vm = vmInstanceDao.findById(device.getVmId());
                if (vm != null && vmMayHoldDevice(vm, device, reportTime)) {
                    device.setState(GpuDevice.State.Allocated);
                } else {
                    logger.warn("VM with ID {} not found for GPU device {}, or it no longer holds the device. Setting state to Free.",
                            device.getVmId(), device);
                    device.setState(GpuDevice.State.Free);
                    device.setVmId(null);
                }
            }
        }
    }

    /**
     * The host's report of which domain uses a device is authoritative, except for a VM starting on the device's host,
     * which holds its devices before its domain exists, and a VM whose state changed after the report was gathered.
     * A stopped VM keeps its devices only when GPUs are not detached on stop, or while a restart within its running
     * session is in progress.
     */
    private boolean vmMayHoldDevice(VMInstanceVO vm, GpuDeviceVO device, Date reportTime) {
        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            return !GpuDetachOnStop.valueIn(vm.getDomainId())
                    || vmInstanceDetailsDao.findDetail(vm.getId(), VmDetailConstants.RESTART_IN_PROGRESS) != null;
        }
        if (!Objects.equals(vm.getHostId(), device.getHostId())) {
            return false;
        }
        return VirtualMachine.State.Starting.equals(vm.getState())
                || (vm.getUpdateTime() != null && reportTime != null && vm.getUpdateTime().getTime() >= reportTime.getTime() - REPORT_TIME_RESOLUTION_MS);
    }

    private boolean updateGpuDeviceManagedState(List<Long> gpuDeviceIds, GpuDevice.ManagedState managedState) {
        if (CollectionUtils.isEmpty(gpuDeviceIds)) {
            throw new InvalidParameterValueException("GPU device IDs cannot be empty");
        }
        List<GpuDeviceVO> gpuDevices = new ArrayList<>();
        Set<Long> hostIds = new HashSet<>();
        for (Long gpuDeviceId : gpuDeviceIds) {
            GpuDeviceVO gpuDevice = gpuDeviceDao.findById(gpuDeviceId);
            if (gpuDevice == null) {
                throw new InvalidParameterValueException(String.format("GPU device with ID %d not found", gpuDeviceId));
            }

            if (gpuDevice.getManagedState().equals(managedState)) {
                logger.debug("GPU device {} is already in resource state: {}. Skipping state update.", gpuDevice,
                        managedState);
            }

            if (gpuDevice.getVmId() != null) {
                throw new InvalidParameterValueException(
                        String.format("Cannot change resource state of GPU device %s as it is in use by VM %d",
                                gpuDevice, gpuDevice.getVmId()));
            }
            gpuDevices.add(gpuDevice);
            hostIds.add(gpuDevice.getHostId());
        }

        inTransaction(new TransactionCallbackWithException<Void, RuntimeException>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                lockHostsOf(gpuDevices);
                for (Long gpuDeviceId : gpuDeviceIds) {
                    GpuDeviceVO gpuDevice = gpuDeviceDao.findById(gpuDeviceId);
                    if (gpuDevice == null) {
                        throw new InvalidParameterValueException(String.format("GPU device with ID %d not found", gpuDeviceId));
                    }
                    if (gpuDevice.getVmId() != null) {
                        throw new InvalidParameterValueException(
                                String.format("Cannot change resource state of GPU device %s as it is in use by VM %d",
                                        gpuDevice, gpuDevice.getVmId()));
                    }
                    gpuDevice.setManagedState(managedState);
                    gpuDeviceDao.update(gpuDevice.getId(), gpuDevice);
                }
                return null;
            }
        });

        for (Long hostId : hostIds) {
            resourceManager.updateGPUDetails(hostId, getGpuGroupDetailsFromGpuDevicesOnHost(hostId));
        }

        return true;
    }

    private ListResponse<GpuDeviceResponse> getGpuDeviceResponseListResponse(BaseCmd cmd, List<GpuDeviceVO> gpuDevices,
            Integer count) {
        ListResponse<GpuDeviceResponse> response = new ListResponse<>();
        List<GpuDeviceResponse> gpuDeviceResponses = new ArrayList<>();

        ResponseObject.ResponseView view = ResponseObject.ResponseView.Full;
        if (cmd instanceof ListGpuDevicesCmdByAdmin) {
            ListGpuDevicesCmd listCmd = (ListGpuDevicesCmdByAdmin) cmd;
            view = listCmd.getResponseView();
        } else if (cmd instanceof ListGpuDevicesCmd) {
            ListGpuDevicesCmd listCmd = (ListGpuDevicesCmd) cmd;
            view = listCmd.getResponseView();
        }

        for (GpuDeviceVO gpuDevice : gpuDevices) {
            GpuDeviceResponse gpuDeviceResponse = createGpuDeviceResponse(gpuDevice, view);
            gpuDeviceResponses.add(gpuDeviceResponse);
        }

        response.setResponses(gpuDeviceResponses, count);
        response.setResponseName(cmd.getCommandName());
        return response;
    }

    private GpuDeviceResponse createGpuDeviceResponse(GpuDeviceVO gpuDevice, ResponseObject.ResponseView view) {
        GpuDeviceResponse response = new GpuDeviceResponse();
        response.setId(gpuDevice.getUuid());
        if (view.equals(ResponseObject.ResponseView.Full)) {
            response.setBussAddress(gpuDevice.getBusAddress());
            response.setState(gpuDevice.getState());
            response.setManagedState(gpuDevice.getManagedState());
            response.setType(gpuDevice.getType());
            response.setNumaNode(gpuDevice.getNumaNode());

            // Host name lookup
            HostVO host = hostDao.findById(gpuDevice.getHostId());
            if (host != null) {
                response.setHostName(host.getName());
                response.setHostId(host.getUuid());
            }

            if (gpuDevice.getParentGpuDeviceId() != null) {
                GpuDeviceVO parentGpuDevice = gpuDeviceDao.findById(gpuDevice.getParentGpuDeviceId());
                if (parentGpuDevice != null) {
                    response.setParentGpuDeviceId(parentGpuDevice.getUuid());
                } else {
                    logger.debug("Parent GPU device with ID {} not found for GPU device {}",
                            gpuDevice.getParentGpuDeviceId(), gpuDevice.getUuid());
                }
            }
        }
        // GPU card info
        GpuCardVO gpuCard = gpuCardDao.findById(gpuDevice.getCardId());
        if (gpuCard != null) {
            if (view.equals(ResponseObject.ResponseView.Full)) {
                response.setGpuCardId(gpuCard.getUuid());
            }
            response.setGpuCardName(gpuCard.getName());
        }

        // vGPU profile info
        VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(gpuDevice.getVgpuProfileId());
        if (vgpuProfile != null) {
            if (view.equals(ResponseObject.ResponseView.Full)) {
                response.setVgpuProfileId(vgpuProfile.getUuid());
            }
            response.setVgpuProfileName(vgpuProfile.getName());
        }

        if (gpuDevice.getVmId() != null) {
            UserVmVO vm = userVmManager.getVirtualMachine(gpuDevice.getVmId());
            if (vm != null) {
                response.setVmId(vm.getUuid());
                response.setVmName(vm.getInstanceName());
                response.setVmState(vm.getState());
            } else {
                logger.debug("VM with ID {} not found for GPU device {}", gpuDevice.getVmId(), gpuDevice.getUuid());
            }
        }

        return response;
    }

    private static final class HostGpus {
        private final GpuGroupSelector selector;
        private final Map<String, GpuDeviceVO> usable = new TreeMap<>();
        private final Map<String, String> free = new TreeMap<>();
        private final Map<String, String> present = new TreeMap<>();

        private HostGpus(GpuGroupSelector selector) {
            this.selector = selector;
        }
    }

    /**
     * @param releasingVmId a VM whose own devices on the host count as free, or null
     * @return the host's GPUs of the vGPU profile by bus address: usable ones (managed and free) and present ones (not
     *         in error), or null when the host has no registered GPU group rule
     */
    private HostGpus loadHostGpus(long hostId, long vgpuProfileId, Long releasingVmId) {
        GroupRule rule = getGpuGroupRule(hostId);
        if (rule == null) {
            return null;
        }
        HostGpus gpus = new HostGpus(new GpuGroupSelector(rule, registeredGroups(hostId, rule)));
        for (GpuDeviceVO device : gpuDeviceDao.listByHostId(hostId)) {
            if (device.getVgpuProfileId() != vgpuProfileId || GpuDevice.DeviceType.VGPUOnly.equals(device.getType())) {
                continue;
            }
            String bus = normalizeBusAddress(device.getBusAddress());
            String node = StringUtils.defaultString(device.getNumaNode()).trim();
            if (!GpuDevice.State.Error.equals(device.getState())) {
                gpus.present.put(bus, node);
            }
            boolean releasing = releasingVmId != null && releasingVmId.equals(device.getVmId())
                    && GpuDevice.State.Allocated.equals(device.getState());
            if (GpuDevice.ManagedState.Managed.equals(device.getManagedState()) && (GpuDevice.State.Free.equals(device.getState()) || releasing)) {
                gpus.free.put(bus, node);
                gpus.usable.put(bus, device);
            }
        }
        return gpus;
    }

    /**
     * @return the address as dddd:bb:ss.f; a short bb:ss.f address, as lspci prints devices in PCI domain 0, is in
     *         domain 0. An address of neither form is returned trimmed and in lower case.
     */
    static String normalizeBusAddress(String busAddress) {
        String address = StringUtils.defaultString(busAddress).trim().toLowerCase(Locale.ROOT);
        String canonical = canonicalBusAddress(address);
        return canonical != null ? canonical : address;
    }

    /**
     * @return the address as dddd:bb:ss.f, or null when it is not a PCI address with or without its domain
     */
    static String canonicalBusAddress(String address) {
        Matcher full = FULL_BUS_ADDRESS.matcher(address);
        Matcher shortForm = SHORT_BUS_ADDRESS.matcher(address);
        int domain;
        int bus;
        int slot;
        int function;
        if (full.matches()) {
            domain = Integer.parseInt(full.group(1), 16);
            bus = Integer.parseInt(full.group(2), 16);
            slot = Integer.parseInt(full.group(3), 16);
            function = Integer.parseInt(full.group(4), 16);
        } else if (shortForm.matches()) {
            domain = 0;
            bus = Integer.parseInt(shortForm.group(1), 16);
            slot = Integer.parseInt(shortForm.group(2), 16);
            function = Integer.parseInt(shortForm.group(3), 16);
        } else {
            return null;
        }
        if (bus > 0xff || slot > 0x1f || function > 7) {
            return null;
        }
        return String.format("%04x:%02x:%02x.%x", domain, bus, slot, function);
    }

    protected AllocationAlgorithm getAllocationAlgorithm() {
        String value = GpuAllocationAlgorithm.value();
        try {
            return AllocationAlgorithm.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warn("Unknown {} value {}; using {}", GpuAllocationAlgorithm.key(), value, AllocationAlgorithm.bestfit);
            return AllocationAlgorithm.bestfit;
        }
    }

    /**
     * @return the devices of the chosen group in bus address order, or empty when the host has no usable group
     */
    private Optional<List<GpuDeviceVO>> chooseGroup(long hostId, long vgpuProfileId, int count) {
        HostGpus gpus = loadHostGpus(hostId, vgpuProfileId, null);
        if (gpus == null) {
            logger.debug("Host {} has no registered GPU group rule and offers no GPU group", hostId);
            return Optional.empty();
        }
        return gpus.selector.choose(gpus.free, count, getAllocationAlgorithm(), random)
                .map(candidate -> candidate.getBuses().stream().map(gpus.usable::get).collect(Collectors.toList()));
    }

    @Override
    public Optional<GpuGroupPick> pickGroup(long hostId, long vgpuProfileId, int count) {
        return chooseGroup(hostId, vgpuProfileId, count).map(devices -> toPick(hostId, devices));
    }

    private static GpuGroupPick toPick(long hostId, List<GpuDeviceVO> devices) {
        return new GpuGroupPick(hostId, devices.stream().map(device -> normalizeBusAddress(device.getBusAddress())).collect(Collectors.toList()),
                devices.stream().map(GpuDeviceVO::getId).collect(Collectors.toList()));
    }

    @Override
    public int bestFitScore(long hostId, long vgpuProfileId, int count) {
        HostGpus gpus = loadHostGpus(hostId, vgpuProfileId, null);
        return gpus == null ? Integer.MAX_VALUE : gpus.selector.bestFitScore(gpus.free, count);
    }

    @Override
    public SlotCount slotCount(ServiceOffering serviceOffering, Long hostId) {
        Long vgpuProfileId = serviceOffering.getVgpuProfileId();
        if (vgpuProfileId == null || vgpuProfileId <= 0) {
            return new SlotCount(0, 0);
        }
        int count = serviceOffering.getGpuCount() != null ? serviceOffering.getGpuCount() : 1;
        List<Long> hostIds = hostId != null ? List.of(hostId)
                : hostGpuGroupRuleDao.listAll().stream().map(HostGpuGroupRuleVO::getHostId).collect(Collectors.toList());
        List<Long> zoneIds = serviceOfferingDetailsDao.findZoneIds(serviceOffering.getId());
        int available = 0;
        int total = 0;
        for (Long id : hostIds) {
            HostVO host = hostDao.findById(id);
            if (!offersGpuSlots(host, serviceOffering, zoneIds)) {
                continue;
            }
            HostGpus gpus = loadHostGpus(id, vgpuProfileId, null);
            if (gpus == null) {
                continue;
            }
            total += gpus.selector.slots(gpus.present, count);
            int free = gpus.selector.slots(gpus.free, count);
            if (free > 0 && localStorageClosed(host, serviceOffering)) {
                logger.debug("The local storage of {} has crossed a disable threshold; its {} free GPU slots are not available", host, free);
                free = 0;
            }
            available += free;
        }
        return new SlotCount(available, total);
    }

    private boolean offersGpuSlots(HostVO host, ServiceOffering serviceOffering, List<Long> zoneIds) {
        if (host == null || host.getType() != Host.Type.Routing || host.getStatus() != Status.Up
                || host.getResourceState() != ResourceState.Enabled) {
            return false;
        }
        if (CollectionUtils.isNotEmpty(zoneIds) && !zoneIds.contains(host.getDataCenterId())) {
            return false;
        }
        hostDao.loadHostTags(host);
        return host.checkHostServiceOfferingTags(serviceOffering);
    }

    /**
     * @return true when the offering keeps its disks on local storage and none of the host's local pools for it is
     *         under both disable thresholds
     */
    private boolean localStorageClosed(HostVO host, ServiceOffering serviceOffering) {
        DiskOfferingVO diskOffering = serviceOffering.getDiskOfferingId() != null ? diskOfferingDao.findById(serviceOffering.getDiskOfferingId()) : null;
        if (diskOffering == null || !diskOffering.isUseLocalStorage()) {
            return false;
        }
        List<StoragePoolVO> pools = primaryDataStoreDao.findLocalStoragePoolsByHostAndTags(host.getId(), diskOffering.getTagsArray());
        return pools.stream().allMatch(storageManager::storagePoolCrossedDisableThreshold);
    }

    @Override
    public GroupRule getGpuGroupRule(long hostId) {
        HostGpuGroupRuleVO row = hostGpuGroupRuleDao.findByHostId(hostId);
        if (row == null) {
            return null;
        }
        try {
            return GroupRule.valueOf(row.getRule());
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warn("Host {} has the unknown GPU group rule {} and offers no GPU group", hostId, row.getRule());
            return null;
        }
    }

    @Override
    public List<Set<String>> getGpuGroups(long hostId) {
        return registeredGroups(hostId, getGpuGroupRule(hostId)).stream().map(TreeSet::new).collect(Collectors.toList());
    }

    private List<List<String>> registeredGroups(long hostId, GroupRule rule) {
        if (rule != GroupRule.partitions) {
            return new ArrayList<>();
        }
        Map<Integer, TreeSet<String>> byIndex = new LinkedHashMap<>();
        for (HostGpuGroupMemberVO member : hostGpuGroupMemberDao.listByHostId(hostId)) {
            byIndex.computeIfAbsent(member.getGroupIndex(), index -> new TreeSet<>()).add(normalizeBusAddress(member.getBusAddress()));
        }
        return byIndex.values().stream().map(ArrayList::new).collect(Collectors.toList());
    }

    /**
     * @return the rule and, for rule partitions, its groups: each a list of lowercase bus addresses in ascending order,
     *         larger groups first
     * @throws IllegalArgumentException unless the JSON is an object with a known rule, and only rule partitions has
     *         groups, which are 1, 2, 4 or 8 distinct bus addresses, pairwise disjoint or nested, and not repeated
     */
    static Pair<GroupRule, List<List<String>>> parseGpuGroups(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(StringUtils.defaultString(json));
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("the GPU groups are not valid JSON");
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("the GPU groups must be a JSON object with a rule");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement ruleElement = object.get("rule");
        GroupRule rule = null;
        if (ruleElement != null && ruleElement.isJsonPrimitive() && ruleElement.getAsJsonPrimitive().isString()) {
            rule = Arrays.stream(GroupRule.values()).filter(r -> r.name().equals(ruleElement.getAsString())).findFirst().orElse(null);
        }
        if (rule == null) {
            throw new IllegalArgumentException(String.format("the rule must be one of %s", Arrays.toString(GroupRule.values())));
        }
        JsonElement groupsElement = object.get("groups");
        boolean noGroups = groupsElement == null || groupsElement.isJsonNull()
                || (groupsElement.isJsonArray() && groupsElement.getAsJsonArray().size() == 0);
        if (rule != GroupRule.partitions) {
            if (!noGroups) {
                throw new IllegalArgumentException(String.format("groups are only for rule partitions, not for rule %s", rule));
            }
            return new Pair<>(rule, new ArrayList<>());
        }
        if (noGroups || !groupsElement.isJsonArray()) {
            throw new IllegalArgumentException("rule partitions needs a non-empty array of groups");
        }
        List<List<String>> groups = new ArrayList<>();
        for (JsonElement groupElement : groupsElement.getAsJsonArray()) {
            groups.add(parseGroup(groupElement, groups));
        }
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                Set<String> a = new HashSet<>(groups.get(i));
                Set<String> b = new HashSet<>(groups.get(j));
                boolean overlap = a.stream().anyMatch(b::contains);
                if (overlap && !a.containsAll(b) && !b.containsAll(a)) {
                    throw new IllegalArgumentException(String.format("groups %s and %s overlap without one containing the other; groups must be laminar",
                            groups.get(i), groups.get(j)));
                }
            }
        }
        groups.sort(Comparator.<List<String>>comparingInt(List::size).reversed().thenComparing(group -> group.get(0)));
        return new Pair<>(rule, groups);
    }

    private static List<String> parseGroup(JsonElement groupElement, List<List<String>> earlier) {
        if (!groupElement.isJsonArray() || groupElement.getAsJsonArray().size() == 0) {
            throw new IllegalArgumentException(String.format("group %s is not a non-empty array of PCI bus addresses", groupElement));
        }
        TreeSet<String> members = new TreeSet<>();
        JsonArray array = groupElement.getAsJsonArray();
        for (JsonElement member : array) {
            if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(String.format("group %s holds %s, which is not a PCI bus address", groupElement, member));
            }
            String bus = canonicalBusAddress(member.getAsString().trim().toLowerCase(Locale.ROOT));
            if (bus == null) {
                throw new IllegalArgumentException(String.format("%s is not a PCI bus address like 0000:07:00.0 or 07:00.0", member.getAsString()));
            }
            if (!members.add(bus)) {
                throw new IllegalArgumentException(String.format("group %s names %s twice", groupElement, bus));
            }
        }
        if (!GROUP_SIZES.contains(members.size())) {
            throw new IllegalArgumentException(String.format("group %s has %d GPUs; a group has 1, 2, 4 or 8", groupElement, members.size()));
        }
        List<String> group = new ArrayList<>(members);
        if (earlier.contains(group)) {
            throw new IllegalArgumentException(String.format("group %s is given twice", group));
        }
        return group;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_GPU_GROUPS_UPDATE, eventDescription = "updating the GPU groups of a host")
    public HostGpuGroupsResponse updateHostGpuGroups(UpdateHostGpuGroupsCmd cmd) {
        HostVO host = hostDao.findById(cmd.getHostId());
        if (host == null) {
            throw new InvalidParameterValueException("Unable to find a host with ID " + cmd.getHostId());
        }
        if (host.getType() != Host.Type.Routing || !HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new InvalidParameterValueException(String.format("%s does not take GPU groups; only KVM hosts do", host));
        }
        Pair<GroupRule, List<List<String>>> registration;
        try {
            registration = parseGpuGroups(cmd.getGroups());
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid GPU groups: " + e.getMessage());
        }
        GroupRule rule = registration.first();
        List<List<String>> groups = registration.second();
        inTransaction(new TransactionCallbackWithException<Void, RuntimeException>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                gpuDeviceDao.lockByHostId(host.getId());
                checkGroupsAreEachOfOneCard(host, groups);
                hostGpuGroupMemberDao.removeByHostId(host.getId());
                HostGpuGroupRuleVO row = hostGpuGroupRuleDao.findByHostId(host.getId());
                if (row == null) {
                    hostGpuGroupRuleDao.persist(new HostGpuGroupRuleVO(host.getId(), rule.name()));
                } else {
                    row.setRule(rule.name());
                    hostGpuGroupRuleDao.update(row.getId(), row);
                }
                for (int index = 0; index < groups.size(); index++) {
                    for (String bus : groups.get(index)) {
                        hostGpuGroupMemberDao.persist(new HostGpuGroupMemberVO(host.getId(), index, bus));
                    }
                }
                return null;
            }
        });
        logger.info("Registered GPU group rule {} with {} groups for {}", rule, groups.size(), host);
        return createHostGpuGroupsResponse(host, rule, groups);
    }

    /**
     * A group may name positions the host has no device row for, such as a card under repair; such a group stays
     * unusable until the device appears. The devices that do exist must all be of one card.
     */
    private void checkGroupsAreEachOfOneCard(HostVO host, List<List<String>> groups) {
        Map<String, GpuDeviceVO> devices = new HashMap<>();
        for (GpuDeviceVO device : gpuDeviceDao.listByHostId(host.getId())) {
            devices.put(normalizeBusAddress(device.getBusAddress()), device);
        }
        for (List<String> group : groups) {
            if (group.stream().map(devices::get).filter(Objects::nonNull).map(GpuDeviceVO::getCardId).distinct().count() > 1) {
                throw new InvalidParameterValueException(String.format("Group %s of %s holds GPUs of more than one card", group, host));
            }
            List<String> absent = group.stream().filter(bus -> !devices.containsKey(bus)).collect(Collectors.toList());
            if (!absent.isEmpty()) {
                logger.info("{} has no GPU device at {} yet; group {} is unusable until it appears", host, absent, group);
            }
        }
    }

    @Override
    public ListResponse<HostGpuGroupsResponse> listHostGpuGroups(ListHostGpuGroupsCmd cmd) {
        List<HostGpuGroupRuleVO> rules;
        if (cmd.getHostId() != null) {
            HostVO host = hostDao.findById(cmd.getHostId());
            if (host == null) {
                throw new InvalidParameterValueException("Unable to find a host with ID " + cmd.getHostId());
            }
            HostGpuGroupRuleVO rule = hostGpuGroupRuleDao.findByHostId(host.getId());
            rules = rule != null ? List.of(rule) : new ArrayList<>();
        } else {
            rules = hostGpuGroupRuleDao.listAll();
        }
        List<HostGpuGroupsResponse> responses = new ArrayList<>();
        for (HostGpuGroupRuleVO row : rules) {
            HostVO host = hostDao.findById(row.getHostId());
            GroupRule rule = getGpuGroupRule(row.getHostId());
            if (host != null && rule != null) {
                responses.add(createHostGpuGroupsResponse(host, rule, registeredGroups(host.getId(), rule)));
            }
        }
        List<HostGpuGroupsResponse> page = com.cloud.utils.StringUtils.applyPagination(responses, cmd.getStartIndex(), cmd.getPageSizeVal());
        ListResponse<HostGpuGroupsResponse> response = new ListResponse<>();
        response.setResponses(page != null ? page : responses, responses.size());
        return response;
    }

    private HostGpuGroupsResponse createHostGpuGroupsResponse(HostVO host, GroupRule rule, List<List<String>> groups) {
        HostGpuGroupsResponse response = new HostGpuGroupsResponse();
        response.setHostId(host.getUuid());
        response.setHostName(host.getName());
        response.setRule(rule.name());
        if (rule == GroupRule.partitions) {
            response.setGroups(groups);
        }
        return response;
    }

    @Override
    public void removeHostGpuGroups(long hostId) {
        int members = hostGpuGroupMemberDao.removeByHostId(hostId);
        int rules = hostGpuGroupRuleDao.removeByHostId(hostId);
        logger.debug("Removed {} GPU group rule and {} group members of removed host {}", rules, members, hostId);
    }
}
