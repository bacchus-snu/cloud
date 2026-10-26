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
  <a-form class="form" v-ctrl-enter="handleSubmit">
    <p v-html="getMessage()"></p>

    <div v-if="loading" class="loading">
      <loading-outlined style="color: #1890ff;" />
    </div>

    <compute-offering-selection
      :compute-items="offerings"
      :loading="loading"
      :rowCount="total"
      :gpuQuota="gpuQuota"
      size="small"
      @select-compute-item="($event) => updateComputeOffering($event)"
      @handle-search-filter="($event) => fetchData($event)" />

    <compute-selection
      v-if="selectedOffering && (selectedOffering.iscustomized || selectedOffering.iscustomizediops)"
      :cpu-number-input-decorator="cpuNumberKey"
      :cpu-speed-input-decorator="cpuSpeedKey"
      :memory-input-decorator="memoryKey"
      :computeOfferingId="selectedOffering.id"
      :isConstrained="'serviceofferingdetails' in selectedOffering"
      :cpuStep="cpuStep"
      :initialCpuValue="getInitialCpuValue()"
      :minCpu="getMinCpu()"
      :maxCpu="maxCpu"
      :cpuSpeed="getCPUSpeed()"
      :initialMemoryValue="getInitialMemoryValue()"
      :minMemory="getMinMemory()"
      :maxMemory="maxMemory"
      :isCustomized="selectedOffering.iscustomized"
      :isCustomizedIOps="'iscustomizediops' in selectedOffering && selectedOffering.iscustomizediops"
      @update-compute-cpunumber="updateFieldValue"
      @update-compute-cpuspeed="updateFieldValue"
      @update-compute-memory="updateFieldValue" />

    <disk-size-selection
      v-if="selectedDiskOffering && (selectedDiskOffering.iscustomized || selectedDiskOffering.iscustomizediops)"
      :inputDecorator="rootDiskSizeKey"
      :minDiskSize="minDiskSize"
      :rootDiskSelected="selectedDiskOffering"
      :isCustomized="selectedDiskOffering.iscustomized"
      @handler-error="handlerError"
      @update-disk-size="updateFieldValue"
      @update-root-disk-iops-value="updateIOPSValue"/>

    <placement-step
      v-if="showPlacement"
      :request="placementRequest"
      :zoneid="resource.zoneid"
      :virtualmachineid="resource.id"
      :capacityOfferingId="capacityOfferingId" />

    <a-form-item v-if="canAutoMigrate" :label="$t('label.automigrate.volume')">
      <template #label>
        <tooltip-label :title="$t('label.automigrate.volume')" :tooltip="apiParams.automigrate.description"/>
      </template>
      <a-switch
        v-model:checked="autoMigrate"
        @change="val => { autoMigrate = val }"/>
    </a-form-item>

    <div :span="24" class="action-button">
      <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
      <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
    </div>
  </a-form>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import ComputeOfferingSelection from '@views/compute/wizard/ComputeOfferingSelection'
import ComputeSelection from '@views/compute/wizard/ComputeSelection'
import DiskSizeSelection from '@views/compute/wizard/DiskSizeSelection'
import PlacementStep from '@views/compute/wizard/PlacementStep'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { automigrateAllowed, fetchCapabilities, vcpuMinimum, vcpuStep } from '@/utils/placement'
import { gpuRemaining, gpuTypeOf, limitTagsOf, loadQuota, remainingLimit } from '@/utils/limits'
import { instanceName } from '@/utils/instanceName'

