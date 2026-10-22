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

import fs from 'fs'
import path from 'path'
import { flushPromises } from '@vue/test-utils'

import common from '../../common'
import { getAPI } from '@/api'
import vcpuStepMixin from '@/utils/vcpuStep'
import { vcpuMinimum, vcpuStep } from '@/utils/placement'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'Admin' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

jest.setTimeout(30000)

const view = (file) => fs.readFileSync(path.join(__dirname, '../../../src/views/compute', file), 'utf8')

const Probe = { mixins: [vcpuStepMixin], template: '<div>{{ cpuStep }}</div>' }

const mountProbe = async (capability) => {
  getAPI.mockImplementation(() => capability === 'fail'
    ? Promise.reject(new Error('down'))
    : Promise.resolve({ listcapabilitiesresponse: { capability } }))
  const wrapper = common.createFactory(Probe, { i18n: common.createMockI18n('en', {}) })
  await flushPromises()
  return wrapper
}

describe('the vCPU step of the wizards that are not the deployment wizard', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('takes the step from listCapabilities, 2 when the server does not say', async () => {
    expect((await mountProbe({ poolvcpustep: 4 })).vm.cpuStep).toBe(4)
    expect((await mountProbe({ poolvcpustep: 1 })).vm.cpuStep).toBe(1)
    expect((await mountProbe({})).vm.cpuStep).toBe(2)
    expect((await mountProbe('fail')).vm.cpuStep).toBe(2)
  })

  it('rounds an offering\'s minimum up to a multiple of the step', async () => {
    const wrapper = await mountProbe({ poolvcpustep: 2 })
    expect(wrapper.vm.cpuMinimum(1)).toBe(2)
    expect(wrapper.vm.cpuMinimum(2)).toBe(2)
    expect(wrapper.vm.cpuMinimum(3)).toBe(4)
    expect(vcpuMinimum(3, vcpuStep({ poolvcpustep: 1 }))).toBe(3)
  })

  it('is used by the instance and AutoScale wizards, which hand the step and minimum to the vCPU input', () => {
    for (const file of ['DeployVnfAppliance.vue', 'CreateAutoScaleVmGroup.vue']) {
      const text = view(file)
      expect(text).toContain('vcpuStepMixin')
      expect(text).toContain(':cpuStep="cpuStep"')
      expect(text).toContain('cpuMinimum(')
    }
  })
})
