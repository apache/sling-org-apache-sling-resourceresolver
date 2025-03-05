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
 * Utilities related to construction of JCR-SQL2 queries
 */
public class QueryBuildHelper {

    private static final String JCR_SYSTEM_PATH = "/jcr:system";

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
}
