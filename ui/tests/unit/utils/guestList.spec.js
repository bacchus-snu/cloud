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

import {
  GUEST_TAG_PREFIX,
  TAG_VALUE_LIMIT,
  isLinuxAccount,
  parseGuestList,
  formatGuestList,
  chunkGuestNames,
  guestTagKey,
  guestAccounts,
  diffGuestTags,
  createTagsParams,
  deleteTagsParams
} from '@/utils/guestList'

const PATTERNS = ['[a-z][a-z0-9]{1,19}', '[a-z0-9._-]+@bacchus\\.snucse\\.org']

// names of 50 characters and a last one, whose joined text is exactly `total` characters long
const namesJoinedTo = (total) => {
  const names = []
  let length = -1
  while (total - length - 1 > 100) {
    names.push(String(names.length).padStart(50, 'n'))
    length += 51
  }
  names.push('z'.repeat(total - length - 1))
  return names
}

describe('a Linux account name for a guest list', () => {
  it('follows the username rule: a lower-case letter or underscore first, then up to 31 of letters, digits, underscore and hyphen', () => {
    for (const ok of ['ubuntu', 'debian', '_svc', 'a', 'dev-1', 'u_2', 'a'.repeat(32)]) {
      expect(isLinuxAccount(ok)).toBe(true)
    }
    for (const bad of ['', '1abc', '-abc', 'Ubuntu', 'a b', 'a.b', 'a:b', 'a'.repeat(33), undefined, null]) {
      expect(isLinuxAccount(bad)).toBe(false)
    }
  })
})

describe('reading a guest list', () => {
  it('splits on commas only and ignores the whitespace around each name', () => {
    expect(parseGuestList('alice,bob')).toEqual({ names: ['alice', 'bob'], errors: [] })
    expect(parseGuestList('  alice ,\tbob\n')).toEqual({ names: ['alice', 'bob'], errors: [] })
    expect(parseGuestList('alice,\n  bob ,\r\ncarol')).toEqual({ names: ['alice', 'bob', 'carol'], errors: [] })
  })

  it('reports two names separated only by whitespace and merges or splits neither', () => {
    expect(parseGuestList('alice bob')).toEqual({ names: [], errors: [{ type: 'separator', entry: 'alice bob' }] })
    expect(parseGuestList('alice\nbob')).toEqual({ names: [], errors: [{ type: 'separator', entry: 'alice\nbob' }] })
    expect(parseGuestList('alice\tbob')).toEqual({ names: [], errors: [{ type: 'separator', entry: 'alice\tbob' }] })
    // the other entries still read, and the faulty one is named whole, as it was typed (trimmed)
    expect(parseGuestList('carol, alice  bob ,dave')).toEqual({
      names: ['carol', 'dave'],
      errors: [{ type: 'separator', entry: 'alice  bob' }]
    })
  })

  it('drops empty entries and duplicates, which it compares without regard to case, and lower-cases every name', () => {
    expect(parseGuestList(',, alice,,\n,')).toEqual({ names: ['alice'], errors: [] })
    expect(parseGuestList('Alice,BOB,alice,Bob,ALICE')).toEqual({ names: ['alice', 'bob'], errors: [] })
    expect(parseGuestList('')).toEqual({ names: [], errors: [] })
    expect(parseGuestList(undefined)).toEqual({ names: [], errors: [] })
    expect(parseGuestList(' \n ,\t')).toEqual({ names: [], errors: [] })
  })

  it('keeps the order in which the names first appear', () => {
    expect(parseGuestList('zed, amy, Zed, bob').names).toEqual(['zed', 'amy', 'bob'])
  })

  describe('with the shapes the deployment allows', () => {
    it('accepts a name only when one pattern matches all of it', () => {
      const ok = parseGuestList('alice, whnbaek@bacchus.snucse.org, a.b-c_d@bacchus.snucse.org, u123', PATTERNS)
      expect(ok).toEqual({ names: ['alice', 'whnbaek@bacchus.snucse.org', 'a.b-c_d@bacchus.snucse.org', 'u123'], errors: [] })
    })

    it('names each name that matches none, once, in the lower case it would be saved in', () => {
      const long = 'q'.repeat(21)
      const result = parseGuestList(`alice, A, Bob.Smith, alice@gmail.com, whnbaek@bacchus.snucse.org.evil, a, x, ${long}`, PATTERNS)
      expect(result.names).toEqual(['alice'])
      expect(result.errors).toEqual([
        { type: 'shape', entry: 'a' },
        { type: 'shape', entry: 'bob.smith' },
        { type: 'shape', entry: 'alice@gmail.com' },
        { type: 'shape', entry: 'whnbaek@bacchus.snucse.org.evil' },
        { type: 'shape', entry: 'x' },
        { type: 'shape', entry: long }
      ])
    })

    it('checks the name after it is lower-cased, so a typed capital is not a shape error', () => {
      expect(parseGuestList('Alice', PATTERNS)).toEqual({ names: ['alice'], errors: [] })
    })

    it('reports a separator fault and a shape fault together, and shape-checks only real names', () => {
      const result = parseGuestList('alice bob, carol, d', PATTERNS)
      expect(result.names).toEqual(['carol'])
      expect(result.errors).toEqual([{ type: 'separator', entry: 'alice bob' }, { type: 'shape', entry: 'd' }])
    })

    it('skips the shape check when the patterns are absent, empty or not usable', () => {
      expect(parseGuestList('A, x@y', undefined).errors).toEqual([])
      expect(parseGuestList('A, x@y', null).errors).toEqual([])
      expect(parseGuestList('A, x@y', []).errors).toEqual([])
      expect(parseGuestList('A, x@y', 'not a list').errors).toEqual([])
      expect(parseGuestList('A, x@y', ['(']).errors).toEqual([])
    })

    it('ignores a pattern that is not a valid expression and still applies the others', () => {
      expect(parseGuestList('alice, 7', ['(', '[a-z]+']).errors).toEqual([{ type: 'shape', entry: '7' }])
    })
  })
})

