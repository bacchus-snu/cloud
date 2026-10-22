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
package com.cloud.servlet;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.cloudstack.management.ManagementServerHost.State;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class HealthServletTest {
    private static final long NODE_ID = 64869790233412L;
    private static final long RUN_ID = 1760000000000L;

    @Mock
    ClusterManager clusterManager;
    @Mock
    ManagementServerHostDao managementServerHostDao;
    @Mock
    HttpServletRequest request;

    private final HealthServlet servlet = new HealthServlet();

    @Before
    public void setUp() {
        servlet.clusterManager = clusterManager;
        servlet.managementServerHostDao = managementServerHostDao;
        Mockito.lenient().when(clusterManager.getManagementNodeId()).thenReturn(NODE_ID);
        Mockito.lenient().when(clusterManager.getCurrentRunId()).thenReturn(RUN_ID);
    }

    @After
    public void tearDown() {
        servlet.destroy();
    }

    private static final class Answer {
        final int status;
        final String body;
        final String cacheControl;

        Answer(int status, String body, String cacheControl) {
            this.status = status;
            this.body = body;
            this.cacheControl = cacheControl;
        }
    }

    private Answer get(HealthServlet healthServlet) throws Exception {
        HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));
        healthServlet.doGet(request, response);
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        ArgumentCaptor<String> cacheControl = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(Mockito.eq("Cache-Control"), cacheControl.capture());
        return new Answer(status.getValue(), body.toString(), cacheControl.getValue());
    }

    @Test
    public void receivesTrafficWhileThisRunIsUpAndOpensNoSession() throws Exception {
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenReturn(State.Up);

        Answer answer = get(servlet);

        Assert.assertEquals(HttpServletResponse.SC_OK, answer.status);
        Assert.assertEquals("the state only: no version, host or account detail", "Up\n", answer.body);
        Assert.assertEquals("no-store", answer.cacheControl);
        verify(request, never()).getSession();
        verify(request, never()).getSession(anyBoolean());
    }

    @Test
    public void isDrainedWhilePreparingForOrInMaintenanceOrShutdown() throws Exception {
        for (State state : new State[] {State.PreparingForMaintenance, State.Maintenance, State.PreparingForShutDown,
                State.ReadyToShutDown, State.ShuttingDown}) {
            when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenReturn(state);

            Answer answer = get(servlet);

            Assert.assertEquals(state.toString(), HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
            Assert.assertEquals(state + "\n", answer.body);
        }
    }

    @Test
    public void answersDownWhenThisRunIsStoppedFencedOrUnknown() throws Exception {
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenReturn(State.Down);
        Answer stopped = get(servlet);
        Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, stopped.status);
        Assert.assertEquals("Down\n", stopped.body);

        // the cluster fences a node by resetting the run id of its record, so no record of this run is left
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenReturn(null);
        Answer fenced = get(servlet);
        Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, fenced.status);
        Assert.assertEquals("Down\n", fenced.body);
    }

    @Test
    public void answersDownWhileTheDatabaseFails() throws Exception {
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS))
                .thenThrow(new CloudRuntimeException("Unable to read the state of management server " + NODE_ID));

        Answer answer = get(servlet);

        Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
        Assert.assertEquals("Down\n", answer.body);
    }

    @Test
    public void aHungDatabaseHoldsNoRequestLongerThanTheTimeout() throws Exception {
        servlet.checkTimeoutMillis = 200;
        CountDownLatch databaseAnswers = new CountDownLatch(1);
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenAnswer(invocation -> {
            databaseAnswers.await(10, TimeUnit.SECONDS);
            return State.Up;
        });

        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            Answer answer = get(servlet);
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
            Assert.assertEquals("Down\n", answer.body);
            Assert.assertTrue("waited " + waitedMillis + " ms", waitedMillis < 2000);
        }
        // the requests share the one check in flight instead of piling up database reads
        verify(managementServerHostDao, times(1)).findStateOfRun(anyLong(), anyLong(), anyInt());

        databaseAnswers.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Answer answer;
        do {
            answer = get(servlet);
        } while (answer.status != HttpServletResponse.SC_OK && System.nanoTime() < deadline);
        Assert.assertEquals(HttpServletResponse.SC_OK, answer.status);
    }

    /** Waits like a read from a peer that never answers: interrupts do not end it. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitPast(long millis) throws InterruptedException {
        Thread.sleep(millis + 50);
    }

    @Test
    public void aReadThatOutlivesItsBoundsIsLeftAndTheNextCheckRunsWhileAtMostOneThreadStaysStuck() throws Exception {
        servlet.checkTimeoutMillis = 100;
        servlet.abandonAfterMillis = 300;
        CountDownLatch firstReadEnds = new CountDownLatch(1);
        CountDownLatch secondReadEnds = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenAnswer(invocation -> {
            int read = reads.incrementAndGet();
            if (read == 1) {
                awaitIgnoringInterrupts(firstReadEnds);
            } else if (read == 2) {
                awaitIgnoringInterrupts(secondReadEnds);
            }
            return State.Up;
        });
        try {
            Assert.assertEquals("Down\n", get(servlet).body);
            Assert.assertEquals("Down\n", get(servlet).body);
            Assert.assertEquals("requests within the bound share the read", 1, reads.get());

            waitPast(servlet.abandonAfterMillis);
            Assert.assertEquals("Down\n", get(servlet).body);
            Assert.assertEquals("the overdue read is left and a new one starts", 2, reads.get());

            waitPast(servlet.abandonAfterMillis);
            Assert.assertEquals("Down\n", get(servlet).body);
            Assert.assertEquals("no third thread while the first is still stuck", 2, reads.get());

            firstReadEnds.countDown();
            waitPast(servlet.abandonAfterMillis);
            Answer answer = get(servlet);
            Assert.assertEquals(3, reads.get());
            Assert.assertEquals(HttpServletResponse.SC_OK, answer.status);
            Assert.assertEquals("Up\n", answer.body);
        } finally {
            firstReadEnds.countDown();
            secondReadEnds.countDown();
        }
    }

    @Test
    public void answersDownOnceDestroyed() throws Exception {
        servlet.destroy();

        Answer answer = get(servlet);

        Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
        Assert.assertEquals("Down\n", answer.body);
    }

    @Test
    public void startsNoCheckOnceDestroyedEvenWhenTheReadInFlightIsOverdue() throws Exception {
        servlet.checkTimeoutMillis = 100;
        servlet.abandonAfterMillis = 300;
        CountDownLatch firstReadEnds = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(managementServerHostDao.findStateOfRun(NODE_ID, RUN_ID, HealthServlet.CHECK_TIMEOUT_SECONDS)).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                awaitIgnoringInterrupts(firstReadEnds);
            }
            return State.Up;
        });
        try {
            Assert.assertEquals("Down\n", get(servlet).body);
            waitPast(servlet.abandonAfterMillis);
            servlet.destroy();

            Answer answer = get(servlet);

            Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
            Assert.assertEquals("Down\n", answer.body);
            Assert.assertEquals("no read starts once destroyed", 1, reads.get());
        } finally {
            firstReadEnds.countDown();
        }
    }

    @Test
    public void answersDownBeforeTheManagementServerHasStarted() throws Exception {
        HealthServlet uninjected = new HealthServlet();
        try {
            Answer answer = get(uninjected);

            Assert.assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, answer.status);
            Assert.assertEquals("Down\n", answer.body);
            verify(managementServerHostDao, never()).findStateOfRun(anyLong(), anyLong(), anyInt());
        } finally {
            uninjected.destroy();
        }
    }

    @Test
    public void theWebApplicationServesItAtClientHealth() throws Exception {
        File file = Paths.get("..", "client", "src", "main", "webapp", "WEB-INF", "web.xml").toFile();
        Assume.assumeTrue("the client module is not beside this one", file.isFile());
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);

        String servletName = null;
        NodeList servlets = document.getElementsByTagName("servlet");
        for (int i = 0; i < servlets.getLength(); i++) {
            Element servletElement = (Element) servlets.item(i);
            if (HealthServlet.class.getName().equals(text(servletElement, "servlet-class"))) {
                servletName = text(servletElement, "servlet-name");
            }
        }
        Assert.assertNotNull("web.xml declares no " + HealthServlet.class.getName(), servletName);

        boolean mapped = false;
        NodeList mappings = document.getElementsByTagName("servlet-mapping");
        for (int i = 0; i < mappings.getLength(); i++) {
            Element mapping = (Element) mappings.item(i);
            mapped |= servletName.equals(text(mapping, "servlet-name")) && "/health".equals(text(mapping, "url-pattern"));
        }
        Assert.assertTrue("web.xml maps no /health (served as /client/health) to " + servletName, mapped);
    }

    private static String text(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }
}
