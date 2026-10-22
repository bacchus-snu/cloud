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

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.dao.ManagementServerHostDao;

/**
 * Unauthenticated health check that tells whether this management server should receive traffic: 200 while the
 * database holds its record of this running process in state Up; 503 while it prepares for or is in maintenance or
 * shutdown, and when it has not started, has stopped, was fenced by the cluster, or the database does not answer in
 * time. The body is the state only. The database is read by one statement under statement and network timeouts, on a
 * checker thread that concurrent requests share, so a hung database holds no request thread longer than the timeout. A
 * read that outlives every bound is left to its thread, and the next request checks on a new one; at most one such
 * thread is kept.
 */
public class HealthServlet extends HttpServlet {
    private static final long serialVersionUID = 4626262150519815390L;
    private static final Logger LOGGER = LogManager.getLogger(HealthServlet.class);
    static final int CHECK_TIMEOUT_SECONDS = 3;

    @Inject
    ClusterManager clusterManager;
    @Inject
    ManagementServerHostDao managementServerHostDao;

    long checkTimeoutMillis = TimeUnit.SECONDS.toMillis(CHECK_TIMEOUT_SECONDS);
    /** Beyond the read's own bounds: the pool's wait for a connection (30 s by default), the statement and network timeouts. */
    long abandonAfterMillis = TimeUnit.SECONDS.toMillis(40);
    private transient ExecutorService checker = newChecker();
    private transient ExecutorService abandonedChecker;
    private transient Future<ManagementServerHost.State> pendingCheck;
    private transient long pendingSince;
    private transient boolean destroyed;

    private static ExecutorService newChecker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "HealthCheck");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        SpringBeanAutowiringSupport.processInjectionBasedOnServletContext(this, config.getServletContext());
    }

    @Override
    public synchronized void destroy() {
        destroyed = true;
        checker.shutdownNow();
        if (abandonedChecker != null) {
            abandonedChecker.shutdownNow();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ManagementServerHost.State state = currentState();
        resp.setStatus(state == ManagementServerHost.State.Up ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(state + "\n");
    }

    /**
     * @return the state of this run's record, or Down when it is not known
     */
    ManagementServerHost.State currentState() {
        if (clusterManager == null || managementServerHostDao == null) {
            return ManagementServerHost.State.Down;
        }
        try {
            ManagementServerHost.State state = check().get(checkTimeoutMillis, TimeUnit.MILLISECONDS);
            return state == null ? ManagementServerHost.State.Down : state;
        } catch (TimeoutException e) {
            LOGGER.debug("Health check: the database did not answer within {} ms", checkTimeoutMillis);
        } catch (ExecutionException e) {
            LOGGER.debug("Health check: the database check failed: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Health check: the servlet is stopping");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ManagementServerHost.State.Down;
    }

    private synchronized Future<ManagementServerHost.State> check() {
        if (destroyed) {
            throw new RejectedExecutionException("the health check is stopped");
        }
        long now = System.nanoTime();
        if (pendingCheck != null && !pendingCheck.isDone()) {
            boolean overdue = now - pendingSince > TimeUnit.MILLISECONDS.toNanos(abandonAfterMillis);
            if (!overdue || (abandonedChecker != null && !abandonedChecker.isTerminated())) {
                return pendingCheck;
            }
            checker.shutdownNow();
            abandonedChecker = checker;
            checker = newChecker();
        }
        long msid = clusterManager.getManagementNodeId();
        long runid = clusterManager.getCurrentRunId();
        pendingCheck = checker.submit(() -> managementServerHostDao.findStateOfRun(msid, runid, CHECK_TIMEOUT_SECONDS));
        pendingSince = now;
        return pendingCheck;
    }
}
