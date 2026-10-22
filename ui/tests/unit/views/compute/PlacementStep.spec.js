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
import { getAPI } from '@/api'
import PlacementStep from '@/views/compute/wizard/PlacementStep'
import PlacementMoveModal from '@/views/compute/wizard/PlacementMoveModal'
import { placementAction, isMoveRequired, placementKind, reasonText, etaText } from '@/utils/placement'
import failedJob from '../../../mockData/FailedJob.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const locales = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales', name), 'utf8'))
const en = locales('en.json')
const ko = locales('ko_KR.json')
const GiB = 1024 * 1024 * 1024

const store = () => common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } })

// the verdicts are given one after the other; the last one repeats
const answer = (verdicts) => (command) => {
  if (command === 'checkPlacement') {
    return Promise.resolve({ checkplacementresponse: { placement: verdicts.length > 1 ? verdicts.shift() : verdicts[0] } })
  }
  if (command === 'listPlacementCapacity') {
    return Promise.resolve({ listplacementcapacityresponse: { placementcapacity: { maxvcpu: 24, maxmemory: 65536, maxdisk: 400, thishost: { freevcpu: 8, freememory: 16384, freedisk: 100, gpuslotsavailable: 2 } } } })
  }
  return Promise.resolve({})
}

const newDeploy = { templateid: 't-1', serviceofferingid: 'o-cpu', zoneid: 'z-1', rootdisksize: 20, 'details[0].cpuNumber': 4, 'details[0].memory': 4096 }

const mountStep = (props, config = {}) => common.createFactory(PlacementStep, {
  store: store(),
  i18n: common.createMockI18n('en', { en }),
  props: { zoneid: 'z-1', capacityOfferingId: 'o-cpu', ...props },
  mocks: { $config: config }
})

const calls = (command) => getAPI.mock.calls.filter(call => call[0] === command).map(call => call[1])

