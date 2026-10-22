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

import mockAxios from '../../../mock/mockAxios'
import common from '../../../common'
import mockData from '../../../mockData/ResourceLimitRequest.mock.json'
import messages from '@public/locales/en.json'
import { getAPI, postAPI } from '@/api'
import MyRequests from '@/views/resourcerequest/MyRequests'
import RequestForm from '@/views/resourcerequest/RequestForm'

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

const answers = (requests, failing = []) => (command) => {
  if (failing.includes(command)) {
    return Promise.reject(new Error(command + ' failed'))
  }
  switch (command) {
    case 'listResourceLimitRequests':
      return Promise.resolve({ listresourcelimitrequestsresponse: { count: requests.length, resourcelimitrequest: requests } })
    case 'listResourceLimits':
      return Promise.resolve({ listresourcelimitsresponse: { count: mockData.limits.length, resourcelimit: mockData.limits } })
  }
  return Promise.reject(new Error('unexpected ' + command))
}

const mountPage = async (requests, query = {}, mocks = {}, features = mockData.features, failing = []) => {
  getAPI.mockImplementation(answers(requests, failing))
  const store = common.createMockStore({
    user: { apis: mockData.userApis, features, info: {}, timezoneoffset: 0, usebrowsertimezone: false }
  })
  const i18n = common.createMockI18n('en', { en: messages })
  const router = common.createMockRouter()
  router.push({ path: '/', query })
  await router.isReady()
  const wrapper = common.createFactory(MyRequests, { router, store, i18n, mocks })
  await flushPromises()
  return wrapper
}

const buttonLabelled = (wrapper, label) => wrapper.findAll('button').find(button => button.text() === label)

