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

const { correctThemeColors, run } = require('../../../scripts/theme-colors')

// The shape of the generated color.less: the default theme variables, the colour rules, then the antd helper block
// (a mixin that carries the colour functions, and the non-theme variables). The header deliberately differs from the
// antd stock theme (primary colour) the way a customised vars.less does.
const HEADER = '@primary-color: #0066cc;\n@text-color-secondary: rgba(0, 0, 0, 0.45);\n@navigation-text-color: rgba(0, 0, 0, 0.65);\n'
const FOOTER = '.colorMixin() {\n@functions: ~`(function() { return 1; })`;\n}\n@blue-6: #1890ff;\n'
const BODY = [
  'html {color: #000;}',
  '.t-ok {color: @primary-color;border: 1px solid @primary-color;}',
  '.t-wrong {background-color: @text-color-secondary;border-color: @primary-color;}',
  '.t-nostatic {color: @primary-color;}',
  '.t-fork {color: @navigation-text-color;}',
  '.t-overridden {border: 1px solid @primary-color;border: 0;}',
  '.t-a, .t-b {color: @primary-color !important;}'
].join('\n') + '\n'
const COLOR_LESS = HEADER + BODY + FOOTER

const ANT_DEFAULTS = '@primary-color: @blue-6;\n@text-color-secondary: fade(#000, 45%);\n@font-size-base: 14px;\n'

// Minified the way the build emits it: notation and spacing differ from the less output.
const ANT_CSS = [
  '.t-ok{color:#1890ff;border:1px solid #1890ff}',
  '.t-wrong{background-color:#fffbe6;border-color:#1890ff}',
  '.t-fork{color:#fff}',
  '.t-overridden{border:0}',
  '.t-a,.t-b{color:#1890ff!important}',
  '@media print{.t-nostatic{color:red}}'
].join('')
const APP_CSS = '.t-fork{color:rgba(0,0,0,.65)}'

const correct = (overrides = {}) => correctThemeColors({
  lessText: COLOR_LESS,
  cssTexts: [ANT_CSS, APP_CSS],
  antDefaultsLess: ANT_DEFAULTS,
  ...overrides
})

