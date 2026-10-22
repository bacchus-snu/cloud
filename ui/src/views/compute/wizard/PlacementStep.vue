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
  <div class="placement-step">
    <a-spin :spinning="loading">
      <a-alert
        v-if="verdict"
        class="placement-verdict"
        :type="alertType"
        show-icon
        :message="verdictText" />
      <p v-else-if="failed" class="placement-failed">{{ $t('message.placement.unavailable') }}</p>
      <p v-else class="placement-idle">{{ $t('message.placement.incomplete') }}</p>
      <ul v-if="capacity && showMaxima" class="placement-capacity">
        <li>{{ $t('message.placement.capacity.largest', { vcpu: capacity.maxvcpu, memory: gib(capacity.maxmemory), disk: capacity.maxdisk }) }}</li>
      </ul>
      <ul v-if="thisHost" class="placement-thishost">
        <li>{{ $t('message.placement.capacity.thishost', { vcpu: thisHost.freevcpu, memory: gib(thisHost.freememory), disk: thisHost.freedisk }) }}</li>
        <li v-if="showSlots && thisHost.gpuslotsavailable !== undefined">{{ $t('message.placement.capacity.gpuslots', { n: thisHost.gpuslotsavailable }) }}</li>
      </ul>
      <p class="placement-hint">{{ $t('message.placement.hint') }}</p>
    </a-spin>
  </div>
</template>

<script>
import { checkPlacement, listPlacementCapacity, placementKind, reasonText, etaText } from '@/utils/placement'
import { showGpuSlots } from '@/utils/uiConfig'

const DEBOUNCE_MS = 500

// Shows what the server says about the chosen combination: the largest Instance one host could take, and the verdict of
// checkPlacement. It only reads, and the inputs never depend on what it shows.
export default {
  name: 'PlacementStep',
  props: {
    request: {
      type: Object,
      default: () => ({})
    },
    zoneid: {
      type: String,
      default: ''
    },
    virtualmachineid: {
      type: String,
      default: ''
    },
    capacityOfferingId: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      verdict: null,
      capacity: null,
      verdictBusy: false,
      capacityBusy: false,
      verdictFailed: false,
      capacityFailed: false,
      verdictTimer: null,
      capacityTimer: null,
      verdictSequence: 0,
      capacitySequence: 0
    }
  },
  computed: {
    // the verdict follows every input; the maxima only the parameters that choose whose maxima they are
    requestKey () {
      return JSON.stringify(this.request)
    },
    capacityKey () {
      return JSON.stringify([this.zoneid, this.virtualmachineid, this.capacityOfferingId])
    },
    loading () {
      return this.verdictBusy || this.capacityBusy
    },
    failed () {
      return this.verdictFailed || this.capacityFailed
    },
    alertType () {
      switch (this.verdict && this.verdict.verdict) {
        case 'move':
          return 'warning'
        case 'none':
          return 'error'
        default:
          return 'success'
      }
    },
    verdictText () {
      const verdict = this.verdict || {}
      switch (verdict.verdict) {
        case 'here':
          return this.$t('message.placement.here')
        case 'anyhost':
          return this.$t('message.placement.anyhost')
        case 'move':
          return this.$t('message.placement.move', {
            size: this.$bytesToHumanReadableSize(verdict.bytestomove),
            eta: etaText(this.$t, verdict.estimatedseconds)
          })
        case 'none':
          return this.$t('message.placement.none', { reason: reasonText(this.$t, verdict.reason) })
        default:
          return ''
      }
    },
    showMaxima () {
      return !!this.capacityOfferingId
    },
    showSlots () {
      return showGpuSlots(this.$config)
    },
    thisHost () {
      return this.capacity && this.capacity.thishost
    }
  },
  watch: {
    requestKey () {
      clearTimeout(this.verdictTimer)
      this.verdictTimer = setTimeout(this.refreshVerdict, DEBOUNCE_MS)
    },
    capacityKey () {
      clearTimeout(this.capacityTimer)
      this.capacityTimer = setTimeout(this.refreshCapacity, DEBOUNCE_MS)
    }
  },
  mounted () {
    this.refreshVerdict()
    this.refreshCapacity()
  },
  beforeUnmount () {
    clearTimeout(this.verdictTimer)
    clearTimeout(this.capacityTimer)
    this.verdictSequence++
    this.capacitySequence++
  },
  methods: {
    gib (mib) {
      return Number(((Number(mib) || 0) / 1024).toFixed(1))
    },
    async refreshVerdict () {
      const sequence = ++this.verdictSequence
      const request = { ...this.request }
      if (placementKind(request) === null) {
        this.verdict = null
        this.verdictFailed = false
        this.verdictBusy = false
        return
      }
      this.verdictBusy = true
      const verdict = await checkPlacement(request).catch(() => undefined)
      if (sequence !== this.verdictSequence) {
        return
      }
      this.verdict = verdict || null
      this.verdictFailed = verdict === undefined
      this.verdictBusy = false
    },
    async refreshCapacity () {
      const sequence = ++this.capacitySequence
      if (!this.zoneid || !(this.capacityOfferingId || this.virtualmachineid)) {
        this.capacity = null
        this.capacityFailed = false
        this.capacityBusy = false
        return
      }
      this.capacityBusy = true
      const capacity = await listPlacementCapacity(this.capacityParams()).catch(() => undefined)
      if (sequence !== this.capacitySequence) {
        return
      }
      this.capacity = capacity || null
      this.capacityFailed = capacity === undefined
      this.capacityBusy = false
    },
    capacityParams () {
      const params = { zoneid: this.zoneid }
      if (this.virtualmachineid) {
        params.virtualmachineid = this.virtualmachineid
      }
      if (this.capacityOfferingId) {
        params.serviceofferingid = this.capacityOfferingId
      }
      return params
    }
  }
}
</script>

<style lang="less" scoped>
.placement-capacity,
.placement-thishost {
  margin: 10px 0 0;
  padding-left: 20px;
}

.placement-hint {
  margin-top: 10px;
  color: rgba(0, 0, 0, 0.45);
}
</style>
