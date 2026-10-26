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
import SnapshotZones from '@/views/storage/SnapshotZones'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))

const snapshot = { id: 's-1', name: 'data-snapshot-0', state: 'BackedUp', zoneid: 'z-1' }
const copies = [
  { id: 's-1', name: 'data-snapshot-0', state: 'BackedUp', zoneid: 'z-1', zonename: 'one', datastorestate: 'Ready' },
  { id: 's-1', name: 'data-snapshot-0', state: 'BackedUp', zoneid: 'z-2', zonename: 'two', datastorestate: 'Ready' }
]

const mounted = []

const open = async (found = copies) => {
  const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: [] }]))
  require('@/store').getters.apis = apis
  getAPI.mockImplementation((command) => command === 'listSnapshots'
    ? Promise.resolve({ listsnapshotsresponse: { count: found.length, snapshot: found } })
    : Promise.resolve({ listzonesresponse: { zone: [] } }))
  const $pollJob = jest.fn()
  const wrapper = common.createFactory(SnapshotZones, {
    router: common.createMockRouter([{ path: '/snapshot/:id', name: 'snapshot', component: {} }]),
    i18n: common.createMockI18n('en', { en }),
    store: common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {} } }),
    props: { resource: snapshot },
    mocks: { $config: {}, $notifyError: jest.fn(), $pollJob },
    // the dialog is drawn with the real transition: the test utilities' stand-in is a new component on every render, which
    // would remount the input while it is typed in
    stubs: { BulkActionProgress: true, transition: false }
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

const field = () => document.querySelector('.ant-modal .confirm-input input')
const okButton = () => document.querySelector('.ant-modal .action-button .ant-btn-primary')
const typeInto = async (text) => {
  field().value = text
  field().dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}
const deleted = () => postAPI.mock.calls.filter(call => call[0] === 'deleteSnapshot').map(call => call[1])

jest.setTimeout(30000)

describe('deleting the copies of a snapshot in its zones', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
    postAPI.mockImplementation(() => Promise.resolve({ deletesnapshotresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.innerHTML = ''
    jest.restoreAllMocks()
  })

  it('asks for the name of the snapshot and keeps the button disabled until it is typed', async () => {
    const wrapper = await open()
    wrapper.vm.onShowDeleteModal(copies[1])
    await flushPromises()
    expect(document.querySelector('.ant-modal .confirm-expected').textContent).toBe('data-snapshot-0')
    expect(okButton().hasAttribute('disabled')).toBe(true)

    await typeInto('data-snapshot')
    expect(okButton().hasAttribute('disabled')).toBe(true)

    await typeInto('data-snapshot-0')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    okButton().click()
    await flushPromises()
    expect(deleted()).toEqual([{ id: 's-1', zoneid: 'z-2' }])
  })

  it('deletes nothing before the name is typed, however the dialog is submitted', async () => {
    const wrapper = await open()
    wrapper.vm.onShowDeleteModal(copies[1])
    await flushPromises()
    wrapper.vm.submitDelete()
    await flushPromises()
    await typeInto('data-snapshot')
    wrapper.vm.submitDelete()
    await flushPromises()
    // the modal's own OK event goes the same way
    wrapper.findComponent({ name: 'AModal' }).vm.$emit('ok')
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto('data-snapshot-0')
    wrapper.vm.submitDelete()
    await flushPromises()
    expect(deleted()).toEqual([{ id: 's-1', zoneid: 'z-2' }])
  })

  it('asks for the name, not the number, when a row is ticked and the trash icon of a row is clicked', async () => {
    const wrapper = await open()
    wrapper.vm.onSelectChange(['z-1'])
    wrapper.vm.onShowDeleteModal(copies[1])
    await flushPromises()
    expect(document.querySelector('.ant-modal .confirm-expected').textContent).toBe('data-snapshot-0')
    expect(document.querySelector('.ant-modal .confirm-prompt').textContent).toBe(en['label.delete.confirmation'])
    expect(document.querySelector('.ant-modal table')).toBeNull()

    await typeInto('1')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    wrapper.vm.submitDelete()
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto('data-snapshot-0')
    okButton().click()
    await flushPromises()
    expect(deleted()).toEqual([{ id: 's-1', zoneid: 'z-2' }])
  })

  it('says that this zone\'s copy is deleted for good when other copies remain', async () => {
    const wrapper = await open()
    wrapper.vm.onShowDeleteModal(copies[1])
    await flushPromises()
    const alert = document.querySelector('.ant-modal .ant-alert').textContent
    expect(alert).toContain(en['message.action.delete.snapshot.copy'])
    expect(en['message.action.delete.snapshot.copy']).toMatch(/for good/)
    expect(alert).not.toContain(en['message.action.delete.snapshot'])
  })

  it('says that the snapshot is deleted for good when its only copy goes', async () => {
    const wrapper = await open([copies[0]])
    wrapper.vm.onShowDeleteModal(copies[0])
    await flushPromises()
    expect(document.querySelector('.ant-modal .ant-alert').textContent).toContain(en['message.action.delete.snapshot'])
  })

  it('asks for the number of the selected copies and deletes each of them once it is typed', async () => {
    const wrapper = await open()
    wrapper.vm.onSelectChange(['z-1', 'z-2'])
    wrapper.vm.bulkActionConfirmation()
    await flushPromises()
    expect(document.querySelector('.ant-modal .confirm-prompt').textContent).toBe(en['message.confirm.type.count'].replace('{count}', '2'))
    expect(okButton().hasAttribute('disabled')).toBe(true)

    await typeInto('1')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    wrapper.vm.submitDelete()
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto('2')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    okButton().click()
    await flushPromises()
    expect(deleted().map(params => params.zoneid).sort()).toEqual(['z-1', 'z-2'])
  })
})
