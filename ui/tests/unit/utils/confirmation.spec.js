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

import { confirmationName, matchesConfirmation, matchesCount } from '@/utils/confirmation'

describe('the name an Instance, a volume or a snapshot is confirmed by', () => {
  it('is the visible name of an Instance, else its name, else its id', () => {
    expect(confirmationName({ id: 'i-1', name: 'i-2-34-VM', displayname: 'lab' })).toBe('lab')
    expect(confirmationName({ id: 'i-1', name: 'i-2-34-VM' })).toBe('i-2-34-VM')
    expect(confirmationName({ id: 'i-1', name: 'i-2-34-VM', displayname: '' })).toBe('i-2-34-VM')
    expect(confirmationName({ id: 'i-1' })).toBe('i-1')
  })

  it('is the name of a volume or a snapshot', () => {
    expect(confirmationName({ id: 'v-1', name: 'data' })).toBe('data')
    expect(confirmationName({ id: 's-1', name: 'data-snapshot-0' })).toBe('data-snapshot-0')
  })

  it('is empty for nothing', () => {
    expect(confirmationName(undefined)).toBe('')
    expect(confirmationName(null)).toBe('')
    expect(confirmationName({})).toBe('')
  })
})

describe('typing a name to confirm', () => {
  it('matches the name exactly', () => {
    expect(matchesConfirmation('lab', 'lab')).toBe(true)
    expect(matchesConfirmation('ROOT-7', 'ROOT-7')).toBe(true)
    expect(matchesConfirmation('data-snapshot-0', 'data-snapshot-0')).toBe(true)
  })

  it('trims the whitespace around what was typed or pasted and nothing else', () => {
    expect(matchesConfirmation('  lab ', 'lab')).toBe(true)
    expect(matchesConfirmation('lab\n', 'lab')).toBe(true)
    expect(matchesConfirmation('\tlab', 'lab')).toBe(true)
    expect(matchesConfirmation('l ab', 'lab')).toBe(false)
    expect(matchesConfirmation('la b', 'lab')).toBe(false)
  })

  it('is case-sensitive', () => {
    expect(matchesConfirmation('Lab', 'lab')).toBe(false)
    expect(matchesConfirmation('LAB', 'lab')).toBe(false)
    expect(matchesConfirmation('lab', 'Lab')).toBe(false)
  })

  it('does not match a part or an extension of the name', () => {
    expect(matchesConfirmation('la', 'lab')).toBe(false)
    expect(matchesConfirmation('lab2', 'lab')).toBe(false)
    expect(matchesConfirmation('', 'lab')).toBe(false)
  })

  it('never matches when there is no name to match', () => {
    expect(matchesConfirmation('', '')).toBe(false)
    expect(matchesConfirmation('  ', '')).toBe(false)
    expect(matchesConfirmation('', undefined)).toBe(false)
    expect(matchesConfirmation(undefined, 'lab')).toBe(false)
    expect(matchesConfirmation('lab', null)).toBe(false)
  })
})

describe('typing the number of the selected items to confirm', () => {
  it('matches the count written in digits, with the whitespace around it trimmed', () => {
    expect(matchesCount('3', 3)).toBe(true)
    expect(matchesCount(' 3 ', 3)).toBe(true)
    expect(matchesCount('12', 12)).toBe(true)
    expect(matchesCount('1', 1)).toBe(true)
  })

  it('matches no other number or text', () => {
    expect(matchesCount('2', 3)).toBe(false)
    expect(matchesCount('33', 3)).toBe(false)
    expect(matchesCount('03', 3)).toBe(false)
    expect(matchesCount('3.0', 3)).toBe(false)
    expect(matchesCount('three', 3)).toBe(false)
    expect(matchesCount('', 3)).toBe(false)
    expect(matchesCount(undefined, 3)).toBe(false)
  })

  it('never matches without a selection', () => {
    expect(matchesCount('0', 0)).toBe(false)
    expect(matchesCount('', 0)).toBe(false)
    expect(matchesCount('0', undefined)).toBe(false)
    expect(matchesCount('-1', -1)).toBe(false)
  })
})
