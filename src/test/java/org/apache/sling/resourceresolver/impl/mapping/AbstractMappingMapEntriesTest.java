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
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static org.apache.sling.resourceresolver.util.MockTestUtil.createStringInterpolationProviderConfiguration;
import static org.apache.sling.resourceresolver.util.MockTestUtil.setupStringInterpolationProvider;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * These are tests that are testing the Sling Interpolation Feature (SLING-7768)
 * on the MapEntries level
 */
public abstract class AbstractMappingMapEntriesTest {
    static final String PROP_REG_EXP = "sling:match";

    @Mock
    MapConfigurationProvider resourceResolverFactory;

    @Mock
    BundleContext bundleContext;

    @Mock
    Bundle bundle;

    @Mock
    EventAdmin eventAdmin;

    @Mock
    ResourceResolver resourceResolver;

    StringInterpolationProviderConfiguration stringInterpolationProviderConfiguration;

    StringInterpolationProviderImpl stringInterpolationProvider = new StringInterpolationProviderImpl();
    MapEntries mapEntries;

    File vanityBloomFilterFile;

    Resource map;
    Resource http;

    Map<String, Map<String, String>> aliasMap;

    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        List<MapConfigurationProvider.VanityPathConfig> configs = getVanityPathConfigs();
        vanityBloomFilterFile = new File("target/test-classes/resourcesvanityBloomFilter.txt");
        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(bundleContext.getDataFile("vanityBloomFilter.txt")).thenReturn(vanityBloomFilterFile);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(configs);
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(true);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(
            Collections.<Resource> emptySet().iterator());

        map = setupEtcMapResource("/etc", "map");
        http = setupEtcMapResource("http", map);

        stringInterpolationProviderConfiguration = createStringInterpolationProviderConfiguration();
        setupStringInterpolationProvider(stringInterpolationProvider, stringInterpolationProviderConfiguration, new String[] {});
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider);

        final Field aliasMapField = MapEntries.class.getDeclaredField("aliasMap");
        aliasMapField.setAccessible(true);
        this.aliasMap = ( Map<String, Map<String, String>>) aliasMapField.get(mapEntries);
    }

    List<MapConfigurationProvider.VanityPathConfig> getVanityPathConfigs() {
        return new ArrayList<>();
    }

    @After
    public void tearDown() throws Exception {
        vanityBloomFilterFile.delete();
    }


    // -------------------------- private methods ----------

    ValueMap buildValueMap(Object... string) {
        final Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < string.length; i = i + 2) {
            data.put((String) string[i], string[i+1]);
        }
        return new ValueMapDecorator(data);
    }

    Resource getVanityPathResource(final String path) {
        Resource rsrc = mock(Resource.class);
        when(rsrc.getPath()).thenReturn(path);
        when(rsrc.getName()).thenReturn(ResourceUtil.getName(path));
        when(rsrc.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/vanity" + path));
        return rsrc;
    }

    Resource setupEtcMapResource(String parentPath, String name, String...valueMapPairs) {
        return setupEtcMapResource0(parentPath, name, null, valueMapPairs);
    }
    Resource setupEtcMapResource(String name, Resource parent, String...valueMapPairs) {
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
        ResourceMetadata resourceMetadata = mock(ResourceMetadata.class);
        when(resource.getResourceMetadata()).thenReturn(resourceMetadata);
        doNothing().when(resourceMetadata).setResolutionPath(anyString());
        doNothing().when(resourceMetadata).setParameterMap(anyMap());

        return resource;
    }

    DataFuture createDataFuture(ExecutorService pool, final MapEntries mapEntries) {

        Future<Iterator<?>> future = pool.submit(new Callable<Iterator<?>>() {
            @Override
            public Iterator<MapEntry> call() throws Exception {
                return mapEntries.getResolveMapsIterator("http/localhost.8080/target/justVanityPath");
            }
        });
        return new DataFuture(future);
    }

    void simulateSomewhatSlowSessionOperation(final Semaphore sessionLock) throws InterruptedException {
        if (!sessionLock.tryAcquire()) {
            fail("concurrent session access detected");
        }
        try{
            Thread.sleep(1);
        } finally {
            sessionLock.release();
        }
    }

    /**
     * Iterator to piggyback the list of Resources onto a Resource Mock
     * so that we can add children to them and create the iterators after
     * everything is setup
     */
    static interface ResourceDecorator {
        public List<Resource> getChildrenList();
    }

    static class DataFuture {
        public Future<Iterator<?>> future;

        public DataFuture(Future<Iterator<?>> future) {
            super();
            this.future = future;
        }
    }

}
