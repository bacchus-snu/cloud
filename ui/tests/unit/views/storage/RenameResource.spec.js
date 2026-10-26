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
import { flushPromises } from '@vue/test-utils'

import common from '../../../common'
import { postAPI } from '@/api'
import RenameResource from '@/views/storage/RenameResource'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { features: {}, apis: {}, userInfo: { roletype: 'User' }, zones: [] } }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))

const en = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/en.json'), 'utf8'))
const ko = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../public/locales/ko_KR.json'), 'utf8'))

const volume = { id: 'v-1', name: 'data' }
const snapshot = { id: 's-1', name: 'data-snapshot-0' }

const open = async ({ resource = volume, api = 'updateVolume', locale = 'en' } = {}) => {
  const apis = { [api]: { params: [{ name: 'id' }, { name: 'name' }] } }
  const $pollJob = jest.fn()
  const $message = { success: jest.fn(), error: jest.fn() }
  const $notifyError = jest.fn()
  const wrapper = common.createFactory(RenameResource, {
    store: common.createMockStore({ user: { apis, info: { roletype: 'User' }, features: {} } }),
    i18n: common.createMockI18n(locale, { en, ko_KR: ko }),
    props: { resource, action: { currentAction: { api } } },
    mocks: { $pollJob, $message, $notifyError }
  })
  wrapper.$pollJob = $pollJob
  wrapper.$message = $message
  wrapper.$notifyError = $notifyError
  await flushPromises()
  return wrapper
}

const input = (wrapper) => wrapper.find('input')
const ok = (wrapper) => wrapper.find('.action-button .ant-btn-primary')
const rename = async (wrapper, name) => {
  await input(wrapper).setValue(name)
  await ok(wrapper).trigger('click')
  await flushPromises()
  await flushPromises()
}
const errors = (wrapper) => wrapper.findAll('.ant-form-item-explain-error').map(node => node.text())

describe('RenameResource', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    postAPI.mockImplementation(() => Promise.resolve({ updatevolumeresponse: { jobid: 'job-1' } }))
  })

  it('has the name of the volume as its one field, filled in', async () => {
    const wrapper = await open()
    expect(wrapper.findAll('input')).toHaveLength(1)
    expect(wrapper.findAll('.ant-form-item')).toHaveLength(1)
    expect(input(wrapper).element.value).toBe('data')
  })

  it('renames a volume with updateVolume, the id and the name alone', async () => {
    const wrapper = await open()
    await rename(wrapper, 'data-2.v1_x')
    expect(postAPI).toHaveBeenCalledTimes(1)
    expect(postAPI).toHaveBeenCalledWith('updateVolume', { id: 'v-1', name: 'data-2.v1_x' })
  })

  it('renames a snapshot with updateSnapshot, the id and the name alone', async () => {
    postAPI.mockImplementation(() => Promise.resolve({ updatesnapshotresponse: { jobid: 'job-2' } }))
    const wrapper = await open({ resource: snapshot, api: 'updateSnapshot' })
    expect(input(wrapper).element.value).toBe('data-snapshot-0')
    await rename(wrapper, 'before-upgrade')
    expect(postAPI).toHaveBeenCalledWith('updateSnapshot', { id: 's-1', name: 'before-upgrade' })
  })

  it('follows the job when the server answers with one, and refreshes and closes', async () => {
    const wrapper = await open()
    await rename(wrapper, 'other')
    expect(wrapper.$pollJob).toHaveBeenCalledTimes(1)
    expect(wrapper.$pollJob.mock.calls[0][0]).toMatchObject({ jobId: 'job-1' })
    expect(wrapper.emitted('close-action')).toBeTruthy()
    expect(wrapper.emitted('refresh-data')).toBeTruthy()
  })

  it('takes an answer that is the result at once', async () => {
    postAPI.mockImplementation(() => Promise.resolve({ updatesnapshotresponse: { snapshot: { id: 's-1', name: 'other' } } }))
    const wrapper = await open({ resource: snapshot, api: 'updateSnapshot' })
    await rename(wrapper, 'other')
    expect(wrapper.$pollJob).not.toHaveBeenCalled()
    expect(wrapper.$message.success).toHaveBeenCalled()
    expect(wrapper.emitted('close-action')).toBeTruthy()
    expect(wrapper.emitted('refresh-data')).toBeTruthy()
  })

  it('sends nothing for a name outside [A-Za-z0-9._-] or outside 1 to 64 characters, and says the rule', async () => {
    const wrapper = await open()
    for (const bad of ['my volume', 'data/disk', '데이터', '', 'a'.repeat(65)]) {
      await rename(wrapper, bad)
      expect(postAPI).not.toHaveBeenCalled()
      expect(errors(wrapper)).toContain(en['message.error.resource.name.rule'])
    }
    await rename(wrapper, 'a'.repeat(64))
    expect(postAPI).toHaveBeenCalledTimes(1)
  })

  it('says the rule in Korean', async () => {
    const wrapper = await open({ locale: 'ko_KR' })
    await rename(wrapper, 'my volume')
    expect(errors(wrapper)).toContain(ko['message.error.resource.name.rule'])
    expect(ko['message.error.resource.name.rule']).not.toBe(en['message.error.resource.name.rule'])
  })

  it('shows the error of the server and stays open', async () => {
    const failure = Object.assign(new Error('refused'), { response: { status: 530, data: {} } })
    postAPI.mockImplementation(() => Promise.reject(failure))
    const wrapper = await open()
    await rename(wrapper, 'other')
    expect(wrapper.$notifyError).toHaveBeenCalledWith(failure)
    expect(wrapper.emitted('close-action')).toBeFalsy()
  })

  it('is called "이름 바꾸기" in Korean and "Rename" in English', () => {
    expect(en['label.rename']).toBe('Rename')
    expect(ko['label.rename']).toBe('이름 바꾸기')
  })
})
