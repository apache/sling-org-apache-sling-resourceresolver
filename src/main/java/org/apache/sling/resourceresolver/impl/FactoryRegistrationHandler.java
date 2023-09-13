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

import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.runtime.RuntimeService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FactoryRegistrationHandler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FactoryRegistrationHandler.class);

    private final ResourceResolverFactoryActivator activator;

    private final FactoryPreconditions factoryPreconditions;

    private final ExecutorService factoryRegistrationWorker;

    @SuppressWarnings("java:S3077") // The field is only ever set to null or to a new FactoryRegistration instance, which is safe.
    private volatile FactoryRegistration factoryRegistration;

    public FactoryRegistrationHandler(
            ResourceResolverFactoryActivator activator, FactoryPreconditions factoryPreconditions) {
        this.factoryPreconditions = factoryPreconditions;
        this.activator = activator;
        this.factoryRegistrationWorker = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ResourceResolverFactory registration/deregistration"));
    }

    @Override
    public void close() {
        unregisterFactory();
        factoryRegistrationWorker.shutdown();
        try {
            if (!factoryRegistrationWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                factoryRegistrationWorker.shutdownNow();
                // make sure everything is unregistered, even if
                // the factoryRegistrationWorker did not complete
                doUnregisterFactory();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check the preconditions and if it changed, either register factory or unregister
     */
    void maybeRegisterFactory(final String unavailableName, final String unavailableServicePid) {
        if (!factoryRegistrationWorker.isShutdown()) {
            LOG.debug("submitting maybeRegisterFactory");
            factoryRegistrationWorker.submit(() -> {
                final boolean preconditionsOk = factoryPreconditions.checkPreconditions(unavailableName, unavailableServicePid);
                if ( preconditionsOk && this.factoryRegistration == null ) {
                    // check system bundle state - if stopping, don't register new factory
                    final Bundle systemBundle = activator.getBundleContext().getBundle(Constants.SYSTEM_BUNDLE_LOCATION);
                    if ( systemBundle != null && systemBundle.getState() != Bundle.STOPPING ) {
                        withThreadName("ResourceResolverFactory registration", this::doRegisterFactory);
                    }
                } else if ( !preconditionsOk && this.factoryRegistration != null ) {
                    LOG.debug("performing unregisterFactory via maybeRegisterFactory");
                    withThreadName("ResourceResolverFactory deregistration", this::doUnregisterFactory);
                }
            });
        }
    }

    void unregisterFactory() {
        LOG.debug("submitting unregisterFactory");
        factoryRegistrationWorker.submit(() -> withThreadName("ResourceResolverFactory deregistration",
                this::doUnregisterFactory));
    }

    /**
     * Register the factory.
     */
    private void doRegisterFactory() {
        LOG.debug("performing registerFactory, factoryRegistration == {}", factoryRegistration);
        if (this.factoryRegistration == null) {
            this.factoryRegistration = new FactoryRegistration(activator.getBundleContext(), activator);
        }
        LOG.debug("finished performing registerFactory, factoryRegistration == {}", factoryRegistration);
    }

    /**
     * Unregister the factory (if registered).
     */
    private void doUnregisterFactory() {
        LOG.debug("performing unregisterFactory, factoryRegistration == {}", factoryRegistration);
        if (this.factoryRegistration != null) {
            this.factoryRegistration.unregister();
            this.factoryRegistration = null;
        }
        LOG.debug("finished performing unregisterFactory, factoryRegistration == {}", factoryRegistration);
    }

    private static void withThreadName(String threadName, Runnable task) {
        final String name = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName(threadName);
            task.run();
        } finally {
            Thread.currentThread().setName(name);
        }
    }

    private final class FactoryRegistration {
        /** Registration .*/
        private final ServiceRegistration<ResourceResolverFactory> factoryRegistration;

        /** Runtime registration. */
        private final ServiceRegistration<RuntimeService> runtimeRegistration;

        private final CommonResourceResolverFactoryImpl commonFactory;

        FactoryRegistration(BundleContext context, ResourceResolverFactoryActivator activator) {
            commonFactory = new CommonResourceResolverFactoryImpl(activator);
            commonFactory.activate(context);

            // activate and register factory
            final Dictionary<String, Object> serviceProps = new Hashtable<>();
            serviceProps.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
            serviceProps.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Resource Resolver Factory");
            factoryRegistration = context.registerService(ResourceResolverFactory.class, new ServiceFactory<>() {
                @Override
                public ResourceResolverFactory getService(final Bundle bundle, final ServiceRegistration<ResourceResolverFactory> registration) {
                    if (FactoryRegistrationHandler.this.factoryRegistrationWorker.isShutdown()) {
                        return null;
                    }
                    return new ResourceResolverFactoryImpl(commonFactory, bundle, activator.getServiceUserMapper());
                }

                @Override
                public void ungetService(final Bundle bundle, final ServiceRegistration<ResourceResolverFactory> registration, final ResourceResolverFactory service) {
                    // nothing to do
                }
            }, serviceProps);

            runtimeRegistration = context.registerService(RuntimeService.class, activator.getRuntimeService(), null);
        }

        void unregister() {
            runtimeRegistration.unregister();
            factoryRegistration.unregister();
            commonFactory.deactivate();
        }
    }
}
