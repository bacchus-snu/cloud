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

import { accessNotice, sshCommand, loginAccount } from '@/utils/instanceAccess'
import deployment from '../../mockData/DeploymentConfig.mock.json'

const access = deployment.instanceAccess

describe('the SSH command to an instance', () => {
  it('goes through the bastion on its port as <username>:<instance name>@<host>', () => {
    expect(sshCommand(access, 'baneling100', 'my-vm')).toBe('ssh -p 2222 baneling100:my-vm@cloud.snucse.org')
    expect(sshCommand({ sshHost: 'bastion.example', sshPort: 22022 }, 'alice', 'web')).toBe('ssh -p 22022 alice:web@bastion.example')
  })

  it('names the instance in lower case and keeps the username as it is', () => {
    expect(sshCommand(access, 'Student.1@bacchus.snucse.org', 'My-VM')).toBe('ssh -p 2222 Student.1@bacchus.snucse.org:my-vm@cloud.snucse.org')
  })

  it('leaves out the port when none is configured, for ssh then takes its own default', () => {
    expect(sshCommand({ sshHost: 'cloud.snucse.org' }, 'baneling100', 'my-vm')).toBe('ssh baneling100:my-vm@cloud.snucse.org')
  })

  it('is null when the host, the username or the instance name is missing', () => {
    expect(sshCommand(undefined, 'baneling100', 'my-vm')).toBeNull()
    expect(sshCommand(null, 'baneling100', 'my-vm')).toBeNull()
    expect(sshCommand({}, 'baneling100', 'my-vm')).toBeNull()
    expect(sshCommand({ sshHost: '', sshPort: 2222 }, 'baneling100', 'my-vm')).toBeNull()
    expect(sshCommand(access, undefined, 'my-vm')).toBeNull()
    expect(sshCommand(access, '', 'my-vm')).toBeNull()
    expect(sshCommand(access, 'baneling100', undefined)).toBeNull()
    expect(sshCommand(access, 'baneling100', '')).toBeNull()
  })
})

describe('the login account of an instance', () => {
  it('is the account mapped to the template family: the template name in lower case up to its first dash', () => {
    expect(loginAccount(access, 'debian-13-20260914')).toBe('debian')
    expect(loginAccount(access, 'Ubuntu-24.04-20260901')).toBe('ubuntu')
    expect(loginAccount(access, 'rocky')).toBe('rocky')
    expect(loginAccount(access, 'ALPINE-3.22')).toBe('alpine')
    expect(loginAccount({ loginAccounts: { debian: 'admin' } }, 'debian-13')).toBe('admin')
  })

  it('is null for a template outside the map, and without a map or a template name', () => {
    expect(loginAccount(access, 'windows-2025-20260914')).toBeNull()
    expect(loginAccount(access, '-13')).toBeNull()
    expect(loginAccount(access, 'constructor-1')).toBeNull()
    expect(loginAccount(access, '')).toBeNull()
    expect(loginAccount(access, undefined)).toBeNull()
    expect(loginAccount({}, 'debian-13')).toBeNull()
    expect(loginAccount(undefined, 'debian-13')).toBeNull()
    expect(loginAccount({ loginAccounts: { debian: '' } }, 'debian-13')).toBeNull()
  })
})

describe('the access notice of a new instance', () => {
  const vm = { id: 'f00d', name: 'My-VM', displayname: 'my vm', templatename: 'debian-13-20260914', password: 'pw' }

  it('carries the command, the login account and the password', () => {
    expect(accessNotice(access, 'baneling100', vm)).toEqual({
      name: 'my vm', sshCommand: 'ssh -p 2222 baneling100:my-vm@cloud.snucse.org', account: 'debian', password: 'pw'
    })
  })

  it('still shows a password where no bastion is configured', () => {
    expect(accessNotice({}, 'baneling100', vm)).toEqual({ name: 'my vm', sshCommand: null, account: null, password: 'pw' })
  })

  it('is null with neither a command nor a password', () => {
    expect(accessNotice({}, 'baneling100', { ...vm, password: undefined })).toBeNull()
    expect(accessNotice(access, 'baneling100', undefined)).toBeNull()
  })
})
