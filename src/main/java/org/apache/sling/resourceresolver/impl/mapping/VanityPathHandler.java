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

import org.apache.commons.collections4.map.LRUMap;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * All things related to the handling of vanity paths.
 */
public class VanityPathHandler {

    private static final String JCR_CONTENT = "jcr:content";
    private static final String JCR_CONTENT_SUFFIX = "/" + JCR_CONTENT;
    private static final String JCR_SYSTEM_PATH = "/jcr:system";
    private static final String JCR_SYSTEM_PREFIX = JCR_SYSTEM_PATH + '/';

    public static final String PROP_REDIRECT_EXTERNAL = "sling:redirect";
    public static final String PROP_REDIRECT_EXTERNAL_REDIRECT_STATUS = "sling:redirectStatus";
    public static final String PROP_VANITY_PATH = "sling:vanityPath";
    public static final String PROP_VANITY_ORDER = "sling:vanityOrder";

    private static final String ANY_SCHEME_HOST = "[^/]+/[^/]+";

    private static final int VANITY_BLOOM_FILTER_MAX_ENTRIES = 10000000;

    final AtomicLong vanityCounter = new AtomicLong(0);
    final AtomicLong vanityResourcesOnStartup = new AtomicLong(0);
    final AtomicLong vanityPathLookups = new AtomicLong(0);
    final AtomicLong vanityPathBloomNegatives = new AtomicLong(0);
    final AtomicLong vanityPathBloomFalsePositives = new AtomicLong(0);

    private final AtomicLong temporaryResolveMapsMapHits = new AtomicLong();
    private final AtomicLong temporaryResolveMapsMapMisses = new AtomicLong();
    private final AtomicBoolean vanityPathsProcessed = new AtomicBoolean(false);

    private final Logger log = LoggerFactory.getLogger(VanityPathHandler.class);

    private final MapConfigurationProvider factory;
    private byte[] vanityBloomFilter;

    private Map<String, List<String>> vanityTargets = Collections.emptyMap();
    private final Map<String, List<MapEntry>> resolveMapsMap;

    // special singleton entry for negative cache entries
    private final List<MapEntry> noMapEntries = Collections.emptyList();

    // Temporary cache for use while doing async vanity path query
    private Map<String, List<MapEntry>> temporaryResolveMapsMap;

    private final ReentrantLock initializing;

    private final Runnable drain;

    public VanityPathHandler(MapConfigurationProvider factory, Map<String, List<MapEntry>> resolveMapsMap,
                             ReentrantLock initializing, Runnable drain) {
        this.factory = factory;
        this.resolveMapsMap = resolveMapsMap;
        this.initializing = initializing;
        this.drain = drain;
    }

    public boolean isReady() {
        return this.vanityPathsProcessed.get();
    }

    public Map<String, List<String>> getVanityPathMappings() {
        return Collections.unmodifiableMap(vanityTargets);
    }

    /**
     * Actual vanity path initializer. Guards itself against concurrent use by
     * using a ReentrantLock. Does nothing if the resource resolver has already
     * been null-ed.
     */
    protected void initializeVanityPaths() {
        this.initializing.lock();
        try {
            if (this.factory.isVanityPathEnabled()) {
                vanityPathsProcessed.set(false);
                this.vanityBloomFilter = createVanityBloomFilter();
                VanityPathInitializer vpi = new VanityPathInitializer(this.factory);

                if (this.factory.isVanityPathCacheInitInBackground()) {
                    this.log.debug("bg init starting");
                    Thread vpinit = new Thread(vpi, "VanityPathInitializer");
                    vpinit.start();
                } else {
                    vpi.run();
                }
            }
        } finally {
            this.initializing.unlock();
        }
    }

    boolean removeVanityPath(final String path) {
        this.initializing.lock();
        try {
            return doRemoveVanity(path);
        } finally {
            this.initializing.unlock();
        }
    }

    private class VanityPathInitializer implements Runnable {

        private int SIZELIMIT = 10000;

        private final MapConfigurationProvider factory;

        public VanityPathInitializer(MapConfigurationProvider factory) {
            this.factory = factory;
        }

