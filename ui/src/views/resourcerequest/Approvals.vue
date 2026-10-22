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
  <div>
    <a-row :gutter="12">
      <a-col :md="24">
        <a-card class="breadcrumb-card">
          <a-col :md="24" style="display: flex">
            <breadcrumb style="padding-top: 6px; padding-left: 8px" />
            <a-button
              style="margin-left: 12px; margin-top: 4px"
              :loading="loading"
              size="small"
              shape="round"
              @click="fetchData">
              <template #icon><reload-outlined /></template>
              {{ $t('label.refresh') }}
            </a-button>
          </a-col>
        </a-card>
      </a-col>
      <a-col :md="24">
        <a-card :title="$t('label.resource.limit.approvals')">
          <template #extra>
            <a-radio-group v-model:value="filter" button-style="solid" size="small" @change="onFilter">
              <a-radio-button v-for="option in filters" :key="option" :value="option">
                {{ option === 'All' ? $t('label.resource.limit.request.filter.all') : $t('label.resource.limit.request.state.' + option.toLowerCase()) }}
              </a-radio-button>
            </a-radio-group>
          </template>
          <request-table
            class="approval-list"
            showAccount
            :requests="requests"
            :loading="loading"
            :actionsFor="record => actionsFor(record, false)"
            :pagination="pagination"
            @action="onAction"
            @page-change="onPage" />
        </a-card>
      </a-col>
    </a-row>
    <a-drawer
      :visible="historyOf !== null"
      :title="historyOf ? $t('label.resource.limit.request.history.of', { account: historyOf.account }) : ''"
      width="90%"
      @close="historyOf = null">
      <request-table
        class="history-list"
        :requests="history"
        :loading="historyLoading"
        :actionsFor="record => actionsFor(record, true)"
        :pagination="{ pageSize: 20, hideOnSinglePage: true }"
        @action="onAction" />
    </a-drawer>
    <approve-request-modal
      :request="approving"
      :gpuTypes="gpuTypes"
      @done="onDecided"
      @cancel="approving = null" />
    <decision-modal
      :request="deciding"
      :kind="decision"
      @done="onDecided"
      @cancel="deciding = null" />
  </div>
</template>

<script>
import { getAPI } from '@/api'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import { STATES, fetchGpuTypes } from '@/utils/resourceLimitRequest'
import ApproveRequestModal from '@/views/resourcerequest/ApproveRequestModal'
import DecisionModal from '@/views/resourcerequest/DecisionModal'
import RequestTable from '@/views/resourcerequest/RequestTable'

export default {
  name: 'Approvals',
  components: {
    ApproveRequestModal,
    Breadcrumb,
    DecisionModal,
    RequestTable
  },
  data () {
    return {
      filters: [...STATES, 'All'],
      filter: 'Pending',
      loading: false,
      requests: [],
      total: 0,
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize || 20,
      gpuTypes: [],
      approving: null,
      deciding: null,
      decision: 'reject',
      historyOf: null,
      history: [],
      historyLoading: false
    }
  },
  computed: {
    pagination () {
      return {
        current: this.page,
        pageSize: this.pageSize,
        total: this.total,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50', '100']
      }
    }
  },
  created () {
    this.fetchData()
    this.loadGpuTypes()
  },
  methods: {
    fetchData () {
      this.loading = true
      const params = { listall: true, page: this.page, pagesize: this.pageSize }
      if (this.filter !== 'All') {
        params.state = this.filter
      }
      return getAPI('listResourceLimitRequests', params).then(json => {
        const response = json.listresourcelimitrequestsresponse
        this.requests = response.resourcelimitrequest || []
        this.total = response.count || 0
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    loadGpuTypes () {
      if (!('listServiceOfferings' in this.$store.getters.apis)) {
        return Promise.resolve()
      }
      return fetchGpuTypes(getAPI).then(types => {
        this.gpuTypes = types
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    fetchHistory () {
      this.historyLoading = true
      const { account, domainid } = this.historyOf
      return getAPI('listResourceLimitRequests', { account, domainid, listall: true }).then(json => {
        this.history = json.listresourcelimitrequestsresponse.resourcelimitrequest || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.historyLoading = false
      })
    },
    onFilter () {
      this.page = 1
      this.fetchData()
    },
    onPage ({ current, pageSize }) {
      this.page = current
      this.pageSize = pageSize
      this.fetchData()
    },
    // No account decides its own request: an owner withdraws a pending one and gives up a grant on "My requests".
    actionsFor (record, inHistory) {
      const history = inHistory ? [] : [{ name: 'history' }]
      const mine = (this.$store.getters.userInfo || {}).accountid
      if (mine && record.accountid === mine) {
        return history
      }
      switch (record.state) {
        case 'Pending':
          return [{ name: 'approve', primary: true }, { name: 'reject', danger: true }, ...history]
        case 'Approved':
          return [{ name: 'revoke', danger: true }, ...history]
      }
      return history
    },
    onAction ({ name, record }) {
      switch (name) {
        case 'approve':
          this.approving = record
          break
        case 'reject':
        case 'revoke':
          this.decision = name
          this.deciding = record
          break
        case 'history':
          this.historyOf = record
          this.history = []
          this.fetchHistory()
          break
      }
    },
    onDecided () {
      this.approving = null
      this.deciding = null
      this.fetchData()
      if (this.historyOf) {
        this.fetchHistory()
      }
    }
  }
}
</script>
