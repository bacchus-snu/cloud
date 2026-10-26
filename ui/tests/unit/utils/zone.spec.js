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
import { hasSeveralZones, hasZoneBesides, zonesBesides } from '@/utils/zone'

jest.mock('@/store', () => ({ getters: { zones: [] } }))

const own = { id: 'z-1', name: 'snucse' }
const other = { id: 'z-2', name: 'other' }
const edge = { id: 'z-3', name: 'edge', type: 'Edge' }

describe('the zones other than the own zone of a resource', () => {
  it('are the zones but the own one and the edge zones', () => {
    expect(zonesBesides([own, other, edge], 'z-1')).toEqual([other])
    expect(zonesBesides([own, edge], 'z-1')).toEqual([])
    expect(zonesBesides([own], 'z-1')).toEqual([])
  })

  it('are all the zones that are not edge zones when there is no own zone', () => {
    expect(zonesBesides([own, other, edge])).toEqual([own, other])
  })

  it('are none while the zones are not known', () => {
    for (const unknown of [undefined, null, {}]) {
      expect(zonesBesides(unknown, 'z-1')).toEqual([])
    }
  })
})

describe('whether there is a zone other than the own one', () => {
  afterEach(() => {
    store.getters.zones = []
  })

  it('reads the zones of the session', () => {
    store.getters.zones = [own, other]
    expect(hasZoneBesides('z-1')).toBe(true)
    store.getters.zones = [own]
    expect(hasZoneBesides('z-1')).toBe(false)
  })

  it('does not count an edge zone', () => {
    store.getters.zones = [own, edge]
    expect(hasZoneBesides('z-1')).toBe(false)
    expect(hasSeveralZones()).toBe(false)
  })

  it('is asked for several zones when the own zone is not known', () => {
    store.getters.zones = [own, other]
    expect(hasZoneBesides(undefined)).toBe(true)
    store.getters.zones = [own]
    expect(hasZoneBesides(undefined)).toBe(false)
  })

  it('is false until the zones of the session are loaded, which start as an empty object', () => {
    for (const unknown of [{}, undefined, null]) {
      store.getters.zones = unknown
      expect(hasZoneBesides('z-1')).toBe(false)
      expect(hasSeveralZones()).toBe(false)
    }
  })

  it('counts the zones that are not edge zones for several', () => {
    store.getters.zones = [own, other]
    expect(hasSeveralZones()).toBe(true)
    store.getters.zones = [own, other, edge]
    expect(hasSeveralZones()).toBe(true)
    store.getters.zones = [own]
    expect(hasSeveralZones()).toBe(false)
  })
})
