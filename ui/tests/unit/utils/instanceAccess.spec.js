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

import { accessNotice, sshCommand, sshTarget, guestSshCommand, loginAccount, isValidVisibleName, instanceNameParams, instanceNameRule } from '@/utils/instanceAccess'
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

describe('the SSH command of a guest', () => {
  it('adds the VM-local account the guest enters as, and a placeholder for the guest\'s own name', () => {
    expect(sshCommand(access, 'alice', 'my-vm', 'debian')).toBe('ssh -p 2222 alice:my-vm:debian@cloud.snucse.org')
    expect(guestSshCommand(access, { name: 'My-VM' }, 'debian')).toBe('ssh -p 2222 <ID>:my-vm:debian@cloud.snucse.org')
  })

  it('names the instance by its sshname when it has one, in lower case', () => {
    expect(guestSshCommand(access, { name: 'i-2-34-VM', sshname: 'VM-0' }, 'ubuntu')).toBe('ssh -p 2222 <ID>:vm-0:ubuntu@cloud.snucse.org')
  })

  it('is null without a bastion host, an instance name or an account', () => {
    expect(guestSshCommand({}, { name: 'my-vm' }, 'debian')).toBeNull()
    expect(guestSshCommand(access, {}, 'debian')).toBeNull()
    expect(guestSshCommand(access, undefined, 'debian')).toBeNull()
    expect(guestSshCommand(access, { name: 'my-vm' }, '')).toBeNull()
    expect(guestSshCommand(access, { name: 'my-vm' }, undefined)).toBeNull()
  })

  it('leaves the command of the owner without an account part when none is given', () => {
    expect(sshCommand(access, 'alice', 'my-vm', undefined)).toBe('ssh -p 2222 alice:my-vm@cloud.snucse.org')
    expect(sshCommand(access, 'alice', 'my-vm', '')).toBe('ssh -p 2222 alice:my-vm@cloud.snucse.org')
  })
})

describe('the bastion target of an instance', () => {
  it('is its sshname when the server gives one, and its name otherwise', () => {
    expect(sshTarget({ name: 'i-2-34-VM', sshname: 'VM-0' })).toBe('VM-0')
    expect(sshTarget({ name: 'my-vm' })).toBe('my-vm')
    expect(sshTarget({ name: 'my-vm', sshname: '' })).toBe('my-vm')
    expect(sshTarget({ name: 'my-vm', sshname: null })).toBe('my-vm')
  })

  it('is undefined for no instance', () => {
    expect(sshTarget(undefined)).toBeUndefined()
    expect(sshTarget(null)).toBeUndefined()
    expect(sshTarget({})).toBeUndefined()
  })
})

describe('the visible name of an instance', () => {
  it('is 1 to 30 letters, digits and hyphens, with no hyphen at either end', () => {
    for (const ok of ['a', 'A', '7', 'VM-0', 'my-vm', 'a-b-c', 'a--b', 'x'.repeat(30), 'ab', 'Lab1']) {
      expect(isValidVisibleName(ok)).toBe(true)
    }
  })

  it('is at most 30 characters', () => {
    expect(isValidVisibleName('x'.repeat(30))).toBe(true)
    expect(isValidVisibleName('x'.repeat(31))).toBe(false)
    expect(isValidVisibleName('a' + '-'.repeat(28) + 'b')).toBe(true)
    expect(isValidVisibleName('a' + '-'.repeat(29) + 'b')).toBe(false)
  })

  it('has no hyphen first or last', () => {
    for (const bad of ['-', '--', '-a', 'a-', '-a-', '-abc', 'abc-']) {
      expect(isValidVisibleName(bad)).toBe(false)
    }
  })

  it('has nothing else in it: no space, dot, underscore, colon, slash or letter outside ASCII', () => {
    for (const bad of ['my vm', 'my.vm', 'my_vm', 'a:b', 'a/b', 'a@b', '인스턴스', 'café', 'a\nb', ' a', 'a ', 'a\u200b']) {
      expect(isValidVisibleName(bad)).toBe(false)
    }
  })

  it('is not empty, not missing and not text', () => {
    for (const bad of ['', undefined, null, 5, {}]) {
      expect(isValidVisibleName(bad)).toBe(false)
    }
  })
})

describe('the form rule of the visible name', () => {
  const rule = instanceNameRule(() => 'the rule')

  it('lets an empty name pass, for the name is optional', async () => {
    for (const none of [undefined, null, '']) {
      await expect(rule.validator({}, none)).resolves.toBeUndefined()
    }
  })

  it('passes a name that follows the rule and rejects any other with the message it was given', async () => {
    await expect(rule.validator({}, 'VM-0')).resolves.toBeUndefined()
    for (const bad of ['my vm', '-a', 'a-', 'x'.repeat(31), 'a_b']) {
      await expect(rule.validator({}, bad)).rejects.toBe('the rule')
    }
  })

  it('reads the message when it rejects, so that a language chosen later is used', async () => {
    let language = 'en'
    const late = instanceNameRule(() => language)
    language = 'ko'
    await expect(late.validator({}, 'a b')).rejects.toBe('ko')
  })
})

describe('the name that a new instance is asked for', () => {
  it('is the visible name alone, never a hostname, which the server picks', () => {
    expect(instanceNameParams('my-vm')).toEqual({ displayname: 'my-vm' })
    expect(instanceNameParams('my-vm')).not.toHaveProperty('name')
  })

  it('is nothing when no name is given, for the server then picks VM-0, VM-1 and so on', () => {
    for (const none of ['', undefined, null]) {
      expect(instanceNameParams(none)).toEqual({})
    }
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

  it('names the instance by its sshname when it has one', () => {
    const scheme = { ...vm, name: 'i-2-34-VM', displayname: 'VM-0', sshname: 'VM-0' }
    expect(accessNotice(access, 'baneling100', scheme)).toEqual({
      name: 'VM-0', sshCommand: 'ssh -p 2222 baneling100:vm-0@cloud.snucse.org', account: 'debian', password: 'pw'
    })
  })

  it('is null with neither a command nor a password', () => {
    expect(accessNotice({}, 'baneling100', { ...vm, password: undefined })).toBeNull()
    expect(accessNotice(access, 'baneling100', undefined)).toBeNull()
  })
})
