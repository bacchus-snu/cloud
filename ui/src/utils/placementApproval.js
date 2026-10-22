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

import { isMoveRequired, placementAction, reasonText } from '@/utils/placement'

/**
 * The approval of a move that an operation needs before it can run, for a dialog that stays open until the operation is
 * over: the placement is asked first, a verdict that needs a move is shown to the owner, the operation then carries the
 * approval it was given, and when the server finds that the approval no longer matches the owner is shown the new verdict.
 *
 * The component holds `loading` and `closeAction`, and supplies:
 *   approvalCheck(params)        the verdict of the placement, as a promise
 *   approvalRun(params)          the operation without a move
 *   approvalPost(params, verdict) the operation with the approval, as a promise of the response that starts the job
 *   approvalPoll(response, handlers) follows the job with the given success, error and catch handlers
 *   approvalRefusal(reason)      the text shown when the operation cannot run now
 */
export default {
  data () {
    return {
      moveVisible: false,
      moveVerdict: null,
      refusal: '',
      working: false,
      pendingParams: null,
      approvalGone: false
    }
  },
  beforeUnmount () {
    this.approvalGone = true
  },
  methods: {
    beginApproval (params) {
      this.refusal = ''
      this.pendingParams = params
      this.loading = true
      return this.askPlacement(params)
    },
    // The check is advisory: when it cannot be made the operation goes on without an approval, which never moves anything,
    // and a move it needs comes back as the distinct refusal.
    askPlacement (params) {
      return this.approvalCheck(params).catch(() => null).then(verdict => {
        this.loading = false
        if (this.approvalGone) {
          return
        }
        if (!verdict) {
          this.$notification.warning({ message: this.$t('label.placement'), description: this.$t('message.placement.check.failed') })
          this.approvalRun(params)
          return
        }
        switch (placementAction(verdict)) {
          case 'approve':
            this.moveVerdict = verdict
            this.moveVisible = true
            break
          case 'refuse':
            this.refusal = this.approvalRefusal(reasonText(this.$t, verdict.reason))
            break
          default:
            this.approvalRun(params)
        }
      })
    },
    approveMove (verdict) {
      this.moveVisible = false
      this.working = true
      this.loading = true
      this.approvalPost(this.pendingParams, verdict).then(response => {
        this.approvalPoll(response, {
          successMethod: () => this.finishApproval(),
          errorMethod: (result) => this.approvalFailed(result),
          catchMethod: () => this.finishApproval()
        })
      }).catch(error => {
        this.working = false
        this.$notifyError(error)
        if (isMoveRequired(error)) {
          this.askPlacement(this.pendingParams)
        } else {
          this.loading = false
        }
      })
    },
    rejectMove () {
      this.moveVisible = false
    },
    approvalFailed (result) {
      this.working = false
      if (this.approvalGone || !isMoveRequired(result)) {
        this.finishApproval()
        return
      }
      this.loading = true
      this.askPlacement(this.pendingParams)
    },
    finishApproval () {
      this.working = false
      this.loading = false
      if (!this.approvalGone) {
        this.closeAction()
      }
    }
  }
}
