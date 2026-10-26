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
import CreateVolume from '@/views/storage/CreateVolume'
import UploadVolume from '@/views/storage/UploadVolume'
import UploadLocalVolume from '@/views/storage/UploadLocalVolume'
import CreateSnapshotFromVMSnapshot from '@/views/storage/CreateSnapshotFromVMSnapshot'
import TakeSnapshot from '@/views/storage/TakeSnapshot'
import CreateSnapshotWizard from '@/views/compute/CreateSnapshotWizard'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', domainid: 'd-1', account: 'student' }, project: {}, zones: [] }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

jest.setTimeout(30000)

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const PARAMS = ['name', 'volumeid', 'zoneid', 'diskofferingid', 'size', 'miniops', 'maxiops', 'kmskeyid', 'usestoragereplication', 'quiescevm',
  'asyncbackup', 'url', 'format', 'checksum', 'domainid', 'account'].map(name => ({ name, description: name }))
const COMMANDS = ['createVolume', 'uploadVolume', 'getUploadParamsForVolume', 'createSnapshot', 'createSnapshotFromVMSnapshot', 'listZones',
  'listDiskOfferings', 'listVolumes', 'listSnapshots', 'listVirtualMachines']
const apis = Object.fromEntries(COMMANDS.map(name => [name, { params: PARAMS }]))

const answers = {
  listZones: { listzonesresponse: { zone: [{ id: 'z-1', name: 'zone' }] } },
  listDiskOfferings: { listdiskofferingsresponse: { diskoffering: [{ id: 'do-1', name: 'small', displaytext: 'Small', iscustomized: false, suitableforvirtualmachine: true }] } },
  listVolumes: { listvolumesresponse: { volume: [{ id: 'v-1', name: 'data' }] } },
  listSnapshots: { listsnapshotsresponse: { count: 0 } }
}

const mounted = []

const open = async (component, { props = {}, path = '/volume', locale = 'en' } = {}) => {
  require('@/store').getters.apis = apis
  const answer = (command) => Promise.resolve(answers[command] || {})
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
    stubs: { OwnershipSelection: true, InfiniteScrollSelect: true }
  })
  mounted.push(wrapper)
  await flushPromises()
  await flushPromises()
  return wrapper
}

const nameErrors = async (wrapper, name) => {
  wrapper.vm.form.name = name
  return wrapper.vm.formRef.value.validateFields(['name']).then(() => [], error => error.errorFields.flatMap(field => field.errors))
}

const BAD = ['my volume', 'a/b', '데이터', 'a'.repeat(65)]
const GOOD = ['data', 'ROOT-7', 'before-upgrade.v1_x', 'a'.repeat(64)]
const rule = en['message.error.resource.name.rule']

describe('the name rule of volumes and snapshots on the forms that give a name', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'warn').mockImplementation(() => {})
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    jest.restoreAllMocks()
  })

  // a name that is required: empty is refused too, with the one message of the form
  const required = {
    'creating a volume': [() => open(CreateVolume, { props: { resource: {} } }), 'message.error.volume.name'],
    'creating a volume of an Instance': [() => open(CreateVolume, { props: { resource: { id: 'vm-1', zoneid: 'z-1' } }, path: '/vm/vm-1' }), 'message.error.volume.name'],
    'uploading a volume from an address': [() => open(UploadVolume), 'message.error.volume.name'],
    'uploading a volume from a file': [() => open(UploadLocalVolume), 'message.error.volume.name'],
    'taking a snapshot of a VM snapshot': [() => open(CreateSnapshotFromVMSnapshot, { props: { resource: { id: 'vms-1', virtualmachineid: 'vm-1' } } }), 'message.error.name']
  }
  // a name that may be left empty
  const optional = {
    'creating a volume from a snapshot': () => open(CreateVolume, { props: { resource: { id: 's-1', volumetype: 'DATADISK', zoneid: 'z-1' } }, path: '/snapshot/s-1' }),
    'taking a snapshot of a volume': () => open(TakeSnapshot, { props: { resource: { id: 'v-1', name: 'data', zoneid: 'z-1' } } }),
    'taking a snapshot of an Instance\'s volume': () => open(CreateSnapshotWizard, { props: { resource: { id: 'vm-1', hypervisor: 'KVM' } } })
  }

  for (const [label, [mount, requiredMessage]] of Object.entries(required)) {
    it(label + ' refuses a name outside the rule and an empty one, each with one message, and takes a valid one', async () => {
      const wrapper = await mount()
      for (const name of BAD) {
        expect(await nameErrors(wrapper, name)).toEqual([rule])
      }
      expect(await nameErrors(wrapper, '')).toEqual([en[requiredMessage]])
      expect(await nameErrors(wrapper, undefined)).toEqual([en[requiredMessage]])
      for (const name of GOOD) {
        expect(await nameErrors(wrapper, name)).toEqual([])
      }
    })
  }

  for (const [label, mount] of Object.entries(optional)) {
    it(label + ' refuses a name outside the rule, and takes an empty one or a valid one', async () => {
      const wrapper = await mount()
      for (const name of BAD) {
        expect(await nameErrors(wrapper, name)).toContain(rule)
      }
      for (const name of ['', undefined, ...GOOD]) {
        expect(await nameErrors(wrapper, name)).toEqual([])
      }
    })
  }

  it('says the rule in Korean to a Korean user', async () => {
    const wrapper = await open(UploadVolume, { locale: 'ko_KR' })
    expect(await nameErrors(wrapper, 'my volume')).toContain(ko['message.error.resource.name.rule'])
  })

  it('stops the submit of an upload form that has a bad name', async () => {
    const wrapper = await open(UploadVolume)
    wrapper.vm.form.name = 'my volume'
    wrapper.vm.form.url = 'http://example.org/disk.qcow2'
    wrapper.vm.form.zoneId = 'z-1'
    await wrapper.vm.handleSubmit({ preventDefault () {} })
    await flushPromises()
    expect(postAPI).not.toHaveBeenCalled()
  })
})
