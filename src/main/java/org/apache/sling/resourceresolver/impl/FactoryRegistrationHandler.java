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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class FactoryRegistrationHandler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FactoryRegistrationHandler.class);

    private final ExecutorService factoryRegistrationWorker;

    /**
     * The configurationLock serializes access to the fields {@code activator}
     * and  {@code factoryPreconditions}. Each access to these fields must be
     * guarded by this lock.
     */
    private final ReentrantLock configurationLock = new ReentrantLock();

    /** Access only when holding configurationLock */
    private ResourceResolverFactoryActivator activator;

    /** Access only when holding configurationLock */
    private FactoryPreconditions factoryPreconditions;

    private final AtomicReference<FactoryRegistration> factoryRegistration = new AtomicReference<>(null);

    public FactoryRegistrationHandler() {
        this.factoryRegistrationWorker = Executors.newSingleThreadExecutor(
                r -> new Thread(r, ResourceResolverFactory.class.getSimpleName() + " registration/deregistration"));
    }

    public void configure(ResourceResolverFactoryActivator activator, FactoryPreconditions factoryPreconditions) {
        checkClosed();

        boolean reRegister;
        try {
            configurationLock.lock();
            reRegister = this.activator != activator || !Objects.equals(this.factoryPreconditions, factoryPreconditions);
            LOG.debug("activator differs = {}, factoryPreconditions differ = {}", this.activator != activator, !Objects.equals(this.factoryPreconditions, factoryPreconditions));
            LOG.debug("factoryPreconditions {} vs {}", this.factoryPreconditions, factoryPreconditions);
            this.factoryPreconditions = factoryPreconditions;
            this.activator = activator;
        } finally {
            configurationLock.unlock();
        }

        if (reRegister) {
            unregisterFactory();
            maybeRegisterFactory();
        }
    }

    private void checkClosed() {
        if (factoryRegistrationWorker.isShutdown()) {
            throw new IllegalStateException("FactoryRegistrationHandler is already closed");
        }
    }

    @Override
    public void close() {
        factoryRegistrationWorker.shutdown();
        try {
            if (!factoryRegistrationWorker.awaitTermination(1, TimeUnit.MINUTES)) {
                factoryRegistrationWorker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runWithThreadName("deregistration on close", this::doUnregisterFactory);
    }

    /**
     * Check the preconditions and if it changed, either register factory or unregister
     */
    void maybeRegisterFactory() {
        if (!factoryRegistrationWorker.isShutdown()) {
            LOG.debug("submitting maybeRegisterFactory");
            factoryRegistrationWorker.execute(() -> {
                final boolean preconditionsOk;
                final ResourceResolverFactoryActivator localActivator;
                try {
                    configurationLock.lock();
                    preconditionsOk = factoryPreconditions.checkPreconditions();
                    localActivator = activator;
                } finally {
                    configurationLock.unlock();
                }
                if (preconditionsOk) {
                    final Bundle systemBundle = localActivator.getBundleContext().getBundle(Constants.SYSTEM_BUNDLE_LOCATION);
                    // check system bundle state - if stopping, don't register new factory
                    if (systemBundle != null && systemBundle.getState() != Bundle.STOPPING) {
                        runWithThreadName("registration", () -> this.doRegisterFactory(localActivator));
                    }
                } else {
                    LOG.debug("performing unregisterFactory via maybeRegisterFactory");
                    runWithThreadName("deregistration", this::doUnregisterFactory);
                }
            });
        }
    }

    void unregisterFactory() {
        if (!factoryRegistrationWorker.isShutdown()) {
            LOG.debug("submitting unregisterFactory");
            factoryRegistrationWorker.execute(
                    () -> runWithThreadName("deregistration", this::doUnregisterFactory));
        }
    }

    /**
     * Register the factory.
     */
    private void doRegisterFactory(ResourceResolverFactoryActivator activator) {
        final FactoryRegistration newRegistration = factoryRegistration.updateAndGet(oldRegistration -> {
            LOG.debug("performing registerFactory, factoryRegistration == {}", oldRegistration);
            return Objects.requireNonNullElseGet(oldRegistration,
                    () -> new FactoryRegistration(activator.getBundleContext(), activator));
        });
        LOG.debug("finished performing registerFactory, factoryRegistration == {}", newRegistration);
    }

    /**
     * Unregister the factory (if registered).
     */
    private void doUnregisterFactory() {
        final FactoryRegistration newRegistration = factoryRegistration.updateAndGet(oldRegistration -> {
            LOG.debug("performing unregisterFactory, factoryRegistration == {}", factoryRegistration);
            Optional.ofNullable(oldRegistration)
                    .ifPresent(FactoryRegistration::unregister);
            LOG.debug("setting factoryRegistration = null");
            return null;
        });
        LOG.debug("finished performing unregisterFactory, factoryRegistration == {}", newRegistration);
    }

    private void runWithThreadName(String threadNameSuffix, Runnable task) {
        final String name = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName(ResourceResolverFactory.class.getSimpleName() + " " + threadNameSuffix);
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
            LOG.debug("Unregister runtimeRegistration");
            runtimeRegistration.unregister();

            LOG.debug("Unregister factoryRegistration");
            factoryRegistration.unregister();

            LOG.debug("Unregister commonFactory");
            commonFactory.deactivate();
                        
            LOG.debug("Unregister completed");
        }
    }
}
