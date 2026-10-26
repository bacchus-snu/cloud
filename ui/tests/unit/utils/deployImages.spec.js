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

import { canDeployFromSnapshot, canDeployFromVolume, deployableSnapshots, deployableVolumes } from '@/utils/deployImages'

describe('the snapshots an Instance can be made from', () => {
  const backedUpRoot = { id: 's-1', state: 'BackedUp', volumetype: 'ROOT' }

  it('are the backed-up snapshots of a ROOT disk', () => {
    expect(canDeployFromSnapshot(backedUpRoot)).toBe(true)
  })

  it('are not the snapshots of a data disk', () => {
    expect(canDeployFromSnapshot({ ...backedUpRoot, volumetype: 'DATADISK' })).toBe(false)
    expect(canDeployFromSnapshot({ id: 's-1', state: 'BackedUp' })).toBe(false)
  })

  it('are not the snapshots that are not backed up', () => {
    for (const state of ['Creating', 'CreatedOnPrimary', 'BackingUp', 'Error', 'Destroyed', 'Destroying', undefined]) {
      expect(canDeployFromSnapshot({ ...backedUpRoot, state })).toBe(false)
    }
  })

  it('are not nothing', () => {
    expect(canDeployFromSnapshot(undefined)).toBe(false)
    expect(canDeployFromSnapshot(null)).toBe(false)
  })

  it('are picked from a list, in its order', () => {
    const list = [
      { id: 'a', state: 'BackedUp', volumetype: 'ROOT' },
      { id: 'b', state: 'BackedUp', volumetype: 'DATADISK' },
      { id: 'c', state: 'Creating', volumetype: 'ROOT' },
      { id: 'd', state: 'BackedUp', volumetype: 'ROOT' }
    ]
    expect(deployableSnapshots(list).map(snapshot => snapshot.id)).toEqual(['a', 'd'])
    expect(deployableSnapshots([])).toEqual([])
    expect(deployableSnapshots(undefined)).toEqual([])
  })
})

describe('the volumes an Instance can be made from', () => {
  const keptRoot = { id: 'v-1', state: 'Ready', templateid: 't-1' }

  it('are the detached Ready volumes made from a template', () => {
    expect(canDeployFromVolume(keptRoot)).toBe(true)
  })

  it('are not the volumes made from no template, such as a data disk', () => {
    expect(canDeployFromVolume({ id: 'v-2', state: 'Ready' })).toBe(false)
    expect(canDeployFromVolume({ id: 'v-2', state: 'Ready', templateid: '' })).toBe(false)
    expect(canDeployFromVolume({ id: 'v-2', state: 'Ready', templateid: null })).toBe(false)
  })

  it('are not the volumes that are attached or not Ready', () => {
    expect(canDeployFromVolume({ ...keptRoot, virtualmachineid: 'vm-1' })).toBe(false)
    for (const state of ['Allocated', 'Creating', 'Destroy', 'Expunging', 'Uploaded', undefined]) {
      expect(canDeployFromVolume({ ...keptRoot, state })).toBe(false)
    }
  })

  it('are not nothing', () => {
    expect(canDeployFromVolume(undefined)).toBe(false)
    expect(canDeployFromVolume(null)).toBe(false)
  })

  it('are picked from a list, in its order', () => {
    const list = [
      keptRoot,
      { id: 'v-2', state: 'Ready' },
      { id: 'v-3', state: 'Ready', templateid: 't-1', virtualmachineid: 'vm-1' },
      { id: 'v-4', state: 'Ready', templateid: 't-2' }
    ]
    expect(deployableVolumes(list).map(volume => volume.id)).toEqual(['v-1', 'v-4'])
    expect(deployableVolumes(undefined)).toEqual([])
  })
})
