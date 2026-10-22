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
  <a-modal
    :visible="request !== null"
    :title="title"
    :okText="$t('label.resource.limit.request.' + kind)"
    :okButtonProps="{ danger: true }"
    :cancelText="$t('label.cancel')"
    :confirmLoading="loading"
    :maskClosable="false"
    @ok="submit"
    @cancel="$emit('cancel')">
    <p v-if="kind === 'revoke'">{{ $t('message.resource.limit.request.confirm.revoke') }}</p>
    <div>{{ $t('label.reason') }}</div>
    <a-textarea v-model:value="reason" :maxlength="1024" :autoSize="{ minRows: 2, maxRows: 6 }" />
  </a-modal>
</template>

<script>
import { postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import { CHANGED_EVENT } from '@/utils/resourceLimitRequest'

const COMMANDS = {
  reject: 'rejectResourceLimitRequest',
  revoke: 'revokeResourceLimitRequest'
}

export default {
  name: 'DecisionModal',
  props: {
    request: {
      type: Object,
      default: null
    },
    kind: {
      type: String,
      default: 'reject',
      validator: kind => kind in COMMANDS
    }
  },
  emits: ['done', 'cancel'],
  data () {
    return {
      loading: false,
      reason: ''
    }
  },
  computed: {
    title () {
      return this.request ? this.$t('label.resource.limit.request.' + this.kind) + ' — ' + this.request.account : ''
    }
  },
  watch: {
    request () {
      this.reason = ''
    }
  },
  methods: {
    submit () {
      this.loading = true
      const params = { id: this.request.id }
      const reason = this.reason.trim()
      if (reason) {
        params.reason = reason
      }
      return postAPI(COMMANDS[this.kind], params).then(() => {
        this.$message.success(this.$t('message.resource.limit.request.' + (this.kind === 'reject' ? 'rejected' : 'revoked')))
        eventBus.emit(CHANGED_EVENT)
        this.$emit('done')
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>
