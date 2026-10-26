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

const zone = { id: 'z-1', name: 'zone', networktype: 'Advanced', securitygroupsenabled: true, allocationstate: 'Enabled' }
const userInfo = { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }

const offering = { id: 'o-cpu', name: 'cpu', iscustomized: false, hosttags: 'cpu', cpunumber: 2, memory: 4096, diskofferingid: 'do-1', diskofferingstrictness: true }
const customOffering = { ...offering, id: 'o-custom', iscustomized: true, serviceofferingdetails: { mincpunumber: '2', maxcpunumber: '4096', minmemory: '32', maxmemory: '67108864' } }

const snapshots = [
  { id: 's-1', name: 'ROOT-3-snapshot-0', state: 'BackedUp', volumetype: 'ROOT', zoneid: 'z-1' },
  { id: 's-2', name: 'data-snapshot-0', state: 'BackedUp', volumetype: 'DATADISK', zoneid: 'z-1' },
  { id: 's-3', name: 'ROOT-3-snapshot-1', state: 'Creating', volumetype: 'ROOT', zoneid: 'z-1' },
  { id: 's-4', name: 'ROOT-4-snapshot-0', state: 'BackedUp', volumetype: 'ROOT', zoneid: 'z-1' }
]
const volumes = [
  { id: 'v-root', name: 'ROOT-3', state: 'Ready', type: 'DATADISK', templateid: 't-1', zoneid: 'z-1' },
  { id: 'v-data', name: 'data', state: 'Ready', type: 'DATADISK', zoneid: 'z-1' },
  { id: 'v-attached', name: 'ROOT-9', state: 'Ready', type: 'ROOT', templateid: 't-1', virtualmachineid: 'vm-9', zoneid: 'z-1' },
  { id: 'v-other', name: 'ROOT-5', state: 'Ready', type: 'DATADISK', templateid: 't-2', zoneid: 'z-1' }
]

let state

const reset = () => {
  state = {
    capabilities: { rootdisksizemin: 10, rootdisksizedefault: 20, poolvcpustep: 2 },
    placements: [{ verdict: 'anyhost' }]
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
      return { listresourcelimitsresponse: { resourcelimit: [] } }
    case 'listAccounts':
      return { listaccountsresponse: { count: 1, account: [{ id: 'acc-1', name: 'student', remainingstoragequota: 77, taggedresources: [] }] } }
    case 'checkPlacement':
      return { checkplacementresponse: { placement: state.placements.length > 1 ? state.placements.shift() : state.placements[0] } }
    case 'listPlacementCapacity':
      return { listplacementcapacityresponse: { placementcapacity: { maxvcpu: 4, maxmemory: 2048, maxdisk: 50 } } }
    case 'listSnapshots': {
      const found = params.id ? snapshots.filter(snapshot => snapshot.id === params.id) : snapshots
      return { listsnapshotsresponse: { count: found.length, snapshot: found } }
    }
    case 'listVolumes': {
      const found = params.id ? volumes.filter(volume => volume.id === params.id) : volumes
      return { listvolumesresponse: { count: found.length, volume: found } }
    }
    default:
      return {}
  }
}

const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))

const stubbed = ['OwnershipSelection', 'InfoCard', 'DeployButtons', 'ResourceIcon', 'ZoneBlockRadioGroupSelect', 'BlockRadioGroupSelect',
  'SshKeyPairSelection', 'UserDataSelection', 'NetworkConfiguration', 'NetworkSelection', 'AffinityGroupSelection', 'TemplateIsoSelection',
  'OsBasedImageSelection', 'DiskSizeSelection', 'MultiDiskSelection', 'DiskOfferingSelection', 'ComputeOfferingSelection', 'ComputeSelection',
  'SecurityGroupSelection', 'InstanceNicsNetworkSelectListView', 'DetailsInput', 'DeployInstanceBackupSelection', 'PlacementStep']

const mounted = []

const mountWizard = async ({ query = '', real = [] } = {}) => {
  const apis = apiMap(minimalUser.apis)
  global.allowedApis = apis
  const store = common.createMockStore({
    app: { device: 'desktop' },
    user: { apis, info: userInfo, features: {}, zones: [zone] }
  })
  const router = common.createMockRouter([{ name: 'deployVirtualMachine', path: '/action/deployVirtualMachine', meta: { name: 'vm' }, component: {} }])
  router.push('/action/deployVirtualMachine' + query)
  await router.isReady()
  const wrapper = common.createFactory(DeployVM, {
    router,
    store,
    i18n: common.createMockI18n('en', { en }),
    mocks: { $config: shippedConfig, $notifyError: jest.fn(), $notification: { warning: jest.fn(), error: jest.fn() }, $fetchCpuArchitectureTypes: () => [], $pollJob: jest.fn() },
    stubs: Object.fromEntries(stubbed.filter(name => !real.includes(name)).map(name => [name, true]))
  })
  await flushPromises()
  await flushPromises()
  mounted.push(wrapper)
  return wrapper
}

