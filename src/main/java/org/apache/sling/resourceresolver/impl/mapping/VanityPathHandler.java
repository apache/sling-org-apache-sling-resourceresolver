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

/**
 * Handles loading and caching of vanity paths.
 */
public class VanityPathHandler {

    private static final int VANITY_BLOOM_FILTER_MAX_ENTRIES = 10000000;

    private volatile MapConfigurationProvider factory;

    private final byte[] bloomFilter;

    /**
     * @param factory {@link MapConfigurationProvider}
     */
    public VanityPathHandler(MapConfigurationProvider factory) {
        this.factory = factory;
        this.bloomFilter = BloomFilterUtils.createFilter(VANITY_BLOOM_FILTER_MAX_ENTRIES,
                this.factory.getVanityBloomFilterMaxBytes());
    }

    public boolean cacheProbablyContains(String path) {
        return BloomFilterUtils.probablyContains(this.bloomFilter, path);
    }

    public void cacheWillProbablyContain(String path) {
        BloomFilterUtils.add(this.bloomFilter, path);
    }
 }
