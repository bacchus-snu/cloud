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

import { canEditTags } from '@/utils/resourceTags'
import { sourceDirectory } from '../../common/studentFiles'

const vueFiles = (directory) => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
  const full = path.join(directory, entry.name)
  if (entry.isDirectory()) {
    return vueFiles(full)
  }
  return entry.name.endsWith('.vue') ? [full] : []
})

// a page offers the free-form tag controls when it opens the tag dialog of a rule or lists a new-tag control
const OFFERS_TAGS = /openTagsModal\(|label\.new\.tag/

describe('who edits the tags of a resource with the free-form controls', () => {
  it('is every role type but User', () => {
    expect(canEditTags('User')).toBe(false)
    for (const roletype of ['Admin', 'DomainAdmin', 'ResourceAdmin', undefined]) {
      expect(canEditTags(roletype)).toBe(true)
    }
  })

  it('is decided in every page that offers them, by the one shared check', () => {
    const offering = vueFiles(sourceDirectory)
      .filter(file => OFFERS_TAGS.test(fs.readFileSync(file, 'utf8')))
      .map(file => path.relative(sourceDirectory, file))
      .sort()
    expect(offering).toEqual([
      'components/view/InfoCard.vue',
      'views/compute/AutoScaleLoadBalancing.vue',
      'views/network/AclRulesTab.vue',
      'views/network/FirewallRules.vue',
      'views/network/IngressEgressRuleConfigure.vue',
      'views/network/LoadBalancing.vue',
      'views/network/PortForwarding.vue',
      'views/network/StaticRoutesTab.vue',
      'views/storage/FormSchedule.vue',
      'views/storage/TakeSnapshot.vue'
    ])
    const unguarded = offering.filter(file => !fs.readFileSync(path.join(sourceDirectory, file), 'utf8').includes('$canEditTags()'))
    expect(unguarded).toEqual([])
  })

  it('has a guard on every control that opens a tag dialog, not only somewhere in the page', () => {
    for (const file of vueFiles(sourceDirectory)) {
      const lines = fs.readFileSync(file, 'utf8').split('\n')
      lines.forEach((line, i) => {
        if (/@onClick="\(\) => openTagsModal\(/.test(line)) {
          expect(`${path.relative(sourceDirectory, file)}:${i + 1} ${line.includes('v-if="$canEditTags()"')}`).toMatch(/true$/)
        }
      })
    }
  })
})
