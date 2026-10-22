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
  <div class="footer">
    <div class="line">
      <span v-html="$config.footer" />
    </div>
    <div class="line credit">
      {{ credit }}
    </div>
    <div class="line" v-if="$store.getters.userInfo.roletype === 'Admin'">
      {{ name }} {{ $store.getters.features.cloudstackversion }}
    </div>
  </div>
</template>

<script>
import { brand, brandCredit } from '@/utils/brand'

export default {
  name: 'LayoutFooter',
  computed: {
    name () {
      return brand.name
    },
    credit () {
      return brandCredit(this.$i18n.locale)
    }
  }
}
</script>

<style lang="less" scoped>
  .footer {
    padding: 0 16px;
    margin: 48px 0 24px;
    text-align: center;

    .line {
      margin-bottom: 8px;
    }
    .credit {
      font-size: 12px;
      opacity: 0.65;
    }
  }
</style>
