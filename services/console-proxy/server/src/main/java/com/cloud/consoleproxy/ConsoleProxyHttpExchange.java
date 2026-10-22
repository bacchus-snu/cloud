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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

/**
 * An {@link HttpExchange} over a servlet request and response, so that {@link com.sun.net.httpserver.HttpHandler}s
 * serve on a servlet container. {@link #sendResponseHeaders(int, long)} keeps its length contract: a positive length is
 * exact, 0 sends a body of any length and -1 sends none.
 */
public class ConsoleProxyHttpExchange extends HttpExchange {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final URI requestUri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private InputStream requestBody;
    private OutputStream responseBody;
    private int responseCode = -1;

    public ConsoleProxyHttpExchange(HttpServletRequest request, HttpServletResponse response, URI requestUri) throws IOException {
        this.request = request;
        this.response = response;
        this.requestUri = requestUri;
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                requestHeaders.add(name, value);
            }
        }
        requestBody = request.getInputStream();
        responseBody = response.getOutputStream();
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return requestUri;
    }

    @Override
    public String getRequestMethod() {
        return request.getMethod();
    }

    /**
     * @return null: the handlers are not served from an {@link com.sun.net.httpserver.HttpServer}
     */
    @Override
    public HttpContext getHttpContext() {
        return null;
    }

    @Override
    public void close() {
        try {
            requestBody.close();
        } catch (IOException ignored) {
            // the request is complete either way
        }
        try {
            responseBody.close();
        } catch (IOException ignored) {
            // the client is gone
        }
    }

    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
        return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        if (responseCode != -1) {
            throw new IOException("The response headers have already been sent");
        }
        responseCode = rCode;
        response.setStatus(rCode);
        for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
            for (String value : header.getValue()) {
                response.addHeader(header.getKey(), value);
            }
        }
        if (responseLength > 0) {
            response.setContentLengthLong(responseLength);
        } else if (responseLength < 0) {
            response.setContentLengthLong(0);
        }
        response.flushBuffer();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort());
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(request.getLocalAddr(), request.getLocalPort());
    }

    @Override
    public String getProtocol() {
        return request.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
        return request.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        request.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        if (i != null) {
            requestBody = i;
        }
        if (o != null) {
            responseBody = o;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }
}
