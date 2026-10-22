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
import ScaleVM from '@/views/compute/ScaleVM'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
const GiB = 1024 * 1024 * 1024

const cpuOffering = {
  id: 'o-cpu',
  name: 'cpu',
  iscustomized: true,
  hosttags: 'cpu',
  cpuspeed: 2400,
  diskofferingid: 'do-1',
  diskofferingstrictness: true,
  serviceofferingdetails: { mincpunumber: '2', maxcpunumber: '4096', minmemory: '32', maxmemory: '67108864' }
}
const gpuOffering = { id: 'o-gpu', name: 'a100-40g-1', cpunumber: 12, memory: 98304, hosttags: 'a100-40g', gpucardname: 'A100', gpucount: 1, diskofferingid: 'do-2' }

const vm = {
  id: 'vm-1',
  name: 'lab',
  state: 'Stopped',
  hypervisor: 'KVM',
  templateid: 't-1',
  zoneid: 'z-1',
  account: 'student',
  domainid: 'd-1',
  cpunumber: 4,
  memory: 4096,
  cpuspeed: 2400
}

const cpuLimits = [
  { resourcetype: '8', tag: 'cpu', max: '16' },
  { resourcetype: '9', tag: 'cpu', max: '32768' }
]
const account = {
  id: 'acc-1',
  remainingstoragequota: 50,
  taggedresources: [
    { resourcetype: 8, tag: 'cpu', total: 8 },
    { resourcetype: 9, tag: 'cpu', total: 16384 }
  ]
}

let state

const answer = (command, params = {}) => {
  switch (command) {
    case 'listServiceOfferings': {
      // as the server lists them: the active offerings, and an inactive one only when the state asked for is 'all'
      const listed = state.offerings.filter(offering => offering.state !== 'Inactive' || (params.id && params.state === 'all'))
      const found = params.id ? listed.filter(offering => offering.id === params.id) : listed
      return { listserviceofferingsresponse: { count: found.length, serviceoffering: found } }
    }
    case 'listCapabilities':
      return { listcapabilitiesresponse: { capability: state.capabilities } }
    case 'listResourceLimits':
      return { listresourcelimitsresponse: { resourcelimit: params.tag === 'cpu' ? cpuLimits : state.gpuLimits } }
    case 'listAccounts':
      return { listaccountsresponse: { account: [{ ...account, taggedresources: [...account.taggedresources, ...state.gpuUsage] }] } }
    case 'listDiskOfferings':
      return { listdiskofferingsresponse: { diskoffering: state.diskOffering[params.id] ? [state.diskOffering[params.id]] : undefined } }
    case 'listTemplates':
      return { listtemplatesresponse: { template: [{ id: 't-1', size: 3 * GiB }] } }
    case 'checkPlacement':
      return { checkplacementresponse: { placement: { verdict: 'anyhost' } } }
    case 'listPlacementCapacity':
      return { listplacementcapacityresponse: { placementcapacity: { maxvcpu: 2, maxmemory: 1024, maxdisk: 10, thishost: { freevcpu: 2, freememory: 1024, freedisk: 10 } } } }
    default:
      return {}
  }
}

