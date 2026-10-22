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
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { apis: {}, token: '' } } }))

const sentKey = (call) => call.params ? call.params.sessionkey : call.data.get('sessionkey')

// Each test gets a page of its own: the module keeps what a page knows of its session.
const openPage = () => {
  let page
  jest.isolateModules(() => {
    page = {
      api: require('@/api'),
      storage: require('@/vue-app').vueProps.$localStorage,
      store: require('@/store').default,
      axios: require('@/utils/request').axios
    }
  })
  page.stored = page.storage.stored
  return page
}

const remember = (page, entries) => Object.entries(entries).forEach(([key, value]) => page.stored.set(key, value))

const SESSION = { apis: { listVirtualMachines: {}, listHosts: {} }, token: 'earlier-session' }

describe('api/index', () => {
  const originalLocation = window.location

  beforeAll(() => {
    delete window.location
    window.location = { pathname: '/client/', search: '', hash: '#/instance', replace: jest.fn() }
  })

  afterAll(() => {
    window.location = originalLocation
  })

  beforeEach(() => {
    jest.clearAllMocks()
    Object.keys(Cookies.get()).forEach(name => {
      Cookies.remove(name, { path: '/' })
      Cookies.remove(name, { path: '/client' })
    })
  })

  it('takes over a session the server opened and drops what the UI kept of an earlier one, preferences included', async () => {
    const page = openPage()
    remember(page, {
      'Access-Token': 'earlier-session',
      APIS: { listVirtualMachines: {} },
      ZONES: [{ id: 'z' }],
      CURRENT_PROJECT: { id: 'p' },
      HEADER_NOTICES: [{ key: 'n' }],
      DOMAIN_STORE: { d: 1 },
      MS_ID: 'ms-1',
      PASSWORD_CHANGE_REQUIRED: true,
      SHOW_SECURITY_GROUPS: true,
      TIMEZONE_OFFSET: 9,
      USE_BROWSER_TIMEZONE: true,
      CUSTOM_COLUMNS: { vm: ['name'] },
      DARK_MODE: true,
      SIDEBAR_TYPE: false
    })
    Cookies.set('sessionkey', 'opened-by-server', { path: '/' })

    await page.api.getAPI('listUsers')
    await page.api.postAPI('updateUser', { id: 'u' })

    expect(page.axios.mock.calls.map(([call]) => sentKey(call))).toEqual(['opened-by-server', 'opened-by-server'])
    expect([...page.stored.keys()].sort()).toEqual(['Access-Token', 'SIDEBAR_TYPE'])
    expect(page.stored.get('Access-Token')).toBe('opened-by-server')
    expect(window.location.replace).not.toHaveBeenCalled()
  })

  describe('the preferences of a user when the server opens a session', () => {
    const PREFERENCES = { TIMEZONE_OFFSET: 9, USE_BROWSER_TIMEZONE: true, CUSTOM_COLUMNS: { vm: ['name'] }, DARK_MODE: true }
    const openSession = (page, previousUser, incomingUser) => {
      remember(page, { 'Access-Token': 'earlier-session', APIS: { listVirtualMachines: {} }, ...PREFERENCES })
      if (previousUser) {
        page.stored.set('SESSION_USER', previousUser)
      }
      Cookies.set('sessionkey', 'opened-by-server', { path: '/' })
      if (incomingUser) {
        Cookies.set('userid', incomingUser, { path: '/' })
      }
    }
    const preferencesKept = (page) => Object.keys(PREFERENCES).filter(key => page.stored.has(key))

    it('stay with the same user: a new session of a user the page knew keeps them and drops the rest of the session', async () => {
      const page = openPage()
      openSession(page, 'user-1', 'user-1')

      await page.api.getAPI('listUsers')

      expect(preferencesKept(page)).toEqual(Object.keys(PREFERENCES))
      expect(page.stored.has('APIS')).toBe(false)
      expect(page.stored.get('Access-Token')).toBe('opened-by-server')
      expect(page.stored.get('SESSION_USER')).toBe('user-1')
    })

    it('go with the other user: a session of another user drops them and names the new user', async () => {
      const page = openPage()
      openSession(page, 'user-1', 'user-2')

      await page.api.getAPI('listUsers')

      expect(preferencesKept(page)).toEqual([])
      expect(page.stored.get('SESSION_USER')).toBe('user-2')
    })

    it('go when the earlier user is not known', async () => {
      const page = openPage()
      openSession(page, undefined, 'user-2')

      await page.api.getAPI('listUsers')

      expect(preferencesKept(page)).toEqual([])
      expect(page.stored.get('SESSION_USER')).toBe('user-2')
    })

    it('go when the new session does not say whose it is, and nobody is then named', async () => {
      const page = openPage()
      openSession(page, 'user-1', undefined)

      await page.api.getAPI('listUsers')

      expect(preferencesKept(page)).toEqual([])
      expect(page.stored.has('SESSION_USER')).toBe(false)
    })
  })

  it('keeps a session the UI opened itself', async () => {
    const page = openPage()
    remember(page, { 'Access-Token': 'own-session', APIS: { listVirtualMachines: {} } })

    await page.api.getAPI('listVirtualMachines')

    expect(sentKey(page.axios.mock.calls[0][0])).toBe('own-session')
    expect(page.stored.get('APIS')).toEqual({ listVirtualMachines: {} })
  })

  it('takes over a server session once', async () => {
    const page = openPage()
    Cookies.set('sessionkey', 'opened-by-server', { path: '/' })
    expect(page.api.sessionKey()).toBe('opened-by-server')
    remember(page, { APIS: { listVirtualMachines: {} } })

    await page.api.getAPI('listVirtualMachines')

    expect(sentKey(page.axios.mock.calls[0][0])).toBe('opened-by-server')
    expect(page.stored.get('APIS')).toEqual({ listVirtualMachines: {} })
  })

  it('removes the readable session cookie of an earlier sign-on before a password login', async () => {
    const page = openPage()
    Cookies.set('sessionkey', 'earlier-sign-on', { path: '/' })

    await page.api.login({ username: 'admin', password: 'secret', domain: '/' })

    expect(Cookies.get('sessionkey')).toBeUndefined()
    const loginCall = page.axios.mock.calls.map(([call]) => call).find(call => call.data && call.data.get('command') === 'login')
    expect(loginCall.data.get('sessionkey')).toBeNull()
  })

  describe('a page that runs one session while another one comes into force', () => {
    const serverOpensAnother = (page) => {
      Object.assign(page.store.getters, SESSION)
      remember(page, { 'Access-Token': 'earlier-session', APIS: { listHosts: {} } })
      expect(page.api.sessionKey()).toBe('earlier-session')
      Cookies.set('sessionkey', 'opened-by-server', { path: '/' })
    }

    it('starts over from the entry address, sends not even the request that finds the change, and sends nothing after it', async () => {
      const page = openPage()
      serverOpensAnother(page)

      await expect(page.api.getAPI('listUsers')).rejects.toMatchObject({ sessionReplaced: true, message: 'message.session.replaced' })
      expect(page.axios).not.toHaveBeenCalled()
      expect(window.location.replace).toHaveBeenCalledTimes(1)
      expect(window.location.replace).toHaveBeenCalledWith('/client/')
      expect(page.stored.get('Access-Token')).toBe('opened-by-server')
      expect(page.stored.has('APIS')).toBe(false)

      await expect(page.api.postAPI('updateUser', { id: 'u' })).rejects.toMatchObject({ sessionReplaced: true })
      await expect(page.api.callAPI('listVirtualMachines')).rejects.toMatchObject({ sessionReplaced: true })
      expect(page.axios).not.toHaveBeenCalled()
      expect(window.location.replace).toHaveBeenCalledTimes(1)
    })

    it('does so for the pages of a forced password change and of a second factor, whose API list is still empty', async () => {
      for (const state of [{ passwordChangeRequired: true }, { twoFaEnabled: true }, { twoFaProvider: 'totp' }, { token: 'earlier-session' }]) {
        jest.clearAllMocks()
        const page = openPage()
        Object.assign(page.store.getters, { apis: {}, token: '', ...state })
        remember(page, { 'Access-Token': 'earlier-session' })
        page.api.sessionKey()
        Cookies.set('sessionkey', 'opened-by-server', { path: '/' })

        await expect(page.api.getAPI('listUsers')).rejects.toMatchObject({ sessionReplaced: true })
        expect(window.location.replace).toHaveBeenCalledTimes(1)
        Cookies.remove('sessionkey', { path: '/' })
      }
    })

    it('does so when a password login in another tab changes the stored key', async () => {
      const page = openPage()
      Object.assign(page.store.getters, SESSION)
      remember(page, { 'Access-Token': 'earlier-session' })
      await page.api.getAPI('listVirtualMachines')
      expect(sentKey(page.axios.mock.calls[0][0])).toBe('earlier-session')

      page.stored.set('Access-Token', 'other-tab-login')
      await expect(page.api.getAPI('listVirtualMachines')).rejects.toMatchObject({ sessionReplaced: true })
      expect(page.axios).toHaveBeenCalledTimes(1)
      expect(window.location.replace).toHaveBeenCalledTimes(1)
    })

    it('does so when another tab has already taken the server\'s session over', async () => {
      const page = openPage()
      serverOpensAnother(page)
      page.stored.set('Access-Token', 'opened-by-server')

      await expect(page.api.getAPI('listUsers')).rejects.toMatchObject({ sessionReplaced: true })
      expect(window.location.replace).toHaveBeenCalledTimes(1)
    })

    it('does not, for a login the page made itself, nor for its own logout', async () => {
      const page = openPage()
      Object.assign(page.store.getters, SESSION)
      remember(page, { 'Access-Token': 'earlier-session' })
      await page.api.getAPI('listVirtualMachines')

      page.stored.set('Access-Token', 'own-new-login')
      page.api.adoptSessionKey('own-new-login')
      await page.api.getAPI('listVirtualMachines')
      expect(sentKey(page.axios.mock.calls[1][0])).toBe('own-new-login')

      // the logout clears the state first, then the key, and still sends its request
      Object.assign(page.store.getters, { apis: {}, token: '' })
      page.stored.delete('Access-Token')
      await page.api.postAPI('logout')
      expect(page.axios).toHaveBeenCalledTimes(3)
      expect(window.location.replace).not.toHaveBeenCalled()
    })

    it('does not, for a page that holds no session yet', async () => {
      const page = openPage()
      Cookies.set('sessionkey', 'opened-by-server', { path: '/' })

      await page.api.getAPI('listApis')
      page.store.getters.apis = { listVirtualMachines: {} }
      await page.api.getAPI('listUsers')
      expect(sentKey(page.axios.mock.calls[1][0])).toBe('opened-by-server')
      expect(window.location.replace).not.toHaveBeenCalled()
    })

    it('does not start the new page over again and again when the key cannot be kept', async () => {
      const first = openPage()
      serverOpensAnother(first)
      await expect(first.api.getAPI('listUsers')).rejects.toMatchObject({ sessionReplaced: true })
      expect(window.location.replace).toHaveBeenCalledTimes(1)

      // the new page, on a storage that keeps nothing
      jest.clearAllMocks()
      const page = openPage()
      page.storage.set = () => {}
      await page.api.getAPI('listApis')
      Object.assign(page.store.getters, SESSION)
      await page.api.getAPI('listUsers')
      await page.api.getAPI('listUsers')
      expect(window.location.replace).not.toHaveBeenCalled()
      expect(page.axios).toHaveBeenCalledTimes(3)
    })
  })
})
