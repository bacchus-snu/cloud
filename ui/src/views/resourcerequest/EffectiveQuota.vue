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
  <a-card class="effective-quota" size="small" :title="$t('label.resource.limit.request.effective')">
    <a-row :gutter="[16, 12]">
      <a-col v-for="item in items" :key="item.key" :xs="12" :md="6" :data-quota="item.key">
        <div class="quota-label">{{ item.label }}</div>
        <div class="quota-value">{{ item.value }}</div>
      </a-col>
    </a-row>
  </a-card>
</template>

<script>
import { effectiveQuota, formatGib } from '@/utils/resourceLimitRequest'

export default {
  name: 'EffectiveQuota',
  props: {
    limits: {
      type: Array,
      default: () => []
    },
    gpuTypes: {
      type: Array,
      default: null
    }
  },
  computed: {
    quota () {
      return effectiveQuota(this.limits, this.gpuTypes)
    },
    items () {
      const quota = this.quota
      const items = [
        { key: 'cpu', label: this.$t('label.resource.limit.request.cpu'), value: this.show(quota.cpu) },
        { key: 'memory', label: this.$t('label.resource.limit.request.memory'), value: this.show(quota.memory, formatGib, 'GiB') },
        { key: 'storage', label: this.$t('label.resource.limit.request.storage'), value: this.show(quota.storage, String, 'GiB') }
      ]
      quota.gpu.forEach(gpu => items.push({
        key: 'gpu:' + gpu.type,
        label: this.$t('label.resource.limit.request.gpu.type', { type: gpu.type }),
        value: this.show(gpu.max)
      }))
      return items
    }
  },
  methods: {
    show (max, format = String, unit = '') {
      if (max === null) {
        return '—'
      }
      if (max < 0) {
        return this.$t('label.unlimited')
      }
      return unit ? format(max) + ' ' + unit : format(max)
    }
  }
}
</script>

<style lang="less" scoped>
.quota-label {
  opacity: 0.65;
}

.quota-value {
  font-size: 20px;
  font-weight: 500;
}
</style>
