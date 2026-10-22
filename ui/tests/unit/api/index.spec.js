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
import { getAPI, postAPI, login, sessionKey } from '@/api'

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

const storage = vueProps.$localStorage.stored
const sentKey = (call) => call.params ? call.params.sessionkey : call.data.get('sessionkey')

const remember = (entries) => Object.entries(entries).forEach(([key, value]) => storage.set(key, value))

describe('api/index', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    storage.clear()
    Object.keys(Cookies.get()).forEach(name => {
      Cookies.remove(name, { path: '/' })
      Cookies.remove(name, { path: '/client' })
    })
  })

  it('takes over a session the server opened and drops what the UI kept of an earlier one', async () => {
    remember({
      'Access-Token': 'earlier-session',
      APIS: { listVirtualMachines: {} },
      ZONES: [{ id: 'z' }],
      CURRENT_PROJECT: { id: 'p' },
      HEADER_NOTICES: [{ key: 'n' }],
      DOMAIN_STORE: { d: 1 },
      MS_ID: 'ms-1',
      PASSWORD_CHANGE_REQUIRED: true,
      SHOW_SECURITY_GROUPS: true,
      DARK_MODE: true
    })
    Cookies.set('sessionkey', 'opened-by-server', { path: '/' })

    await getAPI('listUsers')
    await postAPI('updateUser', { id: 'u' })

    expect(axios.mock.calls.map(([call]) => sentKey(call))).toEqual(['opened-by-server', 'opened-by-server'])
    expect([...storage.keys()].sort()).toEqual(['Access-Token', 'DARK_MODE'])
    expect(storage.get('Access-Token')).toBe('opened-by-server')
  })

  it('keeps a session the UI opened itself', async () => {
    remember({ 'Access-Token': 'own-session', APIS: { listVirtualMachines: {} } })

    await getAPI('listVirtualMachines')

    expect(sentKey(axios.mock.calls[0][0])).toBe('own-session')
    expect(storage.get('APIS')).toEqual({ listVirtualMachines: {} })
  })

  it('takes over a server session once', async () => {
    Cookies.set('sessionkey', 'opened-by-server', { path: '/' })
    expect(sessionKey()).toBe('opened-by-server')
    remember({ APIS: { listVirtualMachines: {} } })

    await getAPI('listVirtualMachines')

    expect(sentKey(axios.mock.calls[0][0])).toBe('opened-by-server')
    expect(storage.get('APIS')).toEqual({ listVirtualMachines: {} })
  })

  it('removes the readable session cookie of an earlier sign-on before a password login', async () => {
    Cookies.set('sessionkey', 'earlier-sign-on', { path: '/' })

    await login({ username: 'admin', password: 'secret', domain: '/' })

    expect(Cookies.get('sessionkey')).toBeUndefined()
    const loginCall = axios.mock.calls.map(([call]) => call).find(call => call.data && call.data.get('command') === 'login')
    expect(loginCall.data.get('sessionkey')).toBeNull()
  })
})
