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

import { vueProps } from '@/vue-app'
import { axios } from '@/utils/request'
import { getAPI } from '@/api'
import store from '@/store'
import user from '@/store/modules/user'

jest.mock('@/vue-app', () => {
  const stored = new Map([['APIS', { listUsers: {} }]])
  return {
    vueProps: {
      $config: { notifyLatestCSVersion: true },
      $localStorage: {
        stored,
        get: (key, fallback = null) => (stored.has(key) ? stored.get(key) : fallback),
        set: (key, value) => stored.set(key, value),
        remove: (key) => stored.delete(key)
      }
    }
  }
})
jest.mock('@/utils/request', () => {
  const axios = jest.fn(() => Promise.resolve({}))
  axios.get = jest.fn(() => Promise.resolve([{ tag_name: '9.9.9.9' }]))
  return { axios, sourceToken: { checkExistSource: () => true, init: jest.fn(), cancel: jest.fn() } }
})
jest.mock('@/api', () => ({ getAPI: jest.fn(), login: jest.fn(), logout: jest.fn(), forgetServerOpenedSession: jest.fn() }))
jest.mock('ant-design-vue/es/message', () => ({ loading: jest.fn(() => jest.fn()), success: jest.fn(), destroy: jest.fn() }))
jest.mock('ant-design-vue/es/notification', () => ({ destroy: jest.fn() }))
jest.mock('@/router', () => ({ addRoute: jest.fn() }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { loginFlag: false }, dispatch: jest.fn() } }))
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))
jest.mock('@/utils/guiTheme', () => ({ applyCustomGuiTheme: jest.fn() }))

const settled = () => new Promise(resolve => setTimeout(resolve, 0))

const requestedUrls = () => [
  ...axios.mock.calls.map(call => JSON.stringify(call)),
  ...axios.get.mock.calls.map(call => JSON.stringify(call)),
  ...(global.fetch ? global.fetch.mock.calls.map(call => JSON.stringify(call)) : [])
]

describe('store > user', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'error').mockImplementation(() => {})
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve([]) }))
    getAPI.mockImplementation((command) => {
      if (command === 'listUsers') {
        return Promise.resolve({ listusersresponse: { user: [{ id: 'u-1', firstname: 'Root', lastname: 'Admin', rolename: 'Root Admin', accountid: 'a-1', domainid: 'd-1' }] } })
      }
      return Promise.reject(new Error(command + ' is not answered'))
    })
    // a dispatch that runs the module's own actions
    store.dispatch = jest.fn((name, payload) => (
      user.actions[name] ? user.actions[name]({ commit: jest.fn() }, payload) : Promise.resolve()
    ))
  })

  afterEach(() => {
    delete global.fetch
  })

  it('no GitHub version check request', async () => {
    expect(vueProps.$config.notifyLatestCSVersion).toBe(true)

    await user.actions.GetInfo({ commit: jest.fn() }, false).catch(() => {})
    await settled()
    await settled()

    expect(getAPI).toHaveBeenCalledWith('listUsers', expect.anything())
    expect(requestedUrls().filter(url => /github/i.test(url))).toEqual([])
    expect(user.actions.SetCsLatestVersion).toBeUndefined()
    expect(store.dispatch.mock.calls.map(call => call[0])).not.toContain('SetCsLatestVersion')
  })

  it('keeps no trace of the upstream version check in the client', () => {
    const uiRoot = path.resolve(__dirname, '../../..')
    const files = [
      'src/store/modules/user.js',
      'src/store/mutation-types.js',
      'src/store/getters.js',
      'src/components/page/GlobalFooter.vue',
      'public/config.json'
    ]
    const traces = []
    for (const file of files) {
      const text = fs.readFileSync(path.join(uiRoot, file), 'utf8')
      for (const needle of ['api.github.com', 'SetCsLatestVersion', 'SET_LATEST_VERSION', 'LATEST_CS_VERSION', 'latestVersion']) {
        if (text.includes(needle)) traces.push(file + ': ' + needle)
      }
    }
    expect(traces).toEqual([])

    const config = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
    expect(config.notifyLatestCSVersion).toBe(false)

    // the version comparison was the only user of the semver package
    expect(JSON.parse(fs.readFileSync(path.join(uiRoot, 'package.json'), 'utf8')).dependencies.semver).toBeUndefined()
  })
})