describe('showing a guest list', () => {
  it('lists the names with a comma and a space', () => {
    expect(formatGuestList(['alice', 'bob'])).toBe('alice, bob')
    expect(formatGuestList([])).toBe('')
  })
})

describe('the chunks of a guest list', () => {
  it('is one chunk of the names joined by commas, without spaces, while they fit', () => {
    expect(chunkGuestNames(['alice', 'bob', 'carol'])).toEqual(['alice,bob,carol'])
    expect(chunkGuestNames(['alice'])).toEqual(['alice'])
  })

  it('is no chunk for no name', () => {
    expect(chunkGuestNames([])).toEqual([])
  })

  it('holds 8192 characters in one chunk', () => {
    expect(TAG_VALUE_LIMIT).toBe(8192)
    const names = namesJoinedTo(8192)
    expect(names.join(',')).toHaveLength(8192)
    expect(chunkGuestNames(names)).toEqual([names.join(',')])
  })

  it('splits 8193 characters at a comma into two chunks that each fit and join back to the same names', () => {
    const names = namesJoinedTo(8193)
    expect(names.join(',')).toHaveLength(8193)
    const chunks = chunkGuestNames(names)
    expect(chunks).toHaveLength(2)
    for (const chunk of chunks) {
      expect(chunk.length).toBeLessThanOrEqual(8192)
      expect(chunk.startsWith(',')).toBe(false)
      expect(chunk.endsWith(',')).toBe(false)
    }
    expect(chunks.join(',')).toBe(names.join(','))
  })

  it('starts the next chunk with the name that does not fit, never in the middle of a name', () => {
    // 10-character names: 744 of them are 8183 characters, one more would be 8194
    const names = Array.from({ length: 800 }, (_, i) => 'user' + String(i).padStart(6, '0'))
    const chunks = chunkGuestNames(names)
    expect(chunks[0].split(',')).toHaveLength(744)
    expect(chunks[0]).toHaveLength(744 * 10 + 743)
    expect(chunks.every(chunk => chunk.length <= 8192)).toBe(true)
    expect(chunks.join(',').split(',')).toEqual(names)
  })

  it('takes a smaller limit when it is given one', () => {
    expect(chunkGuestNames(['aaa', 'bbb', 'ccc'], 7)).toEqual(['aaa,bbb', 'ccc'])
    expect(chunkGuestNames(['aaa', 'bbb', 'ccc'], 6)).toEqual(['aaa', 'bbb', 'ccc'])
  })
})

describe('the tag keys of a guest list', () => {
  it('are ssh.account.<account> for the first chunk and ssh.account.<account>.<n> for chunk n', () => {
    expect(GUEST_TAG_PREFIX).toBe('ssh.account.')
    expect(guestTagKey('debian', 0)).toBe('ssh.account.debian')
    expect(guestTagKey('debian', 1)).toBe('ssh.account.debian.1')
    expect(guestTagKey('debian', 12)).toBe('ssh.account.debian.12')
  })
})

