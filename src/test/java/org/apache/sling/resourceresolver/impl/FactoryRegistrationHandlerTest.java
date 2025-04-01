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
import org.apache.sling.resourceresolver.util.events.RecordingListener;
import org.apache.sling.resourceresolver.util.events.ServiceEventUtil.ServiceEventDTO;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.apache.sling.testing.mock.osgi.junit5.OsgiContextExtension;
import org.hamcrest.Matcher;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.defaultanswers.ReturnsSmartNulls;
import org.osgi.framework.BundleContext;

import static org.apache.sling.resourceresolver.util.CustomMatchers.allOf;
import static org.apache.sling.resourceresolver.util.CustomMatchers.hasItem;
import static org.apache.sling.resourceresolver.util.events.ServiceEventUtil.registration;
import static org.apache.sling.resourceresolver.util.events.ServiceEventUtil.unregistration;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(OsgiContextExtension.class)
class FactoryRegistrationHandlerTest {

    private static final int DEFAULT_TEST_ITERATIONS = 20;

    private static final @NotNull Matcher<Iterable<? extends ServiceEventDTO>> RRF_REGISTRATION =
            allOf(hasSize(4), hasItem(registration(ResourceResolverFactory.class)));

    private static final @NotNull Matcher<Iterable<? extends ServiceEventDTO>> RRF_UNREGISTRATION =
            allOf(hasSize(4), hasItem(unregistration(ResourceResolverFactory.class)));

    private static final @NotNull Matcher<Iterable<? extends ServiceEventDTO>> RRF_REREGISTRATION = allOf(
            hasSize(8),
            hasItem(unregistration(ResourceResolverFactory.class)),
            hasItem(registration(ResourceResolverFactory.class)));

    OsgiContext osgi = new OsgiContext();

    private ResourceResolverFactoryActivator activator;

    @BeforeEach
    void setUp() {
        final ResourceProviderTracker resourceProviderTracker = mock(ResourceProviderTracker.class);
        doReturn(mock(ResourceProviderStorage.class))
                .when(resourceProviderTracker)
                .getResourceProviderStorage();

        final VanityPathConfigurer vanityPathConfigurer = mock(VanityPathConfigurer.class);
        doReturn(false).when(vanityPathConfigurer).isVanityPathEnabled();

        activator = mock(ResourceResolverFactoryActivator.class);
        doReturn(osgi.bundleContext()).when(activator).getBundleContext();
        doReturn(resourceProviderTracker).when(activator).getResourceProviderTracker();
        doReturn(mock(ServiceUserMapper.class)).when(activator).getServiceUserMapper();
        doReturn(new TreeBidiMap<>()).when(activator).getVirtualURLMap();
        doReturn(null).when(activator).getEventAdmin();
        doReturn(vanityPathConfigurer).when(activator).getVanityPathConfigurer();
        doCallRealMethod().when(activator).getRuntimeService();
    }

    private static <T> T mock(Class<T> classToMock) {
        return Mockito.mock(classToMock, new ReturnsSmartNulls());
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testFactoryRegistrationDeregistration() throws InterruptedException {
        final BundleContext bundleContext = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        when(preconditions.checkPreconditions()).thenReturn(true);

        try (FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler()) {

            try (RecordingListener listener = RecordingListener.of(bundleContext)) {
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(RRF_REGISTRATION);
            }

            try (RecordingListener listener = RecordingListener.of(bundleContext)) {
                factoryRegistrationHandler.unregisterFactory();
                listener.assertRecorded(RRF_UNREGISTRATION);
            }

            try (RecordingListener listener = RecordingListener.of(bundleContext)) {
                factoryRegistrationHandler.maybeRegisterFactory();
                listener.assertRecorded(RRF_REGISTRATION);
            }
        }
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testConditionChangeLeadingToUnregistration() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);

        try (FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler()) {
            try (final RecordingListener listener = RecordingListener.of(ctx)) {
                when(preconditions.checkPreconditions()).thenReturn(true);
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(RRF_REGISTRATION);
            }

            try (final RecordingListener listener = RecordingListener.of(ctx)) {
                when(preconditions.checkPreconditions()).thenReturn(false);
                factoryRegistrationHandler.maybeRegisterFactory();
                listener.assertRecorded(RRF_UNREGISTRATION);
            }
        }
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testReconfigurationLeadingToUnregsitration() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        when(preconditions.checkPreconditions()).thenReturn(true);

        try (final FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler()) {
            try (RecordingListener listener = RecordingListener.of(ctx)) {
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(RRF_REGISTRATION);
            }

            try (RecordingListener listener = RecordingListener.of(ctx)) {
                final FactoryPreconditions failingPreconditions = mock(FactoryPreconditions.class); // new instance
                when(failingPreconditions.checkPreconditions()).thenReturn(false);
                factoryRegistrationHandler.configure(activator, failingPreconditions);
                listener.assertRecorded(RRF_UNREGISTRATION);
            }
        }
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testReconfigurationWithNoChanges() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        when(preconditions.checkPreconditions()).thenReturn(true);

        try (final FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler()) {
            try (RecordingListener listener = RecordingListener.of(ctx)) {
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(RRF_REGISTRATION);
            }

            try (RecordingListener listener = RecordingListener.of(ctx)) {
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(empty());
            }
        }
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testReconfigurationLeadingToReregistration() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();

        try (final FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler()) {
            try (RecordingListener listener = RecordingListener.of(ctx)) {
                final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
                when(preconditions.checkPreconditions()).thenReturn(true);
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(RRF_REGISTRATION);
            }

            try (RecordingListener listener = RecordingListener.of(ctx)) {
                final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
                when(preconditions.checkPreconditions()).thenReturn(true);
                factoryRegistrationHandler.configure(activator, preconditions);
                listener.assertRecorded(allOf(RRF_REREGISTRATION));
            }
        }
    }

    @RepeatedTest(DEFAULT_TEST_ITERATIONS)
    void testUnregisterOnClose() throws InterruptedException {
        final BundleContext ctx = osgi.bundleContext();
        final FactoryPreconditions preconditions = mock(FactoryPreconditions.class);
        final FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler();

        try (RecordingListener listener = RecordingListener.of(ctx)) {
            when(preconditions.checkPreconditions()).thenReturn(true);
            factoryRegistrationHandler.configure(activator, preconditions);
            listener.assertRecorded(RRF_REGISTRATION);
        }

        try (RecordingListener listener = RecordingListener.of(ctx)) {
            when(preconditions.checkPreconditions()).thenReturn(false);
            factoryRegistrationHandler.close();
            listener.assertRecorded(allOf(RRF_UNREGISTRATION));

            assertThrows(
                    IllegalStateException.class,
                    () -> factoryRegistrationHandler.configure(activator, preconditions),
                    "Reconfiguration is no longer possible after a FactoryRegistrationHandler is closed.");
        }
    }
}
