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

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class VanityPathConfigurerTest {
    private VanityPathConfigurer vanityPathConfigurer;

    @Before
    public void before() {
        vanityPathConfigurer = new VanityPathConfigurer();
    }

    @Test
    public void testConfigureVanityPathPrefixes_givenBothPathPrefixAndFallBack_thenPathPrefixIsUsed() {
        String[] pathPrefixes = {"/some/path/"};
        String[] pathPrefixesFallback = {"/some/fallback/path/"};

        vanityPathConfigurer.configureVanityPathPrefixes(pathPrefixes, pathPrefixesFallback,
            "resource_resolver_vanitypath_whitelist",
            "resource_resolver_vanitypath_allowlist",
            actualResults -> verifyResults(actualResults, pathPrefixes));
    }

    @Test
    public void testConfigureVanityPathPrefixes_givenOnlyPathPrefix_thenPathPrefixIsUsed() {
        String[] pathPrefixes = {"/some/path/"};
        String[] pathPrefixesFallback = null;

        vanityPathConfigurer.configureVanityPathPrefixes(pathPrefixes, pathPrefixesFallback,
            "resource_resolver_vanitypath_whitelist",
            "resource_resolver_vanitypath_allowlist",
            actualResults -> verifyResults(actualResults, pathPrefixes));
    }

    @Test
    public void testConfigureVanityPathPrefixes_givenOnlyPathPrefixFallback_thenPathPrefixFallbackIsUsed() {
        String[] pathPrefixes = null;
        String[] pathPrefixesFallback = {"/some/fallback/path/"};

        vanityPathConfigurer.configureVanityPathPrefixes(pathPrefixes, pathPrefixesFallback,
            "resource_resolver_vanitypath_whitelist",
            "resource_resolver_vanitypath_allowlist",
            actualResults -> verifyResults(actualResults, pathPrefixesFallback));
    }

    private void verifyResults(String[] actualResults, String[] expectedResults) {
        assertArrayEquals(expectedResults, actualResults);
    }

}
