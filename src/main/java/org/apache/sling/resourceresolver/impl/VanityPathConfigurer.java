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