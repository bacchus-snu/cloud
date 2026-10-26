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

export function confirmationName (resource) {
  return resource?.displayname || resource?.name || resource?.id || ''
}

// Only the whitespace around what was typed is ignored; the comparison is otherwise exact and case-sensitive. A resource
// without a name matches nothing, so that the dialog is never confirmed without typing.
export function matchesConfirmation (typed, expected) {
  if (typeof typed !== 'string' || typeof expected !== 'string' || expected.trim() === '') {
    return false
  }
  return typed.trim() === expected.trim()
}

export function matchesCount (typed, count) {
  return typeof typed === 'string' && Number.isInteger(count) && count > 0 && typed.trim() === String(count)
}
