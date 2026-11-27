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

    public static final int UNPAGED_QUERY = 0;
    public static final int PAGED_QUERY = 1;

    /**
     * Resolves the query paging behavior from a system property.
     *
     * <p>Reads the system property named by {@code propname} and parses it as an integer.
     * If the property is null, blank, unparseable, or not equal to {@link #UNPAGED_QUERY}
     * or {@link #PAGED_QUERY}, this method logs an error (when invalid) and falls back to
     * {@link #PAGED_QUERY}.
     *
     * @param propname the name of the system property to read; may be null or blank
     * @return {@link #UNPAGED_QUERY} or {@link #PAGED_QUERY}, defaulting to {@link #PAGED_QUERY}
     */
    // TODO: update when more paging methods are added
    public static int getPagingBehaviour(String propname) {
        int defaultVal = PAGED_QUERY;
        int finalValue;

        String raw = System.getProperty(propname);

        if (raw != null && !raw.isBlank()) {
            int parsed = defaultVal;
            boolean valid = true;

            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                valid = false;
            }
            if (parsed != UNPAGED_QUERY && parsed != PAGED_QUERY) {
                valid = false;
            }
            if (!valid) {
                LOG.error("Invalid value '{}' for {}, using default {}", raw, propname, defaultVal);
            }
            if (valid && parsed != defaultVal) {
                LOG.debug("{} = {} (default: {})", propname, parsed, defaultVal);
            }

            finalValue = parsed;
        } else {
            finalValue = defaultVal;
        }
        return finalValue;
    }
}
