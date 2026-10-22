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
package org.apache.cloudstack.oidc;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.cloudstack.oidc.api.command.OidcCallbackCmd;

/**
 * The redirect URI of every provider, {@code <client>/oidc/callback/<provider>}: a path without a query, so that it
 * matches its registration under any rule a provider compares redirect URIs by. The provider's answer is handed to
 * the {@code oidcCallback} API with the provider named by the path.
 */
public class OidcCallbackServlet extends HttpServlet {

    /**
     * Request attribute with the query string the provider returned the browser with. A forward merges and re-encodes
     * the query, which changes values that carry reserved characters.
     */
    public static final String ANSWER_ATTRIBUTE = OidcCallbackServlet.class.getName() + ".answer";

    private static final Pattern PROVIDER_PATH = Pattern.compile("/([a-z][a-z0-9]{0,63})");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.getPathInfo();
        Matcher matcher = path == null ? null : PROVIDER_PATH.matcher(path);
        if (matcher == null || !matcher.matches()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        req.setAttribute(ANSWER_ATTRIBUTE, req.getQueryString() == null ? "" : req.getQueryString());
        req.getRequestDispatcher("/api?command=" + OidcCallbackCmd.APINAME + "&provider=" + matcher.group(1)).forward(req, resp);
    }
}
