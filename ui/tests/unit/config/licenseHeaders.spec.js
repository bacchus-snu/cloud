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

const uiRoot = path.resolve(__dirname, '../../..')

// The release check (apache-rat-plugin, run from the root pom) reads every file of ui/ but these: dot files and directories,
// the public and legacy trees, the asset directory, the two test-plan templates, and JSON, log and patch files. What it
// reads must open with the license text.
const SKIPPED_DIRECTORIES = ['node_modules', 'dist', 'coverage', 'public', 'legacy']
const SKIPPED_FILES = new Set(['docs/full-test-plan.template.md', 'docs/smoke-test-plan.template.md'])
const SKIPPED_EXTENSIONS = /\.(json|log|patch|png|svg|ico)$/

const filesToCheck = (directory, relative = '') => {
  const found = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const name = relative ? relative + '/' + entry.name : entry.name
    if (entry.name.startsWith('.')) {
      continue
    }
    if (entry.isDirectory()) {
      if (!SKIPPED_DIRECTORIES.includes(entry.name) && name !== 'src/assets') {
        found.push(...filesToCheck(path.join(directory, entry.name), name))
      }
    } else if (!SKIPPED_FILES.has(name) && !SKIPPED_EXTENSIONS.test(entry.name) && entry.name !== 'LICENSE') {
      found.push(name)
    }
  }
  return found
}

describe('the license header of the files of ui/', () => {
  it('opens every file that the release check reads', () => {
    const files = filesToCheck(uiRoot)
    expect(files.length).toBeGreaterThan(300)
    const without = files.filter(file => !fs.readFileSync(path.join(uiRoot, file), 'utf8').slice(0, 1500).includes('Licensed to the Apache Software Foundation (ASF) under one'))
    expect(without).toEqual([])
  })
})
