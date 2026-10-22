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
  <div v-if="enabled && notices.length > 0" class="resource-limit-expiry-banner">
    <a-alert
      v-for="notice in notices"
      :key="notice.id"
      type="warning"
      banner
      closable
      @close="dismissed.push(notice.id)">
      <template #message>
        <span class="expiry-notice">{{ notice.text }}</span>
        <router-link
          v-if="!hasPending"
          class="expiry-renew"
          style="margin-left: 8px"
          :to="{ path: '/resourcelimitrequest', query: { renew: notice.id } }">
          {{ $t('label.resource.limit.request.renew') }}
        </router-link>
        <span v-else style="margin-left: 8px">{{ $t('message.resource.limit.request.renewal.pending') }}</span>
      </template>
    </a-alert>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { CHANGED_EVENT, dayInZone, daysLeft, grantedValues, isCoveredByLaterGrants, requestsAllowed, valueItems } from '@/utils/resourceLimitRequest'

const POLL_MS = 10 * 60 * 1000

export default {
  name: 'ResourceLimitExpiryBanner',
  data () {
    return {
      grants: [],
      hasPending: false,
      dismissed: [],
      poller: null
    }
  },
  computed: {
    enabled () {
      const apis = this.$store.getters.apis || {}
      return requestsAllowed(this.$store.getters.features) && 'listResourceLimitRequests' in apis && 'createResourceLimitRequest' in apis
    },
    timezone () {
      return (this.$store.getters.features || {}).resourcerequestexpirytimezone
    },
    // The server decides when a grant is to be warned of (expirywarningdays is set once it has), so the banner,
    // the event and the mail agree; the banner leaves out a warning whose grant ending would lower no limit.
    notices () {
      return this.grants
        .filter(grant => grant.approvedexpiry && grant.expirywarningdays !== undefined && grant.expirywarningdays !== null)
        .filter(grant => !isCoveredByLaterGrants(grant, this.grants))
        .filter(grant => !this.dismissed.includes(grant.id))
        .map(grant => ({ grant, days: daysLeft(grant.approvedexpiry, this.timezone) }))
        .filter(notice => notice.days !== null && notice.days >= 0)
        .map(({ grant, days }) => ({
          id: grant.id,
          text: this.$t('message.resource.limit.request.expiring', {
            values: this.describe(grant),
            date: dayInZone(grant.approvedexpiry, this.timezone),
            when: this.when(days)
          })
        }))
    }
  },
  watch: {
    enabled (on) {
      if (on) {
        this.start()
      } else {
        this.stop()
      }
    }
  },
  mounted () {
    eventBus.on(CHANGED_EVENT, this.fetchData)
    if (this.enabled) {
      this.start()
    }
  },
  beforeUnmount () {
    this.stop()
    eventBus.off(CHANGED_EVENT, this.fetchData)
  },
  methods: {
    start () {
      this.stop()
      this.fetchData()
      this.poller = setInterval(this.fetchData, POLL_MS)
    },
    stop () {
      if (this.poller !== null) {
        clearInterval(this.poller)
        this.poller = null
      }
      this.grants = []
      this.hasPending = false
    },
    fetchData () {
      if (!this.enabled) {
        return Promise.resolve()
      }
      return Promise.all([
        getAPI('listResourceLimitRequests', { state: 'Approved' }),
        getAPI('listResourceLimitRequests', { state: 'Pending', page: 1, pagesize: 1 })
      ]).then(([approved, pending]) => {
        this.grants = approved.listresourcelimitrequestsresponse.resourcelimitrequest || []
        const response = pending.listresourcelimitrequestsresponse
        this.hasPending = (response.count || (response.resourcelimitrequest || []).length) > 0
      }).catch(() => {})
    },
    describe (grant) {
      return valueItems(grantedValues(grant) || {})
        .map(item => this.$t('label.resource.limit.request.value.' + item.key, { n: item.n, type: item.type }))
        .join(', ')
    },
    when (days) {
      if (days === 0) {
        return this.$t('label.resource.limit.request.when.today')
      }
      if (days === 1) {
        return this.$t('label.resource.limit.request.when.tomorrow')
      }
      return this.$t('label.resource.limit.request.when.days', { days })
    }
  }
}
</script>
