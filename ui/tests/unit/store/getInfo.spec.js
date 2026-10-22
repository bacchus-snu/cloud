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

import { vueProps } from '@/vue-app'
import { getAPI } from '@/api'
import store from '@/store'
import user from '@/store/modules/user'

jest.mock('@/vue-app', () => {
  const stored = new Map()
  return {
    vueProps: {
      $config: {},
      $localStorage: {
        stored,
        get: (key, fallback = null) => (stored.has(key) ? stored.get(key) : fallback),
        set: (key, value) => stored.set(key, value),
        remove: (key) => stored.delete(key)
      }
    }
  }
})
jest.mock('@/utils/request', () => ({ axios: jest.fn(), sourceToken: { checkExistSource: () => true, init: jest.fn(), cancel: jest.fn() } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), login: jest.fn(), logout: jest.fn(), forgetServerOpenedSession: jest.fn() }))
jest.mock('ant-design-vue/es/message', () => ({ loading: jest.fn(() => jest.fn()), success: jest.fn(), destroy: jest.fn() }))
jest.mock('ant-design-vue/es/notification', () => ({ destroy: jest.fn() }))
jest.mock('@/router', () => ({ addRoute: jest.fn() }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { loginFlag: true, features: {}, apis: {}, addRouters: [] }, dispatch: jest.fn(), commit: jest.fn() } }))
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))
jest.mock('@/utils/guiTheme', () => ({ applyCustomGuiTheme: jest.fn() }))

const settled = () => new Promise(resolve => setTimeout(resolve, 0))
const denied = (command) => Promise.reject(Object.assign(new Error('The API [' + command + '] does not exist or is not available for the account.'),
  { response: { status: 432, headers: {} } }))

// a role whose rules deny the directory listing and every other optional lookup of the sign-in
const roleApis = ['listApis', 'listCapabilities', 'listUsers', 'listZones', 'listVirtualMachines']

const answer = (command, allowed) => {
  if (!allowed.includes(command)) {
    return denied(command)
  }
  switch (command) {
    case 'listApis':
      return Promise.resolve({ listapisresponse: { api: allowed.map(name => ({ name, params: [] })) } })
    case 'listCapabilities':
      return Promise.resolve({ listcapabilitiesresponse: { capability: {} } })
    case 'listUsers':
      return Promise.resolve({ listusersresponse: { user: [{ id: 'u-1', firstname: 'A', lastname: 'B', roletype: 'Admin', accountid: 'a-1', domainid: 'd-1' }] } })
    case 'listZones':
      return Promise.resolve({ listzonesresponse: { zone: [] } })
    case 'listLdapConfigurations':
      return denied(command)
  }
  return Promise.resolve({})
}

describe('store > user > GetInfo', () => {
  let commit

  beforeEach(() => {
    jest.clearAllMocks()
    vueProps.$localStorage.stored.clear()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'error').mockImplementation(() => {})
    commit = jest.fn()
    store.getters.loginFlag = true
    store.dispatch = jest.fn(() => Promise.resolve())
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('GetInfo tolerates denied listLdapConfigurations', async () => {
    getAPI.mockImplementation((command) => answer(command, roleApis))

    const apis = await user.actions.GetInfo({ commit }, false)
    await settled()

    expect(Object.keys(apis)).toEqual(expect.arrayContaining(roleApis))
    expect(store.dispatch).not.toHaveBeenCalledWith('Logout', expect.anything())
    const called = getAPI.mock.calls.map(call => call[0])
    // nothing the role's API list leaves out is asked for, so no denial reaches the user
    for (const command of ['listLdapConfigurations', 'listNetworkServiceProviders', 'cloudianIsEnabled', 'listNetworks', 'listVPCs', 'listHSMProfiles']) {
      expect(called).not.toContain(command)
    }
    expect(commit).toHaveBeenCalledWith('SET_LDAP', false)
  })

  it('a listed directory lookup that fails still lets the sign-in finish', async () => {
    const withLdap = [...roleApis, 'listLdapConfigurations']
    getAPI.mockImplementation((command) => answer(command, withLdap))

    await expect(user.actions.GetInfo({ commit }, false)).resolves.toEqual(expect.objectContaining({ listLdapConfigurations: expect.anything() }))
    await settled()
    expect(getAPI.mock.calls.map(call => call[0])).toContain('listLdapConfigurations')
    expect(commit).toHaveBeenCalledWith('SET_LDAP', false)
  })

  it('a page reload with the API list kept asks only for what the list allows', async () => {
    store.getters.loginFlag = false
    vueProps.$localStorage.set('APIS', Object.fromEntries(roleApis.map(name => [name, {}])))
    getAPI.mockImplementation((command) => answer(command, roleApis))

    await expect(user.actions.GetInfo({ commit }, false)).resolves.toBeDefined()
    await settled()
    const called = getAPI.mock.calls.map(call => call[0])
    expect(called).not.toContain('listLdapConfigurations')
    expect(called).not.toContain('listNetworkServiceProviders')
    expect(called).not.toContain('cloudianIsEnabled')
  })

  it('UpdateConfiguration asks for the directory only when the API list has it', async () => {
    store.getters.apis = {}
    await expect(user.actions.UpdateConfiguration({ commit })).resolves.toBeUndefined()
    expect(getAPI).not.toHaveBeenCalled()
    expect(commit).toHaveBeenCalledWith('SET_LDAP', false)

    store.getters.apis = { listLdapConfigurations: {} }
    getAPI.mockImplementation(() => Promise.resolve({ ldapconfigurationresponse: { count: 1 } }))
    user.actions.UpdateConfiguration({ commit })
    await settled()
    expect(getAPI).toHaveBeenCalledWith('listLdapConfigurations')
    expect(commit).toHaveBeenCalledWith('SET_LDAP', true)
    store.getters.apis = {}
  })
})
