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

import { getAPI } from '@/api'
import { CAPABILITY_WAIT_MS, fetchCapabilitiesWithin } from '@/utils/placement'

jest.mock('@/api', () => ({ getAPI: jest.fn() }))

describe('the capabilities asked for within a wait', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('waits about two seconds at most', () => {
    expect(CAPABILITY_WAIT_MS).toBe(2000)
  })

  it('gives the answer of the server when it comes in time', async () => {
    getAPI.mockResolvedValue({ listcapabilitiesresponse: { capability: { expungeondestroy: true } } })
    await expect(fetchCapabilitiesWithin(1000)).resolves.toEqual({ expungeondestroy: true })
    expect(getAPI).toHaveBeenCalledWith('listCapabilities', {})
  })

  it('asks for the capabilities of a domain when it is given one', async () => {
    getAPI.mockResolvedValue({ listcapabilitiesresponse: { capability: {} } })
    await fetchCapabilitiesWithin(1000, 'd-1')
    expect(getAPI).toHaveBeenCalledWith('listCapabilities', { domainid: 'd-1' })
  })

  it('gives nothing when the server does not answer in time, and ignores the late answer', async () => {
    let answer
    getAPI.mockReturnValue(new Promise(resolve => { answer = resolve }))
    const started = Date.now()
    await expect(fetchCapabilitiesWithin(40)).resolves.toEqual({})
    expect(Date.now() - started).toBeLessThan(1000)
    answer({ listcapabilitiesresponse: { capability: { expungeondestroy: true } } })
  })

  it('gives nothing when the request fails', async () => {
    getAPI.mockRejectedValue(new Error('down'))
    await expect(fetchCapabilitiesWithin(1000)).resolves.toEqual({})
  })
})
