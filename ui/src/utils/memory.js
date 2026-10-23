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

// Memory sizes are MiB in the API and GiB on the pages.

const MIB_PER_GIB = 1024

export function memoryMibOf (gib) {
  return Math.round(Number(gib || 0) * MIB_PER_GIB)
}

export function memoryGibOf (mib) {
  return Number(mib || 0) / MIB_PER_GIB
}

export function formatGib (mib) {
  return String(Number(memoryGibOf(mib).toFixed(2)))
}

/**
 * The GiB of a size in MiB to a tenth, the step in which memory is asked: rounded up for a lower bound, down for an
 * upper bound, to the nearest otherwise.
 */
export function tenthsOfGib (mib, rounding = Math.round) {
  return rounding(memoryGibOf(mib) * 10) / 10
}
