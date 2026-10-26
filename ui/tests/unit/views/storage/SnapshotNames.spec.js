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
import TakeSnapshot from '@/views/storage/TakeSnapshot'
import CreateSnapshotWizard from '@/views/compute/CreateSnapshotWizard'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const PARAMS = ['name', 'volumeid', 'zoneids', 'usestoragereplication', 'quiescevm', 'asyncbackup'].map(name => ({ name, description: name }))
const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: PARAMS }]))
const volume = { id: 'v-1', name: 'data', zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1' }

const open = async (component, { locale = 'en', props = {} } = {}) => {
  const apis = apiMap(minimalUser.apis)
  require('@/store').getters.apis = apis
  getAPI.mockImplementation((command) => command === 'listVolumes'
    ? Promise.resolve({ listvolumesresponse: { volume: [volume] } })
    : Promise.resolve({ listzonesresponse: { zone: [] } }))
  const wrapper = common.createFactory(component, {
    router: common.createMockRouter([{ path: '/', name: 'page', component: {} }]),
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    store: common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {} } }),
    props,
    provide: { parentFetchData: jest.fn() },
    mocks: { $config: {}, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn() }, $pollJob: jest.fn() }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const hint = (wrapper) => wrapper.find('.ant-form-item-extra').text()
const nameItem = (wrapper) => wrapper.findAll('.ant-form-item').find(item => item.find('input').exists())

describe('the name of a new snapshot', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
    postAPI.mockImplementation(() => Promise.resolve({ createsnapshotresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('is optional, and the form says how an empty one is named', async () => {
    const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
    expect(nameItem(wrapper).find('label').text()).toBe(en['label.name.optional'])
    expect(hint(wrapper)).toBe(en['message.snapshot.name.default'])
    expect(en['message.snapshot.name.default']).toContain('-snapshot-0')
    expect(en['message.snapshot.name.default']).toContain('-1')
  })

  it('is optional in the form of an Instance\'s volumes too', async () => {
    const wrapper = await open(CreateSnapshotWizard, { props: { resource: { id: 'vm-1', hypervisor: 'KVM' } } })
    expect(hint(wrapper)).toBe(en['message.snapshot.name.default'])
  })

  it('is hinted in Korean as the student docs word it', async () => {
    const wrapper = await open(TakeSnapshot, { locale: 'ko_KR', props: { resource: volume } })
    expect(ko['message.snapshot.name.default']).toBe('비워 두면 <볼륨 이름>-snapshot-0, -1 … 순서로 이름이 붙습니다.')
    expect(hint(wrapper)).toBe(ko['message.snapshot.name.default'])
    expect(nameItem(wrapper).find('label').text()).toBe(ko['label.name.optional'])
  })

  it('is left out of the request when empty, so that the server names the snapshot', async () => {
    const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    const [command, params] = postAPI.mock.calls[0]
    expect(command).toBe('createSnapshot')
    expect(params).not.toHaveProperty('name')
    expect(params.volumeId).toBe('v-1')
  })

  const wizard = async () => {
    const wrapper = await open(CreateSnapshotWizard, { props: { resource: { id: 'vm-1', hypervisor: 'KVM' } } })
    wrapper.vm.form.volumeid = 'v-1'
    return wrapper
  }
  const submit = async (wrapper) => {
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    await flushPromises()
  }
  const errors = (wrapper) => wrapper.findAll('.ant-form-item-explain-error').map(node => node.text())
  const posted = () => postAPI.mock.calls.filter(call => call[0] === 'createSnapshot').map(call => call[1])

  it('is left out of the request of an Instance\'s volume when it was typed in and cleared', async () => {
    const wrapper = await wizard()
    wrapper.vm.form.name = 'before-upgrade'
    wrapper.vm.form.name = ''
    await submit(wrapper)
    expect(posted()).toHaveLength(1)
    expect(posted()[0]).not.toHaveProperty('name')
    expect(posted()[0].volumeid).toBe('v-1')
  })

  it('is sent as typed when it is valid', async () => {
    const wrapper = await wizard()
    wrapper.vm.form.name = 'before-upgrade.v1_x'
    await submit(wrapper)
    expect(posted()[0].name).toBe('before-upgrade.v1_x')

    const volumeForm = await open(TakeSnapshot, { props: { resource: volume } })
    volumeForm.vm.form.name = 'before-upgrade.v1_x'
    await submit(volumeForm)
    expect(posted()[1].name).toBe('before-upgrade.v1_x')
  })

  it('is refused outside [A-Za-z0-9._-] and 1 to 64 characters, in both forms, with the rule said', async () => {
    for (const bad of ['my snapshot', 'a/b', '데이터', 'a'.repeat(65)]) {
      const wrapper = await wizard()
      wrapper.vm.form.name = bad
      await submit(wrapper)
      expect(errors(wrapper)).toContain(en['message.error.resource.name.rule'])

      const volumeForm = await open(TakeSnapshot, { props: { resource: volume } })
      volumeForm.vm.form.name = bad
      await submit(volumeForm)
      expect(errors(volumeForm)).toContain(en['message.error.resource.name.rule'])
    }
    expect(postAPI).not.toHaveBeenCalled()
  })
})
