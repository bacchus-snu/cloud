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
import DestroyVM from '@/views/compute/DestroyVM'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const vm = { id: 'vm-1', name: 'i-2-34-VM', displayname: 'lab' }
const bulk = {
  resource: {},
  selectedRowKeys: ['vm-1', 'vm-2'],
  selectedItems: [{ id: 'vm-1', name: 'a' }, { id: 'vm-2', name: 'b' }],
  action: { currentAction: { label: 'label.action.destroy.instance', message: 'message.action.destroy.instance' } }
}

let capability
let features
let pollOptions

const open = async ({ props = {}, locale = 'en', roletype = 'User', apiNames = [], route = '/vm' } = {}) => {
  const apis = {
    destroyVirtualMachine: { params: [{ name: 'expunge', description: '' }, { name: 'volumeids', description: '' }] },
    listCapabilities: { params: [] },
    listVolumes: { params: [] },
    ...Object.fromEntries(apiNames.map(name => [name, { params: [] }]))
  }
  const store = common.createMockStore({ user: { apis, info: { roletype }, features } })
  const router = common.createMockRouter([{ path: '/vm', component: {} }, { path: '/vm/:id', component: {} }])
  router.push(route)
  await router.isReady()
  const $pollJob = jest.fn((options) => { pollOptions = options })
  const wrapper = common.createFactory(DestroyVM, {
    store,
    router,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { resource: vm, action: {}, ...props },
    provide: { parentFetchData: jest.fn() },
    mocks: { $pollJob, $notifyError: jest.fn(), $message: { info: jest.fn() } },
    stubs: { BulkActionProgress: true }
  })
  wrapper.$pollJob = $pollJob
  await flushPromises()
  await flushPromises()
  return wrapper
}

const destroyed = () => postAPI.mock.calls.filter(call => call[0] === 'destroyVirtualMachine').map(call => call[1])
const field = (wrapper) => wrapper.find('.confirm-input input')
const ok = (wrapper) => wrapper.find('.action-button .ant-btn-primary')
const isDisabled = (button) => button.attributes('disabled') !== undefined
const submit = async (wrapper) => {
  await wrapper.vm.handleSubmit({ preventDefault () {} })
  await flushPromises()
}

describe('DestroyVM asks for the name of the Instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    capability = { keeprootvolumedefault: true }
    features = {}
    pollOptions = null
    getAPI.mockImplementation((command) => {
      if (command === 'listCapabilities') {
        return Promise.resolve({ listcapabilitiesresponse: { capability } })
      }
      return Promise.resolve({ listvolumesresponse: { volume: [] } })
    })
    postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  it('shows the visible name to type and keeps the button disabled until it is typed', async () => {
    const wrapper = await open()
    expect(field(wrapper).exists()).toBe(true)
    expect(wrapper.find('.confirm-expected').text()).toBe('lab')
    expect(isDisabled(ok(wrapper))).toBe(true)

    for (const wrong of ['l', 'la', 'Lab', 'LAB', 'lab2', 'i-2-34-VM', 'l ab']) {
      await field(wrapper).setValue(wrong)
      expect(isDisabled(ok(wrapper))).toBe(true)
    }
    await field(wrapper).setValue('lab')
    expect(isDisabled(ok(wrapper))).toBe(false)

    await field(wrapper).setValue('labs')
    expect(isDisabled(ok(wrapper))).toBe(true)
  })

  it('takes the name when there is no visible name, and accepts the whitespace around what was pasted', async () => {
    const wrapper = await open({ props: { resource: { id: 'vm-1', name: 'lab' } } })
    expect(wrapper.find('.confirm-expected').text()).toBe('lab')
    await field(wrapper).setValue('  lab\n')
    expect(isDisabled(ok(wrapper))).toBe(false)
  })

  it('takes the name when it is pasted, with the whitespace the clipboard may carry', async () => {
    const wrapper = await open()
    const input = field(wrapper).element
    const paste = new Event('paste', { bubbles: true, cancelable: true })
    input.dispatchEvent(paste)
    expect(paste.defaultPrevented).toBe(false)
    input.value = ' lab\n'
    input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste' }))
    await flushPromises()
    expect(isDisabled(ok(wrapper))).toBe(false)
    await ok(wrapper).trigger('click')
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1', keeprootvolume: true }])
  })

  it('destroys nothing on Enter in the input until the name matches', async () => {
    const wrapper = await open()
    await field(wrapper).setValue('la')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()

    await field(wrapper).setValue('lab')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1', keeprootvolume: true }])
  })

  it('destroys nothing before the name matches, also by the keyboard shortcut', async () => {
    const wrapper = await open()
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()
    await field(wrapper).setValue('Lab')
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()

    await field(wrapper).setValue('lab')
    await ok(wrapper).trigger('click')
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1', keeprootvolume: true }])
  })

  it('speaks the user\'s language', async () => {
    const wrapper = await open({ locale: 'ko_KR' })
    expect(wrapper.find('.confirm-prompt').text()).toBe(ko['label.delete.confirmation'])
    expect(ko['label.delete.confirmation']).toBeTruthy()
  })
})

