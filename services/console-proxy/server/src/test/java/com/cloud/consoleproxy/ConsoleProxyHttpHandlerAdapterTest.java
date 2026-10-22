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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;

/**
 * The page handlers keep the {@link com.sun.net.httpserver.HttpServer} contract when they serve on Jetty.
 */
public class ConsoleProxyHttpHandlerAdapterTest {
    private ConsoleProxyNoVNCServer server;
    private int port;
    private final AtomicReference<Thread> handlerThread = new AtomicReference<>();
    private final AtomicReference<IOException> secondHeaders = new AtomicReference<>();

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Before
    public void startServer() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ConsoleProxyHttpHandlerAdapter pages = new ConsoleProxyHttpHandlerAdapter();
        pages.createContext("/ajax", exchange -> respond(exchange, 200, "ajax " + exchange.getRequestURI().getQuery()));
        pages.createContext("/ajaximg", exchange -> respond(exchange, 200, "ajaximg"));
        pages.createContext("/resource/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/forbidden")) {
                exchange.sendResponseHeaders(403, -1);
            } else if (path.endsWith("/unchanged")) {
                exchange.getResponseHeaders().set("content-type", "text/html");
                exchange.sendResponseHeaders(304, -1);
            } else if (path.endsWith("/stream")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("streamed".getBytes(StandardCharsets.US_ASCII));
            } else {
                exchange.sendResponseHeaders(200, 4);
                try {
                    exchange.sendResponseHeaders(200, 4);
                } catch (IOException e) {
                    secondHeaders.set(e);
                }
                exchange.getResponseBody().write("once".getBytes(StandardCharsets.US_ASCII));
            }
            exchange.close();
        });
        pages.createContext("/getscreen", exchange -> {
            handlerThread.set(Thread.currentThread());
            Thread.currentThread().setName("named after " + exchange.getRequestURI());
            respond(exchange, 200, "screen");
        });
        server = new ConsoleProxyNoVNCServer(List.of(port), pages, null, null);
        server.start();
    }

    @After
    public void stopServer() throws Exception {
        server.stop();
    }

    private String get(String target) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static String body(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    @Test
    public void theLongestContextPathTheRequestStartsWithServesIt() throws IOException {
        Assert.assertEquals("ajax token=abc", body(get("/ajax?token=abc")));
        Assert.assertEquals("ajaximg", body(get("/ajaximg?token=abc")));
        Assert.assertTrue(get("/unknown").startsWith("HTTP/1.1 404"));
    }

    @Test
    public void noResponseLengthSendsNoBody() throws IOException {
        String forbidden = get("/resource/forbidden");
        Assert.assertTrue(forbidden, forbidden.startsWith("HTTP/1.1 403"));
        Assert.assertEquals("", body(forbidden));
        String unchanged = get("/resource/unchanged");
        Assert.assertTrue(unchanged, unchanged.startsWith("HTTP/1.1 304"));
        Assert.assertTrue(unchanged, unchanged.toLowerCase().contains("content-type: text/html"));
        Assert.assertEquals("", body(unchanged));
    }

    @Test
    public void aZeroResponseLengthSendsABodyOfAnyLength() throws IOException {
        String response = get("/resource/stream");
        Assert.assertFalse(response, response.toLowerCase().contains("content-length"));
        Assert.assertTrue(response, body(response).contains("streamed"));
    }

    @Test
    public void theResponseHeadersAreSentOnce() throws IOException {
        Assert.assertEquals("once", body(get("/resource/page")));
        Assert.assertNotNull(secondHeaders.get());
    }

    @Test
    public void aHandlerThatNamesItsThreadLeavesThePoolThreadNameAsItWas() throws Exception {
        Assert.assertEquals("screen", body(get("/getscreen?host=a")));
        Thread thread = handlerThread.get();
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getName().startsWith("named after") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertFalse(thread.getName(), thread.getName().startsWith("named after"));
    }
}
