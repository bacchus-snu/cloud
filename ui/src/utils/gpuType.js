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

// the tag every CPU-only offering and every host carries
export const CPU_LIMIT_TAG = 'cpu'

export const hostTagsOf = (offering) => String((offering && offering.hosttags) || '').split(',').map(tag => tag.trim()).filter(tag => tag)

const namesOf = (types) => {
  if (types === undefined || types === null) {
    return null
  }
  if (Array.isArray(types) || types instanceof Set) {
    return new Set(types)
  }
  return new Set(Object.keys(types))
}

/**
 * The GPU type of an offering with GPUs: its host tag that has a GPU limit row; null for an offering without GPUs, with
 * no such tag, or when the tags that have a row (an array, a set or an object keyed by tag) are not given. The CPU-only
 * tag has a row too and never names a type.
 */
export function gpuTypeOf (offering, types) {
  const known = namesOf(types)
  if (!known || !offering || !(Number(offering.gpucount) > 0)) {
    return null
  }
  return hostTagsOf(offering).filter(tag => tag !== CPU_LIMIT_TAG).find(tag => known.has(tag)) || null
}
