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
import eventBus from '@/config/eventBus'
import { getAPI, postAPI } from '@/api'
import Approvals from '@/views/resourcerequest/Approvals'
import ApproveRequestModal from '@/views/resourcerequest/ApproveRequestModal'
import DecisionModal from '@/views/resourcerequest/DecisionModal'
import RequestTable from '@/views/resourcerequest/RequestTable'

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

const listed = (requests) => Promise.resolve({
  listresourcelimitrequestsresponse: { count: requests.length, resourcelimitrequest: requests }
})

const answers = (queue, history) => (command, params) => {
  switch (command) {
    case 'listResourceLimitRequests':
      return params.account ? listed(history) : listed(queue.filter(request => !params.state || request.state === params.state))
  }
  return Promise.reject(new Error('unexpected ' + command))
}

const approver = { accountid: 'acc-me' }
const own = { ...mockData.pending, id: 'r-own', account: 'carol', accountid: approver.accountid }

const mountPage = async (queue = [mockData.pending, mockData.approved, mockData.rejected], history = [mockData.approved, mockData.rejected],
  { mocks = {}, features = mockData.features } = {}) => {
  getAPI.mockImplementation(answers(queue, history))
  postAPI.mockResolvedValue({})
  const store = common.createMockStore({
    user: { apis: mockData.approverApis, features, info: approver, timezoneoffset: 0, usebrowsertimezone: false }
  })
  const i18n = common.createMockI18n('en', { en: messages })
  const router = common.createMockRouter()
  const wrapper = common.createFactory(Approvals, { router, store, i18n, mocks })
  await flushPromises()
  return wrapper
}

const buttonLabelled = (wrapper, label) => wrapper.findAll('button').find(button => button.text() === label)

