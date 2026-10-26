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

import { placementKind, snapshotPlacementRequest } from '@/utils/placement'

describe('the placement request of an Instance made from a snapshot', () => {
  it('names the snapshot and the offering', () => {
    expect(snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1' })).toEqual({ snapshotid: 's-1', serviceofferingid: 'o-1' })
  })

  it('adds the zone and the data disk sizes when there are any', () => {
    expect(snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1', datadisksizes: '20' }))
      .toEqual({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1', datadisksizes: '20' })
    expect(snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1', datadisksizes: null }))
      .toEqual({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1' })
  })

  it('carries no root disk size, no volume and no template: the root is the snapshot', () => {
    const request = snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1', rootdisksize: 30, templateid: 't-1', volumeid: 'v-1' })
    expect(Object.keys(request).sort()).toEqual(['serviceofferingid', 'snapshotid', 'zoneid'])
  })

  it('is a combination the placement step asks the server about', () => {
    expect(placementKind(snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1' }))).toBe('deploy-from-snapshot')
    expect(placementKind(snapshotPlacementRequest({ snapshotid: 's-1', serviceofferingid: 'o-1', zoneid: 'z-1' }))).toBe('deploy-from-snapshot')
    expect(placementKind({ snapshotid: 's-1' })).toBeNull()
    expect(placementKind({ serviceofferingid: 'o-1' })).toBeNull()
  })

  it('leaves the other combinations as they were', () => {
    expect(placementKind({ virtualmachineid: 'v', snapshotid: 's' })).toBe('start')
    expect(placementKind({ volumeid: 'x', serviceofferingid: 'o' })).toBe('deploy-from-volume')
    expect(placementKind({ templateid: 't', serviceofferingid: 'o', zoneid: 'z' })).toBe('deploy')
  })
})
