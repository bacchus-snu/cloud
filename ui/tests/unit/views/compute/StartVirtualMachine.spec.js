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
import { getAPI, postAPI } from '@/api'
import StartVirtualMachine from '@/views/compute/StartVirtualMachine'
import PlacementMoveModal from '@/views/compute/wizard/PlacementMoveModal'
import failedJob from '../../../mockData/FailedJob.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const GiB = 1024 * 1024 * 1024
const vm = { id: 'vm-1', name: 'lab', zoneid: 'z-1', hypervisor: 'KVM', state: 'Stopped' }

const movePlacement = (token, extra = {}) => ({
  verdict: 'move',
  volumes: [{ id: 'vol-1', name: 'ROOT-1', size: 20 * GiB, physicalsize: 5 * GiB }],
  bytestomove: 5 * GiB,
  estimatedseconds: 54,
  previouscopykept: true,
  placementtoken: token,
  ...extra
})

let placements

const open = async (roletype = 'User', apis = ['checkPlacement', 'startVirtualMachine']) => {
  const store = common.createMockStore({
    user: { apis: Object.fromEntries(apis.map(name => [name, { params: [{ name: 'podid', description: '' }, { name: 'clusterid', description: '' }, { name: 'hostid', description: '' }, { name: 'considerlasthost', description: '' }] }])), info: { roletype }, features: {} }
  })
  const $pollJob = jest.fn()
  const $notifyError = jest.fn()
  const $notification = { warning: jest.fn(), error: jest.fn() }
  const wrapper = common.createFactory(StartVirtualMachine, {
    store,
    i18n: common.createMockI18n('en', { en }),
    props: { resource: vm },
    mocks: { $pollJob, $notifyError, $notification }
  })
  await flushPromises()
  wrapper.$pollJob = $pollJob
  wrapper.$notifyError = $notifyError
  wrapper.$notification = $notification
  return wrapper
}

const submit = async (wrapper) => {
  await wrapper.vm.handleSubmit({ preventDefault () {} })
  await flushPromises()
}

const modalOf = (wrapper) => wrapper.findComponent(PlacementMoveModal)
const approve = async () => {
  document.querySelector('.placement-move-approve').click()
  await flushPromises()
}
const started = () => postAPI.mock.calls.filter(call => call[0] === 'startVirtualMachine').map(call => call[1])

