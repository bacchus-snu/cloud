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

// What the build and the browser bundle agree on about the brand files. It must stay free of
// Node.js modules: the bundle imports it.

// where the build puts the brand directory's images in the output
const assetDir = 'assets/brand'

// the images the brand directory holds, by the name the UI uses for them
const assetFiles = {
  logo: 'logo.svg',
  minilogo: 'mini-logo.svg',
  favicon: 'favicon.svg',
  faviconPng: 'favicon.png',
  faviconIco: 'favicon.ico'
}

const isText = (value) => typeof value === 'string' && value.trim().length > 0

// The credit to the project this software is based on may not be dropped, so a brand file
// without it, in either language, is refused.
function validateBrand (data) {
  if (!data || typeof data !== 'object') {
    throw new Error('brand.json: expected an object')
  }
  for (const key of ['name', 'title', 'twoFaIssuer']) {
    if (!isText(data[key])) {
      throw new Error('brand.json: ' + key + ' is required')
    }
  }
  // the name is placed into locale messages, where these characters have a meaning
  if (/['{}\\]/.test(data.name)) {
    throw new Error('brand.json: name must not contain quotes, braces or backslashes')
  }
  const credit = data.credit
  if (!credit || typeof credit !== 'object' || !isText(credit.en) || !isText(credit.ko)) {
    throw new Error('brand.json: credit is required in en and ko')
  }
  return data
}

module.exports = { assetDir, assetFiles, validateBrand }
