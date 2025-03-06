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
import org.apache.sling.api.resource.QuerySyntaxException;
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
import org.jetbrains.annotations.Nullable;
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
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class MapEntries implements
    MapEntriesHandler,
    ResourceChangeListener,
    ExternalResourceChangeListener {

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_CONTENT_PREFIX = JCR_CONTENT + "/";

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

    /**
     * The key of the map is the parent path, while the value is a map with the the resource name as key and the actual aliases as values)
     */
    private Map<String, Map<String, Collection<String>>> aliasMapsMap;

    private final AtomicLong aliasResourcesOnStartup;
    private final AtomicLong detectedConflictingAliases;
    private final AtomicLong detectedInvalidAliases;

    // keep track of some defunct aliases for diagnostics (thus size-limited)
    private static final int MAX_REPORT_DEFUNCT_ALIASES = 50;

    private final ReentrantLock initializing = new ReentrantLock();

    private final StringInterpolationProvider stringInterpolationProvider;

    private final boolean useOptimizeAliasResolution;

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
        this.aliasMapsMap = new ConcurrentHashMap<>();
        this.stringInterpolationProvider = stringInterpolationProvider;

        this.aliasResourcesOnStartup = new AtomicLong(0);
        this.detectedConflictingAliases = new AtomicLong(0);
        this.detectedInvalidAliases = new AtomicLong(0);

        this.useOptimizeAliasResolution = initializeAliases();

        this.registration = registerResourceChangeListener(bundleContext);

        this.vph = new VanityPathHandler(this.factory, this.resolveMapsMap, this.initializing, this::drainQueue);
        this.vph.initializeVanityPaths();

        this.metrics = metrics;
        if (metrics.isPresent()) {
            // aliases
            this.metrics.get().setNumberOfDetectedConflictingAliasesSupplier(detectedConflictingAliases::get);
            this.metrics.get().setNumberOfDetectedInvalidAliasesSupplier(detectedInvalidAliases::get);
            this.metrics.get().setNumberOfResourcesWithAliasedChildrenSupplier(() -> (long) aliasMapsMap.size());
            this.metrics.get().setNumberOfResourcesWithAliasesOnStartupSupplier(aliasResourcesOnStartup::get);

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
                    changed |= doAddAlias(resource);
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
                        changed |= doUpdateAlias(resource);
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
            for (final String contentPath : this.aliasMapsMap.keySet()) {
                if (path.startsWith(contentPath + "/") || path.equals(contentPath)
                        || contentPath.startsWith(pathPrefix)) {
                    changed |= removeAlias(contentPath, path, resolverRefreshed);
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

    // Alias handling code

    /**
     * Actual initializer. Guards itself against concurrent use by using a
     * ReentrantLock. Does nothing if the resource resolver has already been
     * null-ed.
     * @return true if the optimizedAliasResolution is enabled, false otherwise
     */
    protected boolean initializeAliases() {

        this.initializing.lock();
        try {
            final ResourceResolver resolver = this.resolver;
            final MapConfigurationProvider factory = this.factory;
            if (resolver == null || factory == null) {
                return this.factory.isOptimizeAliasResolutionEnabled();
            }

            List<String> conflictingAliases = new ArrayList<>();
            List<String> invalidAliases = new ArrayList<>();

            boolean isOptimizeAliasResolutionEnabled = this.factory.isOptimizeAliasResolutionEnabled();

            //optimization made in SLING-2521
            if (isOptimizeAliasResolutionEnabled) {
                try {
                    final Map<String, Map<String, Collection<String>>> loadedMap = this.loadAliases(resolver, conflictingAliases, invalidAliases);
                    this.aliasMapsMap = loadedMap;

                    // warn if there are more than a few defunct aliases
                    if (conflictingAliases.size() >= MAX_REPORT_DEFUNCT_ALIASES) {
                        log.warn("There are {} conflicting aliases; excerpt: {}",  conflictingAliases.size(), conflictingAliases);
                    } else if (!conflictingAliases.isEmpty()) {
                        log.warn("There are {} conflicting aliases: {}",  conflictingAliases.size(), conflictingAliases);
                    }
                    if (invalidAliases.size() >= MAX_REPORT_DEFUNCT_ALIASES) {
                        log.warn("There are {} invalid aliases; excerpt: {}", invalidAliases.size(), invalidAliases);
                    } else if (!invalidAliases.isEmpty()) {
                        log.warn("There are {} invalid aliases: {}", invalidAliases.size(), invalidAliases);
                    }
                } catch (final Exception e) {

                    logDisableAliasOptimization(e);

                    // disable optimize alias resolution
                    isOptimizeAliasResolutionEnabled = false;
                }
            }

            doUpdateConfiguration();

            sendChangeEvent();

            return isOptimizeAliasResolutionEnabled;

        } finally {

            this.initializing.unlock();

        }
    }

    private boolean doAddAlias(final Resource resource) {
        return loadAlias(resource, this.aliasMapsMap, null, null);
    }

    /**
     * Remove all aliases for the content path
     * @param contentPath The content path
     * @param path Optional sub path of the vanity path
     * @return {@code true} if a change happened
     */
    private boolean removeAlias(final String contentPath, final String path, final AtomicBoolean resolverRefreshed) {
        // if path is specified we first need to find out if it is
        // a direct child of vanity path but not jcr:content, or a jcr:content child of a direct child
        // otherwise we can discard the event
        boolean handle = true;
        final String resourcePath;
        if ( path != null  && path.length() > contentPath.length()) {
            final String subPath = path.substring(contentPath.length() + 1);
            final int firstSlash = subPath.indexOf('/');
            if ( firstSlash == -1 ) {
                if ( subPath.equals(JCR_CONTENT) ) {
                    handle = false;
                }
                resourcePath = path;
            } else if ( subPath.lastIndexOf('/') == firstSlash) {
                if ( subPath.startsWith(JCR_CONTENT_PREFIX) || !subPath.endsWith(JCR_CONTENT_SUFFIX) ) {
                    handle = false;
                }
                resourcePath = ResourceUtil.getParent(path);
            } else {
                handle = false;
                resourcePath = null;
            }
        }
        else {
            resourcePath = contentPath;
        }
        if ( !handle ) {
            return false;
        }

        this.initializing.lock();
        try {
            final Map<String, Collection<String>> aliasMapEntry = aliasMapsMap.get(contentPath);
            if (aliasMapEntry != null) {
                this.refreshResolverIfNecessary(resolverRefreshed);

                String prefix = contentPath.endsWith("/") ? contentPath : contentPath + "/";
                if (aliasMapEntry.entrySet().removeIf(e -> (prefix + e.getKey()).startsWith(resourcePath)) &&  (aliasMapEntry.isEmpty())) {
                    this.aliasMapsMap.remove(contentPath);
                }

                Resource containingResource = this.resolver != null ? this.resolver.getResource(resourcePath) : null;

                if (containingResource != null) {
                    if (containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                        doAddAlias(containingResource);
                    }
                    final Resource child = containingResource.getChild(JCR_CONTENT);
                    if (child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                        doAddAlias(child);
                    }
                }
            }
            return aliasMapEntry != null;
        } finally {
            this.initializing.unlock();
        }
    }

    /**
     * Update alias from a resource
     * @param resource The resource
     * @return {@code true} if any change
     */
    private boolean doUpdateAlias(final Resource resource) {

        // resource containing the alias
        final Resource containingResource = getResourceToBeAliased(resource);

        if ( containingResource != null ) {
            final String containingResourceName = containingResource.getName();
            final String parentPath = ResourceUtil.getParent(containingResource.getPath());

            final Map<String, Collection<String>> aliasMapEntry = parentPath == null ? null : aliasMapsMap.get(parentPath);
            if (aliasMapEntry != null) {
                aliasMapEntry.remove(containingResourceName);
                if (aliasMapEntry.isEmpty()) {
                    this.aliasMapsMap.remove(parentPath);
                }
            }

            boolean changed = aliasMapEntry != null;

            if ( containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS) ) {
                changed |= doAddAlias(containingResource);
            }
            final Resource child = containingResource.getChild(JCR_CONTENT);
            if ( child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS) ) {
                changed |= doAddAlias(child);
            }

            return changed;
        } else {
            log.warn("containingResource is null for alias on {}, skipping.", resource.getPath());
        }

        return false;
    }

    @Override
    public @NotNull Map<String, Collection<String>> getAliasMap(final String parentPath) {
        Map<String, Collection<String>> aliasMapForParent = aliasMapsMap.get(parentPath);
        return aliasMapForParent != null ? aliasMapForParent : Collections.emptyMap();
    }

    @Override
    public Map<String, List<String>> getVanityPathMappings() {
        return vph.getVanityPathMappings();
    }

    /**
     * Load aliases - Search for all nodes (except under /jcr:system) below
     * configured alias locations having the sling:alias property
     */
    private Map<String, Map<String, Collection<String>>> loadAliases(final ResourceResolver resolver,
                                                                     List<String> conflictingAliases, List<String> invalidAliases) {

        final Map<String, Map<String, Collection<String>>> map = new ConcurrentHashMap<>();
        final String baseQueryString = generateAliasQuery();

        Iterator<Resource> it;
        try {
            final String queryStringWithSort = baseQueryString + " AND FIRST([sling:alias]) >= '%s' ORDER BY FIRST([sling:alias])";
            it = new PagedQueryIterator("alias", "sling:alias", resolver, queryStringWithSort, 2000);
        } catch (QuerySyntaxException ex) {
            log.debug("sort with first() not supported, falling back to base query", ex);
            it = queryUnpaged("alias", baseQueryString);
        } catch (UnsupportedOperationException ex) {
            log.debug("query failed as unsupported, retrying without paging/sorting", ex);
            it = queryUnpaged("alias", baseQueryString);
        }

        log.debug("alias initialization - start");
        long count = 0;
        long processStart = System.nanoTime();
        while (it.hasNext()) {
            count += 1;
            loadAlias(it.next(), map, conflictingAliases, invalidAliases);
        }
        long processElapsed = System.nanoTime() - processStart;
        long resourcePerSecond = (count * TimeUnit.SECONDS.toNanos(1) / (processElapsed == 0 ? 1 : processElapsed));

        String diagnostics = "";
        if (it instanceof PagedQueryIterator) {
            PagedQueryIterator pit = (PagedQueryIterator)it;

            if (!pit.getWarning().isEmpty()) {
                log.warn(pit.getWarning());
            }

            diagnostics = pit.getStatistics();
        }

        log.info("alias initialization - completed, processed {} resources with sling:alias properties in {}ms (~{} resource/s){}",
                count, TimeUnit.NANOSECONDS.toMillis(processElapsed), resourcePerSecond, diagnostics);

        this.aliasResourcesOnStartup.set(count);

        return map;
    }

    /*
     * generate alias query based on configured alias locations
     */
    private String generateAliasQuery() {
        final Set<String> allowedLocations = this.factory.getAllowedAliasLocations();

        StringBuilder baseQuery = new StringBuilder("SELECT [sling:alias] FROM [nt:base] WHERE");

        if (allowedLocations.isEmpty()) {
            baseQuery.append(" ").append(QueryBuildHelper.excludeSystemPath());
        } else {
            Iterator<String> pathIterator = allowedLocations.iterator();
            baseQuery.append(" (");
            String sep = "";
            while (pathIterator.hasNext()) {
                String prefix = pathIterator.next();
                baseQuery.append(sep).append("isdescendantnode('").append(QueryBuildHelper.escapeString(prefix)).append("')");
                sep = " OR ";
            }
            baseQuery.append(")");
        }

        baseQuery.append(" AND [sling:alias] IS NOT NULL");
        return baseQuery.toString();
    }

    /**
     * Load alias given a resource
     */
    private boolean loadAlias(final Resource resource, Map<String, Map<String, Collection<String>>> map,
                              List<String> conflictingAliases, List<String> invalidAliases) {

        // resource containing the alias
        final Resource containingResource = getResourceToBeAliased(resource);

        if (containingResource == null) {
            log.warn("containingResource is null for alias on {}, skipping.", resource.getPath());
            return false;
        } else {
            final Resource parent = containingResource.getParent();

            if (parent == null) {
                log.warn("{} is null for alias on {}, skipping.", containingResource == resource ? "parent" : "grandparent",
                        resource.getPath());
                return false;
            } else {
                final String[] aliasArray = resource.getValueMap().get(ResourceResolverImpl.PROP_ALIAS, String[].class);
                if (aliasArray == null) {
                    return false;
                } else {
                    return loadAliasFromArray(aliasArray, map, conflictingAliases, invalidAliases, containingResource.getName(),
                            parent.getPath());
                }
            }
        }
    }

    /**
     * Load alias given a an alias array, return success flag.
     */
    private boolean loadAliasFromArray(final String[] aliasArray, Map<String, Map<String, Collection<String>>> map,
                                       List<String> conflictingAliases, List<String> invalidAliases, final String resourceName, final String parentPath) {

        boolean hasAlias = false;

        log.debug("Found alias, total size {}", aliasArray.length);

        // the order matters here, the first alias in the array must come first
        for (final String alias : aliasArray) {
            if (isAliasInvalid(alias)) {
                long invalids = detectedInvalidAliases.incrementAndGet();
                log.warn("Encountered invalid alias '{}' under parent path '{}' (total so far: {}). Refusing to use it.",
                        alias, parentPath, invalids);
                if (invalidAliases != null && invalids < MAX_REPORT_DEFUNCT_ALIASES) {
                    invalidAliases.add((String.format("'%s'/'%s'", parentPath, alias)));
                }
            } else {
                Map<String, Collection<String>> parentMap = map.computeIfAbsent(parentPath, key -> new ConcurrentHashMap<>());
                Optional<String> siblingResourceNameWithDuplicateAlias = parentMap.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(resourceName)) // ignore entry for the current resource
                        .filter(entry -> entry.getValue().contains(alias))
                        .findFirst().map(Map.Entry::getKey);
                if (siblingResourceNameWithDuplicateAlias.isPresent()) {
                    long conflicting = detectedConflictingAliases.incrementAndGet();
                    log.warn(
                            "Encountered duplicate alias '{}' under parent path '{}'. Refusing to replace current target '{}' with '{}' (total duplicated aliases so far: {}).",
                            alias, parentPath, siblingResourceNameWithDuplicateAlias.get(), resourceName, conflicting);
                    if (conflictingAliases != null && conflicting < MAX_REPORT_DEFUNCT_ALIASES) {
                        conflictingAliases.add((String.format("'%s': '%s'/'%s' vs '%s'/'%s'", parentPath, resourceName,
                                alias, siblingResourceNameWithDuplicateAlias.get(), alias)));
                    }
                } else {
                    Collection<String> existingAliases = parentMap.computeIfAbsent(resourceName, name -> new CopyOnWriteArrayList<>());
                    existingAliases.add(alias);
                    hasAlias = true;
                }
            }
        }

        return hasAlias;
    }

    /**
     * Given a resource, check whether the name is "jcr:content", in which case return the parent resource
     * @param resource resource to check
     * @return parent of jcr:content resource (may be null), otherwise the resource itself
     */
    @Nullable private Resource getResourceToBeAliased(Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            return resource.getParent();
        } else {
            return resource;
        }
    }

    /**
     * Check alias syntax
     */
    private static boolean isAliasInvalid(String alias) {
        boolean invalid = alias.equals("..") || alias.equals(".") || alias.isEmpty();
        if (!invalid) {
            for (final char c : alias.toCharArray()) {
                // invalid if / or # or a ?
                if (c == '/' || c == '#' || c == '?') {
                    invalid = true;
                    break;
                }
            }
        }
        return invalid;
    }

    private Iterator<Resource> queryUnpaged(String subject, String query) {
        log.debug("start {} query: {}", subject, query);
        long queryStart = System.nanoTime();
        final Iterator<Resource> it = resolver.findResources(query, "JCR-SQL2");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end {} query; elapsed {}ms", subject, TimeUnit.NANOSECONDS.toMillis(queryElapsed));
        return it;
    }


    private final AtomicLong lastTimeLogged = new AtomicLong(-1);

    private final long LOGGING_ERROR_PERIOD = 1000 * 60 * 5;

    @Override
    public void logDisableAliasOptimization() {
        this.logDisableAliasOptimization(null);
    }

    private void logDisableAliasOptimization(final Exception e) {
        if ( e != null ) {
            log.error("Unexpected problem during initialization of optimize alias resolution. Therefore disabling optimize alias resolution. Please fix the problem.", e);
        } else {
            final long now = System.currentTimeMillis();
            if ( now - lastTimeLogged.getAndSet(now) > LOGGING_ERROR_PERIOD) {
                log.error("A problem occured during initialization of optimize alias resolution. Optimize alias resolution is disabled. Check the logs for the reported problem.", e);
            }
        }
    }
}
