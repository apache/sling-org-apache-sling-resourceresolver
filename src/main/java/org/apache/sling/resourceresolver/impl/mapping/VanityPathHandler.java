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

    private final AtomicLong vanityCounter;

    /**
     * @param factory {@link MapConfigurationProvider}
     */
    public VanityPathHandler(MapConfigurationProvider factory) {
        this.factory = factory;
        this.bloomFilter = BloomFilterUtils.createFilter(VANITY_BLOOM_FILTER_MAX_ENTRIES,
                this.factory.getVanityBloomFilterMaxBytes());
        this.vanityCounter = new AtomicLong(0);
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

    boolean cacheProbablyContains(String path) {
        return BloomFilterUtils.probablyContains(this.bloomFilter, path);
    }

    void cacheWillProbablyContain(String path) {
        BloomFilterUtils.add(this.bloomFilter, path);
    }

    long getTotalCount() {
        return vanityCounter.get();
    }

    long addToTotalCountAndGet(long delta) {
        return vanityCounter.addAndGet(delta);
    }
 }
