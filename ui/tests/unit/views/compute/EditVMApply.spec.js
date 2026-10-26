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
import EditVM from '@/views/compute/EditVM'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/ko_KR.json'), 'utf8'))

const editParams = ['name', 'displayname', 'ostypeid', 'isdynamicallyscalable', 'haenable', 'group', 'userdata', 'deleteprotection']
  .map(name => ({ name, description: name }))
const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: name === 'updateVirtualMachine' ? editParams : [] }]))

const userData = '#cloud-config\npackages: [htop]\n'
const scheme = {
  id: 'vm-1',
  name: 'i-2-34-VM',
  displayname: 'Lab',
  sshname: 'Lab',
  state: 'Stopped',
  zoneid: 'z-1',
  serviceofferingid: 'so-1',
  templateid: 't-1',
  ostypeid: 'os-1',
  isdynamicallyscalable: true,
  deleteprotection: false,
  haenable: false,
  group: 'lab-group',
  details: {},
  nic: [{ id: 'nic-1', networkid: 'n-1', isdefault: true }]
}
const running = { ...scheme, state: 'Running' }

const answers = {
  listZones: { listzonesresponse: { zone: [{ id: 'z-1', securitygroupsenabled: false }] } },
  listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [{ id: 'so-1', offerha: true }] } },
  listTemplates: { listtemplatesresponse: { template: [{ id: 't-1' }] } },
  listSecurityGroups: { listsecuritygroupsresponse: { securitygroup: [] } },
  listOsTypes: { listostypesresponse: { ostype: [{ id: 'os-1', description: 'Debian' }, { id: 'os-2', description: 'Ubuntu' }] } },
  listInstanceGroups: { listinstancegroupsresponse: { instancegroup: [{ name: 'lab-group' }] } },
  listVirtualMachines: { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', userdata: btoa(userData) }] } }
}

