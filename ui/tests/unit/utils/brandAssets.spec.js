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

import crypto from 'crypto'
import fs from 'fs'
import path from 'path'

const uiRoot = path.resolve(__dirname, '../../..')
const brandDir = path.resolve(uiRoot, '../snucse/branding')

const removed = [
  'public/assets/logo.svg',
  'public/assets/mini-logo.svg',
  'public/assets/banner.svg',
  'public/assets/asf-logo.svg',
  'public/assets/feather.svg',
  'public/assets/bg-what-is-cloudstack.png',
  'public/cloud.ico',
  'public/css/apache-theme.css'
]

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

const textChunks = (png) => {
  const found = {}
  for (let at = 8; at < png.length;) {
    const length = png.readUInt32BE(at)
    if (png.toString('latin1', at + 4, at + 8) === 'tEXt') {
      const [keyword, value] = png.toString('latin1', at + 8, at + 8 + length).split('\0')
      found[keyword] = value
    }
    at += 12 + length
  }
  return found
}

const walk = (dir, found = []) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== 'node_modules') walk(full, found)
    } else if (/\.(js|vue|json|html|css|less|scss|sh|md)$/.test(entry.name) && !full.includes(path.join('public', 'locales'))) {
      found.push(full)
    }
  }
  return found
}

describe('assets > the CloudStack and ASF images', () => {
  it('no CloudStack logo or feather asset is referenced', () => {
    removed.forEach(file => expect(fs.existsSync(path.join(uiRoot, file))).toBe(false))

    const pattern = /asf-logo|feather\.svg|bg-what-is-cloudstack|cloud\.ico|apache-theme|assets\/(logo|mini-logo|banner)\.svg/
    const files = [
      ...walk(path.join(uiRoot, 'src')),
      ...walk(path.join(uiRoot, 'public')),
      ...walk(path.join(uiRoot, 'docs')),
      ...['vue.config.js', 'index.html', 'package.json', 'prebuild.sh', 'postbuild.sh'].map(file => path.join(uiRoot, file))
    ]
    const holders = files.filter(file => pattern.test(fs.readFileSync(file, 'utf8')))
    expect(holders.map(file => path.relative(uiRoot, file))).toEqual([])
  })

  it('the brand files stand in for them and do not carry the CloudStack name', () => {
    for (const file of ['logo.svg', 'mini-logo.svg', 'favicon.svg']) {
      const svg = fs.readFileSync(path.join(brandDir, file), 'utf8')
      expect(svg).toMatch(/^<svg[^>]+viewBox=/)
      expect(svg).not.toMatch(/cloudstack|apache|feather/i)
      expect(svg).not.toMatch(/<image|data:|xlink:href|<script/i)
    }
    // the rasters are rendered in a container that has no fonts, so the favicon holds no live text
    expect(fs.readFileSync(path.join(brandDir, 'favicon.svg'), 'utf8')).not.toMatch(/<text/)
    expect(fs.readdirSync(brandDir).sort()).toEqual(['brand.json', 'favicon.ico', 'favicon.png', 'favicon.svg', 'logo.svg', 'mini-logo.svg'])
  })

  it('no source file names the upstream project as a literal', () => {
    const holders = walk(path.join(uiRoot, 'src')).filter(file => /CloudStack/.test(fs.readFileSync(file, 'utf8')))
    expect(holders.map(file => path.relative(uiRoot, file))).toEqual([])
  })

  it('the favicon rasters are the renderings of the favicon SVG', () => {
    const png = fs.readFileSync(path.join(brandDir, 'favicon.png'))
    expect(png.subarray(0, 8)).toEqual(PNG_SIGNATURE)
    expect([png.readUInt32BE(16), png.readUInt32BE(20)]).toEqual([64, 64])
    const digest = crypto.createHash('sha256').update(fs.readFileSync(path.join(brandDir, 'favicon.svg'))).digest('hex')
    expect(textChunks(png)['Source-SHA256']).toBe(digest)

    const ico = fs.readFileSync(path.join(brandDir, 'favicon.ico'))
    expect([ico.readUInt16LE(0), ico.readUInt16LE(2), ico.readUInt16LE(4)]).toEqual([0, 1, 3])
    const sizes = [0, 1, 2].map(index => {
      const entry = 6 + 16 * index
      const offset = ico.readUInt32LE(entry + 12)
      expect(ico.subarray(offset, offset + 8)).toEqual(PNG_SIGNATURE)
      expect(ico.readUInt32LE(entry + 8)).toBeGreaterThan(8)
      expect([ico[entry], ico[entry + 1]]).toEqual([ico.readUInt32BE(offset + 16), ico.readUInt32BE(offset + 20)])
      const frame = ico.subarray(offset, offset + ico.readUInt32LE(entry + 8))
      expect(textChunks(frame)['Source-SHA256']).toBe(digest)
      return ico[entry]
    })
    expect(sizes).toEqual([16, 32, 48])
  })

  it('the config keeps no image or title of the brand, the brand files own them', () => {
    const config = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
    for (const key of ['appTitle', 'loginTitle', 'loginFavicon', 'logo', 'minilogo', 'banner', 'favicon']) {
      expect(config[key]).toBeUndefined()
    }
    expect(config.error['404']).toBe('assets/404.png')
  })
})
