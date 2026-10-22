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

import common from '../../../common'
import { getAPI, postAPI, callAPI } from '@/api'
import AutogenView from '@/views/AutogenView'
import storage from '@/config/section/storage'
import { visibleColumns } from '@/utils/uiConfig'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'
import deployment from '../../../mockData/DeploymentConfig.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn(), getBaseUrl: jest.fn(() => '') }))
jest.mock('@/store', () => ({
  getters: {
    features: {},
    apis: Object.fromEntries(require('../../../mockData/MinimalUserApis.mock.json').apis.map(name => [name, { params: [], response: [] }])),
    userInfo: { roletype: 'User', account: 'student', domainid: 'd-1' },
    zones: [{ id: 'z-1', name: 'zone', securitygroupsenabled: true }],
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
  vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: { ...require('../../../../public/config.json'), ...require('../../../mockData/DeploymentConfig.mock.json') } }
}))

const uiRoot = path.resolve(__dirname, '../../../..')
// the fork's default config with the deployment's data, which the fixture mirrors
const deployedConfig = { ...JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8')), ...deployment }
const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: [], response: [] }]))

const volume = { id: 'v-1', name: 'data', type: 'DATADISK', state: 'Ready', size: 21474836480, zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1' }
const snapshot = { id: 's-1', name: 'data-snap', state: 'BackedUp', volumeid: 'v-1', volumename: 'data', zoneid: 'z-1', account: 'student', domainid: 'd-1' }
const objects = { volume: [volume], snapshot: [snapshot], tag: [], icon: [], account: [], event: [], zone: [{ id: 'z-1', name: 'zone' }] }
const objectOf = { listVolumes: 'volume', listVolumesMetrics: 'volume', listSnapshots: 'snapshot', listTags: 'tag', listResourceIcon: 'icon', listAccounts: 'account', listEvents: 'event', listZones: 'zone' }

const answer = (command) => {
  const object = objectOf[command]
  const body = object ? { count: objects[object].length, [object]: objects[object] } : { count: 0 }
  return Promise.resolve({ [command.toLowerCase() + 'response']: body })
}

// the page is mounted directly, so the storage routes only need to match (related links name them); others resolve to 404
const pageRoutes = () => [
  ...storage.children.flatMap(page => [
    { name: page.name, path: '/' + page.name, meta: { ...page, name: page.name, title: page.title }, component: {} },
    { path: '/' + page.name + '/:id', meta: { ...page, name: page.name, title: page.title }, component: {} }
  ]),
  { path: '/:catchAll(.*)', redirect: '/exception/404' }
]
const mounted = []

const vuexStore = () => createStore({
  state: {
    app: { device: 'desktop' },
    user: { apis, info: { id: 'u-1', roletype: 'User', account: 'student', domainid: 'd-1' }, features: {}, customColumns: {} }
  },
  getters: {
    apis: state => state.user.apis,
    userInfo: state => state.user.info,
    features: state => state.user.features,
    project: () => ({}),
    metrics: () => false,
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

const openPage = async (child, path) => {
  const router = common.createMockRouter(pageRoutes())
  router.push(path)
  await router.isReady()
  const wrapper = common.createFactory(AutogenView, {
    router,
    i18n: common.createMockI18n('en', {}),
    store: vuexStore(),
    mocks: { $config: deployedConfig, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn(), info: jest.fn() } }
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

jest.setTimeout(60000)

describe('volume and snapshot pages of the minimal user role', () => {
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

  for (const name of ['volume', 'snapshot']) {
    it(`the ${name} list and a ${name} page call only the commands the role allows`, async () => {
      const child = storage.children.find(c => c.name === name)
      await openPage(child, '/' + name)
      await openPage(child, '/' + name + '/' + (name === 'volume' ? 'v-1' : 's-1'))
      const called = calledCommands()
      expect(called).toContain(name === 'volume' ? 'listVolumes' : 'listSnapshots')
      expect(called.filter(command => !minimalUser.apis.includes(command))).toEqual([])
    })
  }

  it('the volume list shows the reduced columns of the hidden block', async () => {
    const wrapper = await openPage(storage.children.find(c => c.name === 'volume'), '/volume')
    expect(wrapper.vm.columnKeys).toEqual(['name', 'state', 'size', 'type', 'vmname'])
    expect(deployedConfig.hidden.User.columns).toEqual(['volume.vmstate', 'volume.zonename'])
  })
})

describe('the columns area of the hidden block', () => {
  const config = { hidden: { User: { columns: ['volume.zonename', 'volume.vmstate', 'snapshot.created'] } } }
  const columns = ['name', 'vmstate', { field: 'created', customTitle: 'date' }, { zonename: () => 'zone' }]

  it('names a column by its route and field, for each role type apart', () => {
    expect(visibleColumns('volume', columns, 'User', config)).toEqual(['name', { field: 'created', customTitle: 'date' }])
    expect(visibleColumns('snapshot', columns, 'User', config)).toEqual(['name', 'vmstate', { zonename: expect.any(Function) }])
    expect(visibleColumns('volume', columns, 'Admin', config)).toBe(columns)
    expect(visibleColumns('volume', columns, 'User', {})).toBe(columns)
  })
})
