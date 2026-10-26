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
import { unref } from 'vue'
import { createStore } from 'vuex'
import { flushPromises } from '@vue/test-utils'

import common from '../../common'
import { getAPI, postAPI, callAPI } from '@/api'
import AutogenView from '@/views/AutogenView'
import RecurringSnapshotVolume from '@/views/storage/RecurringSnapshotVolume'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
import image from '@/config/section/image'
import event from '@/config/section/event'
import deployment from '../../mockData/DeploymentConfig.mock.json'
import minimalUser from '../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn(), getBaseUrl: jest.fn(() => '') }))
jest.mock('@/store', () => ({
  getters: {
    features: {},
    apis: Object.fromEntries(require('../../mockData/MinimalUserApis.mock.json').apis.map(name => [name, { params: [], response: [] }])),
    userInfo: { roletype: 'User', account: 'student', domainid: 'd-1' },
    zones: [{ id: 'z-1', name: 'zone' }],
    project: {},
    metrics: false,
    listAllProjects: false,
    customColumns: {},
    defaultListViewPageSize: 20
  },
  dispatch: jest.fn(),
  commit: jest.fn(),
  watch: jest.fn()
}))
jest.mock('@/vue-app', () => ({
  vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: { ...require('../../../public/config.json'), ...require('../../mockData/DeploymentConfig.mock.json') } }
}))

const uiRoot = path.resolve(__dirname, '../../..')
const deployedConfig = { ...JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8')), ...deployment }
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/ko_KR.json'), 'utf8'))
const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: [], response: [] }]))

const HOSTNAME = 'i-2-34-VM'
const instance = {
  id: 'vm-1',
  name: HOSTNAME,
  displayname: 'lab',
  sshname: 'lab',
  state: 'Running',
  zoneid: 'z-1',
  zonename: 'zone',
  account: 'student',
  domainid: 'd-1',
  domain: 'ROOT',
  serviceofferingname: 'cpu',
  templatename: 'debian-13-20260914',
  ostypename: 'Debian',
  cpunumber: 4,
  memory: 4096,
  isdynamicallyscalable: true,
  nic: []
}
const volume = {
  id: 'v-1',
  name: 'ROOT-34',
  type: 'ROOT',
  state: 'Ready',
  size: 21474836480,
  zoneid: 'z-1',
  zonename: 'zone',
  account: 'student',
  domainid: 'd-1',
  domain: 'ROOT',
  virtualmachineid: 'vm-1',
  vmname: HOSTNAME,
  vmdisplayname: 'lab',
  vmstate: 'Running'
}
const snapshot = { id: 's-1', name: 'ROOT-34-snapshot-0', state: 'BackedUp', volumeid: 'v-1', volumename: 'ROOT-34', volumetype: 'ROOT', zoneid: 'z-1', account: 'student', domainid: 'd-1', domain: 'ROOT' }
const logged = { id: 'e-1', type: 'VM.START', description: 'started', level: 'INFO', state: 'Completed', resourcetype: 'UserVm', resourceid: 'vm-1', resourcename: 'lab', account: 'student', created: '2026-10-21T10:00:00+0000' }

const policy = {
  id: 'p-1',
  intervaltype: 1,
  schedule: '30:03',
  timezone: 'Asia/Seoul',
  maxsnaps: 3,
  volumeid: 'v-1',
  tags: [{ key: 'ssh.account.debian', value: 'alice' }, { key: 'course', value: 'os' }],
  zone: [{ id: 'z-9', name: 'elsewhere' }]
}

const template = { id: 't-1', name: 'debian-13-20260914', displaytext: 'Debian 13', ostypename: 'Debian', isready: true, zoneid: 'z-1', zonename: 'zone', account: 'admin', domainid: 'd-0', domain: 'ROOT', arch: 'x86_64', hypervisor: 'KVM', tags: [{ key: 'course', value: 'os' }] }