describe('the guest lists that the tags of an instance hold', () => {
  const tag = (key, value) => ({ key, value, resourceid: 'vm-1', resourcetype: 'UserVm' })

  it('is one list per account, from every chunk in numeric order', () => {
    const tags = [
      tag('ssh.account.foo.10', 'k'),
      tag('ssh.account.foo.2', 'c,d'),
      tag('ssh.account.foo', 'a,b'),
      tag('ssh.account.foo.1', 'bb,x')
    ]
    expect(guestAccounts(tags)).toEqual([{ account: 'foo', names: ['a', 'b', 'bb', 'x', 'c', 'd', 'k'] }])
  })

  it('strips and lower-cases the names and drops empties and duplicates, as the reader does', () => {
    expect(guestAccounts([tag('ssh.account.foo', ' Alice, ,bob ,ALICE,,'), tag('ssh.account.foo.1', 'Bob, carol')]))
      .toEqual([{ account: 'foo', names: ['alice', 'bob', 'carol'] }])
  })

  it('keeps a held name with whitespace inside as one name, so that the editor can report it rather than lose it', () => {
    expect(guestAccounts([tag('ssh.account.foo', 'alice bob, carol')])).toEqual([{ account: 'foo', names: ['alice bob', 'carol'] }])
  })

  it('lists the accounts by name, among them one with an empty list, and leaves other tags alone', () => {
    const tags = [
      tag('ssh.account.zed', 'a'),
      tag('owner', 'someone'),
      tag('ssh.account.bar', ''),
      tag('ssh.account', 'x'),
      tag('ssh.account.', 'x'),
      tag('ssh.accounts.foo', 'x'),
      tag('ssh.account.Upper', 'x'),
      tag('ssh.account.foo.bar', 'x'),
      tag('ssh.account.foo.1.2', 'x'),
      tag('my.ssh.account.foo', 'x')
    ]
    expect(guestAccounts(tags)).toEqual([{ account: 'bar', names: [] }, { account: 'zed', names: ['a'] }])
  })

  it('is empty for no tags', () => {
    expect(guestAccounts([])).toEqual([])
    expect(guestAccounts(undefined)).toEqual([])
  })
})

