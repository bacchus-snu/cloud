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
import DeployVM from '@/views/compute/DeployVM'
import PlacementMoveModal from '@/views/compute/wizard/PlacementMoveModal'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: {
    features: {},
    apis: {},
    userInfo: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' },
    project: {},
    zones: []
  }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))

const GiB = 1024 * 1024 * 1024
const zone = { id: 'z-1', name: 'zone', networktype: 'Advanced', securitygroupsenabled: true, allocationstate: 'Enabled' }
const userInfo = { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }

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
const gpuOffering = { id: 'o-gpu', name: 'a100-40g-1', cpunumber: 12, memory: 98304, hosttags: 'a100-40g', gpucardname: 'A100', gpucount: 1, diskofferingstrictness: true }

const limitsOf = (rows) => ({ listresourcelimitsresponse: { resourcelimit: rows } })

const resourceLimits = {
  cpu: limitsOf([
    { resourcetype: '8', resourcetypename: 'cpu', tag: 'cpu', max: '16' },
    { resourcetype: '9', resourcetypename: 'memory', tag: 'cpu', max: '32768' }
  ]),
  gpu: limitsOf([
    { resourcetype: '16', resourcetypename: 'gpu', tag: 'a100-40g', max: '2' },
    { resourcetype: '16', resourcetypename: 'gpu', tag: 'a100-80g', max: '0' }
  ])
}

const account = {
  id: 'acc-1',
  name: 'student',
  // the figures a client could subtract: 125 - 40 = 85, which is not what the server says remains
  primarystoragelimit: 125,
  primarystoragetotal: 40,
  remainingstoragequota: 77,
  taggedresources: [
    { resourcetype: 8, resourcetypename: 'cpu', tag: 'cpu', limit: 16, total: 4, available: 12 },
    { resourcetype: 9, resourcetypename: 'memory', tag: 'cpu', limit: 32768, total: 8192, available: 24576 },
    { resourcetype: 16, resourcetypename: 'gpu', tag: 'a100-40g', limit: 2, total: 1, available: 1 }
  ]
}

const capabilities = { rootdisksizemin: 10, rootdisksizedefault: 20, poolvcpustep: 2, keeprootvolumedefault: true }
const movePlacement = (token) => ({
  verdict: 'move',
  volumes: [{ id: 'vol-1', name: 'ROOT-3', size: 20 * GiB, physicalsize: 4 * GiB }],
  bytestomove: 4 * GiB,
  estimatedseconds: 43,
  previouscopykept: true,
  placementtoken: token
})

let state

const reset = () => {
  state = {
    capabilities: { ...capabilities },
    placements: [{ verdict: 'anyhost' }],
    capacity: { maxvcpu: 4, maxmemory: 2048, maxdisk: 50 }
  }
}

const answers = (command, params = {}) => {
  switch (command) {
    case 'listZones':
      return { listzonesresponse: { count: 1, zone: [zone] } }
    case 'listNetworks':
      return { listnetworksresponse: { count: 0, network: [] } }
    case 'listCapabilities':
      return { listcapabilitiesresponse: { capability: state.capabilities } }
    case 'listResourceLimits':
      return params.tag === 'cpu' ? resourceLimits.cpu : resourceLimits.gpu
    case 'listAccounts':
      return { listaccountsresponse: { count: 1, account: [account] } }
    case 'checkPlacement':
      return { checkplacementresponse: { placement: state.placements.length > 1 ? state.placements.shift() : state.placements[0] } }
    case 'listPlacementCapacity':
      return { listplacementcapacityresponse: { placementcapacity: state.capacity } }
    default:
      return {}
  }
}

const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))

// the wizard's own steps and fields are under test, not the selection widgets inside them; the widgets that carry the
// bounds are left as stubs whose props are read
const stubbed = ['OwnershipSelection', 'InfoCard', 'DeployButtons', 'ResourceIcon', 'ZoneBlockRadioGroupSelect', 'BlockRadioGroupSelect',
  'SshKeyPairSelection', 'UserDataSelection', 'NetworkConfiguration', 'NetworkSelection', 'AffinityGroupSelection', 'TemplateIsoSelection',
  'OsBasedImageSelection', 'DiskSizeSelection', 'MultiDiskSelection', 'DiskOfferingSelection', 'ComputeOfferingSelection', 'ComputeSelection',
  'SecurityGroupSelection', 'InstanceNicsNetworkSelectListView', 'DetailsInput', 'DeployInstanceBackupSelection', 'PlacementStep']

