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

import { i18n } from '@/locales'
import compute from '@/config/section/compute'
import network from '@/config/section/network'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn(), getBaseUrl: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [], project: {} } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const locales = path.join(__dirname, '../../../public/locales')
const read = (name) => JSON.parse(fs.readFileSync(path.join(locales, name + '.json'), 'utf8'))
const en = read('en')
const ko = read('ko_KR')

const resetActions = [compute, network].map(section =>
  section.children.flatMap(child => child.actions || []).find(action => action.api === 'resetPasswordForVirtualMachine'))

const answer = (virtualmachine) => ({ virtualmachine })

describe('the reset of the instance password answers in the language of the page', () => {
  beforeAll(() => {
    i18n.global.setLocaleMessage('en', en)
    i18n.global.setLocaleMessage('ko_KR', ko)
  })

  afterEach(() => {
    i18n.global.locale = 'en'
  })

  it('is on the instance and on the appliance pages', () => {
    expect(resetActions).toHaveLength(2)
    resetActions.forEach(action => expect(action).toBeDefined())
  })

  for (const [index, section] of ['instance', 'appliance'].entries()) {
    describe(`on the ${section} page`, () => {
      const { response } = resetActions[index]

      it('names the instance and the new password, and says it is for the login account and the web console', () => {
        i18n.global.locale = 'ko_KR'
        const result = response(answer({ displayname: 'My-VM', password: 'Xy7kq2Lp' }))
        expect(result.message).toBe(ko['message.instance.password.reset']
          .replace('{name}', '<b>My-VM</b>').replace('{password}', '<b>Xy7kq2Lp</b>'))
        expect(result.message).toContain('다음에 시작할 때 로그인 계정에 적용되며, 웹 콘솔 로그인에만 쓰입니다.')
        expect(result.message).not.toContain('The password of VM')
        expect(result.copybuttontext).toBe('label.copy.password')
        expect(result.copytext).toBe('Xy7kq2Lp')
      })

      it('follows the language', () => {
        const result = response(answer({ displayname: 'My-VM', password: 'Xy7kq2Lp' }))
        expect(result.message).toBe(en['message.instance.password.reset']
          .replace('{name}', '<b>My-VM</b>').replace('{password}', '<b>Xy7kq2Lp</b>'))
      })

      it('escapes the name, which the notice renders as HTML', () => {
        const result = response(answer({ displayname: '<img src=x onerror=alert(1)>', password: 'Xy7kq2Lp' }))
        expect(result.message).toContain('<b>&lt;img src=x onerror=alert(1)&gt;</b>')
        expect(result.message).not.toContain('<img')
      })

      it('has no message and no copy when the answer carries no password', () => {
        expect(response(answer({ displayname: 'My-VM' }))).toEqual({ message: null, copybuttontext: null, copytext: null })
      })
    })
  }
})
