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
package org.apache.cloudstack.api.command;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.ldap.LdapManager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.exception.PermissionDeniedException;

@RunWith(MockitoJUnitRunner.class)
public class LDAPConfigCmdTest {

    @Mock
    ConfigurationManager configurationManager;
    @Mock
    ConfigurationDao configDao;
    @Mock
    LdapManager ldapManager;

    LDAPConfigCmd cmd = new LDAPConfigCmd();

    private void injectByType(Class<?> type, Object value) throws IllegalAccessException {
        for (Field field : LDAPConfigCmd.class.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                field.set(cmd, value);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void theLdapSettingsItWritesChangeOnlyThroughTheSettingsCheck() throws IllegalAccessException {
        injectByType(ConfigurationManager.class, configurationManager);
        injectByType(ConfigurationDao.class, configDao);
        injectByType(LdapManager.class, ldapManager);
        ReflectionTestUtils.setField(cmd, "hostname", "ldap.example.org");
        ReflectionTestUtils.setField(cmd, "port", 389);
        Mockito.doThrow(new PermissionDeniedException("refused")).when(configurationManager).checkCallerMayChangeAccessControlSettings(Mockito.any());

        Assert.assertThrows(PermissionDeniedException.class, () -> cmd.execute());

        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        Mockito.verify(configurationManager).checkCallerMayChangeAccessControlSettings(names.capture());
        Assert.assertTrue(names.getValue().containsAll(List.of("ldap.basedn", "ldap.bind.principal", "ldap.bind.password", "ldap.truststore",
                "ldap.truststore.password")));
        Mockito.verifyNoInteractions(ldapManager, configDao);
    }
}