describe('Views > Resource requests > MyRequests.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse(mockData.now))
  })

  it('request list shows state, values, expiry and the effective quota', async () => {
    const wrapper = await mountPage([mockData.pending, mockData.approved, mockData.rejected])

    const rows = wrapper.findAll('.request-list tbody tr.ant-table-row')
    expect(rows).toHaveLength(3)

    const pending = rows[0].text()
    expect(pending).toContain('Pending')
    expect(pending).toContain('16 vCPU')
    expect(pending).toContain('64 GiB RAM')
    expect(pending).toContain('1000 GiB storage')
    expect(pending).toContain('a100-40g × 4')
    expect(pending).toContain('2026-12-31')
    expect(pending).toContain('thesis experiments')

    const approved = rows[1].text()
    expect(approved).toContain('Approved')
    expect(approved).toContain('8 vCPU')
    expect(approved).toContain('32 GiB RAM')
    expect(approved).toContain('500 GiB storage')
    expect(approved).toContain('a100-40g × 2')
    expect(approved).toContain('2026-11-30')
    expect(approved).toContain('half for now')

    expect(rows[2].text()).toContain('Rejected')

    const quota = (name) => wrapper.find('.effective-quota [data-quota="' + name + '"] .quota-value').text()
    expect(quota('cpu')).toBe('8')
    expect(quota('memory')).toBe('32 GiB')
    expect(quota('storage')).toBe('500 GiB')
    expect(quota('gpu:a100-40g')).toBe('2')
    expect(quota('gpu:a6000')).toBe('0')
    expect(wrapper.find('.effective-quota [data-quota="gpu:cpu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renew prefills a new request', async () => {
    const wrapper = await mountPage([mockData.approved])
    expect(wrapper.findComponent(RequestForm).exists()).toBe(false)

    await buttonLabelled(wrapper, 'Renew').trigger('click')
    await flushPromises()

    const form = wrapper.findComponent(RequestForm)
    expect(form.exists()).toBe(true)
    expect(form.vm.form).toMatchObject({
      cpu: 8,
      memoryGib: 32,
      storage: 500,
      gpu: { 'a100-40g': 2 },
      expiry: '2026-11-19',
      reason: 'course project'
    })

    postAPI.mockResolvedValue({ createresourcelimitrequestresponse: { resourcelimitrequest: { id: 'r-new', state: 'Pending' } } })
    await form.find('form').trigger('submit')
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('createResourceLimitRequest', {
      cpu: 8,
      memory: 32768,
      storage: 500,
      'gpu[0].type': 'a100-40g',
      'gpu[0].count': 2,
      expiry: '2026-11-19',
      reason: 'course project'
    })
    wrapper.unmount()
  })

  it('opens the renewal of the grant that the expiry banner links to', async () => {
    const wrapper = await mountPage([mockData.approved], { renew: 'r-approved' })

    const form = wrapper.findComponent(RequestForm)
    expect(form.exists()).toBe(true)
    expect(form.vm.form).toMatchObject({ cpu: 8, memoryGib: 32, storage: 500, gpu: { 'a100-40g': 2 } })
    wrapper.unmount()
  })

  it('offers no second request while one waits for a decision', async () => {
    const wrapper = await mountPage([mockData.pending, mockData.approved])

    expect(buttonLabelled(wrapper, 'New request').attributes('disabled')).toBeDefined()
    expect(buttonLabelled(wrapper, 'Renew').attributes('disabled')).toBeDefined()
    expect(buttonLabelled(wrapper, 'Cancel request').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('cancels a pending request, and gives up a grant, with cancelResourceLimitRequest', async () => {
    postAPI.mockResolvedValue({})
    const confirm = jest.fn(({ onOk }) => onOk())
    const wrapper = await mountPage([mockData.pending, mockData.approved], {}, { $confirm: confirm })

    await buttonLabelled(wrapper, 'Cancel request').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenLastCalledWith('cancelResourceLimitRequest', { id: 'r-pending' })

    await buttonLabelled(wrapper, 'Give up').trigger('click')
    await flushPromises()
    expect(postAPI).toHaveBeenLastCalledWith('cancelResourceLimitRequest', { id: 'r-approved' })
    expect(getAPI.mock.calls.filter(([command]) => command === 'listResourceLimitRequests')).toHaveLength(3)
    wrapper.unmount()
  })

  it('asks for a new request with the expiry the server proposes, and nothing prefilled', async () => {
    postAPI.mockResolvedValue({ createresourcelimitrequestresponse: { resourcelimitrequest: { id: 'r-new' } } })
    const wrapper = await mountPage([])

    await buttonLabelled(wrapper, 'New request').trigger('click')
    await flushPromises()
    const form = wrapper.findComponent(RequestForm)
    expect(form.vm.form).toMatchObject({ cpu: 0, memoryGib: 0, storage: 0, gpu: { 'a100-40g': 0, a6000: 0 }, expiry: '2026-11-19', reason: '' })

    Object.assign(form.vm.form, { cpu: 2, memoryGib: 8, reason: 'a course' })
    form.vm.form.gpu.a6000 = 1
    await form.find('form').trigger('submit')
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('createResourceLimitRequest', {
      cpu: 2, memory: 8192, storage: 0, 'gpu[0].type': 'a6000', 'gpu[0].count': 1, expiry: '2026-11-19', reason: 'a course'
    })
    expect(wrapper.findComponent(RequestForm).exists()).toBe(false)
    wrapper.unmount()
  })

  it('refuses a request without an expiry date while the server requires one', async () => {
    const wrapper = await mountPage([])

    await buttonLabelled(wrapper, 'New request').trigger('click')
    await flushPromises()
    const form = wrapper.findComponent(RequestForm)
    form.vm.form.expiry = null
    await form.find('form').trigger('submit')
    await flushPromises()

    expect(postAPI).not.toHaveBeenCalled()
    expect(form.text()).toContain('Please choose an expiry date.')
    wrapper.unmount()
  })

  it('makes no request, and offers none, while the server does not allow the account to request', async () => {
    const wrapper = await mountPage([mockData.approved], {}, {}, { ...mockData.features, resourcelimitrequestallowed: false })

    expect(getAPI).not.toHaveBeenCalled()
    expect(wrapper.find('.ant-alert').text()).toContain('The limits of this account are not set by requests.')
    expect(buttonLabelled(wrapper, 'New request')).toBeUndefined()
    expect(wrapper.find('.request-list').exists()).toBe(false)
    wrapper.unmount()
  })

  it('takes the GPU types from the server\'s capabilities and asks for no offerings', async () => {
    const wrapper = await mountPage([mockData.approved])

    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listServiceOfferings')
    expect(wrapper.find('.ant-alert-warning').exists()).toBe(false)
    await buttonLabelled(wrapper, 'New request').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(RequestForm).props('gpuTypes')).toEqual(['a100-40g', 'a6000'])
    expect(Object.keys(wrapper.findComponent(RequestForm).vm.form.gpu)).toEqual(['a100-40g', 'a6000'])

    wrapper.unmount()
    const other = await mountPage([mockData.approved], {}, {}, { ...mockData.features, quotagputypes: ['h100'] })
    await buttonLabelled(other, 'New request').trigger('click')
    await flushPromises()
    expect(Object.keys(other.findComponent(RequestForm).vm.form.gpu)).toEqual(['h100'])
    other.unmount()
  })

  it('says that the GPU types are unknown where the server lists none, offers no GPU inputs, and does not pretend to know the CPU quota', async () => {
    const features = { ...mockData.features }
    delete features.quotagputypes
    const wrapper = await mountPage([mockData.approved], {}, {}, features)

    expect(wrapper.find('.ant-alert-warning').text()).toContain('GPU types could not be loaded')
    expect(wrapper.find('.effective-quota [data-quota="cpu"] .quota-value').text()).toBe('\u2014')

    await buttonLabelled(wrapper, 'New request').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(RequestForm).vm.form.gpu).toEqual({})
    wrapper.unmount()
  })

  it('reports a failed fetch of the limits instead of showing an empty summary silently', async () => {
    const notifyError = jest.fn()
    const wrapper = await mountPage([mockData.approved], {}, { $notifyError: notifyError }, mockData.features, ['listResourceLimits'])

    expect(notifyError).toHaveBeenCalledTimes(1)
    expect(notifyError.mock.calls[0][0].message).toBe('listResourceLimits failed')
    expect(wrapper.find('.effective-quota [data-quota="storage"] .quota-value').text()).toBe('\u2014')
    wrapper.unmount()
  })

  it('has no warning about GPU types while they are known', async () => {
    const wrapper = await mountPage([mockData.approved])

    expect(wrapper.find('.ant-alert-warning').exists()).toBe(false)
    wrapper.unmount()
  })
})
