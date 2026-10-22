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

import { applyCustomGuiTheme } from '@/utils/guiTheme'

jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: () => null, set: () => {} } } }))
jest.mock('@/api', () => ({ getAPI: jest.fn(() => Promise.resolve({ listguithemesresponse: {} })) }))
jest.mock('@/locales', () => ({ loadLanguageAsync: jest.fn() }))

describe('utils/guiTheme', () => {
  beforeEach(() => {
    if (!document.getElementById('favicon')) {
      const favicon = document.createElement('link')
      favicon.id = 'favicon'
      document.head.appendChild(favicon)
    }
    window.less = { modifyVars: jest.fn() }
    global.fetch = jest.fn(() => Promise.resolve({
      json: () => Promise.resolve({ theme: { '@layout-mode': 'light' }, error: {}, userCard: {}, plugins: [] })
    }))
  })

  afterEach(() => {
    jest.restoreAllMocks()
    delete global.fetch
  })

  it('reads config.json past the browser cache each time the theme is applied', async () => {
    jest.spyOn(Date, 'now').mockReturnValueOnce(1000).mockReturnValueOnce(2000)

    await applyCustomGuiTheme(null, null)
    await applyCustomGuiTheme(null, null)

    const urls = global.fetch.mock.calls.map(([url]) => url)
    expect(urls).toHaveLength(2)
    urls.forEach(url => expect(new URL(url, 'http://ui.example/client/').pathname).toBe('/client/config.json'))
    expect(urls[0]).not.toBe(urls[1])
  })
})
