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
package org.apache.sling.resourceresolver.impl.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.resource.path.PathSet;
import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.api.resource.runtime.dto.FailureReason;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderDTO;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderFailureDTO;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.legacy.LegacyResourceProviderWhiteboard;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo.Mode;
import org.apache.sling.spi.resource.provider.ObservationReporter;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This service keeps track of all resource providers.
 */
public class ResourceProviderTracker implements ResourceProviderStorageProvider {

    public interface ObservationReporterGenerator {

        ObservationReporter create(final Path path, final PathSet excludes);

        ObservationReporter createProviderReporter();
    }

    public interface ChangeListener {

        void providerAdded();

        void providerRemoved(boolean stateful, boolean used);
    }

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private volatile BundleContext bundleContext;

    @SuppressWarnings("rawtypes")
    private volatile ServiceTracker<ResourceProvider, ServiceReference<ResourceProvider>> tracker;

    private final Map<String, List<ResourceProviderHandler>> handlers = new HashMap<>();

    private final Map<ResourceProviderInfo, FailureReason> invalidProviders = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private final Map<ServiceReference<ResourceProvider>, ResourceProviderInfo> providerInfos =
            new ConcurrentHashMap<>();

    private volatile EventAdmin eventAdmin;

    private volatile ObservationReporterGenerator reporterGenerator;

    private volatile ResourceProviderStorage storage;

    private volatile ObservationReporter providerReporter;

    private volatile ChangeListener listener;

    @SuppressWarnings("rawtypes")
    public void activate(
            final BundleContext bundleContext, final EventAdmin eventAdmin, final ChangeListener listener) {
        this.bundleContext = bundleContext;
        this.eventAdmin = eventAdmin;
        this.listener = listener;
        this.tracker = new ServiceTracker<>(bundleContext, ResourceProvider.class, new ServiceTrackerCustomizer<>() {

            @Override
            public void removedService(
                    final ServiceReference<ResourceProvider> reference,
                    final ServiceReference<ResourceProvider> tracked) {
                unregister(reference);
            }

            @Override
            public void modifiedService(
                    final ServiceReference<ResourceProvider> reference,
                    final ServiceReference<ResourceProvider> tracked) {
                removedService(reference, tracked);
                addingService(reference);
            }

            @Override
            public ServiceReference<ResourceProvider> addingService(
                    final ServiceReference<ResourceProvider> reference) {
                register(reference);
                return reference;
            }
        });
        this.tracker.open();
    }

    public void deactivate() {
        this.listener = null;
        this.eventAdmin = null;
        this.providerReporter = null;
        if (this.tracker != null) {
            this.tracker.close();
            this.tracker = null;
        }
        this.handlers.clear();
        this.invalidProviders.clear();
    }

    public void setObservationReporterGenerator(final ObservationReporterGenerator generator) {
        this.providerReporter = generator.createProviderReporter();
        synchronized (this.handlers) {
            this.reporterGenerator = generator;
            for (List<ResourceProviderHandler> list : handlers.values()) {
                if (!list.isEmpty()) {
                    final ResourceProviderHandler h = list.get(0);
                    if (h != null) {
                        updateProviderContext(h);
                        h.update();
                    }
                }
            }
        }
    }

    /**
     * Register a new resource provider
     * @param ref The service reference
     */
    @SuppressWarnings("unchecked")
    private void register(@SuppressWarnings("rawtypes") final ServiceReference<ResourceProvider> ref) {
        // create a info
        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        this.providerInfos.put(ref, info);

        // check validity
        if (!info.isValid()) {
            logger.warn("Ignoring invalid resource provider {}", info);
            this.invalidProviders.put(info, FailureReason.invalid);
        } else {
            // get the service
            ResourceProvider<Object> provider = null;
            try {
                provider = (ResourceProvider<Object>) this.bundleContext.getService(ref);
            } catch (final IllegalStateException ise) {
                // ignore
            }
            if (provider == null) {
                logger.warn("Ignoring resource provider as the service is not gettable {}", info);
                this.invalidProviders.put(info, FailureReason.service_not_gettable);
            } else {
                logger.debug("Registering new resource provider {}", info);
                this.add(new ResourceProviderHandler(info, provider));
            }
        }
    }

    /**
     * Unregister a resource provider
     * @param ref The service reference
     * @param info The resource provider info
     */
    private void unregister(@SuppressWarnings("rawtypes") final ServiceReference<ResourceProvider> ref) {
        final ResourceProviderInfo info = this.providerInfos.remove(ref);
        if (info == null) {
            return; // this should never happen
        }
        final boolean isInvalid = this.invalidProviders.remove(info) != null;
        if (!isInvalid) {
            logger.debug("Unregistering resource provider {}", info);
            this.remove(info);
            try {
                this.bundleContext.ungetService(ref);
            } catch (final IllegalStateException ise) {
                // ignore
            }
        }
    }

