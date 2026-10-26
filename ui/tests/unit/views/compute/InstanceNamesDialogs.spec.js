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
import StartVirtualMachine from '@/views/compute/StartVirtualMachine'
import DestroyVM from '@/views/compute/DestroyVM'
import AttachVolume from '@/views/storage/AttachVolume'
import CreateVolume from '@/views/storage/CreateVolume'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))

const HOSTNAME = 'i-2-34-VM'
const vm = { id: 'vm-1', name: HOSTNAME, displayname: 'lab', zoneid: 'z-1', hypervisor: 'KVM', state: 'Stopped' }
const legacy = { id: 'vm-2', name: 'old-vm', zoneid: 'z-1', hypervisor: 'KVM', state: 'Stopped' }

// what a job notice, which the user reads, is made of
const noticeOf = (options) => JSON.stringify(Object.fromEntries(Object.entries(options).filter(([, value]) => typeof value !== 'function')))

jest.setTimeout(30000)

describe('the instance in the notices and the pickers of a student', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
    getAPI.mockImplementation(() => Promise.resolve({}))
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  describe('the notice of a start', () => {
    const start = async (resource) => {
      postAPI.mockImplementation(() => Promise.resolve({ startvirtualmachineresponse: { jobid: 'job-1' } }))
      const $pollJob = jest.fn()
      const store = common.createMockStore({ user: { apis: { startVirtualMachine: { params: [] } }, info: { roletype: 'User' }, features: {} } })
      const wrapper = common.createFactory(StartVirtualMachine, {
        store,
        i18n: common.createMockI18n('en', { en }),
        props: { resource },
        mocks: { $pollJob, $notifyError: jest.fn(), $notification: { warning: jest.fn(), error: jest.fn() } }
      })
      await flushPromises()
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      return $pollJob.mock.calls[0][0]
    }

    it('names the instance by its visible name', async () => {
      const notice = noticeOf(await start(vm))
      expect(notice).toContain('lab')
      expect(notice).not.toContain(HOSTNAME)
    })

    it('names an instance without a visible name by its hostname', async () => {
      expect(noticeOf(await start(legacy))).toContain('old-vm')
    })
  })

  describe('the notices of a destroy', () => {
    const open = async (props) => {
      postAPI.mockImplementation(() => Promise.resolve({ destroyvirtualmachineresponse: { jobid: 'job-1' } }))
      getAPI.mockImplementation((command) => Promise.resolve(command === 'listCapabilities'
        ? { listcapabilitiesresponse: { capability: { keeprootvolumedefault: true } } }
        : { listvolumesresponse: { volume: [] } }))
      const $pollJob = jest.fn()
      const apis = { destroyVirtualMachine: { params: [{ name: 'expunge', description: '' }, { name: 'volumeids', description: '' }] }, listCapabilities: { params: [] }, listVolumes: { params: [] } }
      const store = common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } })
      const wrapper = common.createFactory(DestroyVM, {
        store,
        i18n: common.createMockI18n('en', { en }),
        props: { action: { currentAction: { label: 'label.action.destroy.instance', message: 'message.action.destroy.instance' } }, ...props },
        provide: { parentFetchData: jest.fn() },
        mocks: { $pollJob, $notifyError: jest.fn(), $message: { info: jest.fn() }, $route: { path: '/vm' }, $router: { go: jest.fn() } },
        stubs: { BulkActionProgress: true }
      })
      await flushPromises()
      await flushPromises()
      return { wrapper, $pollJob }
    }

    it('name one instance by its visible name', async () => {
      const { wrapper, $pollJob } = await open({ resource: vm })
      expect(wrapper.find('.confirm-expected').text()).toBe('lab')
      await wrapper.find('.confirm-input input').setValue('lab')
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      const notice = noticeOf($pollJob.mock.calls[0][0])
      expect(notice).toContain('lab')
      expect(notice).not.toContain(HOSTNAME)
    })

    it('name each of many instances by its visible name', async () => {
      const { wrapper, $pollJob } = await open({ resource: {}, selectedRowKeys: ['vm-1', 'vm-2'], selectedItems: [vm, legacy] })
      await wrapper.find('.confirm-input input').setValue('2')
      await wrapper.vm.handleSubmit({ preventDefault () {} })
      await flushPromises()
      const notices = $pollJob.mock.calls.map(call => noticeOf(call[0]))
      expect(notices).toHaveLength(2)
      expect(notices[0]).toContain('lab')
      expect(notices[0]).not.toContain(HOSTNAME)
      expect(notices[1]).toContain('old-vm')
    })
  })

  describe('the picker of an instance', () => {
    // the options are made of their slot, so that the names the user reads can be read
    const stubs = { ASelect: { template: '<div class="picker"><slot /></div>' }, ASelectOption: { template: '<div class="choice"><slot /></div>' } }
    const choices = (wrapper) => wrapper.findAll('.picker .choice').map(choice => choice.text())

    it('offers an instance to attach a volume to by its visible name', async () => {
      getAPI.mockImplementation((command, params) => Promise.resolve(command === 'listVirtualMachines'
        ? { listvirtualmachinesresponse: { virtualmachine: params.state === 'Running' ? [{ ...vm, state: 'Running' }, legacy] : [] } }
        : {}))
      const store = common.createMockStore({ user: { apis: { attachVolume: { params: [{ name: 'virtualmachineid', description: '' }, { name: 'deviceid', description: '' }] }, listVirtualMachines: { params: [] } }, info: { roletype: 'User' }, features: {} } })
      const wrapper = common.createFactory(AttachVolume, {
        store,
        i18n: common.createMockI18n('en', { en }),
        props: { resource: { id: 'vol-1', name: 'scratch', zoneid: 'z-1', account: 'student', domainid: 'd-1', state: 'Ready' } },
        mocks: { $pollJob: jest.fn(), $notifyError: jest.fn(), $notification: { warning: jest.fn(), error: jest.fn() } },
        stubs
      })
      await flushPromises()
      await flushPromises()
      expect(choices(wrapper)).toEqual(expect.arrayContaining(['lab', 'old-vm']))
      expect(choices(wrapper)).not.toContain(HOSTNAME)
    })

    it('offers an instance to attach a new volume to by its visible name', async () => {
      const params = ['name', 'zoneid', 'diskofferingid', 'size', 'miniops', 'maxiops', 'kmskeyid'].map(name => ({ name, description: name }))
      const attachParams = ['virtualmachineid', 'deviceid'].map(name => ({ name, description: name }))
      const apis = { createVolume: { params }, attachVolume: { params: attachParams }, listZones: { params: [] }, listDiskOfferings: { params: [] } }
      getAPI.mockImplementation((command, query) => Promise.resolve(
        command === 'listZones' ? { listzonesresponse: { zone: [{ id: 'z-1', name: 'zone' }] } }
          : command === 'listDiskOfferings' ? { listdiskofferingsresponse: { diskoffering: [{ id: 'do-1', name: 'small', displaytext: 'Small', iscustomized: false }] } }
            : command === 'listVirtualMachines' ? { listvirtualmachinesresponse: { virtualmachine: [query.state === 'Running' ? { ...vm, state: 'Running' } : legacy] } } : {}))
      const store = common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {} } })
      const router = common.createMockRouter([{ path: '/volume', name: 'page', component: {} }])
      router.push('/volume')
      await router.isReady()
      const wrapper = common.createFactory(CreateVolume, {
        router,
        store,
        i18n: common.createMockI18n('en', { en }),
        props: { resource: {} },
        mocks: { $pollJob: jest.fn(), $notifyError: jest.fn() },
        stubs: { ...stubs, OwnershipSelection: true }
      })
      await flushPromises()
      wrapper.vm.onChangeAttachToVM()
      await flushPromises()
      expect(choices(wrapper)).toEqual(expect.arrayContaining(['lab', 'old-vm']))
      expect(choices(wrapper)).not.toContain(HOSTNAME)
    })
  })
})
