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

<template>
  <a-modal
    :visible="visible"
    :title="$t('label.instance.access.title')"
    :closable="false"
    :maskClosable="false"
    :keyboard="false"
    :footer="null"
    :width="560">
    <div class="instance-access" v-if="current">
      <p class="instance-access-name">{{ current.name }}</p>
      <div class="instance-access-item" v-if="current.sshCommand">
        <div class="instance-access-label">{{ $t('label.ssh.access') }}</div>
        <div class="instance-access-value">
          <code class="instance-access-command">{{ current.sshCommand }}</code>
          <tooltip-button
            tooltipPlacement="top"
            :tooltip="$t('label.copy')"
            icon="CopyOutlined"
            type="dashed"
            size="small"
            :copyResource="current.sshCommand"
            @onClick="$message.success($t('label.copied.clipboard'))" />
        </div>
      </div>
      <div class="instance-access-item" v-if="current.account">
        <div class="instance-access-label">{{ $t('label.login.account') }}</div>
        <div class="instance-access-value">
          <code class="instance-access-account">{{ current.account }}</code>
        </div>
        <p class="instance-access-help">{{ $t('message.login.account') }}</p>
      </div>
      <div class="instance-access-item" v-if="current.password">
        <div class="instance-access-label">{{ $t('label.instance.password') }}</div>
        <div class="instance-access-value">
          <code class="instance-access-password">{{ current.password }}</code>
          <tooltip-button
            tooltipPlacement="top"
            :tooltip="$t('label.copy.password')"
            icon="CopyOutlined"
            type="dashed"
            size="small"
            :copyResource="current.password"
            @onClick="$message.success($t('label.copied.clipboard'))" />
        </div>
        <p class="instance-access-help">{{ $t('message.instance.password') }}</p>
      </div>
      <div class="instance-access-actions">
        <a-button class="instance-access-ack" type="primary" @click="acknowledge">{{ $t('label.instance.access.ack') }}</a-button>
      </div>
    </div>
  </a-modal>
</template>

<script>
import eventBus from '@/config/eventBus'
import TooltipButton from '@/components/widgets/TooltipButton'

export default {
  name: 'InstanceAccessModal',
  components: {
    TooltipButton
  },
  data () {
    return {
      queue: []
    }
  },
  computed: {
    current () {
      return this.queue[0] || null
    },
    visible () {
      return this.current !== null
    }
  },
  created () {
    eventBus.on('instance-access', this.enqueue)
  },
  beforeUnmount () {
    eventBus.off('instance-access', this.enqueue)
  },
  methods: {
    enqueue (access) {
      this.queue.push(access)
    },
    acknowledge () {
      this.queue.shift()
    }
  }
}
</script>

<style lang="less" scoped>
.instance-access-name {
  font-weight: bold;
}

.instance-access-item {
  margin-bottom: 16px;
}

.instance-access-label {
  margin-bottom: 4px;
  font-weight: bold;
}

.instance-access-value {
  display: flex;
  align-items: center;
  word-break: break-all;

  button {
    margin-left: 10px;
    flex-shrink: 0;
  }
}

.instance-access-command,
.instance-access-account,
.instance-access-password {
  font-family: monospace;
}

.instance-access-help {
  margin: 4px 0 0;
  opacity: 0.65;
  font-size: 12px;
}

.instance-access-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
