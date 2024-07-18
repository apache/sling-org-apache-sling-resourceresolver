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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

public class PagedQueryIteratorTest extends AbstractMappingMapEntriesTest {

    private MapEntries mapEntries;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.openMocks(this).close();

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] { new Path("/") });
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(Collections.<Resource> emptySet().iterator());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);
    }

    @Test
    public void testInstantiation() {
        mapEntries.new PagedQueryIterator("alias", "sling:alias", resourceResolver, "foo", 2000);
    }
}
