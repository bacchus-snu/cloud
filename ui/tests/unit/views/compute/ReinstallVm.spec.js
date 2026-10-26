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
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))
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
    case 'listVolumes':
      return state.rootGiB === undefined
        ? { listvolumesresponse: {} }
        : { listvolumesresponse: { count: 1, volume: [{ id: 'vol-root', type: 'ROOT', virtualmachineid: params.virtualmachineid, size: state.rootGiB * GiB }] } }
    case 'listOsCategories':
      return { listoscategoriesresponse: { oscategory: [{ id: 'c-1', name: 'Linux' }] } }
    default:
      return {}
  }
}

const open = async (without = [], { features = {}, locale = 'en' } = {}) => {
  const apis = Object.fromEntries(minimalUser.apis.filter(name => !without.includes(name)).map(name => [name, {
    params: name === 'restoreVirtualMachine' ? ['diskofferingid', 'rootdisksize', 'expunge'].map(param => ({ name: param, description: param })) : []
  }]))
  global.allowedApis = apis
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student', accountid: 'acc-1' }, features } })
  const wrapper = common.createFactory(ReinstallVm, {
    store,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
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
    state = { capabilities: { rootdisksizemin: 10, rootdisksizedefault: 20 }, remaining: 77, currentGiB: 5, rootGiB: 50 }
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
    expect(input.props('maxDiskSize')).toBe(77 + 50)
  })

  it('lets the new root take the quota that is left and the root it replaces, as the server counts the change', async () => {
    state.remaining = 0
    state.rootGiB = 50
    const wrapper = await open()
    await wrapper.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(getAPI).toHaveBeenCalledWith('listVolumes', expect.objectContaining({ virtualmachineid: 'vm-1', type: 'ROOT' }))
    expect(sizeInput(wrapper).props('maxDiskSize')).toBe(50)

    state.remaining = 10
    state.rootGiB = 20
    const other = await open()
    await other.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(sizeInput(other).props('maxDiskSize')).toBe(30)
  })

  it('leaves the size unbounded above while the root it replaces is unknown', async () => {
    state.rootGiB = undefined
    const unreadable = await open()
    await unreadable.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(sizeInput(unreadable).props('maxDiskSize')).toBeNull()

    state.rootGiB = 50
    getAPI.mockClear()
    const denied = await open(['listVolumes'])
    await denied.setData({ overrideDiskSize: true })
    await flushPromises()
    expect(getAPI.mock.calls.map(call => call[0])).not.toContain('listVolumes')
    expect(sizeInput(denied).props('maxDiskSize')).toBeNull()
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
    expect(getAPI.mock.calls.map(call => call[0])).toEqual(expect.arrayContaining(['listCapabilities', 'listAccounts', 'listResourceLimits', 'listVolumes']))
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

describe('ReinstallVm when the cloud deletes a volume at once', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    state = { capabilities: { rootdisksizemin: 10, rootdisksizedefault: 20 }, remaining: 77, currentGiB: 5, rootGiB: 50 }
    getAPI.mockImplementation((command, params) => command in global.allowedApis
      ? Promise.resolve(answer(command, params))
      : Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } })))
  })

  const warning = (wrapper) => wrapper.find('.ant-alert').text()

  it('says that the current root disk is replaced and deleted at once and cannot be recovered', async () => {
    state.capabilities = { ...state.capabilities, expungeondestroy: true }
    const wrapper = await open()
    expect(warning(wrapper)).toContain(en['message.reinstall.vm.now'])
    expect(en['message.reinstall.vm.now']).toMatch(/deleted at once/)
    expect(en['message.reinstall.vm.now']).toMatch(/cannot be recovered/)
    expect(warning(wrapper)).toContain(en['message.reinstall.vm'])
  })

  it('says nothing more when the server keeps replaced disks or does not say', async () => {
    for (const capabilities of [{}, { expungeondestroy: false }]) {
      state.capabilities = { rootdisksizemin: 10, ...capabilities }
      const wrapper = await open()
      expect(warning(wrapper)).toContain(en['message.reinstall.vm'])
      expect(warning(wrapper)).not.toContain(en['message.reinstall.vm.now'])
    }
  })

  it('goes by what the server says, and by the session when the server cannot be asked', async () => {
    state.capabilities = { expungeondestroy: false }
    const stale = await open([], { features: { expungeondestroy: true } })
    expect(warning(stale)).not.toContain(en['message.reinstall.vm.now'])

    getAPI.mockImplementation((command, params) => command === 'listCapabilities'
      ? Promise.reject(new Error('down'))
      : Promise.resolve(answer(command, params)))
    const fallback = await open([], { features: { expungeondestroy: true } })
    expect(warning(fallback)).toContain(en['message.reinstall.vm.now'])
  })

  const expungeSwitch = (wrapper) => wrapper.findAll('.ant-form-item').find(item => item.text().includes(en['label.expunge']))
  const reinstall = async (wrapper) => {
    postAPI.mockImplementation(() => Promise.resolve({ restorevmresponse: { jobid: 'job-1' } }))
    wrapper.vm.handleSubmit()
    await flushPromises()
    return postAPI.mock.calls[0][1]
  }

  it('shows no expunge switch and sends no expunge parameter, since the replaced disk goes at once', async () => {
    state.capabilities = { ...state.capabilities, expungeondestroy: true }
    const wrapper = await open()
    expect(expungeSwitch(wrapper)).toBeUndefined()
    const params = await reinstall(wrapper)
    expect(params).toMatchObject({ virtualmachineid: 'vm-1' })
    expect(params).not.toHaveProperty('expunge')
  })

  it('keeps the expunge switch and its parameter while the server keeps replaced disks or does not say', async () => {
    for (const capabilities of [{}, { expungeondestroy: false }]) {
      jest.clearAllMocks()
      state.capabilities = { rootdisksizemin: 10, ...capabilities }
      const wrapper = await open()
      expect(expungeSwitch(wrapper)).toBeDefined()
      await wrapper.setData({ expungeDisk: true })
      const params = await reinstall(wrapper)
      expect(params.expunge).toBe(true)
    }
  })

  it('goes by the session for the switch when the server cannot be asked', async () => {
    getAPI.mockImplementation((command, params) => command === 'listCapabilities'
      ? Promise.reject(new Error('down'))
      : Promise.resolve(answer(command, params)))
    const wrapper = await open([], { features: { expungeondestroy: true } })
    expect(expungeSwitch(wrapper)).toBeUndefined()
  })

  it('is worded in Korean', async () => {
    state.capabilities = { expungeondestroy: true }
    const wrapper = await open([], { locale: 'ko_KR' })
    expect(ko['message.reinstall.vm.now']).toBeTruthy()
    expect(ko['message.reinstall.vm.now']).not.toBe(en['message.reinstall.vm.now'])
    expect(warning(wrapper)).toContain(ko['message.reinstall.vm.now'])
  })
})
