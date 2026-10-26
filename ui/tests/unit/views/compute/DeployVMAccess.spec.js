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
import InstanceAccessModal from '@/components/page/InstanceAccessModal'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'
import deployment from '../../../mockData/DeploymentConfig.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: {
    features: {},
    apis: {},
    userInfo: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1', username: 'baneling100' },
    project: {},
    zones: []
  }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
// the fork's default config with the deployment's data, which the fixture mirrors
const deployedConfig = { ...shippedConfig, ...deployment }
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))

const zone = { id: 'z-1', name: 'zone', networktype: 'Advanced', securitygroupsenabled: true, allocationstate: 'Enabled' }
const userInfo = { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1', username: 'baneling100' }

const answers = (command) => {
  switch (command) {
    case 'listZones':
      return { listzonesresponse: { count: 1, zone: [zone] } }
    case 'listNetworks':
      return { listnetworksresponse: { count: 0, network: [] } }
    case 'listCapabilities':
      return { listcapabilitiesresponse: { capability: { rootdisksizemin: 10, rootdisksizedefault: 20, poolvcpustep: 2 } } }
    case 'listResourceLimits':
      return { listresourcelimitsresponse: { resourcelimit: [] } }
    case 'listAccounts':
      return { listaccountsresponse: { count: 1, account: [{ id: 'acc-1', name: 'student', taggedresources: [] }] } }
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

const mountWizard = async (config = deployedConfig) => {
  const apis = apiMap(minimalUser.apis)
  global.allowedApis = apis
  const store = common.createMockStore({
    app: { device: 'desktop' },
    user: { apis, info: userInfo, features: {}, zones: [zone] }
  })
  const router = common.createMockRouter([{ name: 'deployVirtualMachine', path: '/action/deployVirtualMachine', meta: { name: 'vm' }, component: {} }])
  router.push('/action/deployVirtualMachine')
  await router.isReady()
  const $pollJob = jest.fn()
  const $notifyError = jest.fn()
  const $notification = { warning: jest.fn(), error: jest.fn(), success: jest.fn() }
  const wrapper = common.createFactory(DeployVM, {
    router,
    store,
    i18n: common.createMockI18n('en', { en }),
    mocks: { $config: config, $notifyError, $notification, $fetchCpuArchitectureTypes: () => [], $pollJob },
    stubs: Object.fromEntries(stubbed.map(name => [name, true]))
  })
  await flushPromises()
  wrapper.$pollJob = $pollJob
  wrapper.$notification = $notification
  mounted.push(wrapper)
  return wrapper
}

const mountDialog = () => {
  const wrapper = common.createFactory(InstanceAccessModal, { i18n: common.createMockI18n('en', { en }) })
  mounted.push(wrapper)
  return wrapper
}

const dialog = () => document.querySelector('.instance-access')
const text = () => (dialog() ? dialog().textContent : '')

const deploy = async (wrapper, values = { name: 'my-vm', stayonpage: true }) => {
  wrapper.vm.postDeploy({}, values)
  await flushPromises()
  expect(postAPI).toHaveBeenCalledTimes(1)
  return (virtualmachine) => {
    wrapper.$pollJob.mock.calls[0][0].successMethod({ jobresult: { virtualmachine } })
    return flushPromises()
  }
}

const instance = { id: 'vm-1', name: 'My-VM', displayname: 'My-VM', templatename: 'debian-13-20260914', password: 'Xy7kq2Lp' }

jest.setTimeout(60000)

describe('deploy wizard: how to reach the new instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command) => command in (global.allowedApis || {})
      ? Promise.resolve(answers(command))
      : Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } })))
    postAPI.mockImplementation(() => Promise.resolve({ deployvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
    global.allowedApis = undefined
    document.body.innerHTML = ''
  })

  it('opens a dialog with the command, the login account and the password, and no notification', async () => {
    mountDialog()
    const wrapper = await mountWizard()
    const succeed = await deploy(wrapper)
    await succeed(instance)

    expect(dialog()).not.toBeNull()
    expect(text()).toContain('ssh -p 2222 baneling100:my-vm@cloud.snucse.org')
    expect(document.querySelector('.instance-access-account').textContent).toBe('debian')
    expect(text()).toContain(en['message.login.account'])
    expect(document.querySelector('.instance-access-password').textContent).toBe('Xy7kq2Lp')
    expect(text()).toContain(en['message.instance.password'])
    expect(wrapper.$notification.success).not.toHaveBeenCalled()
  })

  it('shows no password block when the deployment returns no password', async () => {
    mountDialog()
    const wrapper = await mountWizard()
    const succeed = await deploy(wrapper)
    const { password, ...withoutPassword } = instance
    await succeed(withoutPassword)

    expect(text()).toContain('ssh -p 2222 baneling100:my-vm@cloud.snucse.org')
    expect(document.querySelector('.instance-access-account').textContent).toBe('debian')
    expect(document.querySelector('.instance-access-password')).toBeNull()
    expect(text()).not.toContain(en['message.instance.password'])
  })

  it('shows only the password when the config names no host, and nothing when there is no password either', async () => {
    mountDialog()
    const wrapper = await mountWizard({ ...deployedConfig, instanceAccess: {} })
    const succeed = await deploy(wrapper)
    const { password, ...withoutPassword } = instance
    await succeed(withoutPassword)
    expect(dialog()).toBeNull()

    await succeed(instance)
    expect(document.querySelector('.instance-access-password').textContent).toBe('Xy7kq2Lp')
    expect(document.querySelector('.instance-access-command')).toBeNull()
    expect(document.querySelector('.instance-access-account')).toBeNull()
  })

  it('shows no account for a template outside the map, and still the command', async () => {
    mountDialog()
    const wrapper = await mountWizard()
    const succeed = await deploy(wrapper)
    await succeed({ ...instance, templatename: 'windows-2025-20260914' })
    expect(text()).toContain('ssh -p 2222 baneling100:my-vm@cloud.snucse.org')
    expect(document.querySelector('.instance-access-account')).toBeNull()
  })

  it('reaches the owner who has left the wizard before the instance was ready', async () => {
    mountDialog()
    const wrapper = await mountWizard()
    const back = jest.spyOn(wrapper.vm.$router, 'back').mockImplementation(() => {})
    const succeed = await deploy(wrapper, { name: 'my-vm', stayonpage: false })
    expect(back).toHaveBeenCalled()
    wrapper.unmount()
    mounted.splice(mounted.indexOf(wrapper), 1)
    await succeed(instance)
    expect(text()).toContain('Xy7kq2Lp')
    expect(text()).toContain('ssh -p 2222 baneling100:my-vm@cloud.snucse.org')
  })

  it('tells the command by the sshname of an instance that has one, whatever its hostname', async () => {
    mountDialog()
    const wrapper = await mountWizard()
    const succeed = await deploy(wrapper)
    await succeed({ ...instance, name: 'i-2-34-VM', displayname: 'VM-0', sshname: 'VM-0' })
    expect(text()).toContain('ssh -p 2222 baneling100:vm-0@cloud.snucse.org')
    expect(text()).not.toContain('i-2-34-vm')
  })
})

