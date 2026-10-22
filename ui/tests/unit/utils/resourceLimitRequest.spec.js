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

import mockData from '../../mockData/ResourceLimitRequest.mock.json'
import {
  approvalParams,
  dayInZone,
  daysLeft,
  effectiveQuota,
  expiryPolicy,
  isCoveredByLaterGrants,
  outsideExpiryWindow,
  quotaGpuTypes,
  renewalOf,
  requestParams,
  requestsAllowed,
  valueItems
} from '@/utils/resourceLimitRequest'

describe('utils/resourceLimitRequest', () => {
  beforeEach(() => {
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse(mockData.now))
  })

  afterEach(() => {
    jest.restoreAllMocks()
  })

  describe('GPU types', () => {
    it('are the sorted names the server lists in the capabilities, once each', () => {
      expect(quotaGpuTypes(mockData.features)).toEqual(['a100-40g', 'a6000'])
      expect(quotaGpuTypes({ quotagputypes: ['h100', 'a6000', 'h100'] })).toEqual(['a6000', 'h100'])
      expect(quotaGpuTypes({ quotagputypes: [] })).toEqual([])
    })

    it('are unknown where the server does not say, and are never derived from offerings', () => {
      expect(quotaGpuTypes({})).toBeNull()
      expect(quotaGpuTypes(undefined)).toBeNull()
      expect(quotaGpuTypes({ quotagputypes: 'a100-40g' })).toBeNull()
      expect(quotaGpuTypes({ quotagputypes: null })).toBeNull()
    })
  })

  describe('request parameters', () => {
    it('send memory in MiB, GPUs as gpu[i].type and gpu[i].count, and leave out what is empty', () => {
      expect(requestParams({
        cpu: 4,
        memoryGib: 1.5,
        storage: 100,
        gpu: { a6000: 0, 'a100-40g': 2 },
        expiry: '2026-12-31',
        reason: '  lab work  '
      })).toEqual({
        cpu: 4,
        memory: 1536,
        storage: 100,
        'gpu[0].type': 'a100-40g',
        'gpu[0].count': 2,
        expiry: '2026-12-31',
        reason: 'lab work'
      })
      expect(requestParams({ cpu: null, memoryGib: 0, storage: undefined, gpu: {}, expiry: null, reason: '' }))
        .toEqual({ cpu: 0, memory: 0, storage: 0 })
    })

    it('of an approval name every value, including the GPU types the approver zeroed', () => {
      expect(approvalParams('r1', {
        cpu: 8,
        memoryGib: 32,
        storage: 500,
        gpu: { 'a100-40g': 0, a6000: 1 },
        expiry: '2026-11-30',
        comment: ' fine '
      })).toEqual({
        id: 'r1',
        cpu: 8,
        memory: 32768,
        storage: 500,
        'gpu[0].type': 'a100-40g',
        'gpu[0].count': 0,
        'gpu[1].type': 'a6000',
        'gpu[1].count': 1,
        expiry: '2026-11-30',
        comment: 'fine'
      })
    })
  })

  describe('values', () => {
    it('list only what is asked for', () => {
      expect(valueItems({ cpu: 8, memory: 1536, storage: 0, gpu: { a6000: 0, 'a100-40g': 2 } })).toEqual([
        { key: 'cpu', n: 8 },
        { key: 'memory', n: '1.5' },
        { key: 'gpu', type: 'a100-40g', n: 2 }
      ])
    })

    it('of a renewal are the grant\'s own, or the requested ones when it never carried any', () => {
      expect(renewalOf(mockData.approved)).toEqual({
        cpu: 8, memory: 32768, storage: 500, gpu: { 'a100-40g': 2 }, reason: 'course project'
      })
      expect(renewalOf(mockData.pending)).toMatchObject({ cpu: 16, memory: 65536, storage: 1000, gpu: { 'a100-40g': 4 } })
    })
  })

  describe('expiry', () => {
    it('is a calendar day of the expiry zone', () => {
      expect(dayInZone('2026-12-31T14:59:59+0000', 'Asia/Seoul')).toBe('2026-12-31')
      expect(dayInZone('2026-12-31T15:00:01+0000', 'Asia/Seoul')).toBe('2027-01-01')
      expect(dayInZone('2026-12-31T15:00:01+0000', 'UTC')).toBe('2026-12-31')
      expect(dayInZone('2026-12-31T15:00:01+0000', 'Not/AZone')).toBe('2026-12-31')
      expect(dayInZone('not a date', 'UTC')).toBeNull()
    })

    it('counts the days left in that zone', () => {
      expect(daysLeft('2026-10-27T14:59:59+0000', 'Asia/Seoul')).toBe(7)
      expect(daysLeft('2026-10-20T14:59:59+0000', 'Asia/Seoul')).toBe(0)
      expect(daysLeft('2026-10-19T14:59:59+0000', 'Asia/Seoul')).toBe(-1)
    })

    it('policy follows the capabilities: default and cap in days, and the cap makes an end date necessary', () => {
      expect(expiryPolicy(mockData.features)).toEqual({
        timezone: 'Asia/Seoul', required: true, minDay: '2026-10-20', maxDay: '2027-01-18', defaultDay: '2026-11-19'
      })
      expect(expiryPolicy({
        resourcerequestexpiryrequired: false,
        resourcerequestexpirydefaultdays: 0,
        resourcerequestexpirymaxdays: 0,
        resourcerequestexpirytimezone: 'UTC'
      })).toEqual({ timezone: 'UTC', required: false, minDay: '2026-10-20', maxDay: null, defaultDay: null })
      expect(expiryPolicy({ resourcerequestexpiryrequired: false, resourcerequestexpirymaxdays: 30 }).required).toBe(true)
      expect(expiryPolicy({ resourcerequestexpirydefaultdays: 365, resourcerequestexpirymaxdays: 30, resourcerequestexpirytimezone: 'UTC' }).defaultDay)
        .toBe('2026-11-19')
    })

    it('window excludes the days before today and after the cap, and no later day when there is no cap', () => {
      const day = (value) => ({ format: () => value })
      const policy = { minDay: '2026-10-20', maxDay: '2027-01-18' }
      expect(outsideExpiryWindow(policy, day('2026-10-19'))).toBe(true)
      expect(outsideExpiryWindow(policy, day('2026-10-20'))).toBe(false)
      expect(outsideExpiryWindow(policy, day('2027-01-18'))).toBe(false)
      expect(outsideExpiryWindow(policy, day('2027-01-19'))).toBe(true)
      expect(outsideExpiryWindow({ minDay: '2026-10-20', maxDay: null }, day('2099-01-01'))).toBe(false)
    })

    it('policy starts the day in the expiry zone, not the browser\'s', () => {
      Date.now.mockReturnValue(Date.parse('2026-10-19T16:00:00Z'))
      expect(expiryPolicy({ resourcerequestexpirytimezone: 'Asia/Seoul' }).minDay).toBe('2026-10-20')
      expect(expiryPolicy({ resourcerequestexpirytimezone: 'UTC' }).minDay).toBe('2026-10-19')
    })
  })

  describe('effective quota', () => {
    it('reads the rows of the host tag that is no GPU type for Instances without a GPU', () => {
      expect(effectiveQuota(mockData.limits, ['a100-40g', 'a6000'])).toEqual({
        cpu: 8,
        memory: 32768,
        storage: 500,
        gpu: [{ type: 'a100-40g', max: 2 }, { type: 'a6000', max: 0 }]
      })
    })

    it('shows no figure for Instances without a GPU when the host tags that are no known GPU type disagree', () => {
      const rows = [
        { resourcetypename: 'cpu', tag: 'cpu', max: 8 },
        { resourcetypename: 'cpu', tag: 'h100', max: 32 },
        { resourcetypename: 'memory', tag: 'cpu', max: 32768 },
        { resourcetypename: 'memory', tag: 'h100', max: 32768 },
        { resourcetypename: 'primary_storage', max: 500 }
      ]
      expect(effectiveQuota(rows, ['a100-40g'])).toMatchObject({ cpu: null, memory: 32768, storage: 500 })
      expect(effectiveQuota(rows, ['a100-40g', 'h100'])).toMatchObject({ cpu: 8, memory: 32768 })
    })

    it('does not guess the tags while the GPU types are unknown', () => {
      expect(effectiveQuota(mockData.limits, null)).toEqual({ cpu: null, memory: null, storage: 500, gpu: [] })
    })

    it('reads the untagged rows when no tag is configured, and shows unlimited as -1', () => {
      expect(effectiveQuota([
        { resourcetypename: 'cpu', max: -1 },
        { resourcetypename: 'memory' },
        { resourcetypename: 'primary_storage', max: 20 }
      ], [])).toEqual({ cpu: -1, memory: -1, storage: 20, gpu: [] })
    })
  })

  describe('requests allowed', () => {
    it('are what the server says: only a true capability allows them', () => {
      expect(requestsAllowed({ resourcelimitrequestallowed: true })).toBe(true)
      expect(requestsAllowed({ resourcelimitrequestallowed: false })).toBe(false)
      expect(requestsAllowed({})).toBe(false)
      expect(requestsAllowed(undefined)).toBe(false)
    })
  })

  describe('a grant covered by later grants', () => {
    const grant = (id, values, expiry) => ({
      id,
      state: 'Approved',
      approvedcpu: values.cpu || 0,
      approvedmemory: values.memory || 0,
      approvedstorage: values.storage || 0,
      approvedgpu: values.gpu || {},
      approvedexpiry: expiry
    })
    const ending = '2026-10-27T14:59:59+0000'
    const later = '2026-12-31T14:59:59+0000'
    const asked = grant('g', { cpu: 8, memory: 32768, storage: 500, gpu: { 'a100-40g': 2 } }, ending)

    it('loses nothing when one later grant gives at least as much in every category', () => {
      const same = grant('h', { cpu: 8, memory: 32768, storage: 500, gpu: { 'a100-40g': 2 } }, later)
      expect(isCoveredByLaterGrants(asked, [asked, same])).toBe(true)
      expect(isCoveredByLaterGrants(asked, [asked, grant('h', { cpu: 16, memory: 65536, storage: 1000, gpu: { 'a100-40g': 4, a6000: 1 } }, later)])).toBe(true)
    })

    it('loses a category when no later grant gives as much of it', () => {
      expect(isCoveredByLaterGrants(asked, [asked, grant('h', { cpu: 8, memory: 32768, storage: 400, gpu: { 'a100-40g': 2 } }, later)])).toBe(false)
      expect(isCoveredByLaterGrants(asked, [asked, grant('h', { cpu: 8, memory: 32768, storage: 500 }, later)])).toBe(false)
    })

    it('counts the largest value per category over the later grants, not their sum', () => {
      const cpuAndMemory = grant('h1', { cpu: 8, memory: 32768 }, later)
      const rest = grant('h2', { storage: 500, gpu: { 'a100-40g': 2 } }, later)
      expect(isCoveredByLaterGrants(asked, [asked, cpuAndMemory, rest])).toBe(true)
      const half = grant('h3', { cpu: 4, memory: 16384, storage: 250, gpu: { 'a100-40g': 1 } }, later)
      expect(isCoveredByLaterGrants(asked, [asked, half, { ...half, id: 'h4' }])).toBe(false)
    })

    it('ignores grants that end earlier or the same day, are not approved, or are the grant itself', () => {
      const big = { cpu: 64, memory: 262144, storage: 5000, gpu: { 'a100-40g': 8 } }
      expect(isCoveredByLaterGrants(asked, [asked, grant('h', big, '2026-10-20T14:59:59+0000')])).toBe(false)
      expect(isCoveredByLaterGrants(asked, [asked, grant('h', big, ending)])).toBe(false)
      expect(isCoveredByLaterGrants(asked, [asked, { ...grant('h', big, later), state: 'Revoked' }])).toBe(false)
      expect(isCoveredByLaterGrants(asked, [asked])).toBe(false)
    })

    it('takes a grant without an end as ending later than any', () => {
      const forever = grant('h', { cpu: 8, memory: 32768, storage: 500, gpu: { 'a100-40g': 2 } })
      delete forever.approvedexpiry
      expect(isCoveredByLaterGrants(asked, [asked, forever])).toBe(true)
    })

    it('asks nothing of a category the grant does not give', () => {
      const cpuOnly = grant('g2', { cpu: 4 }, ending)
      expect(isCoveredByLaterGrants(cpuOnly, [cpuOnly, grant('h', { cpu: 4 }, later)])).toBe(true)
      expect(isCoveredByLaterGrants(cpuOnly, [cpuOnly, grant('h', { storage: 1000 }, later)])).toBe(false)
    })
  })
})
