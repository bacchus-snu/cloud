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

import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import { getAPI, postAPI, callAPI } from '@/api'
import CreateVolume from '@/views/storage/CreateVolume'
import FormSchedule from '@/views/storage/FormSchedule'
import SnapshotZones from '@/views/storage/SnapshotZones'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

// the fields the forms read the descriptions of
const PARAMS = ['name', 'zoneid', 'diskofferingid', 'size', 'miniops', 'maxiops', 'kmskeyid', 'usestoragereplication'].map(name => ({ name, description: name }))
const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: PARAMS }]))

const volume = { id: 'v-1', name: 'data', zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1' }
const zones = [{ id: 'z-1', name: 'zone' }, { id: 'z-2', name: 'other' }]
const encrypted = { id: 'do-enc', name: 'encrypted', displaytext: 'Encrypted', encrypt: true, iscustomized: false }
const pools = [{ id: 'p-1', name: 'pool', state: 'Up', zoneid: 'z-2', storagecapabilities: { CAN_COPY_SNAPSHOT_BETWEEN_ZONES_AND_SAME_POOL_TYPE: true } }]

// a role gets an answer for the lists it keeps and the refusal the server gives for any other command
const answer = (apis) => (command) => {
  if (!(command in apis)) {
    return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
  }
  switch (command) {
    case 'listZones': return Promise.resolve({ listzonesresponse: { zone: zones } })
    case 'listDiskOfferings': return Promise.resolve({ listdiskofferingsresponse: { diskoffering: [encrypted] } })
    case 'listStoragePools': return Promise.resolve({ liststoragepoolsresponse: { storagepool: pools } })
    case 'listKMSKeys': return Promise.resolve({ listkmskeysresponse: { kmskey: [{ id: 'k-1', name: 'key' }] } })
    case 'listSnapshots': return Promise.resolve({ listsnapshotsresponse: { count: 0 } })
  }
  return Promise.resolve({})
}

const calledCommands = () => [...getAPI.mock.calls, ...postAPI.mock.calls, ...callAPI.mock.calls].map(call => call[0])

const open = async (component, { names = minimalUser.apis, roletype = 'User', path = '/volume', props = {} } = {}) => {
  const apis = apiMap(names)
  const router = common.createMockRouter([{ path, name: 'page', component: {} }])
  router.push(path)
  await router.isReady()
  require('@/store').getters.apis = apis
  require('@/store').getters.userInfo = { roletype, domainid: 'd-1', account: 'student' }
  for (const fn of [getAPI, postAPI, callAPI]) {
    fn.mockImplementation(answer(apis))
  }
  const wrapper = common.createFactory(component, {
    router,
    i18n: common.createMockI18n('en', {}),
    store: common.createMockStore({ user: { apis, info: { roletype, domainid: 'd-1', account: 'student' }, features: {} } }),
    props,
    provide: { parentFetchData: jest.fn() },
    mocks: { $config: {}, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn() } },
    stubs: { OwnershipSelection: true }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('forms of the volume and snapshot pages under the student role', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('creating a volume asks for no KMS key list or storage pool list, and offers neither', async () => {
    const wrapper = await open(CreateVolume, { props: { resource: {} } })
    expect(calledCommands()).toContain('listDiskOfferings')
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
    expect(wrapper.vm.diskOfferingSupportsEncryption).toBe(true)
    expect(wrapper.html()).not.toContain('label.kms.key')
    expect(wrapper.vm.kmsKeys).toBeNull()
  })

  it('creating a volume asks for the key list when the role has it', async () => {
    const wrapper = await open(CreateVolume, { names: [...minimalUser.apis, 'listKMSKeys'], props: { resource: {} } })
    expect(calledCommands()).toContain('listKMSKeys')
    expect(wrapper.vm.kmsKeys).toEqual([{ id: 'k-1', name: 'key' }])
  })

  it('the recurring snapshot form of a volume asks for the zones and no storage pools', async () => {
    await open(FormSchedule, { props: { dataSource: [], resource: volume, resourceType: 'Volume' } })
    expect(calledCommands()).toContain('listZones')
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })

  it('the recurring snapshot form asks for the storage pools of a role that has the list', async () => {
    await open(FormSchedule, { names: [...minimalUser.apis, 'listStoragePools'], roletype: 'Admin', props: { dataSource: [], resource: volume, resourceType: 'Volume' } })
    expect(calledCommands()).toContain('listStoragePools')
  })

  it('the copies of a snapshot are listed without asking for storage pools', async () => {
    const wrapper = await open(SnapshotZones, { path: '/snapshot/s-1', props: { resource: { id: 's-1', zoneid: 'z-1' } } })
    expect(calledCommands()).toContain('listSnapshots')
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
    wrapper.vm.showCopySnapshot({ id: 's-1', zoneid: 'z-1' })
    await flushPromises()
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })
})
