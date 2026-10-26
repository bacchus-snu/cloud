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
  <a-spin :spinning="loading">
    <div class="ssh-guests">
      <p class="ssh-guests-help">{{ $t('message.ssh.guests') }}</p>
      <div v-for="section in sections" :key="section.account" class="ssh-guests-section">
        <div class="ssh-guests-title">
          {{ $t('label.ssh.guests.account') }}
          <code class="ssh-guests-account">{{ section.account }}</code>
        </div>
        <a-textarea
          class="ssh-guests-input"
          :value="section.text"
          :autoSize="{ minRows: 2, maxRows: 12 }"
          :placeholder="$t('message.ssh.guests.placeholder')"
          @update:value="value => setDraft(section.account, value)" />
        <div v-for="error in section.errors" :key="error.type + ':' + error.entry" class="ssh-guests-error">
          <template v-if="error.type === 'separator'">
            <code>{{ error.entry }}</code> {{ $t('message.ssh.guests.separator') }}
          </template>
          <template v-else>{{ $t('message.ssh.guests.shape', { name: error.entry }) }}</template>
        </div>
        <div v-if="section.command" class="ssh-guests-command-row">
          <code class="ssh-guests-command">{{ section.command }}</code>
          <tooltip-button
            tooltipPlacement="top"
            :tooltip="$t('label.copy')"
            icon="CopyOutlined"
            type="dashed"
            size="small"
            :copyResource="section.command"
            @onClick="$message.success($t('label.copied.clipboard'))" />
        </div>
        <a-button
          class="ssh-guests-save"
          type="primary"
          :disabled="!section.canSave"
          :loading="saving === section.account"
          @click="save(section.account)">
          {{ $t('label.save') }}
        </a-button>
      </div>
      <div class="ssh-guests-add">
        <div class="ssh-guests-title">{{ $t('label.ssh.guests.add.account') }}</div>
        <a-input
          class="ssh-guests-add-input"
          v-model:value="newAccount"
          :placeholder="$t('message.ssh.guests.add.account')"
          @pressEnter="addAccount" />
        <a-button :disabled="!newAccountValid" @click="addAccount">{{ $t('label.add') }}</a-button>
        <div v-if="newAccount && !newAccountValid" class="ssh-guests-error">{{ $t('message.ssh.guests.account.invalid') }}</div>
      </div>
    </div>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import TooltipButton from '@/components/widgets/TooltipButton'
import { guestSshCommand, loginAccount } from '@/utils/instanceAccess'
import {
  RESOURCE_TYPE,
  createTagsParams,
  deleteTagsParams,
  diffGuestTags,
  formatGuestList,
  guestAccounts,
  isLinuxAccount,
  parseGuestList
} from '@/utils/guestList'

const POLL_MS = 1000
const MAX_POLLS = 120
const jobError = (errortext) => Object.assign(new Error(errortext), { response: { data: { queryasyncjobresultresponse: { errortext } } } })

