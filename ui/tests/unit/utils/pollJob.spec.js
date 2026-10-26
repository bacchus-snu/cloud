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

import { flushPromises } from '@vue/test-utils'
import { message } from 'ant-design-vue'

import { getAPI } from '@/api'
import { pollJobPlugin } from '@/utils/plugins'

jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn(), callAPI: jest.fn() }))
jest.mock('@/store', () => ({ getters: { headerNotices: [], countNotify: 0 }, dispatch: jest.fn(), commit: jest.fn() }))
jest.mock('@/vue-app', () => ({ vueProps: { $localStorage: { set: jest.fn(), get: jest.fn(() => null) }, $config: {} } }))
jest.mock('@/utils/request', () => ({ sourceToken: { isCancel: (e) => !!(e && e.cancelled) } }))

const cancelled = () => Object.assign(new Error('canceled'), { cancelled: true })
const pending = { queryasyncjobresultresponse: { jobstatus: 0 } }
const done = { queryasyncjobresultresponse: { jobstatus: 1, jobresult: {} } }
const failed = { queryasyncjobresultresponse: { jobstatus: 2, jobresult: { errortext: 'refused' } } }

const app = { config: { globalProperties: {} } }
pollJobPlugin.install(app)
const context = {
  $pollJob: app.config.globalProperties.$pollJob,
  $route: { fullPath: '/vm', path: '/vm' },
  $router: { currentRoute: { value: { path: '/vm' } } },
  $store: { getters: { headerNotices: [] }, commit: jest.fn() }
}

const options = (extra = {}) => ({ jobId: 'job-1', title: 'Destroy', loadingMessage: 'Deleting lab', successMessage: 'Deleted lab', ...extra })
const polls = () => getAPI.mock.calls.filter(call => call[0] === 'queryAsyncJobResult').length
const loadingShown = () => document.querySelectorAll('.ant-message .anticon-loading').length
const succeeded = () => document.querySelectorAll('.ant-message-success').length
const POLL_INTERVAL = 3000

describe('following a job', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    jest.useFakeTimers()
    jest.spyOn(console, 'error').mockImplementation(() => {})
    message.destroy()
    document.body.innerHTML = ''
  })

  afterEach(() => {
    jest.useRealTimers()
    jest.restoreAllMocks()
  })

  it('goes on polling after a poll was cancelled, as a job does not belong to a page, and ends with the success message', async () => {
    const successMethod = jest.fn()
    const catchMethod = jest.fn()
    getAPI
      .mockResolvedValueOnce(pending)
      .mockRejectedValueOnce(cancelled())
      .mockResolvedValue(done)
    context.$pollJob(options({ successMethod, catchMethod }))
    await flushPromises()
    expect(polls()).toBe(1)
    expect(loadingShown()).toBe(1)

    // the next poll is the cancelled one; the page was left while it was in flight
    jest.advanceTimersByTime(POLL_INTERVAL - 1)
    await flushPromises()
    expect(polls()).toBe(1)
    jest.advanceTimersByTime(1)
    await flushPromises()
    expect(polls()).toBe(2)
    expect(successMethod).not.toHaveBeenCalled()
    expect(catchMethod).not.toHaveBeenCalled()
    expect(loadingShown()).toBe(1)

    // and the one after it, the same three seconds later, finds the job done
    jest.advanceTimersByTime(POLL_INTERVAL - 1)
    await flushPromises()
    expect(polls()).toBe(2)
    jest.advanceTimersByTime(1)
    await flushPromises()
    expect(polls()).toBe(3)
    expect(successMethod).toHaveBeenCalledTimes(1)
    expect(loadingShown()).toBe(0)
    expect(succeeded()).toBe(1)
    expect(document.body.textContent).toContain('Deleted lab')
  })

  it('goes on polling after several cancelled polls in a row', async () => {
    const successMethod = jest.fn()
    getAPI
      .mockRejectedValueOnce(cancelled())
      .mockRejectedValueOnce(cancelled())
      .mockRejectedValueOnce(cancelled())
      .mockResolvedValue(done)
    context.$pollJob(options({ successMethod }))
    await flushPromises()
    for (let poll = 2; poll <= 4; poll++) {
      jest.advanceTimersByTime(POLL_INTERVAL)
      await flushPromises()
      expect(polls()).toBe(poll)
    }
    expect(successMethod).toHaveBeenCalledTimes(1)
    expect(loadingShown()).toBe(0)
  })

  it('keeps the handling of a real error: it is the end of the poll, with the catch method', async () => {
    const catchMethod = jest.fn()
    getAPI.mockRejectedValue(new Error('network'))
    context.$pollJob(options({ catchMethod, catchMessage: 'could not follow' }))
    await flushPromises()
    expect(catchMethod).toHaveBeenCalledTimes(1)
    jest.advanceTimersByTime(POLL_INTERVAL * 2)
    await flushPromises()
    expect(polls()).toBe(1)
  })

  it('keeps the handling of a failed job', async () => {
    const errorMethod = jest.fn()
    getAPI.mockResolvedValue(failed)
    context.$pollJob(options({ errorMethod }))
    await flushPromises()
    expect(errorMethod).toHaveBeenCalledTimes(1)
    jest.advanceTimersByTime(POLL_INTERVAL * 2)
    await flushPromises()
    expect(polls()).toBe(1)
  })
})
