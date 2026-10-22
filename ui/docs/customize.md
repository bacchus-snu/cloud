<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# UI customization
Use a `public/config.json` (or `dist/config.json` after build) file for customizing theme, logos,...

## Brand
The product name, the page title, the two-factor issuer, the origin credit shown in the footer and the images
(`logo.svg`, `mini-logo.svg`, `favicon.svg` and its renderings `favicon.png` and `favicon.ico`) are files in
`snucse/branding/`, not settings of `config.json`. After `favicon.svg` is replaced, `snucse/packaging/make-favicons.sh`
renders the two rasters again.
The build validates `brand.json` (the credit is required in `en` and `ko`), makes the page title from it and copies the
images to `assets/brand/`; the browser bundle reads the same file. A `config.json` that still carries `appTitle`,
`logo`, `minilogo`, `banner` or `favicon` is overridden by the brand files. Locale strings write the product name as
`{product}`.

## Images
Change the image of the error pages.
```json
{
  "error": {
    "404": "assets/404.png",
    "403": "assets/403.png",
    "500": "assets/500.png"
  }
}
```

- `error.404` change the image of error Page not found.
- `error.403` change the image of error Forbidden.
- `error.500` change the image of error Internal Server Error.

## Login page
Sign-in through OpenID providers is offered as one button per enabled provider (`listOidcProviders`), followed by
the links that sign out of each provider that has an end-session address.
```json
{
  "foldPasswordLogin": true
}
```

- `foldPasswordLogin` keeps the username and password form folded behind a link while at least one provider is
  offered (default `true`); with `false` the form is always open. Without providers the form is always open.

## What a role type sees
What a caller may do is decided by the server: menus, tabs, actions, wizard steps and the lists they need appear only
when the caller's API list (`listApis`) holds their APIs. The `hidden` block covers what an API list cannot express; it
hides entries for a role type (`Admin`, `ResourceAdmin`, `DomainAdmin`, `User`) and never removes a route, so a hidden
page still opens from a link.
The default `public/config.json` carries none of these lists; a deployment's own `config.json` supplies them.
```json
{
  "hidden": {
    "User": {
      "sidebar": ["template", "event"],
      "header": [],
      "dashboard": ["project", "template"],
      "deploy": ["imagetype.snapshotid", "keyboard"],
      "actions": ["vm.createSnapshot"],
      "details": ["vm.hypervisor"],
      "columns": ["volume.zonename"],
      "languages": ["ja_JP", "zh_CN"]
    }
  },
  "showGpuSlots": true,
  "showCpuSpeed": false
}
```

- `hidden.<role type>.sidebar` lists menu entries by route name (a section left without entries goes too).
- `header` lists `create`, `links`, `language`, `notices` and the user menu items `profile`, `limits`, `timezone`, `document`.
- `dashboard` lists the user dashboard's cards (`gpuslots`, `leases`, `requests`, `help`, `events`), its counts (`instances`,
  `volumes`, `templates`, …) and its usage rows (`vm`, `cpu`, `memory`, `gpu`, `volume`, `storage`, `project`, `template`, …).
- `deploy` lists deploy wizard items: image types as `imagetype.<templateid|isoid|volumeid|snapshotid>`, `dynamicscalingenabled`
  and `keyboard`.
- `actions` lists actions as `<route name>.<API>`, `details` lists detail fields as `<route name>.<field>`, `columns` lists
  list-view columns as `<route name>.<field>`.
- `languages` lists the locales the language menu leaves out (`ja_JP`, `zh_CN`, …). The sign-in page, where no role type
  is known yet, offers every locale.
- `showGpuSlots` (default `true`) shows the free and total slots of the GPU offerings; `showCpuSpeed` (default `false`)
  shows CPU clock speeds.
- `docBase` is the address the Help menu opens and under which the pages' help links open. A value of
  `docHelpMappings` that is a full address (`https://…`) is opened as it is, so pages can keep links to another site.

## Theme
Customize themes like colors, border color, etc.
```json
{
  "theme": {
    "@primary-color": "#1890ff",
    "@success-color": "#52c41a",
    "@processing-color": "#1890ff",
    "@warning-color": "#faad14",
    "@error-color": "#f5222d",
    "@font-size-base": "14px",
    "@heading-color": "rgba(0, 0, 0, 0.85)",
    "@text-color": "rgba(0, 0, 0, 0.65)",
    "@text-color-secondary": "rgba(0, 0, 0, 0.45)",
    "@disabled-color": "rgba(0, 0, 0, 0.25)",
    "@border-color-base": "#d9d9d9",
    "@logo-width": "256px",
    "@logo-height": "64px",
    "@banner-width": "700px",
    "@banner-height": "110px",
    "@error-width": "256px",
    "@error-height": "256px"
  }
}
```

- `@logo-background-color` changes the logo background color.
- `@project-nav-background-color` changes the navigation menu background color of the project .
- `@project-nav-text-color` changes the navigation menu background color of the project view.
- `@navigation-background-color` changes the navigation menu background color.
- `@navigation-text-color` changes the navigation text color.
- `@primary-color` change the major background color of the page (background button, icon hover, etc).
- `@primary-color-light` changes selected menu item bg-color and hovered table cell bg-color.
- `@dashboard-graph-normal-color` changes the -normal- color of stroke in graphs in the Root admin's dashboard.
- `@dashboard-graph-exception-color` changes the -danger- color of stroke in graphs in the Root admin's dashboard.
- `@dashboard-tile-runningvms-bg` changes bg color of the "Running VMs" tile in the non-Root admin's dashboard.
- `@dashboard-tile-stoppedvms-bg` changes bg color of the "Stopped VMs" tile in the non-Root admin's dashboard.
- `@dashboard-tile-totalvms-bg` changes bg color of the "Total VMs" tile in the non-Root admin's dashboard.
- `@dashboard-tile-totalvolumes-bg` changes bg color of the "Total volumes" tile in the non-Root admin's dashboard.
- `@dashboard-tile-totalnetworks-bg` changes bg color of the "Total networks" tile in the non-Root admin's dashboard.
- `@dashboard-tile-totalips-bg` changes bg color of the "Total IPs" tile in the non-Root admin's dashboard.
- `@link-color` changes the link color.
- `@link-hover-color` changes the link hover color.
- `@loading-color` changes the message loading color and page loading bar at the top page.
- `@success-color` change success state color.
- `@processing-color` change processing state color. Exp: progress status.
- `@warning-color` change warning state color.
- `@error-color` change error state color.
- `@heading-color` change table header color.
- `@text-color` change in major text color.
- `@text-color-secondary` change of secondary text color (breadcrumb icon).
- `@disabled-color` change disable state color (disabled button, switch, etc).
- `@border-color-base` change in major border color.
- `@logo-width` change the width of the logo top-left side.
- `@logo-height` change the height of the logo top-left side.
- `@banner-width` changes the width of the login banner.
- `@banner-height` changes the height of the login banner.
- `@error-width` changes the width of the error image.
- `@error-height` changes the height of the error image.

Assorted primary theme colours:

- Blue: #1890FF
- Red: #F5222D
- Yellow: #FAAD14
- Cyan: #13C2C2
- Green: #52C41A
- Purple: #722ED1

Also, to add other properties, we can add new properties into `theme.config.js` based on the Ant Design Vue Less variable.
Refer: https://www.antdv.com/docs/vue/customize-theme/#Ant-Design-Vue-Less-variables
