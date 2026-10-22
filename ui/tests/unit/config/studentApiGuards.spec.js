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

import { STUDENT_FILES, filesOf, sourceDirectory as src } from '../../common/studentFiles'

// A command outside the keep-list that a file names must be guarded by the caller's API list in the same file, or be
// listed here with the reason the role never reaches the call. A guard is `'<command>' in <...>apis` or `apis.<command>`.
const NOT_REACHED = {
  'api/index.js': { logout: 'an API authenticator, which the server serves before any role check' },
  'views/AutogenView.vue': {
    listProjects: 'follows a ?projectid link into a project; a role without project accounts has no such link'
  },
  'views/compute/DeployVM.vue': {
    createBackupSchedule: 'follows the assignment to a backup offering, which the wizard offers only with listBackupOfferings',
    listIsos: 'the ISO image type is offered only with listIsos; the lookup of the template or ISO of a ?isoid link is reached only through a link that no student page produces'
  },
  'views/compute/EditVM.vue': { listConfigurations: 'fetchDynamicScalingVmConfig, which nothing calls' },
  'views/compute/StartVirtualMachine.vue': {
    listClusters: 'asked only for the Admin role type',
    listHosts: 'asked only for the Admin role type',
    listPods: 'asked only for the Admin role type'
  },
  'views/compute/wizard/MultiNetworkSelection.vue': { listNetworks: 'mounted by the wizard only with listNetworks' },
  'views/compute/wizard/NetworkSelection.vue': {
    listNetworkOfferings: 'mounted by the wizard only with listNetworks',
    listVPCs: 'mounted by the wizard only with listNetworks'
  },
  'views/compute/wizard/OwnershipSelection.vue': {
    listDomains: 'mounted only for role types other than User',
    listProjects: 'mounted only for role types other than User'
  },
  'views/compute/wizard/SecurityGroupSelection.vue': { listSecurityGroups: 'mounted by the wizard only with listSecurityGroups' },
  'views/storage/CreateVolume.vue': { listStoragePools: 'asked only after the create-on-storage switch, which only the Admin role type sees' },
  'views/storage/TakeSnapshot.vue': { listStoragePools: 'asked only for the Admin role type' },
  'views/dashboard/UsageDashboard.vue': {
    listKubernetesClusters: 'a card shown and filled only when countShown finds the list in the caller\'s API list',
    listNetworks: 'a card shown and filled only when countShown finds the list in the caller\'s API list',
    listPublicIpAddresses: 'a card shown and filled only when countShown finds the list in the caller\'s API list',
    listVPCs: 'a card shown and filled only when countShown finds the list in the caller\'s API list',
    listProjects: 'the dashboard of a project, which a role without project accounts never selects'
  },
  'components/view/AnnotationsTab.vue': { updateAnnotationVisibility: 'the tab is shown only with listAnnotations, and the visibility only to its administrators' },
  'components/view/ListView.vue': {
    listUsageTypes: 'the usage records page, which needs the usage commands in the API list',
    updateAnnotationVisibility: 'the annotations list, shown only with listAnnotations'
  },
  'components/view/ResourceView.vue': { listNetworks: 'the page of a resource that names an associated network: a public IP address' },
  'components/view/SearchFilter.vue': {
    listAffinityGroupTypes: 'the chip of the affinity group page',
    listAlertTypes: 'the chip of the alerts page',
    listHypervisors: 'the chip of a hypervisor filter, which only the host pages offer'
  },
  'components/view/SearchView.vue': {
    listAffinityGroupTypes: 'a filter of the affinity group page',
    listAlertTypes: 'a filter of the alerts page',
    listExtensions: 'initFields leaves the filter out unless the list is in the API list',
    listGpuCards: 'initFields leaves the filter out unless the list is in the API list',
    listHypervisors: 'initFields leaves the filter out unless the list is in the API list',
    listImageStores: 'initFields leaves the filter out unless the list is in the API list',
    listManagementServers: 'initFields leaves the filter out unless the list is in the API list',
    listNetworks: 'initFields leaves the filter out unless the list is in the API list',
    listStoragePools: 'initFields leaves the filter out unless the list is in the API list',
    listVgpuProfiles: 'initFields leaves the filter out unless the list is in the API list'
  }
}

// a command named as the first argument of a call, or assigned to the apiName variable that a call is given
const CALL = /(?:(?:getAPI|postAPI|callAPI|attempt)\(\s*|(?<![:\w.])apiName\s*=\s*)['"`]([A-Za-z]+)['"`]/g

const isGuarded = (text, command) =>
  new RegExp(`'${command}'\\s+in\\s+[\\w$.]*apis|apis\\.${command}\\b|apis\\['${command}'\\]`, 'i').test(text)

const unguardedCommands = (file) => {
  const text = fs.readFileSync(path.join(src, file), 'utf8')
  const named = [...new Set([...text.matchAll(CALL)].map(match => match[1]))]
  return named.filter(command => !minimalUser.apis.includes(command) && !isGuarded(text, command)).sort()
}

describe('the commands outside the student keep-list that the student-reachable files name', () => {
  const files = STUDENT_FILES.flatMap(filesOf)

  it('are each guarded by the caller\'s API list or listed with the reason they are not reached', () => {
    const found = {}
    for (const file of files) {
      const unguarded = unguardedCommands(file)
      if (unguarded.length > 0) {
        found[file] = unguarded
      }
    }
    const expected = Object.fromEntries(Object.entries(NOT_REACHED).map(([file, commands]) => [file, Object.keys(commands).sort()]))
    expect(found).toEqual(expected)
  })

  it('are listed only with a reason', () => {
    for (const [file, commands] of Object.entries(NOT_REACHED)) {
      expect(files).toContain(file)
      for (const [command, reason] of Object.entries(commands)) {
        expect(reason.length).toBeGreaterThan(10)
        expect(minimalUser.apis).not.toContain(command)
      }
    }
  })
})