const answers = {
  listTemplates: { template: [template] },
  listSnapshotPolicies: { snapshotpolicy: [policy] },
  listVirtualMachines: { virtualmachine: [instance] },
  listVirtualMachinesMetrics: { virtualmachine: [instance] },
  listVolumes: { volume: [volume] },
  listVolumesMetrics: { volume: [volume] },
  listSnapshots: { snapshot: [snapshot] },
  listEvents: { event: [logged] },
  listZones: { zone: [{ id: 'z-1', name: 'zone' }] },
  listTags: { tag: [{ key: 'ssh.account.debian', value: 'alice,bob', resourceid: 'vm-1', resourcetype: 'UserVm' }, { key: 'course', value: 'os', resourceid: 'vm-1', resourcetype: 'UserVm' }] }
}

const answer = (command) => {
  if (!minimalUser.apis.includes(command)) {
    return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
  }
  const body = answers[command] ? { count: 1, ...answers[command] } : { count: 0 }
  return Promise.resolve({ [command.toLowerCase() + 'response']: body })
}

// the application makes the tab components of a page plain components; the route meta of the test does it here
const meta = (page) => ({ ...page, name: page.name, title: page.title, tabs: page.tabs?.map(tab => ({ ...tab, component: unref(tab.component) })) })

const pageRoutes = () => [
  ...[...compute.children, ...storage.children, ...image.children, event].flatMap(page => [
    { name: page.name, path: '/' + page.name, meta: meta(page), component: {} },
    { path: '/' + page.name + '/:id', meta: meta(page), component: {} }
  ]),
  { path: '/:catchAll(.*)', redirect: '/exception/404' }
]
const mounted = []

const ONE_ZONE = [{ id: 'z-1', name: 'zone' }]

const vuexStore = (metrics, roletype, storeApis = apis, zones = ONE_ZONE) => createStore({
  state: {
    app: { device: 'desktop' },
    user: { apis: storeApis, zones, info: { id: 'u-1', roletype, account: 'student', domainid: 'd-1', username: 'student' }, features: {}, customColumns: {} }
  },
  getters: {
    apis: state => state.user.apis,
    userInfo: state => state.user.info,
    features: state => state.user.features,
    project: () => ({}),
    metrics: () => metrics,
    listAllProjects: () => false,
    zones: state => state.user.zones,
    customColumns: state => state.user.customColumns,
    defaultListViewPageSize: () => 20,
    headerNotices: () => [],
    shutdownTriggered: () => false,
    maintenanceInitiated: () => false,
    darkMode: () => false,
    timezoneoffset: () => 0,
    usebrowsertimezone: () => false
  },
  mutations: { SET_HEADER_NOTICES: () => {}, SET_CUSTOM_COLUMNS: () => {}, SET_ZONES: (state, zones) => { state.user.zones = zones } },
  actions: { SetProject: () => {}, ToggleTheme: () => {}, SetCustomColumns: () => {} }
})

const openPage = async (pagePath, { metrics = false, roletype = 'User', locale = 'en', zones = ONE_ZONE } = {}) => {
  const store = require('@/store')
  store.getters.metrics = metrics
  store.getters.userInfo = { roletype, account: 'student', domainid: 'd-1' }
  store.getters.zones = zones
  const router = common.createMockRouter(pageRoutes())
  router.push(pagePath)
  await router.isReady()
  const wrapper = common.createFactory(AutogenView, {
    router,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    store: vuexStore(metrics, roletype, apis, zones),
    mocks: { $config: deployedConfig, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn(), info: jest.fn() } }
  })
  mounted.push(wrapper)
  // the tabs of a page are components loaded on demand: a page of one resource is read once its right-hand card shows
  // what its first tab holds, and the audit fails if it never does
  if (/^\/[a-z]+\/[^/?]+/.test(pagePath)) {
    await tabShows(wrapper, TAB_CONTENT[pagePath])
  }
  await flushPromises()
  await flushPromises()
  return wrapper
}

