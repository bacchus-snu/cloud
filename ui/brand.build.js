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

const fs = require('fs')
const path = require('path')
const { assetDir, assetFiles, validateBrand } = require('./brand.schema')

const brandDir = path.resolve(__dirname, '../snucse/branding')

function loadBrand (dir = brandDir) {
  const file = path.join(dir, 'brand.json')
  if (!fs.existsSync(file)) {
    throw new Error(file + ' not found: the brand files live beside the UI in the source tree')
  }
  const brand = validateBrand(JSON.parse(fs.readFileSync(file, 'utf8')))
  for (const name of Object.values(assetFiles)) {
    if (!fs.existsSync(path.join(dir, name))) {
      throw new Error(path.join(dir, name) + ' not found')
    }
  }
  return brand
}

// Wires the brand directory into the webpack chain: the `@brand` alias the bundle imports the
// strings through, the page title, and the copy of the images into the output. The build fails
// when the brand file or one of the images is missing or invalid.
function chainBrand (config, dir = brandDir) {
  const brand = loadBrand(dir)
  config.resolve.alias.set('@brand', dir)
  config.plugin('html').tap(args => {
    args[0].title = brand.title
    return args
  })
  if (!config.plugins.has('copy')) {
    throw new Error('the build has no copy step for the brand images')
  }
  config.plugin('copy').tap(args => {
    args[0].push({ from: dir, to: assetDir, ignore: ['brand.json'] })
    return args
  })
}

module.exports = { brandDir, loadBrand, chainBrand }
