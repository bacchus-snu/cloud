/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cloudstack.vm.lease;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.ValidatedConfigKey;
import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.Manager;

/**
 * A lease belongs to the running session of an Instance: an Instance whose service offering carries {@link #LEASE_HOURS}
 * gets an expiry when it starts and loses it when it stops. A restart within a running session (a forced reboot, an HA
 * restart, the restart of a reinstall) carries the Instance detail {@code VmDetailConstants.RESTART_IN_PROGRESS}, and the
 * session, with its lease and the host resources the Instance holds, continues through the stop and start it makes.
 */
public interface VMLeaseManager extends Manager {

    int MAX_LEASE_DURATION_DAYS = 365_00; // 100 years

    int MAX_LEASE_HOURS = MAX_LEASE_DURATION_DAYS * 24;

    /**
     * The service offering detail giving every session of its Instances a lease of this many hours.
     */
    String LEASE_HOURS = "leasehours";

    /**
     * The service offering details of a lease, written only through the offering's own validated lease parameters.
     */
    Set<String> OFFERING_LEASE_DETAILS = Set.of(LEASE_HOURS, ApiConstants.INSTANCE_LEASE_DURATION, ApiConstants.INSTANCE_LEASE_EXPIRY_ACTION);

    String RENEW_LEASE_API = "renewVirtualMachineLease";

    String SET_LEASE_API = "setVirtualMachineLease";

    enum ExpiryAction {
        STOP,
        DESTROY
    }

    enum LeaseActionExecution {
        PENDING,
        DISABLED,
        DONE,
        CANCELLED
    }

    ConfigKey<Boolean> InstanceLeaseEnabled = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Boolean.class,
            "instance.lease.enabled", "false", "Indicates whether Instance leases are in force: a running Instance whose service offering carries " +
            "leasehours gets a lease at each start, and the lease's expiry action runs when it ends. Disabling the feature cancels the leases " +
            "of existing Instances; once it is enabled again, running Instances of such offerings get a new lease at the next scheduler run",
            true, List.of(ConfigKey.Scope.Global));

    ConfigKey<Integer> InstanceLeaseSchedulerInterval = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class,
            "instance.lease.scheduler.interval", "3600", "VM Lease Scheduler interval in seconds",
            false, List.of(ConfigKey.Scope.Global));

    ConfigKey<Integer> InstanceLeaseExpiryEventSchedulerInterval = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Integer.class,
            "instance.lease.eventscheduler.interval", "86400", "Lease expiry event Scheduler interval in seconds",
            false, List.of(ConfigKey.Scope.Global));

    ConfigKey<String> InstanceLeaseExpiryEventHoursBefore = new ValidatedConfigKey<>(ConfigKey.CATEGORY_ADVANCED, String.class,
            "instance.lease.expiryevent.hoursbefore", "4,1", "Comma-separated hours before the end of a lease at which its owner is warned " +
            "(an event, and an e-mail once a mail relay is set), once per hour listed. A lease set inside a window is warned at once, once, " +
            "for the narrowest window it is in. Empty sends no warnings",
            true, ConfigKey.Scope.Global, null, VMLeaseManager::validateWarningHours);

    ConfigKey<Boolean> InstanceLeaseRenewalEnabled = new ConfigKey<>(ConfigKey.CATEGORY_ADVANCED, Boolean.class,
            "instance.lease.renewal.enabled", "true", "Indicates whether the owner of a running Instance may renew its lease, " +
            "which then ends the service offering's leasehours after the renewal",
            true, List.of(ConfigKey.Scope.Global));

    void onLeaseFeatureToggle();

    /**
     * Sets the lease of a running Instance to end its offering's leasehours from now.
     */
    UserVm renewLease(long vmId) throws ResourceAllocationException;

    /**
     * Sets the end of the running session's lease; the next start gives the offering's lease again.
     */
    UserVm setLease(long vmId, Date expiry);

    /**
     * Marks the Instance's next stop and start as a restart within its running session. The restart is owned by the job
     * the calling thread runs, or, outside a job (a thread's pseudo job never completes), by this management server's
     * run; the caller ends it on every way out, and a restart whose owner is gone is ended by the lease scheduler.
     *
     * @return the marker to pass to {@link #endRestart(long, String)}
     */
    String beginRestart(long vmId);

    /**
     * Hands a restart over to the HA work item that completes it; a work item already finished ends it at once.
     *
     * @return the marker the HA work ends the restart with
     */
    String handRestartToHaWork(long vmId, String marker, long haWorkId);

    /**
     * Ends the restart named by the marker if the Instance still carries it. An Instance that did not come back to run
     * gives up what its session held: its lease and its host resources.
     */
    void endRestart(long vmId, String marker);

    /**
     * Ends the session of an Instance that leaves management without a state transition: its lease and any restart
     * marker go.
     */
    void endSession(long vmId);

    /**
     * The hours of a stored {@link #LEASE_HOURS} value, or null when it is absent or not a whole number from 1 to
     * {@link #MAX_LEASE_HOURS}.
     */
    static Integer leaseHoursOf(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            int hours = Integer.parseInt(value.trim());
            return hours >= 1 && hours <= MAX_LEASE_HOURS ? hours : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String haWorkRestartMarker(long haWorkId) {
        return "ha:" + haWorkId;
    }

    /**
     * @return the distinct hours of a warning list, ascending; empty for a blank list
     * @throws InvalidParameterValueException for an entry that is not a whole number of hours of 1 or more
     */
    static SortedSet<Integer> parseWarningHours(String value) {
        SortedSet<Integer> hours = new TreeSet<>();
        if (StringUtils.isBlank(value)) {
            return hours;
        }
        for (String entry : value.split(",")) {
            String hour = entry.trim();
            int parsed;
            try {
                parsed = Integer.parseInt(hour);
            } catch (NumberFormatException e) {
                throw new InvalidParameterValueException(String.format("%s lists whole hours separated by commas; '%s' is not one",
                        InstanceLeaseExpiryEventHoursBefore.key(), hour));
            }
            if (parsed < 1) {
                throw new InvalidParameterValueException(String.format("%s lists hours of 1 or more; %d is not",
                        InstanceLeaseExpiryEventHoursBefore.key(), parsed));
            }
            hours.add(parsed);
        }
        return hours;
    }

    static void validateWarningHours(String value) {
        parseWarningHours(value);
    }

    /**
     * @return the hour of the narrowest warning window that holds the time left, or null when the lease has ended or is in none
     */
    static Integer dueWarningHours(SortedSet<Integer> hours, Date expiry, Date now) {
        long left = expiry.getTime() - now.getTime();
        if (left <= 0) {
            return null;
        }
        for (int hour : hours) {
            if (left <= hour * 3_600_000L) {
                return hour;
            }
        }
        return null;
    }
}
