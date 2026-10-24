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
import eventBus from '@/config/eventBus'
import GlobalLayout from '@/components/page/GlobalLayout'
import InstanceAccessModal from '@/components/page/InstanceAccessModal'
import en from '@public/locales/en.json'
import ko from '@public/locales/ko_KR.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: {}, zones: [], project: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const access = {
  name: 'My-VM',
  sshCommand: 'ssh -p 2222 baneling100:my-vm@cloud.snucse.org',
  account: 'debian',
  password: 'Xy7kq2Lp'
}

const mounted = []
const mountModal = (messages = en) => {
  const wrapper = common.createFactory(InstanceAccessModal, { i18n: common.createMockI18n('en', { en: messages }) })
  mounted.push(wrapper)
  return wrapper
}

const dialog = () => document.querySelector('.instance-access')
const text = () => (dialog() ? dialog().textContent : '')
const show = async (info) => {
  eventBus.emit('instance-access', info)
  await flushPromises()
}
const acknowledge = async () => {
  document.querySelector('.instance-access-ack').click()
  await flushPromises()
}

describe('the dialog that shows how to reach a new instance', () => {
  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.innerHTML = ''
  })

  it('is not there until an instance announces its access', async () => {
    mountModal()
    await flushPromises()
    expect(dialog()).toBeNull()
  })

  it('shows the command, the login account with its help, and the instance password with its explanation, in that order', async () => {
    mountModal()
    await show(access)
    expect(dialog()).not.toBeNull()
    expect(document.querySelector('.ant-modal-title').textContent).toBe(en['label.instance.access.title'])
    const shown = text()
    expect(shown).toContain('My-VM')
    const order = [en['label.ssh.access'], access.sshCommand, en['label.login.account'], 'debian', en['message.login.account'],
      en['label.instance.password'], access.password, en['message.instance.password']].map(part => shown.indexOf(part))
    expect(order.every(index => index >= 0)).toBe(true)
    expect([...order].sort((a, b) => a - b)).toEqual(order)
    expect(document.querySelector('.instance-access-command').tagName).toBe('CODE')
    expect(document.querySelector('.instance-access-password').tagName).toBe('CODE')
  })

  it('has a copy button for the command and one for the password', async () => {
    const wrapper = mountModal()
    await show(access)
    const copies = wrapper.findAllComponents({ name: 'TooltipButton' }).map(button => button.props('copyResource'))
    expect(copies).toEqual([access.sshCommand, access.password])
  })

  it('shows no password block when there is no password', async () => {
    mountModal()
    await show({ ...access, password: null })
    expect(text()).toContain(access.sshCommand)
    expect(text()).toContain(en['message.login.account'])
    expect(text()).not.toContain(en['label.instance.password'])
    expect(text()).not.toContain(en['message.instance.password'])
    expect(document.querySelector('.instance-access-password')).toBeNull()
  })

  it('shows only the password when the deployment names no host and no account', async () => {
    mountModal()
    await show({ name: 'My-VM', sshCommand: null, account: null, password: 'Xy7kq2Lp' })
    expect(text()).toContain('Xy7kq2Lp')
    expect(text()).toContain(en['message.instance.password'])
    expect(text()).not.toContain(en['label.ssh.access'])
    expect(text()).not.toContain(en['label.login.account'])
  })

  it('has one button, which says the owner has noted it, and does not close on the mask, the Escape key or a close mark', async () => {
    mountModal(ko)
    await show(access)
    expect(document.querySelectorAll('.instance-access-ack')).toHaveLength(1)
    expect(document.querySelector('.ant-modal-footer')).toBeNull()
    expect(document.querySelector('.instance-access-ack').textContent.trim()).toBe(ko['label.instance.access.ack'])
    expect(document.querySelector('.ant-modal-close')).toBeNull()

    document.querySelector('.ant-modal-wrap').click()
    document.querySelector('.ant-modal-wrap').dispatchEvent(new KeyboardEvent('keydown', { keyCode: 27, key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(dialog()).not.toBeNull()
  })

  it('closes when acknowledged', async () => {
    mountModal()
    await show(access)
    await acknowledge()
    expect(dialog()).toBeNull()
  })

  it('shows the instances announced meanwhile one after the other, the password of none lost', async () => {
    mountModal()
    await show(access)
    await show({ ...access, name: 'Second', sshCommand: 'ssh -p 2222 baneling100:second@cloud.snucse.org', password: 'Aa1Bb2Cc' })
    expect(text()).toContain('Xy7kq2Lp')
    expect(text()).not.toContain('Aa1Bb2Cc')

    await acknowledge()
    expect(dialog()).not.toBeNull()
    expect(text()).toContain('Aa1Bb2Cc')
    expect(text()).toContain('second@cloud.snucse.org')
    expect(text()).not.toContain('Xy7kq2Lp')

    await acknowledge()
    expect(dialog()).toBeNull()
  })

  it('sits in the layout of every signed-in page, which outlives the page that announces', () => {
    const layout = fs.readFileSync(path.join(__dirname, '../../../../src/components/page/GlobalLayout.vue'), 'utf8')
    expect(layout).toContain('<instance-access-modal')
    expect(GlobalLayout.components.InstanceAccessModal).toBe(InstanceAccessModal)
  })

  it('listens no more once it is gone', async () => {
    const wrapper = mountModal()
    wrapper.unmount()
    mounted.splice(0)
    await show(access)
    expect(dialog()).toBeNull()
  })
})
