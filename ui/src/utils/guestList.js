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

// The guests of a VM-local Linux account are the names in the tags `ssh.account.<account>`. A value holds at most
// TAG_VALUE_LIMIT characters, so a longer list continues in `ssh.account.<account>.<n>`; the reader joins the chunks
// in numeric order with commas.

export const GUEST_TAG_PREFIX = 'ssh.account.'
export const TAG_VALUE_LIMIT = 8192
export const RESOURCE_TYPE = 'UserVm'

const ACCOUNT = '[a-z_][a-z0-9_-]{0,31}'
const LINUX_ACCOUNT = new RegExp('^' + ACCOUNT + '$')
const ACCOUNT_KEY = new RegExp('^ssh\\.account\\.(' + ACCOUNT + ')(?:\\.(\\d+))?$')

export function isLinuxAccount (name) {
  return typeof name === 'string' && LINUX_ACCOUNT.test(name)
}

// full-match expressions; one that is not a valid expression is ignored, and without any usable one nothing is checked
function compilePatterns (patterns) {
  if (!Array.isArray(patterns)) {
    return []
  }
  const compiled = []
  for (const pattern of patterns) {
    try {
      compiled.push(new RegExp('^(?:' + pattern + ')$'))
    } catch (e) {
      // a deployment's mistake in one pattern must not lock out the editor
    }
  }
  return compiled
}

// Splits on commas only. Whitespace around a name is ignored, whitespace inside one means two names with no comma
// between them, which is reported and never merged or split. Names are lower-cased, and empties and duplicates dropped.
export function parseGuestList (text, patterns) {
  const shapes = compilePatterns(patterns)
  const names = []
  const errors = []
  const seen = new Set()
  for (const field of String(text ?? '').split(',')) {
    const entry = field.trim()
    if (!entry) {
      continue
    }
    if (/\s/.test(entry)) {
      if (!errors.some(error => error.type === 'separator' && error.entry === entry)) {
        errors.push({ type: 'separator', entry })
      }
      continue
    }
    const name = entry.toLowerCase()
    if (seen.has(name)) {
      continue
    }
    seen.add(name)
    if (shapes.length > 0 && !shapes.some(shape => shape.test(name))) {
      errors.push({ type: 'shape', entry: name })
      continue
    }
    names.push(name)
  }
  return { names, errors }
}

export function formatGuestList (names) {
  return (names || []).join(', ')
}

export function chunkGuestNames (names, limit = TAG_VALUE_LIMIT) {
  const chunks = []
  let current = null
  for (const name of names || []) {
    if (current !== null && current.length + 1 + name.length <= limit) {
      current += ',' + name
    } else {
      if (current !== null) {
        chunks.push(current)
      }
      current = name
    }
  }
  if (current !== null) {
    chunks.push(current)
  }
  return chunks
}

export function guestTagKey (account, index) {
  return GUEST_TAG_PREFIX + account + (index > 0 ? '.' + index : '')
}

const byChunk = (a, b) => a.index - b.index || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)

// the tags of one account as the reader sees them: each with the number of its chunk, in reading order
function chunksOf (tags, account) {
  const chunks = []
  for (const tag of tags || []) {
    const match = ACCOUNT_KEY.exec(tag?.key || '')
    if (match && match[1] === account) {
      chunks.push({ key: tag.key, value: tag.value ?? '', index: match[2] === undefined ? 0 : parseInt(match[2], 10) })
    }
  }
  return chunks.sort(byChunk)
}

// the reader's way: join the chunks, split on commas, strip, lower-case, drop empties and duplicates; a name with
// whitespace inside stays one name, which the editor then reports
function namesOf (chunks) {
  const names = chunks.map(chunk => chunk.value).join(',').split(',').map(name => name.trim().toLowerCase()).filter(Boolean)
  return [...new Set(names)]
}

export function guestAccounts (tags) {
  const accounts = new Set()
  for (const tag of tags || []) {
    const match = ACCOUNT_KEY.exec(tag?.key || '')
    if (match) {
      accounts.add(match[1])
    }
  }
  return [...accounts].sort().map(account => ({ account, names: namesOf(chunksOf(tags, account)) }))
}

// What turns the tags of `account` into the list `names`: the keys to delete, which are the ones the new list no longer
// has or holds a different value in, and the tags to create, which are the new keys and the changed ones.
export function diffGuestTags (tags, account, names) {
  const wanted = chunkGuestNames(names).map((value, index) => ({ key: guestTagKey(account, index), value }))
  const held = chunksOf(tags, account)
  const remove = held
    .filter(chunk => !wanted.some(tag => tag.key === chunk.key && tag.value === chunk.value))
    .map(chunk => chunk.key)
  const add = wanted.filter(tag => !held.some(chunk => chunk.key === tag.key && chunk.value === tag.value))
  return { remove, add }
}

function tagParams (resourceId, entries, withValue) {
  if (!Array.isArray(entries) || entries.length === 0) {
    throw new Error('a tag command needs at least one tag: without one the server acts on every tag of the resource')
  }
  const params = { resourceids: resourceId, resourcetype: RESOURCE_TYPE }
  entries.forEach((entry, i) => {
    params[`tags[${i}].key`] = entry.key
    if (withValue) {
      params[`tags[${i}].value`] = entry.value
    }
  })
  return params
}

export function createTagsParams (resourceId, tags) {
  return tagParams(resourceId, tags, true)
}

// by key alone, so that no value has to match what the server holds
export function deleteTagsParams (resourceId, keys) {
  return tagParams(resourceId, Array.isArray(keys) ? keys.map(key => ({ key })) : keys, false)
}
