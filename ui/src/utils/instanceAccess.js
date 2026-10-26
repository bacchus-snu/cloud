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

// The bastion target of an instance: the name the server gives for it under the per-account naming scheme, else its name.
export function sshTarget (vm) {
  return vm?.sshname || vm?.name || undefined
}

// `account` is the VM-local account a guest enters as; the owner leaves it out and gets the instance's login account.
export function sshCommand (access, username, instanceName, account) {
  if (!access?.sshHost || !username || !instanceName) {
    return null
  }
  const port = access.sshPort ? `-p ${access.sshPort} ` : ''
  const local = account ? `:${account}` : ''
  return `ssh ${port}${username}:${String(instanceName).toLowerCase()}${local}@${access.sshHost}`
}

export function guestSshCommand (access, vm, account) {
  return account ? sshCommand(access, '<ID>', sshTarget(vm), account) : null
}

// A new instance is named by its visible name alone; the server picks the hostname.
export function instanceNameParams (name) {
  return name ? { displayname: name } : {}
}

export const VISIBLE_NAME_MAX = 30

export function isValidVisibleName (name) {
  return typeof name === 'string' && name.length <= VISIBLE_NAME_MAX && /^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$/.test(name)
}

// The message is a function so that it is read in the language of the moment the name is checked. An empty name passes
// because the name of a new instance is optional.
export function instanceNameRule (message) {
  return {
    validator: async (rule, value) => value && !isValidVisibleName(value) ? Promise.reject(message()) : Promise.resolve()
  }
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
  const command = sshCommand(access, username, sshTarget(vm))
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