        @Override
        public void run() {
            try {
                temporaryResolveMapsMap = Collections.synchronizedMap(new LRUMap<>(SIZELIMIT));
                execute();
            } catch (Exception ex) {
                log.error("vanity path initializer thread terminated with an exception", ex);
            }
        }

        private void execute() {
            try (ResourceResolver resolver = factory
                    .getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"))) {

                long initStart = System.nanoTime();
                log.debug("vanity path initialization - start");

                vanityTargets = loadVanityPaths(resolver);

                // process pending event
                VanityPathHandler.this.drain.run();

                vanityPathsProcessed.set(true);

                // drain once more in case more events have arrived
                VanityPathHandler.this.drain.run();

                long initElapsed = System.nanoTime() - initStart;
                long resourcesPerSecond = (vanityResourcesOnStartup.get() * TimeUnit.SECONDS.toNanos(1) / (initElapsed == 0 ? 1 : initElapsed));

                log.info(
                        "vanity path initialization - completed, processed {} resources with sling:vanityPath properties in {}ms (~{} resource/s)",
                        vanityResourcesOnStartup.get(), TimeUnit.NANOSECONDS.toMillis(initElapsed), resourcesPerSecond);
            } catch (LoginException ex) {
                log.error("Vanity path init failed", ex);
            } finally {
                log.debug("dropping temporary resolver map - {}/{} entries, {} hits, {} misses", temporaryResolveMapsMap.size(),
                        SIZELIMIT, temporaryResolveMapsMapHits.get(), temporaryResolveMapsMapMisses.get());
                temporaryResolveMapsMap = null;
            }
        }
    }

    boolean doAddVanity(final Resource resource) {
        log.debug("doAddVanity getting {}", resource.getPath());

        boolean updateTheCache = isAllVanityPathEntriesCached() || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries();
        return null != loadVanityPath(resource, resolveMapsMap, vanityTargets, updateTheCache, true);
    }

    private String getMapEntryRedirect(final MapEntry mapEntry) {
        String[] redirect = mapEntry.getRedirect();
        if (redirect.length > 1) {
            log.warn("something went wrong, please restart the bundle");
            return null;
        }

        String path = redirect[0];
        if (path.endsWith("$1")) {
            path = path.substring(0, path.length() - "$1".length());
        } else if (path.endsWith(".html")) {
            path = path.substring(0, path.length() - ".html".length());
        }

        return path;
    }

    boolean doRemoveVanity(final String path) {
        String actualContentPath = getActualContentPath(path);
        List<String> targets = this.vanityTargets.remove(actualContentPath);

        if (targets != null) {
            for (String target : targets) {
                int count = removeEntriesFromResolvesMap(target, actualContentPath);
                if (vanityCounter.longValue() >= count) {
                    vanityCounter.addAndGet(-count);
                }
            }
            return true;
        } else {
            return false;
        }
    }

    private int removeEntriesFromResolvesMap(String target, String path) {
        List<MapEntry> entries = Objects.requireNonNullElse(this.resolveMapsMap.get(target),
                Collections.emptyList());

        int count = 0;

        // remove all entries for the given path
        for (Iterator<MapEntry> iterator = entries.iterator(); iterator.hasNext();) {
            MapEntry entry = iterator.next();
            String redirect = getMapEntryRedirect(entry);
            if (path.equals(redirect)) {
                iterator.remove();
                count += 1;
            }
        }
        // remove entry when now empty
        if (entries.isEmpty()) {
            this.resolveMapsMap.remove(target);
        }

        return count;
    }

