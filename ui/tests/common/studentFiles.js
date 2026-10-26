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

const src = path.join(__dirname, '../../src')

// Every file a student reaches: the pages and dialogs of the kept sidebar entries, the shells and widgets they share, the
// chrome and the session code. A directory means its files, not those below it.
export const STUDENT_FILES = [
  'api/index.js',
  'views/AutogenView.vue',
  'views/compute/InstanceTab.vue',
  'views/compute/SshGuestsTab.vue',
  'views/compute/DeployVM.vue',
  'views/compute/EditVM.vue',
  'views/compute/StartVirtualMachine.vue',
  'views/compute/ReinstallVm.vue',
  'views/compute/ScaleVM.vue',
  'views/compute/DestroyVM.vue',
  'views/compute/CreateSnapshotWizard.vue',
  'views/compute/wizard',
  'views/storage/CreateVolume.vue',
  'views/storage/AttachVolume.vue',
  'views/storage/TakeSnapshot.vue',
  'views/storage/RecurringSnapshotVolume.vue',
  'views/storage/ScheduledSnapshots.vue',
  'views/storage/FormSchedule.vue',
  'views/storage/RenameResource.vue',
  'views/storage/ResizeVolume.vue',
  'views/storage/SnapshotZones.vue',
  'views/dashboard/UsageDashboard.vue',
  'views/dashboard/Dashboard.vue',
  'views/iam/ApiKeyPairPermissionTable.vue',
  'views/image/TemplateZones.vue',
  'views/resourcerequest/MyRequests.vue',
  'views/resourcerequest/RequestForm.vue',
  'views/resourcerequest/RequestTable.vue',
  'views/resourcerequest/RequestValues.vue',
  'views/resourcerequest/EffectiveQuota.vue',
  'components/view/ActionButton.vue',
  'components/view/AnnotationsTab.vue',
  'components/view/ApiKeyPairsTab.vue',
  'components/view/BulkActionProgress.vue',
  'components/view/BulkActionView.vue',
  'components/view/DetailsTab.vue',
  'components/view/EventsTab.vue',
  'components/view/ImageDeployInstanceButton.vue',
  'components/view/InfoCard.vue',
  'components/view/ListResourceTable.vue',
  'components/view/ListView.vue',
  'components/view/ResourceCountUsage.vue',
  'components/view/ResourceLimitTab.vue',
  'components/view/ResourceView.vue',
  'components/view/SearchFilter.vue',
  'components/view/SearchView.vue',
  'components/view/StatsTab.vue',
  'components/view/VolumesTab.vue',
  'components/header',
  'components/page',
  'components/widgets',
  'store/modules/user.js',
  'utils/confirmation.js',
  'utils/deployImages.js',
  'utils/guestList.js',
  'utils/guiTheme.js',
  'utils/instanceAccess.js',
  'utils/instanceName.js',
  'utils/limits.js',
  'utils/links.js',
  'utils/placement.js',
  'utils/plugins.js',
  'utils/resourceLimitRequest.js',
  'utils/resourceName.js',
  'utils/searchLabel.js',
  'utils/vcpuStep.js',
  'utils/zone.js'
]

// The configuration of the sections a student reaches: their action, tab and column labels are read by the student too.
export const STUDENT_CONFIG = [
  'config/section/compute.js',
  'config/section/storage.js',
  'config/section/event.js',
  'config/section/user.js',
  'config/section/account.js',
  'config/section/resourcerequest.js',
  'config/section/zone.js',
  'config/section/keypair.js',
  'config/section/image.js',
  'config/section/offering.js'
]

export const sourceDirectory = src

/** The files of an entry: the file itself, or the files of a directory, not those below it. */
export const filesOf = (entry) => {
  const target = path.join(src, entry)
  if (fs.statSync(target).isDirectory()) {
    return fs.readdirSync(target).filter(name => /\.(vue|js)$/.test(name)).sort().map(name => entry + '/' + name)
  }
  return [entry]
}