describe('the tag changes that save a guest list', () => {
  const tag = (key, value) => ({ key, value })
  const bigNames = namesJoinedTo(8192 * 2 + 100)
  const bigChunks = chunkGuestNames(bigNames)

  it('are none when the list is as it is', () => {
    expect(diffGuestTags([tag('ssh.account.foo', 'alice,bob')], 'foo', ['alice', 'bob'])).toEqual({ remove: [], add: [] })
    expect(diffGuestTags([], 'foo', [])).toEqual({ remove: [], add: [] })
    expect(diffGuestTags([tag('owner', 'me')], 'foo', [])).toEqual({ remove: [], add: [] })
    expect(bigChunks).toHaveLength(3)
    const held = bigChunks.map((value, i) => tag(guestTagKey('foo', i), value))
    expect(diffGuestTags(held, 'foo', bigNames)).toEqual({ remove: [], add: [] })
  })

  it('create the first key for a first list', () => {
    expect(diffGuestTags([], 'foo', ['alice', 'bob'])).toEqual({ remove: [], add: [{ key: 'ssh.account.foo', value: 'alice,bob' }] })
  })

  it('delete a key whose value changed and create it again', () => {
    expect(diffGuestTags([tag('ssh.account.foo', 'alice')], 'foo', ['alice', 'bob'])).toEqual({
      remove: ['ssh.account.foo'],
      add: [{ key: 'ssh.account.foo', value: 'alice,bob' }]
    })
  })

  it('touch only the chunks that changed', () => {
    const held = bigChunks.map((value, i) => tag(guestTagKey('foo', i), value))
    const changed = [...bigNames.slice(0, -1), 'zzz']
    const last = chunkGuestNames(changed)[2]
    expect(diffGuestTags(held, 'foo', changed)).toEqual({
      remove: ['ssh.account.foo.2'],
      add: [{ key: 'ssh.account.foo.2', value: last }]
    })
  })

  it('delete the keys that a shorter list no longer fills: from three chunks to one, .1 and .2', () => {
    const held = bigChunks.map((value, i) => tag(guestTagKey('foo', i), value))
    const diff = diffGuestTags(held, 'foo', ['alice'])
    expect(diff.remove).toEqual(['ssh.account.foo', 'ssh.account.foo.1', 'ssh.account.foo.2'])
    expect(diff.add).toEqual([{ key: 'ssh.account.foo', value: 'alice' }])

    const keepFirst = diffGuestTags(held, 'foo', bigChunks[0].split(','))
    expect(keepFirst).toEqual({ remove: ['ssh.account.foo.1', 'ssh.account.foo.2'], add: [] })
  })

  it('delete every key of an account whose list is emptied', () => {
    const held = [tag('ssh.account.foo', 'a'), tag('ssh.account.foo.1', 'b'), tag('ssh.account.foo.2', 'c')]
    expect(diffGuestTags(held, 'foo', [])).toEqual({ remove: ['ssh.account.foo', 'ssh.account.foo.1', 'ssh.account.foo.2'], add: [] })
  })

  it('leave the keys of other accounts and other tags alone, among them one whose name starts with this account\'s', () => {
    const held = [
      tag('ssh.account.foo', 'a'),
      tag('ssh.account.foobar', 'x'),
      tag('ssh.account.foo-bar', 'y'),
      tag('ssh.account.foo.bar', 'z'),
      tag('ssh.account.other.1', 'w'),
      tag('owner', 'me')
    ]
    expect(diffGuestTags(held, 'foo', ['b'])).toEqual({ remove: ['ssh.account.foo'], add: [{ key: 'ssh.account.foo', value: 'b' }] })
    expect(diffGuestTags(held, 'foo', [])).toEqual({ remove: ['ssh.account.foo'], add: [] })
  })

  it('clean up a stray chunk key that the reader counts but the writer does not make, such as .0', () => {
    const held = [tag('ssh.account.foo', 'a'), tag('ssh.account.foo.0', 'b'), tag('ssh.account.foo.01', 'c')]
    expect(diffGuestTags(held, 'foo', ['a', 'b', 'c'])).toEqual({
      remove: ['ssh.account.foo', 'ssh.account.foo.0', 'ssh.account.foo.01'],
      add: [{ key: 'ssh.account.foo', value: 'a,b,c' }]
    })
  })

  it('rewrite a held value that holds the same names in another form, since the tag is what changes', () => {
    expect(diffGuestTags([tag('ssh.account.foo', 'Bob, alice')], 'foo', ['bob', 'alice'])).toEqual({
      remove: ['ssh.account.foo'],
      add: [{ key: 'ssh.account.foo', value: 'bob,alice' }]
    })
  })
})

describe('the parameters of the tag commands', () => {
  it('create tags on an instance, a key and a value for each', () => {
    expect(createTagsParams('vm-1', [{ key: 'ssh.account.foo', value: 'a,b' }, { key: 'ssh.account.foo.1', value: 'c' }])).toEqual({
      resourceids: 'vm-1',
      resourcetype: 'UserVm',
      'tags[0].key': 'ssh.account.foo',
      'tags[0].value': 'a,b',
      'tags[1].key': 'ssh.account.foo.1',
      'tags[1].value': 'c'
    })
  })

  it('delete tags by key alone, so that no value has to match', () => {
    expect(deleteTagsParams('vm-1', ['ssh.account.foo', 'ssh.account.foo.1'])).toEqual({
      resourceids: 'vm-1',
      resourcetype: 'UserVm',
      'tags[0].key': 'ssh.account.foo',
      'tags[1].key': 'ssh.account.foo.1'
    })
  })

  it('never delete without a key, for a request with no tags deletes every tag of the instance', () => {
    expect(() => deleteTagsParams('vm-1', [])).toThrow()
    expect(() => deleteTagsParams('vm-1', undefined)).toThrow()
    expect(() => deleteTagsParams('vm-1', null)).toThrow()
  })

  it('never create without a tag', () => {
    expect(() => createTagsParams('vm-1', [])).toThrow()
    expect(() => createTagsParams('vm-1', undefined)).toThrow()
  })
})

describe('the account rule of the editor and of the tag reader', () => {
  it('is one rule: a name the editor accepts for a new section is a key the reader lists, and the reverse', () => {
    const names = ['a', '_x', 'dev-1', 'u_2', 'a'.repeat(32), 'a'.repeat(33), '1a', '-a', 'A', 'a.b', 'a b', '']
    for (const name of names) {
      const listed = guestAccounts([{ key: 'ssh.account.' + name, value: 'x' }]).map(entry => entry.account)
      expect(listed).toEqual(isLinuxAccount(name) ? [name] : [])
    }
  })
})