    /**
     * get the MapEntry list containing all the nodes having a specific vanityPath
     */
    private List<MapEntry> getMapEntryList(String vanityPath) {
        List<MapEntry> mapEntries = null;

        boolean initFinished = vanityPathsProcessed.get();
        boolean probablyPresent = false;

        if (initFinished) {
            // total number of lookups after init (and when cache not complete)
            long current = this.vanityPathLookups.incrementAndGet();
            if (current >= Long.MAX_VALUE - 100000) {
                // reset counters when we get close the limit
                this.vanityPathLookups.set(1);
                this.vanityPathBloomNegatives.set(0);
                this.vanityPathBloomFalsePositives.set(0);
                log.info("Vanity Path metrics reset to 0");
            }

            // init is done - check the bloom filter
            probablyPresent = BloomFilterUtils.probablyContains(vanityBloomFilter, vanityPath);
            log.trace("bloom filter lookup for {} -> {}", vanityPath, probablyPresent);

            if (!probablyPresent) {
                // filtered by Bloom filter
                this.vanityPathBloomNegatives.incrementAndGet();
            }
        }

        if (!initFinished || probablyPresent) {

            // check the cache
            mapEntries = this.resolveMapsMap.get(vanityPath);

            if (mapEntries == null) {
                // try temporary map first
                if (!initFinished && temporaryResolveMapsMap != null) {
                    mapEntries = getMapEntriesFromTemporaryMap(vanityPath);
                }
                // still no entries? Try regular lookup, then update the temporary map
                if (mapEntries == null) {
                    mapEntries = getMapEntriesFromRepository(vanityPath, initFinished);
                }
            }

            if (mapEntries == null && probablyPresent) {
                // Bloom filter had a false positive
                this.vanityPathBloomFalsePositives.incrementAndGet();
            }
        }

        return mapEntries == noMapEntries ? null : mapEntries;
    }

    private @Nullable List<MapEntry> getMapEntriesFromRepository(String vanityPath, boolean initFinished) {
        Map<String, List<MapEntry>> mapEntry = getVanityPaths(vanityPath);
        List<MapEntry> mapEntries = mapEntry.get(vanityPath);
        if (!initFinished && temporaryResolveMapsMap != null) {
            log.trace("getMapEntryList: caching map entries for {} -> {}", vanityPath, mapEntries);
            temporaryResolveMapsMap.put(vanityPath, mapEntries == null ? noMapEntries : mapEntries);
        }
        return mapEntries;
    }

    private @Nullable List<MapEntry> getMapEntriesFromTemporaryMap(String vanityPath) {
        List<MapEntry> mapEntries = temporaryResolveMapsMap.get(vanityPath);
        if (mapEntries != null) {
            temporaryResolveMapsMapHits.incrementAndGet();
            log.trace("getMapEntryList: using temp map entries for {} -> {}", vanityPath, mapEntries);
        } else {
            temporaryResolveMapsMapMisses.incrementAndGet();
        }
        return mapEntries;
    }

    private byte[] createVanityBloomFilter() {
        return BloomFilterUtils.createFilter(VANITY_BLOOM_FILTER_MAX_ENTRIES, this.factory.getVanityBloomFilterMaxBytes());
    }

    private boolean isAllVanityPathEntriesCached() {
        return this.factory.getMaxCachedVanityPathEntries() == -1;
    }

    /**
     * get the vanity paths  Search for all nodes having a specific vanityPath
     */
    private Map<String, List<MapEntry>> getVanityPaths(String vanityPath) {

        Map<String, List<MapEntry>> entryMap = new HashMap<>();

        final String queryString = String.format(
                "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] "
                        + "WHERE %s AND ([sling:vanityPath]='%s' OR [sling:vanityPath]='%s') "
                        + "ORDER BY [sling:vanityOrder] DESC",
                QueryBuildHelper.excludeSystemPath(),
                QueryBuildHelper.escapeString(vanityPath),
                QueryBuildHelper.escapeString(vanityPath.substring(1)));

        try (ResourceResolver queryResolver = factory.getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"))) {
            long totalCount = 0;
            long totalValid = 0;
            log.debug("start vanityPath query: {}", queryString);
            final Iterator<Resource> i = queryResolver.findResources(queryString, "JCR-SQL2");
            log.debug("end vanityPath query");
            while (i.hasNext()) {
                totalCount += 1;
                final Resource resource = i.next();
                boolean isValid = false;
                for(final Path sPath : this.factory.getObservationPaths()) {
                    if (sPath.matches(resource.getPath())) {
                        isValid = true;
                        break;
                    }
                }
                if (isValid) {
                    totalValid += 1;
                    if (this.vanityPathsProcessed.get()
                            && (this.factory.isMaxCachedVanityPathEntriesStartup()
                            || this.isAllVanityPathEntriesCached()
                            || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries())) {
                        loadVanityPath(resource, resolveMapsMap, vanityTargets, true, true);
                        entryMap = resolveMapsMap;
                    } else {
                        final Map <String, List<String>> targetPaths = new HashMap<>();
                        loadVanityPath(resource, entryMap, targetPaths, true, false);
                    }
                }
            }
            log.debug("read {} ({} valid) vanityPaths", totalCount, totalValid);
        } catch (LoginException e) {
            log.error("Exception while obtaining queryResolver", e);
        }
        return entryMap;
    }

