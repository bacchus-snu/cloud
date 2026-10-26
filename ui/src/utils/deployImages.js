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

// Only the snapshot of a disk made from a template can boot an Instance, and only once it is backed up.
export function canDeployFromSnapshot (snapshot) {
  return !!snapshot && snapshot.state === 'BackedUp' && snapshot.volumetype === 'ROOT'
}

export function canDeployFromVolume (volume) {
  return !!volume && volume.state === 'Ready' && !volume.virtualmachineid && !!volume.templateid
}

export function deployableSnapshots (snapshots) {
  return (snapshots || []).filter(canDeployFromSnapshot)
}

export function deployableVolumes (volumes) {
  return (volumes || []).filter(canDeployFromVolume)
}
