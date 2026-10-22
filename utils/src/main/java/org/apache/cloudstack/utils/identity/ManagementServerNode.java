//
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
//

package org.apache.cloudstack.utils.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ComponentLifecycle;
import com.cloud.utils.component.SystemIntegrityChecker;
import com.cloud.utils.exception.CloudRuntimeException;

public class ManagementServerNode extends AdapterBase implements SystemIntegrityChecker {

    static final Path HOST_NAME_FILE = Paths.get("/proc/sys/kernel/hostname");

    private static final long s_nodeId = nodeIdOfHostNameIn(HOST_NAME_FILE);

    /**
     * A node keeps its host name across restarts, while its interfaces and their MAC addresses may be recreated; host
     * names must therefore be unique among the nodes sharing a database. The id stays within the 48-bit range of the
     * MAC-derived ids it replaces.
     */
    static long nodeIdOf(String hostName) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(hostName.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("SHA-256 is unavailable", e);
        }
        long id = 0;
        for (int i = 0; i < 6; i++) {
            id = (id << 8) | (digest[i] & 0xff);
        }
        return id != 0 ? id : 1;
    }

    /**
     * The host name the Linux kernel holds, read from its host name file as it is set rather than resolved, so that the
     * id cannot depend on name resolution. The management server runs on Linux; there is no other source.
     * @return the id of that host name, or 0 when there is none
     */
    static long nodeIdOfHostNameIn(Path hostNameFile) {
        try {
            String hostName = new String(Files.readAllBytes(hostNameFile), StandardCharsets.UTF_8).trim();
            return hostName.isEmpty() ? 0 : nodeIdOf(hostName);
        } catch (IOException e) {
            return 0;
        }
    }

    public ManagementServerNode() {
        setRunLevel(ComponentLifecycle.RUN_LEVEL_FRAMEWORK_BOOTSTRAP);
    }

    @Override
    public void check() {
        if (s_nodeId <= 0) {
            throw new CloudRuntimeException("Unable to get the management server node id: no host name could be read from the Linux kernel host name file " + HOST_NAME_FILE);
        }
    }

    public static long getManagementServerId() {
        return s_nodeId;
    }

    @Override
    public boolean start() {
        try {
            check();
        } catch (Exception e) {
            logger.error("System integrity check exception", e);
            System.exit(1);
        }
        return true;
    }
}
