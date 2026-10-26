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

// Post-build correction of the generated runtime theme (color.less).
//
// antd-theme-generator maps colours back to theme variables by compiling with random colours, so a palette shade of one
// variable can collide with an unrelated colour and the rule ends up themed by the wrong variable. A correct mapping
// reproduces the static stylesheet's value for the same selector and property when it is evaluated with the theme that
// stylesheet was built with: the antd default theme for the ant-design stylesheet, the header defaults of color.less for
// the application stylesheet. A themed declaration that reproduces neither is replaced by the static literal.

'use strict'

const fs = require('fs')
const path = require('path')
const less = require('less')
const postcss = require('postcss')

// The generator appends the antd helper block (mixins carrying the colour functions, then the non-theme variables)
// after the rules; the first mixin definition or helper declaration starts it.
const FOOTER_START = /^(\.[\w-]+\(\)\s*\{|@functions:)/m
const THEMED_VALUE = /@[\w{-]|colorPalette|\bfade\(/
const PROBE = 'theme-colors-probe-'

// Collisions are rare; more disagreement than this, less coverage than MIN_COVERAGE or more unevaluated declarations than
// MAX_UNEVALUATED_SHARE means the evaluation or the selector matching is off, not the mapping.
const MAX_DISAGREEING_SHARE = 0.25
const MIN_COVERAGE = 0.5
const MAX_UNEVALUATED_SHARE = 0.05

// The minifier unquotes attribute values, drops the spacing around combinators and the universal selector before a
// qualifier, and writes legacy pseudo-elements with one colon.
const normalizeSelector = (selector) => selector
  .trim()
  .replace(/::(before|after|first-line|first-letter)/g, ':$1')
  .replace(/\*(?=[:.#[])/g, '')
  .replace(/\s+/g, ' ')
  .replace(/\s*([>+~])\s*/g, '$1')
  .replace(/\[([^\]=~|^$*]+)([~|^$*]?=)(["'])([^"']*)\3\]/g, '[$1$2$4]')

const splitSelectors = (selector) => {
  const parts = []
  let depth = 0
  let current = ''
  for (const char of selector) {
    if (char === '(' || char === '[') depth++
    if (char === ')' || char === ']') depth--
    if (char === ',' && depth === 0) {
      parts.push(current)
      current = ''
    } else {
      current += char
    }
  }
  parts.push(current)
  return parts.map(normalizeSelector).filter(Boolean)
}

const hslToRgb = (hue, saturation, lightness) => {
  const channel = (p, q, t) => {
    if (t < 0) t += 1
    if (t > 1) t -= 1
    if (t < 1 / 6) return p + (q - p) * 6 * t
    if (t < 1 / 2) return q
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
    return p
  }
  const h = (((hue % 360) + 360) % 360) / 360
  if (saturation === 0) return [lightness, lightness, lightness].map(v => Math.round(v * 255))
  const q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation
  const p = 2 * lightness - q
  return [channel(p, q, h + 1 / 3), channel(p, q, h), channel(p, q, h - 1 / 3)].map(v => Math.round(v * 255))
}

const rgba = (r, g, b, a) => `rgba(${r},${g},${b},${Math.round(a * 100) / 100})`

const canonicalColors = (value) => value
  .replace(/\btransparent\b/g, 'rgba(0,0,0,0)')
  .replace(/#([0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{4}|[0-9a-f]{3})\b/g, (match, hex) => {
    const full = hex.length <= 4 ? hex.split('').map(c => c + c).join('') : hex
    const [r, g, b, a] = [0, 2, 4, 6].map(i => (full.length > i ? parseInt(full.slice(i, i + 2), 16) : 255))
    return rgba(r, g, b, a / 255)
  })
  .replace(/rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)/g, (match, r, g, b, a) =>
    rgba(Math.round(r), Math.round(g), Math.round(b), a === undefined ? 1 : Number(a)))
  .replace(/hsla?\(\s*([\d.]+)(?:deg)?\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*(?:,\s*([\d.]+)\s*)?\)/g, (match, h, s, l, a) =>
    rgba(...hslToRgb(Number(h), Number(s) / 100, Number(l) / 100), a === undefined ? 1 : Number(a)))

// Colour notation, spacing and, for the border shorthands, the order of the parts do not change what a browser reads.
const normalizeValue = (property, value) => {
  let normalized = canonicalColors(value.toLowerCase().replace(/\s+/g, ' ').trim())
    .replace(/\s*,\s*/g, ',')
    .replace(/(^|[^\d])0\.(\d)/g, '$1.$2')
    .replace(/\s*!important/, '')
  if (/^(border|outline)(-(top|right|bottom|left))?$/.test(property.toLowerCase())) {
    normalized = normalized.split(' ').sort().join(' ')
  }
  return normalized
}

const staticKey = (selector, property) => `${selector}\n${property.toLowerCase()}`

// Only top-level rules count: the generator drops every at-rule, so a declaration inside one has no counterpart.
const collectStatic = (cssTexts) => {
  const table = new Map()
  cssTexts.forEach((css, index) => {
    let root
    try {
      root = postcss.parse(css)
    } catch (error) {
      throw new Error(`static css ${index + 1} cannot be parsed: ${error.message}`)
    }
    root.each(rule => {
      if (rule.type !== 'rule') return
      const selectors = splitSelectors(rule.selector)
      rule.each(decl => {
        if (decl.type !== 'decl') return
        for (const selector of selectors) {
          const key = staticKey(selector, decl.prop)
          if (!table.has(key)) table.set(key, [])
          table.get(key).push(decl.value)
        }
      })
    })
  })
  return table
}

const splitLess = (lessText) => {
  const start = lessText.search(FOOTER_START)
  return start < 0 ? { body: lessText, footer: '' } : { body: lessText.slice(0, start), footer: lessText.slice(start) }
}

const parseBody = (body) => {
  try {
    return postcss.parse(body)
  } catch (error) {
    throw new Error(`color.less cannot be parsed: ${error.message}`)
  }
}

const themeVariableNames = (root) => root.nodes
  .filter(node => node.type === 'atrule' && node.name.endsWith(':'))
  .map(node => node.name.slice(0, -1))

const antDefaultDefinitions = (antDefaultsLess, names) => {
  const lines = []
  for (const name of names) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const match = new RegExp(`^@${escaped}\\s*:\\s*([^;]*);`, 'm').exec(antDefaultsLess)
    if (match) lines.push(`@${name}: ${match[1]};`)
  }
  if (!lines.length) throw new Error('the antd default theme defines none of the theme variables of color.less')
  return lines.join('\n')
}

// One render evaluates all declarations, each in a probe rule of its own that is read back by index.
const evaluate = async (header, footer, definitions, declarations) => {
  const probes = declarations.map((decl, index) => `.${PROBE}${index} {${decl.prop}: ${decl.value};}`)
  let css
  try {
    css = (await less.render([header, footer, definitions, ...probes].join('\n'), { javascriptEnabled: true })).css
  } catch (error) {
    throw new Error(`the themed declarations of color.less cannot be evaluated: ${error.message}`)
  }
  const values = new Array(declarations.length)
  postcss.parse(css).walkRules(rule => {
    if (rule.selector.startsWith('.' + PROBE)) {
      values[Number(rule.selector.slice(PROBE.length + 1))] = rule.first && rule.first.value
    }
  })
  return values
}

const isOverridden = (decl) => {
  for (let next = decl.next(); next; next = next.next()) {
    if (next.type === 'decl' && next.prop === decl.prop) return true
  }
  return false
}

const correctThemeColors = async ({ lessText, cssTexts, antDefaultsLess }) => {
  if (!cssTexts.length) throw new Error('no static css to compare color.less with')
  const { body, footer } = splitLess(lessText)
  const root = parseBody(body)
  const firstRule = root.nodes.find(node => node.type === 'rule')
  const themed = []
  root.walkDecls(decl => {
    if (decl.parent.type === 'rule' && decl.parent.parent === root && THEMED_VALUE.test(decl.value)) themed.push(decl)
  })
  if (!firstRule || !themed.length) throw new Error('color.less has no themed declaration')

  const header = body.split('\n').slice(0, firstRule.source.start.line - 1).join('\n')
  const definitions = antDefaultDefinitions(antDefaultsLess, themeVariableNames(root))
  const appValues = await evaluate(header, footer, '', themed)
  const antValues = await evaluate(header, footer, definitions, themed)
  const table = collectStatic(cssTexts)

  const stats = { themed: themed.length, matched: 0, withoutStatic: 0, overridden: 0, unevaluated: 0 }
  const changes = []
  themed.forEach((decl, index) => {
    const evaluated = [appValues[index], antValues[index]]
    if (evaluated.some(value => value === undefined || value.includes('@'))) {
      stats.unevaluated++
      return
    }
    if (isOverridden(decl)) {
      stats.overridden++
      return
    }
    const known = evaluated.map(value => normalizeValue(decl.prop, value))
    const candidates = splitSelectors(decl.parent.selector)
      .map(selector => table.get(staticKey(selector, decl.prop)))
      .filter(Boolean)
    if (!candidates.length) {
      stats.withoutStatic++
    } else if (candidates.some(values => values.some(value => known.includes(normalizeValue(decl.prop, value))))) {
      stats.matched++
    } else {
      const literal = candidates[0][candidates[0].length - 1]
      changes.push({
        selector: decl.parent.selector.replace(/\s+/g, ' '),
        property: decl.prop,
        from: decl.value,
        to: literal,
        staticValues: new Set(candidates.flat().map(value => normalizeValue(decl.prop, value))).size
      })
      decl.value = literal
    }
  })

  if (stats.unevaluated > stats.themed * MAX_UNEVALUATED_SHARE) {
    throw new Error(`${stats.unevaluated} of ${stats.themed} themed declarations could not be evaluated`)
  }
  const compared = stats.matched + changes.length
  if (!compared) throw new Error('no themed declaration of color.less has a static counterpart')
  const evaluable = stats.themed - stats.overridden - stats.unevaluated
  if (compared < evaluable * MIN_COVERAGE) {
    throw new Error(`the static css has a counterpart for only ${compared} of ${evaluable} themed declarations`)
  }
  if (changes.length / compared > MAX_DISAGREEING_SHARE) {
    throw new Error(`${changes.length} of ${compared} compared themed declarations disagree with the static css`)
  }
  return { lessText: root.toString() + footer, changes, stats: { ...stats, compared } }
}

const find = (directory, pattern) => fs.readdirSync(directory).filter(name => pattern.test(name)).sort()

const run = async (distDirectory, antDefaultsFile, log) => {
  const lessFile = path.join(distDirectory, 'color.less')
  if (!fs.existsSync(lessFile)) throw new Error(`${lessFile} not found`)
  if (!fs.existsSync(antDefaultsFile)) throw new Error(`${antDefaultsFile} not found`)
  const cssDirectory = path.join(distDirectory, 'css')
  const vendors = fs.existsSync(cssDirectory) ? find(cssDirectory, /^vendors\.[^.]+\.css$/) : []
  if (!vendors.length) throw new Error(`no css/vendors.*.css in ${distDirectory}`)
  const application = find(cssDirectory, /^app\.[^.]+\.css$/)
  if (!application.length) throw new Error(`no css/app.*.css in ${distDirectory}`)
  const stylesheets = vendors.concat(application)

  const result = await correctThemeColors({
    lessText: fs.readFileSync(lessFile, 'utf8'),
    cssTexts: stylesheets.map(name => fs.readFileSync(path.join(cssDirectory, name), 'utf8')),
    antDefaultsLess: fs.readFileSync(antDefaultsFile, 'utf8')
  })
  for (const change of result.changes) {
    const note = change.staticValues > 1 ? ` (last of ${change.staticValues} static values)` : ''
    log(`${change.selector} ${change.property}: ${change.from} -> ${change.to}${note}`)
  }
  if (result.changes.length) fs.writeFileSync(lessFile, result.lessText)
  const { stats } = result
  log(`theme-colors: ${stats.themed} themed declarations, ${stats.matched} match the static css, ` +
    `${result.changes.length} corrected, ${stats.withoutStatic} without static counterpart, ` +
    `${stats.overridden} overridden, ${stats.unevaluated} not evaluable (${stylesheets.join(', ')})`)
}

module.exports = { correctThemeColors, run }

if (require.main === module) {
  const antDefaults = path.join(__dirname, '..', 'node_modules', 'ant-design-vue', 'lib', 'style', 'themes', 'default.less')
  run(path.resolve(process.argv[2] || path.join(__dirname, '..', 'dist')), antDefaults, console.log).catch(error => {
    console.error(`theme-colors: ${error.message}`)
    process.exit(1)
  })
}
