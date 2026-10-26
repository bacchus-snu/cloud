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
import { config, flushPromises } from '@vue/test-utils'

import common from '../../../common'
import { getAPI, postAPI } from '@/api'
import DeployVnfAppliance from '@/views/compute/DeployVnfAppliance'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

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
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))

const zone = { id: 'z-1', name: 'zone', networktype: 'Advanced', securitygroupsenabled: true, allocationstate: 'Enabled' }
const userInfo = { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1', username: 'baneling100' }

const answers = (command) => {
  switch (command) {
    case 'listZones':
      return { listzonesresponse: { count: 1, zone: [zone] } }
    case 'listInstanceGroups':
      return { listinstancegroupsresponse: { instancegroup: [] } }
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

const stubbed = ['InfoCard', 'DeployButtons', 'ResourceIcon', 'ZoneBlockRadioGroupSelect', 'BlockRadioGroupSelect', 'SshKeyPairSelection',
  'UserDataSelection', 'NetworkConfiguration', 'NetworkSelection', 'AffinityGroupSelection', 'TemplateIsoSelection', 'OsBasedImageSelection',
  'DiskSizeSelection', 'MultiDiskSelection', 'DiskOfferingSelection', 'ComputeOfferingSelection', 'ComputeSelection', 'SecurityGroupSelection',
  'VnfNicsSelection', 'InstanceNicsNetworkSelectListView']

const mounted = []
const renderErrors = []
const failures = []
config.global.config.errorHandler = (error) => { renderErrors.push(error) }

const mountWizard = async () => {
  const apis = apiMap(minimalUser.apis)
  global.allowedApis = apis
  const router = common.createMockRouter([{ name: 'wizard', path: '/wizard', meta: { name: 'vm' }, component: {} }])
  router.push('/wizard')
  await router.isReady()
  const wrapper = common.createFactory(DeployVnfAppliance, {
    router,
    store: common.createMockStore({ app: { device: 'desktop' }, user: { apis, info: userInfo, features: {}, zones: [zone] } }),
    i18n: common.createMockI18n('en', { en }),
    props: { preFillContent: {} },
    mocks: { $config: {}, $notifyError: jest.fn(), $notification: { warning: jest.fn(), error: jest.fn(), success: jest.fn() }, $fetchCpuArchitectureTypes: () => [], $pollJob: jest.fn() },
    stubs: Object.fromEntries(stubbed.map(name => [name, true]))
  })
  await flushPromises()
  mounted.push(wrapper)
  return wrapper
}

jest.setTimeout(60000)

describe('deploying a network appliance: its name', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    getAPI.mockImplementation((command) => Promise.resolve(answers(command)))
    postAPI.mockImplementation(() => Promise.resolve({ deployvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    expect(renderErrors.splice(0).map(error => String(error && error.stack || error).slice(0, 600))).toEqual([])
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
    global.allowedApis = undefined
  })

  const prepare = async (wrapper, form = {}) => {
    await wrapper.setData({ zoneSelected: true })
    await flushPromises()
    Object.assign(wrapper.vm.form, { zoneid: 'z-1', hypervisor: 'KVM', templateid: 't-1', computeofferingid: 'so-1', startvm: true, ...form })
    // the wizard looks the chosen offering and template up when the form changes; what it found is replaced here
    await flushPromises()
    Object.assign(wrapper.vm, { zone: { networktype: 'Basic' }, serviceOffering: {}, template: {} })
  }
  const submit = async (wrapper, form = {}) => {
    await prepare(wrapper, form)
    const formRef = wrapper.vm.formRef.value
    jest.spyOn(formRef, 'scrollToField').mockImplementation(() => {})
    // an error inside the submit handler is recorded, for the handler's own catch assumes a validation error
    const validate = formRef.validate.bind(formRef)
    formRef.validate = () => ({
      then: (proceed) => validate().then(async (...args) => {
        try {
          return await proceed(...args)
        } catch (error) {
          failures.push(error)
        }
      })
    })
    wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
    for (let i = 0; i < 10; i++) {
      await flushPromises()
    }
    expect(failures.splice(0).map(error => String(error && error.stack || error).slice(0, 600))).toEqual([])
  }
  const posted = () => postAPI.mock.calls.filter(call => call[0] === 'deployVnfAppliance').map(call => call[1])

  it('says under the field that an empty name gets VM-0, VM-1 and so on', async () => {
    const wrapper = await mountWizard()
    await wrapper.setData({ zoneSelected: true })
    await flushPromises()
    expect(wrapper.text()).toContain(en['message.instance.name.default'])
  })

  it('is posted as displayname and never as name', async () => {
    const wrapper = await mountWizard()
    await submit(wrapper, { name: 'my-vm' })
    expect(posted()).toHaveLength(1)
    expect(posted()[0]).toMatchObject({ zoneid: 'z-1', templateid: 't-1', displayname: 'my-vm' })
    expect(posted()[0]).not.toHaveProperty('name')
  })

  it('is no name of either kind when none was given', async () => {
    const wrapper = await mountWizard()
    await submit(wrapper, { name: '' })
    expect(posted()).toHaveLength(1)
    expect(posted()[0]).not.toHaveProperty('name')
    expect(posted()[0]).not.toHaveProperty('displayname')
  })

  it('is nothing when the name breaks the rule: the form says so and no request leaves', async () => {
    const wrapper = await mountWizard()
    await prepare(wrapper, { name: 'my vm' })
    const refusal = await wrapper.vm.formRef.value.validate().then(() => null, error => error)
    expect(refusal).not.toBeNull()
    expect(refusal.errorFields.filter(field => field.name.join('.') === 'name').map(field => field.errors)).toEqual([[en['message.error.instance.name.rule']]])
    await submit(wrapper, { name: 'my vm' })
    expect(posted()).toHaveLength(0)
  })
})
