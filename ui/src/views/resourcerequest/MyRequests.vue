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
    <a-col v-if="!allowed" :md="24">
      <a-alert type="info" showIcon :message="$t('message.resource.limit.request.not.allowed')" />
    </a-col>
    <a-col v-else :md="24">
      <a-alert
        v-if="gpuTypesUnavailable"
        type="warning"
        showIcon
        :message="$t('message.resource.limit.request.gpu.types.unavailable')"
        style="margin-bottom: 12px" />
      <effective-quota :limits="limits" :gpuTypes="gpuTypes" style="margin-bottom: 12px" />
      <a-card :title="$t('label.resource.limit.request.mine')">
        <template #extra>
          <a-button
            type="primary"
            :disabled="hasPending || showForm"
            :title="hasPending ? $t('message.resource.limit.request.pending.exists') : ''"
            @click="openForm(null)">
            <template #icon><plus-outlined /></template>
            {{ $t('label.resource.limit.request.new') }}
          </a-button>
        </template>
        <request-form
          v-if="showForm"
          :key="formKey"
          :gpuTypes="gpuTypes || []"
          :prefill="prefill"
          style="margin-bottom: 16px"
          @done="onFormDone"
          @cancel="showForm = false" />
        <request-table
          class="request-list"
          :requests="requests"
          :loading="loading"
          :actionsFor="actionsFor"
          :pagination="pagination"
          @action="onAction" />
      </a-card>
    </a-col>
  </a-row>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import eventBus from '@/config/eventBus'
import { CHANGED_EVENT, fetchGpuTypes, renewalOf, requestsAllowed } from '@/utils/resourceLimitRequest'
import EffectiveQuota from '@/views/resourcerequest/EffectiveQuota'
import RequestForm from '@/views/resourcerequest/RequestForm'
import RequestTable from '@/views/resourcerequest/RequestTable'

export default {
  name: 'MyRequests',
  components: {
    Breadcrumb,
    EffectiveQuota,
    RequestForm,
    RequestTable
  },
  data () {
    return {
      loading: false,
      requests: [],
      limits: [],
      gpuTypes: null,
      gpuTypesUnavailable: false,
      showForm: false,
      formKey: 0,
      prefill: null
    }
  },
  computed: {
    allowed () {
      return requestsAllowed(this.$store.getters.features)
    },
    hasPending () {
      return this.requests.some(request => request.state === 'Pending')
    },
    pagination () {
      return { pageSize: this.$store.getters.defaultListViewPageSize || 20, hideOnSinglePage: true }
    }
  },
  created () {
    if (this.allowed) {
      this.fetchData()
    }
  },
  watch: {
    '$route.query.renew' (id) {
      if (id && !this.loading) {
        this.openRenewalFromRoute()
      }
    }
  },
  methods: {
    async fetchData () {
      if (!this.allowed) {
        return
      }
      this.loading = true
      const [requests, limits, gpuTypes] = await Promise.all([this.listRequests(), this.listLimits(), this.listGpuTypes()])
      this.requests = requests
      this.limits = limits
      this.gpuTypes = gpuTypes
      this.gpuTypesUnavailable = gpuTypes === null
      this.loading = false
      this.openRenewalFromRoute()
    },
    listRequests () {
      return getAPI('listResourceLimitRequests', {}).then(json => {
        return json.listresourcelimitrequestsresponse.resourcelimitrequest || []
      }).catch(error => {
        this.$notifyError(error)
        return []
      })
    },
    listLimits () {
      if (!('listResourceLimits' in this.$store.getters.apis)) {
        return Promise.resolve([])
      }
      return getAPI('listResourceLimits', {}).then(json => {
        return json.listresourcelimitsresponse.resourcelimit || []
      }).catch(error => {
        this.$notifyError(error)
        return []
      })
    },
    listGpuTypes () {
      if (!('listServiceOfferings' in this.$store.getters.apis)) {
        return Promise.resolve(null)
      }
      return fetchGpuTypes(getAPI).catch(error => {
        this.$notifyError(error)
        return null
      })
    },
    actionsFor (record) {
      const renew = {
        name: 'renew',
        primary: true,
        disabled: this.hasPending,
        title: this.hasPending ? this.$t('message.resource.limit.request.pending.exists') : ''
      }
      switch (record.state) {
        case 'Pending':
          return [{ name: 'cancel', danger: true }]
        case 'Approved':
          return [renew, { name: 'giveup', danger: true }]
        case 'Expired':
          return [renew]
      }
      return []
    },
    onAction ({ name, record }) {
      if (name === 'renew') {
        this.openForm(renewalOf(record))
        return
      }
      this.$confirm({
        title: this.$t(name === 'cancel' ? 'message.resource.limit.request.confirm.cancel' : 'message.resource.limit.request.confirm.giveup'),
        okText: this.$t('label.ok'),
        cancelText: this.$t('label.cancel'),
        onOk: () => this.cancelRequest(record)
      })
    },
    cancelRequest (record) {
      return postAPI('cancelResourceLimitRequest', { id: record.id }).then(() => {
        this.$message.success(this.$t('message.resource.limit.request.cancelled'))
        eventBus.emit(CHANGED_EVENT)
        return this.fetchData()
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    openForm (prefill) {
      this.prefill = prefill
      this.formKey++
      this.showForm = true
    },
    onFormDone () {
      this.showForm = false
      this.fetchData()
    },
    openRenewalFromRoute () {
      const id = this.$route.query.renew
      if (!id) {
        return
      }
      this.$router.replace({ path: this.$route.path })
      const grant = this.requests.find(request => request.id === id)
      if (!grant || !['Approved', 'Expired'].includes(grant.state)) {
        return
      }
      if (this.hasPending) {
        this.$message.info(this.$t('message.resource.limit.request.pending.exists'))
        return
      }
      this.openForm(renewalOf(grant))
    }
  }
}
</script>
