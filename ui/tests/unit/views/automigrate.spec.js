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

import { postAPI } from '@/api'
import ScaleVM from '@/views/compute/ScaleVM'
import ChangeOfferingForVolume from '@/views/storage/ChangeOfferingForVolume'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
// the form's wizard steps are imported through a webpack alias the unit tests do not map
jest.mock('@views/compute/wizard/ComputeOfferingSelection', () => ({}), { virtual: true })
jest.mock('@views/compute/wizard/ComputeSelection', () => ({}), { virtual: true })
jest.mock('@views/compute/wizard/DiskSizeSelection', () => ({}), { virtual: true })

// A move to another storage pool deletes the disk's source copy; the forms ask for it only when the switch is turned on.
describe('Automatic migration of a volume', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    postAPI.mockReturnValue(new Promise(() => {}))
  })

  it('is off until asked for', () => {
    expect(ScaleVM.data.call({ resource: { id: 'vm-1' } }).autoMigrate).toBe(false)
    expect(ChangeOfferingForVolume.data.call({ resource: { id: 'vol-1' } }).autoMigrate).toBe(false)
  })

  it('follows the switch when the instance is scaled', () => {
    for (const autoMigrate of [false, true]) {
      const form = { ...ScaleVM.data.call({ resource: { id: 'vm-1' } }), autoMigrate, selectedOffering: {} }
      form.params.serviceofferingid = 'offering-1'
      ScaleVM.methods.handleSubmit.call(form)
      expect(postAPI).toHaveBeenLastCalledWith('scaleVirtualMachine', expect.objectContaining({ automigrate: autoMigrate }))
    }
  })
})
