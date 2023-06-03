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
import java.util.Collections;
import java.util.List;

import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider.VanityPathConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class VanityPathConfigurer {

    public @interface DeprecatedVanityConfig {

        /** This is the deprecated fallback configuration for resource_resolver_vanitypath_allowlist() */
        String[] resource_resolver_vanitypath_whitelist();

        /** This is the deprecated fallback configuration for resource_resolver_vanitypath_denylist() */
        String[] resource_resolver_vanitypath_blacklist();
    }

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private volatile ResourceResolverFactoryConfig config;

    private volatile List<VanityPathConfig> vanityPathConfig;

    public void setConfiguration(final ResourceResolverFactoryConfig c, final DeprecatedVanityConfig deprecatedConfig) {
        this.config = c;
        this.vanityPathConfig = null;

        final List<String> includes = this.configureVanityPathPrefixes(c.resource_resolver_vanitypath_allowlist(),
            deprecatedConfig == null ? null : deprecatedConfig.resource_resolver_vanitypath_whitelist(),
            "resource_resolver_vanitypath_allowlist",
            "resource_resolver_vanitypath_whitelist");

        final List<String> excludes = this.configureVanityPathPrefixes(c.resource_resolver_vanitypath_denylist(),
            deprecatedConfig == null ? null : deprecatedConfig.resource_resolver_vanitypath_blacklist(),
            "resource_resolver_vanitypath_denylist",
            "resource_resolver_vanitypath_blacklist");
        if ( includes != null || excludes != null ) {
            this.vanityPathConfig = new ArrayList<>();
            if ( includes != null ) {
                for(final String val : includes) {
                    this.vanityPathConfig.add(new VanityPathConfig(val, false));
                }
            }
            if ( excludes != null ) {
                for(final String val : excludes) {
                    this.vanityPathConfig.add(new VanityPathConfig(val, true));
                }
            }
            Collections.sort(this.vanityPathConfig);
        }
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

    public List<VanityPathConfig> getVanityPathConfig() {
        return this.vanityPathConfig;
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

    List<String> configureVanityPathPrefixes(final String[] pathPrefixes, final String[] pathPrefixesFallback,
                                     String pathPrefixesPropertyName, String pathPrefixesFallbackPropertyName) {
        if (pathPrefixes != null && pathPrefixesFallback != null) {
            logger.error("Both the " + pathPrefixesPropertyName + " and " + pathPrefixesFallbackPropertyName
                + " were defined. Using " + pathPrefixesPropertyName + " for configuring vanity paths.");
            return filterVanityPathPrefixes(pathPrefixes);
        } else if (pathPrefixes != null) {
            return filterVanityPathPrefixes(pathPrefixes);
        } else if (pathPrefixesFallback != null) {
            logger.warn("The " + pathPrefixesPropertyName + " was not set. Using the " +
                pathPrefixesFallbackPropertyName + " instead. Please update your configuration to use " + pathPrefixesPropertyName);
            return filterVanityPathPrefixes(pathPrefixesFallback);
        }
        return null;
    }

    private static List<String> filterVanityPathPrefixes(final String[] vanityPathPrefixes) {
        final List<String> prefixList = new ArrayList<>();
        for (final String value : vanityPathPrefixes) {
            if (value.trim().length() > 0) {
                if (value.trim().endsWith("/")) {
                    prefixList.add(value.trim());
                } else {
                    prefixList.add(value.trim().concat("/"));
                }
            }
        }
        return prefixList.isEmpty() ? null : prefixList;
    }
}