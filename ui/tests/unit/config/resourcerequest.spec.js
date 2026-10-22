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
import section from '@/config/section/resourcerequest'

jest.mock('@/store', () => ({ getters: { features: {} } }))

const page = (name) => section.children.find(child => child.name === name)

describe('config/section/resourcerequest', () => {
  afterEach(() => {
    store.getters.features = {}
  })

  it('lists "My requests" only while the server says the account\'s limits come from grants', () => {
    const mine = page('resourcelimitrequest')

    store.getters.features = { resourcelimitrequestallowed: true }
    expect(mine.show()).toBe(true)
    store.getters.features = { resourcelimitrequestallowed: false }
    expect(mine.show()).toBe(false)
    store.getters.features = {}
    expect(mine.show()).toBe(false)
  })

  it('leaves the approver page to the API list, never to the capability or the account type', () => {
    const approvals = page('resourcelimitapproval')

    expect(approvals.show).toBeUndefined()
    expect(approvals.permission).toEqual(['listResourceLimitRequests', 'approveResourceLimitRequest'])
    expect(page('resourcelimitrequest').permission).toEqual(['listResourceLimitRequests', 'createResourceLimitRequest'])
  })
})
