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

const scheme = {
  id: 'vm-1',
  name: 'i-2-34-VM',
  displayname: 'Lab',
  sshname: 'Lab',
  zoneid: 'z-1',
  serviceofferingid: 'so-1',
  templateid: 't-1',
  details: {},
  nic: [{ id: 'nic-1', networkid: 'n-1', isdefault: true }]
}
const legacy = { ...scheme, name: 'my-old-vm', displayname: 'my old vm', sshname: undefined }

const answers = {
  listZones: { listzonesresponse: { zone: [{ id: 'z-1', securitygroupsenabled: false }] } },
  listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [{ id: 'so-1' }] } },
  listTemplates: { listtemplatesresponse: { template: [{ id: 't-1' }] } },
  listSecurityGroups: { listsecuritygroupsresponse: { securitygroup: [] } },
  listOsTypes: { listostypesresponse: { ostype: [] } },
  listInstanceGroups: { listinstancegroupsresponse: { instancegroup: [] } },
  listVirtualMachines: { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', userdata: '' }] } }
}

const open = async ({ resource = scheme, roletype = 'User', locale = 'en' } = {}) => {
  getAPI.mockImplementation((command) => Promise.resolve(answers[command] || {}))
  postAPI.mockImplementation(() => Promise.resolve({}))
  const store = common.createMockStore({ user: { apis: apiMap(minimalUser.apis), info: { roletype, domainid: 'd-1', account: 'student' }, features: {} } })
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

const inputValues = (wrapper) => wrapper.findAll('input').map(input => input.element.value)
const submit = async (wrapper) => {
  wrapper.vm.handleSubmit()
  await flushPromises()
  await flushPromises()
}
const updateCall = () => postAPI.mock.calls.find(call => call[0] === 'updateVirtualMachine')

jest.setTimeout(30000)

describe('the Edit dialog and the names of an instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  describe('the hostname field', () => {
    it('is the root administrator\'s', async () => {
      const admin = await open({ roletype: 'Admin' })
      expect(admin.vm.canEditHostname).toBe(true)
      expect(inputValues(admin)).toEqual(expect.arrayContaining(['i-2-34-VM', 'Lab']))
    })

    for (const roletype of ['DomainAdmin', 'ResourceAdmin', 'User']) {
      it(`is not the ${roletype}'s`, async () => {
        const other = await open({ roletype })
        expect(other.vm.canEditHostname).toBe(false)
        expect(inputValues(other)).toContain('Lab')
        expect(inputValues(other)).not.toContain('i-2-34-VM')
      })
    }

    it('sends no name for a role that cannot see it, and sends the name of the root administrator only when it was changed', async () => {
      const student = await open()
      student.vm.form.displayname = 'Lab2'
      await submit(student)
      expect(updateCall()[1].name).toBeUndefined()

      jest.clearAllMocks()
      const admin = await open({ roletype: 'Admin' })
      admin.vm.form.displayname = 'Lab2'
      await submit(admin)
      expect(updateCall()[1].name).toBeUndefined()

      jest.clearAllMocks()
      const renamed = await open({ roletype: 'Admin' })
      renamed.vm.form.name = 'i-2-35-VM'
      await submit(renamed)
      expect(updateCall()[1].name).toBe('i-2-35-VM')
    })
  })

  describe('the visible name field', () => {
    it('sends the new displayname, and none when it was not changed, as an instance named before the rule keeps its name', async () => {
      const wrapper = await open()
      wrapper.vm.form.displayname = 'Lab-2'
      await submit(wrapper)
      expect(updateCall()[1].displayname).toBe('Lab-2')

      jest.clearAllMocks()
      const unchanged = await open({ resource: legacy })
      await submit(unchanged)
      expect(updateCall()).toBeDefined()
      expect(updateCall()[1].displayname).toBeUndefined()
    })

    it('refuses a name that breaks the rule, before anything is sent, and states the rule', async () => {
      const wrapper = await open()
      for (const bad of ['', 'my vm', '-lab', 'lab-', 'x'.repeat(31), 'lab_1', '인스턴스']) {
        wrapper.vm.form.displayname = bad
        await submit(wrapper)
        expect(postAPI).not.toHaveBeenCalled()
        expect(wrapper.find('.ant-form-item-explain-error').text()).toBe(en['message.error.instance.name.rule'])
      }
    })

    it('accepts a name of 1 to 30 letters, digits and hyphens', async () => {
      for (const good of ['a', 'VM-3', 'x'.repeat(30)]) {
        jest.clearAllMocks()
        const wrapper = await open()
        wrapper.vm.form.displayname = good
        await submit(wrapper)
        expect(updateCall()[1].displayname).toBe(good)
      }
    })

    it('lets an instance named before the rule be renamed only to a name that follows it', async () => {
      const wrapper = await open({ resource: legacy })
      wrapper.vm.form.displayname = 'still bad'
      await submit(wrapper)
      expect(postAPI).not.toHaveBeenCalled()
      wrapper.vm.form.displayname = 'fixed'
      await submit(wrapper)
      expect(updateCall()[1].displayname).toBe('fixed')
    })
  })

  describe('the note under the visible name', () => {
    it('is there for an instance with an sshname: the SSH command follows the name, the hostname inside does not, and how to change it as code', async () => {
      const wrapper = await open()
      const note = wrapper.find('.instance-rename-help')
      expect(note.exists()).toBe(true)
      expect(note.text()).toContain('Renaming also changes the SSH command.')
      expect(note.text()).toContain('The hostname inside the instance stays as it is')
      expect(note.find('code').element.tagName).toBe('CODE')
      expect(note.find('code').text()).toBe('sudo hostnamectl set-hostname <new name>')
    })

    it('is in Korean for a Korean page, with the command as code and the new name placeholder', async () => {
      const wrapper = await open({ locale: 'ko_KR' })
      const note = wrapper.find('.instance-rename-help')
      expect(note.text()).toContain('이름을 바꾸면 SSH 명령도 새 이름으로 바뀝니다.')
      expect(note.text()).toContain('인스턴스 안의 호스트 이름은 그대로이며')
      expect(note.find('code').text()).toBe('sudo hostnamectl set-hostname <새 이름>')
    })

    it('is not there for an instance without an sshname', async () => {
      const wrapper = await open({ resource: legacy })
      expect(wrapper.find('.instance-rename-help').exists()).toBe(false)
    })
  })
})
