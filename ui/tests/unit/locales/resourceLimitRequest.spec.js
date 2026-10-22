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

import en from '@public/locales/en.json'
import ko from '@public/locales/ko_KR.json'
import { STATES } from '@/utils/resourceLimitRequest'

const root = path.resolve(__dirname, '../../..')
const sources = [
  'src/components/header/PendingRequestBadge.vue',
  'src/components/header/ResourceLimitExpiryBanner.vue',
  'src/config/section/resourcerequest.js',
  'src/views/resourcerequest/Approvals.vue',
  'src/views/resourcerequest/ApproveRequestModal.vue',
  'src/views/resourcerequest/DecisionModal.vue',
  'src/views/resourcerequest/EffectiveQuota.vue',
  'src/views/resourcerequest/MyRequests.vue',
  'src/views/resourcerequest/RequestForm.vue',
  'src/views/resourcerequest/RequestTable.vue',
  'src/views/resourcerequest/RequestValues.vue'
]

const literalKeys = () => {
  const keys = new Set()
  sources.forEach(source => {
    const text = fs.readFileSync(path.join(root, source), 'utf8')
    for (const match of text.matchAll(/['"]((?:label|message)\.resource\.limit\.[a-z.]*[a-z])['"]/g)) {
      keys.add(match[1])
    }
  })
  return [...keys]
}

// Keys the pages assemble at run time.
const computedKeys = () => [
  ...STATES.map(state => 'label.resource.limit.request.state.' + state.toLowerCase()),
  ...['renew', 'cancel', 'giveup', 'approve', 'reject', 'revoke', 'history'].map(action => 'label.resource.limit.request.' + action),
  ...['cpu', 'memory', 'storage', 'gpu'].map(kind => 'label.resource.limit.request.value.' + kind)
]

const placeholders = (text) => (text.match(/\{[a-z]+\}/g) || []).sort()

describe('Resource limit request strings', () => {
  const keys = [...new Set([...literalKeys(), ...computedKeys()])]

  it('are found in the pages', () => {
    expect(keys.length).toBeGreaterThan(40)
  })

  it('exist in the English and Korean locale files', () => {
    expect(keys.filter(key => !en[key])).toEqual([])
    expect(keys.filter(key => !ko[key])).toEqual([])
  })

  it('take the same values in every language', () => {
    expect(keys.filter(key => en[key] && ko[key] && placeholders(en[key]).join() !== placeholders(ko[key]).join())).toEqual([])
  })

  it('name no product', () => {
    expect(keys.filter(key => /cloudstack|snucse|bacchus/i.test(en[key] + ko[key]))).toEqual([])
  })
})
