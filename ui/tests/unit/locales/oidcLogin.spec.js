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

const login = fs.readFileSync(path.resolve(__dirname, '../../../src/views/auth/Login.vue'), 'utf8')

// The outcomes the server reports as oidcerror, whose messages the page assembles at run time.
const outcomes = ['unavailable', 'expired', 'denied', 'failed', 'identity', 'notadmitted', 'noaccount', 'conflict', 'disabled', 'refused']

const placeholders = (text) => (text.match(/\{[a-z]+\}/g) || []).sort()

describe('OpenID sign-in strings', () => {
  const literal = [...login.matchAll(/['"]((?:label\.login|message\.oidc)\.[a-z.]*[a-z])['"]/g)].map(match => match[1])
  const keys = [...new Set([...literal, ...outcomes.map(outcome => 'message.oidc.error.' + outcome)])]

  it('cover every outcome the server reports', () => {
    expect(login).toContain(JSON.stringify(outcomes).replace(/"/g, '\'').replace(/,/g, ', '))
    expect(keys).toEqual(expect.arrayContaining(['label.login.with.provider', 'label.login.password.form', 'message.oidc.end.session', 'message.oidc.error']))
  })

  it('exist in the English and Korean locale files', () => {
    expect(keys.filter(key => !en[key])).toEqual([])
    expect(keys.filter(key => !ko[key])).toEqual([])
  })

  it('take the same values in every language', () => {
    expect(keys.filter(key => placeholders(en[key]).join() !== placeholders(ko[key]).join())).toEqual([])
  })

  it('name no product', () => {
    expect(keys.filter(key => /cloudstack|snucse|bacchus/i.test(en[key] + ko[key]))).toEqual([])
  })
})
