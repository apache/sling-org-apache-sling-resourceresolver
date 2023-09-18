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

import org.apache.sling.resourceresolver.util.events.ServiceEventUtil.ServiceEventDTO;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

abstract class AbstractAwaitingListener implements ServiceListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractAwaitingListener.class);

    private final BundleContext bundleContext;

    private final CountDownLatch latch;

    public AbstractAwaitingListener(BundleContext ctx, int count) {
        this.bundleContext = ctx;
        this.latch = new CountDownLatch(count);
    }

    protected final void startListening() {
        this.bundleContext.addServiceListener(this);
        LOG.info("addServiceListener({})", this);
    }

    @Override
    public void serviceChanged(ServiceEvent event) {
        final ServiceEventDTO eventDTO = ServiceEventDTO.create(event);
        final boolean matchingServiceEvent = isMatchingServiceEvent(event);
        LOG.info("serviceChanged({}, {}) => {} | {}", eventDTO.getEventType(), eventDTO.getClasses(), matchingServiceEvent, this);
        if (matchingServiceEvent) {
            latch.countDown();
        }
    }

    protected abstract boolean isMatchingServiceEvent(ServiceEvent serviceEvent);

    protected boolean await(long timeout, TimeUnit timeUnit) throws InterruptedException {
        return latch.await(timeout, timeUnit);
    }

    @Override
    public void close() {
        stopListening();
    }

    protected void stopListening() {
        bundleContext.removeServiceListener(this);
        LOG.info("removeServiceListener({})", this);
    }

}
