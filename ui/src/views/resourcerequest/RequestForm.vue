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
  <a-form
    class="request-form"
    layout="vertical"
    :model="form"
    :rules="rules"
    @finish="submit">
    <a-row :gutter="16">
      <a-col :xs="24" :md="8">
        <a-form-item name="cpu" :label="$t('label.resource.limit.request.cpu')">
          <a-input-number v-model:value="form.cpu" :min="0" :precision="0" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :xs="24" :md="8">
        <a-form-item name="memoryGib" :label="$t('label.resource.limit.request.memory.gib')">
          <a-input-number v-model:value="form.memoryGib" :min="0" style="width: 100%" />
        </a-form-item>
      </a-col>
      <a-col :xs="24" :md="8">
        <a-form-item name="storage" :label="$t('label.resource.limit.request.storage.gib')">
          <a-input-number v-model:value="form.storage" :min="0" :precision="0" style="width: 100%" />
        </a-form-item>
      </a-col>
    </a-row>
    <a-form-item v-if="gpuKeys.length > 0" :label="$t('label.resource.limit.request.gpus')">
      <a-row :gutter="16">
        <a-col v-for="type in gpuKeys" :key="type" :xs="12" :md="6">
          <div class="request-form-gpu-type">{{ type }}</div>
          <a-input-number v-model:value="form.gpu[type]" :min="0" :precision="0" style="width: 100%" />
        </a-col>
      </a-row>
      <div class="request-form-hint">{{ $t('message.resource.limit.request.gpu.help') }}</div>
    </a-form-item>
    <a-form-item name="expiry" :label="$t('label.resource.limit.request.expiry')">
      <a-date-picker
        v-model:value="form.expiry"
        valueFormat="YYYY-MM-DD"
        :disabledDate="disabledDate"
        :allowClear="!policy.required"
        style="width: 100%" />
      <div class="request-form-hint">{{ $t('message.resource.limit.request.expiry.help', { timezone: policy.timezone }) }}</div>
    </a-form-item>
    <a-form-item name="reason" :label="$t('label.reason')">
      <a-textarea v-model:value="form.reason" :maxlength="1024" :autoSize="{ minRows: 2, maxRows: 6 }" />
    </a-form-item>
    <a-space>
      <a-button type="primary" html-type="submit" :loading="loading">{{ $t('label.submit') }}</a-button>
      <a-button @click="$emit('cancel')">{{ $t('label.cancel') }}</a-button>
    </a-space>
  </a-form>
</template>

<script>
import { postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { CHANGED_EVENT, expiryPolicy, formValuesOf, outsideExpiryWindow, requestParams } from '@/utils/resourceLimitRequest'

export default {
  name: 'RequestForm',
  props: {
    gpuTypes: {
      type: Array,
      default: () => []
    },
    prefill: {
      type: Object,
      default: null
    }
  },
  emits: ['done', 'cancel'],
  data () {
    const policy = expiryPolicy(this.$store.getters.features)
    const values = this.prefill ? formValuesOf(this.prefill) : { cpu: 0, memoryGib: 0, storage: 0, gpu: {} }
    return {
      policy,
      loading: false,
      form: {
        ...values,
        expiry: policy.defaultDay,
        reason: this.prefill ? (this.prefill.reason || '') : ''
      }
    }
  },
  computed: {
    gpuKeys () {
      return [...new Set([...this.gpuTypes, ...Object.keys(this.form.gpu)])].sort()
    },
    rules () {
      return {
        expiry: [{ required: this.policy.required, message: this.$t('message.resource.limit.request.expiry.required') }]
      }
    }
  },
  watch: {
    gpuTypes: {
      immediate: true,
      handler (types) {
        types.forEach(type => {
          if (this.form.gpu[type] === undefined) {
            this.form.gpu[type] = 0
          }
        })
      }
    }
  },
  methods: {
    disabledDate (current) {
      return outsideExpiryWindow(this.policy, current)
    },
    submit () {
      this.loading = true
      postAPI('createResourceLimitRequest', requestParams(this.form)).then(() => {
        this.$message.success(this.$t('message.resource.limit.request.created'))
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
.request-form-gpu-type {
  opacity: 0.65;
}

.request-form-hint {
  margin-top: 4px;
  opacity: 0.65;
}
</style>
