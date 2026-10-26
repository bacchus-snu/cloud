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

import { RESOURCE_NAME_MAX, isValidResourceName, resourceNameRule } from '@/utils/resourceName'

describe('the name of a volume or a snapshot', () => {
  it('is 1 to 64 letters, digits, dots, underscores and hyphens', () => {
    expect(RESOURCE_NAME_MAX).toBe(64)
    for (const name of ['a', 'data', 'ROOT-7', 'data-snapshot-0', 'my_volume.v2', '-', '.', '_', '0', 'a'.repeat(64)]) {
      expect(isValidResourceName(name)).toBe(true)
    }
  })

  it('has no other character', () => {
    for (const name of ['my volume', 'data/disk', 'data:1', 'a,b', 'a+b', 'name!', '데이터', 'tab\tname', 'line\nbreak', ' data', 'data ']) {
      expect(isValidResourceName(name)).toBe(false)
    }
  })

  it('is not empty and not longer than 64 characters', () => {
    expect(isValidResourceName('')).toBe(false)
    expect(isValidResourceName('a'.repeat(65))).toBe(false)
  })

  it('is a text', () => {
    for (const name of [undefined, null, 7, {}, ['a']]) {
      expect(isValidResourceName(name)).toBe(false)
    }
  })
})

describe('the form rule of the name', () => {
  const rule = resourceNameRule(() => 'the rule')

  it('passes a valid name', async () => {
    await expect(rule.validator({}, 'data-snapshot-0')).resolves.toBeUndefined()
  })

  it('rejects an invalid or empty name with the message', async () => {
    await expect(rule.validator({}, 'my volume')).rejects.toBe('the rule')
    await expect(rule.validator({}, '')).rejects.toBe('the rule')
    await expect(rule.validator({}, undefined)).rejects.toBe('the rule')
  })

  describe('of a field that may be left empty', () => {
    const optional = resourceNameRule(() => 'the rule', { optional: true })

    it('passes an empty name and a valid one', async () => {
      for (const name of ['', undefined, null, 'data-snapshot-0']) {
        await expect(optional.validator({}, name)).resolves.toBeUndefined()
      }
    })

    it('rejects a name that is typed and is not valid', async () => {
      for (const name of ['my volume', ' ', 'a'.repeat(65), '데이터']) {
        await expect(optional.validator({}, name)).rejects.toBe('the rule')
      }
    })
  })

  describe('of a field that holds the name the resource already has', () => {
    const unchanged = resourceNameRule(() => 'the rule', { optional: true, unchanged: 'my data' })

    it('passes that name as it is, although it breaks the rule', async () => {
      await expect(unchanged.validator({}, 'my data')).resolves.toBeUndefined()
    })

    it('passes a valid name and an empty one', async () => {
      for (const name of ['', undefined, 'data-2']) {
        await expect(unchanged.validator({}, name)).resolves.toBeUndefined()
      }
    })

    it('rejects any other name that breaks the rule, also one that only resembles the old one', async () => {
      for (const name of ['my data 2', 'my  data', 'My data', 'my data ']) {
        await expect(unchanged.validator({}, name)).rejects.toBe('the rule')
      }
    })

    it('keeps nothing when the resource has no name', async () => {
      const none = resourceNameRule(() => 'the rule', { optional: true, unchanged: undefined })
      await expect(none.validator({}, undefined)).resolves.toBeUndefined()
      await expect(none.validator({}, 'my data')).rejects.toBe('the rule')
    })
  })

  it('reads the message in the language of the moment the name is checked', async () => {
    let language = 'ko'
    const late = resourceNameRule(() => language)
    await expect(late.validator({}, 'a b')).rejects.toBe('ko')
    language = 'en'
    await expect(late.validator({}, 'a b')).rejects.toBe('en')
  })
})