describe('DestroyVM asks for the number of the selected Instances', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    capability = { keeprootvolumedefault: true }
    features = {}
    getAPI.mockImplementation((command) => {
      if (command === 'listCapabilities') {
        return Promise.resolve({ listcapabilitiesresponse: { capability } })
      }
      return Promise.resolve({ listvolumesresponse: { volume: [] } })
    })
    postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  it('keeps the button disabled until the count is typed', async () => {
    const wrapper = await open({ props: bulk })
    expect(wrapper.find('.confirm-prompt').text()).toBe(en['message.confirm.type.count'].replace('{count}', '2'))
    expect(isDisabled(ok(wrapper))).toBe(true)
    for (const wrong of ['1', '3', '22', '02', 'two', 'a']) {
      await field(wrapper).setValue(wrong)
      expect(isDisabled(ok(wrapper))).toBe(true)
    }
    await field(wrapper).setValue(' 2 ')
    expect(isDisabled(ok(wrapper))).toBe(false)
  })

  it('destroys none of them before the count matches, and all of them after', async () => {
    const wrapper = await open({ props: bulk })
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()
    await field(wrapper).setValue('3')
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()

    await field(wrapper).setValue('2')
    await submit(wrapper)
    expect(destroyed().map(params => params.id)).toEqual(['vm-1', 'vm-2'])
  })
})

