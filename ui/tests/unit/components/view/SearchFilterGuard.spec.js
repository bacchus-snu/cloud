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
import { getAPI } from '@/api'
import SearchFilter from '@/components/view/SearchFilter'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const ZONE = '11111111-1111-1111-1111-111111111111'
const DOMAIN = '22222222-2222-2222-2222-222222222222'

const mountFilter = async (names) => {
  const apis = Object.fromEntries(names.map(name => [name, { params: [] }]))
  getAPI.mockImplementation((command) => {
    if (!(command in apis)) {
      return Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } }))
    }
    return command === 'listZones'
      ? Promise.resolve({ listzonesresponse: { zone: [{ id: ZONE, name: 'zone-name' }] } })
      : Promise.resolve({ listdomainsresponse: { domain: [{ id: DOMAIN, name: 'domain-name' }] } })
  })
  const router = common.createMockRouter([{ path: '/vm', name: 'vm', component: {} }])
  router.push('/vm')
  await router.isReady()
  const wrapper = common.createFactory(SearchFilter, {
    router,
    i18n: common.createMockI18n('en', {}),
    store: common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } }),
    props: { apiName: 'listVirtualMachines', filters: [{ key: 'zoneid', value: ZONE }, { key: 'domainid', value: DOMAIN }] }
  })
  await flushPromises()
  return wrapper
}

describe('the chips of the active search filters', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('name a resource only through a list the role has, and show the id otherwise', async () => {
    const wrapper = await mountFilter(minimalUser.apis)
    const asked = getAPI.mock.calls.map(call => call[0])
    expect(asked).toContain('listZones')
    expect(asked).not.toContain('listDomains')
    expect(wrapper.text()).toContain('zone-name')
    expect(wrapper.text()).toContain('label.domainid : ' + DOMAIN.substring(0, 12))
    expect(wrapper.text()).not.toContain('domain-name')
  })

  it('name it through the list when the role has it', async () => {
    const wrapper = await mountFilter([...minimalUser.apis, 'listDomains'])
    expect(getAPI.mock.calls.map(call => call[0])).toContain('listDomains')
    expect(wrapper.text()).toContain('domain-name')
  })
})
