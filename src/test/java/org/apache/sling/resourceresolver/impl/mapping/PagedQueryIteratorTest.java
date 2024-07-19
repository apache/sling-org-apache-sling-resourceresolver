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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class PagedQueryIteratorTest extends AbstractMappingMapEntriesTest {

    private MapEntries mapEntries;

    private static String PROPNAME = "prop";

    @SuppressWarnings("unchecked")
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.openMocks(this).close();

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] { new Path("/") });
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);
    }

    @Test
    public void testEmptyQuery() {
        when(resourceResolver.findResources(eq("empty"), eq("JCR-SQL2"))).thenReturn(Collections.<Resource> emptySet().iterator());
        Iterator<Resource> it = mapEntries.new PagedQueryIterator("alias", PROPNAME, resourceResolver, "empty", 2000);
        assertFalse(it.hasNext());
    }

    @Test(expected = QuerySyntaxException.class)
    public void testMalformedQuery() {
        when(resourceResolver.findResources(eq("malformed"), eq("JCR-SQL2"))).thenThrow(new QuerySyntaxException("x", "y", "z"));
        mapEntries.new PagedQueryIterator("alias", PROPNAME, resourceResolver, "malformed", 2000);
    }

    @Test
    public void testSimple() {
        String[] expected = new String[] { "a", "b", "c" };
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources(eq("simple"), eq("JCR-SQL2"))).thenReturn(expectedResources.iterator());
        Iterator<Resource> it = mapEntries.new PagedQueryIterator("alias", PROPNAME, resourceResolver, "simple", 2000);
        for (String key : expected) {
            assertEquals(key, it.next().getValueMap().get(PROPNAME, new String[0])[0]);
        }
        assertFalse(it.hasNext());
    }

    private static Collection<Resource> toResourceList(String... keys) {
        Collection<Resource> result = new ArrayList<>();
        for (String key : keys) {
            ValueMap m = mock(ValueMap.class);
            when(m.get(eq(PROPNAME), any(Object.class))).thenReturn(new String[] { key });
            Resource r = mock(Resource.class);
            when(r.getValueMap()).thenReturn(m);
            result.add(r);
        }
        return result;
    }
}
