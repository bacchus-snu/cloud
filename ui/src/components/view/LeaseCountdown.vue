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
  <span
    v-if="leased"
    :class="['lease-countdown', { 'lease-countdown--ending': endingSoon }]"
    :title="$t('label.lease.ends.at') + ' ' + $toLocaleDate(resource.leaseexpirydate)">
    <field-time-outlined />
    {{ secondsLeft > 0 ? formatLeaseLeft(secondsLeft, $t) : $t('label.lease.ended') }}
  </span>
</template>

<script>
import { formatLeaseLeft, hasLease, leaseSecondsLeft } from '@/utils/lease'

const TICK_MS = 30000

export default {
  name: 'LeaseCountdown',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      fetchedAt: Date.now(),
      now: Date.now(),
      timer: null
    }
  },
  computed: {
    leased () {
      return hasLease(this.resource)
    },
    secondsLeft () {
      return leaseSecondsLeft(this.resource, this.fetchedAt, this.now)
    },
    endingSoon () {
      return this.secondsLeft !== null && this.secondsLeft < 3600
    }
  },
  watch: {
    resource () {
      this.fetchedAt = Date.now()
      this.now = this.fetchedAt
    }
  },
  mounted () {
    this.timer = setInterval(() => { this.now = Date.now() }, TICK_MS)
  },
  beforeUnmount () {
    clearInterval(this.timer)
  },
  methods: {
    formatLeaseLeft
  }
}
</script>

<style scoped>
.lease-countdown--ending {
  color: #f5222d;
}
</style>
