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

const keys = [
  'label.os.disk',
  'label.os.disk.size.change',
  'label.placement',
  'label.placement.move.title',
  'label.placement.move.approve',
  'label.placement.move.reject',
  'label.placement.used',
  'label.placement.eta.seconds',
  'label.placement.eta.minutes',
  'label.placement.eta.hours',
  'label.keep.root.disk',
  'label.offering.over.quota',
  'message.os.disk',
  'message.placement.move.intro.start',
  'message.placement.move.intro.attach',
  'message.placement.move.intro.deploy',
  'message.placement.move.estimate',
  'message.placement.move.unavailable',
  'message.placement.move.copy.kept',
  'message.placement.move.copy.removed',
  'message.placement.here',
  'message.placement.anyhost',
  'message.placement.move',
  'message.placement.none',
  'message.placement.unavailable',
  'message.placement.incomplete',
  'message.placement.capacity.largest',
  'message.placement.capacity.thishost',
  'message.placement.capacity.gpuslots',
  'message.placement.hint',
  'message.placement.start.refused',
  'message.placement.working',
  'message.placement.working.attach',
  'message.placement.check.failed',
  'message.placement.reason.quota',
  'message.placement.reason.storage.quota',
  'message.placement.reason.cpu.pool',
  'message.placement.reason.vcpu.step',
  'message.placement.reason.memory',
  'message.placement.reason.local.storage',
  'message.placement.reason.volumes.unavailable',
  'message.placement.reason.instance.snapshots',
  'message.placement.reason.volume.snapshots',
  'message.placement.reason.encrypted.volume',
  'message.placement.reason.affinity.groups',
  'message.placement.reason.suitable.host',
  'message.placement.reason.gpu.group',
  'message.keep.root.disk',
  'message.error.vcpu.step',
  'message.error.quota.below.minimum',
  'message.error.limit.maximum'
]

const placeholders = (text) => (text.match(/\{[a-z]+\}/g) || []).sort()

describe('locales > placement, root disk and approved moves', () => {
  it('both languages have every key, with the same placeholders', () => {
    for (const key of keys) {
      expect(typeof en[key]).toBe('string')
      expect(typeof ko[key]).toBe('string')
      expect(en[key].length).toBeGreaterThan(0)
      expect(ko[key].length).toBeGreaterThan(0)
      expect(placeholders(ko[key])).toEqual(placeholders(en[key]))
    }
  })

  it('the Korean strings are Korean, and the label of the checkbox is the one the manual uses', () => {
    for (const key of keys) {
      expect(ko[key]).toMatch(/[ㄱ-힝]/)
    }
    expect(ko['label.keep.root.disk']).toBe('루트 디스크 남기기')
  })

  it('every key of this unit that the code asks for exists in both languages', () => {
    const files = ['views/compute/wizard/PlacementStep.vue', 'views/compute/wizard/PlacementMoveModal.vue', 'views/compute/wizard/ComputeOfferingSelection.vue',
      'views/compute/wizard/ComputeSelection.vue', 'views/compute/wizard/DiskSizeSelection.vue', 'views/compute/DeployVM.vue', 'views/compute/ScaleVM.vue',
      'views/compute/StartVirtualMachine.vue', 'views/compute/DestroyVM.vue', 'views/storage/AttachVolume.vue', 'views/compute/ReinstallVm.vue', 'utils/placement.js',
      'utils/placementApproval.js']
    const added = /^(label\.(os\.disk|placement|keep\.root\.disk|offering\.over\.quota)|message\.(os\.disk|placement\.|keep\.root\.disk|error\.(vcpu\.step|quota\.below\.minimum|limit\.maximum)))/
    const asked = new Set()
    for (const file of files) {
      const text = fs.readFileSync(path.join(__dirname, '../../../src', file), 'utf8')
      for (const match of text.matchAll(/'((?:label|message)\.[a-z0-9.]+)'/g)) {
        if (!match[1].endsWith('.')) {
          asked.add(match[1])
        }
      }
    }
    const mine = [...asked].filter(key => added.test(key))
    expect(mine.length).toBeGreaterThan(25)
    expect(mine.filter(key => !(key in en))).toEqual([])
    expect(mine.filter(key => !(key in ko))).toEqual([])
  })

  it('the messages avoid the characters the message format reads as syntax', () => {
    for (const key of keys) {
      for (const text of [en[key], ko[key]]) {
        expect(text).not.toMatch(/[@|<>]/)
      }
    }
  })
})
