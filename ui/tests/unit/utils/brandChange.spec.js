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

// Every other spec runs against the shipped brand file; this one swaps the file for another one and
// checks that what the person sees follows it.

import { flushPromises, mount } from '@vue/test-utils'

import common from '../../common'
import { getAPI, postAPI } from '@/api'
import GlobalFooter from '@/components/page/GlobalFooter'
import Logo from '@/components/header/Logo'
import SetupTwoFaAtLogin from '@/views/dashboard/SetupTwoFaAtLogin'
import SetupTwoFaAtUserProfile from '@/views/iam/SetupTwoFaAtUserProfile'
import OnboardingDashboard from '@/views/dashboard/OnboardingDashboard'
import { applyBrand, brandCredit, brandUpstreamVersion, totpUri } from '@/utils/brand'
import { i18n, loadLanguageAsync } from '@/locales'

jest.mock('@brand/brand.json', () => ({
  name: 'Acme Cloud',
  title: 'Acme Console',
  twoFaIssuer: 'Acme Auth',
  credit: { en: 'Built on Apache CloudStack 4.99.1.2 by Acme', ko: 'Acme가 Apache CloudStack으로 만듦' }
}))
jest.mock('@/views/iam/ChangeUserPassword.vue', () => ({ name: 'ChangeUserPassword', render: () => null }))
jest.mock('@/views/infra/zone/ZoneWizard.vue', () => ({ name: 'ZoneWizard', render: () => null }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { twoFaIssuer: 'CloudStack' }, dispatch: jest.fn() } }))
jest.mock('vue-qrious', () => ({ name: 'VueQrious', props: ['value'], render: () => null }))

jest.setTimeout(30000)

const mountFooter = (locale = 'en') => common.createFactory(GlobalFooter, {
  i18n: common.createMockI18n(locale, {}),
  store: common.createMockStore({ user: { info: { roletype: 'Admin' }, features: { cloudstackversion: '4.23.0.0' } } }),
  mocks: { $config: { footer: '' } }
})

describe('changing brand.json', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockResolvedValue({ listusertwofactorauthenticatorprovidersresponse: { providers: [{ name: 'totp' }] } })
    postAPI.mockResolvedValue({
      setupusertwofactorauthenticationresponse: { setup2fa: { secretcode: 'JBSWY3DPEHPK3PXP', username: 'jdoe' } }
    })
  })

  it('changing the name in brand.json changes the title, footer and 2FA issuer', async () => {
    expect(applyBrand({}).appTitle).toBe('Acme Console')

    const footer = mountFooter()
    expect(footer.text()).toContain('Built on Apache CloudStack 4.99.1.2 by Acme')
    expect(footer.text()).toContain('Acme Cloud')
    expect(mountFooter('ko_KR').text()).toContain('Acme가 Apache CloudStack으로 만듦')
    expect(brandCredit('en')).toBe('Built on Apache CloudStack 4.99.1.2 by Acme')
    expect(brandUpstreamVersion()).toBe('4.99.1.2')

    expect(totpUri('jdoe', 'SECRET')).toBe('otpauth://totp/Acme%20Auth:jdoe?secret=SECRET&issuer=Acme%20Auth')
    for (const component of [SetupTwoFaAtLogin, SetupTwoFaAtUserProfile]) {
      const wrapper = common.createFactory(component, {
        i18n: common.createMockI18n('en', {}),
        props: { resource: { id: 'u-1', username: 'jdoe' } },
        mocks: { $config: { theme: {} } }
      })
      await flushPromises()
      wrapper.vm.form.selectedProvider = 'totp'
      wrapper.vm.setup2FAProvider()
      await flushPromises()
      expect(wrapper.vm.totpUrl).toBe('otpauth://totp/Acme%20Auth:jdoe?secret=JBSWY3DPEHPK3PXP&issuer=Acme%20Auth')
    }

    const onboarding = mount(OnboardingDashboard, {
      global: {
        plugins: [common.createMockI18n('en', {}), common.createMockStore({ user: { info: { id: 'u-1', username: 'admin' } } })],
        provide: { parentFetchData: () => {} }
      }
    })
    const docs = onboarding.findAll('a').map(link => link.attributes('href'))
    expect(docs).toContain('https://docs.cloudstack.apache.org/en/4.99.1.2/')
    expect(docs).toContain('https://docs.cloudstack.apache.org/en/4.99.1.2/releasenotes/')

    const logo = common.createFactory(Logo, { mocks: { $config: { logo: 'a.svg', minilogo: 'b.svg', theme: {} } } })
    expect(logo.props('title')).toBe('Acme Cloud')
  })

  it('binds the product name into every locale string that takes it', async () => {
    global.fetch = jest.fn(() => Promise.resolve({
      json: () => Promise.resolve({
        'label.about.app': 'About {product}',
        'message.plain': 'No parameter here',
        'message.twice': '{product} and {product}',
        'message.other': 'Hello {name}'
      })
    }))
    try {
      await loadLanguageAsync('en')
      expect(i18n.global.t('label.about.app')).toBe('About Acme Cloud')
      expect(i18n.global.t('message.plain')).toBe('No parameter here')
      expect(i18n.global.t('message.twice')).toBe('Acme Cloud and Acme Cloud')
      expect(i18n.global.t('message.other', { name: 'Ann' })).toBe('Hello Ann')
    } finally {
      delete global.fetch
    }
  })
})
