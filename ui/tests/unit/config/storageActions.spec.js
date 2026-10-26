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
import compute from '@/config/section/compute'
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

const snapshotPage = storage.children.find(child => child.name === 'snapshot')
const offeredSnapshot = (snapshot, apis = minimalUser.apis) => snapshotPage.actions
  .filter(action => apis.includes(action.api) && (action.dataView || action.listView))
  .filter(action => !('show' in action) || action.show(snapshot, getters))
  .map(action => action.label)

const backedUpRoot = { id: 's-1', name: 'ROOT-7-snapshot-0', state: 'BackedUp', volumetype: 'ROOT', volumeid: 'v-1', zoneid: 'z-1' }
const backedUpData = { ...backedUpRoot, id: 's-2', volumetype: 'DATADISK' }

describe('renaming a volume or a snapshot', () => {
  it('is offered for a volume to every role, with a form of the name alone', () => {
    const rename = volumePage.actions.find(action => action.label === 'label.rename')
    expect(rename.api).toBe('updateVolume')
    expect(rename.dataView).toBe(true)
    expect(rename.popup).toBe(true)
    expect(rename.component).toBeDefined()
    expect(rename.args).toBeUndefined()
    for (const volume of [dataDisk, keptRoot, detachedRoot, attachedRoot]) {
      expect(offered(volume)).toContain('label.rename')
    }
    for (const role of ['User', 'DomainAdmin', 'Admin']) {
      expect(rename.show(dataDisk, { ...getters, userInfo: { roletype: role } })).toBe(true)
    }
  })

  it('is not offered for a volume that is gone or going', () => {
    for (const state of ['Destroy', 'Destroyed', 'Expunging', 'Expunged']) {
      expect(offered({ ...dataDisk, state })).not.toContain('label.rename')
    }
  })

  it('leaves the edit form of a volume, with its delete protection, to the roles above User', () => {
    const edit = volumePage.actions.find(action => action.label === 'label.edit')
    expect(offered(dataDisk)).not.toContain('label.edit')
    expect(edit.show(dataDisk, { ...getters, userInfo: { roletype: 'DomainAdmin' } })).toBe(true)
    expect(edit.show(dataDisk, { ...getters, userInfo: { roletype: 'Admin' } })).toBe(true)
  })

  it('is offered for a snapshot to every role, with the new updateSnapshot', () => {
    const rename = snapshotPage.actions.find(action => action.label === 'label.rename')
    expect(rename.api).toBe('updateSnapshot')
    expect(rename.dataView).toBe(true)
    expect(rename.popup).toBe(true)
    expect(rename.component).toBeDefined()
    expect(minimalUser.apis).toEqual(expect.arrayContaining(['updateSnapshot', 'updateVolume']))
    expect(offeredSnapshot(backedUpRoot)).toContain('label.rename')
    expect(offeredSnapshot(backedUpData)).toContain('label.rename')
    expect(offeredSnapshot({ ...backedUpRoot, state: 'Destroyed' })).not.toContain('label.rename')
  })
})

describe('creating an Instance from a snapshot', () => {
  const deploy = snapshotPage.actions.find(action => action.label === 'label.action.deploy.from.snapshot')

  it('opens the wizard on the snapshot and its zone', () => {
    expect(deploy.api).toBe('deployVirtualMachine')
    expect(deploy.dataView).toBe(true)
    expect(deploy.navigate(backedUpRoot)).toEqual({ path: '/action/deployVirtualMachine', query: { snapshotid: 's-1', zoneid: 'z-1' } })
  })

  it('is offered for a backed-up snapshot of a ROOT disk to the student role', () => {
    expect(offeredSnapshot(backedUpRoot)).toContain('label.action.deploy.from.snapshot')
  })

  it('is not offered for the snapshot of a data disk or one that is not backed up', () => {
    expect(offeredSnapshot(backedUpData)).not.toContain('label.action.deploy.from.snapshot')
    for (const state of ['Creating', 'BackingUp', 'Error', 'Destroyed']) {
      expect(offeredSnapshot({ ...backedUpRoot, state })).not.toContain('label.action.deploy.from.snapshot')
    }
  })

  it('is not offered to a role that cannot deploy an Instance', () => {
    expect(offeredSnapshot(backedUpRoot, minimalUser.apis.filter(api => api !== 'deployVirtualMachine'))).not.toContain('label.action.deploy.from.snapshot')
  })
})

