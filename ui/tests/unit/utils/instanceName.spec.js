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

import { instanceName } from '@/utils/instanceName'

describe('the one name an instance is shown by', () => {
  it('is the visible name', () => {
    expect(instanceName({ id: 'vm-1', name: 'i-2-34-VM', displayname: 'lab' })).toBe('lab')
  })

  it('is the hostname only where there is no visible name', () => {
    expect(instanceName({ id: 'vm-1', name: 'old-vm' })).toBe('old-vm')
    expect(instanceName({ id: 'vm-1', name: 'old-vm', displayname: '' })).toBe('old-vm')
    expect(instanceName({ id: 'vm-1', name: 'old-vm', displayname: null })).toBe('old-vm')
  })

  it('is empty for nothing', () => {
    expect(instanceName(undefined)).toBe('')
    expect(instanceName(null)).toBe('')
    expect(instanceName({})).toBe('')
  })
})
