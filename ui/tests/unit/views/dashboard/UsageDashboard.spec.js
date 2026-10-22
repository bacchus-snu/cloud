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
import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import { getAPI, postAPI } from '@/api'
import UsageDashboard from '@/views/dashboard/UsageDashboard'
import ResourceCountUsage from '@/components/view/ResourceCountUsage'
import accountSection from '@/config/section/account'
import minimalUser from '../../../mockData/MinimalUserApis.mock.json'
import deployment from '../../../mockData/DeploymentConfig.mock.json'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: { roletype: 'User', accountid: 'a-1' }, project: {}, logoutFlag: false },
  watch: jest.fn()
}))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const uiRoot = path.resolve(__dirname, '../../../..')
// the fork's default config with the deployment's data, which the fixture mirrors
const deployedConfig = { ...JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8')), ...deployment }
const en = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/ko_KR.json'), 'utf8'))

const HOUR = 3600
const usage = (type, total, limit, available) => ({ [type + 'total']: total, [type + 'limit']: limit, [type + 'available']: available })
const account = {
  id: 'a-1',
  name: 'student',
  ...usage('vm', 2, '10', '8'),
  ...usage('cpu', 34, '64', '30'),
  ...usage('memory', 270336, '524288', '253952'),
  ...usage('gpu', 2, '6', '4'),
  ...usage('volume', 3, '20', '17'),
  ...usage('snapshot', 1, 'Unlimited', 'Unlimited'),
  ...usage('template', 0, '0', '0'),
  ...usage('project', 0, '0', '0'),
  ...usage('network', 0, '0', '0'),
  ...usage('vpc', 0, '0', '0'),
  ...usage('ip', 0, '0', '0'),
  ...usage('bucket', 0, '0', '0'),
  ...usage('objectstorage', 0, '0', '0'),
  ...usage('backup', 0, '0', '0'),
  ...usage('backupstorage', 0, '0', '0'),
  // the stock per-type storage figures, which disagree with the one storage quota on purpose
  ...usage('primarystorage', 10, '100', '90'),
  ...usage('secondarystorage', 5, 'Unlimited', 'Unlimited'),
  remainingstoragequota: 85,
  storagequotacombined: true,
  taggedresources: [
    { resourcetype: 16, resourcetypename: 'gpu', tag: 'a100-40g', limit: 2, total: 2, available: 0 },
    { resourcetype: 16, resourcetypename: 'gpu', tag: 'a6000', limit: 4, total: 0, available: 4 },
    { resourcetype: 8, resourcetypename: 'cpu', tag: 'cpu', limit: 16, total: 2, available: 14 }
  ]
}
const leasedVm = { id: 'vm-1', name: 'train', state: 'Running', leaseexpirydate: '2026-10-22T21:12:00+0000', leaseremainingseconds: 35 * HOUR + 12 * 60, leasehours: 36 }
const offerings = [
  { id: 'so-1', name: 'a100-40g-1', displaytext: 'A100 40GB x1', gpucount: 1, gpuslotsavailable: 3, gpuslotstotal: 8 },
  { id: 'so-2', name: 'a6000-2', displaytext: 'A6000 x2', gpucount: 2, gpuslotsavailable: 0, gpuslotstotal: 2 },
  { id: 'so-3', name: 'cpu', displaytext: 'CPU' }
]
const requests = [{ id: 'r-1', state: 'Pending', created: '2026-10-20T10:00:00+0000', cpu: 8 }]

const apiMap = (names) => Object.fromEntries(names.map(name => [name, { params: [] }]))

const answer = (command, params) => {
  switch (command) {
    case 'listAccounts': return Promise.resolve({ listaccountsresponse: { account: [account] } })
    case 'listVirtualMachines':
      return Promise.resolve({ listvirtualmachinesresponse: params.leased ? { count: 1, virtualmachine: [leasedVm] } : { count: 2 } })
    case 'listServiceOfferings': return Promise.resolve({ listserviceofferingsresponse: { count: offerings.length, serviceoffering: offerings } })
    case 'listResourceLimitRequests': return Promise.resolve({ listresourcelimitrequestsresponse: { count: 1, resourcelimitrequest: requests } })
    case 'listEvents': return Promise.resolve({ listeventsresponse: { event: [] } })
  }
  return Promise.resolve({})
}

const mountDashboard = async ({ apis = minimalUser.apis, config = deployedConfig, features = {}, locale = 'en', owner } = {}) => {
  getAPI.mockImplementation((command, params) => owner && command === 'listAccounts'
    ? Promise.resolve({ listaccountsresponse: { account: [owner] } })
    : answer(command, params))
  const i18n = common.createMockI18n(locale, { en, ko_KR: ko })
  const store = common.createMockStore({
    user: { apis: apiMap(apis), info: { roletype: 'User', accountid: 'a-1' }, features: { instanceleaseenabled: true, resourcelimitrequestallowed: true, ...features } }
  })
  const router = common.createMockRouter([{ name: 'dashboard', path: '/dashboard', meta: {}, component: {} }])
  router.push('/dashboard')
  await router.isReady()
  const wrapper = common.createFactory(UsageDashboard, {
    router, i18n, store, mocks: { $config: config, $notifyError: jest.fn() }
  })
  await flushPromises()
  await flushPromises()
  return wrapper
}

describe('dashboard of the minimal user role', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.useFakeTimers()
    jest.spyOn(Date, 'now').mockImplementation(() => Date.parse('2026-10-21T10:00:00Z'))
    getAPI.mockImplementation(answer)
    postAPI.mockImplementation(() => Promise.resolve({ renewvirtualmachineleaseresponse: { virtualmachine: leasedVm } }))
  })

  afterEach(() => {
    jest.useRealTimers()
    jest.restoreAllMocks()
  })

  it('dashboard shows per-GPU-type limits from taggedresources and no project/network/template/bucket rows', async () => {
    const wrapper = await mountDashboard()
    const text = wrapper.text()
    const gpuTypes = wrapper.findAll('.dashboard-gpu-type')
    expect(gpuTypes.map(row => row.text().replace(/\s+/g, ' '))).toEqual([
      expect.stringContaining('#a100-40g'),
      expect.stringContaining('#a6000')
    ])
    expect(gpuTypes[0].text()).toMatch(/2\s*\/\s*2/)
    expect(gpuTypes[1].text()).toMatch(/0\s*\/\s*4/)
    for (const key of ['label.projects', 'label.networks', 'label.guest.networks', 'label.vpcs', 'label.public.ips', 'label.templates',
      'label.buckets', 'label.object.storage', 'label.backup']) {
      expect(text).not.toContain(en[key])
    }
    expect(text).toContain(en['label.instances'])
    expect(text).toContain(en['label.volumes'])

    // one storage figure: the remaining storage quota as the server computed it, never primary and secondary apart, and no
    // used share or limit beside it, since the account's own limit is not what bounds it when a domain quota is smaller
    const storage = wrapper.find('.dashboard-storage-quota')
    expect(storage.exists()).toBe(true)
    expect(storage.text()).toContain('85 GiB')
    expect(storage.text()).not.toContain('90')
    expect(storage.text()).not.toContain('100')
    expect(storage.text()).not.toContain('15')
    expect(storage.text()).not.toContain('%')
    expect(storage.find('.ant-progress').exists()).toBe(false)
    expect(text).not.toContain(en['label.primary.storage'])
    expect(text).not.toContain(en['label.secondary.storage'])
  })

  it('the dashboard shows primary and secondary storage apart when the server says they are not one quota', async () => {
    const apart = await mountDashboard({ owner: { ...account, storagequotacombined: false } })
    expect(apart.find('.dashboard-storage-quota').exists()).toBe(false)
    const storage = apart.find('.dashboard-storage').text()
    expect(storage).toContain(en['label.primary.storage'])
    expect(storage).toContain(en['label.secondary.storage'])
    expect(storage).not.toContain(en['label.storage.quota'])
    expect(storage).not.toContain(en['message.storage.quota.combined'])
    expect(storage).toContain('90')

    const withoutTheField = await mountDashboard({ owner: { ...account, storagequotacombined: undefined } })
    expect(withoutTheField.find('.dashboard-storage-quota').exists()).toBe(false)
    expect(withoutTheField.find('.dashboard-storage').text()).toContain(en['label.primary.storage'])

    const combined = await mountDashboard()
    expect(combined.find('.dashboard-storage-quota').exists()).toBe(true)
    expect(combined.find('.dashboard-storage').text()).not.toContain(en['label.primary.storage'])
  })

  it('the dashboard asks for the remaining storage quota of the caller\'s account only, and calls only what the role allows', async () => {
    const wrapper = await mountDashboard()
    expect(getAPI).toHaveBeenCalledWith('listAccounts', { id: 'a-1', details: 'all,storagequota' })
    expect(getAPI.mock.calls.filter(call => call[0] === 'listAccounts')).toHaveLength(1)

    await wrapper.find('.dashboard-lease').findAll('button').find(button => button.text().includes('Renew lease')).trigger('click')
    await flushPromises()
    const called = [...getAPI.mock.calls, ...postAPI.mock.calls].map(call => call[0])
    expect(called).toContain('renewVirtualMachineLease')
    expect(called.filter(command => !minimalUser.apis.includes(command))).toEqual([])
  })

  it('an account list never asks for the remaining storage quota; one account\'s page does', () => {
    expect(accountSection.params(false)).toEqual({})
    expect(accountSection.params(true)).toEqual({ details: 'all,storagequota' })
  })

  it('dashboard GPU slots follow showGpuSlots', async () => {
    const shown = await mountDashboard()
    const slots = shown.findAll('.dashboard-gpu-slot')
    expect(slots.map(slot => slot.text().replace(/\s+/g, ' '))).toEqual([
      expect.stringMatching(/A100 40GB x1.*3\s*\/\s*8/),
      expect.stringMatching(/A6000 x2.*0\s*\/\s*2/)
    ])
    expect(getAPI).toHaveBeenCalledWith('listServiceOfferings', expect.objectContaining({ gpuenabled: true }))

    getAPI.mockClear()
    const hidden = await mountDashboard({ config: { ...deployedConfig, showGpuSlots: false } })
    expect(hidden.findAll('.dashboard-gpu-slot')).toHaveLength(0)
    expect(getAPI).not.toHaveBeenCalledWith('listServiceOfferings', expect.objectContaining({ gpuenabled: true }))
    expect(deployedConfig.showGpuSlots).toBe(true)
  })

  it('dashboard lists GPU leases with their remaining time and renews through the lease API', async () => {
    const wrapper = await mountDashboard()
    const lease = wrapper.find('.dashboard-lease')
    expect(lease.text()).toContain('train')
    expect(lease.text()).toContain('35h 12m')
    expect(getAPI).toHaveBeenCalledWith('listVirtualMachines', expect.objectContaining({ leased: true }))

    const renew = lease.findAll('button').find(button => button.text().includes('Renew lease'))
    await renew.trigger('click')
    await flushPromises()
    expect(postAPI).toHaveBeenCalledWith('renewVirtualMachineLease', { id: 'vm-1' })

    // without the renewal in the API list the lease is shown but offers no button; with leases off, nothing is asked
    const readOnly = await mountDashboard({ apis: minimalUser.apis.filter(api => api !== 'renewVirtualMachineLease') })
    expect(readOnly.find('.dashboard-lease').findAll('button')).toHaveLength(0)
    getAPI.mockClear()
    const off = await mountDashboard({ features: { instanceleaseenabled: false } })
    expect(off.find('.dashboard-lease').exists()).toBe(false)
    expect(getAPI).not.toHaveBeenCalledWith('listVirtualMachines', expect.objectContaining({ leased: true }))
  })

  it('offers the quota request with the state of the latest one', async () => {
    const wrapper = await mountDashboard({ locale: 'ko_KR' })
    const card = wrapper.find('.dashboard-requests')
    expect(card.text()).toContain('쿼터 신청')
    expect(card.text()).toContain(ko['label.resource.limit.request.state.pending'] || 'Pending')
    expect(card.find('a[href*="resourcelimitrequest"]').exists()).toBe(true)

    const notAllowed = await mountDashboard({ features: { resourcelimitrequestallowed: false } })
    expect(notAllowed.find('.dashboard-requests').exists()).toBe(false)
  })

  it('the limits page shows the same rows and the one storage quota', async () => {
    const router = common.createMockRouter([{ name: 'account', path: '/account/:id', meta: { name: 'account' }, component: {} }])
    router.push('/account/a-1')
    await router.isReady()
    const limits = (config) => common.createFactory(ResourceCountUsage, {
      router,
      i18n: common.createMockI18n('en', { en }),
      store: common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } }),
      props: { resource: account },
      mocks: { $config: config }
    })
    const page = limits(deployedConfig)
    for (const key of ['label.projectlimit', 'label.networklimit', 'label.templatelimit', 'label.bucketlimit', 'label.primarystoragelimit',
      'label.secondarystoragelimit']) {
      expect(page.text()).not.toContain(en[key] || key)
    }
    expect(page.find('.storage-quota').text()).toContain('85 GiB')
    expect(page.find('.storage-quota').text()).not.toContain('90')
    expect(page.find('.storage-quota').text()).not.toContain('100')
    expect(page.vm.taggedUsage.gpu.map(row => row.tag)).toEqual(['a100-40g', 'a6000'])
    // without the block every row of the account is listed
    expect(limits({ ...deployedConfig, hidden: {} }).vm.shownUsage).toEqual(expect.arrayContaining(['project', 'network', 'template', 'bucket', 'storage']))
  })

  it('the limits page shows primary and secondary storage apart when the server says they are not one quota', async () => {
    const router = common.createMockRouter([{ name: 'account', path: '/account/:id', meta: { name: 'account' }, component: {} }])
    router.push('/account/a-1')
    await router.isReady()
    const limits = (resource) => common.createFactory(ResourceCountUsage, {
      router,
      i18n: common.createMockI18n('en', { en }),
      store: common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } }),
      props: { resource },
      mocks: { $config: { ...deployedConfig, hidden: {} } }
    })

    const apart = limits({ ...account, storagequotacombined: false })
    expect(apart.vm.shownUsage).toEqual(expect.arrayContaining(['primarystorage', 'secondarystorage']))
    expect(apart.vm.shownUsage).not.toContain('storage')
    expect(apart.find('.storage-quota').exists()).toBe(false)
    expect(apart.text()).toContain(en['label.primarystoragelimit'])
    expect(apart.text()).toContain(en['label.secondarystoragelimit'])

    const combined = limits(account)
    expect(combined.vm.shownUsage).toContain('storage')
    expect(combined.vm.shownUsage).not.toEqual(expect.arrayContaining(['primarystorage']))
    expect(combined.find('.storage-quota').text()).toContain(en['message.storage.quota.combined'])

    const unknown = limits({ ...account, storagequotacombined: undefined })
    expect(unknown.vm.shownUsage).toEqual(expect.arrayContaining(['primarystorage', 'secondarystorage']))
  })

  it('the default help card is off and carries no link; a deployment\'s config.json brings its own', () => {
    expect(deployedConfig.userCard.enabled).toBe(false)
    expect(deployedConfig.userCard.links).toEqual([])
  })
})
