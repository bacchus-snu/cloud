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
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'

jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [{ id: 'z-1', securitygroupsenabled: false }], metrics: false, project: {}, listAllProjects: false } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const vmPage = compute.children.find(page => page.name === 'vm')
const volumePage = storage.children.find(page => page.name === 'volume')
const snapshotPage = storage.children.find(page => page.name === 'snapshot')

const as = (roletype) => { store.getters.userInfo = { roletype } }
const fieldOf = (detail) => typeof detail === 'object' ? detail.field : detail
const titleOf = (detail) => typeof detail === 'object' ? detail.customTitle : detail

const scheme = { id: 'vm-1', name: 'i-2-34-VM', displayname: 'lab' }
const legacy = { id: 'vm-2', name: 'old-vm' }

describe('the list of instances', () => {
  afterEach(() => as('User'))

  it('has the name and no hostname column for a student', () => {
    as('User')
    const columns = vmPage.columns()
    expect(columns[0]).toBe('name')
    for (const hidden of ['instancename', 'hostname']) {
      expect(columns).not.toContain(hidden)
    }
    expect(columns.filter(column => typeof column === 'object')).toEqual([])
  })

  it('has the name, and a column of the hostname labelled "vmhostname", for every role type but User', () => {
    for (const roletype of ['Admin', 'DomainAdmin', 'ResourceAdmin']) {
      as(roletype)
      const columns = vmPage.columns()
      expect(columns[0]).toBe('name')
      const hostname = columns.find(column => typeof column === 'object' && 'vmhostname' in column)
      expect(hostname.vmhostname(scheme)).toBe('i-2-34-VM')
    }
  })

  it('has the internal instance name in the list of the root administrator only', () => {
    as('Admin')
    expect(vmPage.columns()).toContain('instancename')
    for (const roletype of ['DomainAdmin', 'User']) {
      as(roletype)
      expect(vmPage.columns()).not.toContain('instancename')
    }
  })

  it('searches by the name for a student, and by the hostname and the tags for an administrator', () => {
    as('User')
    const student = vmPage.searchFilters()
    expect(student).toContain('keyword')
    expect(student).not.toContain('name')
    expect(student).not.toContain('tags')

    as('Admin')
    const admin = vmPage.searchFilters()
    expect(admin).toContain('name')
    expect(admin).toContain('tags')
  })
})

describe('the details of an instance', () => {
  afterEach(() => as('User'))

  it('are the name and the ID for a student, the visible name labelled "name"', () => {
    as('User')
    const details = vmPage.details(scheme)
    expect(details.map(fieldOf)).toEqual(expect.arrayContaining(['displayname', 'id']))
    expect(details.map(fieldOf)).not.toContain('name')
    expect(titleOf(details.find(detail => fieldOf(detail) === 'displayname'))).toBe('name')
    expect(details.map(fieldOf)).not.toContain('isdynamicallyscalable')
  })

  it('are the hostname where an instance has no visible name', () => {
    as('User')
    const details = vmPage.details(legacy)
    expect(details.map(fieldOf)).toContain('name')
    expect(details.map(fieldOf)).not.toContain('displayname')
    expect(titleOf(details.find(detail => fieldOf(detail) === 'name'))).toBe('name')
  })

  it('are the visible name labelled "name" and the hostname labelled "vmhostname" for an administrator', () => {
    for (const roletype of ['Admin', 'DomainAdmin']) {
      as(roletype)
      const details = vmPage.details(scheme)
      expect(titleOf(details.find(detail => fieldOf(detail) === 'displayname'))).toBe('name')
      expect(titleOf(details.find(detail => fieldOf(detail) === 'name'))).toBe('vmhostname')
      expect(details.map(fieldOf)).toContain('isdynamicallyscalable')
    }
  })

  it('can be listed without an instance', () => {
    as('User')
    expect(() => vmPage.details()).not.toThrow()
  })
})

describe('the lists of volumes and snapshots', () => {
  afterEach(() => as('User'))

  it('offer no tag filter to a student, and keep it for an administrator', () => {
    for (const page of [volumePage, snapshotPage]) {
      as('User')
      expect(page.searchFilters()).not.toContain('tags')
      as('Admin')
      expect(page.searchFilters()).toContain('tags')
    }
  })
})