export default {
  name: 'SshGuestsTab',
  components: {
    TooltipButton
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      tags: [],
      // what was typed per account, until it is saved; an account without an entry shows what the tags hold
      drafts: {},
      // accounts the owner started or saved here, which stay listed while their tags are empty
      added: [],
      newAccount: '',
      loading: false,
      saving: '',
      closed: false
    }
  },
  computed: {
    access () {
      return this.$config?.instanceAccess
    },
    held () {
      return Object.fromEntries(guestAccounts(this.tags).map(({ account, names }) => [account, names]))
    },
    accounts () {
      const login = loginAccount(this.access, this.resource.templatename)
      return [...new Set([login, ...Object.keys(this.held), ...this.added].filter(Boolean))]
    },
    sections () {
      return this.accounts.map(account => {
        const heldNames = this.held[account] || []
        const text = account in this.drafts ? this.drafts[account] : formatGuestList(heldNames)
        const { names, errors } = parseGuestList(text, this.access?.guestPatterns)
        const diff = diffGuestTags(this.tags, account, names)
        const changed = JSON.stringify(names) !== JSON.stringify(heldNames)
        return {
          account,
          text,
          errors,
          diff,
          command: guestSshCommand(this.access, this.resource, account),
          canSave: errors.length === 0 && changed && (diff.remove.length > 0 || diff.add.length > 0)
        }
      })
    },
    newAccountValid () {
      return isLinuxAccount(this.newAccount)
    }
  },
  watch: {
    // What was typed belongs to the instance it was typed for: another instance starts afresh, also when the resource
    // had no id for a moment in between.
    'resource.id' (id) {
      if (!id) {
        return
      }
      if (this.knownId && id !== this.knownId) {
        this.drafts = {}
        this.added = []
      }
      this.knownId = id
      this.fetchTags()
    }
  },
  created () {
    this.knownId = this.resource.id
    this.fetchTags()
  },
  beforeUnmount () {
    this.closed = true
  },
  methods: {
    async fetchTags () {
      if (!this.resource?.id) {
        return false
      }
      this.loading = true
      try {
        const json = await getAPI('listTags', { listall: true, resourceid: this.resource.id, resourcetype: RESOURCE_TYPE })
        this.tags = json?.listtagsresponse?.tag || []
        return true
      } catch (error) {
        this.$notifyError(error)
        return false
      } finally {
        this.loading = false
      }
    },
    setDraft (account, value) {
      this.drafts[account] = value
    },
    addAccount () {
      if (!this.newAccountValid) {
        return
      }
      if (!this.accounts.includes(this.newAccount)) {
        this.added.push(this.newAccount)
      }
      this.newAccount = ''
    },
    // Tag commands are jobs: a create sent before the delete of the same key has finished would find the key still there.
    // A command that was accepted is followed to its end even when the tab is gone, for stopping between the delete and
    // the create would leave the list deleted.
    async finished (json) {
      const jobId = Object.values(json || {}).map(response => response?.jobid).find(Boolean)
      if (!jobId) {
        return
      }
      for (let poll = 0; poll < MAX_POLLS; poll++) {
        const result = (await getAPI('queryAsyncJobResult', { jobId }))?.queryasyncjobresultresponse
        if (result?.jobstatus === 1) {
          return
        }
        if (result?.jobstatus === 2) {
          throw jobError(result.jobresult?.errortext)
        }
        await new Promise(resolve => setTimeout(resolve, POLL_MS))
      }
      throw jobError(this.$t('message.ssh.guests.timeout'))
    },
    async save (account) {
      const section = this.sections.find(item => item.account === account)
      if (!section?.canSave || this.saving) {
        return
      }
      this.saving = account
      // a list whose tags are gone after a half-finished save keeps its section, and with it what was typed
      if (!this.added.includes(account)) {
        this.added.push(account)
      }
      const id = this.resource.id
      let sent = false
      let saved = false
      let failure = null
      try {
        if (section.diff.remove.length > 0) {
          const deleting = await postAPI('deleteTags', deleteTagsParams(id, section.diff.remove))
          sent = true
          await this.finished(deleting)
        }
        if (section.diff.add.length > 0) {
          const creating = await postAPI('createTags', createTagsParams(id, section.diff.add))
          sent = true
          await this.finished(creating)
        }
        saved = true
      } catch (error) {
        failure = error
      }
      if (sent) {
        eventBus.emit('resource-tags-changed', id)
      }
      // a closed tab has no one to show anything to
      if (this.closed) {
        return
      }
      if (failure) {
        this.$notifyError(failure)
      }
      // the typed list stays until the tags show it, so that a failed read leaves what the owner typed
      const read = await this.fetchTags()
      this.saving = ''
      if (saved && read) {
        delete this.drafts[account]
      }
      if (saved) {
        this.$message.success(this.$t('message.ssh.guests.saved', { account }))
      }
    }
  }
}
</script>

<style lang="less" scoped>
.ssh-guests-help {
  margin-bottom: 16px;
}

.ssh-guests-section {
  margin-bottom: 24px;
}

.ssh-guests-title {
  font-weight: bold;
  margin-bottom: 4px;
}

.ssh-guests-error {
  color: #f5222d;
  margin-top: 4px;
}

.ssh-guests-command-row {
  margin: 8px 0;

  .ssh-guests-command {
    margin-right: 8px;
  }
}

.ssh-guests-add-input {
  max-width: 280px;
  margin-right: 8px;
}
</style>
