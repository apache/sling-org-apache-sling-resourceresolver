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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class PagedQueryIteratorTest extends AbstractMappingMapEntriesTest {

    private static final String PROPNAME = "prop";

    @SuppressWarnings("unchecked")
    @Override
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.openMocks(this).close();

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] { new Path("/") });
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
    }

    @Test
    public void testEmptyQuery() {
        when(resourceResolver.findResources("empty", "JCR-SQL2")).thenReturn(Collections.emptyIterator());
        Iterator<Resource> it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "empty", 2000);
        assertFalse(it.hasNext());
    }

    @Test(expected = QuerySyntaxException.class)
    public void testMalformedQuery() {
        when(resourceResolver.findResources(eq("malformed"), eq("JCR-SQL2"))).thenThrow(new QuerySyntaxException("x", "y", "z"));
        new PagedQueryIterator("alias", PROPNAME, resourceResolver, "malformed", 2000);
    }

    @Test
    public void testSimple() {
        String[] expected = new String[] { "a", "b", "c" };
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources(eq("simple"), eq("JCR-SQL2"))).thenReturn(expectedResources.iterator());
        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "simple", 2000);
        for (String key : expected) {
            assertEquals(key, getFirstValueOf(it.next(), PROPNAME));
        }
        assertFalse(it.hasNext());
        assertEquals("", it.getWarning());
    }

    @Test(expected = PagedQueryIterator.QueryImplementationException.class)
    public void testSimpleWrongOrder() {
        String[] expected = new String[] { "a", "b", "d", "c" };
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources(eq("testSimpleWrongOrder"), eq("JCR-SQL2"))).thenReturn(expectedResources.iterator());
        Iterator<Resource> it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testSimpleWrongOrder",
                2000);
        while (it.hasNext()) {
            it.next();
        }
    }

    @Test
    public void testPagedWithEmpty() {
        String[] expected = new String[] { "", "a", "b", "c", "d" };
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        when(resourceResolver.findResources(eq("testPagedWithEmpty ''"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResources.iterator());
        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testPagedWithEmpty '%s'",
                2000);
        checkResult(it, expected);
        assertEquals("", it.getWarning());
    }

    @Test
    public void testPagedLargePage() {
        final int cnt = 140;
        final int pageSize = 5;
        String[] expected = new String[cnt];
        Arrays.fill(expected,"a");
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        when(resourceResolver.findResources(eq("testPagedLargePage ''"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResources.iterator());
        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testPagedLargePage '%s'",
                pageSize);
        checkResult(it, expected);
        assertEquals("Largest number of alias entries with the same 'first' selector exceeds expectation of " + pageSize * 10
                + " (value 'a' appears " + cnt + " times)", it.getWarning());
    }

    @Test
    public void testPagedResourcesOnPageBoundaryLost() {
        String[] expected = new String[] { "a", "a", "a", "a", "a", "a", "b", "c", "d" };
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        Collection<Resource> expectedFilteredResourcesA = filter("a", expectedResources);
        Collection<Resource> expectedFilteredResourcesB = filter("b", expectedResources);
        Collection<Resource> expectedFilteredResourcesC = filter("c", expectedResources);
        Collection<Resource> expectedFilteredResourcesD = filter("d", expectedResources);
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost ''"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResources.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'a'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesA.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'b'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesB.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'c'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesC.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'd'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesD.iterator());
        Iterator<Resource> it = new PagedQueryIterator("alias", PROPNAME, resourceResolver,
                "testPagedResourcesOnPageBoundaryLost '%s'", 5);

        checkResult(it, expected);
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

    private static Collection<Resource> filter(String key, Collection<Resource> input) {
        Predicate<Resource> filter = r -> getFirstValueOf(r, PROPNAME).compareTo(key) >= 0;
        return input.stream().filter(filter).collect(Collectors.toList());
    }

    private static String getFirstValueOf(Resource r, String propname) {
        return r.getValueMap().get(propname, new String[0])[0];
    }

    private static void checkResult(Iterator<Resource> it, String...expected ) {
        int pos = 0;
        for (String key : expected) {
            assertEquals("expects " + key + " at position " + pos, key, getFirstValueOf(it.next(), PROPNAME));
            pos += 1;
        }
        assertFalse(it.hasNext());
    }
}