describe('StartVirtualMachine asks for the placement first', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    placements = [{ verdict: 'here' }]
    getAPI.mockImplementation((command) => {
      switch (command) {
        case 'checkPlacement':
          return Promise.resolve({ checkplacementresponse: { placement: placements.length > 1 ? placements.shift() : placements[0] } })
        case 'listPods':
          return Promise.resolve({ listpodsresponse: { pod: [{ id: 'p-1', name: 'pod' }] } })
        case 'listClusters':
          return Promise.resolve({ listclustersresponse: { cluster: [{ id: 'c-1', name: 'cluster' }] } })
        case 'listHosts':
          return Promise.resolve({ listhostsresponse: { host: [{ id: 'h-1', name: 'host' }] } })
        default:
          return Promise.resolve({})
      }
    })
    postAPI.mockImplementation(() => Promise.resolve({ startvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('starts at once when the verdict is here or anyhost', async () => {
    for (const verdict of ['here', 'anyhost']) {
      jest.clearAllMocks()
      placements = [{ verdict }]
      const wrapper = await open()
      await submit(wrapper)
      expect(getAPI.mock.calls.find(call => call[0] === 'checkPlacement')[1]).toMatchObject({ virtualmachineid: 'vm-1' })
      expect(started()).toEqual([{ id: 'vm-1', considerlasthost: true }])
      expect(wrapper.emitted('close-action')).toHaveLength(1)
    }
  })

  it('shows move modal and sends migratevolumes and placementtoken on approve', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await submit(wrapper)
    expect(started()).toHaveLength(0)
    expect(modalOf(wrapper).props('visible')).toBe(true)
    expect(modalOf(wrapper).props('operation')).toBe('start')
    expect(document.body.textContent).toContain('ROOT-1')
    expect(document.body.textContent).toContain(en['message.placement.move.unavailable'])

    await approve()
    expect(started()).toEqual([{ id: 'vm-1', considerlasthost: true, migratevolumes: true, placementtoken: 'tok-1' }])
    // the dialog stays until the job is over, so that a stale approval can be asked for again
    expect(wrapper.$pollJob).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('close-action')).toBeUndefined()
    wrapper.$pollJob.mock.calls[0][0].successMethod({ jobresult: {} })
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })

  it('re-shows modal on token mismatch', async () => {
    placements = [movePlacement('tok-1'), movePlacement('tok-2', { bytestomove: 6 * GiB })]
    const wrapper = await open()
    await submit(wrapper)
    await approve()
    expect(started()[0].placementtoken).toBe('tok-1')

    // the server found the world changed since the check: the job failed, and queryAsyncJobResult serves it with the code
    wrapper.$pollJob.mock.calls[0][0].errorMethod(failedJob.moveRequired)
    await flushPromises()
    expect(wrapper.emitted('close-action')).toBeUndefined()
    expect(modalOf(wrapper).props('visible')).toBe(true)
    expect(modalOf(wrapper).props('verdict').placementtoken).toBe('tok-2')
    expect(getAPI.mock.calls.filter(call => call[0] === 'checkPlacement')).toHaveLength(2)

    await approve()
    expect(started()).toHaveLength(2)
    expect(started()[1]).toMatchObject({ migratevolumes: true, placementtoken: 'tok-2' })
  })

  it('does not ask again for a failure that is not the missing approval', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await submit(wrapper)
    await approve()
    wrapper.$pollJob.mock.calls[0][0].errorMethod(failedJob.other)
    await flushPromises()
    expect(getAPI.mock.calls.filter(call => call[0] === 'checkPlacement')).toHaveLength(1)
    expect(modalOf(wrapper).props('visible')).toBe(false)
    expect(wrapper.emitted('close-action')).toHaveLength(1)

    // a failure that carries no code is not taken for the missing approval either
    wrapper.unmount()
    document.body.innerHTML = ''
    const second = await open()
    await submit(second)
    await approve()
    second.$pollJob.mock.calls[0][0].errorMethod(failedJob.withoutCode)
    await flushPromises()
    expect(second.emitted('close-action')).toHaveLength(1)
  })

  it('reject sends nothing', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await submit(wrapper)
    document.querySelector('.placement-move-reject').click()
    await flushPromises()
    expect(started()).toHaveLength(0)
    expect(modalOf(wrapper).props('visible')).toBe(false)
    expect(wrapper.vm.loading).toBe(false)
  })

  it('shows the reason and does not start when the verdict is none', async () => {
    placements = [{ verdict: 'none', reason: 'volumes unavailable' }]
    const wrapper = await open()
    await submit(wrapper)
    expect(started()).toHaveLength(0)
    expect(wrapper.text()).toContain(en['message.placement.reason.volumes.unavailable'])
    expect(wrapper.emitted('close-action')).toBeUndefined()

    placements = [{ verdict: 'none', reason: 'quota' }]
    const quota = await open()
    await submit(quota)
    expect(quota.text()).toContain(en['message.placement.reason.quota'])
    expect(started()).toHaveLength(0)
  })

  it('does not ask for the placement of a start an admin pinned to a host, or when the API is not listed', async () => {
    const pinned = await open('Admin')
    pinned.vm.form.hostid = 'h-1'
    await submit(pinned)
    expect(getAPI.mock.calls.filter(call => call[0] === 'checkPlacement')).toHaveLength(0)
    expect(started()[0]).toMatchObject({ id: 'vm-1', hostid: 'h-1' })

    jest.clearAllMocks()
    const unlisted = await open('User', ['startVirtualMachine'])
    await submit(unlisted)
    expect(getAPI.mock.calls.filter(call => call[0] === 'checkPlacement')).toHaveLength(0)
    expect(started()).toHaveLength(1)
  })

  it('starts without an approval when the check itself fails, and says the check could not be made', async () => {
    getAPI.mockImplementation(() => Promise.reject(new Error('down')))
    const wrapper = await open()
    await submit(wrapper)
    // the start never moves anything on its own: a move it needs comes back as the distinct refusal
    expect(started()).toEqual([{ id: 'vm-1', considerlasthost: true }])
    expect(wrapper.$notification.warning).toHaveBeenCalledTimes(1)
    expect(wrapper.$notification.warning.mock.calls[0][0].description).toBe(en['message.placement.check.failed'])
    expect(wrapper.$notifyError).not.toHaveBeenCalled()
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })
})
