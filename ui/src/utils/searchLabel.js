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

const FIELD_LABELS = {
  groupid: 'group',
  hsmprofileid: 'hsm.profile',
  kmskeyid: 'kms.key'
}

// The name filter of the instance pages matches the hostname; the name of an instance is searched with the keyword.
export function searchFieldLabelKey (fieldName, { routeName, annotations } = {}) {
  if (fieldName === 'name' && ['vm', 'vnfapp'].includes(routeName)) {
    return 'label.vmhostname'
  }
  if (fieldName === 'keyword') {
    return annotations ? 'label.annotation' : 'label.name'
  }
  return 'label.' + (FIELD_LABELS[fieldName] || fieldName)
}
