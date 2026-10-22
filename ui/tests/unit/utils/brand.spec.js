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
import os from 'os'
import path from 'path'
import template from 'lodash/template'

import brandFile from '@brand/brand.json'
import { brand, brandAssets, brandCredit, brandUpstreamVersion, applyBrand } from '@/utils/brand'
import { validateBrand } from '../../../brand.schema'
import { loadBrand, chainBrand, brandDir } from '../../../brand.build'

const uiRoot = path.resolve(__dirname, '../../..')

const walk = (dir, accept, found = []) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== 'node_modules') walk(full, accept, found)
    } else if (accept(full)) {
      found.push(full)
    }
  }
  return found
}

const imageNames = ['logo.svg', 'mini-logo.svg', 'favicon.svg', 'favicon.png', 'favicon.ico']

const writeBrandDir = (data, without = []) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'brand-'))
  fs.writeFileSync(path.join(dir, 'brand.json'), JSON.stringify(data))
  for (const name of imageNames.filter(image => !without.includes(image))) {
    fs.copyFileSync(path.join(brandDir, name), path.join(dir, name))
  }
  return dir
}

const fakeChain = ({ copy = true } = {}) => {
  const alias = {}
  const taps = {}
  const chain = {
    resolve: { alias: { set: (key, value) => { alias[key] = value } } },
    plugins: { has: (name) => name !== 'copy' || copy },
    plugin: (name) => ({ tap: (fn) => { taps[name] = fn } })
  }
  return { chain, alias, taps }
}

