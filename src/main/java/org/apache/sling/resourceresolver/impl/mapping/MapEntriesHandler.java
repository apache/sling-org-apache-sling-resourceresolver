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

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;

/**
 * Exposes low-level methods used for resource resolving and mapping
 *
 * <p>This interface is intended for bundle internal use and its main goal is to
 * prevent accidental modifications of the internal state by only exposing
 * accessor methods.</p>
 *
 * @see org.apache.sling.api.resource.mapping.ResourceMapper
 * @see org.apache.sling.api.resource.ResourceResolver#map(String)
 * @see org.apache.sling.api.resource.ResourceResolver#map(javax.servlet.http.HttpServletRequest, String)
 */
public interface MapEntriesHandler {

    public MapEntriesHandler EMPTY = new MapEntriesHandler() {

        @Override
        public Iterator<MapEntry> getResolveMapsIterator(String requestPath) {
            return Collections.emptyIterator();
        }

        @Override
        public List<MapEntry> getResolveMaps() {
            return Collections.emptyList();
        }

        @Override
        public Collection<MapEntry> getMapMaps() {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Collection<String>> getAliasMap(String parentPath) {
            return Collections.emptyMap();
        }

        @Override
        public @NotNull Map<String, Collection<String>> getAliasMap(@NotNull Resource parent) {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, List<String>> getVanityPathMappings() {
            return Collections.emptyMap();
        }

        @Override
        public boolean isOptimizeAliasResolutionEnabled() {
            return false;
        }

        @Override
        public void logDisableAliasOptimization() {
            // nothing to do
        }
    };

    /**
     * Whether alias resolution optimization is enabled.
     * If it is enabled {@link #getAliasMap(String)} can be used.
     * @return true if the optimizedAliasResolution should be used, false otherwise
     */
    boolean isOptimizeAliasResolutionEnabled();

    /**
     * Log an error if alias optimization should be used but is currently disabled
     */
    void logDisableAliasOptimization();

    /**
     * Returns all alias entries for children of the specified <code>parentPath</code>
     *
     * <p>The returned map has resource names as keys and the assigned aliases as values.</p>
     *
     * @param parentPath the parent path
     * @return a map of all child alias entries, possibly empty
     */
    @NotNull
    Map<String, Collection<String>> getAliasMap(@NotNull String parentPath);

    @NotNull
    Map<String, Collection<String>> getAliasMap(@NotNull Resource parent);

    /**
     * Creates an iterator over the possibly applicable mapping entries for resolving a resource
     *
     * <p>This method uses the request path to filter out any unapplicable mapping entries and
     * is therefore preferrable over {@link #getResolveMaps()}.</p>
     *
     * <p>The iterator will iterate over the mapping entries in the order of the pattern length.</p>
     *
     * @param requestPath  the requestPath
     * @return the map entry iterator
     */
    @NotNull
    Iterator<MapEntry> getResolveMapsIterator(@NotNull String requestPath);

    /**
     * Return a flat listing of map entries used for mapping resources to URLs
     *
     * <p>This method returns information about all mapping rules. Note that vanity paths are excluded.</p>
     *
     * @return an unmodifiable collection of map entries
     */
    @NotNull
    Collection<MapEntry> getMapMaps();

    /**
     * Creates a flat listing of all the map entries used for resolving URLs to resources
     *
     * <p>This method returns information about all resolve rules, such as vanity paths, mapping entries,
     * virtual URLs and URL mappings.</p>
     *
     * <p>This list is computed on-demand and therefore should not be used in performance-critical code.</p>
     *
     * @return an unmodifiable, sorted, list of resolution map entries
     */
    @NotNull
    List<MapEntry> getResolveMaps();

    /**
     * Returns vanity paths mappings
     *
     * <p>The keys in the map are resource paths and the values are the vanity mappings.</p>
     *
     * @return an unmodifiable list of vanity path mappings
     */
    @NotNull
    Map<String, List<String>> getVanityPathMappings();
}
