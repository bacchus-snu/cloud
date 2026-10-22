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

import { fetchCapabilities, vcpuMinimum, vcpuStep } from '@/utils/placement'

/**
 * The multiple an Instance's vCPU count must be, for a wizard that hands it to the vCPU input: `cpuStep` is the
 * operator's step (2 until the server says otherwise) and `cpuMinimum(n)` the smallest count of an offering that is a
 * multiple of it.
 */
export default {
  data () {
    return {
      poolCapabilities: {}
    }
  },
  computed: {
    cpuStep () {
      return vcpuStep(this.poolCapabilities)
    }
  },
  created () {
    fetchCapabilities().then(capabilities => {
      this.poolCapabilities = capabilities
    }).catch(() => {})
  },
  methods: {
    cpuMinimum (offeringMinimum) {
      return vcpuMinimum(offeringMinimum, this.cpuStep)
    }
  }
}
