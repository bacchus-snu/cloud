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
package com.cloud.configuration;

public interface Resource {

    short RESOURCE_UNLIMITED = -1;
    String UNLIMITED = "Unlimited";

    /**
     * The meaning of each {@link ResourceType} ordinal, as API parameter descriptions (annotations need a constant).
     */
    String RESOURCE_TYPE_VALUES = "Values are 0 to 16. "
            + "0 - Instance. Number of Instances a user can create. "
            + "1 - IP. Number of public IP addresses a user can own. "
            + "2 - Volume. Number of disk volumes a user can create. "
            + "3 - Snapshot. Number of Snapshots a user can create. "
            + "4 - Template. Number of Templates that a user can register/create. "
            + "5 - Project. Number of Projects a user can create. "
            + "6 - Network. Number of guest Network a user can create. "
            + "7 - VPC. Number of VPC a user can create. "
            + "8 - CPU. Total number of CPU cores a user can use. "
            + "9 - Memory. Total Memory (in MB) a user can use. "
            + "10 - PrimaryStorage. Total primary storage space (in GiB) a user can use; while resource.limit.storage.combined is true, "
            + "the space of primary and secondary storage together. "
            + "11 - SecondaryStorage. Total secondary storage space (in GiB) a user can use; not checked while resource.limit.storage.combined is true. "
            + "12 - Backup. Number of Backups a user can create. "
            + "13 - BackupStorage. Total backup storage space (in GiB) a user can use. "
            + "14 - Bucket. Number of object storage Buckets a user can create. "
            + "15 - ObjectStorage. Total object storage space (in GiB) a user can use. "
            + "16 - GPU. Total number of GPUs a user can use. ";

    /**
     * {@link #RESOURCE_TYPE_VALUES} by {@link ResourceType} name.
     */
    String RESOURCE_TYPE_NAME_VALUES = "Values are: "
            + "user_vm - Instance. "
            + "public_ip - IP. "
            + "volume - Volume. "
            + "snapshot - Snapshot. "
            + "template - Template. "
            + "project - Project. "
            + "network - Network. "
            + "vpc - VPC. "
            + "cpu - CPU. "
            + "memory - Memory. "
            + "primary_storage - PrimaryStorage. "
            + "secondary_storage - SecondaryStorage. "
            + "backup - Backup. "
            + "backup_storage - BackupStorage. "
            + "bucket - Bucket. "
            + "object_storage - ObjectStorage. "
            + "gpu - GPU. "
            + "See the resourcetype parameter for their meaning. ";

    enum ResourceType { // All storage type resources are allocated_storage and not the physical storage.
        user_vm("user_vm", 0),
        public_ip("public_ip", 1),
        volume("volume", 2),
        snapshot("snapshot", 3),
        template("template", 4),
        project("project", 5),
        network("network", 6),
        vpc("vpc", 7),
        cpu("cpu", 8),
        memory("memory", 9),
        primary_storage("primary_storage", 10),
        secondary_storage("secondary_storage", 11),
        backup("backup", 12),
        backup_storage("backup_storage", 13),
        bucket("bucket", 14),
        object_storage("object_storage", 15),
        gpu("gpu", 16);

        private String name;
        private int ordinal;
        public static final long bytesToKiB = 1024;
        public static final long bytesToMiB = bytesToKiB * 1024;
        public static final long bytesToGiB = bytesToMiB * 1024;

        ResourceType(String name, int ordinal) {
            this.name = name;
            this.ordinal = ordinal;
        }

        public String getName() {
            return name;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public static ResourceType fromOrdinal(int ordinal) {
            for (ResourceType r : ResourceType.values()) {
                if (r.ordinal == ordinal) {
                    return r;
                }
            }
            return null;
        }

        public static Boolean isStorageType(ResourceType type) {
            return (type == primary_storage || type == secondary_storage || type == backup_storage || type == object_storage);
        }
    }

    public static class ResourceOwnerType {

        public static final ResourceOwnerType Account = new ResourceOwnerType("Account");
        public static final ResourceOwnerType Domain = new ResourceOwnerType("Domain");

        private String name;

        public ResourceOwnerType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    ResourceType getType();

    long getOwnerId();

    ResourceOwnerType getResourceOwnerType();
    String getTag();

}