describe('brand strings come from snucse/branding/brand.json', () => {
  it('brand strings come from snucse/branding/brand.json', () => {
    expect(path.relative(path.resolve(uiRoot, '..'), brandDir)).toBe(path.join('snucse', 'branding'))
    expect(fs.existsSync(path.join(brandDir, 'brand.json'))).toBe(true)

    expect(brand.name).toBe(brandFile.name)
    expect(brand.title).toBe(brandFile.title)
    expect(brand.twoFaIssuer).toBe(brandFile.twoFaIssuer)
    expect(brand.credit).toEqual(brandFile.credit)
    expect(loadBrand().title).toBe(brandFile.title)

    expect(brandCredit('en')).toBe(brandFile.credit.en)
    expect(brandCredit('ko_KR')).toBe(brandFile.credit.ko)
    expect(brandCredit('ko')).toBe(brandFile.credit.ko)
    expect(brandCredit('de_DE')).toBe(brandFile.credit.en)
    expect(brandCredit(undefined)).toBe(brandFile.credit.en)

    const config = applyBrand({ appTitle: 'stale', logo: 'assets/logo.svg', footer: 'kept', theme: {} })
    expect(config.appTitle).toBe(brandFile.title)
    expect(config.footer).toBe('kept')
    expect(config.logo).toBe(brandAssets.logo)
    expect(config.minilogo).toBe(brandAssets.minilogo)
    expect(config.banner).toBe(brandAssets.banner)
    expect(config.favicon).toBe(brandAssets.favicon)
  })

  it('the upstream release is the one the credit names', () => {
    expect(brandUpstreamVersion()).toBe('4.23.0.0')
    expect(brandUpstreamVersion()).toBe(/\d+(?:\.\d+)+/.exec(brandFile.credit.en)[0])
  })

  it('the shipped credit is the origin credit of the licence', () => {
    expect(brandFile.credit.en).toBe('Based on Apache CloudStack 4.23.0.0')
    expect(brandFile.credit.ko.trim().length).toBeGreaterThan(0)
  })

  it('no source file holds the product name as a literal', () => {
    const accept = (file) => /\.(js|vue|json|html|css|less|scss)$/.test(file)
    const notLocale = (file) => accept(file) && !file.includes(path.join('public', 'locales'))
    const files = [
      ...walk(path.join(uiRoot, 'src'), accept),
      ...walk(path.join(uiRoot, 'public'), notLocale),
      path.join(uiRoot, 'vue.config.js'),
      path.join(uiRoot, 'brand.schema.js'),
      path.join(uiRoot, 'brand.build.js')
    ]
    expect(files.length).toBeGreaterThan(100)
    const holders = files.filter(file => fs.readFileSync(file, 'utf8').includes(brandFile.name))
    expect(holders.map(file => path.relative(uiRoot, file))).toEqual([])

    const localeHolders = fs.readdirSync(path.join(uiRoot, 'public', 'locales'))
      .filter(file => fs.readFileSync(path.join(uiRoot, 'public', 'locales', file), 'utf8').includes(brandFile.name))
    expect(localeHolders).toEqual([])
  })

  it('no source file names the product in any spelling either, whatever brand.json says', () => {
    const accept = (file) => /\.(js|vue|json|html|css|less|scss)$/.test(file)
    const files = [
      ...walk(path.join(uiRoot, 'src'), accept),
      ...walk(path.join(uiRoot, 'public'), accept),
      path.join(uiRoot, 'vue.config.js'),
      path.join(uiRoot, 'brand.schema.js'),
      path.join(uiRoot, 'brand.build.js')
    ]
    const holders = files.filter(file => /snucse[\s_-]*cloud/i.test(fs.readFileSync(file, 'utf8')))
    expect(holders.map(file => path.relative(uiRoot, file))).toEqual([])
  })

  it('an empty credit fails the build test', () => {
    expect(() => validateBrand(brandFile)).not.toThrow()

    const blanks = [
      { en: '', ko: brandFile.credit.ko },
      { en: brandFile.credit.en, ko: '' },
      { en: '   ', ko: brandFile.credit.ko },
      { en: brandFile.credit.en },
      {},
      '',
      null,
      undefined
    ]
    blanks.forEach(credit => {
      expect(() => validateBrand({ ...brandFile, credit })).toThrow(/credit/)
    })
    const { credit, ...withoutCredit } = brandFile
    expect(credit).toBeDefined()
    expect(() => validateBrand(withoutCredit)).toThrow(/credit/)

    const dir = writeBrandDir({ ...brandFile, credit: { en: '', ko: '' } })
    try {
      expect(() => loadBrand(dir)).toThrow(/credit/)
      expect(() => chainBrand(fakeChain().chain, dir)).toThrow(/credit/)
    } finally {
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('a build without the copy step for the images fails', () => {
    expect(() => chainBrand(fakeChain({ copy: false }).chain)).toThrow(/copy step/)
  })

  it('a build without the brand file or one of its images fails', () => {
    expect(() => loadBrand(path.join(os.tmpdir(), 'no-such-brand-dir'))).toThrow(/brand\.json not found/)

    for (const missing of imageNames) {
      const dir = writeBrandDir(brandFile, [missing])
      try {
        expect(() => loadBrand(dir)).toThrow(new RegExp(missing.replace('.', '\\.') + ' not found'))
      } finally {
        fs.rmSync(dir, { recursive: true, force: true })
      }
    }
    const whole = writeBrandDir(brandFile)
    try {
      expect(loadBrand(whole).name).toBe(brandFile.name)
    } finally {
      fs.rmSync(whole, { recursive: true, force: true })
    }
  })

  it('every other brand string is required too, and the name cannot break a message', () => {
    ;['name', 'title', 'twoFaIssuer'].forEach(key => {
      expect(() => validateBrand({ ...brandFile, [key]: '' })).toThrow(new RegExp(key))
      expect(() => validateBrand({ ...brandFile, [key]: undefined })).toThrow(new RegExp(key))
    })
    ;["Acme's Cloud", 'Acme {Cloud}', 'Acme\\Cloud'].forEach(name => {
      expect(() => validateBrand({ ...brandFile, name })).toThrow(/name/)
    })
  })

  it('the page title and the assets are put in place by the build from the same files', () => {
    const { chain, alias, taps } = fakeChain()
    chainBrand(chain)

    expect(alias['@brand']).toBe(brandDir)
    expect(taps.html([{ title: 'package-name', template: 'public/index.html' }])[0].title).toBe(brandFile.title)
    const patterns = taps.copy([[{ from: 'public', to: 'dist' }]])[0]
    expect(patterns[0]).toEqual({ from: 'public', to: 'dist' })
    const brandPattern = patterns.find(pattern => pattern.from === brandDir)
    expect(brandPattern.to).toBe(path.posix.dirname(brandAssets.logo))
    expect(brandPattern.ignore).toEqual(expect.arrayContaining(['brand.json']))

    const html = fs.readFileSync(path.join(uiRoot, 'public', 'index.html'), 'utf8')
    const page = template(html)({ BASE_URL: './', htmlWebpackPlugin: { options: { title: brandFile.title } } })
    expect(page).toContain('<title id="title">' + brandFile.title + '</title>')
    expect(page).toContain('href="./' + brandAssets.favicon + '"')
    expect(page).toContain('href="./' + brandAssets.faviconPng + '"')
    expect(page).toContain('href="./' + brandAssets.faviconIco + '"')
    expect(page).toContain(brandFile.title + ' needs JavaScript')

    // the title is escaped where it enters the page
    const risky = template(html)({ BASE_URL: './', htmlWebpackPlugin: { options: { title: 'Acme <Cloud> & "Co"' } } })
    expect(risky).toContain('<title id="title">Acme &lt;Cloud&gt; &amp; &quot;Co&quot;</title>')
    expect(risky).toContain('Acme &lt;Cloud&gt; &amp; &quot;Co&quot; needs JavaScript')

    const alt = writeBrandDir({ ...brandFile, name: 'Acme Cloud', title: 'Acme Console' })
    try {
      chainBrand(chain, alt)
      expect(taps.html([{ title: 'package-name' }])[0].title).toBe('Acme Console')
      expect(alias['@brand']).toBe(alt)
      const altPage = template(html)({ BASE_URL: './', htmlWebpackPlugin: { options: { title: 'Acme Console' } } })
      expect(altPage).toContain('<title id="title">Acme Console</title>')
    } finally {
      fs.rmSync(alt, { recursive: true, force: true })
    }
  })
})
