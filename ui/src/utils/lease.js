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

export const RENEW_LEASE_API = 'renewVirtualMachineLease'

// the lease fields of deploy and update are the administrators' override
export const SET_LEASE_API = 'setVirtualMachineLease'

export function hasLease (resource) {
  return !!(resource && resource.leaseexpirydate)
}

/**
 * Seconds left of an Instance's lease at `now`. The server's own count at the time of the response (`fetchedAt`) is
 * preferred, so that the browser's clock does not shift it; the expiry date is the fallback.
 */
export function leaseSecondsLeft (resource, fetchedAt, now) {
  if (!hasLease(resource)) {
    return null
  }
  if (typeof resource.leaseremainingseconds === 'number') {
    const elapsed = Math.max(0, Math.floor((now - fetchedAt) / 1000))
    return Math.max(0, resource.leaseremainingseconds - elapsed)
  }
  const expiry = new Date(resource.leaseexpirydate).getTime()
  return isNaN(expiry) ? null : Math.max(0, Math.floor((expiry - now) / 1000))
}

export function formatLeaseLeft (seconds, t) {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 0) {
    return t('label.lease.hours.minutes', { hours, minutes })
  }
  return t('label.lease.minutes', { minutes: Math.max(1, minutes) })
}

export function canRenewLease (record, apis) {
  return !!(apis && RENEW_LEASE_API in apis) && hasLease(record) && record.state === 'Running'
}

// a lease counted in hours from its offering, shown by the time left rather than by days
export function isHourLease (resource) {
  return hasLease(resource) && resource.leasehours !== undefined
}

/**
 * The title of an hour lease's mark and whether it ends within the hour; null for any other lease.
 */
export function leaseIndicator (resource, t, fetchedAt = Date.now(), now = fetchedAt) {
  if (!isHourLease(resource)) {
    return null
  }
  const seconds = leaseSecondsLeft(resource, fetchedAt, now)
  return {
    title: seconds > 0 ? t('label.lease.remaining') + ': ' + formatLeaseLeft(seconds, t) : t('label.lease.ended'),
    ending: seconds !== null && seconds < 3600
  }
}

export function remainingLeaseDaysText (leaseDuration) {
  if (leaseDuration > 0) {
    return leaseDuration + (leaseDuration === 1 ? ' day' : ' days')
  } else if (leaseDuration === 0) {
    return 'expiring today'
  }
  return 'over'
}

export function leaseDaysColor (leaseDuration) {
  if (leaseDuration >= 7) {
    return '#888'
  } else if (leaseDuration >= 0) {
    return '#ffbf00'
  }
  return '#fd7e14'
}
