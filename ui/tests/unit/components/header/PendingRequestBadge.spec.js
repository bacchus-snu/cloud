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
import PendingRequestBadge from '@/components/header/PendingRequestBadge'

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

const openRequests = (count, listed = count) => ({
  listresourcelimitrequestsresponse: {
    count,
    resourcelimitrequest: Array.from({ length: listed }, (_, i) => ({ id: 'r' + i, state: 'Pending' }))
  }
})

// Everyone's pending requests are asked with listall, the caller's own without it.
const answers = (all, own = 0) => (command, params) => Promise.resolve(openRequests(params.listall ? all : own))

const i18n = common.createMockI18n('en', { en: messages })

const factory = (apis, features = mockData.features, router = common.createMockRouter()) => {
  const store = common.createMockStore({ user: { apis, features } })
  return common.createFactory(PendingRequestBadge, { router, store, i18n })
}

const reactiveFactory = (apis, features = mockData.features) => {
  const store = createStore({
    state: { apis, features },
    getters: { apis: state => state.apis, features: state => state.features },
    mutations: { SET_APIS: (state, value) => { state.apis = value } }
  })
  return { store, wrapper: common.createFactory(PendingRequestBadge, { router: common.createMockRouter(), store, i18n }) }
}

describe('Components > Header > PendingRequestBadge.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('pending badge counts open requests', async () => {
    getAPI.mockImplementation(answers(3))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()

    expect(getAPI).toHaveBeenCalledWith('listResourceLimitRequests', expect.objectContaining({ state: 'Pending', listall: true }))
    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('3')
    wrapper.unmount()
  })

  it('counts the total, not the page it fetched', async () => {
    getAPI.mockImplementation((command, params) => Promise.resolve(params.listall ? openRequests(42, 1) : openRequests(0)))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()

    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('42')
    wrapper.unmount()
  })

  it('leaves out the approver\'s own pending request, which another approver decides', async () => {
    getAPI.mockImplementation(answers(3, 1))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()

    expect(getAPI).toHaveBeenCalledWith('listResourceLimitRequests', expect.not.objectContaining({ listall: true }))
    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('2')
    wrapper.unmount()
  })

  it('shows no count when the only open request is the approver\'s own', async () => {
    getAPI.mockImplementation(answers(1, 1))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()

    const count = wrapper.find('.ant-badge-count')
    expect(!count.exists() || !count.isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('asks for the approver\'s own request only while the server lets the account request', async () => {
    getAPI.mockImplementation(answers(3, 1))

    const wrapper = factory(mockData.approverApis, { ...mockData.features, resourcelimitrequestallowed: false })
    await flushPromises()

    expect(getAPI).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('3')
    wrapper.unmount()
  })

  it('shows no count while no request is open', async () => {
    getAPI.mockResolvedValue({ listresourcelimitrequestsresponse: {} })

    const wrapper = factory(mockData.approverApis)
    await flushPromises()

    const count = wrapper.find('.ant-badge-count')
    expect(!count.exists() || !count.isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('is absent, and asks the server nothing, without the approve API', async () => {
    const wrapper = factory(mockData.userApis)
    await flushPromises()

    expect(wrapper.find('.pending-request-badge').exists()).toBe(false)
    expect(getAPI).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('counts again after a decision is made on the approver page', async () => {
    let open = 2
    getAPI.mockImplementation((command, params) => Promise.resolve(openRequests(params.listall ? open : 0)))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()
    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('2')

    open = 1
    eventBus.emit('resource-limit-request-changed')
    await flushPromises()

    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('1')
    wrapper.unmount()
  })

  it('stops asking when the approve API leaves the API list, and starts again when it returns', async () => {
    getAPI.mockImplementation(answers(2))
    const started = jest.spyOn(global, 'setInterval')
    const stopped = jest.spyOn(global, 'clearInterval')

    const { store, wrapper } = reactiveFactory(mockData.approverApis)
    await flushPromises()
    expect(started).toHaveBeenCalledTimes(1)
    const asked = getAPI.mock.calls.length

    store.commit('SET_APIS', mockData.userApis)
    await flushPromises()
    expect(stopped).toHaveBeenCalled()
    expect(wrapper.find('.pending-request-badge').exists()).toBe(false)
    eventBus.emit('resource-limit-request-changed')
    await flushPromises()
    expect(getAPI.mock.calls.length).toBe(asked)

    store.commit('SET_APIS', mockData.approverApis)
    await flushPromises()
    expect(started).toHaveBeenCalledTimes(2)
    expect(getAPI.mock.calls.length).toBeGreaterThan(asked)
    expect(wrapper.find('.ant-badge-count').attributes('title')).toBe('2')
    wrapper.unmount()
  })

  it('opens the approver page by click, Enter and Space, as a button', async () => {
    getAPI.mockImplementation(answers(1))
    const router = common.createMockRouter()
    const push = jest.spyOn(router, 'push').mockResolvedValue()

    const wrapper = factory(mockData.approverApis, mockData.features, router)
    await flushPromises()
    const badge = wrapper.find('.pending-request-badge')
    expect(badge.attributes('role')).toBe('button')
    expect(badge.attributes('tabindex')).toBe('0')

    await badge.trigger('click')
    await badge.trigger('keydown.enter')
    const space = new KeyboardEvent('keydown', { key: ' ', code: 'Space', cancelable: true, bubbles: true })
    badge.element.dispatchEvent(space)
    await flushPromises()
    expect(push).toHaveBeenCalledTimes(3)
    expect(push).toHaveBeenCalledWith({ path: '/resourcelimitapproval' })
    expect(space.defaultPrevented).toBe(true)
    wrapper.unmount()
  })

  it('says how many requests wait in its accessible label, and only what it is while none do', async () => {
    getAPI.mockImplementation(answers(3, 1))

    const wrapper = factory(mockData.approverApis)
    await flushPromises()
    expect(wrapper.find('.pending-request-badge').attributes('aria-label')).toBe('Requests waiting for a decision: 2')
    wrapper.unmount()

    getAPI.mockImplementation(answers(0))
    const idle = factory(mockData.approverApis)
    await flushPromises()
    expect(idle.find('.pending-request-badge').attributes('aria-label')).toBe('Requests waiting for a decision')
    idle.unmount()
  })
})
