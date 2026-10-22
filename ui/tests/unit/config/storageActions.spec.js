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

import store from '@/store'
import storage from '@/config/section/storage'
import minimalUser from '../../mockData/MinimalUserApis.mock.json'

jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [{ id: 'z-1' }] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const volumePage = storage.children.find(child => child.name === 'volume')
const getters = { userInfo: { roletype: 'User' }, features: {}, apis: Object.fromEntries(minimalUser.apis.map(a => [a, {}])) }
store.getters.apis = getters.apis

// what the action bar of a volume offers this role: the action's API in the list, and its own condition
const offered = (volume, apis = minimalUser.apis) => volumePage.actions
  .filter(action => apis.includes(action.api) && (action.dataView || action.listView))
  .filter(action => !('show' in action) || action.show(volume, getters))
  .map(action => action.label)

// a root kept by destroying its instance: detached, still Ready, made from a template
const keptRoot = { id: 'v-1', name: 'ROOT-7', type: 'DATADISK', state: 'Ready', zoneid: 'z-1', templateid: 't-1', size: 21474836480 }
const detachedRoot = { ...keptRoot, type: 'ROOT' }
const attachedRoot = { ...keptRoot, type: 'ROOT', virtualmachineid: 'vm-1', vmstate: 'Stopped' }
const dataDisk = { id: 'v-2', name: 'data', type: 'DATADISK', state: 'Ready', zoneid: 'z-1' }

describe('volume actions of the minimal user role', () => {
  it('ROOT volume can be attached, deployed from, destroyed', () => {
    for (const root of [keptRoot, detachedRoot]) {
      const labels = offered(root)
      expect(labels).toEqual(expect.arrayContaining(['label.action.attach.disk', 'label.action.deploy.from.volume', 'label.action.destroy.volume']))
    }
    // a root still in its instance offers none of the three
    const attached = offered(attachedRoot)
    for (const label of ['label.action.attach.disk', 'label.action.deploy.from.volume', 'label.action.destroy.volume']) {
      expect(attached).not.toContain(label)
    }
    // a data disk made from no template cannot boot an instance
    expect(offered(dataDisk)).not.toContain('label.action.deploy.from.volume')

    // the deployment opens the wizard on that volume and its zone
    const deploy = volumePage.actions.find(action => action.label === 'label.action.deploy.from.volume')
    expect(deploy.api).toBe('deployVirtualMachine')
    expect(deploy.navigate(keptRoot)).toEqual({ path: '/action/deployVirtualMachine', query: { volumeid: 'v-1', zoneid: 'z-1' } })
  })

  it('volume actions include Download, exclude upload and change offering', () => {
    const labels = offered({ ...dataDisk, vmstate: undefined })
    expect(labels).toContain('label.action.download.volume')
    expect(labels).toEqual(expect.arrayContaining(['label.action.attach.disk', 'label.action.take.snapshot',
      'label.action.recurring.snapshot', 'label.action.resize.volume', 'label.action.destroy.volume']))
    for (const absent of ['label.upload.volume.from.local', 'label.upload.volume.from.url', 'label.change.offering.for.volume',
      'label.migrate.volume', 'label.action.create.template.from.volume']) {
      expect(labels).not.toContain(absent)
    }
    const listLabels = volumePage.actions.filter(action => action.listView && minimalUser.apis.includes(action.api) &&
      (!('show' in action) || action.show({}, getters))).map(action => action.label)
    expect(listLabels).toEqual(['label.action.create.volume'])
  })
})