    /**
     * Add a new resource provider.
     * @param handler The resource provider handler
     */
    private void add(final ResourceProviderHandler handler) {
        final List<ProviderEvent> events = new ArrayList<>();
        boolean providerAdded = false;
        ResourceProviderHandler deactivateHandler = null;

        // update list of active handlers
        synchronized (this.handlers) {
            final List<ResourceProviderHandler> matchingHandlers =
                    this.handlers.computeIfAbsent(handler.getInfo().getPath(), key -> new ArrayList<>());
            matchingHandlers.add(handler);
            Collections.sort(matchingHandlers);

            if (matchingHandlers.get(0) == handler) {
                this.storage = null;
                providerAdded = true;
                events.add(new ProviderEvent(true, handler.getInfo()));
                this.activate(handler);
                if (matchingHandlers.size() > 1) {
                    deactivateHandler = matchingHandlers.get(1);
                    this.deactivate(deactivateHandler);
                    events.add(new ProviderEvent(false, deactivateHandler.getInfo()));
                }
            }
        }

        // update change listener (only once)
        final ChangeListener cl = this.listener;
        if (cl != null) {
            if (deactivateHandler != null) {
                cl.providerRemoved(
                        deactivateHandler.getInfo().getAuthType() != AuthType.no, deactivateHandler.isUsed());
            } else if (providerAdded) {
                cl.providerAdded();
            }
        }

        // send events
        this.postEvents(events);
    }

    /**
     * Remove a resource provider.
     * @param info The resource provider info.
     */
    private void remove(final ResourceProviderInfo info) {
        final List<ProviderEvent> events = new ArrayList<>();

        // remove provider from handlers and if the provider is active (first handler)
        // keep the reference for deactivation
        ResourceProviderHandler deactivateHandler = null;
        synchronized (this.handlers) {
            final List<ResourceProviderHandler> matchingHandlers = this.handlers.get(info.getPath());
            if (matchingHandlers != null) {
                final Iterator<ResourceProviderHandler> it = matchingHandlers.iterator();
                boolean first = true;
                while (it.hasNext()) {
                    final ResourceProviderHandler h = it.next();
                    if (h.getInfo() == info) {
                        it.remove();
                        if (first) {
                            this.storage = null;
                            deactivateHandler = h;
                        }
                        if (matchingHandlers.isEmpty()) {
                            this.handlers.remove(info.getPath());
                        }

                        break;
                    }
                    first = false;
                }
            }
            if (deactivateHandler != null) {
                this.deactivate(deactivateHandler);
                events.add(new ProviderEvent(false, info));

                // check if we can activate another handler
                if (!matchingHandlers.isEmpty()) {
                    final ResourceProviderHandler addingProvider = matchingHandlers.get(0);
                    this.activate(addingProvider);
                    events.add(new ProviderEvent(true, addingProvider.getInfo()));
                }
            }
        }

        // update change listener (only once)
        final ChangeListener cl = this.listener;
        if (cl != null && deactivateHandler != null) {
            cl.providerRemoved(info.getAuthType() != AuthType.no, deactivateHandler.isUsed());
        }

        // send events
        this.postEvents(events);
    }

    /**
     * Activate a resource provider
     * @param handler The provider handler
     */
    private void activate(final ResourceProviderHandler handler) {
        updateProviderContext(handler);
        handler.activate();
        logger.debug("Activated resource provider {}", handler.getInfo());
    }

    /**
     * Deactivate a resource provider
     * @param handler The provider handler
     */
    private void deactivate(final ResourceProviderHandler handler) {
        handler.deactivate();
        logger.debug("Deactivated resource provider {}", handler.getInfo());
    }

    /**
     * Post a change event through the event admin
     * @param event
     */
    private void postOSGiEvent(final ProviderEvent event) {
        final EventAdmin ea = this.eventAdmin;
        if (ea != null) {
            final Dictionary<String, Object> eventProps = new Hashtable<>();
            eventProps.put(SlingConstants.PROPERTY_PATH, event.path);
            if (event.pid != null) {
                eventProps.put(Constants.SERVICE_PID, event.pid);
            }
            ea.postEvent(new Event(
                    event.isAdd
                            ? SlingConstants.TOPIC_RESOURCE_PROVIDER_ADDED
                            : SlingConstants.TOPIC_RESOURCE_PROVIDER_REMOVED,
                    eventProps));
        }
    }

    /**
     * Post a change event for a resource provider change
     * @param type The change type
     * @param info The resource provider
     */
    private void postResourceProviderChange(final ProviderEvent event) {
        final ObservationReporter or = this.providerReporter;
        if (or != null) {
            final ResourceChange change = new ResourceChange(
                    event.isAdd ? ChangeType.PROVIDER_ADDED : ChangeType.PROVIDER_REMOVED, event.path, false);
            or.reportChanges(Collections.singletonList(change), false);
        }
    }

