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
package org.apache.sling.resourceresolver.impl.mapping;

import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class MapEntries implements
    MapEntriesHandler,
    ResourceChangeListener,
    ExternalResourceChangeListener {

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_CONTENT_SUFFIX = "/" + JCR_CONTENT;

    private static final String PROP_REG_EXP = "sling:match";

    public static final String PROP_REDIRECT_EXTERNAL = "sling:redirect";

    public static final String PROP_REDIRECT_EXTERNAL_STATUS = "sling:status";

    /** Key for the global list. */
    private static final String GLOBAL_LIST_KEY = "*";

    public static final String DEFAULT_MAP_ROOT = "/etc/map";

    public static final int DEFAULT_DEFAULT_VANITY_PATH_REDIRECT_STATUS = HttpServletResponse.SC_FOUND;

    @SuppressWarnings("java:S1075") // Repository path
    private static final String JCR_SYSTEM_PATH = "/jcr:system";

    private static final String JCR_SYSTEM_PREFIX = JCR_SYSTEM_PATH + '/';

    static final String ANY_SCHEME_HOST = "[^/]+/[^/]+";

    /** default log */
    private final Logger log = LoggerFactory.getLogger(MapEntries.class);

    private volatile MapConfigurationProvider factory;

    private volatile ResourceResolver resolver;

    private volatile EventAdmin eventAdmin;

    private Optional<ResourceResolverMetrics> metrics;

    private volatile ServiceRegistration<ResourceChangeListener> registration;

    private final Map<String, List<MapEntry>> resolveMapsMap;

    private List<Map.Entry<String, ResourceChange.ChangeType>> resourceChangeQueue;

    private Collection<MapEntry> mapMaps;

    private final ReentrantLock initializing = new ReentrantLock();

    private final StringInterpolationProvider stringInterpolationProvider;

    private final boolean useOptimizeAliasResolution;

    final AliasHandler ah;
    final VanityPathHandler vph;

    public MapEntries(final MapConfigurationProvider factory,
            final BundleContext bundleContext, 
            final EventAdmin eventAdmin, 
            final StringInterpolationProvider stringInterpolationProvider, 
            final Optional<ResourceResolverMetrics> metrics) 
                    throws LoginException, IOException {

        this.resolver = factory.getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"));
        this.factory = factory;
        this.eventAdmin = eventAdmin;

        this.resolveMapsMap = new ConcurrentHashMap<>(Map.of(GLOBAL_LIST_KEY, List.of()));
        this.mapMaps = Collections.<MapEntry> emptyList();
        this.stringInterpolationProvider = stringInterpolationProvider;

        this.registration = registerResourceChangeListener(bundleContext);

        this.ah = new AliasHandler(this.factory, this.initializing, this::doUpdateConfiguration,
            this::sendChangeEvent);

        this.useOptimizeAliasResolution = ah.initializeAliases();

        this.vph = new VanityPathHandler(this.factory, this.resolveMapsMap, this.initializing, this::drainQueue);
        this.vph.initializeVanityPaths();

        this.metrics = metrics;
        if (metrics.isPresent()) {
            // aliases
            this.metrics.get().setNumberOfDetectedConflictingAliasesSupplier(ah.detectedConflictingAliases::get);
            this.metrics.get().setNumberOfDetectedInvalidAliasesSupplier(ah.detectedInvalidAliases::get);
            this.metrics.get().setNumberOfResourcesWithAliasedChildrenSupplier(() -> (long) ah.aliasMapsMap.size());
            this.metrics.get().setNumberOfResourcesWithAliasesOnStartupSupplier(ah.aliasResourcesOnStartup::get);

            // vanity paths
            this.metrics.get().setNumberOfResourcesWithVanityPathsOnStartupSupplier(vph.vanityResourcesOnStartup::get);
            this.metrics.get().setNumberOfVanityPathBloomFalsePositivesSupplier(vph.vanityPathBloomFalsePositives::get);
            this.metrics.get().setNumberOfVanityPathBloomNegativesSupplier(vph.vanityPathBloomNegatives::get);
            this.metrics.get().setNumberOfVanityPathLookupsSupplier(vph.vanityPathLookups::get);
            this.metrics.get().setNumberOfVanityPathsSupplier(vph.vanityCounter::get);
        }
    }

    private ServiceRegistration<ResourceChangeListener> registerResourceChangeListener(final BundleContext bundleContext) {
        final Dictionary<String, Object> props = new Hashtable<>(); // NOSONAR - required by OSGi APIs
        final String[] paths = new String[factory.getObservationPaths().length];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = factory.getObservationPaths()[i].getPath();
        }
        props.put(ResourceChangeListener.PATHS, paths);
        props.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Map Entries Observation");
        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
        log.info("Registering for {}", Arrays.toString(factory.getObservationPaths()));

        this.resourceChangeQueue = Collections.synchronizedList(new LinkedList<>());
        return bundleContext.registerService(ResourceChangeListener.class, this, props);
    }

    private boolean addResource(final String path, final AtomicBoolean resolverRefreshed) {
        this.initializing.lock();

        try {
            this.refreshResolverIfNecessary(resolverRefreshed);
            final Resource resource = this.resolver != null ? resolver.getResource(path) : null;
            if (resource != null) {
                boolean changed = vph.doAddVanity(resource);
                if (this.useOptimizeAliasResolution && resource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                    changed |= ah.doAddAlias(resource);
                }
                return changed;
            }

            return false;
        } finally {
            this.initializing.unlock();
        }
    }

    private boolean updateResource(final String path, final AtomicBoolean resolverRefreshed) {
        final boolean isValidVanityPath = vph.isValidVanityPath(path);
        if ( this.useOptimizeAliasResolution || isValidVanityPath) {
            this.initializing.lock();

            try {
                this.refreshResolverIfNecessary(resolverRefreshed);
                final Resource resource = this.resolver != null ? resolver.getResource(path) : null;
                if (resource != null) {
                    boolean changed = false;
                    if ( isValidVanityPath ) {
                        // we remove the old vanity path first
                        changed |= vph.doRemoveVanity(path);

                        // add back vanity path
                        Resource contentRsrc = null;
                        if ( !resource.getName().equals(JCR_CONTENT)) {
                            // there might be a JCR_CONTENT child resource
                            contentRsrc = resource.getChild(JCR_CONTENT);
                        }
                        changed |= vph.doAddVanity(contentRsrc != null ? contentRsrc : resource);
                    }
                    if (this.useOptimizeAliasResolution) {
                        changed |= this.ah.doUpdateAlias(resource);
                    }

                    return changed;
                }
            } finally {
                this.initializing.unlock();
            }
        }

        return false;
    }

    private boolean removeResource(final String path, final AtomicBoolean resolverRefreshed) {
        boolean changed = false;
        final String actualContentPath = getActualContentPath(path);
        final String actualContentPathPrefix = actualContentPath + "/";

        for (final String target : vph.getVanityPathMappings().keySet()) {
            if (target.startsWith(actualContentPathPrefix) || target.equals(actualContentPath)) {
                changed |= vph.removeVanityPath(target);
            }
        }
        if (this.useOptimizeAliasResolution) {
            final String pathPrefix = path + "/";
            for (final String contentPath : ah.aliasMapsMap.keySet()) {
                if (path.startsWith(contentPath + "/") || path.equals(contentPath)
                        || contentPath.startsWith(pathPrefix)) {
                    changed |= ah.removeAlias(resolver, contentPath, path, () -> this.refreshResolverIfNecessary(resolverRefreshed));
                }
            }
        }
        return changed;
    }

    /**
     * Update the configuration.
     * Does no locking and does not send an event at the end
     */
    private void doUpdateConfiguration() {
        final List<MapEntry> globalResolveMap = new ArrayList<>();
        final SortedMap<String, MapEntry> newMapMaps = new TreeMap<>();
        // load the /etc/map entries into the maps
        loadResolverMap(resolver, globalResolveMap, newMapMaps);
        // load the configuration into the resolver map
        loadConfiguration(factory, globalResolveMap);
        // load the configuration into the mapper map
        loadMapConfiguration(factory, newMapMaps);
        // sort global list and add to map
        Collections.sort(globalResolveMap);
        resolveMapsMap.put(GLOBAL_LIST_KEY, globalResolveMap);
        this.mapMaps = Collections.unmodifiableSet(new TreeSet<>(newMapMaps.values()));
    }

    /**
     * Cleans up this class.
     */
    public void dispose() {

        if (this.registration != null) {
            this.registration.unregister();
            this.registration = null;
        }

        /*
         * Cooperation with doInit: The same lock as used by doInit is acquired
         * thus preventing doInit from running and waiting for a concurrent
         * doInit to terminate. Once the lock has been acquired, the resource
         * resolver is null-ed (thus causing the init to terminate when
         * triggered the right after and prevent the doInit method from doing
         * any thing).
         */

        // wait at most 10 seconds for a notifcation during initialization
        boolean initLocked;
        try {
            initLocked = this.initializing.tryLock(10, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            initLocked = false;
        }

        try {
            if (!initLocked) {
                log.warn("dispose: Could not acquire initialization lock within 10 seconds; ongoing intialization may fail");
            }

            // immediately set the resolver field to null to indicate
            // that we have been disposed (this also signals to the
            // event handler to stop working
            final ResourceResolver oldResolver = this.resolver;
            this.resolver = null;

            if (oldResolver != null) {
                oldResolver.close();
            } else {
                log.warn("dispose: ResourceResolver has already been cleared before; duplicate call to dispose ?");
            }
        } finally {
            if (initLocked) {
                this.initializing.unlock();
            }
        }

        // clear the rest of the fields
        this.factory = null;
        this.eventAdmin = null;
    }

    @Override
    public List<MapEntry> getResolveMaps() {
        final List<MapEntry> entries = new ArrayList<>();
        for (final List<MapEntry> list : this.resolveMapsMap.values()) {
            entries.addAll(list);
        }
        Collections.sort(entries);
        return Collections.unmodifiableList(entries);
    }

    @Override
    public Iterator<MapEntry> getResolveMapsIterator(final String requestPath) {
        String key = null;
        final int firstIndex = requestPath.indexOf('/');
        final int secondIndex = requestPath.indexOf('/', firstIndex + 1);
        if (secondIndex != -1) {
            key = requestPath.substring(secondIndex);
        }

        return new MapEntryIterator(key, resolveMapsMap.get(GLOBAL_LIST_KEY),
                vph::getCurrentMapEntryForVanityPath, this.factory.hasVanityPathPrecedence());
    }

    @Override
    public Collection<MapEntry> getMapMaps() {
        return mapMaps;
    }

    public boolean isOptimizeAliasResolutionEnabled() {
        return this.useOptimizeAliasResolution;
    }

    @Override
    public @NotNull Map<String, Collection<String>> getAliasMap(final String parentPath) {
        Map<String, Collection<String>> aliasMapForParent = ah.aliasMapsMap.get(parentPath);
        return aliasMapForParent != null ? aliasMapForParent : Collections.emptyMap();
    }

    @Override
    public Map<String, List<String>> getVanityPathMappings() {
        return vph.getVanityPathMappings();
    }

    /**
     * Refresh the resource resolver if not already done
     * @param resolverRefreshed Boolean flag containing the state if the resolver
     *                          has been refreshed. True in any case when this
     *                          method returns
     */
    private void refreshResolverIfNecessary(final AtomicBoolean resolverRefreshed) {
        if ( resolverRefreshed.compareAndSet(false, true) ) {
            this.resolver.refresh();
        }
    }

    /**
     * Checks if the path affects the map configuration. If it does
     * the configuration is updated.
     * @param path The changed path (could be add/remove/update)
     * @param hasReloadedConfig If this is already true, the config will not be reloaded
     * @param resolverRefreshed Boolean flag handling resolver refresh
     * @param isDelete If this is a delete event
     * @return {@code true} if the configuration has been updated, {@code false} if
     *         the path does not affect a config change, {@code null} if the config has already
     *         been reloaded.
     */
    private Boolean handleConfigurationUpdate(final String path,
            final AtomicBoolean hasReloadedConfig,
            final AtomicBoolean resolverRefreshed,
            final boolean isDelete) {
        if ( this.factory.isMapConfiguration(path)
             || (isDelete && this.factory.getMapRoot().startsWith(path + "/")) ) {
            if ( hasReloadedConfig.compareAndSet(false, true) ) {
                this.initializing.lock();

                try {
                    if (this.resolver != null) {
                        refreshResolverIfNecessary(resolverRefreshed);
                        doUpdateConfiguration();
                    }
                } finally {
                    this.initializing.unlock();
                }
                return true;
            }
            return null;
        }
        return false;
    }

    // ---------- ResourceChangeListener interface

    /**
     * Handles the change to any of the node properties relevant for vanity URL
     * mappings. The {@link #MapEntries(MapConfigurationProvider, BundleContext, EventAdmin, StringInterpolationProvider, Optional)}
     * constructor makes sure the event listener is registered to only get
     * appropriate events.
     */
    @Override
    public void onChange(final List<ResourceChange> changes) {

        final boolean inStartup = !vph.isReady();

        final AtomicBoolean resolverRefreshed = new AtomicBoolean(false);

        // send the change event only once
        boolean sendEvent = false;

        // the config needs to be reloaded only once
        final AtomicBoolean hasReloadedConfig = new AtomicBoolean(false);

        for (final ResourceChange rc : changes) {

            final ResourceChange.ChangeType type = rc.getType();
            final String path = rc.getPath();

            log.debug("onChange, type={}, path={}", rc.getType(), path);

            // don't care for system area
            if (path.startsWith(JCR_SYSTEM_PREFIX)) {
                continue;
            }

            // during startup: just enqueue the events
            if (inStartup) {
                if (type == ResourceChange.ChangeType.REMOVED || type == ResourceChange.ChangeType.ADDED
                        || type == ResourceChange.ChangeType.CHANGED) {
                    Map.Entry<String, ResourceChange.ChangeType> entry = new SimpleEntry<>(path, type);
                    log.trace("enqueue: {}", entry);
                    resourceChangeQueue.add(entry);
                }
            } else {
                boolean changed = handleResourceChange(type, path, resolverRefreshed, hasReloadedConfig);

                if (changed) {
                    sendEvent = true;
                }
            }
        }

        if (sendEvent) {
            this.sendChangeEvent();
        }
    }

    private boolean handleResourceChange(ResourceChange.ChangeType type, String path, AtomicBoolean resolverRefreshed,
            AtomicBoolean hasReloadedConfig) {
        boolean changed = false;

        // removal of a resource is handled differently
        if (type == ResourceChange.ChangeType.REMOVED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, true);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= removeResource(path, resolverRefreshed);
                }
            }
            // session.move() is handled differently see also SLING-3713 and
        } else if (type == ResourceChange.ChangeType.ADDED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, false);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= addResource(path, resolverRefreshed);
                }
            }
        } else if (type == ResourceChange.ChangeType.CHANGED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, false);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= updateResource(path, resolverRefreshed);
                }
            }
        }

        return changed;
    }

    // ---------- internal

    private String getActualContentPath(final String path){
        final String checkPath;
        if ( path.endsWith(JCR_CONTENT_SUFFIX) ) {
            checkPath = ResourceUtil.getParent(path);
        } else {
            checkPath = path;
        }
        return checkPath;
    }

    /**
     * Send an OSGi event
     */
    private void sendChangeEvent() {
        final EventAdmin local = this.eventAdmin;
        if (local != null) {
            final Event event = new Event(SlingConstants.TOPIC_RESOURCE_RESOLVER_MAPPING_CHANGED,
                            (Dictionary<String, ?>) null);
            local.postEvent(event);
        }
    }

    private void loadResolverMap(final ResourceResolver resolver, final List<MapEntry> entries, final Map<String, MapEntry> mapEntries) {
        // the standard map configuration
        final Resource res = resolver.getResource(this.factory.getMapRoot());
        if (res != null) {
            gather(resolver, entries, mapEntries, res, "");
        }
    }

    private void gather(final ResourceResolver resolver, final List<MapEntry> entries, final Map<String, MapEntry> mapEntries,
                    final Resource parent, final String parentPath) {
        // scheme list
        final Iterator<Resource> children = parent.listChildren();
        while (children.hasNext()) {
            final Resource child = children.next();
            final ValueMap vm = ResourceUtil.getValueMap(child);

            String name = vm.get(PROP_REG_EXP, String.class);
            boolean trailingSlash = false;
            if (name == null) {
                name = child.getName().concat("/");
                trailingSlash = true;
            }
            // Check for placeholders and replace if needed
            name = stringInterpolationProvider.substitute(name);

            final String childPath = parentPath.concat(name);

            // gather the children of this entry (only if child is not end
            // hooked)
            if (!childPath.endsWith("$")) {

                // add trailing slash to child path to append the child
                String childParent = childPath;
                if (!trailingSlash) {
                    childParent = childParent.concat("/");
                }

                gather(resolver, entries, mapEntries, child, childParent);
            }

            // add resolution entries for this node
            MapEntry childResolveEntry = null;
            try{
                childResolveEntry=MapEntry.createResolveEntry(childPath, child, trailingSlash);
            }catch (IllegalArgumentException iae){
                //ignore this entry
                log.debug("ignored entry due exception ",iae);
            }
            if (childResolveEntry != null) {
                entries.add(childResolveEntry);
            }

            // add map entries for this node
            final List<MapEntry> childMapEntries = MapEntry.createMapEntry(childPath, child, trailingSlash);
            if (childMapEntries != null) {
                for (final MapEntry mapEntry : childMapEntries) {
                    addMapEntry(mapEntries, mapEntry.getPattern(), mapEntry.getRedirect()[0], mapEntry.getStatus());
                }
            }

        }
    }


    private void loadConfiguration(final MapConfigurationProvider factory, final List<MapEntry> entries) {
        // virtual uris
        final Map<String, String> virtuals = factory.getVirtualURLMap();
        if (virtuals != null) {
            for (final Entry<String, String> virtualEntry : virtuals.entrySet()) {
                final String extPath = virtualEntry.getKey();
                final String intPath = virtualEntry.getValue();
                if (!extPath.equals(intPath)) {
                    // this regular expression must match the whole URL !!
                    final String url = "^" + ANY_SCHEME_HOST + extPath + "$";
                    final String redirect = intPath;
                    MapEntry mapEntry = getMapEntry(url, -1, redirect);
                    if (mapEntry!=null){
                        entries.add(mapEntry);
                    }
                }
            }
        }

        // URL Mappings
        final Mapping[] mappings = factory.getMappings();
        if (mappings != null) {
            final Map<String, List<String>> map = new HashMap<>();
            for (final Mapping mapping : mappings) {
                if (mapping.mapsInbound()) {
                    final String url = mapping.getTo();
                    final String alias = mapping.getFrom();
                    if (url.length() > 0) {
                        List<String> aliasList = map.get(url);
                        if (aliasList == null) {
                            aliasList = new ArrayList<>();
                            map.put(url, aliasList);
                        }
                        aliasList.add(alias);
                    }
                }
            }

            for (final Entry<String, List<String>> entry : map.entrySet()) {
                MapEntry mapEntry = getMapEntry(ANY_SCHEME_HOST + entry.getKey(), -1, entry.getValue().toArray(new String[0]));
                if (mapEntry!=null){
                    entries.add(mapEntry);
                }
            }
        }
    }

    private void loadMapConfiguration(final MapConfigurationProvider factory, final Map<String, MapEntry> entries) {
        // URL Mappings
        final Mapping[] mappings = factory.getMappings();
        if (mappings != null) {
            for (int i = mappings.length - 1; i >= 0; i--) {
                final Mapping mapping = mappings[i];
                if (mapping.mapsOutbound()) {
                    final String url = mapping.getTo();
                    final String alias = mapping.getFrom();
                    if (!url.equals(alias)) {
                        addMapEntry(entries, alias, url, -1);
                    }
                }
            }
        }

        // virtual uris
        final Map<String, String> virtuals = factory.getVirtualURLMap();
        if (virtuals != null) {
            for (final Entry<String, String> virtualEntry : virtuals.entrySet()) {
                final String extPath = virtualEntry.getKey();
                final String intPath = virtualEntry.getValue();
                if (!extPath.equals(intPath)) {
                    // this regular expression must match the whole URL !!
                    final String path = "^" + intPath + "$";
                    final String url = extPath;
                    addMapEntry(entries, path, url, -1);
                }
            }
        }
    }

    private void addMapEntry(final Map<String, MapEntry> entries, final String path, final String url, final int status) {
        MapEntry entry = entries.get(path);
        if (entry == null) {
            entry = getMapEntry(path, status, url);
        } else {
            final String[] redir = entry.getRedirect();
            final String[] newRedir = new String[redir.length + 1];
            System.arraycopy(redir, 0, newRedir, 0, redir.length);
            newRedir[redir.length] = url;
            entry = getMapEntry(entry.getPattern(), entry.getStatus(), newRedir);
        }
        if (entry!=null){
            entries.put(path, entry);
        }
    };

    @Override
    public void logDisableAliasOptimization() {
        this.ah.logDisableAliasOptimization(null);
    }

    private MapEntry getMapEntry(final String url, final int status, final String... redirect) {
        return getMapEntry(url, status, 0, redirect);
    }

    private MapEntry getMapEntry(final String url, final int status, long order,
            final String... redirect) {
        try {
            return new MapEntry(url, status, false, order, redirect);
        } catch (IllegalArgumentException iae) {
            // ignore this entry
            log.debug("ignored entry for {} due to exception", url, iae);
            return null;
        }
    }


    private void drainQueue() {
        final AtomicBoolean resolverRefreshed = new AtomicBoolean(false);

        // send the change event only once
        boolean sendEvent = false;

        // the config needs to be reloaded only once
        final AtomicBoolean hasReloadedConfig = new AtomicBoolean(false);

        while (!resourceChangeQueue.isEmpty()) {
            Map.Entry<String, ResourceChange.ChangeType> entry = resourceChangeQueue.remove(0);
            final ResourceChange.ChangeType type = entry.getValue();
            final String path = entry.getKey();

            log.trace("drain type={}, path={}", type, path);
            boolean changed = handleResourceChange(type, path, resolverRefreshed, hasReloadedConfig);

            if (changed) {
                sendEvent = true;
            }
        }

        if (sendEvent) {
            sendChangeEvent();
        }
    }
}
