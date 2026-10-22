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

import { createI18n } from 'vue-i18n'
import { vueProps } from '@/vue-app'
import { brand } from '@/utils/brand'

const FALLBACK_LANG = 'en'
const loadedLanguage = []
const messages = {}

export const i18n = createI18n({
  locale: FALLBACK_LANG,
  fallbackLocale: FALLBACK_LANG,
  silentTranslationWarn: true,
  messages: messages,
  silentFallbackWarn: true,
  warnHtmlInMessage: 'off'
})

// Messages name the product with {product}. The name is bound here, once, as a literal
// interpolation so that no character of it is read as message syntax.
function bindProduct (message) {
  const name = '{\'' + brand.name + '\'}'
  const bound = {}
  for (const [key, value] of Object.entries(message)) {
    bound[key] = typeof value === 'string' ? value.split('{product}').join(name) : value
  }
  return bound
}

function applyMessages (lang, message) {
  if (message && Object.keys(message).length > 0) {
    const bound = bindProduct(message)
    i18n.global.setLocaleMessage(lang, bound)
    messages[lang] = bound
  }
  if (!loadedLanguage.includes(lang)) {
    loadedLanguage.push(lang)
  }
}

function fetchLocale (lang) {
  return fetch(`locales/${lang}.json?ts=${Date.now()}`)
    .then(response => response.json())
    .then(json => applyMessages(lang, json))
}

export function loadLanguageAsync (lang) {
  if (!lang) {
    const locale = vueProps.$localStorage.get('LOCALE')
    lang = (!locale || typeof locale === 'object') ? FALLBACK_LANG : locale
  }

  // Always keep the fallback locale's messages loaded so $t() degrades
  // to readable English instead of raw keys when a translation is missing.
  const ensureFallback = loadedLanguage.includes(FALLBACK_LANG)
    ? Promise.resolve()
    : fetchLocale(FALLBACK_LANG)

  const ensureTarget = (lang === FALLBACK_LANG || loadedLanguage.includes(lang))
    ? ensureFallback
    : ensureFallback.then(() => fetchLocale(lang))

  // Activate locale after messages are in place so the first render
  // already has the translations and avoids a flash of raw keys.
  return ensureTarget.then(() => {
    i18n.global.locale = lang
  })
}
