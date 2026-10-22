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

import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import ComputeOfferingSelection from '@/views/compute/wizard/ComputeOfferingSelection'
import ComputeSelection from '@/views/compute/wizard/ComputeSelection'

jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const cpuOffering = { id: 'o-cpu', name: 'cpu', cpunumber: 2, cpuspeed: 2400, memory: 4096, hosttags: 'cpu' }
const gpuOffering = (id, count, tags, slots = {}) => ({
  id,
  name: id,
  cpunumber: 12 * count,
  cpuspeed: 2400,
  memory: 98304 * count,
  gpucardname: 'A100',
  gpucount: count,
  hosttags: tags,
  ...slots
})

const mountOfferings = (props, config = {}) => common.createFactory(ComputeOfferingSelection, {
  store: common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } }),
  i18n: common.createMockI18n('en', {}),
  mocks: { $config: config },
  props: { computeItems: [], ...props },
  stubs: { 'font-awesome-icon': true }
})

const keys = (wrapper) => wrapper.vm.columns.map(column => column.key)
const rowOf = (wrapper, id) => wrapper.vm.tableSource.find(row => row.key === id)

describe('ComputeOfferingSelection', () => {
  it('shows available/total slots for GPU offerings', async () => {
    const items = [cpuOffering, gpuOffering('a100-40g-1', 1, 'a100-40g', { gpuslotsavailable: 3, gpuslotstotal: 8 })]
    const wrapper = mountOfferings({ computeItems: items }, { showGpuSlots: true })
    await flushPromises()
    expect(keys(wrapper)).toContain('gpuslots')
    expect(rowOf(wrapper, 'a100-40g-1').gpuSlots).toBe('3 / 8')
    expect(rowOf(wrapper, 'o-cpu').gpuSlots).toBe('')
    expect(wrapper.text()).toContain('3 / 8')
    expect(wrapper.html()).toContain('label.gpu.slots')
  })

  it('showGpuSlots false hides the slot column', async () => {
    const items = [gpuOffering('a100-40g-1', 1, 'a100-40g', { gpuslotsavailable: 3, gpuslotstotal: 8 })]
    const hidden = mountOfferings({ computeItems: items }, { showGpuSlots: false })
    await flushPromises()
    expect(keys(hidden)).not.toContain('gpuslots')
    expect(hidden.text()).not.toContain('3 / 8')

    // the flag defaults to shown
    const byDefault = mountOfferings({ computeItems: items }, {})
    expect(keys(byDefault)).toContain('gpuslots')
  })

  it('a GPU offering with no free slot is shown and stays selectable: capacity never disables', async () => {
    const items = [gpuOffering('a6000-1', 1, 'a6000', { gpuslotsavailable: 0, gpuslotstotal: 2 })]
    const wrapper = mountOfferings({ computeItems: items, gpuQuota: { a6000: 1 } }, { showGpuSlots: true })
    expect(rowOf(wrapper, 'a6000-1').gpuSlots).toBe('0 / 2')
    expect(rowOf(wrapper, 'a6000-1').disabled).toBe(false)
  })

  it('disables GPU offering above remaining type quota', async () => {
    const items = [
      cpuOffering,
      gpuOffering('a100-40g-1', 1, 'a100-40g'),
      gpuOffering('a100-40g-nvlink-2', 2, 'a100-40g,a100-40g-nvlink'),
      gpuOffering('a100-80g-1', 1, 'a100-80g'),
      gpuOffering('gtx1080-1', 1, 'gtx1080')
    ]
    const wrapper = mountOfferings({
      computeItems: items,
      gpuQuota: { 'a100-40g': 1, 'a100-80g': 0, gtx1080: null }
    })
    await flushPromises()
    expect(rowOf(wrapper, 'a100-40g-1').disabled).toBe(false)
    expect(rowOf(wrapper, 'a100-40g-nvlink-2').disabled).toBe(true)
    expect(rowOf(wrapper, 'a100-40g-nvlink-2').disabledReason).toBe('quota')
    expect(rowOf(wrapper, 'a100-80g-1').disabled).toBe(true)
    // an unlimited type, and an offering without a GPU, are never disabled by quota
    expect(rowOf(wrapper, 'gtx1080-1').disabled).toBe(false)
    expect(rowOf(wrapper, 'o-cpu').disabled).toBe(false)
    // the reason is shown on the row
    expect(wrapper.text()).toContain('label.offering.over.quota')

    // a disabled row cannot be picked
    wrapper.vm.onClickRow(rowOf(wrapper, 'a100-80g-1')).onClick()
    expect(wrapper.emitted('select-compute-item')).toBeUndefined()
    wrapper.vm.onClickRow(rowOf(wrapper, 'a100-40g-1')).onClick()
    expect(wrapper.emitted('select-compute-item')[0]).toEqual(['a100-40g-1'])
  })

  it('does not preselect a GPU offering that is over its type quota', async () => {
    const items = [gpuOffering('a100-40g-1', 1, 'a100-40g'), cpuOffering]
    const wrapper = mountOfferings({ computeItems: items, gpuQuota: { 'a100-40g': 0 }, loading: true, preFillContent: {} })
    await wrapper.setProps({ loading: false })
    expect(wrapper.emitted('select-compute-item')[0]).toEqual(['o-cpu'])
  })

  it('moves the choice off a GPU offering when the quota that disables it arrives later', async () => {
    const items = [gpuOffering('a100-40g-1', 1, 'a100-40g'), cpuOffering]
    const wrapper = mountOfferings({ computeItems: items, loading: true, preFillContent: {} })
    await wrapper.setProps({ loading: false })
    expect(wrapper.emitted('select-compute-item')[0]).toEqual(['a100-40g-1'])

    await wrapper.setProps({ gpuQuota: { 'a100-40g': 0 } })
    expect(wrapper.emitted('select-compute-item')[1]).toEqual(['o-cpu'])
    expect(wrapper.vm.selectedRowKeys).toEqual(['o-cpu'])

    // a quota that leaves the choice alone does not move it
    await wrapper.setProps({ gpuQuota: { 'a100-40g': 0, a6000: 1 } })
    expect(wrapper.emitted('select-compute-item')).toHaveLength(2)
  })

  it('clears the choice when the quota leaves nothing to choose', async () => {
    const items = [gpuOffering('a100-40g-1', 1, 'a100-40g')]
    const wrapper = mountOfferings({ computeItems: items, loading: true, preFillContent: {} })
    await wrapper.setProps({ loading: false })
    await wrapper.setProps({ gpuQuota: { 'a100-40g': 0 } })
    expect(wrapper.emitted('select-compute-item')[1]).toEqual([undefined])
    expect(wrapper.vm.selectedRowKeys).toEqual([])
  })

  it('takes the GPU type only from the offering\'s own host tags, never from the CPU-only fallback', async () => {
    const untagged = gpuOffering('untagged-gpu', 1, undefined)
    delete untagged.hosttags
    const wrapper = mountOfferings({ computeItems: [untagged], gpuQuota: { cpu: 0 } })
    expect(rowOf(wrapper, 'untagged-gpu').disabled).toBe(false)
  })

  it('CPU speed is never shown with the default config', async () => {
    const wrapper = mountOfferings({ computeItems: [cpuOffering] }, {})
    await flushPromises()
    expect(keys(wrapper)).not.toContain('cpuspeed')
    expect(rowOf(wrapper, 'o-cpu').cpu).toBe('2 CPU')
    expect(wrapper.text()).not.toMatch(/GHz/i)
  })

  it('showCpuSpeed true shows the speed column', async () => {
    const wrapper = mountOfferings({ computeItems: [cpuOffering] }, { showCpuSpeed: true })
    await flushPromises()
    expect(keys(wrapper)).toContain('cpuspeed')
    expect(rowOf(wrapper, 'o-cpu').cpuspeed).toBe('2400')
    expect(rowOf(wrapper, 'o-cpu').cpu).toBe('2 CPU')
    expect(wrapper.html()).toContain('label.cpuspeed')
  })
})

