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

import common from '../../../common'
import deployment from '../../../mockData/DeploymentConfig.mock.json'
import ActionButton from '@/components/view/ActionButton'

jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const BASE = 'https://docs.cloudstack.apache.org/en/4.23.0.0/'

const mountWith = (actions, docHelpMappings = deployment.docHelpMappings) => {
  const apis = Object.fromEntries(actions.map(action => [action.api, {}]))
  return common.createFactory(ActionButton, {
    router: common.createMockRouter(),
    store: common.createMockStore({ user: { apis, info: { roletype: 'Admin' }, features: {} } }),
    i18n: common.createMockI18n('en', {}),
    props: { actions, dataView: true, resource: { id: 'r-1' } },
    mocks: { $config: { docBase: 'https://docs.example', docHelpMappings, hidden: {} } }
  })
}

const press = async (wrapper) => {
  await wrapper.find('button').trigger('click')
  return wrapper.emitted()['exec-action'].map(([action]) => action)
}

describe('the help link of an action', () => {
  const action = (docHelp) => ({ api: 'addHost', icon: 'plus-outlined', label: 'label.action', dataView: true, docHelp })

  it('is the same on every use of an action whose mapping contains its own key', async () => {
    const shared = action('adminguide/hosts.html#adding-hosts')
    const wrapper = mountWith([shared])

    await press(wrapper)
    await press(wrapper)
    await press(wrapper)

    const links = wrapper.emitted()['exec-action'].map(([emitted]) => wrapper.vm.$docHelpUrl(emitted.docHelp))
    expect(links).toEqual(Array(3).fill(BASE + 'adminguide/hosts.html#adding-hosts'))
  })

  it('leaves the action the page keeps as it was, so a later use maps the original', async () => {
    const shared = action('installguide/configuration.html')
    const wrapper = mountWith([shared])

    await press(wrapper)
    expect(shared.docHelp).toBe('installguide/configuration.html')

    wrapper.vm.$config.docHelpMappings = { 'installguide/configuration.html': 'https://manual.example/configuration.html' }
    const emitted = await press(wrapper)
    expect(wrapper.vm.$docHelpUrl(emitted[1].docHelp)).toBe('https://manual.example/configuration.html')
  })

  it('maps the pages students use to the deployment manual and passes an action without help on as it is', async () => {
    const student = action('adminguide/virtual_machines.html#starting-vms')
    const plain = { api: 'stopVirtualMachine', icon: 'minus-outlined', label: 'label.action', dataView: true }
    const wrapper = mountWith([student, plain])

    const [first] = await press(wrapper)
    expect(first.docHelp).toBe('instance.html#starting-vms')
    expect(wrapper.vm.$docHelpUrl(first.docHelp)).toBe('https://docs.example/instance.html#starting-vms')

    const buttons = wrapper.findAll('button')
    await buttons[1].trigger('click')
    const second = wrapper.emitted()['exec-action'][1][0]
    expect(second.api).toBe('stopVirtualMachine')
    expect(second.docHelp).toBeUndefined()
  })
})
