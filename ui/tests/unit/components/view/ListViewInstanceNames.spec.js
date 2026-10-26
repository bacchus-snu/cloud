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

import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import ListView from '@/components/view/ListView'

jest.mock('@/api', () => ({ getAPI: jest.fn(() => Promise.resolve({})), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [], project: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const columns = [{ key: 'name', title: 'Name', dataIndex: 'name' }]
const scheme = { id: 'vm-1', name: 'i-2-34-VM', displayname: 'VM-0', state: 'Running' }
const older = { id: 'vm-2', name: 'old-vm', state: 'Running' }

const mounted = []
const mountList = async (path, items) => {
  const router = common.createMockRouter([{ path: '/vm', name: 'vm', component: {} }, { path: '/vnfapp', name: 'vnfapp', component: {} },
    { path: '/volume', name: 'volume', component: {} }])
  router.push(path)
  await router.isReady()
  const wrapper = common.createFactory(ListView, {
    router,
    i18n: common.createMockI18n('en', {}),
    store: common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } }),
    props: { columns, items },
    provide: { parentFetchData: jest.fn(), parentToggleLoading: jest.fn() },
    mocks: { $config: { theme: {} } }
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
const names = (wrapper) => wrapper.findAll('tbody tr').map(row => row.find('a').text())

describe('the names in a list of instances', () => {
  afterEach(() => {
    mounted.splice(0).forEach(wrapper => wrapper.unmount())
  })

  it('are the visible names, and the hostname for an instance that has none', async () => {
    const wrapper = await mountList('/vm', [scheme, older])
    expect(names(wrapper)).toEqual(['VM-0', 'old-vm'])
  })

  it('are the visible names of an instance that has annotations too', async () => {
    const wrapper = await mountList('/vm', [{ ...scheme, hasannotations: true }])
    expect(names(wrapper)).toEqual(['VM-0'])
  })

  it('are the visible names in the list of network appliances too, which are instances', async () => {
    const wrapper = await mountList('/vnfapp', [scheme, older])
    expect(names(wrapper)).toEqual(['VM-0', 'old-vm'])
  })

  it('are the names as they are in the lists of other resources', async () => {
    const wrapper = await mountList('/volume', [{ id: 'v-1', name: 'data', displayname: 'something else' }])
    expect(names(wrapper)).toEqual(['data'])
  })
})