describe('PlacementStep', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.useFakeTimers()
  })

  afterEach(() => {
    jest.useRealTimers()
  })

  it('shows the capacity maxima and the verdict of the request', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }]))
    const wrapper = mountStep({ request: newDeploy })
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(1)
    expect(calls('checkPlacement')[0]).toMatchObject({ templateid: 't-1', serviceofferingid: 'o-cpu', zoneid: 'z-1', rootdisksize: 20 })
    // the capacity is asked for the CPU-only offering, so the allocator's tag selection applies
    expect(calls('listPlacementCapacity')[0]).toMatchObject({ zoneid: 'z-1', serviceofferingid: 'o-cpu' })
    expect(wrapper.find('.placement-verdict').text()).toContain('It can start as it is now')
    expect(wrapper.find('.placement-capacity').text()).toContain('24 vCPU')
    expect(wrapper.find('.placement-capacity').text()).toContain('64 GiB')
    expect(wrapper.find('.placement-capacity').text()).toContain('400 GiB')
  })

  it('shows a move with its size and estimate, and a refusal with its reason', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'move', bytestomove: 10 * GiB, estimatedseconds: 215, volumes: [] }]))
    const move = mountStep({ request: { virtualmachineid: 'vm-1', serviceofferingid: 'o-cpu' }, virtualmachineid: 'vm-1' })
    await flushPromises()
    expect(move.find('.placement-verdict').text()).toContain('10.00 GiB')
    expect(move.find('.placement-verdict').text()).toContain('4 minutes')
    // an existing instance's own host is shown too, with its free GPU slots
    expect(calls('listPlacementCapacity')[0]).toMatchObject({ virtualmachineid: 'vm-1' })
    expect(move.find('.placement-thishost').text()).toContain('8 vCPU')
    expect(move.find('.placement-thishost').text()).toContain('2')

    getAPI.mockImplementation(answer([{ verdict: 'none', reason: 'volumes unavailable' }]))
    const none = mountStep({ request: newDeploy })
    await flushPromises()
    expect(none.find('.placement-verdict').text()).toContain('Not possible now')
    expect(none.find('.placement-verdict').text()).toContain(en['message.placement.reason.volumes.unavailable'])

    getAPI.mockImplementation(answer([{ verdict: 'none', reason: 'quota' }]))
    const quota = mountStep({ request: newDeploy })
    await flushPromises()
    expect(quota.find('.placement-verdict').text()).toContain(en['message.placement.reason.quota'])
  })

  it('shows the free GPU slots of the instance\'s host only while showGpuSlots is not false', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }]))
    const request = { virtualmachineid: 'vm-1', serviceofferingid: 'o-cpu' }
    const slots = en['message.placement.capacity.gpuslots'].replace('{n}', '2')

    for (const config of [{}, { showGpuSlots: true }]) {
      const shown = mountStep({ request, virtualmachineid: 'vm-1' }, config)
      await flushPromises()
      expect(shown.find('.placement-thishost').text()).toContain(slots)
    }

    const hidden = mountStep({ request, virtualmachineid: 'vm-1' }, { showGpuSlots: false })
    await flushPromises()
    expect(hidden.find('.placement-thishost').text()).toContain('8 vCPU')
    expect(hidden.find('.placement-thishost').text()).not.toContain(slots)
    expect(hidden.find('.placement-thishost').findAll('li')).toHaveLength(1)
  })

  it('recomputes 500 ms after the inputs change, once', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }, { verdict: 'none', reason: 'memory' }]))
    const wrapper = mountStep({ request: newDeploy })
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(1)

    await wrapper.setProps({ request: { ...newDeploy, 'details[0].memory': 8192 } })
    await wrapper.setProps({ request: { ...newDeploy, 'details[0].memory': 16384 } })
    jest.advanceTimersByTime(499)
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(1)
    jest.advanceTimersByTime(1)
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(2)
    expect(calls('checkPlacement')[1]['details[0].memory']).toBe(16384)
    expect(wrapper.find('.placement-verdict').text()).toContain('Not possible now')

    // the same inputs again are not a change
    await wrapper.setProps({ request: { ...newDeploy, 'details[0].memory': 16384 } })
    jest.advanceTimersByTime(1000)
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(2)
  })

  it('never changes inputs', async () => {
    const request = { ...newDeploy }
    const before = JSON.parse(JSON.stringify(request))
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }, { verdict: 'none', reason: 'cpu pool' }]))
    const wrapper = mountStep({ request })
    await flushPromises()
    expect(request).toEqual(before)

    await wrapper.setProps({ request: { ...request, 'details[0].cpuNumber': 64 } })
    jest.advanceTimersByTime(500)
    await flushPromises()
    expect(wrapper.find('.placement-verdict').text()).toContain('Not possible now')
    // a verdict update leaves the values the parent holds as they were, and nothing is emitted for it to take
    expect(request).toEqual(before)
    expect(Object.keys(wrapper.emitted())).toEqual([])
    expect(wrapper.props('request')['details[0].cpuNumber']).toBe(64)
  })

  it('asks nothing while the request is incomplete, and only for a combination the server takes', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }]))
    const wrapper = mountStep({ request: { serviceofferingid: 'o-cpu' }, capacityOfferingId: '', zoneid: 'z-1' })
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(0)
    expect(calls('listPlacementCapacity')).toHaveLength(0)
    expect(wrapper.find('.placement-idle').exists()).toBe(true)

    expect(placementKind({ virtualmachineid: 'v' })).toBe('start')
    expect(placementKind({ virtualmachineid: 'v', volumeid: 'x' })).toBe('attach')
    expect(placementKind({ volumeid: 'x', serviceofferingid: 'o' })).toBe('deploy-from-volume')
    expect(placementKind({ templateid: 't', serviceofferingid: 'o', zoneid: 'z' })).toBe('deploy')
    expect(placementKind({ templateid: 't', serviceofferingid: 'o' })).toBeNull()
  })

  it('a GPU offering gets the verdict without the CPU-only maxima', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }]))
    const wrapper = mountStep({ request: { ...newDeploy, serviceofferingid: 'o-gpu' }, capacityOfferingId: '' })
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(1)
    expect(calls('listPlacementCapacity')).toHaveLength(0)
    expect(wrapper.find('.placement-capacity').exists()).toBe(false)
  })

  it('does not keep its spinner on when the request turns incomplete while a check is in flight', async () => {
    let release
    getAPI.mockImplementation((command) => command === 'checkPlacement'
      ? new Promise(resolve => { release = () => resolve({ checkplacementresponse: { placement: { verdict: 'anyhost' } } }) })
      : Promise.resolve({ listplacementcapacityresponse: { placementcapacity: { maxvcpu: 2, maxmemory: 1024, maxdisk: 1 } } }))
    const wrapper = mountStep({ request: newDeploy })
    await flushPromises()
    expect(wrapper.vm.loading).toBe(true)

    await wrapper.setProps({ request: { serviceofferingid: 'o-cpu' }, capacityOfferingId: '' })
    jest.advanceTimersByTime(500)
    await flushPromises()
    release()
    await flushPromises()
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.find('.placement-idle').exists()).toBe(true)
  })

  it('reads the capacity again only when its own parameters change, not with every input', async () => {
    getAPI.mockImplementation(answer([{ verdict: 'anyhost' }]))
    const wrapper = mountStep({ request: newDeploy })
    await flushPromises()
    expect(calls('listPlacementCapacity')).toHaveLength(1)

    await wrapper.setProps({ request: { ...newDeploy, 'details[0].memory': 8192 } })
    jest.advanceTimersByTime(500)
    await flushPromises()
    expect(calls('checkPlacement')).toHaveLength(2)
    expect(calls('listPlacementCapacity')).toHaveLength(1)

    await wrapper.setProps({ capacityOfferingId: 'o-other' })
    jest.advanceTimersByTime(500)
    await flushPromises()
    expect(calls('listPlacementCapacity')).toHaveLength(2)
    expect(calls('listPlacementCapacity')[1]).toMatchObject({ serviceofferingid: 'o-other' })
  })

  it('says each maximum stands on its own, not that one host takes all three', () => {
    expect(en['message.placement.capacity.largest']).toMatch(/each/i)
    expect(en['message.placement.capacity.largest']).not.toContain('The largest CPU-only instance one host could take')
    expect(ko['message.placement.capacity.largest']).toContain('각각')
  })
})

