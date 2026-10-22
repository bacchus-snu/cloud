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
import ReinstallVm from '@/views/compute/ReinstallVm'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const GiB = 1024 * 1024 * 1024

const vm = { id: 'vm-1', name: 'lab', zoneid: 'z-1', account: 'student', domainid: 'd-1', templateid: 't-current' }

let state

const answer = (command, params = {}) => {
  switch (command) {
    case 'listCapabilities':
      return { listcapabilitiesresponse: { capability: state.capabilities } }
    case 'listResourceLimits':
      return { listresourcelimitsresponse: { resourcelimit: [] } }
    case 'listAccounts':
      return { listaccountsresponse: { account: [{ id: 'acc-1', remainingstoragequota: state.remaining, taggedresources: [] }] } }
    case 'listTemplates':
      return params.id === 't-current'
        ? { listtemplatesresponse: { count: 1, template: [{ id: 't-current', size: state.currentGiB * GiB }] } }
        : { listtemplatesresponse: { count: 0 } }
    case 'listDiskOfferings':
      return { listdiskofferingsresponse: { count: 0 } }
    case 'listOsCategories':
      return { listoscategoriesresponse: { oscategory: [{ id: 'c-1', name: 'Linux' }] } }
    default:
      return {}
  }
}

const open = async (without = []) => {
  const apis = Object.fromEntries(minimalUser.apis.filter(name => !without.includes(name)).map(name => [name, {
    params: name === 'restoreVirtualMachine' ? ['diskofferingid', 'rootdisksize', 'expunge'].map(param => ({ name: param, description: param })) : []
  }]))
  global.allowedApis = apis
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }, features: {} } })
  const wrapper = common.createFactory(ReinstallVm, {
    store,
    i18n: common.createMockI18n('en', { en }),
    props: { resource: vm },
    provide: { parentFetchData: jest.fn() },
    mocks: { $config: {}, $pollJob: jest.fn(), $notifyError: jest.fn() },
    stubs: { OsBasedImageSelection: true, TemplateIsoSelection: true, DiskOfferingSelection: true, DiskSizeSelection: true }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const sizeInput = (wrapper) => wrapper.findAllComponents({ name: 'DiskSizeSelection' }).find(input => input.props('inputDecorator') === 'rootdisksize')

describe('ReinstallVm root disk size', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    state = { capabilities: { rootdisksizemin: 10, rootdisksizedefault: 20 }, remaining: 77, currentGiB: 5 }
    getAPI.mockImplementation((command, params) => command in global.allowedApis
      ? Promise.resolve(answer(command, params))
      : Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } })))
    postAPI.mockImplementation(() => Promise.resolve({ restorevmresponse: { jobid: 'job-1' } }))
  })

  it('bounds the new root size as the OS-disk step does: the floor and default from listCapabilities, the remaining storage quota above', async () => {
    const wrapper = await open()
    await wrapper.setData({ overrideDiskSize: true })
    await flushPromises()
    const input = sizeInput(wrapper)
    expect(input.props('minDiskSize')).toBe(10)
    expect(input.props('initialDiskSize')).toBe(20)
    expect(input.props('maxDiskSize')).toBe(77)
  })

  it('labels the switch with what it does: it changes the size of the OS disk', async () => {
    const wrapper = await open()
    expect(wrapper.text()).toContain(en['label.os.disk.size.change'])
    expect(en['label.os.disk.size.change']).not.toBe(en['label.os.disk'])
  })

  it('raises the floor to the size of the template it is reinstalled from, the chosen one first', async () => {
    state.currentGiB = 31
    const current = await open()
    await current.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(sizeInput(current).props('minDiskSize')).toBe(31)
    expect(sizeInput(current).props('initialDiskSize')).toBe(31)

    state.currentGiB = 5
    const wrapper = await open()
    wrapper.vm.options.templates = { executable: { count: 1, template: [{ id: 't-12', size: 12.5 * GiB }] } }
    wrapper.vm.updateFieldValue('templateid', 't-12')
    await wrapper.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(sizeInput(wrapper).props('minDiskSize')).toBe(13)
    expect(sizeInput(wrapper).props('initialDiskSize')).toBe(20)
  })

  it('leaves the size unbounded above when the quota is unlimited, and sends the size chosen', async () => {
    state.remaining = -1
    const wrapper = await open()
    await wrapper.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(sizeInput(wrapper).props('maxDiskSize')).toBeNull()

    wrapper.vm.updateFieldValue('overrideRootDiskSize', 40)
    wrapper.vm.handleSubmit()
    await flushPromises()
    expect(postAPI.mock.calls[0][0]).toBe('restoreVirtualMachine')
    expect(postAPI.mock.calls[0][1]).toMatchObject({ virtualmachineid: 'vm-1', rootdisksize: 40 })
  })

  it('calls only commands the student role keeps', async () => {
    await open()
    expect(getAPI.mock.calls.map(call => call[0]).filter(command => !minimalUser.apis.includes(command))).toEqual([])
    expect(getAPI.mock.calls.map(call => call[0])).toEqual(expect.arrayContaining(['listCapabilities', 'listAccounts', 'listResourceLimits']))
  })

  it('does not call the quota lists when the role lacks them, and then bounds nothing above', async () => {
    const wrapper = await open(['listAccounts', 'listResourceLimits'])
    await wrapper.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listAccounts')
    expect(sizeInput(wrapper).props('maxDiskSize')).toBeNull()
    expect(sizeInput(wrapper).props('minDiskSize')).toBe(10)
  })
})
