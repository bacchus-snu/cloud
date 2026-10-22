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

import { getAPI } from '@/api'

// the resource types of the tagged limit rows the wizards read
export const LIMIT_CPU = 'cpu'
export const LIMIT_MEMORY = 'memory'
export const LIMIT_GPU = 'gpu'

// the tag every CPU-only offering and every host carries
export const CPU_LIMIT_TAG = 'cpu'

const TYPE_ORDINALS = { cpu: 8, memory: 9, gpu: 16 }
const UNLIMITED = -1

const isType = (row, name) => row.resourcetypename === name || Number(row.resourcetype) === TYPE_ORDINALS[name]

const rowTag = (row) => row.tag || ''

/**
 * Quota is the only bound of an input: the owner's limit less the usage the owner's account reports. Capacity figures
 * never enter. An unknown or unlimited figure is null, so nothing bounds the input.
 */
export function remainingLimit (quota, typeName, tag) {
  const limit = ((quota && quota.limits) || []).find(row => isType(row, typeName) && rowTag(row) === (tag || ''))
  if (!limit || limit.max === undefined || limit.max === null || Number(limit.max) === UNLIMITED) {
    return null
  }
  const usage = ((quota && quota.usage) || []).find(row => isType(row, typeName) && rowTag(row) === (tag || ''))
  return Math.max(Number(limit.max) - Number((usage && usage.total) || 0), 0)
}

/** The remaining storage quota exactly as the server computed it; the client never derives it. */
export function remainingStorage (quota) {
  const remaining = quota && quota.storage
  if (remaining === undefined || remaining === null || Number(remaining) === UNLIMITED) {
    return null
  }
  return Math.max(Number(remaining), 0)
}

/** The remaining GPU quota of each GPU type the owner has a limit row for. */
export function gpuRemaining (quota) {
  const remaining = {}
  for (const row of ((quota && quota.limits) || [])) {
    if (isType(row, LIMIT_GPU) && rowTag(row)) {
      remaining[rowTag(row)] = remainingLimit(quota, LIMIT_GPU, rowTag(row))
    }
  }
  return remaining
}

const hostTagsOf = (offering) => String((offering && offering.hosttags) || '').split(',').map(tag => tag.trim()).filter(tag => tag)

/** The limit tags of an offering: its host tags, or the CPU-only tag when it names none. */
export function limitTagsOf (offering) {
  const tags = hostTagsOf(offering)
  return tags.length > 0 ? tags : [CPU_LIMIT_TAG]
}

/** The GPU type of an offering: the host tag that has a GPU limit row; null for an offering without a GPU or without such a tag. */
export function gpuTypeOf (offering, remaining) {
  if (!offering || !(Number(offering.gpucount) > 0)) {
    return null
  }
  return hostTagsOf(offering).find(tag => remaining && Object.prototype.hasOwnProperty.call(remaining, tag)) || null
}

const rowsOf = (json, response, row) => (json && json[response] && json[response][row]) || []

/**
 * Reads the quota of an owner ({ account, domainid, projectid } as the wizards hold it). A project has no account row
 * to read, so it is left unbounded and the server decides. A request the role may not make, or one that fails, leaves
 * its part unknown.
 */
export async function loadQuota (owner, userInfo, apis) {
  const quota = { limits: [], usage: [], storage: undefined }
  if (!owner || owner.projectid || !owner.account) {
    return quota
  }
  const own = !!userInfo && owner.account === userInfo.account && owner.domainid === userInfo.domainid
  const who = own ? {} : { account: owner.account, domainid: owner.domainid }
  const attempt = (api, params, apply) => {
    if (!(api in apis)) {
      return Promise.resolve()
    }
    return getAPI(api, params).then(apply).catch(() => {})
  }
  const accountParams = own && userInfo.accountid
    ? { id: userInfo.accountid, details: 'resource,storagequota' }
    : { name: owner.account, domainid: owner.domainid, details: 'resource,storagequota' }
  await Promise.all([
    attempt('listResourceLimits', { ...who, tag: CPU_LIMIT_TAG }, json => {
      quota.limits.push(...rowsOf(json, 'listresourcelimitsresponse', 'resourcelimit'))
    }),
    attempt('listResourceLimits', { ...who, resourcetype: TYPE_ORDINALS.gpu }, json => {
      quota.limits.push(...rowsOf(json, 'listresourcelimitsresponse', 'resourcelimit'))
    }),
    attempt('listAccounts', accountParams, json => {
      const account = rowsOf(json, 'listaccountsresponse', 'account')[0]
      if (account) {
        quota.usage = account.taggedresources || []
        quota.storage = account.remainingstoragequota
      }
    })
  ])
  return quota
}
