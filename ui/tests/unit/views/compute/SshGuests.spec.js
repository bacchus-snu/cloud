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
import { message } from 'ant-design-vue'
import eventBus from '@/config/eventBus'
import { getAPI, postAPI } from '@/api'
import SshGuestsTab from '@/views/compute/SshGuestsTab'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'
import deployment from '../../../mockData/DeploymentConfig.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', username: 'baneling100' }, zones: [], project: {} }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/ko_KR.json'), 'utf8'))

const GUEST_PATTERNS = ['[a-z][a-z0-9]{1,19}', '[a-z0-9._-]+@bacchus\\.snucse\\.org']
const config = (instanceAccess = {}) => ({ instanceAccess: { ...deployment.instanceAccess, ...instanceAccess } })

const vm = { id: 'vm-1', name: 'i-2-34-VM', displayname: 'VM-0', sshname: 'VM-0', templatename: 'debian-13-20260914', state: 'Running' }
const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))

let held
const notifyError = jest.fn()
const mounted = []
const changed = []
const onTagsChanged = (id) => changed.push(id)

const mountTab = async ({ resource = vm, tags = [], access = config(), locale = 'en' } = {}) => {
  held = tags.map(([key, value]) => ({ key, value, resourceid: resource.id, resourcetype: 'UserVm' }))
  const store = common.createMockStore({ user: { apis: apiMap(minimalUser.apis), info: { roletype: 'User', username: 'baneling100' }, features: {} } })
  const wrapper = common.createFactory(SshGuestsTab, {
    store,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { resource },
    mocks: { $config: access, $notifyError: notifyError }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const sections = (wrapper) => wrapper.findAll('.ssh-guests-section')
const accountsOf = (wrapper) => sections(wrapper).map(section => section.find('.ssh-guests-account').text())
const boxOf = (wrapper, account) => sections(wrapper).find(section => section.find('.ssh-guests-account').text() === account).find('textarea')
const sectionOf = (wrapper, account) => sections(wrapper).find(section => section.find('.ssh-guests-account').text() === account)
const type = async (wrapper, account, text) => {
  await boxOf(wrapper, account).setValue(text)
  await flushPromises()
}
const save = async (wrapper, account) => {
  await sectionOf(wrapper, account).find('button.ssh-guests-save').trigger('click')
  await flushPromises()
}
const commands = () => postAPI.mock.calls.map(call => call[0])
const tagReads = () => getAPI.mock.calls.filter(call => call[0] === 'listTags').length
const jobQueries = () => getAPI.mock.calls.filter(call => call[0] === 'queryAsyncJobResult').length
const jobOf = (command) => ({ [command.toLowerCase() + 'response']: { jobid: 'job-' + command } })

describe('the SSH guests tab of an instance', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    getAPI.mockImplementation((command) => {
      if (command === 'listTags') {
        return Promise.resolve({ listtagsresponse: { count: held.length, tag: held } })
      }
      if (command === 'queryAsyncJobResult') {
        return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 1, jobresult: {} } })
      }
      return Promise.resolve({})
    })
    postAPI.mockImplementation((command) => Promise.resolve(jobOf(command)))
    changed.length = 0
    eventBus.on('resource-tags-changed', onTagsChanged)
    jest.spyOn(message, 'success').mockImplementation(() => {})
  })

  afterEach(() => {
    eventBus.off('resource-tags-changed', onTagsChanged)
    message.success.mockRestore()
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
  })

  describe('the sections', () => {
    it('are the login account of the template family first, then each account with guest tags, by name', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.zed', 'a'], ['ssh.account.foo', 'b'], ['ssh.account.foo.1', 'c'], ['owner', 'me']] })
      expect(accountsOf(wrapper)).toEqual(['debian', 'foo', 'zed'])
      expect(boxOf(wrapper, 'foo').element.value).toBe('b, c')
      expect(boxOf(wrapper, 'zed').element.value).toBe('a')
      expect(boxOf(wrapper, 'debian').element.value).toBe('')
      expect(getAPI).toHaveBeenCalledWith('listTags', expect.objectContaining({ resourceid: 'vm-1', resourcetype: 'UserVm' }))
    })

    it('do not repeat the login account when it has tags, and keep it first', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.aaa', 'x'], ['ssh.account.debian', 'alice,bob']] })
      expect(accountsOf(wrapper)).toEqual(['debian', 'aaa'])
      expect(boxOf(wrapper, 'debian').element.value).toBe('alice, bob')
    })

    it('start with the accounts that have tags when the template family has no login account', async () => {
      const wrapper = await mountTab({ resource: { ...vm, templatename: 'windows-2025' }, tags: [['ssh.account.foo', 'a']] })
      expect(accountsOf(wrapper)).toEqual(['foo'])
    })

    it('show under each the command a guest types: a placeholder for the guest, the sshname, the account', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'a']] })
      expect(sectionOf(wrapper, 'debian').find('.ssh-guests-command').text()).toBe('ssh -p 2222 <ID>:vm-0:debian@cloud.snucse.org')
      expect(sectionOf(wrapper, 'foo').find('.ssh-guests-command').text()).toBe('ssh -p 2222 <ID>:vm-0:foo@cloud.snucse.org')
    })

    it('name the instance by its name when it has no sshname', async () => {
      const { sshname, ...older } = vm
      const wrapper = await mountTab({ resource: { ...older, name: 'My-VM' } })
      expect(sectionOf(wrapper, 'debian').find('.ssh-guests-command').text()).toBe('ssh -p 2222 <ID>:my-vm:debian@cloud.snucse.org')
    })
  })

  describe('typing a list', () => {
    it('reports two names with only whitespace between them, in the language of the page, and does not save', async () => {
      const wrapper = await mountTab()
      for (const text of ['alice bob', 'alice\nbob', 'carol, alice\tbob']) {
        await type(wrapper, 'debian', text)
        const errors = sectionOf(wrapper, 'debian').findAll('.ssh-guests-error')
        expect(errors).toHaveLength(1)
        expect(errors[0].text()).toContain(en['message.ssh.guests.separator'])
        expect(errors[0].text()).toContain('Separate names with commas')
        expect(sectionOf(wrapper, 'debian').find('button.ssh-guests-save').attributes('disabled')).toBeDefined()
      }
      await save(wrapper, 'debian')
      expect(postAPI).not.toHaveBeenCalled()
    })

    it('says it in Korean for a Korean page', async () => {
      const wrapper = await mountTab({ locale: 'ko_KR' })
      await type(wrapper, 'debian', 'alice bob')
      expect(sectionOf(wrapper, 'debian').find('.ssh-guests-error').text()).toContain('쉼표로 구분하세요')
    })

    it('names a name whose shape the deployment does not allow', async () => {
      const wrapper = await mountTab()
      await type(wrapper, 'debian', 'alice, Bob.Smith, x, whnbaek@bacchus.snucse.org')
      const errors = sectionOf(wrapper, 'debian').findAll('.ssh-guests-error').map(error => error.text())
      expect(errors).toHaveLength(2)
      expect(errors[0]).toContain('bob.smith')
      expect(errors[1]).toContain('x')
      expect(sectionOf(wrapper, 'debian').find('button.ssh-guests-save').attributes('disabled')).toBeDefined()
    })

    it('checks the shapes that the deployment ships, which the fixture carries as it does', async () => {
      expect(deployment.instanceAccess.guestPatterns).toEqual(GUEST_PATTERNS)
      const wrapper = await mountTab({ access: { instanceAccess: deployment.instanceAccess } })
      await type(wrapper, 'debian', 'alice@gmail.com, whnbaek@bacchus.snucse.org.evil, alice, whnbaek@bacchus.snucse.org')
      const errors = sectionOf(wrapper, 'debian').findAll('.ssh-guests-error').map(error => error.text())
      expect(errors).toHaveLength(2)
      expect(errors[0]).toContain('alice@gmail.com')
      expect(errors[1]).toContain('whnbaek@bacchus.snucse.org.evil')
    })

    it('checks no shape where the config lists no patterns', async () => {
      const { guestPatterns, ...withoutPatterns } = deployment.instanceAccess
      const wrapper = await mountTab({ access: { instanceAccess: withoutPatterns } })
      await type(wrapper, 'debian', 'x, Bob.Smith')
      expect(sectionOf(wrapper, 'debian').findAll('.ssh-guests-error')).toHaveLength(0)
      expect(sectionOf(wrapper, 'debian').find('button.ssh-guests-save').attributes('disabled')).toBeUndefined()
    })

    it('leaves Save off until the list differs from what the tags hold', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice,bob']] })
      const button = () => sectionOf(wrapper, 'foo').find('button.ssh-guests-save').attributes('disabled')
      expect(button()).toBeDefined()
      await type(wrapper, 'foo', 'alice,bob')
      expect(button()).toBeDefined()
      await type(wrapper, 'foo', 'alice, bob, carol')
      expect(button()).toBeUndefined()
    })
  })

  describe('saving', () => {
    it('writes a first list as the tag ssh.account.<account> on the instance: lower case, no spaces, no empties, no duplicates', async () => {
      const wrapper = await mountTab()
      await type(wrapper, 'debian', ' Alice ,\nbob,, ALICE ,')
      await save(wrapper, 'debian')
      expect(commands()).toEqual(['createTags'])
      expect(postAPI).toHaveBeenCalledWith('createTags', {
        resourceids: 'vm-1',
        resourcetype: 'UserVm',
        'tags[0].key': 'ssh.account.debian',
        'tags[0].value': 'alice,bob'
      })
    })

    it('deletes the changed key by its name and then creates it again, the delete first and finished first', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(postAPI).toHaveBeenNthCalledWith(1, 'deleteTags', { resourceids: 'vm-1', resourcetype: 'UserVm', 'tags[0].key': 'ssh.account.foo' })
      expect(postAPI).toHaveBeenNthCalledWith(2, 'createTags', {
        resourceids: 'vm-1', resourcetype: 'UserVm', 'tags[0].key': 'ssh.account.foo', 'tags[0].value': 'alice,bob'
      })
      // the delete's job is asked about before the create is sent
      const asked = getAPI.mock.invocationCallOrder[getAPI.mock.calls.findIndex(call => call[0] === 'queryAsyncJobResult')]
      expect(asked).toBeGreaterThan(postAPI.mock.invocationCallOrder[0])
      expect(asked).toBeLessThan(postAPI.mock.invocationCallOrder[1])
    })

    it('deletes every key of a list that is emptied and creates none', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'a'], ['ssh.account.foo.1', 'b'], ['ssh.account.foo.2', 'c'], ['ssh.account.bar', 'z']] })
      await type(wrapper, 'foo', ' , \n')
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags'])
      expect(postAPI).toHaveBeenCalledWith('deleteTags', {
        resourceids: 'vm-1',
        resourcetype: 'UserVm',
        'tags[0].key': 'ssh.account.foo',
        'tags[1].key': 'ssh.account.foo.1',
        'tags[2].key': 'ssh.account.foo.2'
      })
    })

    it('reads the tags again, tells the page that the tags of the instance changed, and shows the saved list as it is held now', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'foo', 'alice, Bob')
      postAPI.mockImplementation((command) => {
        if (command === 'createTags') {
          held = [{ key: 'ssh.account.foo', value: 'alice,bob' }]
        }
        return Promise.resolve(jobOf(command))
      })
      const listed = tagReads()
      await save(wrapper, 'foo')
      expect(tagReads()).toBe(listed + 1)
      expect(changed).toEqual(['vm-1'])
      expect(message.success).toHaveBeenCalledTimes(1)
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
      expect(sectionOf(wrapper, 'foo').find('button.ssh-guests-save').attributes('disabled')).toBeDefined()
    })

    it('keeps what was typed in the other sections, and an account added and not yet saved', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'debian', 'carol')
      await wrapper.find('.ssh-guests-add input').setValue('worker_1')
      await wrapper.find('.ssh-guests-add button').trigger('click')
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(boxOf(wrapper, 'debian').element.value).toBe('carol')
      expect(accountsOf(wrapper)).toEqual(['debian', 'foo', 'worker_1'])
    })

    it('keeps the typed list until the tags have been read again, and then shows what they hold', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'foo', 'alice, bob')
      let release
      const reread = new Promise(resolve => { release = resolve })
      const answer = getAPI.getMockImplementation()
      getAPI.mockImplementation((command, params) => command === 'listTags' ? reread.then(() => answer(command, params)) : answer(command, params))
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
      held = [{ key: 'ssh.account.foo', value: 'alice,bob' }]
      release()
      await flushPromises()
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
      expect(sectionOf(wrapper, 'foo').find('button.ssh-guests-save').attributes('disabled')).toBeDefined()
    })

    it('keeps the typed list when the tags cannot be read again', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'foo', 'alice, bob')
      const answer = getAPI.getMockImplementation()
      const failure = new Error('network down')
      getAPI.mockImplementation((command, params) => command === 'listTags' ? Promise.reject(failure) : answer(command, params))
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(notifyError).toHaveBeenCalledWith(failure)
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
    })

    it('shows the error of a create job that fails after the delete took effect, keeps the typed list, and tells the page', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      postAPI.mockImplementation((command) => {
        if (command === 'deleteTags') {
          held = []
        }
        return Promise.resolve(jobOf(command))
      })
      getAPI.mockImplementation((command, params) => {
        if (command === 'queryAsyncJobResult') {
          const result = params.jobId === 'job-createTags'
            ? { jobstatus: 2, jobresult: { errortext: 'tag already exists' } }
            : { jobstatus: 1, jobresult: {} }
          return Promise.resolve({ queryasyncjobresultresponse: result })
        }
        return Promise.resolve({ listtagsresponse: { tag: held } })
      })
      await type(wrapper, 'foo', 'alice, bob')
      const listed = tagReads()
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(notifyError).toHaveBeenCalledTimes(1)
      expect(notifyError.mock.calls[0][0].response.data).toEqual({ queryasyncjobresultresponse: { errortext: 'tag already exists' } })
      expect(tagReads()).toBe(listed + 1)
      expect(changed).toEqual(['vm-1'])
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
      expect(sectionOf(wrapper, 'foo').find('button.ssh-guests-save').attributes('disabled')).toBeUndefined()
    })

    it('does not tell the page when no command was accepted', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      postAPI.mockImplementation(() => Promise.reject(new Error('denied')))
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(changed).toEqual([])
    })

    it('shows the error of the API, keeps the typed list, and sends nothing after a failed delete', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      const failure = Object.assign(new Error('denied'), { response: { status: 530, data: { deletetagsresponse: { errortext: 'denied' } } } })
      postAPI.mockImplementation((command) => command === 'deleteTags' ? Promise.reject(failure) : Promise.resolve({}))
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(notifyError).toHaveBeenCalledWith(failure)
      expect(commands()).toEqual(['deleteTags'])
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
    })

    it('shows the error of a job that fails, and creates nothing after it', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      getAPI.mockImplementation((command) => {
        if (command === 'queryAsyncJobResult') {
          return Promise.resolve({ queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'tag is in use' } } })
        }
        return Promise.resolve({ listtagsresponse: { tag: held } })
      })
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags'])
      expect(notifyError).toHaveBeenCalledTimes(1)
      expect(notifyError.mock.calls[0][0].response.data).toEqual({ queryasyncjobresultresponse: { errortext: 'tag is in use' } })
    })
  })

  describe('when the page hands the tab its resource again', () => {
    it('keeps the typed lists and the added accounts when the resource is cleared and restored, and reads the tags once more', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'debian', 'carol')
      await wrapper.find('.ssh-guests-add input').setValue('worker_1')
      await wrapper.find('.ssh-guests-add button').trigger('click')
      const listed = tagReads()

      await wrapper.setProps({ resource: {} })
      await flushPromises()
      expect(tagReads()).toBe(listed)
      await wrapper.setProps({ resource: vm })
      await flushPromises()
      expect(tagReads()).toBe(listed + 1)
      expect(accountsOf(wrapper)).toEqual(['debian', 'foo', 'worker_1'])
      expect(boxOf(wrapper, 'debian').element.value).toBe('carol')
    })

    it('starts afresh for another instance: no typed list, no added account, the tags of that instance', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'debian', 'carol')
      await wrapper.find('.ssh-guests-add input').setValue('worker_1')
      await wrapper.find('.ssh-guests-add button').trigger('click')

      held = [{ key: 'ssh.account.bar', value: 'dave' }]
      await wrapper.setProps({ resource: { ...vm, id: 'vm-2' } })
      await flushPromises()
      expect(getAPI).toHaveBeenLastCalledWith('listTags', expect.objectContaining({ resourceid: 'vm-2', resourcetype: 'UserVm' }))
      expect(accountsOf(wrapper)).toEqual(['debian', 'bar'])
      expect(boxOf(wrapper, 'debian').element.value).toBe('')
      expect(boxOf(wrapper, 'bar').element.value).toBe('dave')
    })

    it('starts afresh for another instance also when the resource was cleared in between', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'debian', 'carol')
      await wrapper.find('.ssh-guests-add input').setValue('worker_1')
      await wrapper.find('.ssh-guests-add button').trigger('click')

      held = [{ key: 'ssh.account.bar', value: 'dave' }]
      await wrapper.setProps({ resource: {} })
      await flushPromises()
      await wrapper.setProps({ resource: { ...vm, id: 'vm-2' } })
      await flushPromises()
      expect(getAPI).toHaveBeenLastCalledWith('listTags', expect.objectContaining({ resourceid: 'vm-2', resourcetype: 'UserVm' }))
      expect(accountsOf(wrapper)).toEqual(['debian', 'bar'])
      expect(boxOf(wrapper, 'debian').element.value).toBe('')
    })
  })

  describe('while a tag job runs', () => {
    const MAX_WAIT_SECONDS = 130

    beforeEach(() => {
      jest.useFakeTimers()
    })

    afterEach(() => {
      jest.useRealTimers()
    })

    const second = async (times = 1) => {
      for (let i = 0; i < times; i++) {
        jest.advanceTimersByTime(1000)
        await flushPromises()
      }
    }
    // the jobs stay pending until `done.finished` is set
    const runningJobs = (done = { finished: false }) => {
      const answer = getAPI.getMockImplementation()
      getAPI.mockImplementation((command, params) => command === 'queryAsyncJobResult'
        ? Promise.resolve({ queryasyncjobresultresponse: { jobstatus: done.finished ? 1 : 0 } })
        : answer(command, params))
      return done
    }

    it('asks again every second and goes on with the create only when the delete has finished', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      const done = runningJobs()
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      expect(commands()).toEqual(['deleteTags'])
      expect(jobQueries()).toBe(1)

      await second(3)
      expect(jobQueries()).toBe(4)
      expect(commands()).toEqual(['deleteTags'])

      done.finished = true
      await second(1)
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(notifyError).not.toHaveBeenCalled()
    })

    it('gives up on a job that does not finish, shows that, creates nothing, and keeps the typed list', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      runningJobs()
      await type(wrapper, 'foo', 'alice, bob')
      const listed = tagReads()
      await save(wrapper, 'foo')
      await second(MAX_WAIT_SECONDS)

      expect(commands()).toEqual(['deleteTags'])
      expect(jobQueries()).toBeLessThan(MAX_WAIT_SECONDS)
      expect(notifyError).toHaveBeenCalledTimes(1)
      expect(notifyError.mock.calls[0][0].message).toBe(en['message.ssh.guests.timeout'])
      expect(notifyError.mock.calls[0][0].response.data).toEqual({ queryasyncjobresultresponse: { errortext: en['message.ssh.guests.timeout'] } })
      expect(tagReads()).toBe(listed + 1)
      expect(changed).toEqual(['vm-1'])
      expect(boxOf(wrapper, 'foo').element.value).toBe('alice, bob')
      const queried = jobQueries()
      await second(5)
      expect(jobQueries()).toBe(queried)
    })

    const close = (wrapper) => {
      wrapper.unmount()
      mounted.splice(mounted.indexOf(wrapper), 1)
    }

    it('finishes a save whose tab is closed while the delete runs: the create is sent, nothing is shown, the page is told', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      const done = runningJobs()
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      await second(2)
      expect(commands()).toEqual(['deleteTags'])
      const listed = tagReads()
      close(wrapper)

      await second(3)
      expect(commands()).toEqual(['deleteTags'])
      done.finished = true
      await second(1)
      expect(commands()).toEqual(['deleteTags', 'createTags'])
      expect(postAPI).toHaveBeenLastCalledWith('createTags', expect.objectContaining({ resourceids: 'vm-1', 'tags[0].key': 'ssh.account.foo', 'tags[0].value': 'alice,bob' }))
      expect(changed).toEqual(['vm-1'])
      expect(tagReads()).toBe(listed)
      expect(notifyError).not.toHaveBeenCalled()
      expect(message.success).not.toHaveBeenCalled()
    })

    it('shows nothing either when the job of a closed tab fails, but tells the page that the delete went through', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      const answer = getAPI.getMockImplementation()
      let status = 0
      getAPI.mockImplementation((command, params) => command === 'queryAsyncJobResult'
        ? Promise.resolve({ queryasyncjobresultresponse: { jobstatus: status, jobresult: { errortext: 'tag is in use' } } })
        : answer(command, params))
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      close(wrapper)
      status = 2
      await second(1)
      expect(commands()).toEqual(['deleteTags'])
      expect(changed).toEqual(['vm-1'])
      expect(notifyError).not.toHaveBeenCalled()
    })

    it('still gives up on a job that does not finish after the tab is closed, quietly', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      runningJobs()
      await type(wrapper, 'foo', 'alice, bob')
      await save(wrapper, 'foo')
      close(wrapper)

      await second(MAX_WAIT_SECONDS)
      expect(jobQueries()).toBeLessThan(MAX_WAIT_SECONDS)
      const queried = jobQueries()
      await second(5)
      expect(jobQueries()).toBe(queried)
      expect(commands()).toEqual(['deleteTags'])
      expect(notifyError).not.toHaveBeenCalled()
      expect(changed).toEqual(['vm-1'])
    })

    it('sends nothing for a tab that is closed before anything was saved', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'alice']] })
      await type(wrapper, 'foo', 'alice, bob')
      close(wrapper)
      await second(3)
      expect(commands()).toEqual([])
      expect(jobQueries()).toBe(0)
      expect(changed).toEqual([])
    })
  })

  describe('adding an account', () => {
    const addBox = (wrapper) => wrapper.find('.ssh-guests-add input')
    const addButton = (wrapper) => wrapper.find('.ssh-guests-add button')

    it('starts a section for a name that follows the Linux username rule, and the section writes the tag of that account', async () => {
      const wrapper = await mountTab()
      await addBox(wrapper).setValue('worker_1')
      expect(wrapper.find('.ssh-guests-add .ssh-guests-error').exists()).toBe(false)
      await addButton(wrapper).trigger('click')
      await flushPromises()
      expect(accountsOf(wrapper)).toEqual(['debian', 'worker_1'])
      expect(sectionOf(wrapper, 'worker_1').find('.ssh-guests-command').text()).toBe('ssh -p 2222 <ID>:vm-0:worker_1@cloud.snucse.org')
      expect(addBox(wrapper).element.value).toBe('')

      await type(wrapper, 'worker_1', 'alice')
      await save(wrapper, 'worker_1')
      expect(postAPI).toHaveBeenCalledWith('createTags', expect.objectContaining({ 'tags[0].key': 'ssh.account.worker_1', 'tags[0].value': 'alice' }))
    })

    it('refuses a name that breaks the rule, and says what the rule is', async () => {
      const wrapper = await mountTab()
      for (const bad of ['Worker', '1abc', 'a b', 'a.b', 'x'.repeat(33)]) {
        await addBox(wrapper).setValue(bad)
        expect(wrapper.find('.ssh-guests-add .ssh-guests-error').text()).toBe(en['message.ssh.guests.account.invalid'])
        expect(addButton(wrapper).attributes('disabled')).toBeDefined()
      }
      expect(accountsOf(wrapper)).toEqual(['debian'])
    })

    it('does not start a second section for an account that already has one', async () => {
      const wrapper = await mountTab({ tags: [['ssh.account.foo', 'a']] })
      await addBox(wrapper).setValue('foo')
      await addButton(wrapper).trigger('click')
      await flushPromises()
      expect(accountsOf(wrapper)).toEqual(['debian', 'foo'])
    })
  })
})
