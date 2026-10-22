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
  <span class="request-values">
    <template v-if="items.length > 0">
      <span v-for="item in items" :key="item.key + (item.type || '')" class="request-value">{{ label(item) }}</span>
    </template>
    <span v-else>&mdash;</span>
  </span>
</template>

<script>
import { valueItems } from '@/utils/resourceLimitRequest'

export default {
  name: 'RequestValues',
  props: {
    values: {
      type: Object,
      default: () => ({})
    }
  },
  computed: {
    items () {
      return valueItems(this.values || {})
    }
  },
  methods: {
    label (item) {
      return this.$t('label.resource.limit.request.value.' + item.key, { n: item.n, type: item.type })
    }
  }
}
</script>

<style lang="less" scoped>
.request-value {
  display: inline-block;
  margin-right: 12px;
  white-space: nowrap;
}
</style>
