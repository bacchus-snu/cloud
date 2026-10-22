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
import AttachVolume from '@/views/storage/AttachVolume'
import PlacementMoveModal from '@/views/compute/wizard/PlacementMoveModal'
import failedJob from '../../../mockData/FailedJob.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const GiB = 1024 * 1024 * 1024
const volume = { id: 'vol-1', name: 'scratch', zoneid: 'z-1', account: 'student', domainid: 'd-1', state: 'Ready' }

const machines = { Running: { id: 'vm-run', name: 'running', state: 'Running' }, Stopped: { id: 'vm-stop', name: 'stopped', state: 'Stopped' } }

const movePlacement = (token) => ({
  verdict: 'move',
  volumes: [{ id: 'vol-1', name: 'scratch', size: 50 * GiB, physicalsize: 8 * GiB }],
  bytestomove: 8 * GiB,
  estimatedseconds: 86,
  previouscopykept: false,
  placementtoken: token
})

let placements

const open = async () => {
  const apis = { attachVolume: { params: [{ name: 'virtualmachineid', description: '' }, { name: 'deviceid', description: '' }] }, checkPlacement: { params: [] }, listVirtualMachines: { params: [] } }
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } })
  const $pollJob = jest.fn()
  const $notifyError = jest.fn()
  const $notification = { warning: jest.fn(), error: jest.fn() }
  const wrapper = common.createFactory(AttachVolume, {
    store,
    i18n: common.createMockI18n('en', { en }),
    props: { resource: volume },
    mocks: { $pollJob, $notifyError, $notification }
  })
  await flushPromises()
  wrapper.$pollJob = $pollJob
  wrapper.$notifyError = $notifyError
  wrapper.$notification = $notification
  return wrapper
}

const attachTo = async (wrapper, vmId) => {
  wrapper.vm.form.virtualmachineid = vmId
  wrapper.vm.form.deviceid = 2
  await wrapper.vm.handleSubmit({ preventDefault () {} })
  await flushPromises()
}

const attached = () => postAPI.mock.calls.filter(call => call[0] === 'attachVolume').map(call => call[1])
const checks = () => getAPI.mock.calls.filter(call => call[0] === 'checkPlacement').map(call => call[1])
const approve = async () => {
  document.querySelector('.placement-move-approve').click()
  await flushPromises()
}

describe('AttachVolume: the approval of a move for a running instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    placements = [{ verdict: 'here' }]
    getAPI.mockImplementation((command, params) => {
      if (command === 'checkPlacement') {
        return Promise.resolve({ checkplacementresponse: { placement: placements.length > 1 ? placements.shift() : placements[0] } })
      }
      if (command === 'listVirtualMachines') {
        return Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: [machines[params.state]] } })
      }
      return Promise.resolve({})
    })
    postAPI.mockImplementation(() => Promise.resolve({ attachvolumeresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('attaches to a running instance at once when no move is needed', async () => {
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    expect(checks()).toEqual([{ virtualmachineid: 'vm-run', volumeid: 'vol-1' }])
    expect(attached()).toEqual([{ id: 'vol-1', virtualmachineid: 'vm-run', deviceid: 2 }])
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })

  it('shows the move modal for a running instance and sends migratevolume and placementtoken on approve', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    expect(attached()).toHaveLength(0)
    expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(true)
    expect(wrapper.findComponent(PlacementMoveModal).props('operation')).toBe('attach')
    expect(document.body.textContent).toContain('scratch')
    expect(document.body.textContent).toContain(en['message.placement.move.copy.removed'])

    await approve()
    expect(attached()).toEqual([{ id: 'vol-1', virtualmachineid: 'vm-run', deviceid: 2, migratevolume: true, placementtoken: 'tok-1' }])
    wrapper.$pollJob.mock.calls[0][0].successMethod({ jobresult: {} })
    expect(wrapper.emitted('close-action')).toHaveLength(1)
  })

  it('reject sends nothing, and a stopped instance is attached without a check', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    document.querySelector('.placement-move-reject').click()
    await flushPromises()
    expect(attached()).toHaveLength(0)
    expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(false)

    jest.clearAllMocks()
    const stopped = await open()
    await attachTo(stopped, 'vm-stop')
    expect(checks()).toHaveLength(0)
    expect(attached()).toEqual([{ id: 'vol-1', virtualmachineid: 'vm-stop', deviceid: 2 }])
  })

  it('shows the new verdict when the token no longer matches', async () => {
    placements = [movePlacement('tok-1'), movePlacement('tok-2')]
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    await approve()
    // the job failed, and queryAsyncJobResult serves it with the code
    wrapper.$pollJob.mock.calls[0][0].errorMethod(failedJob.moveRequired)
    await flushPromises()
    expect(wrapper.findComponent(PlacementMoveModal).props('verdict').placementtoken).toBe('tok-2')
    await approve()
    expect(attached()[1]).toMatchObject({ migratevolume: true, placementtoken: 'tok-2' })
  })

  it('says it copies the volume and attaches it while an approved attach runs, not that it starts anything', async () => {
    placements = [movePlacement('tok-1')]
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    await approve()
    const working = wrapper.find('.attach-working').text()
    expect(working).toContain(en['message.placement.working.attach'])
    expect(working).not.toContain('starting')
  })

  it('attaches without an approval when the check itself fails, and says the check could not be made', async () => {
    getAPI.mockImplementation((command, params) => command === 'listVirtualMachines'
      ? Promise.resolve({ listvirtualmachinesresponse: { virtualmachine: [machines[params.state]] } })
      : Promise.reject(new Error('down')))
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    expect(attached()).toEqual([{ id: 'vol-1', virtualmachineid: 'vm-run', deviceid: 2 }])
    expect(wrapper.$notification.warning.mock.calls[0][0].description).toBe(en['message.placement.check.failed'])
    expect(wrapper.$notifyError).not.toHaveBeenCalled()
  })

  it('says why when the volume cannot be put on the instance\'s host', async () => {
    placements = [{ verdict: 'none', reason: 'local storage' }]
    const wrapper = await open()
    await attachTo(wrapper, 'vm-run')
    expect(attached()).toHaveLength(0)
    expect(wrapper.text()).toContain(en['message.placement.reason.local.storage'])
  })
})
