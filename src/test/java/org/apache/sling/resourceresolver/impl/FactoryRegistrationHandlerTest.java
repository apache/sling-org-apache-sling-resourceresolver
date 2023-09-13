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
package org.apache.sling.resourceresolver.impl;

import org.apache.commons.collections4.bidimap.TreeBidiMap;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.defaultanswers.ReturnsSmartNulls;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class FactoryRegistrationHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(FactoryRegistrationHandlerTest.class);

    public static final ReturnsSmartNulls DEFAULT_ANSWER = new ReturnsSmartNulls();

    @Rule
    public OsgiContext osgi = new OsgiContext();

    private ResourceResolverFactoryActivator activator;

    @Before
    public void setUp() {
        final ResourceProviderTracker resourceProviderTracker = mock(ResourceProviderTracker.class);
        doReturn(mock(ResourceProviderStorage.class)).when(resourceProviderTracker).getResourceProviderStorage();

        activator = mock(ResourceResolverFactoryActivator.class);
        doReturn(osgi.bundleContext()).when(activator).getBundleContext();
        doReturn(resourceProviderTracker).when(activator).getResourceProviderTracker();
        doReturn(mock(ServiceUserMapper.class)).when(activator).getServiceUserMapper();
        doReturn(new TreeBidiMap<>()).when(activator).getVirtualURLMap();
        doReturn(null).when(activator).getEventAdmin();

    }

    private static <T> T mock(Class<T> classToMock) {
        return Mockito.mock(classToMock, DEFAULT_ANSWER);
    }

    @Test
    public void testFactoryRegistration() throws InterruptedException {
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        when(preconditions.checkPreconditions(isNull(), isNull())).thenReturn(true);

        try (AwaitingListener unregistration =
                     AwaitingListener.unregistration(osgi.bundleContext(), ResourceResolverFactory.class)) {
            try (FactoryRegistrationHandler factoryRegistrationHandler =
                         new FactoryRegistrationHandler(activator, preconditions);
                 AwaitingListener registration = AwaitingListener.registration(osgi.bundleContext(), ResourceResolverFactory.class)) {
                factoryRegistrationHandler.maybeRegisterFactory(null, null);
                assertTrue("Expected ResourceResolverFactory service to be registered",
                        registration.await(5, TimeUnit.SECONDS));
            }
            assertTrue("Expected ResourceResolverFactory service to be unregistered",
                    unregistration.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testFactoryDeregistrationWhenConditionsUnsatisfied() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        try (FactoryRegistrationHandler factoryRegistrationHandler =
                     new FactoryRegistrationHandler(activator, preconditions);
             AwaitingListener registration = AwaitingListener.registration(ctx, ResourceResolverFactory.class);
             AwaitingListener unregistration = AwaitingListener.unregistration(ctx, ResourceResolverFactory.class)) {

            when(preconditions.checkPreconditions(isNull(), isNull())).thenReturn(true);
            factoryRegistrationHandler.maybeRegisterFactory(null, null);
            assertTrue("Expected ResourceResolverFactory service to be registered",
                    registration.await(5, TimeUnit.SECONDS));

            when(preconditions.checkPreconditions(isNull(), isNull())).thenReturn(false);
            factoryRegistrationHandler.maybeRegisterFactory(null, null);
            assertTrue("Expected ResourceResolverFactory service to be unregistered",
                    unregistration.await(5, TimeUnit.SECONDS));
        }
    }

    private static class AwaitingListener implements ServiceListener, AutoCloseable {

        private final BundleContext bundleContext;

        private final Predicate<ServiceEvent> expectedEventPredicate;

        private final CountDownLatch latch;

        public AwaitingListener(BundleContext ctx, Predicate<ServiceEvent> expectedEventPredicate) {
            this(ctx, expectedEventPredicate, 1);
        }

        public AwaitingListener(BundleContext ctx, Predicate<ServiceEvent> expectedEventPredicate, int count) {
            this.bundleContext = ctx;
            this.expectedEventPredicate = expectedEventPredicate;
            this.latch = new CountDownLatch(count);
            this.bundleContext.addServiceListener(this);
        }

        public static AwaitingListener registration(BundleContext bundleContext, Class<?> clazz) {
            return new AwaitingListener(bundleContext, serviceEvent -> {
                if (serviceEvent.getType() == ServiceEvent.REGISTERED) {
                    return isInstance(bundleContext, serviceEvent.getServiceReference(), clazz);
                }
                return false;
            });
        }

        public static AwaitingListener unregistration(BundleContext bundleContext, Class<?> clazz) {
            return new AwaitingListener(bundleContext, serviceEvent -> {
                if (serviceEvent.getType() == ServiceEvent.UNREGISTERING) {
                    return isInstance(bundleContext, serviceEvent.getServiceReference(), clazz);
                }
                return false;
            });
        }

        private static boolean isInstance(BundleContext bundleContext, ServiceReference<?> ref, Class<?> clazz) {
            try {
                final Object service = bundleContext.getService(ref);
                final boolean isInstance = clazz.isInstance(service);
                LOG.info("{} == isInstance({}, {})", isInstance, service.getClass(), clazz);
                return isInstance;
            } finally {
                bundleContext.ungetService(ref);
            }
        }

        @Override
        public void serviceChanged(ServiceEvent event) {
            if (expectedEventPredicate.test(event)) {
                latch.countDown();
            }
        }

        public boolean await(long timeout, TimeUnit timeUnit) throws InterruptedException {
            return latch.await(timeout, timeUnit);
        }

        @Override
        public void close() {
            bundleContext.removeServiceListener(this);
        }
    }
}