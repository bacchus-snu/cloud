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

import fs from 'fs'
import path from 'path'

import mockAxios from '../../../mock/mockAxios'
import common from '../../../common'
import LeaseCountdown from '@/components/view/LeaseCountdown'
import ActionButton from '@/components/view/ActionButton'
import InfoCard from '@/components/view/InfoCard'
import { canRenewLease, formatLeaseLeft, isHourLease, leaseIndicator, leaseSecondsLeft } from '@/utils/lease'
import compute from '@/config/section/compute'

jest.mock('axios', () => mockAxios)
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: {}, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) } } }))

const locales = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales', name), 'utf8'))
const en = locales('en.json')
const ko = locales('ko_KR.json')

const HOUR = 3600
const leasedVm = {
  id: 'vm-1',
  name: 'gpu-vm',
  state: 'Running',
  leaseexpirydate: '2026-10-22T21:12:00+0000',
  leaseremainingseconds: 35 * HOUR + 12 * 60,
  leaseexpiryaction: 'STOP',
  leasehours: 36
}

const i18n = common.createMockI18n('en', { en, ko_KR: ko })
const storeWith = (apis) => common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } })
const renewAction = () => compute.children.find(child => child.name === 'vm').actions.find(action => action.api === 'renewVirtualMachineLease')

const countdown = (resource, locale = 'en') => {
  i18n.global.locale = locale
  return common.createFactory(LeaseCountdown, { i18n, store: storeWith({}), props: { resource } })
}

