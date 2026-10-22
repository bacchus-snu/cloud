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

import * as gpuType from '@/utils/gpuType'
import { gpuTypeOf } from '@/utils/gpuType'
import { gpuTypeOf as limitsGpuTypeOf } from '@/utils/limits'
import * as requestUtils from '@/utils/resourceLimitRequest'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

const offering = (hosttags, gpucount = 1) => ({ hosttags, gpucount })

describe('the GPU type of an offering', () => {
  it('is none for an offering without GPUs', () => {
    expect(gpuTypeOf(offering('a100-40g', 0), ['a100-40g'])).toBeNull()
    expect(gpuTypeOf({ hosttags: 'a100-40g' }, ['a100-40g'])).toBeNull()
    expect(gpuTypeOf(undefined, ['a100-40g'])).toBeNull()
  })

  it('is the host tag of the offering that has a GPU limit row, never the CPU-only tag, wherever it stands in the list', () => {
    const types = ['a100-40g', 'cpu']
    expect(gpuTypeOf(offering('a100-40g'), types)).toBe('a100-40g')
    expect(gpuTypeOf(offering(' a100-40g '), types)).toBe('a100-40g')
    expect(gpuTypeOf(offering('a100-40g,cpu'), types)).toBe('a100-40g')
    expect(gpuTypeOf(offering('cpu,a100-40g'), types)).toBe('a100-40g')
    expect(gpuTypeOf(offering('cpu'), types)).toBeNull()
    expect(gpuTypeOf(offering(''), types)).toBeNull()
    expect(gpuTypeOf({ gpucount: 1 }, types)).toBeNull()
  })

  it('is the first of several tags that has a row, and none without a row', () => {
    const tagged = offering('spare,a100-40g,a6000')
    expect(gpuTypeOf(tagged, ['a6000', 'a100-40g'])).toBe('a100-40g')
    expect(gpuTypeOf(tagged, new Set(['a6000']))).toBe('a6000')
    expect(gpuTypeOf(tagged, { 'a100-40g': 2, cpu: 0 })).toBe('a100-40g')
    expect(gpuTypeOf(tagged, [])).toBeNull()
    expect(gpuTypeOf(offering('a100-40g'), ['a6000'])).toBeNull()
  })

  it('has one mode: without the GPU limit rows no tag names a type', () => {
    expect(gpuTypeOf(offering('a100-40g'))).toBeNull()
    expect(gpuTypeOf(offering('a100-40g,cpu'), null)).toBeNull()
    expect(gpuType.gpuTypesOf).toBeUndefined()
  })

  it('is the one the wizards use, and the request form no longer has a rule of its own', () => {
    expect(limitsGpuTypeOf).toBe(gpuTypeOf)
    expect(requestUtils.gpuTypesOf).toBeUndefined()
    expect(requestUtils.fetchGpuTypes).toBeUndefined()
  })
})