const open = async ({ resource = scheme, roletype = 'User', locale = 'en', extra = [] } = {}) => {
  getAPI.mockImplementation((command) => Promise.resolve(answers[command] || {}))
  postAPI.mockImplementation(() => Promise.resolve({}))
  const store = common.createMockStore({ user: { apis: apiMap([...minimalUser.apis, ...extra]), info: { roletype, domainid: 'd-1', account: 'student' }, features: {} } })
  const wrapper = common.createFactory(EditVM, {
    store,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { action: {}, resource },
    mocks: { $notifyError: jest.fn() }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const itemOf = (wrapper, label) => wrapper.findAll('.ant-form-item').find(item => item.find('.ant-form-item-label').exists() && item.find('.ant-form-item-label').text().includes(label))
const noteOf = (wrapper, label) => itemOf(wrapper, label).find('.ant-form-item-extra').text()
const submit = async (wrapper) => {
  wrapper.vm.handleSubmit()
  await flushPromises()
  await flushPromises()
}
const sent = () => postAPI.mock.calls.find(call => call[0] === 'updateVirtualMachine')[1]

jest.setTimeout(30000)

describe('the Edit dialog says when each change applies', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  it('has no notice of a restart for the fields as a whole, for any role', async () => {
    expect(en).not.toHaveProperty(['message.restart.vm.to.update.settings'])
    expect(ko).not.toHaveProperty(['message.restart.vm.to.update.settings'])
    for (const roletype of ['User', 'DomainAdmin', 'Admin']) {
      const wrapper = await open({ roletype })
      expect(wrapper.text()).not.toMatch(/will require the Instance to be restarted/)
      expect(wrapper.find('.ant-alert').exists()).toBe(false)
    }
  })

  describe('the hostname and the fields of an administrator', () => {
    const restartNote = (wrapper) => wrapper.findAll('.instance-edit-restart-note')

    it('says of the hostname that it takes effect after the instance is stopped and started', async () => {
      const admin = await open({ roletype: 'Admin', resource: running })
      expect(noteOf(admin, en['label.vmhostname'])).toBe(en['message.vm.edit.hostname.restart'])
      expect(en['message.vm.edit.hostname.restart']).toMatch(/stopped and started/)
      expect(en['message.vm.edit.hostname.restart']).not.toBe(en['message.vm.edit.applies.now'])
      expect(noteOf(admin, en['label.name'])).toBe(en['message.vm.edit.applies.now'])
    })

    it('says it in Korean', async () => {
      const admin = await open({ roletype: 'Admin', locale: 'ko_KR' })
      expect(noteOf(admin, ko['label.vmhostname'])).toBe(ko['message.vm.edit.hostname.restart'])
      expect(ko['message.vm.edit.hostname.restart']).toBeTruthy()
      expect(ko['message.vm.edit.admin.restart']).toBeTruthy()
    })

    it('say once that the OS type, the HA flag, the group, the extra configuration and the lease may need the instance to be stopped and started', async () => {
      const admin = await open({ roletype: 'Admin', extra: ['listOsTypes', 'listInstanceGroups'] })
      expect(itemOf(admin, en['label.ostypeid'])).toBeDefined()
      expect(restartNote(admin)).toHaveLength(1)
      expect(restartNote(admin)[0].text()).toBe(en['message.vm.edit.admin.restart'])
      expect(en['message.vm.edit.admin.restart']).toMatch(/may need the instance to be stopped and started/)

      const korean = await open({ roletype: 'Admin', locale: 'ko_KR', extra: ['listOsTypes', 'listInstanceGroups'] })
      expect(restartNote(korean)[0].text()).toBe(ko['message.vm.edit.admin.restart'])
    })

    it('is not shown to a student, or to an administrator who has none of these fields', async () => {
      expect(restartNote(await open({ roletype: 'User', extra: ['listOsTypes', 'listInstanceGroups'] }))).toHaveLength(0)
      const offerings = answers.listServiceOfferings
      answers.listServiceOfferings = { listserviceofferingsresponse: { serviceoffering: [{ id: 'so-1', offerha: false }] } }
      try {
        const bare = await open({ roletype: 'Admin' })
        expect(itemOf(bare, en['label.ostypeid'])).toBeUndefined()
        expect(restartNote(bare)).toHaveLength(0)
      } finally {
        answers.listServiceOfferings = offerings
      }
    })
  })

  it('says that the name and the delete protection apply at once, in any state', async () => {
    for (const resource of [scheme, running]) {
      const wrapper = await open({ resource })
      expect(noteOf(wrapper, en['label.name'])).toBe(en['message.vm.edit.applies.now'])
      expect(noteOf(wrapper, en['label.deleteprotection'])).toBe(en['message.vm.edit.applies.now'])
    }
  })

  it('says it in Korean for a Korean page', async () => {
    const wrapper = await open({ locale: 'ko_KR' })
    expect(ko['message.vm.edit.applies.now']).toBeTruthy()
    expect(noteOf(wrapper, ko['label.name'])).toBe(ko['message.vm.edit.applies.now'])
    expect(noteOf(wrapper, ko['label.deleteprotection'])).toBe(ko['message.vm.edit.applies.now'])
  })

  describe('the user data', () => {
    const box = (wrapper) => wrapper.findAll('textarea').find(textarea => textarea.element.value === userData)

    it('can be changed while the instance is stopped, and says when it takes effect', async () => {
      const wrapper = await open()
      expect(box(wrapper).attributes('disabled')).toBeUndefined()
      expect(noteOf(wrapper, en['label.user.data'])).toBe(en['message.vm.edit.userdata.next.start'])
    })

    it('is disabled while the instance is not stopped, and says to stop it first', async () => {
      for (const state of ['Running', 'Starting', 'Stopping', 'Error']) {
        const wrapper = await open({ resource: { ...scheme, state } })
        expect(box(wrapper).attributes('disabled')).toBeDefined()
        expect(noteOf(wrapper, en['label.user.data'])).toBe(en['message.vm.edit.userdata.stopped'])
      }
    })

    it('says it in Korean: "인스턴스를 정지한 뒤 바꿀 수 있습니다"', async () => {
      const wrapper = await open({ resource: running, locale: 'ko_KR' })
      expect(ko['message.vm.edit.userdata.stopped']).toContain('인스턴스를 정지한 뒤 바꿀 수 있습니다')
      expect(noteOf(wrapper, ko['label.user.data'])).toBe(ko['message.vm.edit.userdata.stopped'])
      expect(ko['message.vm.edit.userdata.next.start']).toBeTruthy()
    })

    it('is sent only when it was changed', async () => {
      const unchanged = await open()
      await submit(unchanged)
      expect(sent()).not.toHaveProperty('userdata')

      jest.clearAllMocks()
      const changed = await open()
      changed.vm.form.userdata = userData + 'runcmd: [ls]\n'
      await submit(changed)
      expect(sent().userdata).toBeDefined()
      expect(Buffer.from(decodeURIComponent(sent().userdata), 'base64').toString('utf8')).toContain('runcmd')
    })

    it('is not sent while the instance runs, whatever the box holds', async () => {
      const wrapper = await open({ resource: running })
      wrapper.vm.form.userdata = 'other'
      await submit(wrapper)
      expect(sent()).not.toHaveProperty('userdata')
    })
  })

  describe('every other field', () => {
    it('is sent only when it was changed: nothing but the id when nothing was', async () => {
      for (const roletype of ['User', 'Admin']) {
        jest.clearAllMocks()
        const wrapper = await open({ roletype })
        await submit(wrapper)
        expect(sent()).toEqual({ id: 'vm-1' })
      }
    })

    it('sends the delete protection when it was changed', async () => {
      const wrapper = await open()
      wrapper.vm.form.deleteprotection = true
      await submit(wrapper)
      expect(sent()).toEqual({ id: 'vm-1', deleteprotection: true })
    })

    it('sends the name when it was changed', async () => {
      const wrapper = await open()
      wrapper.vm.form.displayname = 'Lab-2'
      await submit(wrapper)
      expect(sent()).toEqual({ id: 'vm-1', displayname: 'Lab-2' })
    })

    it('sends the OS type, the HA flag, the group and the scaling flag of an administrator only when they were changed', async () => {
      const wrapper = await open({ roletype: 'Admin' })
      wrapper.vm.form.ostypeid = 'os-2'
      wrapper.vm.form.haenable = true
      wrapper.vm.form.group = 'other-group'
      wrapper.vm.form.isdynamicallyscalable = false
      await submit(wrapper)
      expect(sent()).toEqual({ id: 'vm-1', ostypeid: 'os-2', haenable: true, group: 'other-group', isdynamicallyscalable: false })
    })
  })

  describe('dynamic scaling', () => {
    it('is not offered to the User role type, which this cloud never scales live', async () => {
      const wrapper = await open({ roletype: 'User' })
      expect(itemOf(wrapper, en['label.isdynamicallyscalable'])).toBeUndefined()
      wrapper.vm.form.isdynamicallyscalable = false
      await submit(wrapper)
      expect(sent()).not.toHaveProperty('isdynamicallyscalable')
    })

    it('is offered to the other role types', async () => {
      for (const roletype of ['DomainAdmin', 'Admin']) {
        const wrapper = await open({ roletype })
        expect(itemOf(wrapper, en['label.isdynamicallyscalable'])).toBeDefined()
      }
    })
  })

  describe('the names', () => {
    it('are "Name" for the visible name, and "Hostname" for the hostname of the root administrator', async () => {
      const admin = await open({ roletype: 'Admin' })
      expect(itemOf(admin, en['label.vmhostname'])).toBeDefined()
      expect(admin.findAll('.ant-form-item-label').map(label => label.text())).toEqual(expect.arrayContaining([en['label.name'], en['label.vmhostname']]))
      expect(admin.text()).not.toContain(en['label.displayname'])

      const student = await open({ roletype: 'User' })
      expect(itemOf(student, en['label.vmhostname'])).toBeUndefined()
      expect(student.text()).not.toContain(en['label.displayname'])
      expect(student.findAll('input').map(input => input.element.value)).not.toContain('i-2-34-VM')
    })
  })
})
