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
import brandFile from '@brand/brand.json'
import { i18n, loadLanguageAsync } from '@/locales'

// The only places a locale string may still say CloudStack: the upstream project and its documentation,
// and the name of the upstream CSI driver. Everything that names this product takes {product}.
const upstream = {
  'label.enable.csi': ['CloudStack CSI Driver'],
  'message.desc.edge.zone': ['Apache CloudStack documentation'],
  'message.offering.ipv6.warning': ['IPv6 support in CloudStack'],
  'message.installwizard.cloudstack.helptext.header': ['Apache CloudStack™']
}

const withoutUpstream = (key, value) => (upstream[key] || []).reduce((text, fragment) => text.split(fragment).join(''), value)

describe('locales > the product name is a parameter', () => {
  const locales = { en, ko_KR: ko }

  Object.entries(locales).forEach(([name, messages]) => {
    it(name + ' says CloudStack only where it means the upstream project', () => {
      const literal = Object.entries(messages)
        .filter(([key, value]) => typeof value === 'string' && withoutUpstream(key, value).includes('CloudStack'))
        .map(([key]) => key)
      expect(literal).toEqual([])
    })

    it(name + ' strings never hold the product name itself', () => {
      const holders = Object.entries(messages)
        .filter(([key, value]) => typeof value === 'string' && value.includes(brandFile.name))
        .map(([key]) => key)
      expect(holders).toEqual([])
    })
  })

  it('the strings that name the product take {product} in both languages', () => {
    const keys = [
      'label.about.app',
      'label.app.name',
      'label.installwizard.title',
      'label.installwizard.subtitle',
      'label.what.is.cloudstack',
      'label.introduction.to.cloudstack',
      'message.desc.host',
      'message.desc.zone',
      'message.zone.detail.hint',
      'message.installwizard.copy.whatiscloudstack'
    ]
    keys.forEach(key => {
      expect(en[key]).toContain('{product}')
      expect(ko[key]).toContain('{product}')
    })
    expect(Object.values(en).filter(value => typeof value === 'string' && value.includes('{product}')).length).toBe(31)
    expect(Object.values(ko).filter(value => typeof value === 'string' && value.includes('{product}')).length).toBe(19)
  })

  it('the strings of the removed version notice and links are gone, and nothing in the client asks for them', () => {
    const removed = [
      'label.new.version.available',
      'message.new.version.available',
      'label.report.bug',
      'message.installwizard.cloudstack.helptext.mailinglists',
      'message.installwizard.cloudstack.helptext.survey',
      'message.installwizard.cloudstack.helptext.issues'
    ]
    for (const messages of [en, ko]) {
      expect(removed.filter(key => key in messages)).toEqual([])
    }

    const sources = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
      const full = path.join(dir, entry.name)
      return entry.isDirectory() ? sources(full) : [full]
    })
    const asking = sources(path.join(__dirname, '../../../src'))
      .filter(file => removed.some(key => fs.readFileSync(file, 'utf8').includes(key)))
    expect(asking).toEqual([])
  })

  it('binds the name when a language is loaded, so $t() needs no parameter', async () => {
    const answers = { en, ko_KR: ko }
    global.fetch = jest.fn((url) => Promise.resolve({ json: () => Promise.resolve(answers[url.split('/').pop().split('.')[0]]) }))
    try {
      await loadLanguageAsync('ko_KR')
      expect(i18n.global.t('label.about.app')).toBe(ko['label.about.app'].replace('{product}', brandFile.name))
      expect(i18n.global.t('label.about.app')).toContain(brandFile.name)
      i18n.global.locale = 'en'
      expect(i18n.global.t('label.about.app')).toBe('About ' + brandFile.name)
      expect(i18n.global.t('message.shutdown.triggered')).toBe('A shutdown has been triggered. ' + brandFile.name + ' will not accept new jobs')
    } finally {
      delete global.fetch
    }
  })
})