describe('DestroyVM when the cloud deletes an Instance at once', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    capability = { keeprootvolumedefault: true, expungeondestroy: true }
    features = {}
    pollOptions = null
    getAPI.mockImplementation((command) => {
      if (command === 'listCapabilities') {
        return Promise.resolve({ listcapabilitiesresponse: { capability } })
      }
      return Promise.resolve({ listvolumesresponse: { volume: [] } })
    })
    postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  const message = (wrapper) => wrapper.find('.ant-alert').text()
  const keepRootBox = (wrapper) => wrapper.find('.keep-root-volume input[type="checkbox"]')

  it('says that the Instance is deleted at once and cannot be recovered', async () => {
    const wrapper = await open()
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance'])
  })

  it('adds that the root disk is deleted with it while "keep root disk" is unchecked', async () => {
    capability = { keeprootvolumedefault: false, expungeondestroy: true }
    const wrapper = await open()
    expect(keepRootBox(wrapper).element.checked).toBe(false)
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now.root'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance.now.keeproot'])
  })

  it('adds that the root disk stays as a volume while "keep root disk" is checked, and follows the checkbox', async () => {
    const wrapper = await open()
    expect(keepRootBox(wrapper).element.checked).toBe(true)
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now.keeproot'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance.now.root'])

    await keepRootBox(wrapper).setValue(false)
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now.root'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance.now.keeproot'])
  })

  it('says nothing of the root disk while the default of the operator is not known', async () => {
    getAPI.mockImplementation((command) => command === 'listCapabilities'
      ? Promise.reject(new Error('no'))
      : Promise.resolve({ listvolumesresponse: { volume: [] } }))
    features = { expungeondestroy: true }
    const wrapper = await open()
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance.now.root'])
    expect(message(wrapper)).not.toContain(en['message.action.destroy.instance.now.keeproot'])
  })

  it('takes the capability from the session too', async () => {
    capability = { keeprootvolumedefault: true }
    features = { expungeondestroy: true }
    const wrapper = await open()
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now'])
  })

  it('does not show the stock expunge switch to the roles that would see it, one Instance or many', async () => {
    const single = await open({ roletype: 'Admin', apiNames: ['expungeVirtualMachine'] })
    expect(single.find('.ant-switch').exists()).toBe(false)
    const many = await open({ roletype: 'Admin', apiNames: ['expungeVirtualMachine'], props: bulk })
    expect(many.find('.ant-switch').exists()).toBe(false)
  })

  it('says the same of many Instances', async () => {
    const wrapper = await open({ props: bulk })
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now'])
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now.keeproot'])
  })

  it('says that the destroy is refused while the Instance has backups, and does not say that they are kept', async () => {
    const wrapper = await open({ props: { resource: { ...vm, backupofferingid: 'b-1' } } })
    expect(message(wrapper)).toContain(en['message.action.destroy.instance.now.backups'])
    expect(en['message.action.destroy.instance.now.backups']).toMatch(/backup offering/)
    expect(en['message.action.destroy.instance.now.backups']).toMatch(/cannot be destroyed/)
    expect(en['message.action.destroy.instance.now.backups']).not.toMatch(/not deleted|kept|stay/i)
    expect(ko['message.action.destroy.instance.now.backups']).toBeTruthy()
    expect(ko['message.action.destroy.instance.now.backups']).not.toContain('삭제되지 않')
    const without = await open()
    expect(message(without)).not.toContain(en['message.action.destroy.instance.now.backups'])
  })

  it('leaves the page of the Instance, which no longer exists, when the job has finished', async () => {
    const wrapper = await open({ route: '/vm/vm-1' })
    const back = jest.spyOn(wrapper.vm.$router, 'go').mockImplementation(() => {})
    await field(wrapper).setValue('lab')
    await submit(wrapper)
    expect(pollOptions).not.toBeNull()
    pollOptions.successMethod()
    expect(back).toHaveBeenCalledWith(-1)
  })

  it('is worded in Korean', async () => {
    const wrapper = await open({ locale: 'ko_KR' })
    expect(message(wrapper)).toContain(ko['message.action.destroy.instance.now'])
    expect(message(wrapper)).toContain(ko['message.action.destroy.instance.now.keeproot'])
    for (const key of ['message.action.destroy.instance.now', 'message.action.destroy.instance.now.root',
      'message.action.destroy.instance.now.keeproot', 'message.action.destroy.instance.now.backups']) {
      expect(ko[key]).toBeTruthy()
    }
  })
})

describe('DestroyVM when the cloud keeps a destroyed Instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    capability = { keeprootvolumedefault: true, expungeondestroy: false }
    features = {}
    getAPI.mockImplementation((command) => {
      if (command === 'listCapabilities') {
        return Promise.resolve({ listcapabilitiesresponse: { capability } })
      }
      return Promise.resolve({ listvolumesresponse: { volume: [] } })
    })
    postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  it('keeps the stock wording and the expunge switch, and still asks for the name', async () => {
    const wrapper = await open({ roletype: 'Admin', apiNames: ['expungeVirtualMachine'] })
    expect(wrapper.find('.ant-alert').text()).toContain(en['message.action.destroy.instance'])
    expect(wrapper.find('.ant-alert').text()).not.toContain(en['message.action.destroy.instance.now'])
    expect(wrapper.find('.ant-switch').exists()).toBe(true)
    expect(isDisabled(ok(wrapper))).toBe(true)
    await field(wrapper).setValue('lab')
    expect(isDisabled(ok(wrapper))).toBe(false)
  })

  it('keeps the stock wording when the capability is absent', async () => {
    capability = { keeprootvolumedefault: true }
    const wrapper = await open()
    expect(wrapper.find('.ant-alert').text()).toContain(en['message.action.destroy.instance'])
    expect(wrapper.find('.ant-alert').text()).not.toContain(en['message.action.destroy.instance.now'])
  })
})
