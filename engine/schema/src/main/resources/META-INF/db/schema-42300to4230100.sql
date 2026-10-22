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
