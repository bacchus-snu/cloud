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
import OsBasedImageSelection from '@/views/compute/wizard/OsBasedImageSelection'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))

const zone = { id: 'z-1', name: 'zone', networktype: 'Advanced', securitygroupsenabled: true, allocationstate: 'Enabled' }
const sgNetwork = { id: 'n-1', name: 'guest', service: [{ name: 'SecurityGroup' }] }

const answers = {
  listZones: { listzonesresponse: { count: 1, zone: [zone] } },
  listNetworks: { listnetworksresponse: { count: 1, network: [sgNetwork] } },
  listAffinityGroups: { listaffinitygroupsresponse: { count: 0, affinitygroup: [] } },
  listSecurityGroups: { listsecuritygroupsresponse: { count: 0, securitygroup: [] } }
}

const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))
const studentApis = () => apiMap(minimalUser.apis)

// the wizard's own steps and fields are under test, not the selection widgets inside them
const wizardParts = ['OwnershipSelection', 'InfoCard', 'DeployButtons', 'ResourceIcon', 'ZoneBlockRadioGroupSelect', 'BlockRadioGroupSelect',
  'SshKeyPairSelection', 'UserDataSelection', 'NetworkConfiguration', 'NetworkSelection', 'AffinityGroupSelection', 'TemplateIsoSelection',
  'OsBasedImageSelection', 'DiskSizeSelection', 'MultiDiskSelection', 'DiskOfferingSelection', 'ComputeOfferingSelection', 'ComputeSelection',
  'SecurityGroupSelection', 'InstanceNicsNetworkSelectListView', 'DetailsInput', 'DeployInstanceBackupSelection']

const mountWizard = async (apis, config = shippedConfig) => {
  const store = common.createMockStore({
    app: { device: 'desktop' },
    user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {}, zones: [zone] }
  })
  const router = common.createMockRouter([{ name: 'deployVirtualMachine', path: '/action/deployVirtualMachine', meta: { name: 'vm' }, component: {} }])
  router.push('/action/deployVirtualMachine')
  await router.isReady()
  const wrapper = common.createFactory(DeployVM, {
    router,
    store,
    i18n: common.createMockI18n('en', {}),
    mocks: { $config: config, $notifyError: jest.fn(), $fetchCpuArchitectureTypes: () => [] },
    stubs: Object.fromEntries(wizardParts.map(name => [name, true]))
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const calledLists = () => getAPI.mock.calls.map(call => call[0])

// a refused call the page leaves unhandled must not end the test run; the assertions name what was asked for
const unhandled = []
const keepUnhandled = (reason) => unhandled.push(reason)

jest.setTimeout(60000)

describe('deploy wizard API guards', () => {
  beforeAll(() => {
    process.on('unhandledRejection', keepUnhandled)
  })

  afterAll(() => {
    process.off('unhandledRejection', keepUnhandled)
  })

  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command) => {
      if (answers[command]) {
        return Promise.resolve(answers[command])
      }
      if (!(command in (global.allowedApis || {}))) {
        return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
      }
      return Promise.resolve({})
    })
    postAPI.mockImplementation(() => Promise.resolve({}))
  })

  afterEach(() => {
    jest.restoreAllMocks()
    global.allowedApis = undefined
  })

  it('wizard hides network and SG steps when the network APIs are denied', async () => {
    global.allowedApis = studentApis()
    const wrapper = await mountWizard(global.allowedApis)
    expect(wrapper.vm.zoneSelected).toBe(true)
    expect(wrapper.vm.showNetworkSection).toBe(false)
    expect(wrapper.vm.showSecurityGroupSection).toBe(false)
    expect(wrapper.html()).not.toContain('label.networks')
    expect(wrapper.html()).not.toContain('label.security.groups')
    expect(calledLists()).not.toContain('listNetworks')
    expect(calledLists()).not.toContain('listSecurityGroups')

    // the deployment asks for no network: the server takes the zone's network with security groups
    expect(wrapper.vm.networkDeployParams({})).toEqual({})

    global.allowedApis = { ...studentApis(), listNetworks: { params: [] }, listSecurityGroups: { params: [] } }
    const full = await mountWizard(global.allowedApis)
    expect(full.vm.showNetworkSection).toBe(true)
    expect(full.html()).toContain('label.networks')
    expect(calledLists()).toContain('listNetworks')
    // with the step shown, a deployment without a network is still refused on the client
    expect(full.vm.networkDeployParams({ networkids: [] })).toBeNull()
    expect(full.vm.networkDeployParams({ networkids: ['n-1'] })).toEqual({ 'iptonetworklist[0].networkid': 'n-1' })
  })

  it('affinity field hidden without listAffinityGroups', async () => {
    global.allowedApis = studentApis()
    const wrapper = await mountWizard(global.allowedApis)
    wrapper.vm.showDetails = true
    await flushPromises()
    expect(wrapper.html()).not.toContain('label.affinity.groups')
    expect(calledLists()).not.toContain('listAffinityGroups')
    // nor any other list the role cannot call
    for (const denied of ['listSSHKeyPairs', 'listUserData', 'listHypervisors', 'listConfigurations', 'listInstanceGroups', 'listKMSKeys']) {
      expect(calledLists()).not.toContain(denied)
    }

    global.allowedApis = { ...studentApis(), listAffinityGroups: { params: [] } }
    const withAffinity = await mountWizard(global.allowedApis)
    withAffinity.vm.showDetails = true
    await flushPromises()
    expect(withAffinity.html()).toContain('label.affinity.groups')
  })

  it('wizard offers Template and Volume images only', async () => {
    const typesFor = (apis, config) => {
      const store = common.createMockStore({ user: { apis: apiMap(apis), info: { roletype: 'User' }, features: {} } })
      const wrapper = common.createFactory(OsBasedImageSelection, {
        store,
        i18n: common.createMockI18n('en', {}),
        mocks: { $config: config },
        props: {
          imageTypeSelectionAllowed: true,
          selectedImageType: 'templateid',
          guestOsCategoriesSelectionDisallowed: true,
          imageItems: {},
          filterOption: () => true
        },
        stubs: { OsBasedImageSelectionSearchView: true, OsBasedImageRadioGroup: true, DiskSizeSelection: true, BlockRadioGroupSelect: true }
      })
      return wrapper.findAll('.ant-radio-button-wrapper input').map(input => input.element.value)
    }

    expect(typesFor(minimalUser.apis, shippedConfig)).toEqual(['templateid', 'volumeid'])
    // a list the role cannot call removes its type; the snapshot type is the config's to hide
    expect(typesFor([...minimalUser.apis, 'listIsos'], { ...shippedConfig, hidden: {} })).toEqual(['templateid', 'isoid', 'volumeid', 'snapshotid'])
    expect(typesFor(minimalUser.apis.filter(api => api !== 'listVolumes'), shippedConfig)).toEqual(['templateid'])
  })
})
