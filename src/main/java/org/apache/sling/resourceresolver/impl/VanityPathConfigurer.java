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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class VanityPathConfigurer {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private volatile ResourceResolverFactoryConfig config;

    /** Vanity path allow list */
    private volatile String[] vanityPathAllowList;

    /** Vanity path deny list */
    private volatile String[] vanityPathDenyList;

    public void setConfiguration(final ResourceResolverFactoryConfig c) {
        this.config = c;
        // vanity path white list
        this.vanityPathAllowList = null;
        this.configureVanityPathPrefixes(config.resource_resolver_vanitypath_whitelist(),
            config.resource_resolver_vanitypath_allowlist(),
            "resource_resolver_vanitypath_whitelist",
            "resource_resolver_vanitypath_allowlist",
            filteredPrefixes -> this.vanityPathAllowList = filteredPrefixes);
        // vanity path black list
        this.vanityPathDenyList = null;
        this.configureVanityPathPrefixes(config.resource_resolver_vanitypath_blacklist(),
            config.resource_resolver_vanitypath_denylist(),
            "resource_resolver_vanitypath_blacklist",
            "resource_resolver_vanitypath_denylist",
            filteredPrefixes -> this.vanityPathDenyList = filteredPrefixes);

    }

    public int getDefaultVanityPathRedirectStatus() {
        return config.resource_resolver_default_vanity_redirect_status();
    }

    public boolean isVanityPathEnabled() {
        return this.config.resource_resolver_enable_vanitypath();
    }

    public boolean isVanityPathCacheInitInBackground() {
        return this.config.resource_resolver_vanitypath_cache_in_background();
    }

    public String[] getVanityPathAllowList() {
        return this.vanityPathAllowList;
    }

    public String[] getVanityPathDenyList() {
        return this.vanityPathDenyList;
    }

    public boolean hasVanityPathPrecedence() {
        return this.config.resource_resolver_vanity_precedence();
    }

    public long getMaxCachedVanityPathEntries() {
        return this.config.resource_resolver_vanitypath_maxEntries();
    }

    public boolean isMaxCachedVanityPathEntriesStartup() {
        return this.config.resource_resolver_vanitypath_maxEntries_startup();
    }

    public int getVanityBloomFilterMaxBytes() {
        return this.config.resource_resolver_vanitypath_bloomfilter_maxBytes();
    }

    void configureVanityPathPrefixes(String[] pathPrefixes, String[] pathPrefixesFallback,
                                     String pathPrefixesPropertyName, String pathPrefixesFallbackPropertyName,
                                     Consumer<String[]> filteredPathPrefixesConsumer) {
        if (pathPrefixes != null && pathPrefixesFallback != null) {
            logger.warn("Both the " + pathPrefixesPropertyName + " and " + pathPrefixesFallbackPropertyName
                + " were defined. Using " + pathPrefixesPropertyName + " for configuring vanity paths.");
            configureVanityPathPrefixes(pathPrefixes, filteredPathPrefixesConsumer);
        } else if (pathPrefixes != null) {
            configureVanityPathPrefixes(pathPrefixes, filteredPathPrefixesConsumer);
        } else {
            logger.debug("The " + pathPrefixesPropertyName + " was null. Using the " +
                pathPrefixesFallbackPropertyName + " instead if defined.");
            if (pathPrefixesFallback != null) {
                configureVanityPathPrefixes(pathPrefixesFallback, filteredPathPrefixesConsumer);
            }
        }
    }

    private static void configureVanityPathPrefixes(String[] pathPrefixes, Consumer<String[]> pathPrefixesConsumer) {
        final List<String> filterVanityPaths = filterVanityPathPrefixes(pathPrefixes);
        if (filterVanityPaths.size() > 0) {
            pathPrefixesConsumer.accept(filterVanityPaths.toArray(new String[filterVanityPaths.size()]));
        }
    }

    @NotNull
    private static List<String> filterVanityPathPrefixes(String[] vanityPathPrefixes) {
        final List<String> prefixList = new ArrayList<>();
        for (final String value : vanityPathPrefixes) {
            if (value.trim().length() > 0) {
                if (value.trim().endsWith("/")) {
                    prefixList.add(value.trim());
                } else {
                    prefixList.add(value.trim() + "/");
                }
            }
        }
        return prefixList;
    }
}