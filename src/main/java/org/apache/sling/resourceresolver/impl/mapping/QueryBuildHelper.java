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

import java.util.Locale;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities related to configuration and construction of queries
 */
public class QueryBuildHelper {

    private static final String JCR_SYSTEM_PATH = "/jcr:system";

    private static Logger LOG = LoggerFactory.getLogger(QueryBuildHelper.class);

    private QueryBuildHelper() {
        // no instances for you
    }

    /**
     * Escape string literal for use in JCR-SQL2 query string
     * @param input literal to escape
     * @return escaped literal
     */
    public static String escapeString(String input) {
        return input.replace("'", "''");
    }

    /**
     * Generate query condition to exclude JCR system path
     * @return query condition
     */
    public static String excludeSystemPath() {
        return String.format("NOT isdescendantnode('%s')", escapeString(JCR_SYSTEM_PATH));
    }

    public enum PagingBehavior {
        UNPAGED,
        PAGED;

        /**
         * Resolves the query paging behavior from a system property.
         *
         * <p>Reads the system property named by {@code propname} and resolves it via
         * {@link #valueOf(String)} after trimming and uppercasing. If the property is
         * null, blank, or does not match a known {@link PagingBehavior}, this method
         * logs an error (when invalid) and falls back to {@link #PAGED}.
         *
         * @param propname the name of the system property to read; may be null or blank
         * @return the resolved {@link PagingBehavior}, defaulting to {@link #PAGED}
         */
        // TODO: update when more paging methods are added
        public static PagingBehavior getPagingBehaviour(@NotNull String propname) {
            PagingBehavior defaultVal = PAGED;

            String raw = System.getProperty(propname);
            if (raw == null || raw.isBlank()) {
                return defaultVal;
            }

            try {
                PagingBehavior resolved = valueOf(raw.trim().toUpperCase(Locale.ROOT));
                if (resolved != defaultVal) {
                    LOG.debug("{} = {} (default: {})", propname, resolved, defaultVal);
                }
                return resolved;
            } catch (IllegalArgumentException e) {
                LOG.error("Invalid value '{}' for {}, using default {}", raw, propname, defaultVal);
                return defaultVal;
            }
        }
    }
}
