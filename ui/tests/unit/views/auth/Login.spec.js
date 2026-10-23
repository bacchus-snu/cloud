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
import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import messages from '@public/locales/en.json'
import { getAPI, postAPI } from '@/api'
import Login from '@/views/auth/Login'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { logoutFlag: false }, dispatch: jest.fn() } }))
jest.mock('@/utils/request', () => ({ sourceToken: { init: jest.fn() } }))
jest.mock('@/components/header/TranslationMenu', () => ({ name: 'TranslationMenu', render: () => null }))

// the first mount compiles the component library
jest.setTimeout(30000)

const students = {
  id: 'p-1',
  name: 'students',
  label: 'Student ID',
  endsessionurl: 'https://id.example.org/o/session/end',
  endsessionlabel: 'Log out of Student ID',
  refusal: '<p>Only <b>majors</b> may use it.</p>'
}
const members = {
  id: 'p-2',
  name: 'members',
  label: 'Members',
  endsessionurl: 'https://auth.example.org/oidc/logout',
  endsessionlabel: 'Log out of Members'
}
const plain = { id: 'p-3', name: 'plain', label: 'Plain' }

const answers = (providers) => (command) => {
  if (command === 'listOidcProviders') {
    return Promise.resolve({ listoidcprovidersresponse: { count: providers.length, oidcprovider: providers } })
  }
  return Promise.reject(new Error(command + ' is not available'))
}

const mountLogin = async ({ providers = [], config = {}, search = '' } = {}) => {
  window.history.replaceState(null, '', '/client/' + search + '#/user/login')
  getAPI.mockImplementation(answers(providers))
  postAPI.mockResolvedValue({ forgotpasswordresponse: { enabled: false } })
  const store = common.createMockStore({}, { Login: jest.fn(() => Promise.resolve()), Logout: jest.fn() })
  const i18n = common.createMockI18n('en', { en: messages })
  const router = common.createMockRouter()
  router.push('/')
  await router.isReady()
  const wrapper = common.createFactory(Login, {
    router,
    store,
    i18n,
    mocks: { $config: { apiBase: '/client/api', multipleServer: false, servers: [], ...config } }
  })
  await flushPromises()
  return wrapper
}

const usernameField = (wrapper) => wrapper.find('input[placeholder="' + messages['label.username'] + '"]')

