/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.sling.resourceresolver.impl.mapping.MapEntries.PROP_REDIRECT_EXTERNAL;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * These are tests that are testing the Sling Interpolation Feature (SLING-7768)
 * on the MapEntries level
 */
public class StringInterpolationMapEntriesTest {
    private static final String PROP_REG_EXP = "sling:match";

    @Mock
    private MapConfigurationProvider resourceResolverFactory;

    @Mock
    private BundleContext bundleContext;

    @Mock
    private EventAdmin eventAdmin;

    @Mock
    private ResourceResolver resourceResolver;

    @Mock
    private StringInterpolationProviderConfiguration stringInterpolationProviderConfiguration;

    File vanityBloomFilterFile;

    private Resource map;
    private Resource http;

    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        final List<MapConfigurationProvider.VanityPathConfig> configs = new ArrayList<>();
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(configs);
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(true);
        when(resourceResolverFactory.isForceNoAliasTraversal()).thenReturn(true);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(
            Collections.<Resource> emptySet().iterator());
        vanityBloomFilterFile = new File("target/test-classes/resourcesvanityBloomFilter.txt");
        when(bundleContext.getDataFile("vanityBloomFilter.txt")).thenReturn(vanityBloomFilterFile);

        map = setupEtcMapResource("/etc", "map");
        http = setupEtcMapResource("http", map);
    }

    @Test
    public void simple_node_string_interpolation() throws Exception {
        // To avoid side effects the String Interpolation uses its own Resource Resolver
        Resource sivOne = setupEtcMapResource("${siv.one}", http,PROP_REDIRECT_EXTERNAL, "/content/test-me");
        StringInterpolationProvider stringInterpolationProvider = setupStringInterpolationProvider(new String[] {"siv.one=test-value"});

        MapEntries mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider);
        List<MapEntry> mapMaps = mapEntries.getResolveMaps();
        assertEquals("Expected one mapping", 1, mapMaps.size());
        MapEntry mapEntry = mapMaps.get(0);
        assertEquals("Wrong String Interpolation for siv.one", "^http/test-value/", mapEntry.getPattern());
        String[] redirects = mapEntry.getRedirect();
        assertEquals("Expected one redirect", 1, redirects.length);
        assertEquals("Wrong Mapping found for siv.one", "/content/test-me/", redirects[0]);
    }

    @Test
    public void simple_match_string_interpolation() throws Exception {
        // To avoid side effects the String Interpolation uses its own Resource Resolver
        Resource sivOne = setupEtcMapResource("test-node", http,
            PROP_REG_EXP, "${siv.one}/",
            PROP_REDIRECT_EXTERNAL, "/content/test-me/"
        );
        StringInterpolationProvider stringInterpolationProvider = setupStringInterpolationProvider(new String[] {"siv.one=test-value"});

        MapEntries mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider);
        List<MapEntry> mapMaps = mapEntries.getResolveMaps();
        assertEquals("Expected one mapping", 1, mapMaps.size());
        MapEntry mapEntry = mapMaps.get(0);
        assertEquals("Wrong String Interpolation for siv.one", "^http/test-value/", mapEntry.getPattern());
        String[] redirects = mapEntry.getRedirect();
        assertEquals("Expected one redirect", 1, redirects.length);
        assertEquals("Wrong Mapping found for siv.one", "/content/test-me/", redirects[0]);
    }

    // -------------------------- private methods ----------

    private ValueMap buildValueMap(Object... string) {
        final Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < string.length; i = i + 2) {
            data.put((String) string[i], string[i+1]);
        }
        return new ValueMapDecorator(data);
    }

    private Resource setupEtcMapResource(String parentPath, String name, String...valueMapPairs) {
        return setupEtcMapResource0(parentPath, name, null, valueMapPairs);
    }
    private Resource setupEtcMapResource(String name, Resource parent, String...valueMapPairs) {
        return setupEtcMapResource0(null, name, parent, valueMapPairs);
    }
    private Resource setupEtcMapResource0(String parentPath, String name, Resource parent, String...valueMapPairs) {
        Resource resource = mock(Resource.class, withSettings().name(name).extraInterfaces(ResourceDecorator.class));
        String path = (parent == null ? parentPath : parent.getPath()) + "/" + name;
        when(resource.getPath()).thenReturn(path);
        when(resource.getName()).thenReturn(name);
        ValueMap valueMap = buildValueMap(valueMapPairs);
        when(resource.getValueMap()).thenReturn(valueMap);
        when(resource.adaptTo(ValueMap.class)).thenReturn(valueMap);
        when(resourceResolver.getResource(resource.getPath())).thenReturn(resource);
        if(parent != null) {
            List<Resource> childList = ((ResourceDecorator) parent).getChildrenList();
            childList.add(resource);
        }
        final List<Resource> childrenList = new ArrayList<>();
        when(((ResourceDecorator) resource).getChildrenList()).thenReturn(childrenList);
        // Delay the children list iterator to make sure all children are added beforehand
        // Iterators have a modCount that is set when created. Any changes to the underlying list will
        // change that modCount and the usage of the iterator will fail due to Concurrent Modification Exception
        when(resource.listChildren()).thenAnswer(new Answer<Iterator<Resource>>() {
            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                return childrenList.iterator();
            }
        });

        return resource;
    }

    private StringInterpolationProvider setupStringInterpolationProvider(final String[] placeholderValues) {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(placeholderValues);
        StringInterpolationProviderImpl stringInterpolationProvider = new StringInterpolationProviderImpl();
        stringInterpolationProvider.activate(bundleContext, stringInterpolationProviderConfiguration);
        return stringInterpolationProvider;
    }

    /**
     * Iterator to piggyback the list of Resources onto a Resource Mock
     * so that we can add children to them and create the iterators after
     * everything is setup
     */
    private static interface ResourceDecorator {
        public List<Resource> getChildrenList();
    }
}
