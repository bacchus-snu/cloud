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

import store from '@/store'
import { vueProps } from '@/vue-app'

// Display choices read from the runtime config.json, so that a deployment changes them without a new build.
// What a caller may do is the server's: the UI first hides what the caller's API list lacks. The `hidden` block
// ({ "<role type>": { "<area>": ["<key>", ...] } }) only covers what an API list cannot express; it hides entries and
// never removes a route or an API.

function runtimeConfig () {
  return vueProps.$config || {}
}

export function hiddenKeys (area, roletype = store.getters.userInfo?.roletype, config = runtimeConfig()) {
  const keys = config?.hidden?.[roletype]?.[area]
  return Array.isArray(keys) ? keys : []
}

export function isHidden (area, key, roletype, config) {
  return hiddenKeys(area, roletype, config).includes(key)
}

// a list column is named "<route name>.<field>"; a column given as an object names its field by `field` or by its only key
export function visibleColumns (routeName, columnKeys, roletype, config) {
  const hidden = hiddenKeys('columns', roletype, config)
  if (hidden.length === 0 || !Array.isArray(columnKeys)) {
    return columnKeys
  }
  const field = column => typeof column === 'object' ? ('field' in column ? column.field : Object.keys(column)[0]) : column
  return columnKeys.filter(column => !hidden.includes(routeName + '.' + field(column)))
}

export function showGpuSlots (config = runtimeConfig()) {
  return config?.showGpuSlots !== false
}

export function showCpuSpeed (config = runtimeConfig()) {
  return config?.showCpuSpeed === true
}

export function offeredLanguages (languages, roletype, config) {
  return languages.filter(language => !isHidden('languages', language.key, roletype, config))
}

// a help path that the mappings turned into a full address is opened as it is
export function docHelpUrl (docHelp, config = runtimeConfig()) {
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(docHelp || '')) {
    return docHelp
  }
  return config?.docBase + '/' + docHelp
}
