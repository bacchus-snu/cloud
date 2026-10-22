-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade from 4.23.0.0 to 4.23.0.100
--;

-- Whole cores of a host on which VMs without a vGPU profile run; a core is named by its lower thread id
CREATE TABLE IF NOT EXISTS `cloud`.`host_pool_core` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `core` int unsigned NOT NULL COMMENT 'lower thread id of the core',
  `sibling` int unsigned NOT NULL COMMENT 'the other thread id of the core',
  `node` int unsigned NOT NULL COMMENT 'NUMA node of the core',
  `created` datetime NOT NULL COMMENT 'date the core was registered',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_host_pool_core__host_id__core` (`host_id`, `core`),
  CONSTRAINT `fk_host_pool_core__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pool cores held by VMs, one row per core; a core is held by one VM at most and cannot be unregistered while held
CREATE TABLE IF NOT EXISTS `cloud`.`host_core_assignment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `core` int unsigned NOT NULL COMMENT 'lower thread id of the core',
  `vm_id` bigint unsigned NOT NULL COMMENT 'vm instance id',
  `created` datetime NOT NULL COMMENT 'date the core was assigned',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_host_core_assignment__host_id__core` (`host_id`, `core`),
  INDEX `i_host_core_assignment__vm_id` (`vm_id`),
  CONSTRAINT `fk_host_core_assignment__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_host_core_assignment__host_pool_core` FOREIGN KEY (`host_id`, `core`) REFERENCES `host_pool_core`(`host_id`, `core`) ON DELETE RESTRICT,
  CONSTRAINT `fk_host_core_assignment__vm_id` FOREIGN KEY (`vm_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- GPU group rule registered per host: partitions, any or single; a host without a rule offers no GPU group
CREATE TABLE IF NOT EXISTS `cloud`.`host_gpu_group_rule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `rule` varchar(16) NOT NULL COMMENT 'partitions, any or single',
  `created` datetime NOT NULL COMMENT 'date the rule was registered',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_host_gpu_group_rule__host_id` (`host_id`),
  CONSTRAINT `fk_host_gpu_group_rule__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- GPUs of the groups registered for a host with rule partitions, one row per GPU of a group; the GPUs of a group share its index
CREATE TABLE IF NOT EXISTS `cloud`.`host_gpu_group_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `group_index` int unsigned NOT NULL COMMENT 'index of the group in the host registration',
  `bus_address` varchar(255) NOT NULL COMMENT 'PCI bus address of the GPU',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_host_gpu_group_member__host_id__group_index__bus_address` (`host_id`, `group_index`, `bus_address`),
  CONSTRAINT `fk_host_gpu_group_member__host_gpu_group_rule` FOREIGN KEY (`host_id`) REFERENCES `host_gpu_group_rule`(`host_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Resource limit requests of accounts, and the grants made of them: an approved grant raises the account's limits until its expiry
CREATE TABLE IF NOT EXISTS `cloud`.`resource_limit_request` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `account_id` bigint unsigned NOT NULL COMMENT 'account the request is for',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain of the account',
  `cpu` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'requested vCPUs of Instances without a GPU',
  `memory_mib` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'requested memory of Instances without a GPU, in MiB',
  `storage_gib` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'requested storage of volumes and snapshots together, in GiB',
  `gpus` text COMMENT 'requested GPUs, a JSON object from GPU type tag to count',
  `reason` varchar(1024) COMMENT 'why the account asks',
  `requested_expiry` datetime COMMENT 'requested end of the grant; null for none',
  `state` varchar(16) NOT NULL COMMENT 'Pending, Approved, Rejected, Cancelled, Expired or Revoked',
  `approved_cpu` bigint unsigned COMMENT 'granted vCPUs of Instances without a GPU',
  `approved_memory_mib` bigint unsigned COMMENT 'granted memory of Instances without a GPU, in MiB',
  `approved_storage_gib` bigint unsigned COMMENT 'granted storage of volumes and snapshots together, in GiB',
  `approved_gpus` text COMMENT 'granted GPUs, a JSON object from GPU type tag to count',
  `approved_expiry` datetime COMMENT 'end of the grant; null for none',
  `approver_id` bigint unsigned COMMENT 'user who decided the request last',
  `decided_at` datetime COMMENT 'when the request was decided last',
  `comment` varchar(1024) COMMENT 'comment of the last decision',
  `expiry_warning_days` int unsigned COMMENT 'fewest days before approved_expiry for which the account has been warned',
  `created` datetime NOT NULL COMMENT 'date the request was made',
  `removed` datetime COMMENT 'date the request was removed',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_limit_request__uuid` (`uuid`),
  INDEX `i_resource_limit_request__account_id__state` (`account_id`, `state`),
  INDEX `i_resource_limit_request__state__approved_expiry` (`state`, `approved_expiry`),
  CONSTRAINT `fk_resource_limit_request__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_resource_limit_request__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_resource_limit_request__approver_id` FOREIGN KEY (`approver_id`) REFERENCES `user`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tag values up to 8192 characters (ResourceTag.VALUE_MAX_LENGTH)
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.resource_tags', 'value', 'value', 'varchar(8192)');

-- Guest OS types of current distributions
INSERT INTO `cloud`.`guest_os` (`uuid`, `category_id`, `display_name`, `created`)
    SELECT UUID(), `category`.`id`, `new_os`.`display_name`, now()
    FROM (SELECT 'Ubuntu' AS `category_name`, 'Ubuntu 26.04 LTS' AS `display_name`
          UNION ALL SELECT 'Debian', 'Debian GNU/Linux 13 (64-bit)'
          UNION ALL SELECT 'Rocky Linux', 'Rocky Linux 10'
          UNION ALL SELECT 'AlmaLinux', 'AlmaLinux 10'
          UNION ALL SELECT 'Other', 'Arch Linux'
          UNION ALL SELECT 'Other', 'Alpine Linux') `new_os`
    JOIN `cloud`.`guest_os_category` `category` ON `category`.`name` = `new_os`.`category_name` AND `category`.`removed` IS NULL
    WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`guest_os` `existing` WHERE `existing`.`display_name` = `new_os`.`display_name` AND `existing`.`removed` IS NULL);

-- Their KVM mappings, and those of the generic Fedora and openSUSE types, which had none
INSERT INTO `cloud`.`guest_os_hypervisor` (`uuid`, `hypervisor_type`, `hypervisor_version`, `guest_os_name`, `guest_os_id`, `created`, `is_user_defined`)
    SELECT UUID(), 'KVM', 'default', `guest_os`.`display_name`, `guest_os`.`id`, now(), 0
    FROM `cloud`.`guest_os`
    WHERE `guest_os`.`display_name` IN ('Ubuntu 26.04 LTS', 'Debian GNU/Linux 13 (64-bit)', 'Rocky Linux 10', 'AlmaLinux 10', 'Arch Linux', 'Alpine Linux',
                                        'Fedora Linux (64 bit)', 'OpenSUSE Linux (64 bit)')
      AND `guest_os`.`removed` IS NULL
      AND NOT EXISTS (SELECT 1 FROM `cloud`.`guest_os_hypervisor` `mapping`
                      WHERE `mapping`.`hypervisor_type` = 'KVM' AND `mapping`.`hypervisor_version` = 'default'
                        AND `mapping`.`guest_os_id` = `guest_os`.`id` AND `mapping`.`removed` IS NULL);