const open = async ({ resource = vm, config = shippedConfig, features = {} } = {}) => {
  const apis = { scaleVirtualMachine: { params: [{ name: 'automigrate', description: '' }] } }
  ;['listServiceOfferings', 'listCapabilities', 'listResourceLimits', 'listAccounts', 'listDiskOfferings', 'listTemplates', 'checkPlacement', 'listPlacementCapacity']
    .forEach(name => { apis[name] = { params: [] } })
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }, features } })
  const wrapper = common.createFactory(ScaleVM, {
    store,
    i18n: common.createMockI18n('en', {}),
    props: { resource },
    provide: { parentFetchData: jest.fn() },
    mocks: { $config: config, $pollJob: jest.fn(), $notifyError: jest.fn() }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const choose = async (wrapper, id) => {
  wrapper.vm.updateComputeOffering(id)
  await flushPromises()
  await flushPromises()
}

const scaled = () => postAPI.mock.calls.filter(call => call[0] === 'scaleVirtualMachine').map(call => call[1])
const calls = (command) => getAPI.mock.calls.filter(call => call[0] === command).map(call => call[1])

describe('ScaleVM', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    state = {
      capabilities: { poolvcpustep: 2 },
      offerings: [cpuOffering, gpuOffering],
      gpuLimits: [],
      gpuUsage: [],
      diskOffering: {
        'do-1': { id: 'do-1', iscustomized: false, iscustomizediops: true },
        'do-2': { id: 'do-2', iscustomized: false, iscustomizediops: false }
      }
    }
    getAPI.mockImplementation((command, params) => Promise.resolve(answer(command, params)))
    postAPI.mockImplementation(() => Promise.resolve({ scalevirtualmachineresponse: { jobid: 'job-1' } }))
  })

  it('vCPU input steps by listCapabilities poolvcpustep (default 2) with minimum 2 in ScaleVM', async () => {
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    let compute = wrapper.findComponent({ name: 'ComputeSelection' })
    expect(compute.props('cpuStep')).toBe(2)
    expect(compute.props('minCpu')).toBe(2)

    state.capabilities = {}
    const byDefault = await open()
    await choose(byDefault, 'o-cpu')
    expect(byDefault.findComponent({ name: 'ComputeSelection' }).props('cpuStep')).toBe(2)

    state.capabilities = { poolvcpustep: 1 }
    const stepOne = await open()
    await choose(stepOne, 'o-cpu')
    compute = stepOne.findComponent({ name: 'ComputeSelection' })
    expect(compute.props('cpuStep')).toBe(1)
    expect(compute.props('minCpu')).toBe(2)
  })

  it('bounds vCPU and RAM by the remaining quota, adding back what the instance already holds', async () => {
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    const compute = wrapper.findComponent({ name: 'ComputeSelection' })
    // 16 - 8 vCPU remain, and the instance's own 4 are in that count; 32768 - 16384 MiB and its 4096
    expect(compute.props('maxCpu')).toBe(12)
    expect(compute.props('maxMemory')).toBe(20480)
  })

  it('shows the placement of the new offering for a stopped instance only', async () => {
    const stopped = await open()
    await choose(stopped, 'o-cpu')
    const step = stopped.findComponent({ name: 'PlacementStep' })
    expect(step.exists()).toBe(true)
    expect(step.props('virtualmachineid')).toBe('vm-1')
    expect(step.props('request')).toMatchObject({ virtualmachineid: 'vm-1', serviceofferingid: 'o-cpu' })

    const running = await open({ resource: { ...vm, state: 'Running' } })
    await choose(running, 'o-cpu')
    expect(running.findComponent({ name: 'PlacementStep' }).exists()).toBe(false)
  })

  it('does not send a different rootdisksize to a compute-only-root offering', async () => {
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    // the disk offering of the new offering is compute-only with customized IOPS: its size input is not offered, and
    // the template size the widget would fall back to is never sent
    wrapper.vm.updateFieldValue('details[0].rootdisksize', 3)
    wrapper.vm.updateFieldValue('details[0].cpuNumber', 4)
    wrapper.vm.updateFieldValue('details[0].memory', 4096)
    await wrapper.vm.handleSubmit()
    await flushPromises()
    expect(scaled()).toHaveLength(1)
    expect(Object.keys(scaled()[0]).filter(key => /rootdisksize/i.test(key))).toEqual([])
    expect(scaled()[0]).toMatchObject({ id: 'vm-1', serviceofferingid: 'o-cpu', 'details[0].cpuNumber': 4, 'details[0].memory': 4096 })
  })

  it('sends no rootdisksize when the new offering has no disk offering to list, as for every compute-only root', async () => {
    // the server never lists a compute-only disk offering: nothing comes back, no size input is offered, nothing is sent
    state.diskOffering = {}
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    expect(wrapper.findComponent({ name: 'DiskSizeSelection' }).exists()).toBe(false)
    wrapper.vm.updateFieldValue('details[0].cpuNumber', 4)
    wrapper.vm.updateFieldValue('details[0].memory', 4096)
    await wrapper.vm.handleSubmit()
    await flushPromises()
    expect(Object.keys(scaled()[0]).filter(key => /rootdisksize/i.test(key))).toEqual([])
  })

  describe('GPU offerings above the remaining quota of their type', () => {
    const a6000 = { id: 'o-a6000', name: 'a6000-1', cpunumber: 12, memory: 24576, hosttags: 'a6000', gpucardname: 'A6000', gpucount: 1, diskofferingid: 'do-2' }
    const a100 = { id: 'o-a100', name: 'a100-40g-1', cpunumber: 12, memory: 98304, hosttags: 'a100-40g', gpucardname: 'A100', gpucount: 1, diskofferingid: 'do-2' }
    const limitOf = (tag, max) => ({ resourcetype: '16', resourcetypename: 'gpu', tag, max: String(max) })
    const usageOf = (tag, total) => ({ resourcetype: 16, resourcetypename: 'gpu', tag, total })
    const rows = (wrapper) => Object.fromEntries(wrapper.findComponent({ name: 'ComputeOfferingSelection' }).vm.tableSource.map(row => [row.key, row]))

    beforeEach(() => {
      state.offerings = [cpuOffering, a6000, a100]
      state.gpuLimits = [limitOf('a6000', 1), limitOf('a100-40g', 0)]
      state.gpuUsage = [usageOf('a6000', 1)]
    })

    it('disables them as the wizard does, adding back the GPUs the instance already holds in their own type', async () => {
      const wrapper = await open({ resource: { ...vm, serviceofferingid: 'o-a6000', gpucount: 1 } })
      const offerings = rows(wrapper)
      // the instance's own a6000 is in the a6000 usage: a change within the type stays possible
      expect(offerings['o-a6000'].disabled).toBe(false)
      // another type with nothing left is disabled, and says why
      expect(offerings['o-a100'].disabled).toBe(true)
      expect(offerings['o-a100'].disabledReason).toBe('quota')
      expect(offerings['o-cpu'].disabled).toBe(false)
      expect(calls('listServiceOfferings').some(params => params.id === 'o-a6000')).toBe(true)
    })

    it('finds the current offering of an instance whose offering has been deactivated, and adds its GPUs back', async () => {
      // a replaced offering is kept, inactive, for the instances still on it: only the state 'all' lists it
      const retired = { ...a6000, id: 'o-a6000-old', name: 'a6000-1-old', state: 'Inactive' }
      state.offerings = [cpuOffering, retired, { ...a6000, id: 'o-a6000-new', name: 'a6000-1-new' }, a100]
      const wrapper = await open({ resource: { ...vm, serviceofferingid: 'o-a6000-old', gpucount: 1 } })
      const offerings = rows(wrapper)
      expect(calls('listServiceOfferings').find(params => params.id === 'o-a6000-old').state).toBe('all')
      expect(offerings['o-a6000-new'].disabled).toBe(false)
      expect(offerings['o-a100'].disabledReason).toBe('quota')
    })

    it('gives no add-back to an instance without a GPU: a change to a GPU type with nothing left is disabled', async () => {
      const wrapper = await open()
      const offerings = rows(wrapper)
      expect(offerings['o-a6000'].disabled).toBe(true)
      expect(offerings['o-a6000'].disabledReason).toBe('quota')
      expect(offerings['o-a100'].disabled).toBe(true)
      expect(calls('listServiceOfferings').some(params => params.id !== undefined)).toBe(false)
    })
  })

  it('still sends the root size the user chose for a customized-size disk offering', async () => {
    state.diskOffering['do-1'] = { id: 'do-1', iscustomized: true, iscustomizediops: false }
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    wrapper.vm.updateFieldValue('details[0].rootdisksize', 30)
    await wrapper.vm.handleSubmit()
    await flushPromises()
    expect(scaled()[0]['details[0].rootdisksize']).toBe(30)
  })

  it('offers the automatic migration only to a caller the server lets use it', async () => {
    const hasSwitch = (wrapper) => wrapper.text().includes('label.automigrate.volume')

    expect(hasSwitch(await open())).toBe(false)
    expect(hasSwitch(await open({ features: { automigrateallowed: false } }))).toBe(false)

    const allowed = await open({ features: { automigrateallowed: true } })
    expect(hasSwitch(allowed)).toBe(true)
    await choose(allowed, 'o-cpu')
    allowed.vm.autoMigrate = true
    await allowed.vm.handleSubmit()
    await flushPromises()
    expect(scaled()[0].automigrate).toBe(true)

    postAPI.mockClear()
    const refused = await open()
    await choose(refused, 'o-cpu')
    await refused.vm.handleSubmit()
    await flushPromises()
    expect(scaled()[0].automigrate).toBe(false)
  })

  it('CPU speed is never shown with the default config', async () => {
    const wrapper = await open()
    await choose(wrapper, 'o-cpu')
    const offerings = wrapper.findComponent({ name: 'ComputeOfferingSelection' })
    expect(offerings.vm.columns.map(column => column.key)).not.toContain('cpuspeed')
    expect(wrapper.html()).not.toContain('label.cpuspeed')

    const shown = await open({ config: { ...shippedConfig, showCpuSpeed: true } })
    expect(shown.findComponent({ name: 'ComputeOfferingSelection' }).vm.columns.map(column => column.key)).toContain('cpuspeed')
  })
})