const mounted = []

const mountWizard = async ({ query = '', config = shippedConfig, real = [] } = {}) => {
  const apis = apiMap(minimalUser.apis)
  global.allowedApis = apis
  const store = common.createMockStore({
    app: { device: 'desktop' },
    user: { apis, info: userInfo, features: {}, zones: [zone] }
  })
  const router = common.createMockRouter([{ name: 'deployVirtualMachine', path: '/action/deployVirtualMachine', meta: { name: 'vm' }, component: {} }])
  router.push('/action/deployVirtualMachine' + query)
  await router.isReady()
  const $pollJob = jest.fn()
  const $notifyError = jest.fn()
  const $notification = { warning: jest.fn(), error: jest.fn() }
  const wrapper = common.createFactory(DeployVM, {
    router,
    store,
    i18n: common.createMockI18n('en', { en }),
    mocks: { $config: config, $notifyError, $notification, $fetchCpuArchitectureTypes: () => [], $pollJob },
    stubs: Object.fromEntries(stubbed.filter(name => !real.includes(name)).map(name => [name, true]))
  })
  await flushPromises()
  await flushPromises()
  wrapper.$pollJob = $pollJob
  wrapper.$notifyError = $notifyError
  wrapper.$notification = $notification
  mounted.push(wrapper)
  return wrapper
}

const calls = (command) => getAPI.mock.calls.filter(call => call[0] === command).map(call => call[1])

const child = (wrapper, name, decorator) => wrapper.findAllComponents({ name })
  .find(component => decorator === undefined || component.props('inputDecorator') === decorator)

const useTemplate = async (wrapper, sizeGiB) => {
  const template = { id: 't-' + sizeGiB, displaytext: 'image', size: sizeGiB * GiB, hypervisor: 'KVM', details: {} }
  wrapper.vm.options.templates = { executable: { count: 1, template: [template] } }
  wrapper.vm.updateFieldValue('templateid', template.id)
  await flushPromises()
  return template
}

const useOffering = async (wrapper, offering) => {
  wrapper.vm.options.serviceOfferings = [offering]
  wrapper.vm.updateComputeOffering(offering.id)
  await flushPromises()
}

jest.setTimeout(60000)

