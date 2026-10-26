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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-form
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      @finish="handleSubmit">
      <a-form-item name="name" ref="name" :label="$t('label.name')">
        <a-input v-model:value="form.name" v-focus="true" />
      </a-form-item>
      <div :span="24" class="action-button">
        <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>

<script>
import { ref, reactive } from 'vue'
import { postAPI } from '@/api'
import { resourceNameRule } from '@/utils/resourceName'

export default {
  name: 'RenameResource',
  props: {
    resource: {
      type: Object,
      required: true
    },
    action: {
      type: Object,
      default: () => ({})
    }
  },
  data () {
    return {
      loading: false
    }
  },
  computed: {
    api () {
      return this.action?.currentAction?.api
    }
  },
  created () {
    this.formRef = ref()
    this.form = reactive({ name: this.resource.name })
    this.rules = reactive({
      name: [resourceNameRule(() => this.$t('message.error.resource.name.rule'))]
    })
  },
  methods: {
    handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const name = this.form.name
        this.loading = true
        postAPI(this.api, { id: this.resource.id, name }).then(json => {
          const answer = Object.values(json).find(value => value && typeof value === 'object') || {}
          if (answer.jobid) {
            this.$pollJob({
              jobId: answer.jobid,
              title: this.$t('label.rename'),
              description: name,
              loadingMessage: `${this.$t('label.rename')} ${this.resource.name} ${this.$t('label.in.progress')}`,
              catchMessage: this.$t('error.fetching.async.job.result')
            })
          } else {
            this.$message.success(`${this.$t('label.rename')} - ${name}`)
          }
          this.$emit('refresh-data')
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        if (error?.errorFields?.length) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        }
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped lang="less">
.form-layout {
  width: 85vw;

  @media (min-width: 760px) {
    width: 450px;
  }
}
</style>
