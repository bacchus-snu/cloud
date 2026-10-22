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
    :visible="visible"
    :title="$t('label.placement.move.title')"
    :closable="false"
    :maskClosable="false"
    :footer="null"
    :width="560"
    @cancel="$emit('reject')">
    <div class="placement-move">
      <p class="placement-move-intro">{{ $t('message.placement.move.intro.' + operation) }}</p>
      <ul class="placement-move-volumes">
        <li v-for="volume in volumes" :key="volume.id">
          <strong>{{ volume.name }}</strong>
          {{ $bytesToHumanReadableSize(volume.size) }}
          <span v-if="volume.physicalsize !== undefined"> ({{ $t('label.placement.used') }} {{ $bytesToHumanReadableSize(volume.physicalsize) }})</span>
        </li>
      </ul>
      <p class="placement-move-estimate">{{ estimate }}</p>
      <a-alert type="warning" show-icon :message="$t('message.placement.move.unavailable')" />
      <p class="placement-move-copy">{{ copyText }}</p>
    </div>
    <div class="placement-move-actions">
      <a-button class="placement-move-reject" :disabled="busy" @click="$emit('reject')">{{ $t('label.placement.move.reject') }}</a-button>
      <a-button class="placement-move-approve" type="primary" :loading="busy" @click="$emit('approve', verdict)">
        {{ $t('label.placement.move.approve') }}
      </a-button>
    </div>
  </a-modal>
</template>

<script>
import { etaText } from '@/utils/placement'

export default {
  name: 'PlacementMoveModal',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    verdict: {
      type: Object,
      default: null
    },
    operation: {
      type: String,
      default: 'start',
      validator: value => ['start', 'attach', 'deploy'].includes(value)
    },
    busy: {
      type: Boolean,
      default: false
    }
  },
  emits: ['approve', 'reject'],
  computed: {
    volumes () {
      return (this.verdict && this.verdict.volumes) || []
    },
    // whether the old copy stays is the verdict's: the previous copy is a volume of the owner and counts against quota
    copyText () {
      if (this.verdict && this.verdict.previouscopykept) {
        return this.$t('message.placement.move.copy.kept', { pattern: '<name>-previous-<date>' })
      }
      return this.$t('message.placement.move.copy.removed')
    },
    estimate () {
      if (!this.verdict) {
        return ''
      }
      return this.$t('message.placement.move.estimate', {
        size: this.$bytesToHumanReadableSize(this.verdict.bytestomove),
        eta: etaText(this.$t, this.verdict.estimatedseconds)
      })
    }
  }
}
</script>

<style lang="less" scoped>
.placement-move-volumes {
  padding-left: 20px;
}

.placement-move-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;

  button:not(:last-child) {
    margin-right: 10px;
  }
}
</style>
