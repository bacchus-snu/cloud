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
import { createStore } from 'vuex'
import { flushPromises } from '@vue/test-utils'

import common from '../../common'
import { getAPI, postAPI, callAPI } from '@/api'
import AutogenView from '@/views/AutogenView'
import compute from '@/config/section/compute'
import account from '@/config/section/account'
import user from '@/config/section/user'
import zones from '@/config/section/zone'
import event from '@/config/section/event'
import keypair from '@/config/section/keypair'
import offering from '@/config/section/offering'
import image from '@/config/section/image'
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
const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: [], response: [] }]))

const instance = { id: 'vm-1', name: 'lab', state: 'Running', zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1', serviceofferingname: 'cpu', cpunumber: 4, memory: 4096, nic: [] }

const owner = { id: 'a-1', name: 'student', state: 'enabled', rolename: 'SNUCSE User', roletype: 'User', domainid: 'd-1', domainpath: 'ROOT', remainingstoragequota: 85, storagequotacombined: true, taggedresources: [] }
const me = { id: 'u-1', username: 'student', account: 'student', accountid: 'a-1', domainid: 'd-1', roletype: 'User', state: 'enabled' }

// the pages a student reaches by a link, and the list each is made of
const LINKED = [
  ['/zones/z-1', 'listZones', 'zone', { id: 'z-1', name: 'zone', allocationstate: 'Enabled', networktype: 'Advanced' }],
  ['/event/e-1', 'listEvents', 'event', { id: 'e-1', type: 'VM.START', description: 'started', level: 'INFO', state: 'Completed', account: 'student', created: '2026-10-21T10:00:00+0000' }],
  ['/keypair/k-1', 'listUserKeys', 'apikeypair', { id: 'k-1', name: 'key', startdate: '2026-10-21', enddate: '2027-10-21' }],
  ['/computeoffering/o-1', 'listServiceOfferings', 'serviceoffering', { id: 'o-1', name: 'cpu', displaytext: 'CPU', cpunumber: 2, cpuspeed: 2400, memory: 4096, hosttags: 'cpu' }],
  ['/diskoffering/d-1', 'listDiskOfferings', 'diskoffering', { id: 'd-1', name: 'standard', displaytext: 'Standard', disksize: 20, iscustomized: false }],
  ['/template/t-1', 'listTemplates', 'template', { id: 't-1', name: 'ubuntu', displaytext: 'Ubuntu', ostypename: 'Ubuntu', zoneid: 'z-1', zonename: 'zone', isready: true, templatetype: 'USER', format: 'QCOW2', size: 1073741824 }]
]

const answer = (command) => {
  if (!minimalUser.apis.includes(command)) {
    return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
  }
  const linked = LINKED.find(([, listing]) => listing === command)
  const body = ['listVirtualMachines', 'listVirtualMachinesMetrics'].includes(command)
    ? { count: 1, virtualmachine: [instance] }
    : command === 'listZones' ? { count: 1, zone: [{ id: 'z-1', name: 'zone' }] }
      : command === 'listAccounts' ? { count: 1, account: [owner] }
        : command === 'listUsers' ? { count: 1, user: [me] }
          : linked ? { count: 1, [linked[2]]: [linked[3]] } : { count: 0 }
  return Promise.resolve({ [command.toLowerCase() + 'response']: body })
}

const pageRoutes = () => [
  ...[...compute.children, account, user, zones, event, keypair, ...offering.children, ...image.children].flatMap(page => [
    { name: page.name, path: '/' + page.name, meta: { ...page, name: page.name, title: page.title }, component: {} },
    { path: '/' + page.name + '/:id', meta: { ...page, name: page.name, title: page.title }, component: {} }
  ]),
  // the account page links to the pages of its resources by route name
  ...['volume', 'guestnetwork', 'ssh', 'userdata'].map(name => ({ name, path: '/' + name, meta: { name }, component: {} })),
  { path: '/:catchAll(.*)', redirect: '/exception/404' }
]
const mounted = []

const vuexStore = (metrics) => createStore({
  state: {
    app: { device: 'desktop' },
    user: { apis, info: { id: 'u-1', roletype: 'User', account: 'student', domainid: 'd-1' }, features: {}, customColumns: {} }
  },
  getters: {
    apis: state => state.user.apis,
    userInfo: state => state.user.info,
    features: state => state.user.features,
    project: () => ({}),
    metrics: () => metrics,
    listAllProjects: () => false,
    zones: () => [{ id: 'z-1', name: 'zone' }],
    customColumns: state => state.user.customColumns,
    defaultListViewPageSize: () => 20,
    headerNotices: () => [],
    shutdownTriggered: () => false,
    maintenanceInitiated: () => false,
    darkMode: () => false,
    timezoneoffset: () => 0,
    usebrowsertimezone: () => false
  },
  mutations: { SET_HEADER_NOTICES: () => {}, SET_CUSTOM_COLUMNS: () => {} },
  actions: { SetProject: () => {}, ToggleTheme: () => {}, SetCustomColumns: () => {} }
})

const calledCommands = () => [...getAPI.mock.calls, ...postAPI.mock.calls, ...callAPI.mock.calls].map(call => call[0])

const openPage = async (pagePath, metrics) => {
  require('@/store').getters.metrics = metrics
  const router = common.createMockRouter(pageRoutes())
  router.push(pagePath)
  await router.isReady()
  const wrapper = common.createFactory(AutogenView, {
    router,
    i18n: common.createMockI18n('en', {}),
    store: vuexStore(metrics),
    mocks: { $config: deployedConfig, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn(), info: jest.fn() } }
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

jest.setTimeout(60000)

describe('pages of the minimal user role beyond the volumes and snapshots', () => {
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
    jest.restoreAllMocks()
  })

  for (const [name, metrics, listing] of [['plain', false, 'listVirtualMachines'], ['metrics', true, 'listVirtualMachinesMetrics']]) {
    it(`the ${name} list, its search and the search filters call only the commands the role allows`, async () => {
      const wrapper = await openPage('/vm?zoneid=11111111-1111-1111-1111-111111111111&domainid=22222222-2222-2222-2222-222222222222', metrics)
      expect(calledCommands()).toContain(listing)
      expect(wrapper.text()).toContain('lab')
      expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
    })
  }

  it('the account page with its limits, and the profile page, call only the commands the role allows', async () => {
    const limits = await openPage('/account/a-1', false)
    expect(calledCommands()).toContain('listAccounts')
    expect(callAPI).toHaveBeenCalledWith('listAccounts', expect.objectContaining({ details: 'all,storagequota' }))
    expect(limits.text()).toContain('student')

    const profile = await openPage('/accountuser/u-1', false)
    expect(calledCommands()).toContain('listUsers')
    expect(profile.text()).toContain('student')
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })

  it.each(LINKED.map(([pagePath, listing]) => [pagePath, listing]))('the page %s, opened by a link, calls only the commands the role allows', async (pagePath, listing) => {
    await openPage(pagePath, false)
    expect(calledCommands()).toContain(listing)
    expect(calledCommands().filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })
})