const calls = (command) => getAPI.mock.calls.filter(call => call[0] === command).map(call => call[1])
const child = (wrapper, name, decorator) => wrapper.findAllComponents({ name })
  .find(component => decorator === undefined || component.props('inputDecorator') === decorator)

const useOffering = async (wrapper, chosen) => {
  wrapper.vm.options.serviceOfferings = [chosen]
  wrapper.vm.updateComputeOffering(chosen.id)
  await flushPromises()
}

const useSnapshot = async (wrapper, id = 's-1') => {
  wrapper.vm.options.snapshots = { executable: { count: 1, snapshot: snapshots.filter(snapshot => snapshot.id === id) } }
  wrapper.vm.updateFieldValue('snapshotid', id)
  await flushPromises()
}

jest.setTimeout(60000)

describe('deploy wizard: images from snapshots and volumes', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    reset()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command, params) => {
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

  describe('the Snapshot image list', () => {
    it('lists only the backed-up snapshots of a ROOT disk of the account', async () => {
      const wrapper = await mountWizard()
      wrapper.vm.changeImageType('snapshotid')
      await flushPromises()
      const listed = calls('listSnapshots')
      expect(listed.length).toBeGreaterThan(0)
      expect(listed[0]).toMatchObject({ zoneid: 'z-1', account: 'student', domainid: 'd-1' })
      expect(listed[0]).not.toHaveProperty('listall')
      const [listing] = Object.values(wrapper.vm.options.snapshots)
      expect(listing.snapshot.map(snapshot => snapshot.id)).toEqual(['s-1', 's-4'])
      expect(listing.count).toBe(2)
      expect(listing.snapshot[0].displaytext).toBe('ROOT-3-snapshot-0')
    })

    it('is chosen by its id, and clears the other kinds of image', async () => {
      const wrapper = await mountWizard()
      await useSnapshot(wrapper)
      expect(wrapper.vm.imageType).toBe('snapshotid')
      expect(wrapper.vm.form.snapshotid).toBe('s-1')
      expect(wrapper.vm.form.templateid).toBeNull()
      expect(wrapper.vm.form.volumeid).toBeNull()
    })

    it('opens on the snapshot it was asked for, in its zone', async () => {
      const wrapper = await mountWizard({ query: '?snapshotid=s-4&zoneid=z-1' })
      expect(wrapper.vm.imageType).toBe('snapshotid')
      expect(calls('listSnapshots').some(params => params.id === 's-4')).toBe(true)
      expect(wrapper.vm.form.snapshotid).toBe('s-4')
    })

    it('does not choose a snapshot it was asked for that cannot boot an Instance', async () => {
      const wrapper = await mountWizard({ query: '?snapshotid=s-2&zoneid=z-1' })
      expect(wrapper.vm.imageType).toBe('snapshotid')
      expect(wrapper.vm.form.snapshotid).toBeFalsy()
    })
  })

  describe('the Volume image list', () => {
    it('lists only the detached Ready volumes made from a template', async () => {
      const wrapper = await mountWizard()
      wrapper.vm.changeImageType('volumeid')
      await flushPromises()
      expect(calls('listVolumes')[0]).toMatchObject({ state: 'Ready', zoneid: 'z-1' })
      const [listing] = Object.values(wrapper.vm.options.volumes)
      expect(listing.volume.map(volume => volume.id)).toEqual(['v-root', 'v-other'])
      expect(listing.count).toBe(2)
    })

    it('does not choose the volume it was asked for when it was made from no template', async () => {
      const wrapper = await mountWizard({ query: '?volumeid=v-data&zoneid=z-1' })
      expect(wrapper.vm.imageType).toBe('volumeid')
      expect(wrapper.vm.form.volumeid).toBeFalsy()
    })

    it('still opens on the volume it was asked for when it can boot', async () => {
      const wrapper = await mountWizard({ query: '?volumeid=v-root&zoneid=z-1' })
      expect(wrapper.vm.form.volumeid).toBe('v-root')
    })
  })

  describe('the placement step of a deployment from a snapshot', () => {
    it('is shown, and gets the snapshot and the offering, with the zone as for a Template', async () => {
      const wrapper = await mountWizard()
      await useSnapshot(wrapper)
      await useOffering(wrapper, offering)
      const step = child(wrapper, 'PlacementStep')
      expect(step).toBeDefined()
      expect(step.props('request')).toEqual({ snapshotid: 's-1', serviceofferingid: 'o-cpu', zoneid: 'z-1' })
      expect(step.props('zoneid')).toBe('z-1')
      expect(step.props('capacityOfferingId')).toBe('o-cpu')
    })

    it('carries the data disk size and the customized CPU and memory, and no root disk size', async () => {
      const wrapper = await mountWizard()
      await useSnapshot(wrapper)
      await useOffering(wrapper, customOffering)
      wrapper.vm.updateFieldValue('cpunumber', 4)
      wrapper.vm.updateFieldValue('memory', 4096)
      wrapper.vm.updateFieldValue('rootdisksize', 30)
      wrapper.vm.options.diskOfferings = [{ id: 'd-custom', iscustomized: true, displaytext: 'data' }]
      wrapper.vm.form.diskofferingid = 'd-custom'
      wrapper.vm.updateFieldValue('size', 10)
      await flushPromises()
      expect(child(wrapper, 'PlacementStep').props('request')).toEqual({
        snapshotid: 's-1',
        serviceofferingid: 'o-custom',
        zoneid: 'z-1',
        datadisksizes: '10',
        'details[0].cpuNumber': 4,
        'details[0].memory': 4096
      })
    })

    it('asks nothing until a snapshot and an offering are chosen', async () => {
      const wrapper = await mountWizard()
      wrapper.vm.changeImageType('snapshotid')
      await flushPromises()
      expect(child(wrapper, 'PlacementStep').props('request')).toEqual({})
      await useOffering(wrapper, offering)
      expect(child(wrapper, 'PlacementStep').props('request')).toEqual({})
    })

    it('shows the verdict of checkPlacement like that of a Template', async () => {
      state.placements = [{ verdict: 'none', reason: 'memory' }]
      const wrapper = await mountWizard({ real: ['PlacementStep'] })
      await useSnapshot(wrapper)
      await useOffering(wrapper, offering)
      await new Promise(resolve => setTimeout(resolve, 700))
      await flushPromises()
      expect(calls('checkPlacement').pop()).toEqual({ snapshotid: 's-1', serviceofferingid: 'o-cpu', zoneid: 'z-1' })
      const alert = wrapper.find('.placement-verdict')
      expect(alert.exists()).toBe(true)
      expect(alert.classes()).toContain('ant-alert-error')
    })
  })

  describe('the deployment from a snapshot', () => {
    it('sends the snapshot and the offering at once, never a move or an approval', async () => {
      state.placements = [{ verdict: 'move', volumes: [], bytestomove: 1, estimatedseconds: 1, placementtoken: 'tok-1' }]
      const wrapper = await mountWizard()
      await useSnapshot(wrapper)
      await useOffering(wrapper, offering)
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()

      expect(calls('checkPlacement')).toHaveLength(0)
      expect(postAPI).toHaveBeenCalledTimes(1)
      const [command, params] = postAPI.mock.calls[0]
      expect(command).toBe('deployVirtualMachine')
      expect(params).toMatchObject({ snapshotid: 's-1', serviceofferingid: 'o-cpu', zoneid: 'z-1' })
      for (const absent of ['templateid', 'volumeid', 'rootdisksize', 'migratevolumes', 'placementtoken']) {
        expect(params).not.toHaveProperty(absent)
      }
      expect(wrapper.findComponent(PlacementMoveModal).props('visible')).toBe(false)
    })

    const fixedRoot = { ...offering, id: 'o-fixed', rootdisksize: 30 }
    const useFixedRootOffering = async (wrapper) => {
      await useOffering(wrapper, fixedRoot)
      // the fixed root size of the offering is taken a moment after it is chosen
      await new Promise(resolve => setTimeout(resolve, 700))
      await flushPromises()
      expect(wrapper.vm.rootDiskSizeFixed).toBe(30)
    }
    const posted = async (wrapper) => {
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      return postAPI.mock.calls[0][1]
    }

    it('sends no root disk size for a snapshot although the offering fixes one', async () => {
      const wrapper = await mountWizard()
      await useSnapshot(wrapper)
      await useFixedRootOffering(wrapper)
      const params = await posted(wrapper)
      expect(params).toMatchObject({ snapshotid: 's-1', serviceofferingid: 'o-fixed' })
      expect(params).not.toHaveProperty('rootdisksize')
    })

    it('sends no root disk size for a volume although the offering fixes one', async () => {
      const wrapper = await mountWizard()
      wrapper.vm.options.volumes = { executable: { count: 1, volume: [{ id: 'v-root', name: 'ROOT-3', displaytext: 'ROOT-3' }] } }
      wrapper.vm.updateFieldValue('volumeid', 'v-root')
      await useFixedRootOffering(wrapper)
      const params = await posted(wrapper)
      expect(params).toMatchObject({ volumeid: 'v-root', serviceofferingid: 'o-fixed' })
      expect(params).not.toHaveProperty('rootdisksize')
    })

    it('still sends the fixed root disk size of a Template', async () => {
      const wrapper = await mountWizard()
      wrapper.vm.options.templates = { executable: { count: 1, template: [{ id: 't-1', displaytext: 'image', size: 5 * 1024 * 1024 * 1024, hypervisor: 'KVM', details: {} }] } }
      wrapper.vm.updateFieldValue('templateid', 't-1')
      await useFixedRootOffering(wrapper)
      const params = await posted(wrapper)
      expect(params).toMatchObject({ templateid: 't-1', rootdisksize: 30 })
    })
  })
})
