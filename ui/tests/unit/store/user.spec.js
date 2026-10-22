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

import Cookies from 'js-cookie'

import { vueProps } from '@/vue-app'
import { axios } from '@/utils/request'
import { sessionKey } from '@/api'
import user from '@/store/modules/user'

jest.mock('@/vue-app', () => {
  const stored = new Map()
  return {
    vueProps: {
      $localStorage: {
        stored,
        get: (key, fallback = null) => (stored.has(key) ? stored.get(key) : fallback),
        set: (key, value) => stored.set(key, value),
        remove: (key) => stored.delete(key)
      }
    }
  }
})
jest.mock('@/utils/request', () => ({
  axios: jest.fn(() => Promise.resolve({})),
  sourceToken: { checkExistSource: () => true, init: jest.fn(), cancel: jest.fn() }
}))
jest.mock('ant-design-vue', () => ({ message: { destroy: jest.fn() }, notification: { destroy: jest.fn() } }))
jest.mock('ant-design-vue/es/message', () => ({ destroy: jest.fn() }))
jest.mock('ant-design-vue/es/notification', () => ({ destroy: jest.fn() }))
jest.mock('@/router', () => ({}))
jest.mock('@/store', () => ({}))
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))
jest.mock('@/utils/guiTheme', () => ({ applyCustomGuiTheme: jest.fn() }))

const storage = vueProps.$localStorage.stored
const settled = () => new Promise(resolve => setTimeout(resolve, 0))

describe('store/modules/user Logout', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    storage.clear()
    Object.keys(Cookies.get()).forEach(name => {
      Cookies.remove(name, { path: '/' })
      Cookies.remove(name, { path: '/client' })
    })
  })

  it('ends a session the server opened without taking its key over again', async () => {
    Cookies.set('sessionkey', 'opened-by-server', { path: '/' })
    Cookies.set('userid', 'u-1', { path: '/' })
    expect(sessionKey()).toBe('opened-by-server')

    await user.actions.Logout({ commit: jest.fn(), state: { token: 'opened-by-server', cloudian: {} } })
    await settled()

    expect(axios).toHaveBeenCalledTimes(1)
    expect(axios.mock.calls[0][0].data.get('command')).toBe('logout')
    expect(storage.has('Access-Token')).toBe(false)
    expect(Cookies.get('sessionkey')).toBeUndefined()
    const validLogin = sessionKey() || Cookies.get('userid') || Cookies.get('userid', { path: '/client' })
    expect(validLogin).toBeFalsy()
  })
})
