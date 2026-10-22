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
  <a-dropdown>
    <span class="action ant-dropdown-link translation-menu">
      <TranslationOutlined />
    </span>
    <template #overlay>
      <a-menu
        :selectedKeys="[language]"
        @click="onClick">
        <a-menu-item v-for="item in languages" :key="item.key" :value="item.value">{{ item.name }}</a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>

<script>
import moment from 'moment'
import 'moment/locale/zh-cn'
import { loadLanguageAsync } from '@/locales'
import { vueProps } from '@/vue-app'
import { offeredLanguages } from '@/utils/uiConfig'

moment.locale('en')

const LANGUAGES = [
  { key: 'en', value: 'enUS', name: 'English' },
  { key: 'hi', value: 'hi', name: 'हिन्दी' },
  { key: 'te', value: 'te', name: 'తెలుగు' },
  { key: 'ja_JP', value: 'jpJP', name: '日本語' },
  { key: 'ko_KR', value: 'koKR', name: '한국어' },
  { key: 'zh_CN', value: 'zhCN', name: '简体中文' },
  { key: 'ar', value: 'arEG', name: 'Arabic' },
  { key: 'ca', value: 'caES', name: 'Catalan' },
  { key: 'de_DE', value: 'deDE', name: 'Deutsch' },
  { key: 'es', value: 'esES', name: 'Español' },
  { key: 'fr_FR', value: 'frFR', name: 'Français' },
  { key: 'it_IT', value: 'itIT', name: 'Italiano' },
  { key: 'hu', value: 'huHU', name: 'Magyar' },
  { key: 'nl_NL', value: 'nlNL', name: 'Nederlands' },
  { key: 'nb_NO', value: 'nbNO', name: 'Norsk' },
  { key: 'pl', value: 'plPL', name: 'Polish' },
  { key: 'pt_BR', value: 'ptBR', name: 'Português brasileiro' },
  { key: 'ru_RU', value: 'ruRU', name: 'Русский' },
  { key: 'el_GR', value: 'elGR', name: 'Ελληνικά' }
]

export default {
  name: 'TranslationMenu',
  data () {
    return {
      language: 'en'
    }
  },
  computed: {
    // every language except those the config.json hides from the caller's role type (area "languages")
    languages () {
      return offeredLanguages(LANGUAGES, this.$store.getters.userInfo?.roletype, this.$config)
    }
  },
  watch: {
    // the offered list can narrow once the caller's role type is known, after this menu mounted
    languages (offered) {
      if (!offered.some(item => item.key === this.language)) {
        this.setLocale(this.offeredLocale())
      }
    }
  },
  mounted () {
    this.setLocale(this.offeredLocale())
  },
  methods: {
    moment,
    offeredLocale () {
      const offered = this.languages.map(item => item.key)
      const wanted = [this.$localStorage.get('LOCALE'), vueProps.$config?.defaultLanguage, 'en', offered[0]]
      return wanted.find(key => key && offered.includes(key)) || 'en'
    },
    onClick (e) {
      let localeValue = e.key
      if (!localeValue) {
        localeValue = 'en'
      }
      this.setLocale(localeValue)
    },
    setLocale (localeValue) {
      this.$i18n.locale = localeValue
      this.language = localeValue
      moment.locale(localeValue)
      this.$localStorage.set('LOCALE', localeValue)
      loadLanguageAsync(localeValue)
    }
  }
}
</script>

<style lang="less" scoped>
.translation-menu {
  font-size: 18px;
  line-height: 1;
}

</style>
