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
import * as api from '@/api'
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

  it('Login makes the key it stores the one the page runs with, so that no other change of the key is mistaken for it', async () => {
    const adopt = jest.spyOn(api, 'adoptSessionKey')
    axios.mockImplementation((request) => request.data && request.data.get('command') === 'login'
      ? Promise.resolve({ loginresponse: { sessionkey: 'own-new-login', account: 'student', userid: 'u-1', username: 'student', firstname: 'A', lastname: 'B' } })
      : Promise.resolve({}))

    await user.actions.Login({ commit: jest.fn() }, { username: 'student', password: 'secret', domain: '/' })

    expect(storage.get('Access-Token')).toBe('own-new-login')
    expect(adopt).toHaveBeenCalledWith('own-new-login')
    expect(storage.get('SESSION_USER')).toBe('u-1')
    axios.mockImplementation(() => Promise.resolve({}))
  })

  describe('the preferences of the user a password login replaces', () => {
    const loginAs = async (userid) => {
      axios.mockImplementation((request) => request.data && request.data.get('command') === 'login'
        ? Promise.resolve({ loginresponse: { sessionkey: 'own-new-login', account: 'student', userid, username: 'student', firstname: 'A', lastname: 'B' } })
        : Promise.resolve({}))
      const commit = jest.fn()
      await user.actions.Login({ commit }, { username: 'student', password: 'secret', domain: '/' })
      axios.mockImplementation(() => Promise.resolve({}))
      const committed = (name) => (commit.mock.calls.find(call => call[0] === name) || [])[1]
      return { darkMode: committed('SET_DARK_MODE'), browserTimezone: committed('SET_USE_BROWSER_TIMEZONE'), columns: committed('SET_CUSTOM_COLUMNS') }
    }
    const earlier = (userid) => {
      storage.set('DARK_MODE', true)
      storage.set('USE_BROWSER_TIMEZONE', true)
      storage.set('CUSTOM_COLUMNS', { vm: ['name'] })
      storage.set('TIMEZONE_OFFSET', 9)
      if (userid) {
        storage.set('SESSION_USER', userid)
      }
    }

    it('stay with the same user', async () => {
      earlier('u-1')
      expect(await loginAs('u-1')).toEqual({ darkMode: true, browserTimezone: true, columns: { vm: ['name'] } })
      expect(storage.get('SESSION_USER')).toBe('u-1')
    })

    it('go with another user, who is then the one remembered', async () => {
      earlier('u-1')
      expect(await loginAs('u-2')).toEqual({ darkMode: false, browserTimezone: false, columns: {} })
      expect(['DARK_MODE', 'USE_BROWSER_TIMEZONE', 'CUSTOM_COLUMNS', 'TIMEZONE_OFFSET'].filter(key => storage.has(key))).toEqual([])
      expect(storage.get('SESSION_USER')).toBe('u-2')
    })

    it('go when the earlier user is not known', async () => {
      earlier(undefined)
      expect(await loginAs('u-1')).toEqual({ darkMode: false, browserTimezone: false, columns: {} })
      expect(storage.get('SESSION_USER')).toBe('u-1')
    })
  })
})