describe('Views > Resource requests > Approvals.vue', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse(mockData.now))
  })

  it('lists the pending requests of every account first, with what was asked', async () => {
    const wrapper = await mountPage()

    expect(getAPI).toHaveBeenCalledWith('listResourceLimitRequests', expect.objectContaining({ listall: true, state: 'Pending' }))
    const rows = wrapper.findAll('.approval-list tbody tr.ant-table-row')
    expect(rows).toHaveLength(1)
    const text = rows[0].text()
    expect(text).toContain('alice')
    expect(text).toContain('ROOT/students')
    expect(text).toContain('16 vCPU')
    expect(text).toContain('a100-40g × 4')
    expect(text).toContain('2026-12-31')
    expect(text).toContain('thesis experiments')
    expect(buttonLabelled(wrapper, 'Approve')).toBeTruthy()
    expect(buttonLabelled(wrapper, 'Reject')).toBeTruthy()
    wrapper.unmount()
  })

  it('offers a grant for revoking, not a pending request', async () => {
    const wrapper = await mountPage()
    await wrapper.findAll('.ant-radio-button-wrapper').find(label => label.text() === 'Approved').find('input').setValue(true)
    await flushPromises()

    expect(getAPI).toHaveBeenLastCalledWith('listResourceLimitRequests', expect.objectContaining({ listall: true, state: 'Approved' }))
    expect(buttonLabelled(wrapper, 'Revoke')).toBeTruthy()
    expect(buttonLabelled(wrapper, 'Approve')).toBeFalsy()
    wrapper.unmount()
  })

  it('approves with the values and the expiry the approver adjusted', async () => {
    const changed = jest.fn()
    eventBus.on('resource-limit-request-changed', changed)
    const wrapper = await mountPage()
    await buttonLabelled(wrapper, 'Approve').trigger('click')
    await flushPromises()

    const modal = wrapper.findComponent(ApproveRequestModal)
    expect(modal.props('request').id).toBe('r-pending')
    expect(modal.vm.form).toMatchObject({
      cpu: 16, memoryGib: 64, storage: 1000, gpu: { 'a100-40g': 4, a6000: 0 }, expiry: '2026-12-31'
    })

    Object.assign(modal.vm.form, { cpu: 8, memoryGib: 32, storage: 500, expiry: '2026-11-30', comment: 'half for now' })
    modal.vm.form.gpu['a100-40g'] = 2
    await modal.vm.submit()
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('approveResourceLimitRequest', {
      id: 'r-pending',
      cpu: 8,
      memory: 32768,
      storage: 500,
      'gpu[0].type': 'a100-40g',
      'gpu[0].count': 2,
      'gpu[1].type': 'a6000',
      'gpu[1].count': 0,
      expiry: '2026-11-30',
      comment: 'half for now'
    })
    expect(changed).toHaveBeenCalled()
    expect(getAPI.mock.calls.filter(([command, params]) => command === 'listResourceLimitRequests' && !params.account)).toHaveLength(2)
    eventBus.off('resource-limit-request-changed', changed)
    wrapper.unmount()
  })

  it('starts the expiry of an approval anew when the requested day has passed', async () => {
    const stale = { ...mockData.pending, expiry: '2026-10-01T14:59:59+0000' }
    const wrapper = await mountPage([stale])
    await buttonLabelled(wrapper, 'Approve').trigger('click')
    await flushPromises()

    expect(wrapper.findComponent(ApproveRequestModal).vm.form.expiry).toBe('2026-11-19')
    wrapper.unmount()
  })

  it('rejects with the reason', async () => {
    const wrapper = await mountPage()
    await buttonLabelled(wrapper, 'Reject').trigger('click')
    await flushPromises()

    const modal = wrapper.findComponent(DecisionModal)
    expect(modal.props('kind')).toBe('reject')
    modal.vm.reason = 'ask for less'
    await modal.vm.submit()
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('rejectResourceLimitRequest', { id: 'r-pending', reason: 'ask for less' })
    wrapper.unmount()
  })

  it('shows the grant history of an account and revokes a grant from it', async () => {
    const wrapper = await mountPage()
    await buttonLabelled(wrapper, 'History').trigger('click')
    await flushPromises()

    expect(getAPI).toHaveBeenCalledWith('listResourceLimitRequests', { account: 'alice', domainid: 'dom-students', listall: true })
    const history = wrapper.findAllComponents(RequestTable).find(table => table.classes('history-list'))
    const rows = history.findAll('tbody tr.ant-table-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('a100-40g × 2')
    expect(rows[0].text()).toContain('a100-40g × 4')

    await history.findAll('button').find(button => button.text() === 'Revoke').trigger('click')
    await flushPromises()
    const modal = wrapper.findComponent(DecisionModal)
    expect(modal.props('kind')).toBe('revoke')
    modal.vm.reason = 'left the lab'
    await modal.vm.submit()
    await flushPromises()

    expect(postAPI).toHaveBeenCalledWith('revokeResourceLimitRequest', { id: 'r-approved', reason: 'left the lab' })
    wrapper.unmount()
  })

  it('offers no decision on the approver\'s own request, which another approver decides', async () => {
    const wrapper = await mountPage([mockData.pending, own])

    const rows = wrapper.findAll('.approval-list tbody tr.ant-table-row')
    expect(rows).toHaveLength(2)
    const labels = (row) => row.findAll('button').map(button => button.text())
    expect(labels(rows[0])).toEqual(['Approve', 'Reject', 'History'])
    expect(labels(rows[1])).toEqual(['History'])
    wrapper.unmount()
  })

  it('offers no revoke on the approver\'s own grant, which its owner gives up', async () => {
    const ownGrant = { ...mockData.approved, id: 'r-own-grant', account: 'carol', accountid: approver.accountid }
    const wrapper = await mountPage([mockData.approved, ownGrant])
    await wrapper.findAll('.ant-radio-button-wrapper').find(label => label.text() === 'Approved').find('input').setValue(true)
    await flushPromises()

    const rows = wrapper.findAll('.approval-list tbody tr.ant-table-row')
    expect(rows.map(row => row.findAll('button').map(button => button.text()))).toEqual([['Revoke', 'History'], ['History']])
    wrapper.unmount()
  })

  it('takes the GPU types for the approval from the server\'s capabilities and asks for no offerings', async () => {
    const wrapper = await mountPage()

    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listServiceOfferings')
    await buttonLabelled(wrapper, 'Approve').trigger('click')
    await flushPromises()
    const modal = wrapper.findComponent(ApproveRequestModal)
    expect(modal.props('gpuTypes')).toEqual(['a100-40g', 'a6000'])
    expect(Object.keys(modal.vm.form.gpu)).toEqual(['a100-40g', 'a6000'])
    wrapper.unmount()
  })

  it('offers only the GPU types the request names where the server lists none', async () => {
    const features = { ...mockData.features }
    delete features.quotagputypes
    const wrapper = await mountPage(undefined, undefined, { features })

    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listServiceOfferings')
    await buttonLabelled(wrapper, 'Approve').trigger('click')
    await flushPromises()
    const modal = wrapper.findComponent(ApproveRequestModal)
    expect(modal.props('gpuTypes')).toEqual([])
    expect(Object.keys(modal.vm.form.gpu)).toEqual(['a100-40g'])
    wrapper.unmount()
  })
})
