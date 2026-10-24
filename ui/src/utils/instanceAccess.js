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

export function sshCommand (access, username, instanceName) {
  if (!access?.sshHost || !username || !instanceName) {
    return null
  }
  const port = access.sshPort ? `-p ${access.sshPort} ` : ''
  return `ssh ${port}${username}:${String(instanceName).toLowerCase()}@${access.sshHost}`
}

// A template belongs to the family named by its name in lower case up to the first dash.
export function loginAccount (access, templateName) {
  const accounts = access?.loginAccounts
  const family = String(templateName || '').toLowerCase().split('-')[0]
  if (!accounts || !family || !Object.prototype.hasOwnProperty.call(accounts, family)) {
    return null
  }
  return accounts[family] || null
}

// What the instance access dialog shows for a newly created instance, or null when there is nothing to show.
export function accessNotice (access, username, vm) {
  const command = sshCommand(access, username, vm?.name)
  if (!command && !vm?.password) {
    return null
  }
  return {
    name: vm.displayname || vm.name || vm.id,
    sshCommand: command,
    account: loginAccount(access, vm.templatename),
    password: vm.password || null
  }
}