describe('placement helpers', () => {
  it('a verdict asks the caller to go on, approve a move, or stop', () => {
    expect(placementAction({ verdict: 'here' })).toBe('proceed')
    expect(placementAction({ verdict: 'anyhost' })).toBe('proceed')
    expect(placementAction({ verdict: 'move' })).toBe('approve')
    expect(placementAction({ verdict: 'none' })).toBe('refuse')
  })

  it('tells the missing or stale approval apart from other failures', () => {
    expect(isMoveRequired({ jobresult: { cserrorcode: 4336 } })).toBe(true)
    expect(isMoveRequired({ jobresult: { cserrorcode: 4250 } })).toBe(false)
    // the results of failed jobs as queryAsyncJobResult serves them
    expect(isMoveRequired(failedJob.moveRequired)).toBe(true)
    expect(isMoveRequired(failedJob.other)).toBe(false)
    expect(isMoveRequired(failedJob.withoutCode)).toBe(false)
    expect(isMoveRequired({ response: { data: { deployvirtualmachineresponse: { cserrorcode: 4336 } } } })).toBe(true)
    expect(isMoveRequired({ response: { data: { deployvirtualmachineresponse: { cserrorcode: 9999 } } } })).toBe(false)
    expect(isMoveRequired(new Error('x'))).toBe(false)
  })

  it('renders every reason in both languages, and an unknown one as given', () => {
    const reasons = ['quota', 'storage quota', 'cpu pool', 'vcpu step', 'memory', 'local storage', 'volumes unavailable', 'instance snapshots',
      'volume snapshots on primary storage', 'encrypted volume', 'affinity groups', 'suitable host']
    for (const [name, messages] of [['en', en], ['ko_KR', ko]]) {
      const i18n = common.createMockI18n(name, { [name]: messages })
      const t = i18n.global.t
      for (const reason of reasons) {
        expect(reasonText(t, reason)).not.toBe(reason)
        expect(reasonText(t, reason)).not.toMatch(/^message\./)
      }
      expect(reasonText(t, 'gpu group A100 x2')).toContain('A100 x2')
      expect(reasonText(t, 'something new')).toBe('something new')
      expect(etaText(t, 30)).toContain('30')
      expect(etaText(t, 215)).toContain('4')
      expect(etaText(t, 4000)).toContain('1')
    }
  })
})

describe('PlacementMoveModal', () => {
  const verdict = (extra = {}) => ({
    verdict: 'move',
    volumes: [{ id: 'v-1', name: 'ROOT-7', size: 20 * GiB, physicalsize: 4 * GiB }, { id: 'v-2', name: 'data', size: 100 * GiB, physicalsize: 6 * GiB }],
    bytestomove: 10 * GiB,
    estimatedseconds: 215,
    previouscopykept: true,
    placementtoken: 'tok-1',
    ...extra
  })

  const open = (props, locale = 'en') => common.createFactory(PlacementMoveModal, {
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { visible: true, operation: 'start', verdict: verdict(), ...props }
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('lists the volumes with their sizes, the estimate, and that the disks are unavailable during the move', async () => {
    open()
    await flushPromises()
    const text = document.body.textContent
    expect(text).toContain('ROOT-7')
    expect(text).toContain('20.00 GiB')
    expect(text).toContain('4.00 GiB')
    expect(text).toContain('data')
    expect(text).toContain('100.00 GiB')
    expect(text).toContain('10.00 GiB to copy, about 4 minutes')
    expect(text).toContain(en['message.placement.move.unavailable'])
  })

  it('says the previous copy stays, with its name and that it counts against storage quota', async () => {
    open({ verdict: verdict({ previouscopykept: true }) })
    await flushPromises()
    const text = document.body.textContent
    expect(text).toContain('-previous-')
    expect(text).toContain('counts against your storage quota')
    expect(text).not.toContain('is removed')
  })

  it('says the old copy is removed when it is not kept', async () => {
    open({ verdict: verdict({ previouscopykept: false }) })
    await flushPromises()
    const text = document.body.textContent
    expect(text).toContain(en['message.placement.move.copy.removed'])
    expect(text).not.toContain('-previous-')
  })

  it('says the same in Korean, and names the operation', async () => {
    open({ operation: 'attach' }, 'ko_KR')
    await flushPromises()
    const text = document.body.textContent
    expect(text).toContain(ko['message.placement.move.unavailable'])
    expect(text).toContain(ko['message.placement.move.intro.attach'])
    expect(text).toContain('-previous-')
  })

  it('emits the approval with the verdict, and the rejection', async () => {
    const wrapper = open()
    await flushPromises()
    document.querySelector('.placement-move-approve').click()
    expect(wrapper.emitted('approve')[0][0].placementtoken).toBe('tok-1')
    document.querySelector('.placement-move-reject').click()
    expect(wrapper.emitted('reject')).toHaveLength(1)
  })
})