    public void fill(final RuntimeDTO dto) {
        final List<ResourceProviderDTO> dtos = new ArrayList<>();
        final List<ResourceProviderFailureDTO> failures = new ArrayList<>();

        synchronized (this.handlers) {
            for (final List<ResourceProviderHandler> handlers : this.handlers.values()) {
                boolean isFirst = true;
                for (final ResourceProviderHandler h : handlers) {
                    final ResourceProviderDTO d;
                    if (isFirst) {
                        d = new ResourceProviderDTO();
                        dtos.add(d);
                        isFirst = false;
                    } else {
                        d = new ResourceProviderFailureDTO();
                        ((ResourceProviderFailureDTO) d).reason = FailureReason.shadowed;
                        failures.add((ResourceProviderFailureDTO) d);
                    }
                    fill(d, h);
                }
            }
        }
        for (final Map.Entry<ResourceProviderInfo, FailureReason> entry : this.invalidProviders.entrySet()) {
            final ResourceProviderFailureDTO d = new ResourceProviderFailureDTO();
            fill(d, entry.getKey());
            d.reason = entry.getValue();
            failures.add(d);
        }
        dto.providers = dtos.toArray(new ResourceProviderDTO[dtos.size()]);
        dto.failedProviders = failures.toArray(new ResourceProviderFailureDTO[failures.size()]);
    }

    @Override
    public ResourceProviderStorage getResourceProviderStorage() {
        ResourceProviderStorage result = storage;
        if (result == null) {
            synchronized (this.handlers) {
                if (storage == null) {
                    final List<ResourceProviderHandler> handlerList = new ArrayList<>();
                    for (List<ResourceProviderHandler> list : handlers.values()) {
                        if (!list.isEmpty()) {
                            final ResourceProviderHandler h = list.get(0);
                            if (h != null && h.getResourceProvider() != null) {
                                handlerList.add(h);
                            }
                        }
                    }
                    storage = new ResourceProviderStorage(handlerList);
                }
                result = storage;
            }
        }
        return result;
    }

    private void fill(final ResourceProviderDTO d, final ResourceProviderInfo info) {
        d.adaptable = info.isAdaptable();
        d.attributable = info.isAttributable();
        d.authType = info.getAuthType();
        d.modifiable = info.isModifiable();
        d.name = info.getName();
        d.path = info.getPath();
        d.refreshable = info.isRefreshable();
        d.serviceId = (Long) info.getServiceReference().getProperty(Constants.SERVICE_ID);
        d.supportsQueryLanguage = false;
        d.useResourceAccessSecurity = info.getUseResourceAccessSecurity();
    }

    private void fill(final ResourceProviderDTO d, final ResourceProviderHandler handler) {
        fill(d, handler.getInfo());
        final ResourceProvider<?> provider = handler.getResourceProvider();
        if (provider != null) {
            d.supportsQueryLanguage = provider.getQueryLanguageProvider() != null;
        }
    }

    private void updateProviderContext(final ResourceProviderHandler handler) {
        final Set<String> excludedPaths = new HashSet<>();
        final Path handlerPath = new Path(handler.getPath());

        for (final Map.Entry<String, List<ResourceProviderHandler>> entry : handlers.entrySet()) {
            if (entry.getValue().get(0).getInfo().getMode() == Mode.PASSTHROUGH) {
                continue;
            }
            if (!handler.getPath().equals(entry.getKey()) && handlerPath.matches(entry.getKey())) {
                excludedPaths.add(entry.getKey());
            }
        }

        final PathSet excludedPathSet = PathSet.fromStringCollection(excludedPaths);
        handler.getProviderContext().update(reporterGenerator.create(handlerPath, excludedPathSet), excludedPathSet);
    }

    private void postEvents(final List<ProviderEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        if (this.listener == null && this.providerReporter == null) {
            return;
        }
        final Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                for (final ProviderEvent e : events) {
                    postOSGiEvent(e);
                    postResourceProviderChange(e);
                }
            }
        });
        t.setName("Apache Sling Resource Provider Change Notifier");
        t.setDaemon(true);

        t.start();
    }

    private static final class ProviderEvent {
        public final boolean isAdd;
        public final Object pid;
        public final String path;

        public ProviderEvent(final boolean isAdd, final ResourceProviderInfo info) {
            this.isAdd = isAdd;
            this.path = info.getPath();
            Object pid = info.getServiceReference().getProperty(Constants.SERVICE_PID);
            if (pid == null) {
                pid = info.getServiceReference().getProperty(LegacyResourceProviderWhiteboard.ORIGINAL_SERVICE_PID);
            }
            this.pid = pid;
        }
    }
}
