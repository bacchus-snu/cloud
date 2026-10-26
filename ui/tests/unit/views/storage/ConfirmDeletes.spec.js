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
import compute from '@/config/section/compute'
import { CAPABILITY_WAIT_MS } from '@/utils/placement'
import network from '@/config/section/network'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

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
  vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: { ...require('../../../../public/config.json') } }
}))

const en = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))
const apis = Object.fromEntries(minimalUser.apis.map(name => [name, { params: [], response: [] }]))
for (const name of ['destroyVolume', 'deleteVolume', 'deleteSnapshot', 'expungeVirtualMachine']) {
  apis[name] = { params: [{ name: 'id', type: 'uuid' }], response: [] }
}
apis.destroyVolume.params.push({ name: 'expunge', type: 'boolean' })
apis.updateVolume = { params: [{ name: 'id', type: 'uuid' }, { name: 'name', type: 'string' }, { name: 'deleteprotection', type: 'boolean' }], response: [] }

const volumes = [
  { id: 'v-1', name: 'data', type: 'DATADISK', state: 'Ready', size: 21474836480, zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1' },
  { id: 'v-2', name: 'scratch', type: 'DATADISK', state: 'Allocated', size: 21474836480, zoneid: 'z-1', zonename: 'zone', account: 'student', domainid: 'd-1' }
]
const snapshots = [
  { id: 's-1', name: 'data-snapshot-0', state: 'BackedUp', volumeid: 'v-1', volumename: 'data', zoneid: 'z-1', account: 'student', domainid: 'd-1' },
  { id: 's-2', name: 'data-snapshot-1', state: 'BackedUp', volumeid: 'v-1', volumename: 'data', zoneid: 'z-1', account: 'student', domainid: 'd-1' },
  { id: 's-3', name: 'data-snapshot-2', state: 'BackedUp', volumeid: 'v-1', volumename: 'data', zoneid: 'z-1', account: 'student', domainid: 'd-1' }
]
const objects = { volume: volumes, snapshot: snapshots, tag: [], icon: [], account: [], event: [], zone: [{ id: 'z-1', name: 'zone' }] }
const objectOf = { listVolumes: 'volume', listVolumesMetrics: 'volume', listSnapshots: 'snapshot', listTags: 'tag', listResourceIcon: 'icon', listAccounts: 'account', listEvents: 'event', listZones: 'zone' }

// what the server says now, which the session may not know any more
let serverCapability = {}
let capabilityFails = false
let capabilityHangs = false

const answer = (command) => {
  if (command === 'listCapabilities') {
    if (capabilityHangs) {
      return new Promise(() => {})
    }
    return capabilityFails
      ? Promise.reject(new Error('down'))
      : Promise.resolve({ listcapabilitiesresponse: { capability: serverCapability } })
  }
  const object = objectOf[command]
  const body = object ? { count: objects[object].length, [object]: objects[object] } : { count: 0 }
  return Promise.resolve({ [command.toLowerCase() + 'response']: body })
}

const pageRoutes = () => [
  ...storage.children.flatMap(page => [
    { name: page.name, path: '/' + page.name, meta: { ...page, name: page.name, title: page.title }, component: {} },
    { path: '/' + page.name + '/:id', meta: { ...page, name: page.name, title: page.title }, component: {} }
  ]),
  { path: '/:catchAll(.*)', redirect: '/exception/404' }
]
const mounted = []

const vuexStore = (features, roletype) => createStore({
  state: {
    app: { device: 'desktop' },
    user: { apis, info: { id: 'u-1', roletype, account: 'student', domainid: 'd-1' }, features, customColumns: {} }
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

const openPage = async (route, { features = {}, locale = 'en', roletype = 'User' } = {}) => {
  require('@/store').getters.features = features
  require('@/store').getters.apis = apis
  const router = common.createMockRouter(pageRoutes())
  router.push(route)
  await router.isReady()
  const wrapper = common.createFactory(AutogenView, {
    router,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    store: vuexStore(features, roletype),
    mocks: { $config: require('@/vue-app').vueProps.$config, $notifyError: jest.fn(), $message: { success: jest.fn(), error: jest.fn(), info: jest.fn() } }
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

const action = (page, api, label) => storage.children.find(child => child.name === page).actions.find(a => a.api === api && (!label || a.label === label))

const modal = () => document.body
const field = () => document.querySelector('.ant-modal .confirm-input input')
const okButton = () => document.querySelector('.ant-modal .action-button .ant-btn-primary')
const typeInto = async (text) => {
  field().value = text
  field().dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}
const submit = async (wrapper) => {
  await wrapper.vm.handleSubmit({ preventDefault () {} })
  await flushPromises()
}
const posted = (command) => postAPI.mock.calls.filter(call => call[0] === command).map(call => call[1])

jest.setTimeout(60000)

describe('the volume and snapshot dialogs ask for what is to be deleted', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    serverCapability = {}
    capabilityFails = false
    capabilityHangs = false
    jest.spyOn(console, 'log').mockImplementation(() => {})
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    window.ResizeObserver = jest.fn().mockImplementation(() => ({ observe: jest.fn(), unobserve: jest.fn(), disconnect: jest.fn() }))
    for (const fn of [getAPI, callAPI]) {
      fn.mockImplementation(answer)
    }
    postAPI.mockImplementation(() => Promise.resolve({ destroyvolumeresponse: { jobid: 'job-1' } }))
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.innerHTML = ''
    jest.restoreAllMocks()
  })

  it('destroying a volume asks for its name and keeps the button disabled until it is typed', async () => {
    const wrapper = await openPage('/volume/v-1')
    wrapper.vm.execAction({ ...action('volume', 'destroyVolume'), resource: volumes[0] }, false)
    await flushPromises()

    expect(modal().querySelector('.confirm-expected').textContent).toBe('data')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    expect(wrapper.vm.isSubmitDisabled).toBe(true)
    for (const wrong of ['dat', 'Data', 'DATA', 'data2', 'scratch']) {
      await typeInto(wrong)
      expect(okButton().hasAttribute('disabled')).toBe(true)
    }
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto('data')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    expect(wrapper.vm.isSubmitDisabled).toBe(false)
    await submit(wrapper)
    expect(posted('destroyVolume')).toEqual([{ id: 'v-1' }])
  })

  it('takes the name when it is pasted, with the whitespace the clipboard may carry', async () => {
    const wrapper = await openPage('/volume/v-1')
    wrapper.vm.execAction({ ...action('volume', 'destroyVolume'), resource: volumes[0] }, false)
    await flushPromises()
    const paste = new Event('paste', { bubbles: true, cancelable: true })
    field().dispatchEvent(paste)
    expect(paste.defaultPrevented).toBe(false)
    field().value = '  data \n'
    field().dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertFromPaste' }))
    await flushPromises()
    expect(okButton().hasAttribute('disabled')).toBe(false)
    okButton().click()
    await flushPromises()
    expect(posted('destroyVolume')).toEqual([{ id: 'v-1' }])
  })

  it('deleting a volume asks for its name too', async () => {
    const wrapper = await openPage('/volume/v-2')
    wrapper.vm.execAction({ ...action('volume', 'deleteVolume'), resource: volumes[1] }, false)
    await flushPromises()
    expect(modal().querySelector('.confirm-expected').textContent).toBe('scratch')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await typeInto('scratch')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    await submit(wrapper)
    expect(posted('deleteVolume')).toEqual([{ id: 'v-2' }])
  })

  it('deleting a snapshot asks for its name and says that it is deleted for good', async () => {
    const wrapper = await openPage('/snapshot/s-1')
    wrapper.vm.execAction({ ...action('snapshot', 'deleteSnapshot'), resource: snapshots[0] }, false)
    await flushPromises()
    expect(modal().querySelector('.confirm-expected').textContent).toBe('data-snapshot-0')
    expect(modal().querySelector('.ant-modal .ant-alert').textContent).toContain(en['message.action.delete.snapshot'])
    expect(en['message.action.delete.snapshot']).toMatch(/for good/)
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await typeInto('data-snapshot-1')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await typeInto('data-snapshot-0')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    await submit(wrapper)
    expect(posted('deleteSnapshot')).toEqual([{ id: 's-1' }])
  })

  it('deleting many volumes asks for their number', async () => {
    postAPI.mockImplementation(() => Promise.resolve({ deletevolumeresponse: { jobid: 'job-1' } }))
    const wrapper = await openPage('/volume')
    wrapper.vm.onRowSelectionChange(['v-1', 'v-2'])
    wrapper.vm.execAction({ ...action('volume', 'deleteVolume'), resource: undefined }, true)
    await flushPromises()

    expect(modal().querySelector('.confirm-input .confirm-prompt').textContent).toBe(en['message.confirm.type.count'].replace('{count}', '2'))
    expect(okButton().hasAttribute('disabled')).toBe(true)
    for (const wrong of ['1', '3', '22', 'data']) {
      await typeInto(wrong)
      expect(okButton().hasAttribute('disabled')).toBe(true)
    }
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto(' 2 ')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    await submit(wrapper)
    expect(posted('deleteVolume')).toEqual([{ id: 'v-1' }, { id: 'v-2' }])
  })

  it('deleting many snapshots asks for their number', async () => {
    postAPI.mockImplementation(() => Promise.resolve({ deletesnapshotresponse: { jobid: 'job-1' } }))
    const wrapper = await openPage('/snapshot')
    wrapper.vm.onRowSelectionChange(['s-1', 's-2', 's-3'])
    wrapper.vm.execAction({ ...action('snapshot', 'deleteSnapshot'), resource: undefined }, true)
    await flushPromises()

    expect(modal().querySelector('.confirm-input .confirm-prompt').textContent).toBe(en['message.confirm.type.count'].replace('{count}', '3'))
    await typeInto('2')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()
    await typeInto('3')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    await submit(wrapper)
    expect(posted('deleteSnapshot').map(params => params.id)).toEqual(['s-1', 's-2', 's-3'])
  })

  it('starts empty each time a dialog opens', async () => {
    const wrapper = await openPage('/volume/v-1')
    wrapper.vm.execAction({ ...action('volume', 'destroyVolume'), resource: volumes[0] }, false)
    await flushPromises()
    await typeInto('data')
    wrapper.vm.closeAction()
    await flushPromises()
    wrapper.vm.execAction({ ...action('volume', 'destroyVolume'), resource: volumes[0] }, false)
    await flushPromises()
    expect(field().value).toBe('')
    expect(okButton().hasAttribute('disabled')).toBe(true)
  })

  const alertText = () => modal().querySelector('.ant-modal .ant-alert').textContent
  const dialogOf = async (route, api, resource, options) => {
    const wrapper = await openPage(route, options)
    wrapper.vm.execAction({ ...action('volume', api), resource }, false)
    await flushPromises()
    return wrapper
  }

  it('words the volume dialogs as the cloud keeps destroyed volumes when the server does not say or says false', async () => {
    for (const capability of [{}, { expungeondestroy: false }]) {
      serverCapability = capability
      await dialogOf('/volume/v-1', 'destroyVolume', volumes[0])
      expect(alertText()).toContain(en['message.action.destroy.volume'])
      expect(alertText()).not.toContain(en['message.action.destroy.volume.now'])
      document.body.innerHTML = ''
      await dialogOf('/volume/v-2', 'deleteVolume', volumes[1])
      expect(alertText()).toContain(en['message.action.delete.volume'])
      expect(alertText()).not.toContain(en['message.action.delete.volume.now'])
      document.body.innerHTML = ''
    }
  })

  it('says that a volume is deleted at once and cannot be recovered when the server says so', async () => {
    serverCapability = { expungeondestroy: true }
    await dialogOf('/volume/v-1', 'destroyVolume', volumes[0])
    expect(alertText()).toContain(en['message.action.destroy.volume.now'])
    expect(modal().querySelector('.confirm-expected').textContent).toBe('data')
    document.body.innerHTML = ''
    await dialogOf('/volume/v-2', 'deleteVolume', volumes[1])
    expect(alertText()).toContain(en['message.action.delete.volume.now'])
  })

  it('goes by what the server says when the dialog opens, not by what the session was told at login', async () => {
    serverCapability = { expungeondestroy: true }
    await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { features: { expungeondestroy: false } })
    expect(alertText()).toContain(en['message.action.destroy.volume.now'])
    document.body.innerHTML = ''

    serverCapability = { expungeondestroy: false }
    await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { features: { expungeondestroy: true } })
    expect(alertText()).toContain(en['message.action.destroy.volume'])
    expect(alertText()).not.toContain(en['message.action.destroy.volume.now'])
  })

  it('falls back to the session when the server cannot be asked', async () => {
    capabilityFails = true
    await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { features: { expungeondestroy: true } })
    expect(alertText()).toContain(en['message.action.destroy.volume.now'])
  })

  it('opens at once with the session\'s value when the server does not answer in time', async () => {
    capabilityHangs = true
    const wrapper = await openPage('/volume/v-1', { features: { expungeondestroy: true } })
    wrapper.vm.execAction({ ...action('volume', 'destroyVolume'), resource: volumes[0] }, false)
    await new Promise(resolve => setTimeout(resolve, CAPABILITY_WAIT_MS + 300))
    await flushPromises()
    expect(alertText()).toContain(en['message.action.destroy.volume.now'])
  })

  it('asks for the capabilities of the caller, whatever the domain of the volume, and for a selection too', async () => {
    await dialogOf('/volume/v-1', 'destroyVolume', { ...volumes[0], domainid: 'd-9' })
    expect(getAPI.mock.calls.filter(call => call[0] === 'listCapabilities').map(call => call[1])).toEqual([{}])
    document.body.innerHTML = ''
    getAPI.mockClear()

    const wrapper = await openPage('/volume')
    getAPI.mockClear()
    wrapper.vm.onRowSelectionChange(['v-1', 'v-2'])
    wrapper.vm.execAction({ ...action('volume', 'deleteVolume'), resource: undefined }, true)
    await flushPromises()
    expect(getAPI.mock.calls.filter(call => call[0] === 'listCapabilities').map(call => call[1])).toEqual([{}])
  })

  it('offers the stock expunge field to an administrator only while the server keeps destroyed volumes', async () => {
    serverCapability = { expungeondestroy: false }
    const kept = await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { roletype: 'Admin' })
    expect(kept.vm.currentAction.paramFields.map(field => field.name)).toEqual(['expunge'])
    document.body.innerHTML = ''
    serverCapability = { expungeondestroy: true }
    const gone = await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { roletype: 'Admin', features: { expungeondestroy: false } })
    expect(gone.vm.currentAction.paramFields).toEqual([])
  })

  it('refuses a name outside the rule in the edit form of a volume, and sends a valid one', async () => {
    const wrapper = await openPage('/volume/v-1', { roletype: 'Admin' })
    wrapper.vm.execAction({ ...action('volume', 'updateVolume', 'label.edit'), resource: volumes[0] }, false)
    await flushPromises()
    expect(wrapper.vm.form.name).toBe('data')

    wrapper.vm.form.name = 'my volume'
    await submit(wrapper)
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(en['message.error.resource.name.rule'])

    wrapper.vm.form.name = 'data-2'
    await submit(wrapper)
    await flushPromises()
    expect(posted('updateVolume')).toHaveLength(1)
    expect(posted('updateVolume')[0]).toMatchObject({ id: 'v-1', name: 'data-2' })
  })

  it('lets the edit form of a volume keep a name that broke the rule before the rule existed', async () => {
    const wrapper = await openPage('/volume/v-1', { roletype: 'Admin' })
    wrapper.vm.execAction({ ...action('volume', 'updateVolume', 'label.edit'), resource: { ...volumes[0], name: 'my data' } }, false)
    await flushPromises()
    expect(wrapper.vm.form.name).toBe('my data')
    wrapper.vm.form.deleteprotection = true
    await submit(wrapper)
    await flushPromises()
    expect(posted('updateVolume')).toHaveLength(1)
    expect(posted('updateVolume')[0]).toMatchObject({ id: 'v-1', name: 'my data', deleteprotection: true })
  })

  it('still refuses another name that breaks the rule in that edit form', async () => {
    const wrapper = await openPage('/volume/v-1', { roletype: 'Admin' })
    wrapper.vm.execAction({ ...action('volume', 'updateVolume', 'label.edit'), resource: { ...volumes[0], name: 'my data' } }, false)
    await flushPromises()
    wrapper.vm.form.name = 'my other data'
    await submit(wrapper)
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain(en['message.error.resource.name.rule'])
  })

  it('leaves the name out of the edit form of a volume when it is emptied', async () => {
    const wrapper = await openPage('/volume/v-1', { roletype: 'Admin' })
    wrapper.vm.execAction({ ...action('volume', 'updateVolume', 'label.edit'), resource: volumes[0] }, false)
    await flushPromises()
    wrapper.vm.form.name = ''
    await submit(wrapper)
    await flushPromises()
    expect(posted('updateVolume')).toHaveLength(1)
    expect(posted('updateVolume')[0]).not.toHaveProperty('name')
  })

  it('expunging a destroyed Instance asks for its visible name', async () => {
    const expunge = compute.children.find(page => page.name === 'vm').actions.find(a => a.api === 'expungeVirtualMachine')
    const wrapper = await openPage('/volume/v-1')
    wrapper.vm.execAction({ ...expunge, resource: { id: 'vm-1', name: 'i-2-34-VM', displayname: 'lab', state: 'Destroyed' } }, false)
    await flushPromises()
    expect(modal().querySelector('.confirm-expected').textContent).toBe('lab')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await submit(wrapper)
    expect(postAPI).not.toHaveBeenCalled()

    await typeInto('i-2-34-VM')
    expect(okButton().hasAttribute('disabled')).toBe(true)
    await typeInto('lab')
    expect(okButton().hasAttribute('disabled')).toBe(false)
    await submit(wrapper)
    expect(posted('expungeVirtualMachine')).toEqual([{ id: 'vm-1' }])
  })

  it('asks for the name to expunge an application Instance too', () => {
    const expunges = network.children.flatMap(page => page.actions || []).filter(a => a.api === 'expungeVirtualMachine')
    expect(expunges.length).toBeGreaterThan(0)
    for (const expunge of expunges) {
      expect(expunge.requireNameConfirmation).toBe(true)
    }
  })

  it('is worded in Korean', async () => {
    serverCapability = { expungeondestroy: true }
    await dialogOf('/volume/v-1', 'destroyVolume', volumes[0], { locale: 'ko_KR' })
    expect(modal().querySelector('.ant-modal .ant-alert').textContent).toContain(ko['message.action.destroy.volume.now'])
    expect(modal().querySelector('.confirm-prompt').textContent).toBe(ko['label.delete.confirmation'])
    for (const key of ['message.action.destroy.volume.now', 'message.action.delete.volume.now', 'message.action.delete.snapshot',
      'label.delete.confirmation', 'message.confirm.type.count']) {
      expect(ko[key]).toBeTruthy()
      expect(ko[key]).not.toBe(en[key])
    }
  })
})
