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

import { vueProps } from '@/vue-app'
import offering from '@/config/section/offering'

jest.mock('@/store', () => ({ getters: { userInfo: { roletype: 'User' }, apis: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const resolved = (value) => typeof value === 'function' ? value() : value

// the pages a student reaches by the offering's link on an instance, and the system offerings behind the same section
describe('the CPU clock on the offering pages follows showCpuSpeed', () => {
  afterEach(() => {
    vueProps.$config = {}
  })

  for (const name of ['computeoffering', 'systemoffering']) {
    const page = offering.children.find(child => child.name === name)

    it(`${name} lists, details and filters no CPU speed unless the flag is true`, () => {
      for (const config of [{}, { showCpuSpeed: false }]) {
        vueProps.$config = config
        expect(resolved(page.columns)).not.toContain('cpuspeed')
        expect(resolved(page.details)).not.toContain('cpuspeed')
        expect(resolved(page.searchFilters)).not.toContain('cpuspeed')
      }

      vueProps.$config = { showCpuSpeed: true }
      expect(resolved(page.columns)).toContain('cpuspeed')
      expect(resolved(page.details)).toContain('cpuspeed')
      expect(resolved(page.searchFilters)).toContain('cpuspeed')
    })

    it(`${name} keeps the other fields in their order`, () => {
      vueProps.$config = { showCpuSpeed: true }
      const withSpeed = resolved(page.columns)
      vueProps.$config = { showCpuSpeed: false }
      expect(resolved(page.columns)).toEqual(withSpeed.filter(column => column !== 'cpuspeed'))
      expect(resolved(page.columns)).toEqual(expect.arrayContaining(['name', 'cpunumber', 'memory']))
    })
  }
})
