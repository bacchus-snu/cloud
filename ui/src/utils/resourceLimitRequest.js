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

export const APPROVE_API = 'approveResourceLimitRequest'
export const CHANGED_EVENT = 'resource-limit-request-changed'
export const STATES = ['Pending', 'Approved', 'Rejected', 'Cancelled', 'Expired', 'Revoked']

const MIB_PER_GIB = 1024
const DAY_MS = 24 * 60 * 60 * 1000

/** Whether the server says the caller's limits come from grants, so that the caller may request them. */
export function requestsAllowed (features) {
  return !!features && features.resourcelimitrequestallowed === true
}

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
 * The GPU types a request can name, as the server lists them in the capabilities (the types its quota service sizes);
 * null where it lists none, so that nothing is offered and nothing is guessed from the offerings.
 */
export function quotaGpuTypes (features) {
  const types = features && features.quotagputypes
  return Array.isArray(types) ? [...new Set(types.map(String))].sort() : null
}

export function positiveGpus (gpu) {
  const counts = {}
  Object.keys(gpu || {}).sort().forEach(type => {
    if (Number(gpu[type]) > 0) {
      counts[type] = Number(gpu[type])
    }
  })
  return counts
}

/** The non-empty values of a request or a grant, in display order. */
export function valueItems ({ cpu, memory, storage, gpu }) {
  const items = []
  if (Number(cpu) > 0) {
    items.push({ key: 'cpu', n: Number(cpu) })
  }
  if (Number(memory) > 0) {
    items.push({ key: 'memory', n: formatGib(memory) })
  }
  if (Number(storage) > 0) {
    items.push({ key: 'storage', n: Number(storage) })
  }
  const counts = positiveGpus(gpu)
  Object.keys(counts).forEach(type => items.push({ key: 'gpu', type, n: counts[type] }))
  return items
}

export function requestedValues (record) {
  return { cpu: record.cpu, memory: record.memory, storage: record.storage, gpu: record.gpu }
}

export function grantedValues (record) {
  if (record.approvedcpu === undefined && record.approvedmemory === undefined && record.approvedstorage === undefined && !record.approvedgpu) {
    return null
  }
  return { cpu: record.approvedcpu, memory: record.approvedmemory, storage: record.approvedstorage, gpu: record.approvedgpu }
}

