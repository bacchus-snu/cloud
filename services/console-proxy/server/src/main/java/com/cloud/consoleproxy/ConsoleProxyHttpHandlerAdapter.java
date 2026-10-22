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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.sun.net.httpserver.HttpHandler;

/**
 * Serves {@link HttpHandler}s on Jetty. A request goes to the handler of the longest context path its path starts
 * with, as {@link com.sun.net.httpserver.HttpServer} dispatches; a request no context matches is left unhandled.
 */
public class ConsoleProxyHttpHandlerAdapter extends AbstractHandler {
    private final Map<String, HttpHandler> contexts = new HashMap<>();

    public void createContext(String path, HttpHandler handler) {
        contexts.put(path, handler);
    }

    protected HttpHandler findHandler(String path) {
        String match = null;
        for (String context : contexts.keySet()) {
            if (path.startsWith(context) && (match == null || context.length() > match.length())) {
                match = context;
            }
        }
        return match == null ? null : contexts.get(match);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpHandler handler = findHandler(request.getRequestURI());
        if (handler == null) {
            return;
        }
        baseRequest.setHandled(true);
        URI uri;
        try {
            uri = new URI(request.getQueryString() == null ? request.getRequestURI() : request.getRequestURI() + "?" + request.getQueryString());
        } catch (URISyntaxException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        // handlers may name the thread after the request, and this one returns to a pool
        String threadName = Thread.currentThread().getName();
        try {
            handler.handle(new ConsoleProxyHttpExchange(request, response, uri));
        } finally {
            Thread.currentThread().setName(threadName);
        }
    }
}