describe('instance page shows the remaining lease time and renews', () => {
  let clock

  beforeEach(() => {
    jest.useFakeTimers()
    clock = Date.parse('2026-10-21T10:00:00Z')
    jest.spyOn(Date, 'now').mockImplementation(() => clock)
  })

  afterEach(() => {
    jest.useRealTimers()
    jest.restoreAllMocks()
    i18n.global.locale = 'en'
  })

  it('shows the time left of the lease from the server\'s count, in hours and minutes', () => {
    expect(countdown(leasedVm).text()).toContain('35h 12m')
    expect(countdown(leasedVm, 'ko_KR').text()).toContain('35시간 12분')
  })

  it('counts down while the page stays open and turns red in the last hour', async () => {
    const wrapper = countdown({ ...leasedVm, leaseremainingseconds: HOUR + 60 })
    expect(wrapper.text()).toContain('1h 1m')
    expect(wrapper.find('.lease-countdown--ending').exists()).toBe(false)

    clock += 90 * 1000
    jest.advanceTimersByTime(90 * 1000)
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('59m')
    expect(wrapper.find('.lease-countdown--ending').exists()).toBe(true)
  })

  it('says the lease has ended at zero and shows nothing for an instance without a lease', () => {
    expect(countdown({ ...leasedVm, leaseremainingseconds: 0 }).text()).toContain('Lease ended')
    expect(countdown({ id: 'vm-2', state: 'Running' }).html()).not.toContain('lease-countdown')
  })

  it('falls back to the expiry date when the response has no count', () => {
    const now = new Date('2026-10-21T10:00:00Z').getTime()
    expect(leaseSecondsLeft({ leaseexpirydate: '2026-10-21T12:30:00Z' }, now, now)).toBe(2.5 * HOUR)
    expect(formatLeaseLeft(30, (key, values) => `${key}:${values.minutes}`)).toBe('label.lease.minutes:1')
  })

  it('offers the renewal only for a running instance with a lease', () => {
    const action = renewAction()
    expect(action.dataView).toBe(true)
    expect(action.label).toBe('label.lease.renew')
    expect(action.message).toBe('message.lease.renew')
    expect(action.args).toBeUndefined()
    expect(action.show(leasedVm)).toBe(true)
    expect(action.show({ ...leasedVm, state: 'Stopped' })).toBe(false)
    expect(action.show({ id: 'vm-3', state: 'Running' })).toBe(false)
    expect(en['label.lease.renew']).toBe('Renew lease')
    expect(ko['label.lease.renew']).toBe('기한 갱신')
  })

  it('shows the renew button only when the caller\'s API list has the renewal, and renews the instance', async () => {
    const router = common.createMockRouter([{ name: 'vm', path: '/vm', meta: { name: 'vm' } }])
    const props = { actions: [renewAction()], resource: leasedVm, dataView: true }

    const denied = common.createFactory(ActionButton, { router, i18n, store: storeWith({ listVirtualMachines: {} }), props })
    expect(denied.html()).not.toContain('field-time')

    const allowed = common.createFactory(ActionButton, { router, i18n, store: storeWith({ renewVirtualMachineLease: {} }), props })
    const button = allowed.findAll('button').find(b => b.html().includes('field-time'))
    expect(button).toBeDefined()
    await button.trigger('click')
    const executed = allowed.emitted('exec-action')
    expect(executed).toHaveLength(1)
    expect(executed[0][0].api).toBe('renewVirtualMachineLease')
    expect(executed[0][0].resource.id).toBe('vm-1')

    expect(canRenewLease(leasedVm, { renewVirtualMachineLease: {} })).toBe(true)
    expect(canRenewLease(leasedVm, {})).toBe(false)
    expect(canRenewLease({ ...leasedVm, state: 'Stopped' }, { renewVirtualMachineLease: {} })).toBe(false)
  })

  it('leaves the stock lease fields of deploy and edit to callers that may set a lease', () => {
    const src = (file) => fs.readFileSync(path.join(__dirname, '../../../../src/views/compute', file), 'utf8')
    expect(src('DeployVM.vue')).toContain('isLeaseFeatureEnabled: this.$store.getters.features.instanceleaseenabled && SET_LEASE_API in this.$store.getters.apis')
    expect(src('EditVM.vue')).toContain('isLeaseEditable: this.$store.getters.features.instanceleaseenabled && SET_LEASE_API in this.$store.getters.apis')
  })

  it('marks an hour lease in the list by the hours left, red in the last hour', () => {
    const t = (key, values) => i18n.global.t(key, values)
    const now = Date.now()
    expect(isHourLease(leasedVm)).toBe(true)
    expect(isHourLease({ ...leasedVm, leasehours: undefined, leaseduration: 2 })).toBe(false)

    const plenty = leaseIndicator(leasedVm, t, now, now)
    expect(plenty.title).toBe('Lease ends in: 35h 12m')
    expect(plenty.ending).toBe(false)
    const ending = leaseIndicator({ ...leasedVm, leaseremainingseconds: 30 * 60 }, t, now, now)
    expect(ending.title).toBe('Lease ends in: 30m')
    expect(ending.ending).toBe(true)
    expect(leaseIndicator({ ...leasedVm, leaseremainingseconds: 0 }, t, now, now).title).toBe('Lease ended')
    i18n.global.locale = 'ko_KR'
    expect(leaseIndicator({ ...leasedVm, leaseremainingseconds: 0 }, t, now, now).title).toBe('기한 만료')
    expect(leaseIndicator(leasedVm, t, now, now).title).toBe('남은 기한: 35시간 12분')
    i18n.global.locale = 'en'
    expect(leaseIndicator({ ...leasedVm, leasehours: undefined, leaseduration: 2 }, t, now, now)).toBeNull()

    const list = fs.readFileSync(path.join(__dirname, '../../../../src/components/view/ListView.vue'), 'utf8')
    expect(list).toContain('leaseIndicator(record, this.$t)')
  })

  it('shows an hour lease on the instance page by its countdown alone', async () => {
    const router = common.createMockRouter([{ name: 'vm', path: '/vm/:id', meta: { name: 'vm' }, component: {} },
      { name: 'computeoffering', path: '/computeoffering/:id', meta: { name: 'computeoffering' }, component: {} }])
    router.push('/vm/vm-1')
    await router.isReady()
    const resource = { ...leasedVm, leaseduration: 1, serviceofferingid: 'so-1', serviceofferingname: 'a100-40g', ostypeid: 'os-1' }
    const store = common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } })
    const card = common.createFactory(InfoCard, { router, i18n, store, props: { resource, loading: false } })
    expect(card.findAll('.lease-countdown')).toHaveLength(1)
    expect(card.findAll('.anticon-field-time')).toHaveLength(1)
  })

  it('names no product in the lease strings', () => {
    for (const key of Object.keys(en).filter(k => k.includes('.lease.'))) {
      expect(en[key]).not.toMatch(/cloudstack/i)
    }
  })
})