describe('deploy wizard: bounds from quota, OS disk, placement', () => {
  const unhandled = []
  const keepUnhandled = (reason) => unhandled.push(reason)

  beforeAll(() => {
    process.on('unhandledRejection', keepUnhandled)
  })

  afterAll(() => {
    process.off('unhandledRejection', keepUnhandled)
  })

  beforeEach(() => {
    jest.clearAllMocks()
    reset()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command, params) => {
      // a command outside the minimal role's list is refused, as the server would
      if (!(command in (global.allowedApis || {}))) {
        return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
      }
      return Promise.resolve(answers(command, params))
    })
    postAPI.mockImplementation(() => Promise.resolve({ deployvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
    global.allowedApis = undefined
    document.body.innerHTML = ''
  })

  it('reads the root disk floor and default from listCapabilities', async () => {
    state.capabilities = { ...capabilities, rootdisksizemin: 12, rootdisksizedefault: 25 }
    const wrapper = await mountWizard()
    expect(calls('listCapabilities').length).toBeGreaterThan(0)
    await useTemplate(wrapper, 5)
    const osDisk = child(wrapper, 'DiskSizeSelection', 'rootdisksize')
    expect(osDisk.props('minDiskSize')).toBe(12)
    expect(osDisk.props('initialDiskSize')).toBe(25)
  })

  it('OS disk min is max(template, floor) and max is remaining storage quota', async () => {
    const wrapper = await mountWizard()
    // a template below the floor: the floor binds, the default is the operator's
    await useTemplate(wrapper, 5)
    let osDisk = child(wrapper, 'DiskSizeSelection', 'rootdisksize')
    expect(osDisk.props('minDiskSize')).toBe(10)
    expect(osDisk.props('initialDiskSize')).toBe(20)
    expect(osDisk.props('maxDiskSize')).toBe(77)
    expect(osDisk.props('isCustomized')).toBe(true)

    // a larger template raises both, in whole GiB
    await useTemplate(wrapper, 31)
    osDisk = child(wrapper, 'DiskSizeSelection', 'rootdisksize')
    expect(osDisk.props('minDiskSize')).toBe(31)
    expect(osDisk.props('initialDiskSize')).toBe(31)
    wrapper.vm.options.templates = { executable: { count: 1, template: [{ id: 't-fraction', size: 12.5 * GiB, hypervisor: 'KVM', details: {} }] } }
    wrapper.vm.updateFieldValue('templateid', 't-fraction')
    await flushPromises()
    expect(child(wrapper, 'DiskSizeSelection', 'rootdisksize').props('minDiskSize')).toBe(13)
    expect(child(wrapper, 'DiskSizeSelection', 'rootdisksize').props('initialDiskSize')).toBe(20)
  })

  it('has no override switches: the OS disk section is the only root disk control', async () => {
    const wrapper = await mountWizard()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    const html = wrapper.html()
    expect(en['label.override.rootdisk.size']).toBeDefined()
    expect(html).not.toContain(en['label.override.rootdisk.size'])
    expect(html).not.toContain(en['label.override.root.diskoffering'])
    expect(html).toContain(en['label.os.disk'])
    expect(wrapper.findAllComponents({ name: 'DiskSizeSelection' }).filter(c => c.props('inputDecorator') === 'rootdisksize')).toHaveLength(1)
  })

  it('storage maximum is the API\'s remaining storage quota, with no client-side subtraction', async () => {
    const wrapper = await mountWizard()
    expect(calls('listAccounts')[0]).toMatchObject({ id: 'acc-1', details: 'resource,storagequota' })
    await useTemplate(wrapper, 5)
    wrapper.vm.options.diskOfferings = [{ id: 'd-custom', iscustomized: true, displaytext: 'data' }]
    wrapper.vm.form.diskofferingid = 'd-custom'
    wrapper.vm.updateFieldValue('rootdisksize', 30)
    wrapper.vm.updateFieldValue('size', 10)
    await flushPromises()
    const osDisk = child(wrapper, 'DiskSizeSelection', 'rootdisksize')
    const dataDisk = child(wrapper, 'DiskSizeSelection', 'size')
    // 77 is the figure the API reported (not 125 - 40, and not less the 30 and 10 chosen here)
    expect(osDisk.props('maxDiskSize')).toBe(77)
    expect(dataDisk.props('maxDiskSize')).toBe(77)
  })

  it('leaves the storage unbounded when the quota is unlimited or unknown', async () => {
    account.remainingstoragequota = -1
    try {
      const wrapper = await mountWizard()
      await useTemplate(wrapper, 5)
      expect(child(wrapper, 'DiskSizeSelection', 'rootdisksize').props('maxDiskSize')).toBeNull()
    } finally {
      account.remainingstoragequota = 77
    }
  })

  it('vCPU and RAM max are remaining cpu quota, not capacity', async () => {
    state.capacity = { maxvcpu: 4, maxmemory: 2048, maxdisk: 10 }
    const wrapper = await mountWizard({ real: ['PlacementStep'] })
    expect(calls('listResourceLimits').find(params => params.tag === 'cpu')).toBeDefined()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    // the placement step asks 500 ms after its inputs settle
    await new Promise(resolve => setTimeout(resolve, 700))
    await flushPromises()
    let compute = child(wrapper, 'ComputeSelection')
    // 16 - 4 vCPU and 32768 - 8192 MiB remain; the placement step's maxima (4 vCPU, 2048 MiB) bound nothing
    expect(compute.props('maxCpu')).toBe(12)
    expect(compute.props('maxMemory')).toBe(24576)
    expect(calls('listPlacementCapacity').length).toBeGreaterThan(0)
    expect(calls('listPlacementCapacity')[0]).toMatchObject({ zoneid: 'z-1', serviceofferingid: 'o-cpu' })

    state.capacity = { maxvcpu: 400, maxmemory: 9999999, maxdisk: 10 }
    wrapper.vm.updateFieldValue('cpunumber', 6)
    await new Promise(resolve => setTimeout(resolve, 700))
    await flushPromises()
    compute = child(wrapper, 'ComputeSelection')
    expect(compute.props('maxCpu')).toBe(12)
    expect(compute.props('maxMemory')).toBe(24576)
  })

  it('the offering\'s own maximum still binds when it is below the quota', async () => {
    const wrapper = await mountWizard()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, { ...cpuOffering, serviceofferingdetails: { ...cpuOffering.serviceofferingdetails, maxcpunumber: '8', maxmemory: '4096' } })
    const compute = child(wrapper, 'ComputeSelection')
    expect(compute.props('maxCpu')).toBe(8)
    expect(compute.props('maxMemory')).toBe(4096)
  })

  it('vCPU input steps by listCapabilities poolvcpustep (default 2) with minimum 2 in DeployVM', async () => {
    const wrapper = await mountWizard()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    let compute = child(wrapper, 'ComputeSelection')
    expect(compute.props('cpuStep')).toBe(2)
    expect(compute.props('minCpu')).toBe(2)

    // an offering whose own minimum is below the step starts at the step
    await useOffering(wrapper, { ...cpuOffering, serviceofferingdetails: { ...cpuOffering.serviceofferingdetails, mincpunumber: '1' } })
    expect(child(wrapper, 'ComputeSelection').props('minCpu')).toBe(2)

    // without the capability the step is 2; with a step of 1 odd values are offered
    state.capabilities = { ...capabilities }
    delete state.capabilities.poolvcpustep
    const byDefault = await mountWizard()
    await useTemplate(byDefault, 5)
    await useOffering(byDefault, cpuOffering)
    expect(child(byDefault, 'ComputeSelection').props('cpuStep')).toBe(2)

    state.capabilities = { ...capabilities, poolvcpustep: 1 }
    const stepOne = await mountWizard()
    await useTemplate(stepOne, 5)
    await useOffering(stepOne, cpuOffering)
    compute = child(stepOne, 'ComputeSelection')
    expect(compute.props('cpuStep')).toBe(1)
    expect(compute.props('minCpu')).toBe(2)
  })

  it('a GPU offering takes no vCPU step and its quota bounds come from its own type', async () => {
    const wrapper = await mountWizard()
    expect(child(wrapper, 'ComputeOfferingSelection').props('gpuQuota')).toEqual({ 'a100-40g': 1, 'a100-80g': 0 })
  })

  it('sends the OS disk size and no override of the root disk offering', async () => {
    const wrapper = await mountWizard()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    wrapper.vm.updateFieldValue('cpunumber', 4)
    wrapper.vm.updateFieldValue('memory', 4096)
    wrapper.vm.updateFieldValue('rootdisksize', 30)
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    const [command, params] = postAPI.mock.calls[0]
    expect(command).toBe('deployVirtualMachine')
    expect(params).toMatchObject({ templateid: 't-5', serviceofferingid: 'o-cpu', rootdisksize: 30, 'details[0].cpuNumber': 4, 'details[0].memory': 4096 })
    expect(params).not.toHaveProperty('overridediskofferingid')
    expect(params).not.toHaveProperty('migratevolumes')
  })

  it('PlacementStep gets the request of the chosen combination, and the CPU-only offering for the capacity', async () => {
    const wrapper = await mountWizard()
    expect(child(wrapper, 'PlacementStep')).toBeDefined()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    wrapper.vm.updateFieldValue('cpunumber', 4)
    wrapper.vm.updateFieldValue('memory', 4096)
    wrapper.vm.updateFieldValue('rootdisksize', 30)
    await flushPromises()
    let step = child(wrapper, 'PlacementStep')
    expect(step.props('request')).toMatchObject({ templateid: 't-5', serviceofferingid: 'o-cpu', zoneid: 'z-1', rootdisksize: 30, 'details[0].cpuNumber': 4, 'details[0].memory': 4096 })
    expect(step.props('zoneid')).toBe('z-1')
    expect(step.props('capacityOfferingId')).toBe('o-cpu')

    await useOffering(wrapper, gpuOffering)
    step = child(wrapper, 'PlacementStep')
    expect(step.props('request')).toMatchObject({ serviceofferingid: 'o-gpu' })
    expect(step.props('capacityOfferingId')).toBe('')
  })

  describe('deploy from a volume', () => {
    const openVolumeDeploy = async () => {
      const wrapper = await mountWizard()
      wrapper.vm.options.volumes = { executable: { count: 1, volume: [{ id: 'vol-1', name: 'ROOT-3', displaytext: 'ROOT-3' }] } }
      wrapper.vm.updateFieldValue('volumeid', 'vol-1')
      await useOffering(wrapper, { ...cpuOffering, iscustomized: false, cpunumber: 2, memory: 4096 })
      return wrapper
    }

    it('shows the move modal and sends migratevolumes and placementtoken', async () => {
      state.placements = [movePlacement('tok-1')]
      const wrapper = await openVolumeDeploy()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()

      expect(calls('checkPlacement')[0]).toMatchObject({ volumeid: 'vol-1', serviceofferingid: 'o-cpu' })
      expect(postAPI).not.toHaveBeenCalled()
      const modal = wrapper.findComponent(PlacementMoveModal)
      expect(modal.props('visible')).toBe(true)
      expect(modal.props('operation')).toBe('deploy')
      expect(document.body.textContent).toContain('ROOT-3')

      document.querySelector('.placement-move-approve').click()
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      const [command, params] = postAPI.mock.calls[0]
      expect(command).toBe('deployVirtualMachine')
      expect(params).toMatchObject({ volumeid: 'vol-1', serviceofferingid: 'o-cpu', migratevolumes: true, placementtoken: 'tok-1' })
    })

    it('sends nothing when the move is rejected', async () => {
      state.placements = [movePlacement('tok-1')]
      const wrapper = await openVolumeDeploy()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      document.querySelector('.placement-move-reject').click()
      await flushPromises()
      expect(postAPI).not.toHaveBeenCalled()
      expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(false)
      expect(wrapper.vm.loading.deploy).toBe(false)
    })

    it('shows the new verdict when the token no longer matches', async () => {
      state.placements = [movePlacement('tok-1'), movePlacement('tok-2')]
      const refused = Object.assign(new Error('move'), { response: { status: 530, data: { deployvirtualmachineresponse: { cserrorcode: 4336, errortext: 'move' } } } })
      postAPI.mockImplementationOnce(() => Promise.reject(refused))
      const wrapper = await openVolumeDeploy()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      document.querySelector('.placement-move-approve').click()
      await flushPromises()
      expect(postAPI.mock.calls[0][1].placementtoken).toBe('tok-1')
      expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(true)
      expect(wrapper.findComponent(PlacementMoveModal).props('verdict').placementtoken).toBe('tok-2')

      document.querySelector('.placement-move-approve').click()
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(2)
      expect(postAPI.mock.calls[1][1].placementtoken).toBe('tok-2')
    })

    it('deploys at once when no move is needed', async () => {
      state.placements = [{ verdict: 'here' }]
      const wrapper = await openVolumeDeploy()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI.mock.calls[0][1]).toMatchObject({ volumeid: 'vol-1' })
      expect(postAPI.mock.calls[0][1]).not.toHaveProperty('migratevolumes')
      expect(postAPI.mock.calls[0][1]).not.toHaveProperty('placementtoken')
    })

    it('opens on the volume it was asked for', async () => {
      const wrapper = await mountWizard({ query: '?volumeid=vol-1&zoneid=z-1' })
      expect(wrapper.vm.imageType).toBe('volumeid')
    })

    it('does not keep "stay on page" after the move is rejected', async () => {
      state.placements = [movePlacement('tok-1')]
      const wrapper = await openVolumeDeploy()
      wrapper.vm.form.stayonpage = true
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      document.querySelector('.placement-move-reject').click()
      await flushPromises()
      expect(wrapper.vm.form.stayonpage).toBe(false)
    })

    it('shows the new verdict when a deployment that carried no approval is refused with the move-required code', async () => {
      state.placements = [{ verdict: 'here' }, movePlacement('tok-2')]
      const refused = Object.assign(new Error('move'), { response: { status: 530, data: { deployvirtualmachineresponse: { cserrorcode: 4336, errortext: 'move' } } } })
      postAPI.mockImplementationOnce(() => Promise.reject(refused))
      const wrapper = await openVolumeDeploy()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI.mock.calls[0][1]).not.toHaveProperty('placementtoken')
      expect(wrapper.$notifyError).not.toHaveBeenCalled()
      expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(true)
      expect(wrapper.findComponent(PlacementMoveModal).props('verdict').placementtoken).toBe('tok-2')

      document.querySelector('.placement-move-approve').click()
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(2)
      expect(postAPI.mock.calls[1][1]).toMatchObject({ migratevolumes: true, placementtoken: 'tok-2' })
    })

    it('stays on the page after the move shown again is approved, when Launch and stay was chosen', async () => {
      state.placements = [{ verdict: 'here' }, movePlacement('tok-2')]
      const refused = Object.assign(new Error('move'), { response: { status: 530, data: { deployvirtualmachineresponse: { cserrorcode: 4336, errortext: 'move' } } } })
      postAPI.mockImplementationOnce(() => Promise.reject(refused))
      const wrapper = await openVolumeDeploy()
      const back = jest.spyOn(wrapper.vm.$router, 'back').mockImplementation(() => {})
      wrapper.vm.form.stayonpage = true
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(true)

      document.querySelector('.placement-move-approve').click()
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(2)
      expect(back).not.toHaveBeenCalled()
      // and the choice does not outlive that deployment
      expect(wrapper.vm.form.stayonpage).toBe(false)
    })

    it('shows the refusal and drops the choice to stay when the check finds no move to approve after it', async () => {
      state.placements = [{ verdict: 'here' }, { verdict: 'here' }]
      const refused = Object.assign(new Error('move'), { response: { status: 530, data: { deployvirtualmachineresponse: { cserrorcode: 4336, errortext: 'move' } } } })
      postAPI.mockImplementationOnce(() => Promise.reject(refused))
      const wrapper = await openVolumeDeploy()
      wrapper.vm.form.stayonpage = true
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(wrapper.$notifyError).toHaveBeenCalledWith(refused)
      expect(wrapper.vm.loading.deploy).toBe(false)
      expect(wrapper.vm.form.stayonpage).toBe(false)
    })

    it('deploys without an approval when the check itself fails, and says the check could not be made', async () => {
      const wrapper = await openVolumeDeploy()
      getAPI.mockImplementation((command, params) => command === 'checkPlacement'
        ? Promise.reject(new Error('down'))
        : Promise.resolve(answers(command, params)))
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      expect(postAPI).toHaveBeenCalledTimes(1)
      expect(postAPI.mock.calls[0][1]).toMatchObject({ volumeid: 'vol-1' })
      expect(postAPI.mock.calls[0][1]).not.toHaveProperty('migratevolumes')
      expect(wrapper.$notification.warning).toHaveBeenCalledTimes(1)
      expect(wrapper.$notification.warning.mock.calls[0][0].description).toBe(en['message.placement.check.failed'])
      expect(wrapper.$notifyError).not.toHaveBeenCalled()
    })
  })

  it('passes the root key choice to the OS-disk step for an offering that encrypts the root', async () => {
    const wrapper = await mountWizard()
    await useTemplate(wrapper, 5)
    await useOffering(wrapper, cpuOffering)
    expect(child(wrapper, 'DiskSizeSelection', 'rootdisksize').props('computeOfferingEncryptRoot')).toBe(false)

    await useOffering(wrapper, { ...cpuOffering, id: 'o-cpu-encrypted', encryptroot: true })
    const osDisk = child(wrapper, 'DiskSizeSelection', 'rootdisksize')
    expect(osDisk.props('computeOfferingEncryptRoot')).toBe(true)
    expect(osDisk.props('kmsKeys')).toEqual(wrapper.vm.options.kmsKeys)
    osDisk.vm.$emit('update-root-kms-key', 'key-1')
    wrapper.vm.updateFieldValue('cpunumber', 4)
    wrapper.vm.updateFieldValue('memory', 4096)
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(postAPI.mock.calls[0][1]).toMatchObject({ rootdiskkmskeyid: 'key-1' })
  })

  it('CPU speed is never shown with the default config, and shown with showCpuSpeed', async () => {
    const { serviceofferingdetails, cpuspeed, ...unconstrained } = cpuOffering
    const byDefault = await mountWizard({ real: ['ComputeSelection'] })
    await useTemplate(byDefault, 5)
    await useOffering(byDefault, unconstrained)
    expect(byDefault.html()).not.toContain(en['label.cpuspeed'])

    const shown = await mountWizard({ real: ['ComputeSelection'], config: { ...shippedConfig, showCpuSpeed: true } })
    await useTemplate(shown, 5)
    await useOffering(shown, unconstrained)
    expect(shown.html()).toContain(en['label.cpuspeed'])
  })
})