describe('recovering a destroyed volume', () => {
  const recover = volumePage.actions.find(action => action.api === 'recoverVolume')
  const destroyed = { ...dataDisk, state: 'Destroy' }
  const admin = { userInfo: { roletype: 'Admin' }, features: {} }

  it('is offered to an administrator for a volume in the destroyed state', () => {
    expect(recover.show(destroyed, admin)).toBe(true)
    expect(recover.show(destroyed, { ...admin, features: { expungeondestroy: false } })).toBe(true)
    expect(recover.show({ ...destroyed, state: 'Ready' }, admin)).toBe(false)
  })

  it('is not offered while the cloud deletes a destroyed volume at once', () => {
    expect(recover.show(destroyed, { ...admin, features: { expungeondestroy: true } })).toBe(false)
    expect(recover.show(destroyed, { userInfo: { roletype: 'User' }, features: { expungeondestroy: true, allowuserexpungerecovervolume: true } })).toBe(false)
  })
})

describe('deleting a volume or a snapshot', () => {
  const find = (page, api) => page.actions.find(action => action.api === api)

  it('asks for the name of the volume or the snapshot, and for the number when many are chosen', () => {
    for (const action of [find(volumePage, 'destroyVolume'), find(volumePage, 'deleteVolume'), find(snapshotPage, 'deleteSnapshot')]) {
      expect(action.requireNameConfirmation).toBe(true)
    }
    expect(find(volumePage, 'deleteVolume').groupAction).toBe(true)
    expect(find(snapshotPage, 'deleteSnapshot').groupAction).toBe(true)
  })

  it('words the destroy of a volume as the cloud keeps destroyed volumes until the capabilities say otherwise', () => {
    const destroy = find(volumePage, 'destroyVolume')
    expect(destroy.freshCapabilities).toBe(true)
    expect(destroy.message(dataDisk, {})).toBe('message.action.destroy.volume')
    expect(destroy.message(dataDisk, { expungeondestroy: false })).toBe('message.action.destroy.volume')
    expect(destroy.message(dataDisk, { expungeondestroy: true })).toBe('message.action.destroy.volume.now')
  })

  it('words the delete of a volume the same way', () => {
    const remove = find(volumePage, 'deleteVolume')
    expect(remove.freshCapabilities).toBe(true)
    expect(remove.message(dataDisk, {})).toBe('message.action.delete.volume')
    expect(remove.message(dataDisk, { expungeondestroy: true })).toBe('message.action.delete.volume.now')
  })

  it('offers the stock expunge switch only when the cloud keeps destroyed volumes', () => {
    const destroy = find(volumePage, 'destroyVolume')
    const admin = { userInfo: { roletype: 'Admin' }, features: {} }
    expect(destroy.args(dataDisk, admin)).toEqual(['expunge'])
    expect(destroy.args(dataDisk, { ...admin, features: { allowuserexpungerecovervolume: true } })).toEqual(['expunge'])
    expect(destroy.args(dataDisk, { ...admin, features: { expungeondestroy: false } })).toEqual(['expunge'])
    expect(destroy.args(dataDisk, { ...admin, features: { expungeondestroy: true } })).toEqual([])
    expect(destroy.args(dataDisk, { userInfo: { roletype: 'User' }, features: { expungeondestroy: true, allowuserexpungerecovervolume: true } })).toEqual([])
    expect(destroy.args(dataDisk, { userInfo: { roletype: 'User' }, features: {} })).toEqual([])
  })

  it('says of a snapshot that it is deleted for good', () => {
    expect(find(snapshotPage, 'deleteSnapshot').message).toBe('message.action.delete.snapshot')
  })

  it('has the name of an Instance typed to expunge it, also in the page of the application Instances', () => {
    const expunge = compute.children.find(page => page.name === 'vm').actions.find(action => action.api === 'expungeVirtualMachine')
    expect(expunge.requireNameConfirmation).toBe(true)
  })
})
