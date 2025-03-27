/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.resourceresolver.util.events;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.sling.resourceresolver.util.events.ServiceEventUtil.ServiceEventDTO;
import org.hamcrest.Matcher;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceRegistration;

import static org.hamcrest.MatcherAssert.assertThat;

public class RecordingListener extends AbstractAwaitingListener {

    private final Collection<ServiceEventDTO> serviceEvents = new ConcurrentLinkedQueue<ServiceEventDTO>();

    private ServiceRegistration<RecordingListener> signalRegistration;

    private final Predicate<ServiceEvent> recordingFilter;

    public static RecordingListener of(BundleContext ctx) {
        final RecordingListener recordingListener = new RecordingListener(ctx, serviceEvent -> true);
        recordingListener.startListening();
        return recordingListener;
    }

    public static RecordingListener of(BundleContext ctx, Predicate<ServiceEvent> recordingFilter) {
        final RecordingListener recordingListener = new RecordingListener(ctx, recordingFilter);
        recordingListener.startListening();
        return recordingListener;
    }

    private RecordingListener(BundleContext ctx, Predicate<ServiceEvent> recordingFilter) {
        super(ctx, 1);
        this.recordingFilter = recordingFilter;
        this.signalRegistration = ctx.registerService(
                RecordingListener.class, this, new Hashtable<>(Map.of("::class::", RecordingListener.class)));
    }

    @Override
    protected boolean isMatchingServiceEvent(ServiceEvent serviceEvent) {
        return signalRegistration != null // is null when registering "this"
                && serviceEvent.getServiceReference() == signalRegistration.getReference();
    }

    @Override
    public void serviceChanged(ServiceEvent serviceEvent) {
        super.serviceChanged(serviceEvent);
        if (!isInternalEvent(serviceEvent) && recordingFilter.test(serviceEvent)) {
            serviceEvents.add(ServiceEventDTO.create(serviceEvent));
        }
    }

    private boolean isInternalEvent(ServiceEvent serviceEvent) {
        return Objects.equals(serviceEvent.getServiceReference().getProperty("::class::"), RecordingListener.class);
    }

    public void assertRecorded(Matcher<? super Collection<? extends ServiceEventDTO>> serviceEventDTOMatcher)
            throws InterruptedException {
        assertRecordedWithin(5, serviceEventDTOMatcher);
    }

    public void assertRecordedWithin(
            int maxWaitSec, Matcher<? super Collection<? extends ServiceEventDTO>> serviceEventDTOMatcher)
            throws InterruptedException {
        if (signalRegistration != null) {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSec);
            while (!serviceEventDTOMatcher.matches(serviceEvents) && System.nanoTime() < deadline) {
                // give other threads a chance
                Thread.yield();
            }
            signalRegistration.unregister();
            if (!await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Unregistration event not observed within 1 second.");
            }
            signalRegistration = null;
            stopListening();
        }

        assertThat(
                "Expected ServiceEvents were not recorded within " + maxWaitSec + " seconds. Make sure to "
                        + "re-try with a longer wait time.",
                serviceEvents,
                serviceEventDTOMatcher);
    }
}
