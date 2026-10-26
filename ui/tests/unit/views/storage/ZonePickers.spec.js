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
import { getAPI, postAPI, callAPI } from '@/api'
import store from '@/store'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
import image from '@/config/section/image'
import CreateVolume from '@/views/storage/CreateVolume'
import FormSchedule from '@/views/storage/FormSchedule'
import TakeSnapshot from '@/views/storage/TakeSnapshot'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

jest.setTimeout(30000)

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const own = { id: 'z-1', name: 'snucse' }
const other = { id: 'z-2', name: 'other' }
const volume = { id: 'v-1', name: 'data', zoneid: 'z-1', zonename: 'snucse', account: 'student', domainid: 'd-1' }

const PARAMS = ['name', 'zoneid', 'diskofferingid', 'size', 'zoneids', 'usestoragereplication', 'quiescevm', 'asyncbackup', 'intervaltype', 'maxsnaps', 'schedule', 'timezone']
  .map(name => ({ name, description: name }))
const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: PARAMS }]))

let zones

const open = async (component, { props = {}, locale = 'en', path = '/volume' } = {}) => {
  store.getters.apis = apis
  const answer = (command) => {
    switch (command) {
      case 'listZones': return Promise.resolve({ listzonesresponse: { zone: zones } })
      case 'listDiskOfferings': return Promise.resolve({ listdiskofferingsresponse: { diskoffering: [{ id: 'do-1', name: 'small', displaytext: 'Small', iscustomized: false }] } })
      case 'listSnapshots': return Promise.resolve({ listsnapshotsresponse: { count: 0 } })
      default: return Promise.resolve({})
    }
  }
  for (const fn of [getAPI, postAPI, callAPI]) {
    fn.mockImplementation(answer)
  }
  const router = common.createMockRouter([{ path, name: 'page', component: {} }])
  router.push(path)
  await router.isReady()
  const wrapper = common.createFactory(component, {
    router,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    store: common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {} } }),
    props,
    provide: { parentFetchData: jest.fn() },
    mocks: { $config: {}, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn() }, $pollJob: jest.fn() },
    stubs: { OwnershipSelection: true }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const hintOf = (zonename) => en['message.snapshot.additional.zones'].replace('%x', zonename)