    /**
     * Check if the path is a valid vanity path
     * @param path The resource path to check
     * @return {@code true} if this is valid, {@code false} otherwise
     */
    boolean isValidVanityPath(final String path) {
        if (path == null) {
            throw new IllegalArgumentException("Unexpected null path");
        }

        // ignore system tree
        if (path.startsWith(JCR_SYSTEM_PREFIX)) {
            log.debug("isValidVanityPath: not valid {}", path);
            return false;
        }

        // check allow/deny list
        if (this.factory.getVanityPathConfig() != null) {
            boolean allowed = false;
            for (MapConfigurationProvider.VanityPathConfig config : this.factory.getVanityPathConfig()) {
                // process the first config entry matching the path
                if (path.startsWith(config.prefix)) {
                    allowed = !config.isExclude;
                    break;
                }
            }
            if (!allowed) {
                log.debug("isValidVanityPath: not valid as not in allow list {}", path);
                return false;
            }
        }

        // either no allow/deny list, or no config entry found
        return true;
    }

    /**
     * Load vanity paths - search for all nodes (except under /jcr:system)
     * having a sling:vanityPath property
     */
    private Map<String, List<String>> loadVanityPaths(ResourceResolver resolver) {
        final Map<String, List<String>> targetPaths = new ConcurrentHashMap<>();
        final String baseQueryString = "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus]" + " FROM [nt:base]"
                + " WHERE " + QueryBuildHelper.excludeSystemPath() + " AND [sling:vanityPath] IS NOT NULL";

        Iterator<Resource> it;
        try {
            final String queryStringWithSort = baseQueryString + " AND FIRST([sling:vanityPath]) >= '%s' ORDER BY FIRST([sling:vanityPath])";
            it = new PagedQueryIterator("vanity path", PROP_VANITY_PATH, resolver, queryStringWithSort, 2000);
        } catch (QuerySyntaxException ex) {
            log.debug("sort with first() not supported, falling back to base query", ex);
            it = queryUnpaged(baseQueryString, resolver);
        } catch (UnsupportedOperationException ex) {
            log.debug("query failed as unsupported, retrying without paging/sorting", ex);
            it = queryUnpaged(baseQueryString, resolver);
        }

        long count = 0;
        long countInScope = 0;
        long processStart = System.nanoTime();

        while (it.hasNext()) {
            count += 1;
            final Resource resource = it.next();
            final String resourcePath = resource.getPath();
            if (Stream.of(this.factory.getObservationPaths()).anyMatch(path -> path.matches(resourcePath))) {
                countInScope += 1;
                final boolean addToCache = isAllVanityPathEntriesCached()
                        || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries();
                loadVanityPath(resource, resolveMapsMap, targetPaths, addToCache, true);
            }
        }
        long processElapsed = System.nanoTime() - processStart;
        log.debug("processed {} resources with sling:vanityPath properties (of which {} in scope) in {}ms", count, countInScope, TimeUnit.NANOSECONDS.toMillis(processElapsed));
        if (!isAllVanityPathEntriesCached()) {
            if (countInScope > this.factory.getMaxCachedVanityPathEntries()) {
                log.warn("Number of resources with sling:vanityPath property ({}) exceeds configured cache size ({}); handling of uncached vanity paths will be much slower. Consider increasing the cache size or decreasing the number of vanity paths.", countInScope, this.factory.getMaxCachedVanityPathEntries());
            } else if (countInScope > (this.factory.getMaxCachedVanityPathEntries() / 10) * 9) {
                log.info("Number of resources with sling:vanityPath property in scope ({}) within 10% of configured cache size ({})", countInScope, this.factory.getMaxCachedVanityPathEntries());
            }
        }

        this.vanityResourcesOnStartup.set(count);

        return targetPaths;
    }

