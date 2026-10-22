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
  <a-table
    size="small"
    :columns="columns"
    :dataSource="requests"
    :rowKey="record => record.id"
    :loading="loading"
    :pagination="pagination"
    @change="(page) => $emit('page-change', page)">
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'account'">
        <div>{{ record.account }}</div>
        <div class="request-muted">{{ record.domain }}</div>
      </template>
      <a-tag v-else-if="column.key === 'state'" :color="stateColors[record.state]">
        {{ $t('label.resource.limit.request.state.' + String(record.state).toLowerCase()) }}
      </a-tag>
      <request-values v-else-if="column.key === 'requested'" :values="requestedValues(record)" />
      <request-values v-else-if="column.key === 'granted'" :values="grantedValues(record) || {}" />
      <span v-else-if="column.key === 'expiry'">{{ expiryOf(record) }}</span>
      <template v-else-if="column.key === 'note'">
        <div>{{ record.reason }}</div>
        <div v-if="record.approver && record.state !== 'Pending'" class="request-muted">
          {{ record.approver }}<span v-if="record.comment">: {{ record.comment }}</span>
        </div>
      </template>
      <span v-else-if="column.key === 'created'">{{ $toLocaleDate(record.created) }}</span>
      <a-space v-else-if="column.key === 'actions'">
        <a-button
          v-for="action in actionsFor(record)"
          :key="action.name"
          size="small"
          :type="action.primary ? 'primary' : 'default'"
          :danger="action.danger"
          :disabled="action.disabled"
          :title="action.title"
          @click="$emit('action', { name: action.name, record })">
          {{ $t('label.resource.limit.request.' + action.name) }}
        </a-button>
      </a-space>
    </template>
  </a-table>
</template>

<script>
import { dayInZone, grantedValues, requestedValues } from '@/utils/resourceLimitRequest'
import RequestValues from '@/views/resourcerequest/RequestValues'

export default {
  name: 'RequestTable',
  components: {
    RequestValues
  },
  props: {
    requests: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    showAccount: {
      type: Boolean,
      default: false
    },
    actionsFor: {
      type: Function,
      default: () => []
    },
    pagination: {
      type: [Object, Boolean],
      default: false
    }
  },
  emits: ['action', 'page-change'],
  data () {
    return {
      stateColors: {
        Pending: 'orange',
        Approved: 'green',
        Rejected: 'red',
        Cancelled: 'default',
        Expired: 'default',
        Revoked: 'volcano'
      }
    }
  },
  computed: {
    columns () {
      const columns = []
      if (this.showAccount) {
        columns.push({ key: 'account', title: this.$t('label.account') })
      }
      columns.push(
        { key: 'state', title: this.$t('label.state') },
        { key: 'requested', title: this.$t('label.resource.limit.request.requested') },
        { key: 'granted', title: this.$t('label.resource.limit.request.granted') },
        { key: 'expiry', title: this.$t('label.resource.limit.request.expiry') },
        { key: 'note', title: this.$t('label.resource.limit.request.note') },
        { key: 'created', title: this.$t('label.created') },
        { key: 'actions', title: this.$t('label.actions') }
      )
      return columns
    }
  },
  methods: {
    requestedValues,
    grantedValues,
    expiryOf (record) {
      const end = record.approvedexpiry || record.expiry
      if (!end) {
        return this.$t('label.resource.limit.request.no.expiry')
      }
      return dayInZone(end, (this.$store.getters.features || {}).resourcerequestexpirytimezone)
    }
  }
}
</script>

<style lang="less" scoped>
.request-muted {
  opacity: 0.65;
}
</style>