/** The calendar day (yyyy-MM-dd) of an instant in the given zone; an unknown zone falls back to UTC. */
export function dayInZone (instant, timeZone) {
  const date = instant instanceof Date ? instant : new Date(instant)
  if (isNaN(date.getTime())) {
    return null
  }
  const format = (zone) => {
    const parts = {}
    new Intl.DateTimeFormat('en-US', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(date).forEach(part => { parts[part.type] = part.value })
    return `${parts.year}-${parts.month}-${parts.day}`
  }
  try {
    return format(timeZone || 'UTC')
  } catch (e) {
    return format('UTC')
  }
}

const dayNumber = (day) => Date.UTC(...day.split('-').map((part, i) => i === 1 ? Number(part) - 1 : Number(part))) / DAY_MS

export function addDays (day, days) {
  return new Date((dayNumber(day) + days) * DAY_MS).toISOString().substring(0, 10)
}

export function daysBetween (fromDay, toDay) {
  return Math.round(dayNumber(toDay) - dayNumber(fromDay))
}

/** How the request form treats the expiry date, from the server's capabilities. */
export function expiryPolicy (features) {
  const capabilities = features || {}
  const timezone = capabilities.resourcerequestexpirytimezone || 'UTC'
  const maxDays = Math.max(0, Number(capabilities.resourcerequestexpirymaxdays) || 0)
  const defaultDays = Math.max(0, Number(capabilities.resourcerequestexpirydefaultdays) || 0)
  const today = dayInZone(new Date(Date.now()), timezone)
  return {
    timezone,
    // A cap on the expiry makes an end date necessary, whatever the required switch says.
    required: capabilities.resourcerequestexpiryrequired !== false || maxDays > 0,
    minDay: today,
    maxDay: maxDays > 0 ? addDays(today, maxDays) : null,
    defaultDay: defaultDays > 0 ? addDays(today, maxDays > 0 ? Math.min(defaultDays, maxDays) : defaultDays) : null
  }
}

/** Whether a day of the date picker (a dayjs value) lies outside the days a grant may end on. */
export function outsideExpiryWindow (policy, current) {
  const day = current.format('YYYY-MM-DD')
  return day < policy.minDay || (policy.maxDay !== null && day > policy.maxDay)
}

export function gpuParams (counts, keepZero = false) {
  const params = {}
  Object.keys(counts || {}).sort().filter(type => keepZero || Number(counts[type]) > 0).forEach((type, i) => {
    params[`gpu[${i}].type`] = type
    params[`gpu[${i}].count`] = Math.round(Number(counts[type]) || 0)
  })
  return params
}

const count = (value) => Math.round(Number(value) || 0)

/** The parameters of createResourceLimitRequest for the values of the request form. */
export function requestParams (form) {
  const params = {
    cpu: count(form.cpu),
    memory: memoryMibOf(form.memoryGib),
    storage: count(form.storage),
    ...gpuParams(form.gpu)
  }
  if (form.expiry) {
    params.expiry = form.expiry
  }
  const reason = String(form.reason || '').trim()
  if (reason) {
    params.reason = reason
  }
  return params
}

/** The parameters of approveResourceLimitRequest; every value is explicit, so the grant never depends on defaults. */
export function approvalParams (id, form) {
  const params = {
    id,
    cpu: count(form.cpu),
    memory: memoryMibOf(form.memoryGib),
    storage: count(form.storage),
    ...gpuParams(form.gpu, true)
  }
  if (form.expiry) {
    params.expiry = form.expiry
  }
  const comment = String(form.comment || '').trim()
  if (comment) {
    params.comment = comment
  }
  return params
}

/** The form values of a request: what was asked, or what the grant gave. */
export function formValuesOf (values) {
  const gpu = {}
  Object.keys(values.gpu || {}).forEach(type => { gpu[type] = Number(values.gpu[type]) || 0 })
  return {
    cpu: Number(values.cpu) || 0,
    memoryGib: memoryGibOf(values.memory),
    storage: Number(values.storage) || 0,
    gpu
  }
}

/** The values a renewal asks for: the grant's own, the requested ones when it never carried any. */
export function renewalOf (record) {
  const given = grantedValues(record)
  const values = given || requestedValues(record)
  return { ...values, reason: record.reason }
}

/**
 * The effective limits of the account, from listResourceLimits rows; null where no row says.
 * The vCPUs and memory of Instances without a GPU are the rows of the host tags that are no GPU type;
 * with the GPU types unknown (null), or those tags disagreeing, they cannot be told apart from a GPU type's own rows.
 */
export function effectiveQuota (limits, gpuTypes) {
  const rows = limits || []
  const known = Array.isArray(gpuTypes)
  const types = known ? gpuTypes : []
  const row = (name, tag) => rows.find(limit => limit.resourcetypename === name && (tag ? limit.tag === tag : !limit.tag))
  const valueOf = (limit) => limit ? (limit.max === undefined || limit.max === null ? -1 : Number(limit.max)) : null
  const taggedCpu = rows.filter(limit => limit.resourcetypename === 'cpu' && limit.tag)
  const plainTags = [...new Set(taggedCpu.map(limit => limit.tag).filter(tag => !types.includes(tag)))]
  const withoutGpu = (name) => {
    if (!known) {
      return null
    }
    if (plainTags.length === 0) {
      return taggedCpu.length === 0 ? valueOf(row(name)) : null
    }
    const values = plainTags.map(tag => valueOf(row(name, tag)))
    return values.every(value => value === values[0]) ? values[0] : null
  }
  return {
    cpu: withoutGpu('cpu'),
    memory: withoutGpu('memory'),
    storage: valueOf(row('primary_storage')),
    gpu: types.map(type => ({ type, max: valueOf(row('gpu', type)) }))
  }
}

/** How far off the end of a grant is, in calendar days of the expiry zone. */
export function daysLeft (expiry, timezone) {
  const end = dayInZone(expiry, timezone)
  const today = dayInZone(new Date(Date.now()), timezone)
  return end && today ? daysBetween(today, end) : null
}

/**
 * Whether the account's effective quota keeps every value of a grant after the grant ends: per category, the other
 * approved grants that end later (or never) give at least as much, the quota being the largest value, not the sum.
 */
export function isCoveredByLaterGrants (grant, grants) {
  if (!grant.approvedexpiry) {
    return true
  }
  const end = Date.parse(grant.approvedexpiry)
  const later = (grants || [])
    .filter(other => other.id !== grant.id && other.state === 'Approved' && (!other.approvedexpiry || Date.parse(other.approvedexpiry) > end))
    .map(other => grantedValues(other) || {})
  const largest = (value) => Math.max(0, ...later.map(value).map(Number).filter(n => !isNaN(n)))
  const given = grantedValues(grant) || {}
  const covered = (value, amount) => !(Number(amount) > 0) || largest(value) >= Number(amount)
  return covered(values => values.cpu, given.cpu) &&
    covered(values => values.memory, given.memory) &&
    covered(values => values.storage, given.storage) &&
    Object.keys(given.gpu || {}).every(type => covered(values => (values.gpu || {})[type], given.gpu[type]))
}
