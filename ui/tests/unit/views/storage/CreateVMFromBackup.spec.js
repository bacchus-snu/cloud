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
import CreateVMFromBackup from '@/views/storage/CreateVMFromBackup'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', username: 'baneling100' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))

const backup = {
  id: 'b-1',
  zoneid: 'z-1',
  backupofferingid: 'bo-1',
  isbackupvmexpunged: false,
  volumes: '[]',
  vmdetails: { serviceofferingid: 'so-1', templateid: 't-1' }
}

const answers = {
  listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [{ id: 'so-1' }] } },
  listBackupOfferings: { listbackupofferingsresponse: { backupoffering: [{ id: 'bo-1', provider: 'dummy' }] } },
  listTemplates: { listtemplatesresponse: { template: [{ id: 't-1', arch: 'x86_64' }] } }
}

const mounted = []

const open = async () => {
  const apis = { createVMFromBackup: { params: [{ name: 'quickrestore', description: 'quick' }] } }
  const pollJob = jest.fn()
  const wrapper = common.createFactory(CreateVMFromBackup, {
    store: common.createMockStore({ user: { apis, info: { roletype: 'User', username: 'baneling100' }, features: {} } }),
    i18n: common.createMockI18n('en', { en }),
    props: { resource: backup },
    mocks: { $notifyError: jest.fn(), $pollJob: pollJob }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const submit = async (wrapper, name) => {
  wrapper.vm.form.name = name
  wrapper.vm.handleSubmit({ preventDefault: jest.fn() })
  await flushPromises()
  await flushPromises()
}
const posted = () => postAPI.mock.calls.filter(call => call[0] === 'createVMFromBackup').map(call => call[1])

describe('creating an instance from a backup: its name', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    getAPI.mockImplementation((command) => Promise.resolve(answers[command] || {}))
    postAPI.mockImplementation(() => Promise.resolve({ deployvirtualmachineresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
  })

  it('says under the field that an empty name gets VM-0, VM-1 and so on', async () => {
    const wrapper = await open()
    expect(wrapper.find('.ant-form-item-extra').text()).toBe(en['message.instance.name.default'])
  })

  it('is posted as displayname and never as name', async () => {
    const wrapper = await open()
    await submit(wrapper, 'my-vm')
    expect(posted()).toHaveLength(1)
    expect(posted()[0]).toMatchObject({ backupid: 'b-1', zoneid: 'z-1', displayname: 'my-vm' })
    expect(posted()[0]).not.toHaveProperty('name')
  })

  it('is no name of either kind when none was given', async () => {
    const wrapper = await open()
    await submit(wrapper, '')
    expect(posted()).toHaveLength(1)
    expect(posted()[0]).not.toHaveProperty('name')
    expect(posted()[0]).not.toHaveProperty('displayname')
  })

  it('is nothing when the name breaks the rule: the form says so and no request leaves', async () => {
    const wrapper = await open()
    await submit(wrapper, 'my vm')
    expect(posted()).toHaveLength(0)
    expect(wrapper.find('.ant-form-item-explain-error').text()).toBe(en['message.error.instance.name.rule'])
  })

  it('shows the error of a request the server refuses, and the failure handling itself throws nothing', async () => {
    const wrapper = await open()
    const unhandled = []
    const record = (reason) => unhandled.push(reason)
    process.on('unhandledRejection', record)
    const refusal = new Error('name is taken')
    postAPI.mockImplementation(() => Promise.reject(refusal))
    await submit(wrapper, 'my-vm')
    await new Promise(resolve => setTimeout(resolve, 20))
    process.removeListener('unhandledRejection', record)
    expect(wrapper.vm.$notifyError).toHaveBeenCalledWith(refusal)
    expect(unhandled).toEqual([])
    expect(wrapper.vm.loading).toBe(false)
  })
})
