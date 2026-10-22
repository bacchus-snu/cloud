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
import { getAPI } from '@/api'
import ResizeVolume from '@/views/storage/ResizeVolume'
import ChangeOfferingForVolume from '@/views/storage/ChangeOfferingForVolume'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

jest.setTimeout(30000)

const volume = { id: 'vol-1', name: 'data', zoneid: 'z-1', hypervisor: 'KVM' }
const offering = { id: 'do-1', name: 'standard', displaytext: 'Standard', iscustomized: true }

// the switch is the only field these two forms label with the automigrate string
const hasSwitch = (wrapper) => wrapper.text().includes('label.automigrate.volume')

const open = async (component, features) => {
  const apis = {
    resizeVolume: { params: [{ name: 'automigrate', description: 'move the volume' }] },
    changeOfferingForVolume: { params: [{ name: 'automigrate', description: 'move the volume' }, { name: 'diskofferingid', description: '' }, { name: 'size', description: '' }, { name: 'miniops', description: '' }, { name: 'maxiops', description: '' }] }
  }
  const wrapper = common.createFactory(component, {
    store: common.createMockStore({ user: { apis, info: { roletype: 'User' }, features } }),
    i18n: common.createMockI18n('en', {}),
    props: { resource: volume },
    provide: { parentFetchData: jest.fn() },
    mocks: { $notifyError: jest.fn(), $pollJob: jest.fn() }
  })
  await flushPromises()
  return wrapper
}

describe('the switch for the automatic migration of a volume', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    getAPI.mockImplementation(() => Promise.resolve({ listdiskofferingsresponse: { diskoffering: [offering] } }))
  })

  for (const [name, component] of [['ResizeVolume', ResizeVolume], ['ChangeOfferingForVolume', ChangeOfferingForVolume]]) {
    it(`${name} shows it only when the server's capabilities say the caller may use it`, async () => {
      expect(hasSwitch(await open(component, {}))).toBe(false)
      expect(hasSwitch(await open(component, { automigrateallowed: false }))).toBe(false)
      expect(hasSwitch(await open(component, { automigrateallowed: true }))).toBe(true)
    })
  }
})
