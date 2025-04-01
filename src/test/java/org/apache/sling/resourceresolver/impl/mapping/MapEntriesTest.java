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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests related to {@link MapEntries} that are not specific to aliases or
 * vanity paths.
 */
public class MapEntriesTest extends AbstractMappingMapEntriesTest {

    private MapEntries mapEntries;

    @Mock
    private MapConfigurationProvider resourceResolverFactory;

    @Mock
    private BundleContext bundleContext;

    @Mock
    private Bundle bundle;

    @Mock
    private ResourceResolver resourceResolver;

    @Mock
    private EventAdmin eventAdmin;

    public MapEntriesTest() {}

    private AutoCloseable mockCloser;

    @Override
    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        this.mockCloser = MockitoAnnotations.openMocks(this);

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(List.of());
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(true);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(Collections.emptyIterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(Collections.emptyIterator());
        // when(resourceResolverFactory.getAliasPath()).thenReturn(Arrays.asList("/child"));

        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Set.of());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(
                resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        final Field aliasMapField = MapEntries.class.getDeclaredField("aliasMapsMap");
        aliasMapField.setAccessible(true);
        this.aliasMap = (Map<String, Map<String, String>>) aliasMapField.get(mapEntries);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mapEntries.dispose();
        mockCloser.close();
    }

    @Test
    // SLING-4847
    public void test_doNodeAdded1() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);
        final AtomicBoolean refreshed = new AtomicBoolean(false);
        addResource.invoke(mapEntries, "/node", refreshed);
        assertTrue(refreshed.get());
    }

    // tests SLING-6542
    @Test
    public void sessionConcurrency() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);
        final Method updateResource =
                MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);
        final Method handleConfigurationUpdate = MapEntries.class.getDeclaredMethod(
                "handleConfigurationUpdate", String.class, AtomicBoolean.class, AtomicBoolean.class, boolean.class);
        handleConfigurationUpdate.setAccessible(true);

        final Semaphore sessionLock = new Semaphore(1);
        // simulate somewhat slow (1ms) session operations that use locking
        // to determine that they are using the session exclusively.
        // if that lock mechanism detects concurrent access we fail
        Mockito.doAnswer((Answer<Void>) invocation -> {
                    simulateSomewhatSlowSessionOperation(sessionLock);
                    return null;
                })
                .when(resourceResolver)
                .refresh();
        Mockito.doAnswer((Answer<Resource>) invocation -> {
                    simulateSomewhatSlowSessionOperation(sessionLock);
                    return null;
                })
                .when(resourceResolver)
                .getResource(any(String.class));

        when(resourceResolverFactory.isMapConfiguration(any(String.class))).thenReturn(true);

        final AtomicInteger failureCnt = new AtomicInteger(0);
        final List<Exception> exceptions = new LinkedList<>();
        final Semaphore done = new Semaphore(0);
        final int NUM_THREADS = 30;
        final Random random = new Random(12321);
        for (int i = 0; i < NUM_THREADS; i++) {
            final int randomWait = random.nextInt(10);
            Runnable r = () -> {
                try {
                    Thread.sleep(randomWait);
                    for (int i1 = 0; i1 < 3; i1++) {
                        addResource.invoke(mapEntries, "/node", new AtomicBoolean());
                        updateResource.invoke(mapEntries, "/node", new AtomicBoolean());
                        handleConfigurationUpdate.invoke(
                                mapEntries, "/node", new AtomicBoolean(), new AtomicBoolean(), false);
                    }
                } catch (Exception e) {
                    // e.printStackTrace();
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                    failureCnt.incrementAndGet();
                } finally {
                    done.release();
                }
            };
            Thread th = new Thread(r);
            th.setDaemon(true);
            th.start();
        }
        assertTrue("threads did not finish in time", done.tryAcquire(NUM_THREADS, 30, TimeUnit.SECONDS));
        if (failureCnt.get() != 0) {
            synchronized (exceptions) {
                throw new AssertionError(
                        "exceptions encountered (" + failureCnt.get() + "). First exception: ", exceptions.get(0));
            }
        }
    }

    @Test
    public void testLoadAliases_ValidAbsolutePath_DefaultPaths() {
        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Collections.emptySet());

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = StringUtils.trim((String) invocation.getArguments()[0]);
                    assertEquals(
                            "SELECT [sling:alias] FROM [nt:base] WHERE NOT isdescendantnode('/jcr:system') AND [sling:alias] IS NOT NULL AND FIRST([sling:alias]) >= '' ORDER BY FIRST([sling:alias])",
                            query);
                    return Collections.emptyIterator();
                });

        mapEntries.initializeAliases();
    }
}
