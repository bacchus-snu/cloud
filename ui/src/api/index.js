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
import store from '@/store'
import { i18n } from '@/locales'
import {
  ACCESS_TOKEN,
  APIS,
  CURRENT_PROJECT,
  CUSTOM_COLUMNS,
  DARK_MODE,
  DOMAIN_STORE,
  HEADER_NOTICES,
  MS_ID,
  PASSWORD_CHANGE_REQUIRED,
  SESSION_USER,
  SHOW_SECURTIY_GROUPS,
  TIMEZONE_OFFSET,
  USE_BROWSER_TIMEZONE,
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
// What a user chose for themself belongs to the user, not to one session of the user.
const USER_PREFERENCE_KEYS = [TIMEZONE_OFFSET, USE_BROWSER_TIMEZONE, CUSTOM_COLUMNS, DARK_MODE]
const SESSION_COOKIE_PATHS = ['/', '/client']

// The key of the session whose state this page holds in memory, and whether the page is giving way to another session.
let pageKey = null
let restarting = false

// What the page keeps in memory of a signed-in user: an API list, or the token, forced password change or second factor
// that precede it.
function runsSession () {
  const getters = (store && store.getters) || {}
  return Object.keys(getters.apis || {}).length > 0 || !!getters.token || !!getters.passwordChangeRequired ||
    !!getters.twoFaEnabled || !!getters.twoFaProvider
}

function restartPage () {
  restarting = true
  window.location.replace(window.location.pathname + window.location.search)
}

const sessionReplacedError = () => {
  const text = i18n.global.t('message.session.replaced')
  return Object.assign(new Error(text), { sessionReplaced: true, response: { data: text } })
}

/**
 * A session of the given user starts (a sign-in through the server, or a login through the UI). The preferences of the
 * user it replaces stay when it is the same user and go when it is another's, when the earlier user is unknown or when
 * the new one is; the user of the latest session is remembered.
 */
export function startSessionOfUser (userid) {
  const sameUser = !!userid && userid === vueProps.$localStorage.get(SESSION_USER)
  if (!sameUser) {
    USER_PREFERENCE_KEYS.forEach(name => vueProps.$localStorage.remove(name))
  }
  if (userid) {
    vueProps.$localStorage.set(SESSION_USER, userid)
  } else {
    vueProps.$localStorage.remove(SESSION_USER)
  }
}

/** The session key a login through the UI has just stored becomes the key of the session this page runs. */
export function adoptSessionKey (key) {
  pageKey = key || null
}

/**
 * The session key to send. A session the server opened itself (single sign-on) reaches the UI only as the readable
 * sessionkey cookie; when it differs from the stored key, it is newer than everything stored, so the stored session's
 * leftovers are dropped and the server's session is kept as the UI's own, its user being the one the userid cookie names.
 * A page that runs a session holds that session's user, menus and API list in memory, which no stored key replaces:
 * when the key in force is no longer the one the page runs (the server opened another session, or a login in another
 * tab stored its own), the page starts over from the entry address, and from then on sends nothing, so that nothing of
 * the earlier user acts with the new key. A login or logout through the UI removes the cookie first, so the cookie never
 * stands for a session the UI has replaced or ended.
 */
export function sessionKey () {
  const opened = Cookies.get('sessionkey')
  const stored = vueProps.$localStorage.get(ACCESS_TOKEN)
  let key = stored || opened
  if (opened && opened !== stored) {
    SESSION_STORAGE_KEYS.forEach(name => vueProps.$localStorage.remove(name))
    startSessionOfUser(Cookies.get('userid'))
    vueProps.$localStorage.set(ACCESS_TOKEN, opened, 24 * 60 * 60 * 1000)
    key = opened
  }
  if (!runsSession() || pageKey === null) {
    pageKey = key || null
  } else if (key && key !== pageKey && !restarting) {
    restartPage()
  }
  return key
}

export function forgetServerOpenedSession () {
  SESSION_COOKIE_PATHS.forEach(path => Cookies.remove('sessionkey', { path }))
}

export function getAPI (command, args = {}) {
  args.command = command
  args.response = 'json'

  const sessionkey = sessionKey()
  if (restarting) {
    return Promise.reject(sessionReplacedError())
  }
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
  if (restarting) {
    return Promise.reject(sessionReplacedError())
  }
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
