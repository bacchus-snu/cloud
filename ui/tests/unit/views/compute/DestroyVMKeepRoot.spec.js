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
const vm = { id: 'vm-1', name: 'lab' }

let capability

const open = async (props = {}, locale = 'en') => {
  const apis = { destroyVirtualMachine: { params: [{ name: 'expunge', description: '' }, { name: 'volumeids', description: '' }] }, listCapabilities: { params: [] }, listVolumes: { params: [] } }
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } })
  const $pollJob = jest.fn()
  const wrapper = common.createFactory(DestroyVM, {
    store,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { resource: vm, action: {}, ...props },
    provide: { parentFetchData: jest.fn() },
    mocks: { $pollJob, $notifyError: jest.fn(), $message: { info: jest.fn() } },
    stubs: { BulkActionProgress: true }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const destroyed = () => postAPI.mock.calls.filter(call => call[0] === 'destroyVirtualMachine').map(call => call[1])
const checkbox = (wrapper) => wrapper.find('.keep-root-volume input[type="checkbox"]')

describe('DestroyVM keeps the root disk', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    capability = { keeprootvolumedefault: true }
    getAPI.mockImplementation((command) => {
      if (command === 'listCapabilities') {
        return Promise.resolve({ listcapabilitiesresponse: { capability } })
      }
      return Promise.resolve({ listvolumesresponse: { volume: [] } })
    })
    postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  it('checkbox starts at keeprootvolumedefault and sends keeprootvolume', async () => {
    const wrapper = await open()
    expect(getAPI.mock.calls.some(call => call[0] === 'listCapabilities')).toBe(true)
    // checked by default, labelled as the user's language says
    expect(checkbox(wrapper).exists()).toBe(true)
    expect(checkbox(wrapper).element.checked).toBe(true)
    expect(wrapper.find('.keep-root-volume').text()).toContain('Keep the root disk')

    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1', keeprootvolume: true }])

    // and the user's choice is sent as it stands, also when it is off
    const second = await open()
    await checkbox(second).setValue(false)
    await second.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(destroyed()[1]).toEqual({ id: 'vm-1', keeprootvolume: false })
  })

  it('starts unchecked when the operator set the root to be destroyed with its instance', async () => {
    capability = { keeprootvolumedefault: false }
    const wrapper = await open()
    expect(checkbox(wrapper).element.checked).toBe(false)
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1', keeprootvolume: false }])
  })

  it('asks nothing of the server and sends no value while the default is unknown', async () => {
    getAPI.mockImplementation((command) => command === 'listCapabilities'
      ? Promise.reject(new Error('no'))
      : Promise.resolve({ listvolumesresponse: { volume: [] } }))
    const wrapper = await open()
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(destroyed()).toEqual([{ id: 'vm-1' }])
  })

  it('is called "루트 디스크 남기기" in Korean', async () => {
    const wrapper = await open({}, 'ko_KR')
    expect(wrapper.find('.keep-root-volume').text()).toContain('루트 디스크 남기기')
  })

  it('applies the one choice to every selected instance', async () => {
    const wrapper = await open({
      resource: {},
      selectedRowKeys: ['vm-1', 'vm-2'],
      selectedItems: [{ id: 'vm-1', name: 'a' }, { id: 'vm-2', name: 'b' }],
      action: { currentAction: { label: 'label.action.destroy.instance', message: 'message.action.destroy.instance' } }
    })
    expect(checkbox(wrapper).element.checked).toBe(true)
    await checkbox(wrapper).setValue(false)
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(destroyed().map(params => [params.id, params.keeprootvolume])).toEqual([['vm-1', false], ['vm-2', false]])
  })
})
