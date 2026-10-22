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

import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import brandFile from '@brand/brand.json'
import { getAPI, postAPI } from '@/api'
import SetupTwoFaAtLogin from '@/views/dashboard/SetupTwoFaAtLogin'
import SetupTwoFaAtUserProfile from '@/views/iam/SetupTwoFaAtUserProfile'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
// the server still answers with its own default issuer on a password sign-in
jest.mock('@/store', () => ({ __esModule: true, default: { getters: { twoFaIssuer: 'CloudStack' }, dispatch: jest.fn() } }))
jest.mock('vue-qrious', () => ({ name: 'VueQrious', props: ['value'], render: () => null }))

jest.setTimeout(30000)

const otpAuthUrl = async (component, provider, username = 'jdoe') => {
  getAPI.mockResolvedValue({ listusertwofactorauthenticatorprovidersresponse: { providers: [{ name: 'totp' }] } })
  postAPI.mockResolvedValue({
    setupusertwofactorauthenticationresponse: { setup2fa: { secretcode: 'JBSWY3DPEHPK3PXP', username } }
  })
  const wrapper = common.createFactory(component, {
    i18n: common.createMockI18n('en', {}),
    props: { resource: { id: 'u-1', username } },
    mocks: { $config: { theme: {} } }
  })
  await flushPromises()
  wrapper.vm.form.selectedProvider = provider
  wrapper.vm.setup2FAProvider()
  await flushPromises()
  return wrapper.vm.totpUrl
}

describe('views > Setup two-factor authentication', () => {
  beforeEach(() => {
    jest.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('2FA issuer is SNUCSE Cloud', async () => {
    expect(brandFile.twoFaIssuer).toBe('SNUCSE Cloud')

    for (const component of [SetupTwoFaAtLogin, SetupTwoFaAtUserProfile]) {
      for (const provider of ['totp', 'othertotp']) {
        const url = new URL(await otpAuthUrl(component, provider))
        expect(url.protocol).toBe('otpauth:')
        expect(url.host).toBe('totp')
        expect(decodeURIComponent(url.pathname)).toBe('/SNUCSE Cloud:jdoe')
        expect(url.searchParams.get('issuer')).toBe('SNUCSE Cloud')
        expect(url.searchParams.get('secret')).toBe('JBSWY3DPEHPK3PXP')
      }
    }
  })

  it('writes the issuer and the account into the key URI percent-encoded', async () => {
    const url = await otpAuthUrl(SetupTwoFaAtLogin, 'totp', 'ann smith@example.org')
    expect(url).toBe('otpauth://totp/SNUCSE%20Cloud:ann%20smith%40example.org?secret=JBSWY3DPEHPK3PXP&issuer=SNUCSE%20Cloud')
  })
})
