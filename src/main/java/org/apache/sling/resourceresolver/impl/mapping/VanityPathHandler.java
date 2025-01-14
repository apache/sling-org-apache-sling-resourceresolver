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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles loading and caching of vanity paths.
 */
public class VanityPathHandler {

    /** default log */
    private final Logger log = LoggerFactory.getLogger(VanityPathHandler.class);

    private static final int VANITY_BLOOM_FILTER_MAX_ENTRIES = 10000000;

    private volatile MapConfigurationProvider factory;

    private final byte[] bloomFilter;

    private static final String JCR_SYSTEM_PREFIX = "/jcr:system/";

    private final AtomicLong counter;
    private final AtomicLong resourcesOnStartup;
    private final AtomicLong lookups;
    private final AtomicLong bloomNegatives;
    private final AtomicLong bloomFalsePositives;

    private static final String ANY_SCHEME_HOST = "[^/]+/[^/]+";

    /**
     * @param factory {@link MapConfigurationProvider}
     */
    public VanityPathHandler(MapConfigurationProvider factory) {
        this.factory = factory;
        this.bloomFilter = BloomFilterUtils.createFilter(VANITY_BLOOM_FILTER_MAX_ENTRIES,
                this.factory.getVanityBloomFilterMaxBytes());
        this.counter = new AtomicLong(0);
        this.resourcesOnStartup = new AtomicLong(0);
        this.lookups = new AtomicLong(0);
        this.bloomNegatives = new AtomicLong(0);
        this.bloomFalsePositives = new AtomicLong(0);
    }

    /**
     * Check if the path is a valid vanity path
     * @param path The resource path to check
     * @return {@code true} if this is valid, {@code false} otherwise
     */
    boolean isValidVanityPath(final String path){

        Objects.requireNonNull(path, "Unexpected null path");

        // ignore system tree
        if (path.startsWith(JCR_SYSTEM_PREFIX)) {
            log.debug("isValidVanityPath: not valid {}", path);
            return false;
        }

        // check allow/deny list
        if (this.factory.getVanityPathConfig() != null) {
            boolean allowed = false;
            for (final MapConfigurationProvider.VanityPathConfig config : this.factory.getVanityPathConfig()) {
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
        return true;
    }

    void updateTargetPaths(final Map<String, List<String>> targetPaths, final String key, final String entry) {
        if (entry != null) {
            List<String> entries = targetPaths.computeIfAbsent(key, x -> new ArrayList<>());
            entries.add(entry);
        }
    }

    /**
     * Create the vanity path definition. String array containing:
     * {protocol}/{host}[.port] {absolute path}
     */
    String[] getVanityPathDefinition(final String sourcePath, final String vanityPath) {

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
            } catch (MalformedURLException e) {
                log.warn("Ignoring malformed vanity path '{}' on {}", info, sourcePath);
                return null;
            }
        } else {
            prefix = "^" + ANY_SCHEME_HOST;
            path = info.startsWith("/") ? info : "/" + info;
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

    MapEntry getMapEntry(final String url, final int status, final boolean trailingSlash, long order, final String... redirect){
        try {
            return new MapEntry(url, status, trailingSlash, order, redirect);
        } catch (IllegalArgumentException iae) {
            //ignore this entry
            log.debug("ignored entry for {} due to exception", url, iae);
            return null;
        }
    }

    /**
     * Add an entry to the resolve map.
     */
    boolean addEntry(final Map<String, List<MapEntry>> entryMap, final String key, final MapEntry entry) {

        if (entry == null) {
            log.trace("trying to add null entry for {}", key);
            return false;
        } else {
            List<MapEntry> entries = entryMap.get(key);
            if (entries == null) {
                entries = new ArrayList<>();
                entries.add(entry);
                entryMap.put(key, entries);
            } else {
                List<MapEntry> entriesCopy = new ArrayList<>(entries);
                entriesCopy.add(entry);
                // and finally sort list
                Collections.sort(entriesCopy);
                entryMap.put(key, entriesCopy);
                int size = entriesCopy.size();
                if (size >= 100) {
                    log.info(">= 100 MapEntries for {} - check your configuration", key);
                } else if (size >= 10) {
                    log.debug(">= 10 MapEntries for {} - check your configuration", key);
                }
            }
            return true;
        }
    }

    // Bloom Filter

    boolean cacheProbablyContains(String path) {
        return BloomFilterUtils.probablyContains(this.bloomFilter, path);
    }

    void cacheWillProbablyContain(String path) {
        BloomFilterUtils.add(this.bloomFilter, path);
    }

    // Metrics

    AtomicLong getCounter() {
        return this.counter;
    }

    AtomicLong getResourceCountOnStartup() {
        return this.resourcesOnStartup;
    }

    AtomicLong getLookups() {
        return this.lookups;
    }

    AtomicLong getBloomNegatives() {
        return this.bloomNegatives;
    }

    AtomicLong getBloomFalsePositives() {
        return this.bloomFalsePositives;
    }
}