    private void updateTargetPaths(final Map<String, List<String>> targetPaths, final String key, final String entry) {
        if (entry != null) {
            List<String> entries = targetPaths.computeIfAbsent(key, k -> new ArrayList<>());
            entries.add(entry);
        }
    }

    /**
     * Load vanity path given a resource
     *
     * @return first vanity path or {@code null}
     */
    private String loadVanityPath(final Resource resource, final Map<String, List<MapEntry>> entryMap, final Map <String, List<String>> targetPaths, boolean addToCache, boolean updateCounter) {

        if (!isValidVanityPath(resource.getPath())) {
            return null;
        }

        final ValueMap props = resource.getValueMap();
        long vanityOrder = props.get(PROP_VANITY_ORDER, 0L);

        // url is ignoring scheme and host.port and the path is
        // what is stored in the sling:vanityPath property
        boolean hasVanityPath = false;
        final String[] pVanityPaths = props.get(PROP_VANITY_PATH, new String[0]);
        if (log.isTraceEnabled()) {
            log.trace("vanity paths on {}: {}", resource.getPath(), Arrays.asList(pVanityPaths));
        }

        for (final String pVanityPath : pVanityPaths) {
            final String[] result = this.getVanityPathDefinition(resource.getPath(), pVanityPath);
            if (result != null) {
                // redirect target is the node providing the sling:vanityPath
                // property (or its parent if the node is called jcr:content)
                final Resource redirectTarget;
                if (JCR_CONTENT.equals(resource.getName())) {
                    redirectTarget = resource.getParent();
                    if (redirectTarget == null) {
                        // we encountered a broken resource jcr:content resource
                        // that apparently has no parent; skip this one and
                        // continue with next
                        log.warn("containingResource is null for vanity path on {}, skipping.", resource.getPath());
                        continue;
                    }
                } else {
                    redirectTarget = resource;
                }

                hasVanityPath = true;
                final String url = result[0] + result[1];
                final String redirect = redirectTarget.getPath();
                final String redirectName = redirectTarget.getName();

                // whether the target is attained by an external redirect or
                // by an internal redirect is defined by the sling:redirect
                // property
                final int httpStatus = props.get(PROP_REDIRECT_EXTERNAL, false) ? props.get(
                        PROP_REDIRECT_EXTERNAL_REDIRECT_STATUS, factory.getDefaultVanityPathRedirectStatus())
                        : -1;

                final String checkPath = result[1];

                if (addToCache) {
                    MapEntry entry1;
                    MapEntry entry2;

                    if (redirectName.contains(".")) {
                        // name with extension
                        String extension = redirectName.substring(redirectName.lastIndexOf('.') + 1);

                        // 1. entry with exact match
                        entry1 = createMapEntry(url + "$", httpStatus, vanityOrder, redirect);

                        // 2. entry with extension
                        entry2 = createMapEntry(url + "\\." + extension, httpStatus, vanityOrder, redirect);
                    } else {
                        // name without extension

                        // 1. entry with exact match
                        entry1 = createMapEntry(url + "$", httpStatus, vanityOrder, redirect + ".html");

                        // 2. entry with match supporting selectors and extension
                        entry2 = createMapEntry(url + "(\\..*)", httpStatus, vanityOrder, redirect + "$1");

                    }

                    int count = 0;

                    if (this.addEntry(entryMap, checkPath, entry1)) {
                        count += 1;
                    }

                    if (this.addEntry(entryMap, checkPath, entry2)) {
                        count += 1;
                    }

                    if (count > 0) {
                        // keep the path to return
                        this.updateTargetPaths(targetPaths, redirect, checkPath);

                        if (updateCounter) {
                            vanityCounter.addAndGet(count);
                        }

                        // update bloom filter
                        BloomFilterUtils.add(vanityBloomFilter, checkPath);
                    }
                } else {
                    // update bloom filter
                    BloomFilterUtils.add(vanityBloomFilter, checkPath);
                }
            }
        }
        return hasVanityPath ? pVanityPaths[0] : null;
    }

