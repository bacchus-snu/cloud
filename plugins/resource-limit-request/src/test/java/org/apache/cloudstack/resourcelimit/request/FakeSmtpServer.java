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

package org.apache.cloudstack.resourcelimit.request;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** An SMTP server on the loopback address that accepts every message and keeps it. */
final class FakeSmtpServer implements AutoCloseable {

    static final class Message {
        final String sender;
        final List<String> recipients;
        final String data;

        Message(String sender, List<String> recipients, String data) {
            this.sender = sender;
            this.recipients = recipients;
            this.data = data;
        }
    }

    private final ServerSocket socket;
    private final Thread acceptor;
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private volatile int connections;

    FakeSmtpServer() throws IOException {
        socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::accept, "fake-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int getPort() {
        return socket.getLocalPort();
    }

    List<Message> getMessages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    int getConnections() {
        return connections;
    }

    private void accept() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                connections++;
                serve(client);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = client.getOutputStream();
        reply(out, "220 fake ESMTP");
        String sender = null;
        List<String> recipients = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.toUpperCase(Locale.ROOT);
            if (command.startsWith("MAIL FROM:")) {
                sender = line.substring(10).trim();
                reply(out, "250 OK");
            } else if (command.startsWith("RCPT TO:")) {
                recipients.add(line.substring(8).trim().replaceAll("[<>]", ""));
                reply(out, "250 OK");
            } else if (command.equals("DATA")) {
                reply(out, "354 End data with <CR><LF>.<CR><LF>");
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null && !line.equals(".")) {
                    data.append(line).append('\n');
                }
                messages.add(new Message(sender, new ArrayList<>(recipients), data.toString()));
                recipients.clear();
                reply(out, "250 OK");
            } else if (command.equals("QUIT")) {
                reply(out, "221 Bye");
                return;
            } else {
                reply(out, "250 OK");
            }
        }
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