describe('config/theme-colors', () => {
  it('keeps a declaration whose mapping reproduces the static value', async () => {
    const result = await correct()
    expect(result.lessText).toContain('.t-ok {color: @primary-color;border: 1px solid @primary-color;}')
    expect(result.lessText).toContain('.t-a, .t-b {color: @primary-color !important;}')
  })

  it('replaces a wrongly mapped declaration with the static literal', async () => {
    const result = await correct()
    expect(result.lessText).toContain('.t-wrong {background-color: #fffbe6;border-color: @primary-color;}')
    expect(result.changes).toHaveLength(1)
    expect(result.changes[0]).toMatchObject({ selector: '.t-wrong', property: 'background-color', from: '@text-color-secondary', to: '#fffbe6' })
  })

  it('keeps a declaration without a static counterpart', async () => {
    const result = await correct()
    expect(result.lessText).toContain('.t-nostatic {color: @primary-color;}')
    expect(result.stats.withoutStatic).toBeGreaterThanOrEqual(1)
  })

  it('keeps a declaration of the application theme that matches the static application css', async () => {
    const result = await correct()
    expect(result.lessText).toContain('.t-fork {color: @navigation-text-color;}')
  })

  it('does not correct a declaration that a later declaration of the same rule overrides', async () => {
    const result = await correct()
    expect(result.lessText).toContain('.t-overridden {border: 1px solid @primary-color;border: 0;}')
  })

  it('leaves everything outside the corrected declarations byte for byte', async () => {
    const result = await correct()
    expect(result.lessText.replace('background-color: #fffbe6', 'background-color: @text-color-secondary')).toBe(COLOR_LESS)
  })

  it('is idempotent', async () => {
    const first = await correct()
    const second = await correct({ lessText: first.lessText })
    expect(second.changes).toEqual([])
    expect(second.lessText).toBe(first.lessText)
  })

  it('fails when the file has no themed declaration', async () => {
    await expect(correct({ lessText: HEADER + 'html {color: #000;}\n' + FOOTER })).rejects.toThrow(/no themed declaration/)
  })

  it('fails when the file cannot be parsed', async () => {
    await expect(correct({ lessText: HEADER + '.broken {color: @primary-color;\n' + FOOTER })).rejects.toThrow(/color\.less/)
  })

  it('fails when there is no static css', async () => {
    await expect(correct({ cssTexts: [] })).rejects.toThrow(/no static css/)
  })

  it('fails when no themed declaration has a static counterpart', async () => {
    await expect(correct({ cssTexts: ['.other{color:#fff}'] })).rejects.toThrow(/counterpart/)
  })

  it('fails when the static css covers too few of the themed declarations', async () => {
    await expect(correct({ cssTexts: ['.t-ok{color:#1890ff}'] })).rejects.toThrow(/counterpart for only 1 of 7/)
  })

  it('fails instead of rewriting a file that mostly disagrees with the static css', async () => {
    const skewed = '.t-ok{color:#111}.t-wrong{background-color:#111;border-color:#111}.t-a,.t-b{color:#111!important}'
    await expect(correct({ cssTexts: [skewed] })).rejects.toThrow(/disagree/)
  })

  it('fails when the antd default theme cannot be read', async () => {
    await expect(correct({ antDefaultsLess: '@unrelated: 1px;\n' })).rejects.toThrow(/antd default/)
  })

  it('fails when too many themed declarations cannot be evaluated', async () => {
    const lessText = HEADER + BODY + '.t-raw {color: ~"@primary-color";}\n' + FOOTER
    await expect(correct({ lessText })).rejects.toThrow(/1 of 9 themed declarations could not be evaluated/)
  })

  it('tolerates a few declarations that stay unevaluated', async () => {
    const many = Array.from({ length: 40 }, (_, i) => i)
    const lessText = HEADER + BODY + '.t-raw {color: ~"@primary-color";}\n' + many.map(i => `.g-${i} {color: @primary-color;}\n`).join('') + FOOTER
    const result = await correct({ lessText, cssTexts: [ANT_CSS + many.map(i => `.g-${i}{color:#1890ff}`).join(''), APP_CSS] })
    expect(result.stats.unevaluated).toBe(1)
    expect(result.changes).toHaveLength(1)
  })

  describe('on the syntax of the generator', () => {
    // The palette function is a stand-in for the antd one; the declaration syntax and the helper block are the generated ones.
    const GENERATED_HEADER = '@primary-color: #0066cc;\n@text-color-secondary: rgba(0, 0, 0, 0.45);\n@warning-color: #faad14;\n'
    const GENERATED_BODY = [
      '.p-info {background-color: color(~`colorPalette("@{primary-color}", 1)`);border: 1px solid color(~`colorPalette("@{primary-color}", 3)`);}',
      '.p-warning {background-color: color(~`colorPalette("@{text-color-secondary}", 3)`);border: 1px solid color(~`colorPalette("@{warning-color}", 3)`);}',
      '.p-focus {box-shadow: 0 0 0 2px fade(@primary-color, 20%);}',
      '.p-line {border-color: fade(@primary-color, 50%);}',
      '.p-glow {box-shadow: 0 0 0 2px fade(@primary-color, 10%);}',
      '.p-soft {border-color: fade(@primary-color, 30%);}',
      '.p-faint {border-color: fade(@primary-color, 70%);}'
    ].join('\n') + '\n'
    const GENERATED_FOOTER = [
      '.colorPaletteMixin() {',
      '@functions: ~`(function() {this.colorPalette = function(color, index) {',
      '  var c = String(color);',
      '  var rgb = c.charAt(0) === "#" ? [1, 3, 5].map(function(i) { return parseInt(c.substr(i, 2), 16); }) : c.slice(c.indexOf("(") + 1).split(",").slice(0, 3).map(Number);',
      '  var toWhite = (10 - index) / 10;',
      '  return "#" + rgb.map(function(v) { return ("0" + Math.round(v + (255 - v) * toWhite).toString(16)).slice(-2); }).join("");',
      '};})()`;',
      '}',
      '.colorPaletteMixin();',
      '@blue-6: #1890ff;',
      '@gold-6: #faad14;',
      ''
    ].join('\n')
    const GENERATED_ANT_DEFAULTS = '@primary-color: @blue-6;\n@text-color-secondary: fade(#000, 45%);\n@warning-color: @gold-6;\n'
    // What the stand-in function returns for the antd primary colour (shades 1 and 3) and the warning colour (shade 3).
    const GENERATED_STATIC = [
      '.p-info{background-color:#e8f4ff;border:1px solid #badeff}',
      '.p-warning{background-color:#fffbe6;border:1px solid #fee6b9}',
      '.p-focus{box-shadow:0 0 0 2px rgba(24,144,255,.2)}',
      '.p-line{border-color:rgba(24,144,255,.5)}',
      '.p-glow{box-shadow:0 0 0 2px rgba(24,144,255,.1)}',
      '.p-soft{border-color:rgba(24,144,255,.3)}',
      '.p-faint{border-color:rgba(24,144,255,.7)}'
    ].join('')

    const generated = (overrides = {}) => correctThemeColors({
      lessText: GENERATED_HEADER + GENERATED_BODY + GENERATED_FOOTER,
      cssTexts: [GENERATED_STATIC, APP_CSS],
      antDefaultsLess: GENERATED_ANT_DEFAULTS,
      ...overrides
    })

    it('replaces a palette shade of the wrong variable and keeps the correct palette and fade mappings', async () => {
      const result = await generated()
      expect(result.stats.unevaluated).toBe(0)
      expect(result.changes).toHaveLength(1)
      expect(result.changes[0]).toMatchObject({
        selector: '.p-warning',
        property: 'background-color',
        from: 'color(~`colorPalette("@{text-color-secondary}", 3)`)',
        to: '#fffbe6'
      })
      expect(result.lessText).toContain(GENERATED_BODY.replace('color(~`colorPalette("@{text-color-secondary}", 3)`)', '#fffbe6'))
    })

    it('replaces a fade of the wrong variable', async () => {
      const result = await generated({ cssTexts: [GENERATED_STATIC.replace('rgba(24,144,255,.5)', 'rgba(0,0,0,.45)'), APP_CSS] })
      expect(result.changes.map(change => [change.selector, change.to])).toEqual([
        ['.p-warning', '#fffbe6'],
        ['.p-line', 'rgba(0,0,0,.45)']
      ])
    })
  })

  describe('on a built output directory', () => {
    let dist

    beforeEach(() => {
      dist = fs.mkdtempSync(path.join(os.tmpdir(), 'theme-colors-'))
      fs.mkdirSync(path.join(dist, 'css'))
      fs.writeFileSync(path.join(dist, 'color.less'), COLOR_LESS)
      fs.writeFileSync(path.join(dist, 'css', 'vendors.0123abcd.css'), ANT_CSS)
      fs.writeFileSync(path.join(dist, 'css', 'app.4567ef01.css'), APP_CSS)
      fs.writeFileSync(path.join(dist, 'css', 'chunk-aa.11111111.css'), '.t-wrong{background-color:#abcdef}')
    })

    afterEach(() => {
      fs.rmSync(dist, { recursive: true, force: true })
    })

    const antDefaultsFile = () => {
      const file = path.join(dist, 'default.less')
      fs.writeFileSync(file, ANT_DEFAULTS)
      return file
    }

    it('rewrites color.less in place from the vendors and app stylesheets only', async () => {
      const log = jest.fn()
      await run(dist, antDefaultsFile(), log)
      const written = fs.readFileSync(path.join(dist, 'color.less'), 'utf8')
      expect(written).toContain('.t-wrong {background-color: #fffbe6;border-color: @primary-color;}')
      expect(log.mock.calls.map(call => call[0]).join('\n')).toMatch(/\.t-wrong.*background-color.*#fffbe6/)
    })

    it('fails when color.less is missing', async () => {
      fs.rmSync(path.join(dist, 'color.less'))
      await expect(run(dist, antDefaultsFile(), jest.fn())).rejects.toThrow(/color\.less/)
    })

    it('fails when the vendors stylesheet is missing', async () => {
      fs.rmSync(path.join(dist, 'css', 'vendors.0123abcd.css'))
      await expect(run(dist, antDefaultsFile(), jest.fn())).rejects.toThrow(/vendors/)
    })

    it('fails when the application stylesheet is missing, and leaves color.less as it is', async () => {
      fs.rmSync(path.join(dist, 'css', 'app.4567ef01.css'))
      await expect(run(dist, antDefaultsFile(), jest.fn())).rejects.toThrow(/css\/app/)
      expect(fs.readFileSync(path.join(dist, 'color.less'), 'utf8')).toBe(COLOR_LESS)
    })
  })
})
