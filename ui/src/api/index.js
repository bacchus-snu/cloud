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

import Cookies from 'js-cookie'
import { axios, sourceToken } from '@/utils/request'
import { message, notification } from 'ant-design-vue'
import { vueProps } from '@/vue-app'
import {
  ACCESS_TOKEN,
  APIS,
  CURRENT_PROJECT,
  DOMAIN_STORE,
  HEADER_NOTICES,
  MS_ID,
  PASSWORD_CHANGE_REQUIRED,
  SHOW_SECURTIY_GROUPS,
  ZONES
} from '@/store/mutation-types'

const getAPICommandsRegex = /^(get|list|query|find)\w+$/i
const additionalGetAPICommandsList = [
  'isaccountallowedtocreateofferingswithtags',
  'readyforshutdown',
  'cloudianisenabled',
  'quotabalance',
  'quotasummary',
  'quotatarifflist',
  'quotaisenabled',
  'quotastatement',
  'quotaemailtemplatelist'
]

// What the UI keeps of one session; none of it may outlive that session.
const SESSION_STORAGE_KEYS = [ACCESS_TOKEN, APIS, ZONES, CURRENT_PROJECT, HEADER_NOTICES, DOMAIN_STORE, MS_ID, PASSWORD_CHANGE_REQUIRED,
  SHOW_SECURTIY_GROUPS]
const SESSION_COOKIE_PATHS = ['/', '/client']

/**
 * The session key to send. A session the server opened itself (single sign-on) reaches the UI only as the readable
 * sessionkey cookie; when it differs from the stored key, it is newer than everything stored, so the stored session's
 * leftovers are dropped and the server's session is kept as the UI's own. A login or logout through the UI removes that
 * cookie first, so the cookie never stands for a session the UI has replaced or ended.
 */
export function sessionKey () {
  const opened = Cookies.get('sessionkey')
  const stored = vueProps.$localStorage.get(ACCESS_TOKEN)
  if (opened && opened !== stored) {
    SESSION_STORAGE_KEYS.forEach(key => vueProps.$localStorage.remove(key))
    vueProps.$localStorage.set(ACCESS_TOKEN, opened, 24 * 60 * 60 * 1000)
    return opened
  }
  return stored || opened
}

export function forgetServerOpenedSession () {
  SESSION_COOKIE_PATHS.forEach(path => Cookies.remove('sessionkey', { path }))
}

export function getAPI (command, args = {}) {
  args.command = command
  args.response = 'json'

  const sessionkey = sessionKey()
  if (sessionkey) {
    args.sessionkey = sessionkey
  }

  return axios({
    params: {
      ...args
    },
    url: '/',
    method: 'GET'
  })
}

export function postAPI (command, data = {}) {
  const params = new URLSearchParams()
  params.append('command', command)
  params.append('response', 'json')
  if (data) {
    Object.entries(data).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        params.append(key, value)
      }
    })
  }

  const sessionkey = sessionKey()
  if (sessionkey) {
    params.append('sessionkey', sessionkey)
  }
  return axios({
    url: '/',
    method: 'POST',
    data: params
  })
}

export function callAPI (command, args = {}) {
  const isGetAPICommand = getAPICommandsRegex.test(command) || additionalGetAPICommandsList.includes(command.toLowerCase())
  const call = isGetAPICommand ? getAPI : postAPI
  return call(command, args)
}

export function login (arg) {
  if (!sourceToken.checkExistSource()) {
    sourceToken.init()
  }

  // Logout before login is called to purge any duplicate sessionkey cookies
  postAPI('logout')
  forgetServerOpenedSession()

  const params = new URLSearchParams()
  params.append('command', 'login')
  params.append('username', arg.username || arg.email)
  params.append('password', arg.password)
  params.append('domain', arg.domain)
  params.append('response', 'json')
  return axios({
    url: '/',
    method: 'POST',
    data: params,
    headers: {
      'content-type': 'application/x-www-form-urlencoded'
    }
  })
}

export async function logout () {
  const result = await postAPI('logout').finally(() => {
    sourceToken.cancel()
    message.destroy()
    notification.destroy()
  })
  return result
}

export function getBaseUrl () {
  return vueProps.axios.defaults.baseURL
}