describe('Views > auth > Login.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('logoutPageShowsIdpEndSessionLinks', async () => {
    const wrapper = await mountLogin({ providers: [students, members, plain] })

    const block = wrapper.find('.oidc-end-session')
    expect(block.exists()).toBe(true)
    expect(block.text()).toContain(messages['message.oidc.end.session'])
    expect(block.findAll('a').map(link => [link.text(), link.attributes('href')])).toEqual([
      ['Log out of Student ID', 'https://id.example.org/o/session/end'],
      ['Log out of Members', 'https://auth.example.org/oidc/logout']
    ])
    wrapper.unmount()

    const withoutLinks = await mountLogin({ providers: [plain] })
    expect(withoutLinks.find('.oidc-end-session').exists()).toBe(false)
    withoutLinks.unmount()
  })

  it('logoutLinksComeFromProviderFields', async () => {
    const moved = { ...students, endsessionurl: 'https://elsewhere.example.org/logout', endsessionlabel: 'Elsewhere' }
    const wrapper = await mountLogin({ providers: [moved] })

    const links = wrapper.findAll('.oidc-end-session a')
    expect(links.map(link => [link.text(), link.attributes('href')])).toEqual([['Elsewhere', 'https://elsewhere.example.org/logout']])
    wrapper.unmount()

    const source = fs.readFileSync(path.resolve(__dirname, '../../../../src/views/auth/Login.vue'), 'utf8')
    expect(source).not.toMatch(/session\/end|oidc\/logout|https:\/\//)
  })

  it('offers each enabled provider as a button that starts oidcLogin', async () => {
    const wrapper = await mountLogin({ providers: [students, { ...plain, enabled: false }, members] })

    const buttons = wrapper.findAll('.oidc-providers .oidc-login-button')
    expect(buttons.map(button => [button.text(), button.attributes('href')])).toEqual([
      ['Log in with Student ID', '/client/api?command=oidcLogin&provider=students'],
      ['Log in with Members', '/client/api?command=oidcLogin&provider=members']
    ])
    expect(getAPI).toHaveBeenCalledWith('listOidcProviders')
    wrapper.unmount()
  })

  it('folds the password form behind the administrator link while providers are offered', async () => {
    const wrapper = await mountLogin({ providers: [students] })

    expect(usernameField(wrapper).exists()).toBe(false)
    const toggle = wrapper.find('.password-login-toggle button')
    expect(toggle.exists()).toBe(true)
    expect(toggle.text()).toBe('Administrator login')
    await toggle.trigger('click')
    expect(usernameField(wrapper).exists()).toBe(true)
    expect(wrapper.find('button.login-button').exists()).toBe(true)
    wrapper.unmount()
  })

  it('offers a folded provider only inside the administrator section', async () => {
    const wrapper = await mountLogin({ providers: [students, members], config: { foldedLoginProviders: ['members'] } })

    expect(wrapper.findAll('.oidc-providers .oidc-login-button').map(button => button.text())).toEqual(['Log in with Student ID'])
    expect(wrapper.findAll('.oidc-end-session a').map(link => link.text())).toEqual(['Log out of Student ID'])
    expect(wrapper.text()).not.toContain('Members')

    await wrapper.find('.password-login-toggle button').trigger('click')
    expect(wrapper.findAll('.password-login .folded-providers .oidc-login-button').map(button => [button.text(), button.attributes('href')]))
      .toEqual([['Log in with Members', '/client/api?command=oidcLogin&provider=members']])
    expect(wrapper.findAll('.oidc-end-session a').map(link => link.text())).toEqual(['Log out of Student ID', 'Log out of Members'])
    expect(usernameField(wrapper).exists()).toBe(true)
    wrapper.unmount()
  })

  it('opens the administrator section for the outcome of a folded provider', async () => {
    const wrapper = await mountLogin({
      providers: [students, members],
      config: { foldedLoginProviders: ['members'] },
      search: '?oidcerror=notadmitted&provider=members'
    })

    expect(wrapper.find('.oidc-error').exists()).toBe(true)
    expect(wrapper.findAll('.password-login .folded-providers .oidc-login-button').map(button => button.text())).toEqual(['Log in with Members'])
    wrapper.unmount()
  })

  it('shows folded providers at once when no other provider is offered', async () => {
    const wrapper = await mountLogin({ providers: [members], config: { foldedLoginProviders: ['members'] } })

    expect(wrapper.find('.password-login-toggle').exists()).toBe(false)
    expect(wrapper.findAll('.password-login .folded-providers .oidc-login-button').map(button => button.text())).toEqual(['Log in with Members'])
    expect(usernameField(wrapper).exists()).toBe(true)
    wrapper.unmount()
  })

  it('keeps the password form open when the option says so or no provider is offered', async () => {
    const unfolded = await mountLogin({ providers: [students], config: { foldPasswordLogin: false } })
    expect(unfolded.find('.password-login-toggle').exists()).toBe(false)
    expect(usernameField(unfolded).exists()).toBe(true)
    unfolded.unmount()

    const alone = await mountLogin({ providers: [] })
    expect(alone.find('.oidc-providers').exists()).toBe(false)
    expect(alone.find('.password-login-toggle').exists()).toBe(false)
    expect(usernameField(alone).exists()).toBe(true)
    alone.unmount()
  })

  it('explains a sign-in that ended without a session and drops the outcome from the address', async () => {
    const wrapper = await mountLogin({ providers: [students], search: '?oidcerror=expired&provider=students' })

    expect(wrapper.find('.oidc-error').exists()).toBe(true)
    expect(wrapper.find('.oidc-error').text()).toContain(messages['message.oidc.error.expired'])
    expect(wrapper.find('.oidc-refusal').exists()).toBe(false)
    expect(window.location.search).toBe('')
    expect(window.location.pathname).toBe('/client/')
    wrapper.unmount()
  })

  it('shows the refusal of the provider that did not admit the person', async () => {
    const wrapper = await mountLogin({ providers: [students, members], search: '?oidcerror=notadmitted&provider=students' })

    expect(wrapper.find('.oidc-error').exists()).toBe(true)
    expect(wrapper.find('.oidc-error').text()).toContain(messages['message.oidc.error.notadmitted'])
    expect(wrapper.find('.oidc-refusal').html()).toContain('Only <b>majors</b> may use it.')
    wrapper.unmount()
  })

  it('shows only its own words for an unknown outcome or provider', async () => {
    const wrapper = await mountLogin({ providers: [students], search: '?oidcerror=%3Cb%3Eboom%3C%2Fb%3E&provider=%3Cscript%3E' })

    expect(wrapper.find('.oidc-error').exists()).toBe(true)
    expect(wrapper.find('.oidc-error').text()).toContain(messages['message.oidc.error'])
    expect(wrapper.find('.oidc-error').html()).not.toMatch(/<b>boom|<script/)
    wrapper.unmount()

    const unknownProvider = await mountLogin({ providers: [students], search: '?oidcerror=notadmitted&provider=elsewhere' })
    expect(unknownProvider.find('.oidc-error').exists()).toBe(true)
    expect(unknownProvider.find('.oidc-error').text()).toContain(messages['message.oidc.error.notadmitted'])
    expect(unknownProvider.find('.oidc-refusal').exists()).toBe(false)
    unknownProvider.unmount()
  })

  it('offers no OAuth tab and asks for no OAuth provider', async () => {
    const wrapper = await mountLogin({ providers: [students] })
    const toggle = wrapper.find('.password-login-toggle button')
    if (toggle.exists()) {
      await toggle.trigger('click')
    }

    expect(wrapper.text()).not.toContain('External')
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual([messages['label.login.portal'], messages['label.login.single.signon']])
    expect(getAPI.mock.calls.map(([command]) => command)).not.toContain('listOauthProvider')
    wrapper.unmount()
  })

  it('leaves no trace of the OAuth sign-in in the client', () => {
    const root = path.resolve(__dirname, '../../../../src')
    const sources = []
    const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).forEach(entry => {
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        walk(file)
      } else if (/\.(js|vue)$/.test(entry.name)) {
        sources.push(file)
      }
    })
    walk(root)
    const leftovers = sources.filter(file => /oauthlogin|verifyOAuthCodeAndGetUser|listOauthProvider|VerifyOauth|OAUTH_PROVIDER/i
      .test(fs.readFileSync(file, 'utf8')))
    expect(leftovers.map(file => path.relative(root, file))).toEqual([])

    const locales = path.resolve(__dirname, '../../../../public/locales')
    const strings = fs.readdirSync(locales).filter(name => name.endsWith('.json')).flatMap(name =>
      Object.keys(JSON.parse(fs.readFileSync(path.join(locales, name), 'utf8'))).filter(key => /oauth|login\.external/.test(key))
        .map(key => name + ': ' + key))
    expect(strings).toEqual([])

    const manifest = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../package.json'), 'utf8'))
    expect(Object.keys(manifest.dependencies).filter(name => /social-auth|google-login/.test(name))).toEqual([])
  })
})
