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

package org.apache.cloudstack.resourcelimit.request;

import java.util.Map;
import java.util.TreeMap;

import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.resourcelimit.ResourceLimitGrantService;
import org.apache.cloudstack.resourcelimit.request.api.command.ApproveResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.CreateResourceLimitRequestCmd;
import org.apache.cloudstack.resourcelimit.request.api.command.ListResourceLimitRequestsCmd;
import org.apache.cloudstack.resourcelimit.request.api.response.ResourceLimitRequestResponse;
import org.apache.cloudstack.resourcelimit.request.vo.ResourceLimitRequestVO;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.component.PluggableService;

/**
 * Requests of accounts for CPU, memory, storage and GPUs per GPU type, decided by the accounts whose role rules allow
 * {@link ResourceLimitGrantService#APPROVE_API}. Values are vCPUs and GPUs counted, memory in MiB and storage in GiB;
 * the storage is the one quota of volumes and snapshots together.
 */
public interface ResourceLimitRequestService extends ResourceLimitGrantService, PluggableService {

    String CPU = "cpu";
    String MEMORY = "memory";
    String STORAGE = "storage";
    String GPU = "gpu";
    String GPU_TYPE = "type";
    String GPU_COUNT = "count";
    String EXPIRY = "expiry";
    String REASON = "reason";
    String COMMENT = "comment";

    ResourceLimitRequestVO createRequest(CreateResourceLimitRequestCmd cmd);

    ListResponse<ResourceLimitRequestResponse> listRequests(ListResourceLimitRequestsCmd cmd);

    ResourceLimitRequestVO cancelRequest(long id);

    ResourceLimitRequestVO approveRequest(ApproveResourceLimitRequestCmd cmd);

    ResourceLimitRequestVO rejectRequest(long id, String reason);

    ResourceLimitRequestVO revokeRequest(long id, String reason);

    ResourceLimitRequestResponse createResponse(ResourceLimitRequestVO request);

    /**
     * Reads a GPU count map parameter given as {@code gpu[i].type} and {@code gpu[i].count}.
     *
     * @return the counts per GPU type tag, or null when the parameter was not given
     */
    static Map<String, Long> gpuCounts(Map<String, Map<String, String>> parameter) {
        if (parameter == null) {
            return null;
        }
        Map<String, Long> counts = new TreeMap<>();
        for (Map<String, String> entry : parameter.values()) {
            String type = entry.get(GPU_TYPE);
            String count = entry.get(GPU_COUNT);
            if (type == null || type.trim().isEmpty() || count == null) {
                throw new InvalidParameterValueException("Each gpu entry needs a type and a count");
            }
            long value;
            try {
                value = Long.parseLong(count.trim());
            } catch (NumberFormatException e) {
                throw new InvalidParameterValueException(String.format("The GPU count %s of type %s is not a number", count, type));
            }
            if (counts.put(type.trim(), value) != null) {
                throw new InvalidParameterValueException(String.format("GPU type %s is given twice", type));
            }
        }
        return counts;
    }
}
