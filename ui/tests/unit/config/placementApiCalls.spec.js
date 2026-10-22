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

import minimalUser from '../../mockData/MinimalUserApis.mock.json'

const src = (file) => fs.readFileSync(path.join(__dirname, '../../../src', file), 'utf8')

const callsOf = (text) => [...text.matchAll(/(?:getAPI|postAPI|attempt)\(\s*'([A-Za-z]+)'/g)].map(match => match[1])

// the commands each view of the placement unit asks the server for; a role that may not call one never reaches the
// view's use of it
const views = {
  'utils/placement.js': ['checkPlacement', 'listCapabilities', 'listPlacementCapacity'],
  'utils/limits.js': ['listAccounts', 'listResourceLimits'],
  'views/compute/StartVirtualMachine.vue': ['startVirtualMachine'],
  'views/storage/AttachVolume.vue': ['attachVolume', 'listVirtualMachines'],
  'views/compute/DestroyVM.vue': ['destroyVirtualMachine', 'listVolumes'],
  'views/compute/ScaleVM.vue': ['listDiskOfferings', 'listServiceOfferings', 'listTemplates', 'scaleVirtualMachine'],
  'views/compute/ReinstallVm.vue': ['listDiskOfferings', 'listOsCategories', 'listTemplates', 'listVolumes', 'restoreVirtualMachine']
}

// the pod, cluster and host lists of the start dialog are asked only for the Admin role type, as before
const adminOnly = { 'views/compute/StartVirtualMachine.vue': ['listClusters', 'listHosts', 'listPods'] }

describe('the student keep-list covers what the placement views call', () => {
  Object.entries(views).forEach(([file, expected]) => {
    it(file + ' asks for exactly ' + expected.join(', '), () => {
      const found = [...new Set(callsOf(src(file)))].filter(name => !(adminOnly[file] || []).includes(name)).sort()
      expect(found).toEqual([...expected].sort())
      for (const name of expected) {
        expect(minimalUser.apis).toContain(name)
      }
    })
  })

  it('the reinstall dialog and the wizards that share the step read the capability list through the helpers', () => {
    expect(src('views/compute/ReinstallVm.vue')).toContain('loadQuota(')
    expect(src('views/compute/ReinstallVm.vue')).toContain('fetchCapabilities(')
    expect(src('utils/vcpuStep.js')).toContain('fetchCapabilities(')
  })

  it('the wizard asks the placement, capacity, limit, account and capability lists the role keeps', () => {
    const wizard = src('views/compute/DeployVM.vue')
    expect(wizard).toContain('loadQuota(')
    expect(wizard).toContain('fetchCapabilities(')
    for (const name of ['checkPlacement', 'listPlacementCapacity', 'listResourceLimits', 'listAccounts', 'listCapabilities', 'deployVirtualMachine']) {
      expect(minimalUser.apis).toContain(name)
    }
  })
})
