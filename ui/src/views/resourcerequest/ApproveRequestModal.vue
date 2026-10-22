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
    :visible="request !== null"
    :title="title"
    :okText="$t('label.resource.limit.request.approve')"
    :cancelText="$t('label.cancel')"
    :confirmLoading="loading"
    :maskClosable="false"
    width="680px"
    @ok="submit"
    @cancel="$emit('cancel')">
    <template v-if="request">
      <p v-if="request.reason" class="approve-reason">{{ request.reason }}</p>
      <table class="approve-grid">
        <thead>
          <tr>
            <th></th>
            <th>{{ $t('label.resource.limit.request.requested') }}</th>
            <th>{{ $t('label.resource.limit.request.to.approve') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr data-field="cpu">
            <td>{{ $t('label.resource.limit.request.cpu') }}</td>
            <td>{{ request.cpu }}</td>
            <td><a-input-number v-model:value="form.cpu" :min="0" :precision="0" /></td>
          </tr>
          <tr data-field="memory">
            <td>{{ $t('label.resource.limit.request.memory.gib') }}</td>
            <td>{{ requestedMemory }}</td>
            <td><a-input-number v-model:value="form.memoryGib" :min="0" /></td>
          </tr>
          <tr data-field="storage">
            <td>{{ $t('label.resource.limit.request.storage.gib') }}</td>
            <td>{{ request.storage }}</td>
            <td><a-input-number v-model:value="form.storage" :min="0" :precision="0" /></td>
          </tr>
          <tr v-for="type in gpuKeys" :key="type" :data-field="'gpu:' + type">
            <td>{{ $t('label.resource.limit.request.gpu.type', { type }) }}</td>
            <td>{{ (request.gpu || {})[type] || 0 }}</td>
            <td><a-input-number v-model:value="form.gpu[type]" :min="0" :precision="0" /></td>
          </tr>
          <tr data-field="expiry">
            <td>{{ $t('label.resource.limit.request.expiry') }}</td>
            <td>{{ requestedExpiry }}</td>
            <td>
              <a-date-picker
                v-model:value="form.expiry"
                valueFormat="YYYY-MM-DD"
                :disabledDate="disabledDate"
                :allowClear="!policy.required && !request.expiry" />
            </td>
          </tr>
        </tbody>
      </table>
      <div class="approve-hint">{{ $t('message.resource.limit.request.expiry.help', { timezone: policy.timezone }) }}</div>
      <div class="approve-comment">{{ $t('label.resource.limit.request.comment') }}</div>
      <a-textarea v-model:value="form.comment" :maxlength="1024" :autoSize="{ minRows: 2, maxRows: 6 }" />
    </template>
  </a-modal>
</template>

<script>
import { postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { CHANGED_EVENT, approvalParams, dayInZone, expiryPolicy, formatGib, formValuesOf, outsideExpiryWindow } from '@/utils/resourceLimitRequest'

export default {
  name: 'ApproveRequestModal',
  props: {
    request: {
      type: Object,
      default: null
    },
    gpuTypes: {
      type: Array,
      default: () => []
    }
  },
  emits: ['done', 'cancel'],
  data () {
    return {
      loading: false,
      policy: expiryPolicy(this.$store.getters.features),
      form: { cpu: 0, memoryGib: 0, storage: 0, gpu: {}, expiry: null, comment: '' }
    }
  },
  computed: {
    title () {
      return this.request ? this.$t('label.resource.limit.request.approve') + ' — ' + this.request.account : ''
    },
    gpuKeys () {
      return [...new Set([...this.gpuTypes, ...Object.keys(this.form.gpu), ...Object.keys((this.request || {}).gpu || {})])].sort()
    },
    requestedMemory () {
      return formatGib(this.request.memory)
    },
    requestedExpiry () {
      return this.request.expiry ? dayInZone(this.request.expiry, this.policy.timezone) : this.$t('label.resource.limit.request.no.expiry')
    }
  },
  watch: {
    request: {
      immediate: true,
      handler (request) {
        if (request) {
          this.start(request)
        }
      }
    }
  },
  methods: {
    start (request) {
      const policy = expiryPolicy(this.$store.getters.features)
      const asked = request.expiry ? dayInZone(request.expiry, policy.timezone) : null
      const usable = asked !== null && asked >= policy.minDay && (policy.maxDay === null || asked <= policy.maxDay)
      const gpu = formValuesOf(request).gpu
      this.gpuTypes.forEach(type => { gpu[type] = gpu[type] || 0 })
      this.policy = policy
      this.form = {
        ...formValuesOf({ cpu: request.cpu, memory: request.memory, storage: request.storage, gpu }),
        expiry: usable ? asked : policy.defaultDay,
        comment: ''
      }
    },
    disabledDate (current) {
      return outsideExpiryWindow(this.policy, current)
    },
    submit () {
      if (this.policy.required && !this.form.expiry) {
        this.$message.error(this.$t('message.resource.limit.request.expiry.required'))
        return Promise.resolve()
      }
      this.loading = true
      return postAPI('approveResourceLimitRequest', approvalParams(this.request.id, this.form)).then(() => {
        this.$message.success(this.$t('message.resource.limit.request.approved'))
        eventBus.emit(CHANGED_EVENT)
        this.$emit('done')
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style lang="less" scoped>
.approve-grid {
  width: 100%;

  th, td {
    padding: 4px 8px;
    text-align: left;
  }
}

.approve-reason,
.approve-hint {
  opacity: 0.65;
}

.approve-comment {
  margin-top: 16px;
}
</style>
