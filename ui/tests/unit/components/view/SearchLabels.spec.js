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
import { searchFieldLabelKey } from '@/utils/searchLabel'
import SearchFilter from '@/components/view/SearchFilter'
import SearchView from '@/components/view/SearchView'

jest.mock('@/api', () => ({ getAPI: jest.fn(() => Promise.resolve({})) }))
jest.mock('@/store', () => ({ getters: { apis: {}, userInfo: { roletype: 'Admin' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

describe('the label of a search field', () => {
  it('is the hostname for the name filter of the instance pages, and the name elsewhere', () => {
    expect(searchFieldLabelKey('name', { routeName: 'vm' })).toBe('label.vmhostname')
    expect(searchFieldLabelKey('name', { routeName: 'vnfapp' })).toBe('label.vmhostname')
    expect(searchFieldLabelKey('name', { routeName: 'volume' })).toBe('label.name')
    expect(searchFieldLabelKey('name', {})).toBe('label.name')
  })

  it('is the name for the keyword, or the annotation where annotations are listed', () => {
    expect(searchFieldLabelKey('keyword', { routeName: 'vm' })).toBe('label.name')
    expect(searchFieldLabelKey('keyword', { routeName: 'vm', annotations: true })).toBe('label.annotation')
  })

  it('names the fields that are asked for by another word', () => {
    expect(searchFieldLabelKey('groupid', {})).toBe('label.group')
    expect(searchFieldLabelKey('hsmprofileid', {})).toBe('label.hsm.profile')
    expect(searchFieldLabelKey('kmskeyid', {})).toBe('label.kms.key')
    expect(searchFieldLabelKey('zoneid', {})).toBe('label.zoneid')
  })

  it('reads "Hostname" and "호스트 이름"', () => {
    expect(en[searchFieldLabelKey('name', { routeName: 'vm' })]).toBe('Hostname')
    expect(ko[searchFieldLabelKey('name', { routeName: 'vm' })]).toBe('호스트 이름')
  })
})

describe('the one function behind the search form and the chips of its filters', () => {
  const withMessages = (messages) => (key) => messages[key] || key
  const context = (routeName, messages = en) => ({
    $t: withMessages(messages),
    $route: { meta: { name: routeName } },
    $store: { getters: { apis: {} } }
  })

  it.each([['search form', SearchView], ['chips of the filters', SearchFilter]])('gives the %s the same labels', (name, component) => {
    const labelOf = (routeName, field) => component.methods.retrieveFieldLabel.call(context(routeName), field)
    expect(labelOf('vm', 'name')).toBe('Hostname')
    expect(labelOf('volume', 'name')).toBe('Name')
    expect(labelOf('vm', 'keyword')).toBe('Name')
    expect(labelOf('vm', 'groupid')).toBe(en['label.group'])
  })
})

describe('the chip of the name filter', () => {
  const chipsOn = async (routeName, locale) => {
    const router = common.createMockRouter([{ path: '/page', name: routeName, meta: { name: routeName }, component: {} }])
    router.push('/page')
    await router.isReady()
    const wrapper = common.createFactory(SearchFilter, {
      router,
      i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
      store: common.createMockStore({ user: { apis: {}, info: { roletype: 'Admin' }, features: {} } }),
      props: { apiName: 'listVirtualMachines', filters: [{ key: 'name', value: 'i-2-34-VM' }] }
    })
    await flushPromises()
    return wrapper
  }

  it('says "Hostname" on the instance pages and "Name" on the others', async () => {
    expect((await chipsOn('vm', 'en')).text()).toContain('Hostname : i-2-34-VM')
    expect((await chipsOn('volume', 'en')).text()).toContain('Name : i-2-34-VM')
  })

  it('says "호스트 이름" on the instance pages of a Korean page', async () => {
    expect((await chipsOn('vm', 'ko_KR')).text()).toContain('호스트 이름 : i-2-34-VM')
  })
})