describe('the zone pickers and hints of a cloud with one zone, and with two', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  describe('taking a snapshot', () => {
    it('offers no zone, no hint about zones and no storage replication when the only zone is the volume\'s own', async () => {
      zones = [own]
      const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
      expect(wrapper.find('#zone-selection').exists()).toBe(false)
      expect(wrapper.find('.ant-alert-info').exists()).toBe(false)
      expect(wrapper.text()).not.toContain(hintOf('snucse'))
      expect(wrapper.text()).not.toContain(en['label.zones'])
      expect(wrapper.text()).not.toContain(en['label.usestoragereplication'])
    })

    it('still takes the snapshot, in its own zone', async () => {
      zones = [own]
      const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      const call = postAPI.mock.calls.find(entry => entry[0] === 'createSnapshot')
      expect(call[1].volumeId).toBe('v-1')
      expect(call[1]).not.toHaveProperty('zoneids')
      expect(call[1].useStorageReplication).toBe(false)
    })

    it('offers the other zones, the hint and the storage replication when there is another zone', async () => {
      zones = [own, other]
      const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
      expect(wrapper.find('#zone-selection').exists()).toBe(true)
      expect(wrapper.find('.ant-alert-info').exists()).toBe(true)
      expect(wrapper.text()).toContain(hintOf('snucse'))
      expect(wrapper.text()).toContain(en['label.usestoragereplication'])
    })

    it('offers no zone for an edge zone alone either', async () => {
      zones = [own, { id: 'z-3', name: 'edge', type: 'Edge' }]
      const wrapper = await open(TakeSnapshot, { props: { resource: volume } })
      expect(wrapper.find('#zone-selection').exists()).toBe(false)
    })
  })

  describe('the recurring snapshot of a volume', () => {
    const props = { dataSource: [], resource: volume, resourceType: 'Volume' }

    it('offers no zone, no hint about zones and no storage replication when the only zone is the volume\'s own', async () => {
      zones = [own]
      const wrapper = await open(FormSchedule, { props })
      expect(wrapper.find('#zone-selection').exists()).toBe(false)
      expect(wrapper.text()).not.toContain(hintOf('snucse'))
      expect(wrapper.text()).not.toContain(en['label.usestoragereplication'])
    })

    it('offers the other zones, the hint and the storage replication when there is another zone', async () => {
      zones = [own, other]
      const wrapper = await open(FormSchedule, { props })
      expect(wrapper.find('#zone-selection').exists()).toBe(true)
      expect(wrapper.text()).toContain(hintOf('snucse'))
      expect(wrapper.text()).toContain(en['label.usestoragereplication'])
    })
  })

  describe('creating a volume', () => {
    it('chooses the only zone and does not ask for it', async () => {
      zones = [own]
      const wrapper = await open(CreateVolume, { props: { resource: {} } })
      expect(wrapper.vm.form.zoneid).toBe('z-1')
      expect(wrapper.text()).not.toContain(en['label.zoneid'])
    })

    it('asks for the zone when there are two', async () => {
      zones = [own, other]
      const wrapper = await open(CreateVolume, { props: { resource: {} } })
      expect(wrapper.text()).toContain(en['label.zoneid'])
    })

    it('does not ask for the zone of a volume made from a snapshot that lives in the only zone', async () => {
      zones = [own]
      getAPI.mockImplementation((command) => command === 'listSnapshots'
        ? Promise.resolve({ listsnapshotsresponse: { snapshot: [{ id: 's-1', zoneid: 'z-1' }] } })
        : command === 'listZones' ? Promise.resolve({ listzonesresponse: { zone: zones } }) : Promise.resolve({}))
      const wrapper = await open(CreateVolume, { props: { resource: { id: 's-1', volumetype: 'DATADISK', zoneid: 'z-1' } }, path: '/snapshot/s-1' })
      expect(wrapper.text()).not.toContain(en['label.zoneid'])
    })
  })

  describe('the Zones tab of a snapshot', () => {
    const snapshotPage = storage.children.find(page => page.name === 'snapshot')
    const zonesTab = snapshotPage.tabs.find(tab => tab.name === 'zones')
    const as = (roletype, zoneCount) => {
      store.getters.userInfo = { roletype }
      store.getters.zones = Array.from({ length: zoneCount }, (_, i) => ({ id: 'z-' + (i + 1) }))
    }

    afterEach(() => {
      store.getters.userInfo = { roletype: 'User', domainid: 'd-1', account: 'student' }
      store.getters.zones = []
    })

    it('is hidden from the User role type while there is no zone but its own', () => {
      as('User', 1)
      expect(zonesTab.show({ id: 's-1', zoneid: 'z-1' })).toBe(false)
    })

    it('is shown to the User role type when there is another zone', () => {
      as('User', 2)
      expect(zonesTab.show({ id: 's-1', zoneid: 'z-1' })).toBe(true)
    })

    it('does not count an edge zone as another zone', () => {
      as('User', 1)
      store.getters.zones.push({ id: 'z-9', type: 'Edge' })
      expect(zonesTab.show({ id: 's-1', zoneid: 'z-1' })).toBe(false)
    })

    it('is hidden from the User role type until the zones of the session are loaded', () => {
      as('User', 1)
      store.getters.zones = {}
      expect(zonesTab.show({ id: 's-1', zoneid: 'z-1' })).toBe(false)
    })

    it('is shown to the other role types whatever the number of zones', () => {
      for (const roletype of ['Admin', 'DomainAdmin']) {
        as(roletype, 1)
        expect(zonesTab.show({ id: 's-1', zoneid: 'z-1' })).toBe(true)
      }
    })
  })

  describe.each([['template', 'zones'], ['iso', 'zones']])('the Zones tab of a %s', (pageName, tabName) => {
    const tab = image.children.find(page => page.name === pageName).tabs.find(entry => entry.name === tabName)
    const as = (roletype, zoneCount) => {
      store.getters.userInfo = { roletype }
      store.getters.zones = Array.from({ length: zoneCount }, (_, i) => ({ id: 'z-' + (i + 1) }))
    }

    afterEach(() => {
      store.getters.userInfo = { roletype: 'User', domainid: 'd-1', account: 'student' }
      store.getters.zones = []
    })

    it('is hidden from the User role type while there is one zone, and shown when there are two', () => {
      as('User', 1)
      expect(tab.show({ id: 't-1' })).toBe(false)
      as('User', 2)
      expect(tab.show({ id: 't-1' })).toBe(true)
    })

    it('is shown to the other role types whatever the number of zones', () => {
      for (const roletype of ['Admin', 'DomainAdmin']) {
        as(roletype, 1)
        expect(tab.show({ id: 't-1' })).toBe(true)
      }
    })
  })

  describe('the zone filter of the lists', () => {
    const pages = [
      ['instances', compute.children.find(page => page.name === 'vm')],
      ['volumes', storage.children.find(page => page.name === 'volume')],
      ['snapshots', storage.children.find(page => page.name === 'snapshot')],
      ['templates', image.children.find(page => page.name === 'template')],
      ['ISOs', image.children.find(page => page.name === 'iso')]
    ]
    const as = (roletype, zoneCount) => {
      store.getters.userInfo = { roletype }
      store.getters.zones = Array.from({ length: zoneCount }, (_, i) => ({ id: 'z-' + (i + 1) }))
    }

    afterEach(() => {
      store.getters.userInfo = { roletype: 'User', domainid: 'd-1', account: 'student' }
      store.getters.zones = []
    })

    it.each(pages)('is not offered by the list of %s of a student while there is one zone', (name, page) => {
      as('User', 1)
      expect(page.searchFilters()).not.toContain('zoneid')
    })

    it.each(pages)('is offered by the list of %s of a student when there are two zones', (name, page) => {
      as('User', 2)
      expect(page.searchFilters()).toContain('zoneid')
    })

    it.each(pages)('is offered by the list of %s of an administrator', (name, page) => {
      as('Admin', 1)
      expect(page.searchFilters()).toContain('zoneid')
    })

    it.each(pages)('is not offered by the list of %s of a student until the zones are loaded, and not for an edge zone', (name, page) => {
      as('User', 1)
      store.getters.zones = {}
      expect(page.searchFilters()).not.toContain('zoneid')
      store.getters.zones = [{ id: 'z-1' }, { id: 'z-2', type: 'Edge' }]
      expect(page.searchFilters()).not.toContain('zoneid')
    })
  })

  describe('the tag filter of the lists', () => {
    const pages = [
      ['instances', compute.children.find(page => page.name === 'vm')],
      ['instance snapshots', compute.children.find(page => page.name === 'vmsnapshot')],
      ['volumes', storage.children.find(page => page.name === 'volume')],
      ['snapshots', storage.children.find(page => page.name === 'snapshot')],
      ['templates', image.children.find(page => page.name === 'template')],
      ['ISOs', image.children.find(page => page.name === 'iso')]
    ]
    const filtersOf = (page) => typeof page.searchFilters === 'function' ? page.searchFilters() : page.searchFilters

    afterEach(() => {
      store.getters.userInfo = { roletype: 'User', domainid: 'd-1', account: 'student' }
    })

    it.each(pages)('is not offered by the list of %s of a student, and is offered to an administrator', (name, page) => {
      store.getters.userInfo = { roletype: 'User' }
      expect(filtersOf(page)).not.toContain('tags')
      store.getters.userInfo = { roletype: 'Admin' }
      expect(filtersOf(page)).toContain('tags')
    })
  })
})
