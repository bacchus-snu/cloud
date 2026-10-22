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

import { mount } from '@vue/test-utils'

import common from '../../../common'
import brandFile from '@brand/brand.json'
import OnboardingDashboard from '@/views/dashboard/OnboardingDashboard'

jest.mock('@/views/iam/ChangeUserPassword.vue', () => ({ name: 'ChangeUserPassword', render: () => null }))
jest.mock('@/views/infra/zone/ZoneWizard.vue', () => ({ name: 'ZoneWizard', render: () => null }))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ __esModule: true, default: { getters: {}, dispatch: jest.fn() } }))

jest.setTimeout(30000)

const mountOnboarding = () => mount(OnboardingDashboard, {
  global: {
    plugins: [
      common.createMockI18n('en', {}),
      common.createMockStore({ user: { info: { id: 'u-1', username: 'admin' } } })
    ],
    provide: { parentFetchData: () => {} }
  }
})

describe('views > dashboard > OnboardingDashboard.vue', () => {
  beforeEach(() => {
    jest.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('links the upstream documentation of the release the credit names', () => {
    const release = /\d+(?:\.\d+)+/.exec(brandFile.credit.en)[0]
    expect(release).toBe('4.23.0.0')

    const links = mountOnboarding().findAll('a').map(link => link.attributes('href'))
    expect(links).toContain('https://docs.cloudstack.apache.org/en/' + release + '/')
    expect(links).toContain('https://docs.cloudstack.apache.org/en/' + release + '/releasenotes/')
    expect(links.filter(href => /latest|4\.23\.0\.100/.test(href))).toEqual([])
  })

  it('sends nobody to the upstream community channels or the issue tracker', () => {
    const links = mountOnboarding().findAll('a').map(link => link.attributes('href'))
    expect(links.filter(href => /mailing-lists|survey|issues|github\.com/.test(href))).toEqual([])
  })
})
