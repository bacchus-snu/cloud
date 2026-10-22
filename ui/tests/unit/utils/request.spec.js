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

import notification from 'ant-design-vue/es/notification'

import store from '@/store'
import { axios as service } from '@/utils/request'

jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { get: () => null } } }))
jest.mock('@/router', () => ({
  __esModule: true,
  default: { currentRoute: { value: { path: '/user/login' } }, push: jest.fn() }
}))
jest.mock('@/store', () => ({
  __esModule: true,
  default: { getters: { countNotify: 0 }, commit: jest.fn(), dispatch: jest.fn(() => Promise.resolve()) }
}))
jest.mock('@/locales', () => ({ i18n: { global: { t: (key) => key } } }))
jest.mock('ant-design-vue/es/notification', () => ({
  __esModule: true,
  default: { error: jest.fn(), warn: jest.fn() }
}))

// answers every request as the API server answers an unauthenticated one
const unauthorized = (config) => {
  const command = config.params ? config.params.command : new URLSearchParams(config.data).get('command')
  const error = new Error('Request failed with status code 401')
  error.isAxiosError = true
  error.config = config
  error.response = {
    status: 401,
    config,
    data: { [`${command.toLowerCase()}response`]: { errorcode: 401, errortext: 'unable to verify user credentials and/or request signature' } }
  }
  return Promise.reject(error)
}

const post = (command) => {
  const data = new URLSearchParams()
  data.append('command', command)
  data.append('response', 'json')
  return service({ url: '/', method: 'POST', data, adapter: unauthorized }).catch(() => {})
}

const get = (command) => service({ url: '/', method: 'GET', params: { command, response: 'json' }, adapter: unauthorized }).catch(() => {})

describe('utils/request', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  describe('an unauthorized answer to a command the login page sends before sign-in', () => {
    it('neither signs out nor reports when the command was posted', async () => {
      await post('forgotPassword')

      expect(store.dispatch).not.toHaveBeenCalledWith('Logout')
      expect(notification.error).not.toHaveBeenCalled()
    })

    it('neither signs out nor reports when the command was sent by GET', async () => {
      await get('listIdps')

      expect(store.dispatch).not.toHaveBeenCalledWith('Logout')
      expect(notification.error).not.toHaveBeenCalled()
    })
  })

  describe('an unauthorized answer to any other command', () => {
    it('signs out and reports, whichever way the command was sent', async () => {
      await post('deployVirtualMachine')
      await get('listVirtualMachines')

      expect(store.dispatch.mock.calls.filter(([action]) => action === 'Logout')).toHaveLength(2)
      expect(notification.error).toHaveBeenCalledTimes(2)
    })
  })
})
