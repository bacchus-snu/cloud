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

import brandData from '@brand/brand.json'
import { assetDir, assetFiles, validateBrand } from '../../brand.schema'

// The only reader of the brand files: the product name, the page title, the two-factor issuer, the
// origin credit and the image paths come from here, never from a literal in a component.
export const brand = Object.freeze(validateBrand(brandData))

export const brandAssets = Object.freeze({
  logo: assetDir + '/' + assetFiles.logo,
  minilogo: assetDir + '/' + assetFiles.minilogo,
  banner: assetDir + '/' + assetFiles.logo,
  favicon: assetDir + '/' + assetFiles.favicon,
  faviconPng: assetDir + '/' + assetFiles.faviconPng,
  faviconIco: assetDir + '/' + assetFiles.faviconIco
})

// The credit in the language of the page, in English where it has none.
export function brandCredit (locale) {
  const language = String(locale || '').split(/[-_]/)[0]
  return brand.credit[language] || brand.credit.en
}

// The release of the upstream project that the credit names, for links into its documentation;
// null when the credit names none.
export function brandUpstreamVersion () {
  const found = /\d+(?:\.\d+)+/.exec(brand.credit.en)
  return found ? found[0] : null
}

// The key URI an authenticator app reads from a QR code; label and issuer are percent-encoded.
export function totpUri (account, secret) {
  const issuer = encodeURIComponent(brand.twoFaIssuer)
  return 'otpauth://totp/' + issuer + ':' + encodeURIComponent(account) +
    '?secret=' + encodeURIComponent(secret) + '&issuer=' + issuer
}

// The brand owns the identity keys of the runtime configuration, so a config.json that still
// names a title or an image of its own cannot bring back another brand. A theme that an
// administrator stored in the database is applied after this and may still override them.
export function applyBrand (config) {
  config.appTitle = brand.title
  config.logo = brandAssets.logo
  config.minilogo = brandAssets.minilogo
  config.banner = brandAssets.banner
  config.favicon = brandAssets.favicon
  return config
}