// what the first tab of the page of a resource shows, and the card on the left does not
const TAB_CONTENT = {
  '/vm/vm-1': instance.templatename,
  '/volume/v-1': volume.name,
  '/snapshot/s-1': snapshot.name,
  '/template/t-1': template.name
}

const tabShows = async (wrapper, content) => {
  expect(content).toBeTruthy()
  for (let attempt = 0; attempt < 100; attempt++) {
    await flushPromises()
    if (wrapper.find('.ant-col-lg-17').exists() && wrapper.find('.ant-col-lg-17').text().includes(content)) {
      return
    }
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  throw new Error(`the tab of the page never showed "${content}"; the card holds "${wrapper.find('.ant-col-lg-17').text()}"`)
}

// what a student must never be shown for an instance: the hostname and the labels of the fields that hold it, and the
// tags, which hold the guest lists under keys of their own
const FORBIDDEN = {
  en: [HOSTNAME, en['label.vmhostname'], en['label.instancename'], en['label.displayname'], en['label.tags'], 'ssh.account.', 'course = os'],
  ko_KR: [HOSTNAME, ko['label.vmhostname'], ko['label.instancename'], ko['label.displayname'], ko['label.tags'], 'ssh.account.', 'course = os']
}

const PAGES = [
  ['/vm', 'the list of instances', { metrics: false }, 'lab'],
  ['/vm', 'the list of instances with metrics', { metrics: true }, 'lab'],
  ['/vm/vm-1', 'the page of an instance', { metrics: false }, 'lab'],
  ['/volume', 'the list of volumes', { metrics: false }, 'lab'],
  ['/volume/v-1', 'the page of a volume', { metrics: false }, 'lab'],
  ['/snapshot/s-1', 'the page of a snapshot', { metrics: false }, 'ROOT-34-snapshot-0'],
  ['/event', 'the list of events', { metrics: false }, 'lab']
]

jest.setTimeout(60000)

describe('what a student is shown of the name of an instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    window.ResizeObserver = jest.fn().mockImplementation(() => ({ observe: jest.fn(), unobserve: jest.fn(), disconnect: jest.fn() }))
    for (const fn of [getAPI, postAPI, callAPI]) {
      fn.mockImplementation(answer)
    }
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.innerHTML = ''
    jest.restoreAllMocks()
  })

  for (const locale of ['en', 'ko_KR']) {
    for (const [pagePath, label, options, expected] of PAGES) {
      it(`shows ${label} (${locale}) without the hostname or the label of a field that holds it`, async () => {
        const wrapper = await openPage(pagePath, { ...options, locale })
        const shown = wrapper.text() + document.body.textContent
        expect(shown).toContain(expected)
        for (const forbidden of FORBIDDEN[locale]) {
          expect(shown).not.toContain(forbidden)
        }
      })
    }
  }

  it('shows no tag filter in the search of the lists of instances, volumes and snapshots', async () => {
    for (const pagePath of ['/vm', '/volume', '/snapshot']) {
      const wrapper = await openPage(pagePath)
      expect(wrapper.vm.searchFilters).not.toContain('tags')
      expect(wrapper.vm.searchFilters).toContain(pagePath === '/vm' ? 'keyword' : 'name')
    }
  })

  describe('the zone filter of the search, which follows the zones of the session', () => {
    const TWO_ZONES = [{ id: 'z-1', name: 'zone' }, { id: 'z-2', name: 'other' }]
    const land = (wrapper, zones) => {
      require('@/store').getters.zones = zones
      wrapper.vm.$store.commit('SET_ZONES', zones)
      return flushPromises()
    }

    afterEach(() => {
      require('@/store').getters.zones = ONE_ZONE
    })

    it.each(['/vm', '/volume', '/snapshot', '/template'])('is offered by the list at %s once the zones are loaded, and not before', async (pagePath) => {
      const wrapper = await openPage(pagePath, { zones: {} })
      expect(wrapper.vm.searchFilters).not.toContain('zoneid')
      await land(wrapper, TWO_ZONES)
      expect(wrapper.vm.searchFilters).toContain('zoneid')
    })

    it('is not offered when the zones that are loaded are one', async () => {
      const wrapper = await openPage('/vm', { zones: {} })
      await land(wrapper, ONE_ZONE)
      expect(wrapper.vm.searchFilters).not.toContain('zoneid')
    })

    it('goes when the zones come back to one', async () => {
      const wrapper = await openPage('/vm', { zones: TWO_ZONES })
      expect(wrapper.vm.searchFilters).toContain('zoneid')
      await land(wrapper, ONE_ZONE)
      expect(wrapper.vm.searchFilters).not.toContain('zoneid')
    })
  })

  it('sorts the instance column of the list of volumes by the visible name, and by the hostname where there is none', async () => {
    const column = (await openPage('/volume')).vm.columns.find(entry => entry.key === 'vmname')
    expect(column.sorter({ vmname: 'i-2-1-VM', vmdisplayname: 'b' }, { vmname: 'i-2-2-VM', vmdisplayname: 'a' })).toBeGreaterThan(0)
    expect(column.sorter({ vmname: 'i-2-1-VM', vmdisplayname: 'a' }, { vmname: 'i-2-2-VM', vmdisplayname: 'b' })).toBeLessThan(0)
    expect(column.sorter({ vmname: 'old-b' }, { vmname: 'old-a' })).toBeGreaterThan(0)
  })

  describe.each(['en', 'ko_KR'])('an image reached by its address (%s)', (locale) => {
    const labels = locale === 'en' ? en : ko

    it('has no tags, no per-zone copies and no zone or tag filter in a cloud with one zone', async () => {
      const list = await openPage('/template', { locale })
      expect(list.vm.searchFilters).not.toContain('tags')
      expect(list.vm.searchFilters).not.toContain('zoneid')
      expect(list.vm.searchFilters).toContain('name')

      const page = await openPage('/template/t-1', { locale })
      expect(page.find('.ant-col-lg-17 .ant-tabs-nav').text()).toContain(labels['label.details'])
      expect(page.find('.ant-col-lg-17 .ant-tabs-nav').text()).not.toContain(labels['label.zones'])
      for (const hidden of [labels['label.tags'], 'course = os', 'course=os']) {
        expect(page.text()).not.toContain(hidden)
      }
    })
  })

  describe.each(['en', 'ko_KR'])('the recurring snapshots of a volume (%s)', (locale) => {
    const dialogParams = ['name', 'zoneid', 'zoneids', 'usestoragereplication', 'quiescevm', 'asyncbackup', 'intervaltype', 'maxsnaps', 'schedule', 'timezone']
      .map(name => ({ name, description: name }))
    const openDialog = async () => {
      const router = common.createMockRouter(pageRoutes())
      router.push('/volume/v-1')
      await router.isReady()
      const wrapper = common.createFactory(RecurringSnapshotVolume, {
        router,
        i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
        store: vuexStore(false, 'User', Object.fromEntries(minimalUser.apis.map(name => [name, { params: dialogParams, response: [] }]))),
        props: { resource: volume },
        provide: { parentFetchData: jest.fn() },
        mocks: { $config: deployedConfig, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn(), info: jest.fn() } }
      })
      mounted.push(wrapper)
      await flushPromises()
      await flushPromises()
      return wrapper
    }
    const labels = locale === 'en' ? en : ko
    const SECRET = [labels['label.tags'], 'ssh.account.', 'course=os', 'course = os']

    it('shows the schedule form without tags or zones, in a cloud with one zone', async () => {
      const wrapper = await openDialog()
      expect(wrapper.text()).toContain(labels['label.intervaltype'])
      for (const hidden of [...SECRET, labels['label.zones'], labels['label.usestoragereplication'], labels['message.snapshot.additional.zones'].replace('%x', volume.zonename)]) {
        expect(wrapper.text()).not.toContain(hidden)
      }
    })

    it('lists its policies without their tags or zones, in a cloud with one zone', async () => {
      const wrapper = await openDialog()
      const tabs = wrapper.findAll('.ant-tabs-tab')
      await tabs[1].trigger('click')
      await flushPromises()
      expect(wrapper.find('.list-schedule').exists()).toBe(true)
      expect(wrapper.find('.list-schedule').text()).toContain(labels['label.keep'])
      for (const hidden of [...SECRET, labels['label.zones'], 'elsewhere']) {
        expect(wrapper.find('.list-schedule').text()).not.toContain(hidden)
      }
    })
  })

  it('shows an instance by its visible name and its ID, labelled "Name"', async () => {
    const wrapper = await openPage('/vm/vm-1')
    const text = wrapper.text()
    expect(text).toContain('lab')
    expect(text).toContain('vm-1')
    expect(text).toContain(en['label.name'])
    expect(text).toContain(en['label.id'])
  })

  it('labels the visible name "이름" for a Korean student', async () => {
    const wrapper = await openPage('/vm/vm-1', { locale: 'ko_KR' })
    expect(wrapper.text()).toContain(ko['label.name'])
    expect(ko['label.name']).toBe('이름')
  })

  it('shows the volume\'s instance by its visible name', async () => {
    const list = await openPage('/volume')
    expect(list.text()).toContain('lab')
    const detail = await openPage('/volume/v-1')
    expect(detail.text()).toContain('lab')
  })

  it('shows an instance without a visible name by its hostname', async () => {
    answers.listVirtualMachines = answers.listVirtualMachinesMetrics = { virtualmachine: [{ ...instance, displayname: '' }] }
    try {
      const list = await openPage('/vm')
      expect(list.text()).toContain(HOSTNAME)
      const detail = await openPage('/vm/vm-1')
      expect(detail.text()).toContain(HOSTNAME)
      expect(detail.text()).not.toContain(en['label.vmhostname'])
    } finally {
      answers.listVirtualMachines = answers.listVirtualMachinesMetrics = { virtualmachine: [instance] }
    }
  })
})

