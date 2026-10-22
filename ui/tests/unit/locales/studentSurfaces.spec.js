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

import { STUDENT_CONFIG, STUDENT_FILES, filesOf, sourceDirectory } from '../../common/studentFiles'

const locales = path.join(__dirname, '../../../public/locales')
const en = JSON.parse(fs.readFileSync(path.join(locales, 'en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(locales, 'ko_KR.json'), 'utf8'))

const placeholders = (text) => (text.match(/\{\w+\}/g) || []).sort()

// the keys the student-reachable files name as string literals, which have an English string (a literal that is no key is skipped)
const LITERAL = /['"`]((?:label|message|error|state|status|alert)\.[A-Za-z0-9_.-]+)['"`]/g
const referenced = () => {
  const keys = new Set()
  for (const file of [...STUDENT_FILES, ...STUDENT_CONFIG].flatMap(filesOf)) {
    const text = fs.readFileSync(path.join(sourceDirectory, file), 'utf8')
    for (const match of text.matchAll(LITERAL)) {
      if (match[1] in en) {
        keys.add(match[1])
      }
    }
  }
  return [...keys].sort()
}

// The student default is Korean: a string with no Korean one shows English in the middle of a Korean page.
describe('strings that students see', () => {
  const keys = referenced()

  it('are found in the files students reach, among them the session notice and the strings of the instance page', () => {
    expect(keys.length).toBeGreaterThan(400)
    for (const key of ['message.session.replaced', 'label.copy.password', 'label.deleteprotection', 'label.leaseexpiryaction', 'label.remainingdays']) {
      expect(keys).toContain(key)
    }
  })

  // named by a label built at run time, so that no scan of the files finds them
  it('have a Korean string for the labels the dashboard builds from a usage row, and for the lease and GPU notices', () => {
    for (const key of ['label.gpulimit', 'label.leasedinstances', 'label.leaseexpiry.date.and.time']) {
      expect(en[key]).toBeTruthy()
      expect(typeof ko[key] === 'string' && ko[key].trim() !== '').toBe(true)
      expect(ko[key]).not.toBe(en[key])
    }
  })

  it('all have a Korean string', () => {
    expect(keys.filter(key => typeof ko[key] !== 'string' || ko[key].trim() === '')).toEqual([])
  })

  it('keep the placeholders of the English string', () => {
    expect(keys.filter(key => ko[key] && JSON.stringify(placeholders(ko[key])) !== JSON.stringify(placeholders(en[key])))).toEqual([])
  })

  // the term of the student docs for what the server calls an Instance
  describe('the term for an Instance', () => {
    const VM = /(?<![A-Za-z])VM(?![A-Za-z])/
    // a virtual machine of the platform itself, which keeps its name
    const SYSTEM_VM = /(시스템|콘솔 프록시|2차 스토리지|LB|라우터) VM/
    // strings whose English names an Instance but whose Korean names such a machine
    const SYSTEM_VM_KEYS = ['label.start.lb.vm', 'message.confirm.start.lb.vm', 'message.confirm.scale.up.router.vm']

    it('is 인스턴스 in every Korean string, not 가상머신', () => {
      expect(Object.keys(ko).filter(key => ko[key].includes('가상머신'))).toEqual([])
    })

    it('is 인스턴스 and not the abbreviation VM in every Korean string whose English names an Instance', () => {
      const abbreviated = Object.keys(ko).filter(key => key in en && /\bInstances?\b/.test(en[key]) && VM.test(ko[key]) && !SYSTEM_VM_KEYS.includes(key))
      expect(abbreviated).toEqual([])
      for (const key of SYSTEM_VM_KEYS) {
        expect(ko[key]).toMatch(SYSTEM_VM)
      }
    })

    it('leaves VM only in the names of the platform\'s own machines', () => {
      expect(Object.keys(ko).filter(key => VM.test(ko[key]) && !SYSTEM_VM.test(ko[key]))).toEqual([])
      expect(ko['message.update.ssl']).toContain('콘솔 프록시 VM')
      expect(ko['label.internallbvm']).toBe('내부 LB VM')
      expect(ko['label.console.proxy.vm']).toContain('VM')
    })

    it('is the one term in the labels of the pages and in the named strings', () => {
      for (const key of ['label.vms', 'label.launch.vm', 'label.running', 'label.stopped', 'label.vmtotal', 'label.vmstate', 'label.localstorageenabled']) {
        expect(ko[key]).toContain('인스턴스')
        expect(ko[key]).not.toMatch(VM)
      }
      expect(ko['label.create.instance']).toBe('인스턴스 생성')
      expect(ko['label.create.instance']).toContain(ko['label.create.vm'].replace(' 생성', ''))
    })

    it('puts the particles that follow it as a noun without a final consonant takes them', () => {
      const wrong = Object.keys(ko).filter(key => /인스턴스(은|을|으로|과|이(?=[\s.,;:)]|$))/.test(ko[key]))
      expect(wrong).toEqual([])
    })
  })

  describe('the wording of the strings that stand beside others', () => {
    it('counts a selection in one message that puts the number before the unit without a space', () => {
      expect(en['label.items.selected.count']).toBe('{count} item(s) selected')
      expect(ko['label.items.selected.count']).toBe('{count}개 선택됨')
      const callers = []
      for (const file of [...STUDENT_FILES, ...STUDENT_CONFIG].flatMap(filesOf)) {
        if (/['"`]label\.items\.selected['"`]/.test(fs.readFileSync(path.join(sourceDirectory, file), 'utf8'))) {
          callers.push(file)
        }
      }
      expect(callers).toEqual([])
    })

    it('reads evenly in the memory chart choice and in the notices', () => {
      expect(ko['label.used']).toBe('사용된')
      expect(ko['label.free']).toBe('남은')
      expect(ko['state.inprogress']).toBe('진행 중')
      expect(ko['message.alert.show.all.stats.data']).toContain('조회될')
    })
  })
})
