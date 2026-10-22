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
package com.cloud.consoleproxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Stands in for the console proxy, which the agent reaches by reflection, and records what it is given. A key store's
 * bytes stand for its certificate.
 */
public class ConsoleProxy {
    static final List<String> LOADED_KEY_STORES = new ArrayList<>();
    static String servedCertificate;
    static String failure;

    static synchronized void reset() {
        LOADED_KEY_STORES.clear();
        servedCertificate = null;
        failure = null;
    }

    public static synchronized void startWithContext(Properties conf, Object context, byte[] ksBits, String ksPassword, String password, Boolean isSourceIpCheckEnabled) {
        LOADED_KEY_STORES.add(new String(ksBits));
        servedCertificate = new String(ksBits);
    }

    public static void setEncryptorPassword(String password) {
    }

    public static void setIsSourceIpCheckEnabled(Boolean isEnabled) {
    }

    public static synchronized void loadServerCertificate(byte[] ksBits, String ksPassword) {
        if (failure != null) {
            throw new IllegalArgumentException(failure);
        }
        LOADED_KEY_STORES.add(new String(ksBits));
        servedCertificate = new String(ksBits);
    }

    public static synchronized String getServerCertificateFingerprint() {
        return servedCertificate;
    }
}
