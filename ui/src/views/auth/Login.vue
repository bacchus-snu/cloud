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

<template>
  <a-form
    id="formLogin"
    class="user-layout-login"
    :ref="formRef"
    :model="form"
    :rules="rules"
    @finish="handleSubmit"
  >
    <a-alert
      v-if="oidcError"
      class="oidc-error"
      type="error"
      show-icon
      :message="oidcErrorMessage">
      <template v-if="oidcRefusal" #description>
        <div class="oidc-refusal" v-html="oidcRefusal"></div>
      </template>
    </a-alert>
    <div v-if="oidcProviders.length > 0" class="oidc-providers">
      <a-button
        v-for="(provider, index) in oidcProviders"
        :key="provider.name"
        :type="index === 0 ? 'primary' : 'default'"
        size="large"
        block
        class="oidc-login-button"
        :href="oidcLoginUrl(provider)"
      >{{ $t('label.login.with.provider', { provider: provider.label }) }}</a-button>
    </div>
    <div v-if="passwordFolded" class="password-login-toggle">
      <a-button type="link" size="small" @click="passwordFormOpen = !passwordFormOpen">{{ $t('label.login.password.form') }}</a-button>
    </div>
    <div v-if="showPasswordForm" class="password-login" v-ctrl-enter="handleSubmit">
      <a-tabs
        class="tab-center"
        :key="$i18n.locale"
        :activeKey="customActiveKey"
        size="large"
        :tabBarStyle="{ textAlign: 'center', borderBottom: 'unset' }"
        @change="handleTabClick"
        :animated="false"
      >
        <a-tab-pane key="cs">
          <template #tab>
            <span>
              <safety-outlined />
              {{ $t('label.login.portal') }}
            </span>
          </template>
          <a-form-item v-if="$config.multipleServer" name="server" ref="server">
            <a-select
              size="large"
              :placeholder="$t('server')"
              v-model:value="form.server"
              @change="onChangeServer"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }">
              <a-select-option v-for="item in $config.servers" :key="(item.apiHost || '') + item.apiBase" :label="item.name">
                <template #prefix>
                  <database-outlined />
                </template>
                {{ item.name }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item ref="username" name="username">
            <a-input
              size="large"
              type="text"
              v-focus="true"
              :placeholder="$t('label.username')"
              v-model:value="form.username"
            >
              <template #prefix>
                <user-outlined />
              </template>
            </a-input>
          </a-form-item>
          <a-form-item ref="password" name="password">
            <a-input-password
              size="large"
              type="password"
              autocomplete="false"
              :placeholder="$t('label.password')"
              v-model:value="form.password"
            >
              <template #prefix>
                <lock-outlined />
              </template>
            </a-input-password>
          </a-form-item>
          <a-form-item ref="domain" name="domain">
            <a-input
              size="large"
              type="text"
              :placeholder="$t('label.domain')"
              v-model:value="form.domain"
            >
              <template #prefix>
                <project-outlined />
              </template>
            </a-input>
          </a-form-item>
          <a-form-item ref="project" name="project" v-if="$config.displayProjectFieldOnLogin">
            <a-input
              size="large"
              type="text"
              :placeholder="$t('label.project')"
              v-model:value="form.project"
            >
              <template #prefix>
                <block-outlined />
              </template>
            </a-input>
          </a-form-item>
        </a-tab-pane>
        <a-tab-pane key="saml" :disabled="idps.length === 0">
          <template #tab>
            <span>
              <audit-outlined />
              {{ $t('label.login.single.signon') }}
            </span>
          </template>
          <a-form-item v-if="$config.multipleServer" name="server" ref="server">
            <a-select
              size="large"
              :placeholder="$t('server')"
              v-model:value="form.server"
              @change="onChangeServer"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option v-for="item in $config.servers" :key="(item.apiHost || '') + item.apiBase" :label="item.name">
                <template #prefix>
                  <database-outlined />
                </template>
                {{ item.name }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="idp" ref="idp">
            <a-select
              v-model:value="form.idp"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option v-for="(idp, idx) in idps" :key="idx" :value="idp.id" :label="idp.orgName">
                {{ idp.orgName }}
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-tab-pane>
      </a-tabs>

      <a-form-item>
        <a-button
          size="large"
          type="primary"
          html-type="submit"
          class="login-button"
          :loading="state.loginBtn"
          :disabled="state.loginBtn"
          ref="submit"
          @click="handleSubmit"
        >{{ $t('label.login') }}</a-button>
      </a-form-item>
    </div>
    <div v-if="endSessionProviders.length > 0" class="oidc-end-session">
      <div>{{ $t('message.oidc.end.session') }}</div>
      <a v-for="provider in endSessionProviders" :key="provider.name" :href="provider.endsessionurl">{{ provider.endsessionlabel }}</a>
    </div>
    <a-row justify="space-between">
      <a-col>
      <translation-menu/>
      </a-col>
      <a-col v-if="forgotPasswordEnabled">
        <router-link :to="{ name: 'forgotPassword' }">
          {{ $t('label.forgot.password') }}
        </router-link>
      </a-col>
    </a-row>
  </a-form>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import store from '@/store'
import { mapActions } from 'vuex'
import { sourceToken } from '@/utils/request'
import { SERVER_MANAGER } from '@/store/mutation-types'
import TranslationMenu from '@/components/header/TranslationMenu'

// Why a sign-in through an OpenID provider ended without a session; the server returns the code as oidcerror.
const OIDC_ERRORS = ['unavailable', 'expired', 'denied', 'failed', 'identity', 'notadmitted', 'noaccount', 'conflict', 'disabled', 'refused']

export default {
  components: {
    TranslationMenu
  },
  data () {
    return {
      idps: [],
      oidcProviders: [],
      oidcError: null,
      passwordFormOpen: false,
      customActiveKey: 'cs',
      loginBtn: false,
      loginType: 0,
      state: {
        time: 60,
        loginBtn: false,
        loginType: 0
      },
      server: '',
      forgotPasswordEnabled: false,
      project: null
    }
  },
  computed: {
    passwordFolded () {
      return this.oidcProviders.length > 0 && this.$config.foldPasswordLogin !== false
    },
    showPasswordForm () {
      return !this.passwordFolded || this.passwordFormOpen
    },
    endSessionProviders () {
      return this.oidcProviders.filter(provider => provider.endsessionurl && provider.endsessionlabel)
    },
    oidcErrorMessage () {
      if (!this.oidcError) {
        return ''
      }
      return this.$t(OIDC_ERRORS.includes(this.oidcError.code) ? 'message.oidc.error.' + this.oidcError.code : 'message.oidc.error')
    },
    oidcRefusal () {
      if (!this.oidcError || this.oidcError.code !== 'notadmitted') {
        return ''
      }
      const provider = this.oidcProviders.find(provider => provider.name === this.oidcError.provider)
      return provider?.refusal || ''
    }
  },
  created () {
    if (this.$config.multipleServer) {
      this.server = this.$localStorage.get(SERVER_MANAGER) || this.$config.servers[0]
    }
    this.initForm()
    this.readOidcError()
    if (store.getters.logoutFlag) {
      if (store.getters.readyForShutdownPollingJob !== '' || store.getters.readyForShutdownPollingJob !== undefined) {
        clearInterval(store.getters.readyForShutdownPollingJob)
      }
      sourceToken.init()
      this.fetchData()
    } else {
      this.fetchData()
    }
  },
  methods: {
    ...mapActions(['Login', 'Logout']),
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        server: (this.server.apiHost || '') + this.server.apiBase,
        username: this.$route.query?.username || '',
        domain: this.$route.query?.domain || '',
        project: null
      })
      this.rules = reactive({})
      this.setRules()
    },
    setRules () {
      if (this.customActiveKey === 'cs') {
        this.rules.username = [
          {
            required: true,
            message: this.$t('message.error.username'),
            trigger: 'change'
          },
          {
            validator: this.handleUsernameOrEmail,
            trigger: 'change'
          }
        ]
        this.rules.password = [
          {
            required: true,
            message: this.$t('message.error.password'),
            trigger: 'change'
          }
        ]
      } else {
        this.rules.username = []
        this.rules.password = []
      }
    },
    // The outcome of a sign-in that ended without a session is read once and removed from the address.
    readOidcError () {
      const url = new URL(window.location.href)
      const code = url.searchParams.get('oidcerror')
      if (!code) {
        return
      }
      this.oidcError = { code, provider: url.searchParams.get('provider') }
      url.searchParams.delete('oidcerror')
      url.searchParams.delete('provider')
      window.history.replaceState(window.history.state, '', url.toString())
    },
    fetchData () {
      getAPI('listIdps').then(response => {
        if (response) {
          this.idps = response.listidpsresponse.idp || []
          this.idps.sort(function (a, b) {
            if (a.orgName < b.orgName) { return -1 }
            if (a.orgName > b.orgName) { return 1 }
            return 0
          })
          this.form.idp = this.idps[0]?.id || ''
        }
      }).catch(() => {})
      getAPI('listOidcProviders').then(response => {
        // an administrator's session also lists the disabled ones
        this.oidcProviders = (response?.listoidcprovidersresponse?.oidcprovider || []).filter(provider => provider.enabled !== false)
      }).catch(() => {
        this.oidcProviders = []
      })
      postAPI('forgotPassword', {}).then(response => {
        this.forgotPasswordEnabled = response.forgotpasswordresponse.enabled
      }).catch((err) => {
        if (err?.response?.data === null) {
          this.forgotPasswordEnabled = true
        } else {
          this.forgotPasswordEnabled = false
        }
      })
    },
    oidcLoginUrl (provider) {
      const apiBase = this.$config.multipleServer ? (this.server.apiHost || '') + this.server.apiBase : this.$config.apiBase
      return apiBase + '?command=oidcLogin&provider=' + encodeURIComponent(provider.name)
    },
    // handler
    async handleUsernameOrEmail (rule, value) {
      const { state } = this
      const regex = /^([a-zA-Z0-9_-])+@([a-zA-Z0-9_-])+((\.[a-zA-Z0-9_-]{2,3}){1,2})$/
      if (regex.test(value)) {
        state.loginType = 0
      } else {
        state.loginType = 1
      }
      return Promise.resolve()
    },
    handleTabClick (key) {
      this.customActiveKey = key
      this.setRules()
    },
    getLoginDomain (domain) {
      if (this.$config.loginBaseDomain) {
        if (domain) {
          return this.$config.loginBaseDomain + '/' + domain
        }
        return this.$config.loginBaseDomain
      }
      if (domain) {
        return domain
      }
      return '/'
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.state.loginBtn || !this.showPasswordForm) return
      this.formRef.value.validate().then(() => {
        this.state.loginBtn = true

        const values = toRaw(this.form)
        if (this.$config.multipleServer) {
          this.axios.defaults.baseURL = (this.server.apiHost || '') + this.server.apiBase
          store.dispatch('SetServer', this.server)
        }
        if (this.customActiveKey === 'cs') {
          const loginParams = { ...values }
          delete loginParams.username
          loginParams[!this.state.loginType ? 'email' : 'username'] = values.username
          loginParams.password = values.password
          loginParams.domain = this.getLoginDomain(values.domain)
          this.Login(loginParams)
            .then((res) => this.loginSuccess(res))
            .catch(err => {
              this.requestFailed(err)
              this.state.loginBtn = false
            })
        } else if (this.customActiveKey === 'saml') {
          this.state.loginBtn = false
          var samlUrl = this.$config.apiBase + '?command=samlSso'
          if (values.idp) {
            samlUrl += ('&idpid=' + values.idp)
          }
          window.location.href = samlUrl
        }
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    async loginSuccess (res) {
      this.$notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
      if (store.getters.twoFaEnabled === true && store.getters.twoFaProvider !== '' && store.getters.twoFaProvider !== undefined) {
        this.$router.push({ path: '/verify2FA' }).catch(() => {})
      } else if (store.getters.twoFaEnabled === true && (store.getters.twoFaProvider === '' || store.getters.twoFaProvider === undefined)) {
        this.$router.push({ path: '/setup2FA' }).catch(() => {})
      } else {
        this.$store.commit('SET_LOGIN_FLAG', true)
        const values = toRaw(this.form)
        if (values.project) {
          await this.getProject(values.project)
          this.$store.dispatch('ProjectView', this.project.id)
          this.$store.dispatch('SetProject', this.project)
          this.$store.dispatch('ToggleTheme', this.project.id === undefined ? 'light' : 'dark')
        }
        this.$router.push({ path: '/dashboard' }).catch(() => {})
      }
    },
    getProject (projectName) {
      return new Promise((resolve, reject) => {
        getAPI('listProjects', {
          response: 'json',
          domainId: this.selectedDomain,
          details: 'min'
        }).then((response) => {
          const projects = response.listprojectsresponse.project
          this.project = projects.filter(project => project.name === projectName)?.[0] || null
          resolve(this.project)
        }).catch((error) => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      })
    },
    requestFailed (err) {
      if (err && err.response && err.response.data && err.response.data.loginresponse) {
        const error = err.response.data.loginresponse.errorcode + ': ' + err.response.data.loginresponse.errortext
        this.$message.error(`${this.$t('label.error')} ${error}`)
      } else {
        this.$message.error(this.$t('message.login.failed'))
      }
    },
    onChangeServer (server) {
      const servers = this.$config.servers || []
      const serverFilter = servers.filter(ser => (ser.apiHost || '') + ser.apiBase === server)
      this.server = serverFilter[0] || {}
    }
  }
}
</script>

<style lang="less" scoped>
.user-layout-login {
  min-width: 300px;
  width: 500px;
  margin: 0 auto;

  :deep(.tab-center .ant-tabs-tab) {
    margin: 0 16px 0 0;
  }

  .mobile & {
    max-width: 500px;
    width: 98%;
  }

  label {
    font-size: 14px;
  }

  button.login-button {
    margin-top: 8px;
    padding: 0 15px;
    font-size: 16px;
    height: 40px;
    width: 100%;
  }

  .oidc-error {
    margin-bottom: 16px;
  }

  .oidc-providers {
    display: flex;
    flex-direction: column;
    gap: 12px;
    margin-bottom: 8px;
  }

  .password-login-toggle {
    text-align: center;
    margin-bottom: 8px;
  }

  .oidc-end-session {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    margin: 8px 0 16px;
    font-size: 13px;

    div {
      flex-basis: 100%;
    }
  }
}
</style>