    /**
     * Create the vanity path definition. String array containing:
     * {protocol}/{host}[.port] {absolute path}
     */
    private String[] getVanityPathDefinition(final String sourcePath, final String vanityPath) {

        if (vanityPath == null) {
            log.trace("getVanityPathDefinition: null vanity path on {}", sourcePath);
            return null;
        }

        String info = vanityPath.trim();

        if (info.isEmpty()) {
            log.trace("getVanityPathDefinition: empty vanity path on {}", sourcePath);
            return null;
        }

        String prefix, path;

        // check for URL-shaped path
        if (info.contains(":/")) {
            try {
                final URL u = new URL(info);
                prefix = u.getProtocol() + '/' + u.getHost() + '.' + u.getPort();
                path = u.getPath();
            } catch (final MalformedURLException e) {
                log.warn("Ignoring malformed vanity path '{}' on {}", info, sourcePath);
                return null;
            }
        } else {
            prefix = "^" + ANY_SCHEME_HOST;

            if (!info.startsWith("/")) {
                path = "/" + info;
            } else {
                path = info;
            }
        }

        // remove extension
        int lastSlash = path.lastIndexOf('/');
        int firstDot = path.indexOf('.', lastSlash + 1);
        if (firstDot != -1) {
            path = path.substring(0, firstDot);
            log.warn("Removing extension from vanity path '{}' on {}", info, sourcePath);
        }

        return new String[] { prefix, path };
    }

    // return vanity path entry iterator from cache when complete and ready, otherwise from
    // regular lockup
    public @Nullable Iterator<MapEntry> getCurrentMapEntryForVanityPath(final String key) {
        List<MapEntry> l;
        if (this.isAllVanityPathEntriesCached() && this.vanityPathsProcessed.get()) {
            l = this.resolveMapsMap.get(key);
        } else {
            l = this.getMapEntryList(key);
        }
        return l == null ? null : l.iterator();
    }

    /**
     * Add an entry to the resolve map.
     */
    private boolean addEntry(final Map<String, List<MapEntry>> entryMap, final String key, final MapEntry entry) {

        if (entry == null) {
            log.trace("trying to add null entry for {}", key);
            return false;
        } else {
            List<MapEntry> entries = entryMap.get(key);

            // copy existing list contents (when not empty), add new entry, then sort
            List<MapEntry> entriesCopy = new ArrayList<>(entries != null ? entries : List.of());
            entriesCopy.add(entry);
            Collections.sort(entriesCopy);

            // update map with new list
            entryMap.put(key, entriesCopy);

            // warn when list of entries for one key grows
            int size = entriesCopy.size();
            if (size == 10) {
                log.debug("10 MapEntries for {} - check your configuration", key);
            } else if (size == 100) {
                log.info("100 MapEntries for {} - check your configuration", key);
            }

            return true;
        }
    }

    private String getActualContentPath(final String path) {
        if (path.endsWith(JCR_CONTENT_SUFFIX)) {
            return ResourceUtil.getParent(path);
        } else {
            return path;
        }
    }

    private MapEntry createMapEntry(final String urlPattern, final int httpStatus, long order,
                                    final String... redirects) {
        try {
            return new MapEntry(urlPattern, httpStatus, false, order, redirects);
        } catch (IllegalArgumentException iae) {
            // ignore this entry
            log.debug("ignored entry for {} due to exception", urlPattern, iae);
            return null;
        }
    }

    private Iterator<Resource> queryUnpaged(String query, ResourceResolver resolver) {
        log.debug("start vanity path query: {}", query);
        long queryStart = System.nanoTime();
        final Iterator<Resource> it = resolver.findResources(query, "JCR-SQL2");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end vanity path query; elapsed {}ms", TimeUnit.NANOSECONDS.toMillis(queryElapsed));
        return it;
    }
}
