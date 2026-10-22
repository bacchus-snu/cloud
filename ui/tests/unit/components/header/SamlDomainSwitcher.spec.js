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
import { postAPI } from '@/api'
import SamlDomainSwitcher from '@/components/header/SamlDomainSwitcher'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { userInfo: { id: 'u-1' } }, dispatch: jest.fn(() => Promise.resolve()) }))

const accounts = [
  { userId: 'u-1', accountName: 'student', domainName: 'ROOT', domainPath: 'ROOT', domainId: 'd-1' },
  { userId: 'u-2', accountName: 'member', domainName: 'ROOT', domainPath: 'ROOT', domainId: 'd-1' }
]

const mountSwitcher = async (names, mocks = {}) => {
  const apis = Object.fromEntries(names.map(name => [name, { params: [] }]))
  postAPI.mockImplementation((command, params) => command in apis
    ? Promise.resolve({ listandswitchsamlaccountresponse: { count: accounts.length, samluseraccount: accounts } })
    : Promise.reject(Object.assign(new Error(command + ' is not available'), { response: { status: 432, headers: {} } })))
  const wrapper = common.createFactory(SamlDomainSwitcher, {
    i18n: common.createMockI18n('en', {}),
    store: common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } }),
    mocks
  })
  await flushPromises()
  return wrapper
}

describe('the account switcher of a single sign-on session', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.spyOn(console, 'log').mockImplementation(() => {})
  })

  it('asks for the accounts only a role that has the command, and shows nothing otherwise', async () => {
    const wrapper = await mountSwitcher(minimalUser.apis)
    expect(postAPI).not.toHaveBeenCalled()
    expect(wrapper.find('.domain-select').exists()).toBe(false)
  })

  it('shows the switcher for a role that has the command and more than one account', async () => {
    const wrapper = await mountSwitcher([...minimalUser.apis, 'listAndSwitchSamlAccount'])
    expect(postAPI).toHaveBeenCalledWith('listAndSwitchSamlAccount', expect.objectContaining({ details: 'min' }))
    expect(wrapper.find('.domain-select').exists()).toBe(true)
  })

  it('switches to the chosen account, and reports a refused switch instead of throwing', async () => {
    const mocks = { $message: { success: jest.fn() }, $router: { go: jest.fn() } }
    const wrapper = await mountSwitcher([...minimalUser.apis, 'listAndSwitchSamlAccount'], mocks)

    expect(() => wrapper.vm.changeAccount(1)).not.toThrow()
    await flushPromises()
    expect(postAPI).toHaveBeenLastCalledWith('listAndSwitchSamlAccount', { userid: 'u-2', domainid: 'd-1' })
    expect(mocks.$message.success).toHaveBeenCalledTimes(1)
    expect(mocks.$router.go).toHaveBeenCalledTimes(1)

    postAPI.mockImplementation(() => Promise.reject(new Error('refused')))
    expect(() => wrapper.vm.changeAccount(1)).not.toThrow()
    await flushPromises()
    expect(console.log).toHaveBeenCalledWith(expect.stringContaining('error refreshing with new user context'))
    expect(mocks.$router.go).toHaveBeenCalledTimes(1)
  })
})
