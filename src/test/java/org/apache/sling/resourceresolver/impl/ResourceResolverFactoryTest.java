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

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.junit.Before;
import org.junit.Test;

import static org.apache.sling.resourceresolver.util.MockTestUtil.setInaccessibleField;
import static org.junit.Assert.*;

public class ResourceResolverFactoryTest {

    private CommonResourceResolverFactoryImpl commonFactory;

    private ResourceResolverFactoryActivator activator;

    @Before
    public void setup() {
        activator = new ResourceResolverFactoryActivator();
        activator.resourceProviderTracker = new ResourceProviderTracker();
        commonFactory = new CommonResourceResolverFactoryImpl(activator);
    }

    @Test
    public void testSingleThreadLocal() throws Exception {
        assertNull(this.commonFactory.getThreadResourceResolver());
        // create first resolver
        final ResourceResolver rr1 = this.commonFactory.getResourceResolver(null);
        assertNotNull(rr1);
        assertEquals(rr1, this.commonFactory.getThreadResourceResolver());
        rr1.close();

        assertNull(this.commonFactory.getThreadResourceResolver());
    }

    @Test
    public void testNestedThreadLocal() throws Exception {
        assertNull(this.commonFactory.getThreadResourceResolver());
        // create first resolver
        final ResourceResolver rr1 = this.commonFactory.getResourceResolver(null);
        assertNotNull(rr1);
        assertEquals(rr1, this.commonFactory.getThreadResourceResolver());

        // create second resolver
        final ResourceResolver rr2 = this.commonFactory.getResourceResolver(null);
        assertNotNull(rr2);
        assertEquals(rr2, this.commonFactory.getThreadResourceResolver());

        rr2.close();
        assertEquals(rr1, this.commonFactory.getThreadResourceResolver());

        rr1.close();
        assertNull(this.commonFactory.getThreadResourceResolver());
    }

    @Test
    public void testNestedUnorderedCloseThreadLocal() throws Exception {
        assertNull(this.commonFactory.getThreadResourceResolver());
        // create three resolver
        final ResourceResolver rr1 = this.commonFactory.getResourceResolver(null);
        final ResourceResolver rr2 = this.commonFactory.getResourceResolver(null);
        final ResourceResolver rr3 = this.commonFactory.getResourceResolver(null);

        assertEquals(rr3, this.commonFactory.getThreadResourceResolver());

        rr2.close();
        assertEquals(rr3, this.commonFactory.getThreadResourceResolver());

        rr3.close();
        assertEquals(rr1, this.commonFactory.getThreadResourceResolver());

        rr1.close();
        assertNull(this.commonFactory.getThreadResourceResolver());
    }

    @Test
    public void testThreadLocalWithAdmin() throws Exception {
        assertNull(this.commonFactory.getThreadResourceResolver());
        final ResourceResolver rr1 = this.commonFactory.getResourceResolver(null);
        final ResourceResolver admin = this.commonFactory.getAdministrativeResourceResolver(null);

        assertEquals(rr1, this.commonFactory.getThreadResourceResolver());

        rr1.close();
        assertNull(this.commonFactory.getThreadResourceResolver());

        admin.close();
        assertNull(this.commonFactory.getThreadResourceResolver());
    }

    @Test
    public void testGetAllowedAliasPaths() throws NoSuchMethodException {
        assertTrue(this.commonFactory.getAllowedAliasLocations().isEmpty());
        String[] allowPaths = {"/parent", "/parent0"};
        setInaccessibleField(
                "allowedAliasLocations",
                activator,
                Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(allowPaths))));
        assertTrue(!this.commonFactory.getAllowedAliasLocations().isEmpty());
    }
}
