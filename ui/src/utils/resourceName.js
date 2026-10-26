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

export const RESOURCE_NAME_MAX = 64

export function isValidResourceName (name) {
  return typeof name === 'string' && name.length <= RESOURCE_NAME_MAX && /^[A-Za-z0-9._-]+$/.test(name)
}

// The message is a function so that it is read in the language of the moment the name is checked. An optional name passes
// when it is empty; `unchanged` is the name a resource already has, which passes as it is because only a change is checked.
export function resourceNameRule (message, { optional = false, unchanged } = {}) {
  const empty = (value) => value === undefined || value === null || value === ''
  const accepted = (value) => (optional && empty(value)) || (unchanged !== undefined && value === unchanged) || isValidResourceName(value)
  return {
    validator: async (rule, value) => accepted(value) ? Promise.resolve() : Promise.reject(message())
  }
}