describe('what an administrator is shown of the names and the tags of an instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    window.ResizeObserver = jest.fn().mockImplementation(() => ({ observe: jest.fn(), unobserve: jest.fn(), disconnect: jest.fn() }))
    for (const fn of [getAPI, postAPI, callAPI]) {
      fn.mockImplementation(answer)
    }
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.innerHTML = ''
    jest.restoreAllMocks()
  })

  it('shows both on the page of an instance: the name, and the hostname labelled "Hostname"', async () => {
    const wrapper = await openPage('/vm/vm-1', { roletype: 'Admin' })
    const text = wrapper.text()
    expect(text).toContain('lab')
    expect(text).toContain(HOSTNAME)
    expect(text).toContain(en['label.vmhostname'])
    expect(en['label.vmhostname']).toBe('Hostname')
    expect(ko['label.vmhostname']).toBe('호스트 이름')
    expect(text).not.toContain(en['label.displayname'])
  })

  it('keeps the tags on the page of an instance, the page of a volume, and in the search', async () => {
    const instancePage = await openPage('/vm/vm-1', { roletype: 'Admin' })
    expect(instancePage.text()).toContain(en['label.tags'])
    expect(instancePage.text()).toContain('ssh.account.debian = alice,bob')
    const volumePage = await openPage('/volume/v-1', { roletype: 'Admin' })
    expect(volumePage.text()).toContain(en['label.tags'])
    for (const pagePath of ['/vm', '/volume', '/snapshot']) {
      expect((await openPage(pagePath, { roletype: 'Admin' })).vm.searchFilters).toContain('tags')
    }
  })

  it('shows both in the list: the name, and a column of the hostname', async () => {
    const wrapper = await openPage('/vm', { roletype: 'Admin' })
    const text = wrapper.text()
    expect(text).toContain('lab')
    expect(text).toContain(HOSTNAME)
    expect(wrapper.findAll('th').map(cell => cell.text())).toEqual(expect.arrayContaining([en['label.name'], en['label.vmhostname']]))
  })
})
