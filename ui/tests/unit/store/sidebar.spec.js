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

import store from '@/store'
import { vueProps } from '@/vue-app'
import { asyncRouterMap } from '@/config/router'
import { filterAsyncRouter, visibleMenu } from '@/store/modules/permission'
import { hiddenKeys } from '@/utils/uiConfig'
import catalog from '../../mockData/ApiCatalog.mock.json'
import minimalUser from '../../mockData/MinimalUserApis.mock.json'

jest.mock('@/store', () => ({
  getters: { features: {}, apis: {}, userInfo: {}, zones: [], project: {}, metrics: false, listAllProjects: false }
}))
jest.mock('@/vue-app', () => ({
  vueProps: { $config: {}, $applyDocHelpMappings: (docHelp) => docHelp, $localStorage: { get: () => null, set: () => {} } }
}))
// the route tree only: the pages and layouts themselves are not needed
jest.mock('@/router', () => ({ addRoute: jest.fn(), currentRoute: { value: {} } }))
jest.mock('@/layouts', () => ({ UserLayout: {}, BasicLayout: {}, RouteView: {} }))
jest.mock('@/views/AutogenView.vue', () => ({}))
jest.mock('@/views/plugins/IFramePlugin.vue', () => ({}))
jest.mock('@/views/plugins/ApiDocsPlugin.vue', () => ({}))

const uiRoot = path.resolve(__dirname, '../../..')
const shippedConfig = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/config.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(uiRoot, 'public/locales/ko_KR.json'), 'utf8'))

const apiMap = (names) => Object.fromEntries(names.map(name => [name, {}]))

// the menu a caller sees: routes the API list allows, less what the config hides for the caller's role type
const menuFor = ({ apis, roletype, rolename = 'Some role', config = shippedConfig, features = {} }) => {
  vueProps.$config = config
  store.getters.apis = apiMap(apis)
  store.getters.userInfo = { roletype, rolename }
  store.getters.features = features
  const root = filterAsyncRouter(asyncRouterMap(), Object.keys(store.getters.apis)).find(route => route.path === '/')
  return visibleMenu(root.children, hiddenKeys('sidebar', roletype, config))
}

const entries = (menu) => {
  const leaves = []
  const sections = []
  for (const item of menu) {
    if (item.children && !item.hideChildrenInMenu) {
      sections.push(item.name)
      leaves.push(...item.children.map(child => child.name))
    } else {
      leaves.push(item.name)
    }
  }
  return { leaves, sections }
}

const withHidden = (sidebar) => ({ ...shippedConfig, hidden: { User: { sidebar } } })
const student = { apis: minimalUser.apis, roletype: 'User', features: { resourcelimitrequestallowed: true } }

describe('the central role type filter', () => {
  it('roletype filter hides admin-only menus for User, never by role name', () => {
    const admin = entries(menuFor({ apis: catalog.apis, roletype: 'Admin', rolename: 'User' }))
    expect(admin.leaves).toEqual(expect.arrayContaining(['vm', 'volume', 'host', 'computeoffering', 'role', 'domain']))

    const user = entries(menuFor({ ...student, rolename: 'Root Admin' }))
    for (const adminOnly of ['host', 'cluster', 'pod', 'computeoffering', 'role', 'domain', 'globalsetting', 'infra', 'offering']) {
      expect(user.leaves).not.toContain(adminOnly)
      expect(user.sections).not.toContain(adminOnly)
    }
    // the role name plays no part: the same role type and API list give the same menu
    expect(entries(menuFor({ ...student, rolename: 'Another role' }))).toEqual(user)

    for (const file of ['src/store/modules/permission.js', 'src/utils/uiConfig.js', 'src/components/menu/SMenu.vue']) {
      const text = fs.readFileSync(path.join(uiRoot, file), 'utf8')
      expect(text).not.toMatch(/rolename|accounttype|'User'|"User"|'Admin'|"Admin"/)
    }
  })

  it('the hidden lists come from config.json', () => {
    const none = entries(menuFor({ ...student, config: withHidden([]) }))
    const apiOnly = entries(visibleMenu(
      filterAsyncRouter(asyncRouterMap(), minimalUser.apis).find(route => route.path === '/').children, []))
    expect(none).toEqual(apiOnly)
    expect(none.leaves).toContain('event')

    const eventsHidden = entries(menuFor({ ...student, config: withHidden(['event']) }))
    expect(eventsHidden.leaves).not.toContain('event')
    expect(eventsHidden.leaves).toEqual(none.leaves.filter(name => name !== 'event'))

    // another role type's block does not apply, and a missing block hides nothing
    expect(entries(menuFor({ ...student, config: { ...shippedConfig, hidden: { Admin: { sidebar: ['vm'] } } } }))).toEqual(none)
    expect(entries(menuFor({ ...student, config: { ...shippedConfig, hidden: undefined } }))).toEqual(none)

    // hiding every child of a section hides the section, and the routes stay
    const routes = filterAsyncRouter(asyncRouterMap(), minimalUser.apis).find(route => route.path === '/').children
    const storageHidden = entries(visibleMenu(routes, ['volume', 'snapshot', 'snapshotpolicy']))
    expect(storageHidden.sections).not.toContain('storage')
    expect(routes.find(route => route.name === 'storage').children.map(child => child.name)).toEqual(
      expect.arrayContaining(['volume', 'snapshot']))
  })

  it('sidebar for the minimal user role shows only Dashboard/Instances/Volumes/Snapshots/쿼터 신청', () => {
    const menu = menuFor(student)
    const { leaves, sections } = entries(menu)
    expect(leaves).toEqual(['dashboard', 'vm', 'volume', 'snapshot', 'resourcelimitrequest'])
    expect(sections).toEqual(['compute', 'storage', 'resourcerequests'])
    expect(ko[menu.find(item => item.name === 'resourcerequests').meta.title]).toBe('쿼터 신청')

    // the hidden pages keep their routes: the limits page and the events of the dashboard still open
    const routes = filterAsyncRouter(asyncRouterMap(), minimalUser.apis).find(route => route.path === '/').children
    for (const kept of ['account', 'event', 'zones', 'accountuser']) {
      expect(routes.map(route => route.name)).toContain(kept)
    }
  })
})
