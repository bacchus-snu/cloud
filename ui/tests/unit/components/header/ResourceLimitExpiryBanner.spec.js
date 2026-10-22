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
import { createStore } from 'vuex'

import mockAxios from '../../../mock/mockAxios'
import common from '../../../common'
import mockData from '../../../mockData/ResourceLimitRequest.mock.json'
import messages from '@public/locales/en.json'
import eventBus from '@/config/eventBus'
import { getAPI } from '@/api'
import ResourceLimitExpiryBanner from '@/components/header/ResourceLimitExpiryBanner'

jest.mock('axios', () => mockAxios)
jest.mock('@/vue-app', () => ({
  vueProps: {
    $localStorage: {
      set: jest.fn((key, value) => {}),
      get: jest.fn((key) => null)
    }
  }
}))
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))

const warned = (days, expirywarningdays = 7, id = 'r-warned') => ({
  ...mockData.approved,
  id,
  expirywarningdays,
  approvedexpiry: new Date(Date.parse(mockData.now) + days * 24 * 60 * 60 * 1000).toISOString()
})

const answers = ({ approved = [], pending = [] }) => (command, params) => {
  const list = params.state === 'Approved' ? approved : pending
  return Promise.resolve({ listresourcelimitrequestsresponse: { count: list.length, resourcelimitrequest: list } })
}

const mountBanner = async (lists, apis = mockData.userApis, features = mockData.features) => {
  getAPI.mockImplementation(answers(lists))
  const store = common.createMockStore({ user: { apis, features } })
  const i18n = common.createMockI18n('en', { en: messages })
  const router = common.createMockRouter()
  const wrapper = common.createFactory(ResourceLimitExpiryBanner, { router, store, i18n })
  await flushPromises()
  return wrapper
}

describe('Components > Header > ResourceLimitExpiryBanner.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse(mockData.now))
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('warns of a grant the server has warned of, and offers to renew it', async () => {
    const wrapper = await mountBanner({ approved: [warned(7)] })

    const text = wrapper.find('.resource-limit-expiry-banner').text()
    expect(text).toContain('The grant of 8 vCPU, 32 GiB RAM, 500 GiB storage, a100-40g × 2 ends on 2026-10-27 (in 7 days).')
    const renew = wrapper.find('a.expiry-renew')
    expect(renew.text()).toBe('Renew')
    expect(renew.attributes('href')).toContain('renew=r-warned')
    wrapper.unmount()
  })

  it('stays away from grants the server has not warned of', async () => {
    const wrapper = await mountBanner({ approved: [{ ...warned(7), expirywarningdays: undefined }, mockData.approved] })

    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('says when the end is today or tomorrow', async () => {
    const wrapper = await mountBanner({ approved: [{ ...warned(0.2, 1, 'r-today'), approvedcpu: 32 }, warned(1.2, 1, 'r-tomorrow')] })

    const notices = wrapper.findAll('.expiry-notice').map(notice => notice.text())
    expect(notices[0]).toContain('(today)')
    expect(notices[1]).toContain('(tomorrow)')
    wrapper.unmount()
  })

  it('does not offer a second request while one is waiting for a decision', async () => {
    const wrapper = await mountBanner({ approved: [warned(7)], pending: [mockData.pending] })

    expect(wrapper.find('a.expiry-renew').exists()).toBe(false)
    expect(wrapper.find('.resource-limit-expiry-banner').text()).toContain('A renewal request is waiting for a decision.')
    wrapper.unmount()
  })

  it('asks the server nothing for an account that cannot request', async () => {
    const wrapper = await mountBanner({ approved: [warned(7)] }, {})

    expect(getAPI).not.toHaveBeenCalled()
    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('asks the server nothing while it does not allow the account to request', async () => {
    const wrapper = await mountBanner({ approved: [warned(7)] }, mockData.userApis, { ...mockData.features, resourcelimitrequestallowed: false })

    expect(getAPI).not.toHaveBeenCalled()
    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(false)
    wrapper.unmount()
  })

  it('hides, and stops asking, when the server stops allowing the account to request, and returns when it allows again', async () => {
    getAPI.mockImplementation(answers({ approved: [warned(7)] }))
    const started = jest.spyOn(global, 'setInterval')
    const stopped = jest.spyOn(global, 'clearInterval')
    const store = createStore({
      state: { apis: mockData.userApis, features: mockData.features },
      getters: { apis: state => state.apis, features: state => state.features },
      mutations: { SET_FEATURES: (state, value) => { state.features = value } }
    })
    const wrapper = common.createFactory(ResourceLimitExpiryBanner, {
      router: common.createMockRouter(), store, i18n: common.createMockI18n('en', { en: messages })
    })
    await flushPromises()
    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(true)
    expect(started).toHaveBeenCalledTimes(1)
    const asked = getAPI.mock.calls.length

    store.commit('SET_FEATURES', { ...mockData.features, resourcelimitrequestallowed: false })
    await flushPromises()
    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(false)
    expect(stopped).toHaveBeenCalled()
    eventBus.emit('resource-limit-request-changed')
    await flushPromises()
    expect(getAPI.mock.calls.length).toBe(asked)

    store.commit('SET_FEATURES', mockData.features)
    await flushPromises()
    expect(started).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(true)
    wrapper.unmount()
  })

  describe('when another grant lasts longer', () => {
    const lasting = (days, values = {}) => ({
      ...mockData.approved,
      id: 'r-later',
      expirywarningdays: undefined,
      approvedexpiry: new Date(Date.parse(mockData.now) + days * 24 * 60 * 60 * 1000).toISOString(),
      ...values
    })

    it('leaves out the warning of a grant that the other covers in every category', async () => {
      const wrapper = await mountBanner({ approved: [warned(7), lasting(60)] })

      expect(wrapper.find('.resource-limit-expiry-banner').exists()).toBe(false)
      wrapper.unmount()
    })

    it('keeps the warning when the other gives less of one category', async () => {
      const wrapper = await mountBanner({ approved: [warned(7), lasting(60, { approvedstorage: 400 })] })

      expect(wrapper.find('.expiry-notice').text()).toContain('ends on 2026-10-27')
      wrapper.unmount()
    })

    it('keeps the warning when the other ends first', async () => {
      const wrapper = await mountBanner({ approved: [warned(7), lasting(3)] })

      expect(wrapper.find('.expiry-notice').text()).toContain('ends on 2026-10-27')
      wrapper.unmount()
    })

    it('warns of the grant that is not covered and not of the one that is', async () => {
      const covered = warned(7, 7, 'r-covered')
      const alone = { ...warned(5, 7, 'r-alone'), approvedcpu: 32 }
      const wrapper = await mountBanner({ approved: [covered, alone, lasting(60)] })

      const notices = wrapper.findAll('.expiry-notice').map(notice => notice.text())
      expect(notices).toHaveLength(1)
      expect(notices[0]).toContain('32 vCPU')
      wrapper.unmount()
    })
  })
})
