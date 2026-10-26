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
  <div class="confirm-input">
    <p v-if="mode === 'count'" class="confirm-prompt">{{ $t('message.confirm.type.count', { count: expected }) }}</p>
    <template v-else>
      <p class="confirm-prompt">{{ $t('label.delete.confirmation') }}</p>
      <p><code class="confirm-expected">{{ expected }}</code></p>
    </template>
    <a-input
      :value="value"
      autocomplete="off"
      @update:value="text => $emit('update:value', text)" />
  </div>
</template>

<script>
// Whether the typed text matches is decided by the caller with the helpers of utils/confirmation.
export default {
  name: 'ConfirmInput',
  props: {
    value: {
      type: String,
      default: ''
    },
    mode: {
      type: String,
      default: 'name',
      validator: (mode) => ['name', 'count'].includes(mode)
    },
    expected: {
      type: [String, Number],
      default: ''
    }
  },
  emits: ['update:value']
}
</script>

<style scoped lang="less">
.confirm-input {
  margin-bottom: 12px;

  p {
    margin-bottom: 4px;
  }

  .confirm-expected {
    word-break: break-all;
  }
}
</style>
