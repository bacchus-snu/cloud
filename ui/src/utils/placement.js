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

// the error code of an operation that needs an approved move it did not carry, or carried with a token that no longer matches
export const VOLUME_MOVE_REQUIRED = 4336

export function checkPlacement (params) {
  return getAPI('checkPlacement', { ...params }).then(json => json.checkplacementresponse.placement)
}

export function listPlacementCapacity (params) {
  return getAPI('listPlacementCapacity', { ...params }).then(json => json.listplacementcapacityresponse.placementcapacity)
}

/** The capabilities of the caller's domain, or of the given one: the root disk bounds, the vCPU step, the keep-root default. */
export function fetchCapabilities (domainid) {
  return getAPI('listCapabilities', domainid ? { domainid } : {}).then(json => json.listcapabilitiesresponse.capability || {})
}

/** Whether the server says the caller may ask for the automatic migration of a volume. */
export function automigrateAllowed (features) {
  return !!features && features.automigrateallowed === true
}

const DEFAULT_VCPU_STEP = 2

/** The multiple an Instance's vCPU count must be; the operator's setting, 2 when the server does not say. */
export function vcpuStep (capabilities) {
  const step = Math.floor(Number(capabilities && capabilities.poolvcpustep))
  return step >= 1 ? step : DEFAULT_VCPU_STEP
}

/** The smallest vCPU count of an offering that is a multiple of the step. */
export function vcpuMinimum (offeringMinimum, step) {
  return Math.ceil(Math.max(Number(offeringMinimum) || 1, 1) / step) * step
}

/** What a verdict asks of the caller: go on, ask for the approval of a move, or stop with the reason. */
export function placementAction (placement) {
  switch (placement && placement.verdict) {
    case 'move':
      return 'approve'
    case 'none':
      return 'refuse'
    default:
      return 'proceed'
  }
}

/** Whether a failed job result or a refused request is the server asking for an approved move. */
export function isMoveRequired (failure) {
  const code = failure && failure.jobresult && failure.jobresult.cserrorcode
  if (code !== undefined && code !== null) {
    return Number(code) === VOLUME_MOVE_REQUIRED
  }
  const data = failure && failure.response && failure.response.data
  return Object.values(data || {}).some(body => body && Number(body.cserrorcode) === VOLUME_MOVE_REQUIRED)
}

/** The kind of a checkPlacement request, or null while the request lacks what the server needs. */
export function placementKind (request) {
  const r = request || {}
  if (r.virtualmachineid && r.volumeid) {
    return 'attach'
  }
  if (r.virtualmachineid) {
    return 'start'
  }
  if (r.volumeid && r.serviceofferingid) {
    return 'deploy-from-volume'
  }
  if (r.templateid && r.serviceofferingid && r.zoneid) {
    return 'deploy'
  }
  return null
}

const REASONS = {
  quota: 'message.placement.reason.quota',
  'storage quota': 'message.placement.reason.storage.quota',
  'cpu pool': 'message.placement.reason.cpu.pool',
  'vcpu step': 'message.placement.reason.vcpu.step',
  memory: 'message.placement.reason.memory',
  'local storage': 'message.placement.reason.local.storage',
  'volumes unavailable': 'message.placement.reason.volumes.unavailable',
  'instance snapshots': 'message.placement.reason.instance.snapshots',
  'volume snapshots on primary storage': 'message.placement.reason.volume.snapshots',
  'encrypted volume': 'message.placement.reason.encrypted.volume',
  'affinity groups': 'message.placement.reason.affinity.groups',
  'suitable host': 'message.placement.reason.suitable.host'
}

const GPU_GROUP = 'gpu group '

/** A reason the server gave, in the user's language when it is a known one and as given otherwise. */
export function reasonText (t, reason) {
  if (!reason) {
    return ''
  }
  if (REASONS[reason]) {
    return t(REASONS[reason])
  }
  if (reason.startsWith(GPU_GROUP)) {
    return t('message.placement.reason.gpu.group', { group: reason.slice(GPU_GROUP.length) })
  }
  return reason
}

/** An estimated duration in seconds, as a short phrase. */
export function etaText (t, seconds) {
  const total = Math.max(Math.round(Number(seconds) || 0), 1)
  if (total < 90) {
    return t('label.placement.eta.seconds', { n: total })
  }
  const minutes = Math.ceil(total / 60)
  if (minutes < 60) {
    return t('label.placement.eta.minutes', { n: minutes })
  }
  return t('label.placement.eta.hours', { h: Math.floor(minutes / 60), m: minutes % 60 })
}
