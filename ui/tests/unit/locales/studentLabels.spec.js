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

import store from '@/store'
import { sourceDirectory } from '../../common/studentFiles'
import compute from '@/config/section/compute'
import storage from '@/config/section/storage'
import event from '@/config/section/event'
import user from '@/config/section/user'
import account from '@/config/section/account'
import zones from '@/config/section/zone'
import keypair from '@/config/section/keypair'
import image from '@/config/section/image'
import offering from '@/config/section/offering'

jest.mock('@/store', () => ({
  __esModule: true,
  default: {
    getters: {
      apis: Object.fromEntries(require('../../mockData/MinimalUserApis.mock.json').apis.map(name => [name, { params: [], response: [] }])),
      userInfo: { roletype: 'User' },
      features: { instanceleaseenabled: true },
      zones: [{ id: 'z-1', securitygroupsenabled: false }],
      metrics: true,
      project: {},
      listAllProjects: false
    }
  }
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))

const locales = path.join(__dirname, '../../../public/locales')
const en = JSON.parse(fs.readFileSync(path.join(locales, 'en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(locales, 'ko_KR.json'), 'utf8'))

const resolved = (value) => typeof value === 'function' ? value() : value
const lowerKey = (title) => 'label.' + String(title).toLowerCase()

// the pages a student reaches, and the label families the shared views build from what a page lists: the title of a detail
// (DetailsTab), of a column (AutogenView), of a search filter (SearchView), of a tab (ResourceView) and of a list filter
const pages = () => {
  const children = (section, ...names) => section.children.filter(page => names.includes(page.name))
  return [
    ...children(compute, 'vm'), ...children(storage, 'volume', 'snapshot', 'snapshotpolicy'), ...children(image, 'template'),
    ...children(offering, 'computeoffering', 'diskoffering'), event, user, account, zones, keypair
  ]
}

const detailTitle = (detail) => typeof detail === 'object' ? detail.customTitle : detail
const columnTitle = (column) => {
  if (typeof column !== 'object') {
    return column
  }
  return 'customTitle' in column && 'field' in column ? column.customTitle : Object.keys(column)[0]
}
const searchFilterKey = (field) => {
  const named = { groupid: 'group', hsmprofileid: 'hsm.profile', kmskeyid: 'kms.key' }
  return 'label.' + (named[field] || field)
}

const families = () => {
  const keys = new Map()
  const add = (family, key) => keys.set(key, [...(keys.get(key) || []), family])
  for (const metrics of [true, false]) {
    store.getters.metrics = metrics
    for (const page of pages()) {
      resolved(page.details || []).forEach(detail => add('details of ' + page.name, lowerKey(detailTitle(detail))))
      resolved(page.columns || []).forEach(column => add('columns of ' + page.name, lowerKey(columnTitle(column))))
      resolved(page.searchFilters || []).forEach(field => add('search filters of ' + page.name, searchFilterKey(field)))
      resolved(page.filters || []).forEach(filter => add('list filters of ' + page.name, 'label.' + filter))
      ;(page.tabs || []).filter(tab => tab.name).forEach(tab => add('tabs of ' + page.name, 'label.' + tab.name))
    }
  }
  for (const row of ['vm', 'cpu', 'memory', 'gpu', 'primarystorage', 'volume', 'ip', 'network', 'vpc', 'secondarystorage', 'snapshot',
    'template', 'project', 'backup', 'backupstorage', 'bucket', 'objectstorage']) {
    add('usage rows', 'label.' + row + 'limit')
  }
  for (const day of ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday']) {
    add('days of the recurring snapshot', 'label.' + day)
  }
  const events = fs.readFileSync(path.join(sourceDirectory, 'components/view/EventsTab.vue'), 'utf8')
  const eventColumns = events.match(/columnKeys: \[([^\]]*)\]/)
  ;(eventColumns ? [...eventColumns[1].matchAll(/'([^']+)'/g)].map(match => match[1]) : []).forEach(key => add('columns of the events tab', lowerKey(key)))
  return keys
}

// The student default is Korean. These keys are assembled when a page is drawn, so the scan of string literals cannot find them.
describe('labels that the student pages assemble at run time', () => {
  const keys = families()

  it('are found for the pages a student reaches', () => {
    expect(keys.size).toBeGreaterThan(100)
    for (const key of ['label.snapshottype', 'label.volumetype', 'label.maxsnaps', 'label.resourcetype', 'label.is2faenabled',
      'label.apikeypair.startdate', 'label.gpuenabled', 'label.kmskey', 'label.diskofferingstrictness', 'label.gpucardid']) {
      expect(keys.has(key)).toBe(true)
    }
  })

  it('all have a Korean string where they have an English one', () => {
    const missing = [...keys.keys()].filter(key => key in en && (typeof ko[key] !== 'string' || ko[key].trim() === ''))
    expect(missing.sort()).toEqual([])
  })
})