describe('deploy wizard: the name of the new instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command) => command in (global.allowedApis || {})
      ? Promise.resolve(answers(command))
      : Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } })))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
    global.allowedApis = undefined
    document.body.innerHTML = ''
  })

  it('is optional, and when given follows the visible-name rule, which the message of the field states', async () => {
    const wrapper = await mountWizard()
    const [rule] = wrapper.vm.rules.name
    for (const fine of [undefined, null, '', 'a', 'VM-0', 'Lab-1', 'x'.repeat(30)]) {
      await expect(rule.validator({}, fine)).resolves.toBeUndefined()
    }
    for (const bad of ['my vm', '-a', 'a-', 'x'.repeat(31), 'a_b', 'a.b', '인스턴스']) {
      await expect(rule.validator({}, bad)).rejects.toBe(en['message.error.instance.name.rule'])
    }
  })

  it('says under the field that an empty name gets VM-0, VM-1 and so on', async () => {
    const wrapper = await mountWizard()
    await wrapper.setData({ zoneSelected: true })
    await flushPromises()
    expect(wrapper.find('.ant-form-item-extra').exists()).toBe(true)
    expect(wrapper.text()).toContain(en['message.instance.name.default'])
  })

  describe('what the submit posts', () => {
    const submit = async (wrapper, form = {}) => {
      await wrapper.setData({ zoneSelected: true })
      Object.assign(wrapper.vm.form, { zoneid: 'z-1', hypervisor: 'KVM', templateid: 't-1', computeofferingid: 'so-1', startvm: true, stayonpage: true, ...form })
      wrapper.vm.handleSubmit()
      await flushPromises()
      await flushPromises()
    }
    const posted = () => postAPI.mock.calls.filter(call => call[0] === 'deployVirtualMachine').map(call => call[1])

    beforeEach(() => {
      postAPI.mockImplementation(() => Promise.resolve({ deployvirtualmachineresponse: { jobid: 'job-1' } }))
    })

    it('is the visible name as displayname, and never as name', async () => {
      const wrapper = await mountWizard()
      await submit(wrapper, { name: 'my-vm' })
      expect(posted()).toHaveLength(1)
      expect(posted()[0]).toMatchObject({ zoneid: 'z-1', templateid: 't-1', serviceofferingid: 'so-1', displayname: 'my-vm' })
      expect(posted()[0]).not.toHaveProperty('name')
    })

    it('is no name of either kind when none was given, so that the server picks one', async () => {
      const wrapper = await mountWizard()
      await submit(wrapper, { name: '' })
      expect(posted()).toHaveLength(1)
      expect(posted()[0]).not.toHaveProperty('name')
      expect(posted()[0]).not.toHaveProperty('displayname')
    })

    it('is nothing when the name breaks the rule: the form says so and no request leaves', async () => {
      const wrapper = await mountWizard()
      await submit(wrapper, { name: 'my vm' })
      expect(posted()).toHaveLength(0)
      expect(wrapper.find('.ant-form-item-explain-error').text()).toBe(en['message.error.instance.name.rule'])
    })
  })
})
