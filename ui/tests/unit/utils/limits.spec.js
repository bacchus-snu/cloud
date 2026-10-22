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

import { exceedsGpuQuota, gpuRemaining } from '@/utils/limits'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const offering = (hosttags, gpucount = 1) => ({ hosttags, gpucount })

// the rows of an account that follows grants: one GPU row per resource limit host tag, the CPU-only tag's among them
const rows = (counts) => ({
  limits: Object.entries(counts).map(([tag, max]) => ({ resourcetypename: 'gpu', resourcetype: '16', tag, max: String(max) })),
  usage: []
})

describe('the GPU quota an offering is checked against', () => {
  // the server reserves the GPUs on every limit tag of the offering, so every tag that has a row must have room
  const remaining = gpuRemaining(rows({ T: 2, A: 2, B: 0, cpu: 0 }))

  it('is the tag of an offering tagged with its type only', () => {
    expect(exceedsGpuQuota(offering('T'), remaining)).toBe(false)
    expect(exceedsGpuQuota(offering('T', 2), remaining)).toBe(false)
    expect(exceedsGpuQuota(offering('T', 3), remaining)).toBe(true)
  })

  it('leaves out a tag that has no row, such as the class tag of the catalog', () => {
    expect(exceedsGpuQuota(offering('T,T-class'), remaining)).toBe(false)
    expect(exceedsGpuQuota(offering('T-class,T', 3), remaining)).toBe(true)
  })

  it('counts the CPU-only tag, whose GPU row is zero, and does not depend on the order of the tags', () => {
    expect(exceedsGpuQuota(offering('T,cpu'), remaining)).toBe(true)
    expect(exceedsGpuQuota(offering('cpu,T'), remaining)).toBe(true)
  })

  it('needs room on every tag of several GPU types', () => {
    expect(exceedsGpuQuota(offering('A,B'), remaining)).toBe(true)
    expect(exceedsGpuQuota(offering('B,A'), remaining)).toBe(true)
    expect(exceedsGpuQuota(offering('A,T', 2), remaining)).toBe(false)
    expect(exceedsGpuQuota(offering('A,T', 3), remaining)).toBe(true)
  })

  it('is never exceeded without GPUs, without a tag, without rows, or on an unlimited tag', () => {
    expect(exceedsGpuQuota(offering('B', 0), remaining)).toBe(false)
    expect(exceedsGpuQuota({ gpucount: 1 }, remaining)).toBe(false)
    expect(exceedsGpuQuota(offering('T', 9), undefined)).toBe(false)
    expect(exceedsGpuQuota(offering('T', 9), gpuRemaining(rows({ T: -1 })))).toBe(false)
    expect(exceedsGpuQuota(offering('X', 9), remaining)).toBe(false)
    expect(exceedsGpuQuota(undefined, remaining)).toBe(false)
  })
})
