//
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
//

package com.cloud.agent.api;

/**
 * The fingerprint of the server certificate a system VM presents, carried by the plain {@link Answer} to a command that
 * starts or reloads its server as an answer context parameter: a management server that does not know the parameter
 * reads the answer as before and ignores it.
 */
public final class ServerCertificateFingerprint {
    static final String CONTEXT_PARAMETER = "serverCertificateFingerprint";

    private ServerCertificateFingerprint() {
    }

    /**
     * @param fingerprint the SHA-256 fingerprint of the certificate the server presents to new connections, as
     *        colon-separated upper-case hex, or null when it presents none or it is not known
     */
    public static Answer answer(Command command, boolean success, String details, String fingerprint) {
        Answer answer = new Answer(command, success, details);
        if (fingerprint != null) {
            answer.setContextParam(CONTEXT_PARAMETER, fingerprint);
        }
        return answer;
    }

    /**
     * @return the fingerprint the answer carries, or null when it carries none
     */
    public static String of(Answer answer) {
        return answer == null ? null : answer.getContextParam(CONTEXT_PARAMETER);
    }
}
