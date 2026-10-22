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

import common from '../../../common'
import brandFile from '@brand/brand.json'
import GlobalFooter from '@/components/page/GlobalFooter'

jest.setTimeout(30000)

// what the management server of this fork reports: the version of its own build, not the upstream release
const SERVER_VERSION = '4.23.0.100'

const mountFooter = ({ roletype = 'User', locale = 'en', latest, footer = 'Licensed under the Apache License' } = {}) => {
  const store = common.createMockStore({
    user: {
      info: { roletype },
      features: { cloudstackversion: SERVER_VERSION },
      latestVersion: latest
    }
  })
  return common.createFactory(GlobalFooter, {
    i18n: common.createMockI18n(locale, {}),
    store,
    mocks: { $config: { footer } }
  })
}

describe('components > page > GlobalFooter.vue', () => {
  it('footer shows the Based on Apache CloudStack credit', () => {
    expect(brandFile.credit.en).toBe('Based on Apache CloudStack 4.23.0.0')

    for (const roletype of ['User', 'Admin', 'DomainAdmin']) {
      const english = mountFooter({ roletype })
      expect(english.text()).toContain('Based on Apache CloudStack 4.23.0.0')
      expect(english.text()).toContain('Licensed under the Apache License')

      const korean = mountFooter({ roletype, locale: 'ko_KR' })
      expect(korean.text()).toContain(brandFile.credit.ko)
      expect(korean.text()).not.toContain(brandFile.credit.en)

      expect(mountFooter({ roletype, locale: 'de_DE' }).text()).toContain(brandFile.credit.en)
    }
  })

  it('names the product with the version of this build for administrators only', () => {
    const lines = (wrapper) => wrapper.findAll('.line').map(line => line.text())
    expect(lines(mountFooter({ roletype: 'Admin' }))).toEqual([
      'Licensed under the Apache License',
      brandFile.credit.en,
      brandFile.name + ' ' + SERVER_VERSION
    ])
    expect(lines(mountFooter({ roletype: 'User' }))).toEqual(['Licensed under the Apache License', brandFile.credit.en])
    // the upstream release is named once, by the credit, and never attached to the product name
    expect(mountFooter({ roletype: 'Admin' }).text()).not.toContain(brandFile.name + ' 4.23.0.0')
  })

  it('sends nobody to the upstream tracker and shows no upstream version notice', () => {
    const wrapper = mountFooter({ roletype: 'Admin', latest: { version: '4.99.0.0', fetchedTs: Date.now() } })
    const links = wrapper.findAll('a').map(link => link.attributes('href') || '')
    expect(links.filter(href => /github\.com\/apache\/cloudstack/.test(href))).toEqual([])
    expect(wrapper.text()).not.toContain('4.99.0.0')
    expect(wrapper.text()).not.toMatch(/new version/i)
  })
})
