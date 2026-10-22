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
import EditVM from '@/views/compute/EditVM'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const userData = '#cloud-config\npackages: [htop]\n'
const editParams = ['name', 'displayname', 'ostypeid', 'isdynamicallyscalable', 'haenable', 'group', 'userdata', 'deleteprotection']
  .map(name => ({ name, description: name }))

const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: name === 'updateVirtualMachine' ? editParams : [] }]))

const vm = {
  id: 'vm-1',
  name: 'lab',
  zoneid: 'z-1',
  serviceofferingid: 'so-1',
  templateid: 't-1',
  details: {},
  nic: [{ id: 'nic-1', networkid: 'n-1', isdefault: true }]
}

const answer = (networkServices) => (command) => {
  const answers = {
    listZones: { listzonesresponse: { zone: [{ id: 'z-1', securitygroupsenabled: true }] } },
    listServiceOfferings: { listserviceofferingsresponse: { serviceoffering: [{ id: 'so-1' }] } },
    listTemplates: { listtemplatesresponse: { template: [{ id: 't-1' }] } },
    listSecurityGroups: { listsecuritygroupsresponse: { securitygroup: [] } },
    listNetworks: { listnetworksresponse: { network: [{ id: 'n-1', service: networkServices.map(name => ({ name })) }] } },
    listVirtualMachines: { listvirtualmachinesresponse: { virtualmachine: [{ id: 'vm-1', userdata: btoa(userData) }] } }
  }
  return Promise.resolve(answers[command] || {})
}

const openEdit = async (apis, networkServices = ['UserData']) => {
  getAPI.mockImplementation(answer(networkServices))
  const store = common.createMockStore({ user: { apis, info: { roletype: 'User', domainid: 'd-1', account: 'student' }, features: {} } })
  const wrapper = common.createFactory(EditVM, {
    store,
    i18n: common.createMockI18n('en', {}),
    props: { action: {}, resource: vm },
    mocks: { $notifyError: jest.fn() }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

const calledLists = () => getAPI.mock.calls.map(call => call[0])
const userDataBox = (wrapper) => wrapper.findAll('textarea').filter(box => box.element.value === userData)

describe('the Edit dialog\'s user data', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('the minimal user role gets the user data field from updateVirtualMachine, without listing networks', async () => {
    const wrapper = await openEdit(apiMap(minimalUser.apis))
    expect(wrapper.vm.userDataEnabled).toBe(true)
    expect(wrapper.vm.form.userdata).toBe(userData)
    expect(userDataBox(wrapper)).toHaveLength(1)
    expect(calledLists()).not.toContain('listNetworks')
    expect(calledLists().filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })

  it('a role that lists networks keeps the probe of the default network\'s UserData service', async () => {
    const withNetworks = apiMap([...minimalUser.apis, 'listNetworks'])
    const offered = await openEdit(withNetworks, ['UserData', 'Dhcp'])
    expect(offered.vm.userDataEnabled).toBe(true)
    expect(calledLists()).toContain('listNetworks')

    jest.clearAllMocks()
    const notOffered = await openEdit(withNetworks, ['Dhcp'])
    expect(notOffered.vm.userDataEnabled).toBe(false)
    expect(calledLists()).toContain('listNetworks')
  })
})