export default {
  name: 'ScaleVM',
  components: {
    ComputeOfferingSelection,
    ComputeSelection,
    DiskSizeSelection,
    PlacementStep,
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      offeringsMap: {},
      offerings: [],
      selectedOffering: {},
      selectedDiskOffering: {},
      autoMigrate: false,
      total: 0,
      params: { id: this.resource.id },
      loading: false,
      cpuNumberKey: 'details[0].cpuNumber',
      cpuSpeedKey: 'details[0].cpuSpeed',
      memoryKey: 'details[0].memory',
      rootDiskSizeKey: 'details[0].rootdisksize',
      minIopsKey: 'details[0].minIops',
      maxIopsKey: 'details[0].maxIops',
      fixedOfferingKvm: false,
      minDiskSize: 0,
      capabilities: {},
      quota: null,
      currentOffering: null
    }
  },
  computed: {
    canAutoMigrate () {
      return automigrateAllowed(this.$store.getters.features)
    },
    cpuStep () {
      return vcpuStep(this.capabilities)
    },
    // The remaining GPU quota of each type, as the deployment wizard has it, plus the GPUs the instance holds now in their
    // own type: a change replaces them within the type, while a change across types or from an offering without a GPU
    // adds nothing back.
    gpuQuota () {
      const remaining = gpuRemaining(this.quota)
      const held = Number(this.resource.gpucount) || 0
      const type = held > 0 ? gpuTypeOf(this.currentOffering, remaining) : null
      if (type && remaining[type] !== null) {
        remaining[type] += held
      }
      return remaining
    },
    // Quota is the only maximum: what is left of it and what the instance already holds, since a change replaces the
    // instance's own amount. An instance on a GPU offering holds none of the CPU-only quota.
    maxCpu () {
      const offeringMax = 'serviceofferingdetails' in this.selectedOffering ? this.selectedOffering.serviceofferingdetails.maxcpunumber * 1 : Number.MAX_SAFE_INTEGER
      return this.boundedByQuota(offeringMax, 'cpu', this.resource.cpunumber)
    },
    maxMemory () {
      const offeringMax = 'serviceofferingdetails' in this.selectedOffering ? this.selectedOffering.serviceofferingdetails.maxmemory * 1 : Number.MAX_SAFE_INTEGER
      return this.boundedByQuota(offeringMax, 'memory', this.resource.memory)
    },
    // the root keeps its size on a change to a compute-only root; a size is offered only for a disk offering that takes one
    rootSizeApplies () {
      return !!this.selectedDiskOffering && this.selectedDiskOffering.iscustomized === true
    },
    showPlacement () {
      return this.resource.state === 'Stopped' && !!this.selectedOffering && !!this.selectedOffering.id
    },
    capacityOfferingId () {
      return this.showPlacement && !this.selectedOffering.gpucardname ? this.selectedOffering.id : ''
    },
    placementRequest () {
      const request = { virtualmachineid: this.resource.id, serviceofferingid: this.selectedOffering.id }
      if (this.selectedOffering.iscustomized) {
        if (this.params[this.cpuNumberKey]) {
          request[this.cpuNumberKey] = this.params[this.cpuNumberKey]
        }
        if (this.params[this.memoryKey]) {
          request[this.memoryKey] = this.params[this.memoryKey]
        }
      }
      return request
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('scaleVirtualMachine')
  },
  created () {
    this.fetchData({
      keyword: '',
      pageSize: 10,
      page: 1
    })
    fetchCapabilities(this.resource.domainid).then(capabilities => {
      this.capabilities = capabilities
    }).catch(() => {})
    loadQuota(this.resource, this.$store.getters.userInfo, this.$store.getters.apis).then(quota => {
      this.quota = quota
    })
    if (Number(this.resource.gpucount) > 0 && this.resource.serviceofferingid) {
      getAPI('listServiceOfferings', { id: this.resource.serviceofferingid, state: 'all' }).then(response => {
        this.currentOffering = response?.listserviceofferingsresponse?.serviceoffering?.[0] || null
      }).catch(() => {})
    }
  },
  methods: {
    boundedByQuota (offeringMax, type, own) {
      const tag = limitTagsOf(this.selectedOffering)[0]
      const remaining = remainingLimit(this.quota, type, tag)
      if (remaining === null) {
        return offeringMax
      }
      const held = Number(this.resource.gpucount) > 0 ? 0 : Number(own) || 0
      return Math.min(offeringMax, remaining + held)
    },
    fetchData (options) {
      this.loading = true
      this.total = 0
      this.offerings = []
      this.offeringsMap = []
      getAPI('listServiceOfferings', {
        virtualmachineid: this.resource.id,
        keyword: options.keyword,
        page: options.page,
        pageSize: options.pageSize,
        gpuenabled: options.gpuenabled,
        details: 'min',
        response: 'json'
      }).then(response => {
        this.total = response.listserviceofferingsresponse.count
        if (this.total === 0) {
          return
        }
        this.offerings = response.listserviceofferingsresponse.serviceoffering || []
        this.offerings.forEach(offering => { this.offeringsMap[offering.id] = offering })
      }).finally(() => {
        this.loading = false
      })
    },
    getMinCpu () {
      return vcpuMinimum(this.selectedOffering?.serviceofferingdetails?.mincpunumber * 1 || 1, this.cpuStep)
    },
    getInitialCpuValue () {
      const offeringMinCpu = this.getMinCpu()
      if (this.resource.cpunumber < offeringMinCpu) {
        return offeringMinCpu
      }
      return this.resource.cpunumber
    },
    getMinMemory () {
      return this.selectedOffering?.serviceofferingdetails?.minmemory * 1 || 32
    },
    getInitialMemoryValue () {
      const offeringMinMemory = this.getMinMemory()
      if (this.resource.memory < offeringMinMemory) {
        return offeringMinMemory
      }
      return this.resource.memory
    },
    getCPUSpeed () {
      // We can only scale up while a VM is running
      if (this.resource.state === 'Running') {
        return this.resource.cpuspeed
      }
      this.getMinDiskSize()
      return this.selectedOffering?.serviceofferingdetails?.cpuspeed * 1 || 1
    },
    getTemplate () {
      return new Promise((resolve, reject) => {
        getAPI('listTemplates', {
          templatefilter: 'all',
          isready: true,
          id: this.resource.templateid
        }).then(response => {
          var template = response?.listtemplatesresponse?.template?.[0] || null
          resolve(template)
        }).catch(error => {
          reject(error)
        })
      })
    },
    async getMinDiskSize () {
      const template = await this.getTemplate()
      this.minDiskSize = Math.ceil(template?.size / (1024 * 1024 * 1024) || 0)
    },
    getMessage () {
      if (this.resource.hypervisor === 'VMware') {
        return this.$t('message.read.admin.guide.scaling.up')
      }
      return this.$t('message.change.offering.confirm')
    },
    updateIOPSValue (input, value) {
      console.log(input)
      const key = input === 'minIops' ? this.minIopsKey : this.maxIopsKey
      this.params[key] = value
    },
    updateComputeOffering (id) {
      // Delete custom details
      delete this.params[this.cpuNumberKey]
      delete this.params[this.cpuSpeedKey]
      delete this.params[this.memoryKey]
      delete this.params[this.rootDiskSizeKey]

      this.params.serviceofferingid = id
      this.selectedOffering = this.offeringsMap[id] || {}
      this.selectedDiskOffering = null
      if (this.selectedOffering.diskofferingid) {
        getAPI('listDiskOfferings', {
          id: this.selectedOffering.diskofferingid
        }).then(response => {
          const diskOfferings = response?.listdiskofferingsresponse?.diskoffering || []
          if (diskOfferings?.length > 0) {
            this.selectedDiskOffering = diskOfferings[0]
          }
        }).catch(error => {
          this.$notifyError(error)
        })
      }
      this.params.automigrate = this.autoMigrate
    },
    updateFieldValue (name, value) {
      if (name === this.rootDiskSizeKey && !this.rootSizeApplies) {
        return
      }
      this.params[name] = value
    },
    closeAction () {
      this.$emit('close-action')
    },
    handlerError (error) {
      this.error = error
    },
    handleSubmit () {
      if (this.loading) return
      this.loading = true

      if ('cpuspeed' in this.selectedOffering && this.selectedOffering.iscustomized) {
        delete this.params[this.cpuSpeedKey]
      }
      this.params.automigrate = this.autoMigrate

      postAPI('scaleVirtualMachine', this.params).then(response => {
        const jobId = response.scalevirtualmachineresponse.jobid
        if (jobId) {
          this.$pollJob({
            jobId,
            title: this.$t('label.scale.vm'),
            description: instanceName(this.resource),
            successMethod: result => {
              this.$notification.success({
                message: this.$t('message.success.change.offering')
              })
            },
            loadingMessage: this.$t('message.scale.processing'),
            catchMessage: this.$t('error.fetching.async.job.result')
          })
        }
        this.$emit('close-action')
        this.parentFetchData()
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped lang="scss">

.form {
  width: 90vw;
  @media (min-width: 700px) {
    width: 50vw;
  }
}
</style>
