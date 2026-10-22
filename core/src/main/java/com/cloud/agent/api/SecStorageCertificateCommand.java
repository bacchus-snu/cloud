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

import org.apache.cloudstack.framework.security.keystore.KeystoreManager;

/**
 * Gives a running secondary storage VM's web server new certificates; it presents them to new connections while the
 * ones in progress finish. The wait is short: the caller waits for each secondary storage VM in turn. An agent that
 * predates this command answers it as unsupported.
 */
public class SecStorageCertificateCommand extends Command {
    public static final int WAIT_SECONDS = 30;

    private KeystoreManager.Certificates certs;

    public SecStorageCertificateCommand() {
    }

    public SecStorageCertificateCommand(KeystoreManager.Certificates certs) {
        this.certs = certs;
        setWait(WAIT_SECONDS);
    }

    public KeystoreManager.Certificates getCerts() {
        return certs;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
