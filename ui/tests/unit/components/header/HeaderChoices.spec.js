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

import fs from 'fs'
import path from 'path'
import { createStore } from 'vuex'
import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import deployment from '../../../mockData/DeploymentConfig.mock.json'
import globalStore from '@/store'
import { vueProps } from '@/vue-app'
import TranslationMenu from '@/components/header/TranslationMenu'
import UserMenu from '@/components/header/UserMenu'

jest.mock('@/api', () => ({ getAPI: jest.fn(() => Promise.resolve({ listusersresponse: { user: [] } })), postAPI: jest.fn() }))
jest.mock('@/locales', () => ({ loadLanguageAsync: jest.fn(), i18n: { global: { t: (key) => key } } }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' } } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
// the fork's default config with the deployment's data, which the fixture mirrors
const deployedConfig = { ...shippedConfig, ...deployment }
const i18n = common.createMockI18n('en', {})
const store = (roletype = 'User') => common.createMockStore({
  user: { apis: {}, info: { roletype, firstname: 'Ann', lastname: 'Kim', id: 'u-1', accountid: 'a-1' }, features: {} }
})

describe('header choices come from the config', () => {
  it('the language menu offers en and ko_KR to the user role type and every language to the others', () => {
    const offered = (roletype, config = deployedConfig) => common.createFactory(TranslationMenu, {
      i18n, store: store(roletype), mocks: { $config: config }
    }).vm.languages.map(language => language.key)
    expect(offered('User')).toEqual(['en', 'ko_KR'])
    for (const roletype of ['Admin', 'DomainAdmin']) {
      expect(offered(roletype)).toHaveLength(19)
    }
    expect(offered('User', { ...shippedConfig, hidden: {} })).toHaveLength(19)
    expect('languages' in shippedConfig).toBe(false)
  })

  it('the language menu narrows when the role type becomes known and leaves a locale that is no longer offered', async () => {
    // one store, as in the application: the menu's and the one the config helpers read
    const signedIn = createStore({
      state: { user: { info: {} } },
      getters: { userInfo: state => state.user.info, apis: () => ({}), features: () => ({}) },
      mutations: { SET_INFO: (state, info) => { state.user.info = info } }
    })
    const moduleGetters = globalStore.getters
    globalStore.getters = signedIn.getters
    vueProps.$config = { ...deployedConfig, defaultLanguage: 'ko_KR' }
    window.localStorage.clear()
    try {
      const menu = common.createFactory(TranslationMenu, { i18n, store: signedIn, mocks: { $config: vueProps.$config } })
      expect(menu.vm.languages).toHaveLength(19)
      menu.vm.onClick({ key: 'ja_JP' })
      expect(menu.vm.language).toBe('ja_JP')

      signedIn.commit('SET_INFO', { roletype: 'User' })
      await flushPromises()
      expect(menu.vm.languages.map(language => language.key)).toEqual(['en', 'ko_KR'])
      expect(menu.vm.language).toBe('ko_KR')

      // a locale that stays offered is kept
      menu.vm.onClick({ key: 'en' })
      signedIn.commit('SET_INFO', { roletype: 'User', id: 'u-1' })
      await flushPromises()
      expect(menu.vm.language).toBe('en')
    } finally {
      globalStore.getters = moduleGetters
      vueProps.$config = {}
      window.localStorage.clear()
    }
  })

  it('the default config carries no deployment data; the theme stays the operator\'s; the user menu follows the hidden header list', () => {
    expect(shippedConfig.docBase).toBe('http://docs.cloudstack.apache.org/en/latest')
    expect(shippedConfig.docHelpMappings).toEqual({})
    expect('hidden' in shippedConfig).toBe(false)
    // every address it names is upstream's, and it names no mailbox
    const text = JSON.stringify(shippedConfig)
    const hosts = (text.match(/https?:\/\/[^/'"\\\s]+/g) || []).map(url => new URL(url).hostname)
    expect(hosts.length).toBeGreaterThan(0)
    expect(hosts.filter(host => !/(^|\.)apache\.org$/.test(host))).toEqual([])
    expect(text).not.toContain('mailto:')
    expect(shippedConfig.allowSettingTheme).toBe(false)

    const router = common.createMockRouter([])
    const items = (config) => common.createFactory(UserMenu, {
      router, i18n, store: store(), mocks: { $config: config }, stubs: { CreateMenu: true, ExternalLink: true, PendingRequestBadge: true, HeaderNotice: true, TranslationMenu: true }
    }).vm.menuItems
    expect(items({ ...shippedConfig, hidden: {} })).toEqual(['profile', 'limits', 'timezone', 'document', 'logout'])
    expect(items({ ...shippedConfig, hidden: { User: { header: ['timezone'] } } })).toEqual(['profile', 'limits', 'document', 'logout'])
    expect(items({ ...shippedConfig, hidden: { Admin: { header: ['timezone'] } } })).toEqual(['profile', 'limits', 'timezone', 'document', 'logout'])
  })

  it('a help path mapped to a full address opens that address; any other opens under the manual', () => {
    const wrapper = common.createFactory({ template: '<div/>' }, {
      mocks: { $config: { docBase: 'https://manual.example', docHelpMappings: {} } }
    })
    expect(wrapper.vm.$docHelpUrl('instance.html')).toBe('https://manual.example/instance.html')
    expect(wrapper.vm.$docHelpUrl('https://docs.example/en/adminguide/hosts.html')).toBe('https://docs.example/en/adminguide/hosts.html')
  })
})
