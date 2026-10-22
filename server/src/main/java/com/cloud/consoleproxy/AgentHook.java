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

import java.util.List;

import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.ConsoleAccessAuthenticationCommand;
import com.cloud.agent.api.ConsoleProxyLoadReportCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupProxyCommand;
import com.cloud.host.Host;
import com.cloud.host.Status;

public interface AgentHook {
    void onLoadReport(ConsoleProxyLoadReportCommand cmd);

    AgentControlAnswer onConsoleAccessAuthentication(ConsoleAccessAuthenticationCommand cmd);

    void onAgentConnect(Host host, StartupCommand cmd);

    public void onAgentDisconnect(long agentId, Status state);

    public void startAgentHttpHandlerInVM(StartupProxyCommand startupCmd);

    /**
     * Sends every connected console proxy agent of a zone that serves the stored server certificate its HTTP handler
     * settings with that certificate, as on connect; a proxy that is already serving loads it for new connections.
     *
     * @return the names of the console proxies that did not confirm serving the stored certificate
     */
    List<String> startAgentHttpHandlers();
}
