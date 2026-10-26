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
import store from '@/store'
import ScheduledSnapshots from '@/views/storage/ScheduledSnapshots'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const own = { id: 'z-1', name: 'snucse' }
const other = { id: 'z-2', name: 'other' }
const volume = { id: 'v-1', name: 'data', zoneid: 'z-1', zonename: 'snucse' }
const policy = {
  id: 'p-1',
  intervaltype: 1,
  schedule: '30:03',
  timezone: 'Asia/Seoul',
  maxsnaps: 3,
  tags: [{ key: 'ssh.account.debian', value: 'alice' }, { key: 'course', value: 'os' }],
  zone: [{ id: 'z-2', name: 'other' }]
}

const open = async ({ roletype = 'User', locale = 'en', zones = [own] } = {}) => {
  store.getters.zones = zones
  const router = common.createMockRouter([{ path: '/', component: {} }, { path: '/zones/:id', component: {} }])
  router.push('/')
  await router.isReady()
  const wrapper = common.createFactory(ScheduledSnapshots, {
    router,
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    store: common.createMockStore({ user: { apis: {}, info: { roletype }, features: {} } }),
    props: { dataSource: [policy], resource: volume, loading: false },
    mocks: { $notifyError: jest.fn() }
  })
  await flushPromises()
  return wrapper
}

const headers = (wrapper) => wrapper.findAll('th').map(cell => cell.text())

describe('the snapshot policies of a volume', () => {
  afterEach(() => {
    store.getters.zones = []
  })

  describe.each([['en', en], ['ko_KR', ko]])('for a student (%s)', (locale, messages) => {
    it('are listed without their tags, which are no business of a student, and the values of the tags are not shown', async () => {
      const wrapper = await open({ locale, zones: [own, other] })
      expect(headers(wrapper)).not.toContain(messages['label.tags'])
      expect(wrapper.text()).not.toContain('ssh.account.debian')
      expect(wrapper.text()).not.toContain('course=os')
    })

    it('are listed without their zones while there is no zone but the own one', async () => {
      const wrapper = await open({ locale, zones: [own] })
      expect(headers(wrapper)).not.toContain(messages['label.zones'])
      expect(wrapper.text()).not.toContain('other')
    })

    it('are listed with their zones when there is another zone', async () => {
      const wrapper = await open({ locale, zones: [own, other] })
      expect(headers(wrapper)).toContain(messages['label.zones'])
      expect(wrapper.text()).toContain('other')
    })
  })

  it('does not count an edge zone as another zone', async () => {
    const wrapper = await open({ zones: [own, { id: 'z-9', name: 'edge', type: 'Edge' }] })
    expect(headers(wrapper)).not.toContain(en['label.zones'])
  })

  it('keep the columns, the same for the time of the schedule, of a student', async () => {
    const wrapper = await open()
    for (const label of ['label.time', 'label.timezone', 'label.keep']) {
      expect(headers(wrapper)).toContain(en[label])
    }
  })

  it('are listed with their tags and zones for an administrator, whatever the number of zones', async () => {
    for (const roletype of ['Admin', 'DomainAdmin']) {
      const wrapper = await open({ roletype, zones: [own] })
      expect(headers(wrapper)).toEqual(expect.arrayContaining([en['label.tags'], en['label.zones']]))
      expect(wrapper.text()).toContain('ssh.account.debian=alice')
      expect(wrapper.text()).toContain('other')
    }
  })
})