const mountCompute = (props, config = {}) => common.createFactory(ComputeSelection, {
  store: common.createMockStore({ user: { apis: {}, info: { roletype: 'User' }, features: {} } }),
  i18n: common.createMockI18n('en', {}),
  mocks: { $config: config },
  props: {
    cpuNumberInputDecorator: 'cpunumber',
    cpuSpeedInputDecorator: 'cpuspeed',
    memoryInputDecorator: 'memory',
    isCustomized: true,
    isConstrained: true,
    minCpu: 2,
    maxCpu: 12,
    minMemory: 32,
    maxMemory: 24576,
    ...props
  }
})

describe('ComputeSelection bounds', () => {
  it('steps by the pool vCPU step: a multiple of it only', async () => {
    const stepTwo = mountCompute({ cpuStep: 2 })
    await flushPromises()
    stepTwo.vm.updateComputeCpuNumber(3)
    expect(stepTwo.vm.errors.cpu.status).toBe('error')
    expect(stepTwo.emitted('update-compute-cpunumber').filter(call => call[1] === 3)).toHaveLength(0)
    stepTwo.vm.updateComputeCpuNumber(4)
    expect(stepTwo.vm.errors.cpu.status).toBe('')
    expect(stepTwo.emitted('update-compute-cpunumber').filter(call => call[1] === 4)).toHaveLength(1)
    expect(stepTwo.findAllComponents({ name: 'AInputNumber' })[0].props('step')).toBe(2)

    // with a step of 1 odd values are offered
    const stepOne = mountCompute({ cpuStep: 1 })
    await flushPromises()
    stepOne.vm.updateComputeCpuNumber(3)
    expect(stepOne.vm.errors.cpu.status).toBe('')
    expect(stepOne.emitted('update-compute-cpunumber').filter(call => call[1] === 3)).toHaveLength(1)
    expect(stepOne.findAllComponents({ name: 'AInputNumber' })[0].props('step')).toBe(1)
  })

  it('refuses a value above the maximum it is given, constrained or not', async () => {
    const constrained = mountCompute({ cpuStep: 2, maxCpu: 12 })
    await flushPromises()
    constrained.vm.updateComputeCpuNumber(14)
    expect(constrained.vm.errors.cpu.status).toBe('error')
    constrained.vm.updateComputeMemory(30000)
    expect(constrained.vm.errors.memory.status).toBe('error')

    const free = mountCompute({ isConstrained: false, minCpu: 0, maxCpu: 12, minMemory: 0, maxMemory: 24576 })
    await flushPromises()
    free.vm.updateComputeCpuNumber(14)
    expect(free.vm.errors.cpu.status).toBe('error')
    free.vm.updateComputeMemory(24576)
    expect(free.vm.errors.memory.status).toBe('')
  })

  it('says so when the remaining quota is below the minimum', async () => {
    const wrapper = mountCompute({ cpuStep: 2, minCpu: 2, maxCpu: 0 })
    await flushPromises()
    wrapper.vm.updateComputeCpuNumber(2)
    expect(wrapper.vm.errors.cpu.status).toBe('error')
    expect(wrapper.vm.errors.cpu.message).toBe('message.error.quota.below.minimum')
  })

  it('CPU speed is never shown with the default config, and shown with showCpuSpeed', async () => {
    const byDefault = mountCompute({ isConstrained: false, minCpu: 0, maxCpu: Number.MAX_SAFE_INTEGER })
    await flushPromises()
    expect(byDefault.html()).not.toContain('label.cpuspeed')

    const shown = mountCompute({ isConstrained: false, minCpu: 0, maxCpu: Number.MAX_SAFE_INTEGER }, { showCpuSpeed: true })
    await flushPromises()
    expect(shown.html()).toContain('label.cpuspeed')
  })
})
