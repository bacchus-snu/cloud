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
import ActionButton from '@/components/view/ActionButton'
import DetailsTab from '@/components/view/DetailsTab'
import InfoCard from '@/components/view/InfoCard'
import ResourceView from '@/components/view/ResourceView'
import InstanceTab from '@/views/compute/InstanceTab'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'
import deployment from '../../../mockData/DeploymentConfig.mock.json'

// one answer that fits every list the page asks for: nothing listed
const mockEmptyAnswer = { listresourceiconresponse: { icon: [] }, listaccountsresponse: { account: [] }, listtagsresponse: { tag: [] } }
jest.mock('@/api', () => ({ getAPI: jest.fn(() => Promise.resolve(mockEmptyAnswer)), postAPI: jest.fn(() => Promise.resolve({})), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: {}, zones: [], project: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
// the fork's default config with the deployment's data, which the fixture mirrors
const deployedConfig = { ...JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8')), ...deployment }
const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))
const i18n = common.createMockI18n('en', {})
const storeWith = (apis, roletype = 'User', features = {}) =>
  common.createMockStore({ user: { apis, info: { roletype }, features } })

const gpuVm = {
  id: 'vm-1',
  name: 'train',
  state: 'Running',
  zoneid: 'z-1',
  cpunumber: 32,
  cpuspeed: 2450,
  memory: 262144,
  serviceofferingid: 'so-1',
  serviceofferingname: 'a100-40g-2',
  gpucount: 2,
  gpucardname: 'A100-SXM4-40GB',
  gpucardid: 'card-1',
  vgpuprofilename: 'passthrough',
  hypervisor: 'KVM',
  securitygroup: [{ id: 'sg-1', name: 'default' }],
  leaseexpirydate: '2026-10-22T21:12:00+0000',
  leaseremainingseconds: 35 * 3600,
  leasehours: 36
}

const vmRouter = async () => {
  const router = common.createMockRouter([{ name: 'vm', path: '/vm/:id', meta: { name: 'vm' }, component: {} },
    { name: 'computeoffering', path: '/computeoffering/:id', meta: { name: 'computeoffering' }, component: {} },
    { path: '/:catchAll(.*)', redirect: '/exception/404' }])
  router.push('/vm/vm-1')
  await router.isReady()
  return router
}

const stubs = ['DetailsTab', 'StatsTab', 'EventsTab', 'DetailSettings', 'CreateVolume', 'NicsTab', 'GPUTab', 'ResourceSchedules',
  'ListResourceTable', 'SecurityGroupSelection', 'AnnotationsTab', 'VolumesTab']

const mountInstanceTab = async (apis) => {
  const router = await vmRouter()
  const wrapper = common.createFactory(InstanceTab, {
    router,
    i18n,
    store: storeWith(apis),
    props: { resource: gpuVm, loading: false },
    mocks: { $config: deployedConfig, parentFetchData: jest.fn() },
    provide: { parentFetchData: jest.fn() },
    stubs: Object.fromEntries(stubs.map(name => [name, true]))
  })
  return wrapper
}

const tabKeys = (wrapper) => wrapper.findAll('.ant-tabs-tab').map(tab => tab.text())

describe('instance page for the minimal user role', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('tabs hidden when tab.api not in apis', async () => {
    const router = await vmRouter()
    const Plain = { template: '<div class="plain-tab"/>' }
    const tabs = [
      { name: 'details', component: Plain },
      { name: 'comments', api: 'listAnnotations', component: Plain },
      { name: 'events', api: 'listEvents', show: () => true, component: Plain }
    ]
    const mountView = (apis) => common.createFactory(ResourceView, {
      router, i18n, store: storeWith(apis), props: { resource: { id: 'v-1' }, loading: false, tabs }, mocks: { $config: deployedConfig }
    })
    const without = mountView(apiMap(['listEvents']))
    expect(tabKeys(without)).toEqual(['label.details', 'label.events'])
    const withComments = mountView(apiMap(['listEvents', 'listAnnotations']))
    expect(tabKeys(withComments)).toEqual(['label.details', 'label.comments', 'label.events'])

    // every tab of the pages this role keeps names the list it calls
    for (const page of [storage.children.find(c => c.name === 'volume'), storage.children.find(c => c.name === 'snapshot')]) {
      expect(page.tabs.find(tab => tab.name === 'comments').api).toBe('listAnnotations')
    }
    expect(storage.children.find(c => c.name === 'volume').tabs.find(tab => tab.name === 'metrics').api).toBe('listVolumesUsageHistory')
  })

  it('instance view has no GPU tab for users and Details show the offering GPU card and count', async () => {
    const user = await mountInstanceTab(apiMap(minimalUser.apis))
    await flushPromises()
    expect(tabKeys(user)).not.toContain('label.gpu')
    for (const absent of ['label.nics', 'label.securitygroups', 'label.settings', 'label.annotations', 'label.vm.snapshots', 'label.backup']) {
      expect(tabKeys(user)).not.toContain(absent)
    }
    expect(tabKeys(user)).toEqual(expect.arrayContaining(['label.details', 'label.metrics', 'label.volumes', 'label.events']))
    // nothing the role cannot call is asked for on the way
    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listAnnotations')

    const admin = await mountInstanceTab(apiMap([...minimalUser.apis, 'listGpuDevices']))
    expect(tabKeys(admin)).toContain('label.gpu')

    // the Details tab carries the offering's GPU card and count from the instance response
    const details = compute.children.find(child => child.name === 'vm').details()
    expect(details).toEqual(expect.arrayContaining(['serviceofferingname', 'gpucardname', 'gpucount']))

    // the card names the GPU without a link to the device pages a user cannot open
    const router = await vmRouter()
    const card = common.createFactory(InfoCard, {
      router, i18n, store: storeWith(apiMap(minimalUser.apis)), props: { resource: gpuVm, loading: false }, mocks: { $config: deployedConfig }
    })
    expect(card.text()).toContain('2 x')
    expect(card.text()).toContain('A100-SXM4-40GB')
    expect(card.html()).not.toContain('/gpucard/')
  })

  it('the card shows the memory in GiB', async () => {
    const router = await vmRouter()
    const card = common.createFactory(InfoCard, {
      router, i18n, store: storeWith(apiMap(minimalUser.apis)), props: { resource: gpuVm, loading: false }, mocks: { $config: deployedConfig }
    })
    expect(card.text()).toContain('256 label.gib.memory')
    expect(card.text()).not.toContain('262144')
  })

  it('the card names the network of an interface with a link only for a role that has the network pages', async () => {
    const router = await vmRouter()
    const withNic = { ...gpuVm, nic: [{ id: 'nic-1', ipaddress: '10.0.0.5', networkid: 'net-1', networkname: 'guest-net', isdefault: true }] }
    const cardFor = (apis) => common.createFactory(InfoCard, {
      router, i18n, store: storeWith(apiMap(apis)), props: { resource: withNic, loading: false }, mocks: { $config: deployedConfig }
    })

    const student = cardFor(minimalUser.apis)
    expect(student.text()).toContain('guest-net')
    expect(student.html()).not.toContain('/guestnetwork/')

    const member = cardFor([...minimalUser.apis, 'listNetworks'])
    expect(member.text()).toContain('guest-net')
    expect(member.html()).toContain('/guestnetwork/net-1')
  })

  it('instance Details show no CPU speed with the default showCpuSpeed false, and show it only when the flag is true', async () => {
    const router = await vmRouter()
    const cardWith = (config) => common.createFactory(InfoCard, {
      router, i18n, store: storeWith(apiMap(minimalUser.apis)), props: { resource: gpuVm, loading: false }, mocks: { $config: config }
    })
    expect(deployedConfig.showCpuSpeed).toBe(false)
    const plain = cardWith(deployedConfig)
    expect(plain.text()).toContain('32')
    expect(plain.text()).not.toMatch(/GHz|MHz/)
    const absent = cardWith({ ...deployedConfig, showCpuSpeed: undefined })
    expect(absent.text()).not.toMatch(/GHz|MHz/)
    const speed = cardWith({ ...deployedConfig, showCpuSpeed: true })
    expect(speed.text()).toContain('2.45 GHz')

    const details = compute.children.find(child => child.name === 'vm').details()
    expect(details).not.toContain('cpuspeed')
  })

  it('the per-instance volume snapshot and the extra detail fields are the config\'s to hide for a role type', async () => {
    const router = await vmRouter()
    const vmActions = compute.children.find(child => child.name === 'vm').actions
    const actionApis = (config) => common.createFactory(ActionButton, {
      router,
      i18n,
      store: storeWith(apiMap(minimalUser.apis), 'User', { kvmsnapshotenabled: true }),
      props: { actions: vmActions, resource: gpuVm, dataView: true },
      mocks: { $config: config }
    }).vm.visibleActions.map(action => action.api)

    const shipped = actionApis(deployedConfig)
    expect(shipped).not.toContain('createSnapshot')
    expect(shipped).toEqual(['updateVirtualMachine', 'stopVirtualMachine', 'renewVirtualMachineLease', 'rebootVirtualMachine',
      'restoreVirtualMachine', 'scaleVirtualMachine', 'destroyVirtualMachine'])
    for (const absent of ['createVMSnapshot', 'updateVMAffinityGroup', 'assignVirtualMachineToBackupOffering', 'createBackup', 'attachIso',
      'migrateVirtualMachine', 'resetUserDataForVirtualMachine', 'resetSSHKeyForVirtualMachine', 'expungeVirtualMachine']) {
      expect(shipped).not.toContain(absent)
    }
    expect(actionApis({ ...deployedConfig, hidden: {} })).toContain('createSnapshot')

    const fields = (config) => DetailsTab.methods.fetchDetails.call({
      $route: { meta: { name: 'vm', details: compute.children.find(child => child.name === 'vm').details } },
      $isHidden: (area, key) => (config.hidden?.User?.[area] || []).includes(key),
      detailsTitles: {},
      projectname: null
    })
    expect(fields(deployedConfig)).not.toContain('hypervisor')
    expect(fields(deployedConfig)).toEqual(expect.arrayContaining(['name', 'state', 'serviceofferingname', 'gpucardname', 'gpucount']))
    expect(fields({ hidden: {} })).toContain('hypervisor')
  })

  it('stop has no forced option for users', () => {
    const stop = compute.children.find(child => child.name === 'vm').actions.find(action => action.api === 'stopVirtualMachine')
    expect(stop.args(gpuVm, { userInfo: { roletype: 'User' }, features: { allowuserforcestopvm: false } })).toEqual([])
    expect(stop.args(gpuVm, { userInfo: { roletype: 'User' }, features: {} })).toEqual([])
    // the server setting is the switch
    expect(stop.args(gpuVm, { userInfo: { roletype: 'User' }, features: { allowuserforcestopvm: true } })).toEqual(['forced'])
  })
})

describe('the instance card tells how to reach the instance', () => {
  const debianVm = { ...gpuVm, name: 'My-VM', templateid: 'tpl-1', templatename: 'debian-13-20260914' }
  const storeOf = (username) => common.createMockStore({ user: { apis: apiMap(minimalUser.apis), info: { roletype: 'User', username }, features: {} } })

  const cardFor = async ({ config = deployedConfig, resource = debianVm, username = 'baneling100', route = vmRouter } = {}) =>
    common.createFactory(InfoCard, {
      router: await route(), i18n, store: storeOf(username), props: { resource, loading: false }, mocks: { $config: config }
    })

  const shown = (card) => ({
    ssh: card.find('.instance-ssh-command').exists() ? card.find('.instance-ssh-command').text() : null,
    account: card.find('.instance-login-account').exists() ? card.find('.instance-login-account').text() : null
  })

  it('shows the SSH command with a copy button and the login account of the template family, with the help line', async () => {
    const card = await cardFor()
    const command = 'ssh -p 2222 baneling100:my-vm@cloud.snucse.org'
    expect(shown(card)).toEqual({ ssh: command, account: 'debian' })
    expect(card.text()).toContain('label.ssh.access')
    expect(card.text()).toContain('label.login.account')
    expect(card.find('.instance-login-account-help').text()).toBe('message.login.account')
    expect(card.find('.instance-ssh-command').element.tagName).toBe('CODE')
    const copy = card.findAllComponents({ name: 'TooltipButton' }).find(button => button.props('copyResource') === command)
    expect(copy).toBeDefined()
  })

  it('names the signed-in user, a member by the full username', async () => {
    const member = await cardFor({ username: 'jh.kim@bacchus.snucse.org' })
    expect(shown(member).ssh).toBe('ssh -p 2222 jh.kim@bacchus.snucse.org:my-vm@cloud.snucse.org')
    expect(shown(await cardFor({ username: null })).ssh).toBeNull()
  })

  it('shows neither without instanceAccess in the config, or with it empty as shipped', async () => {
    for (const config of [{ ...deployedConfig, instanceAccess: undefined }, { ...deployedConfig, instanceAccess: {} }, {}]) {
      const card = await cardFor({ config })
      expect(shown(card)).toEqual({ ssh: null, account: null })
      expect(card.text()).not.toContain('label.ssh.access')
      expect(card.text()).not.toContain('label.login.account')
    }
  })

  it('leaves out the login account when the template is not in the map, and keeps the command', async () => {
    const card = await cardFor({ resource: { ...debianVm, templatename: 'windows-2025-20260914' } })
    expect(shown(card)).toEqual({ ssh: 'ssh -p 2222 baneling100:my-vm@cloud.snucse.org', account: null })
    expect(card.text()).not.toContain('label.login.account')
    expect(card.find('.instance-login-account-help').exists()).toBe(false)
  })

  it('is for an instance that exists, on the instance page only', async () => {
    const { id, ...unsaved } = debianVm
    expect(shown(await cardFor({ resource: unsaved }))).toEqual({ ssh: null, account: null })

    const volumeRoute = async () => {
      const router = common.createMockRouter([{ name: 'volume', path: '/volume/:id', meta: { name: 'volume' }, component: {} },
        { path: '/:catchAll(.*)', redirect: '/exception/404' }])
      router.push('/volume/vol-1')
      await router.isReady()
      return router
    }
    expect(shown(await cardFor({ route: volumeRoute }))).toEqual({ ssh: null, account: null })
  })
})
