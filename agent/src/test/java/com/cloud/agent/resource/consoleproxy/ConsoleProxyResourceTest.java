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
package com.cloud.agent.resource.consoleproxy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ServerCertificateFingerprint;
import com.cloud.agent.api.proxy.StartConsoleProxyAgentHttpHandlerCommand;
import com.cloud.serializer.GsonHelper;

public class ConsoleProxyResourceTest {
    private ConsoleProxyResource resource;

    private static Class<?> consoleProxy() throws ClassNotFoundException {
        return Class.forName("com.cloud.consoleproxy.ConsoleProxy");
    }

    @Before
    @After
    public void resetConsoleProxy() throws ReflectiveOperationException {
        Method reset = consoleProxy().getDeclaredMethod("reset");
        reset.setAccessible(true);
        reset.invoke(null);
        resource = new ConsoleProxyResource();
    }

    private static StartConsoleProxyAgentHttpHandlerCommand handlerCommand(String keyStore) {
        StartConsoleProxyAgentHttpHandlerCommand cmd = new StartConsoleProxyAgentHttpHandlerCommand(keyStore.getBytes(), "password");
        cmd.setEncryptorPassword("encryptor");
        cmd.setIsSourceIpCheckEnabled(false);
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private static List<String> loadedKeyStores() throws ReflectiveOperationException {
        Field loaded = consoleProxy().getDeclaredField("LOADED_KEY_STORES");
        loaded.setAccessible(true);
        return (List<String>)loaded.get(null);
    }

    @Test
    public void aRunningConsoleProxyLoadsEachKeyStoreItIsSentAndReportsTheCertificateItServes() throws Exception {
        Answer first = resource.executeRequest(handlerCommand("first"));
        Assert.assertTrue(first.getResult());
        Assert.assertEquals("first", ServerCertificateFingerprint.of(first));

        Answer second = resource.executeRequest(handlerCommand("second"));
        Assert.assertTrue(second.getResult());
        Assert.assertEquals("second", ServerCertificateFingerprint.of(second));
        Assert.assertEquals(List.of("first", "second"), loadedKeyStores());
    }

    /**
     * Agent and management server exchange answers as JSON that names each answer's class, which the receiving side
     * loads by name; a management server of an earlier release has only the classes it shipped with.
     */
    @Test
    public void theAnswerIsOfTheClassAnEarlierManagementServerReadsAndStillCarriesTheFingerprint() throws Exception {
        Answer answer = resource.executeRequest(handlerCommand("first"));

        String json = GsonHelper.getGson().toJson(new Answer[] {answer}, Answer[].class);
        Assert.assertTrue(json, json.startsWith("[{\"" + Answer.class.getName() + "\":"));

        Answer[] received = GsonHelper.getGson().fromJson(json, Answer[].class);
        Assert.assertEquals(Answer.class, received[0].getClass());
        Assert.assertTrue(received[0].getResult());
        Assert.assertEquals("first", ServerCertificateFingerprint.of(received[0]));
    }

    @Test
    public void aKeyStoreThatCannotBeLoadedLeavesTheServedCertificateInPlace() throws Exception {
        resource.executeRequest(handlerCommand("first"));
        Field failure = consoleProxy().getDeclaredField("failure");
        failure.setAccessible(true);
        failure.set(null, "no private key");

        Answer answer = resource.executeRequest(handlerCommand("broken"));
        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails(), answer.getDetails().contains("no private key"));
        Assert.assertEquals("first", ServerCertificateFingerprint.of(answer));
    }
}
