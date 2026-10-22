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
    v-if="enabled"
    v-bind="$attrs"
    class="pending-request-badge"
    role="button"
    tabindex="0"
    :aria-label="label"
    @click="open"
    @keydown.enter="open"
    @keydown.space.prevent="open">
    <a-tooltip placement="bottom" :title="$t('label.resource.limit.request.pending.tooltip')">
      <a-badge :count="count" :overflowCount="99">
        <audit-outlined class="pending-request-badge-icon" />
      </a-badge>
    </a-tooltip>
  </span>
</template>

<script>
import { getAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { APPROVE_API, CHANGED_EVENT, requestsAllowed } from '@/utils/resourceLimitRequest'

const POLL_MS = 60 * 1000

export default {
  name: 'PendingRequestBadge',
  inheritAttrs: false,
  data () {
    return {
      count: 0,
      poller: null
    }
  },
  computed: {
    label () {
      return this.count > 0
        ? this.$t('label.resource.limit.request.pending.count', { count: this.count })
        : this.$t('label.resource.limit.request.pending.tooltip')
    },
    // The role rules decide who approves; the account type is never consulted.
    enabled () {
      return !!this.$store.getters.apis && APPROVE_API in this.$store.getters.apis
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
    eventBus.on(CHANGED_EVENT, this.fetchCount)
    if (this.enabled) {
      this.start()
    }
  },
  beforeUnmount () {
    this.stop()
    eventBus.off(CHANGED_EVENT, this.fetchCount)
  },
  methods: {
    start () {
      this.stop()
      this.fetchCount()
      this.poller = setInterval(this.fetchCount, POLL_MS)
    },
    stop () {
      if (this.poller !== null) {
        clearInterval(this.poller)
        this.poller = null
      }
      this.count = 0
    },
    // No account decides its own request, so the account's own pending one (it has at most one) is not counted.
    fetchCount () {
      if (!this.enabled) {
        return Promise.resolve()
      }
      const total = (json) => {
        const response = json.listresourcelimitrequestsresponse || {}
        return response.count || (response.resourcelimitrequest || []).length
      }
      const pending = { state: 'Pending', page: 1, pagesize: 1 }
      return Promise.all([
        getAPI('listResourceLimitRequests', { ...pending, listall: true }),
        requestsAllowed(this.$store.getters.features) ? getAPI('listResourceLimitRequests', pending) : Promise.resolve(null)
      ]).then(([everyone, own]) => {
        this.count = Math.max(0, total(everyone) - (own ? total(own) : 0))
      }).catch(() => {})
    },
    open () {
      this.$router.push({ path: '/resourcelimitapproval' })
    }
  }
}
</script>

<style lang="less" scoped>
.pending-request-badge {
  display: inline-block;
  cursor: pointer;
  transition: all 0.3s;
  vertical-align: initial;

  &-icon {
    font-size: 18px;
    padding: 4px;
  }
}
</style>
