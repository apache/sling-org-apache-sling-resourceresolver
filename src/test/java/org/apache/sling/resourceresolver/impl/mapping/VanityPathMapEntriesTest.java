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
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider.VanityPathConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class VanityPathMapEntriesTest extends AbstractMappingMapEntriesTest {

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

    private final boolean isMaxCachedVanityPathEntriesStartup;
    private final boolean isVanityPathCacheInitInBackground;

    @Parameters(name="isMaxCachedVanityPathEntriesStartup={0}, isVanityPathCacheInitInBackground={1}")
    public static Collection<Object[]> data() {
        return List.of(new Object[][] {
                {false, true},
                {true, false},
                {true, false}}
        );
    }

    public VanityPathMapEntriesTest(boolean isMaxCachedVanityPathEntriesStartup,
                                    boolean isVanityPathCacheInitInBackground) {
        this.isMaxCachedVanityPathEntriesStartup = isMaxCachedVanityPathEntriesStartup;
        this.isVanityPathCacheInitInBackground = isVanityPathCacheInitInBackground;
    }

    @Override
    @SuppressWarnings({ "unchecked" })
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.openMocks(this);

        final List<VanityPathConfig> configs = new ArrayList<>();
        configs.add(new VanityPathConfig("/libs/", false));
        configs.add(new VanityPathConfig("/libs/denied", true));
        configs.add(new VanityPathConfig("/foo/", false));
        configs.add(new VanityPathConfig("/baa/", false));
        configs.add(new VanityPathConfig("/justVanityPath", false));
        configs.add(new VanityPathConfig("/justVanityPath2", false));
        configs.add(new VanityPathConfig("/badVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath301", false));
        configs.add(new VanityPathConfig("/vanityPathOnJcrContent", false));

        Collections.sort(configs);
        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceUserAuthenticationInfo(anyString())).thenReturn(Map.of());
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(configs);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(isMaxCachedVanityPathEntriesStartup);
        when(resourceResolverFactory.isVanityPathCacheInitInBackground()).thenReturn(isVanityPathCacheInitInBackground);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(
                Collections.emptyIterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(
                Collections.emptyIterator());
        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Collections.emptySet());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);
        waitForBgInit();
    }

    // get internal flag that signals completion of background task
    private AtomicBoolean getVanityPathsProcessed() {
        try {
            Field field = MapEntries.class.getDeclaredField("vanityPathsProcessed");
            field.setAccessible(true);
            return (AtomicBoolean) field.get(mapEntries);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // wait for background thread to complete
    private void waitForBgInit() {
        while (!getVanityPathsProcessed().get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    // get vanity paths (after waiting for bg init to complete)
    private void initializeVanityPaths() throws IOException {
        mapEntries.vph.initializeVanityPaths();
        waitForBgInit();
    }

    @Override
    @After
    public void tearDown() {
        mapEntries.dispose();
    }

    @Test
    public void test_simple_vanity_path() throws IOException {
        String vanityPath = "/xyz";
        String containerName = "foo";
        String childName = "child";
        String oneMore = "one-more";
        prepareMapEntriesForVanityPath(false, false, containerName,
                childName, oneMore, vanityPath);

        initializeVanityPaths();

        Map<String, List<String>> vanityMap = mapEntries.getVanityPathMappings();
        assertNotNull(vanityMap);
        assertEquals(vanityPath, vanityMap.get("/" + containerName + "/" + childName).get(0));
        assertEquals(2, vanityMap.size());
        assertNotNull(vanityMap.get("/" + containerName + "/" + oneMore));
    }

    // see SLING-12620
    @Test
    public void test_simple_vanity_path_support_with_null_parent() throws IOException {
        String vanityPath = "/xyz";
        String containerName = "foo";
        String childName = "child";
        String oneMore = "one-more";
        prepareMapEntriesForVanityPath(true, true, containerName,
                childName, oneMore, vanityPath);

        initializeVanityPaths();

        Map<String, List<String>> vanityMap = mapEntries.getVanityPathMappings();
        assertNotNull(vanityMap);
        // not present
        assertNull(vanityMap.get("/" + containerName + "/" + childName));
        assertNull(vanityMap.get("/" + containerName + "/" + childName + "/jcr:content"));
        // but the other one is present
        assertEquals(1, vanityMap.size());
        assertNotNull(vanityMap.get("/" + containerName + "/" + oneMore));
    }

    // create a 'custom' node (two flags), followed by a hardwired one (this is used to check that vanity path
    // processing does not abort after the first error
    private void prepareMapEntriesForVanityPath(boolean onJcrContent, boolean withNullParent,
                                                String containerName, String childName,
                                                String additionalChildName, String vanityPath) {

        final Resource parent = mock(Resource.class);

        when(parent.getParent()).thenReturn(null);
        when(parent.getPath()).thenReturn("/" + containerName);
        when(parent.getName()).thenReturn(containerName);

        final Resource vanity = mock(Resource.class);

        when(vanity.getParent()).thenReturn(withNullParent && !onJcrContent ? null : parent);
        when(vanity.getPath()).thenReturn("/" + containerName + "/" + childName);
        when(vanity.getName()).thenReturn(childName);

        final Resource content = mock(Resource.class);

        when(content.getParent()).thenReturn(withNullParent && onJcrContent ? null : vanity);
        when(content.getPath()).thenReturn("/" + containerName + "/" + childName + "/jcr:content");
        when(content.getName()).thenReturn("jcr:content");

        final Resource oneMore = mock(Resource.class);

        when(oneMore.getParent()).thenReturn(parent);
        when(oneMore.getPath()).thenReturn("/" + containerName + "/" + additionalChildName);
        when(oneMore.getName()).thenReturn(additionalChildName);

        when(oneMore.getValueMap()).thenReturn(buildValueMap(MapEntries.PROP_VANITY_PATH, vanityPath + "/onemore"));

        final Resource vanityPropHolder = onJcrContent ? content : vanity;

        when(vanityPropHolder.getValueMap()).thenReturn(buildValueMap(MapEntries.PROP_VANITY_PATH, vanityPath));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains(MapEntries.PROP_VANITY_PATH)) {
                return List.of(vanityPropHolder, oneMore).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });
    }

    @Test
    public void test_vanity_path_registration() throws Exception {
        // specifically making this a weird value because we want to verify that
        // the configuration value is being used
        int DEFAULT_VANITY_STATUS = 333333;

        when(resourceResolverFactory.getDefaultVanityPathRedirectStatus()).thenReturn(DEFAULT_VANITY_STATUS);

        final List<Resource> resources = new ArrayList<>();

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));
        resources.add(justVanityPath);

        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));
        resources.add(badVanityPath);

        Resource redirectingVanityPath = mock(Resource.class, "redirectingVanityPath");
        when(redirectingVanityPath.getPath()).thenReturn("/redirectingVanityPath");
        when(redirectingVanityPath.getName()).thenReturn("redirectingVanityPath");
        when(redirectingVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/redirectingVanityPath", "sling:redirect", true));
        resources.add(redirectingVanityPath);

        Resource redirectingVanityPath301 = mock(Resource.class, "redirectingVanityPath301");
        when(redirectingVanityPath301.getPath()).thenReturn("/redirectingVanityPath301");
        when(redirectingVanityPath301.getName()).thenReturn("redirectingVanityPath301");
        when(redirectingVanityPath301.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/redirectingVanityPath301", "sling:redirect", true, "sling:redirectStatus", 301));
        resources.add(redirectingVanityPath301);

        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));
        resources.add(vanityPathOnJcrContent);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            String query = invocation.getArguments()[0].toString();
            if (matchesPagedQuery(query)) {
                String path = extractStartPath(query);
                resources.sort(vanityResourceComparator);
                return resources.stream().filter(e -> getFirstVanityPath(e).compareTo(path) > 0).iterator();
            } else if (query.contains("sling:vanityPath")) {
                return resources.iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        initializeVanityPaths();

        List<MapEntry> entries = mapEntries.getResolveMaps();

        assertEquals(8, entries.size());
        for (MapEntry entry : entries) {
            if (entry.getPattern().contains("/target/redirectingVanityPath301")) {
                assertEquals(301, entry.getStatus());
                assertFalse(entry.isInternal());
            } else if (entry.getPattern().contains("/target/redirectingVanityPath")) {
                assertEquals(DEFAULT_VANITY_STATUS, entry.getStatus());
                assertFalse(entry.isInternal());
            } else if (entry.getPattern().contains("/target/justVanityPath")) {
                assertTrue(entry.isInternal());
            } else if (entry.getPattern().contains("/target/vanityPathOnJcrContent")) {
                for (String redirect : entry.getRedirect()) {
                    assertFalse(redirect.contains("jcr:content"));
                }
            }
        }

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(4, vanityTargets.size());
    }

    @Test
    public void test_vanity_path_updates() throws Exception {
        Resource parent = mock(Resource.class, "parent");
        when(parent.getPath()).thenReturn("/foo/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(new ValueMapDecorator(Collections.emptyMap()));
        when(resourceResolver.getResource(parent.getPath())).thenReturn(parent);

        Resource child = mock(Resource.class, "jcrcontent");
        when(child.getPath()).thenReturn("/foo/parent/jcr:content");
        when(child.getName()).thenReturn("jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found"));
        when(child.getParent()).thenReturn(parent);
        when(parent.getChild(child.getName())).thenReturn(child);
        when(resourceResolver.getResource(child.getPath())).thenReturn(child);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> Collections.emptyIterator());

        initializeVanityPaths();

        // map entries should have no alias atm
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // add parent
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.ADDED, parent.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // add child
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.ADDED, child.getPath(), false)));

        // two entries for the vanity path
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // update parent - no change
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.CHANGED, parent.getPath(), false)));
        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // update child - no change
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.CHANGED, child.getPath(), false)));
        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // remove child - empty again
        when(resourceResolver.getResource(child.getPath())).thenReturn(null);
        when(parent.getChild(child.getName())).thenReturn(null);
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.REMOVED, child.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // remove parent - still empty
        when(resourceResolver.getResource(parent.getPath())).thenReturn(null);
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.REMOVED, parent.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());
    }

    @Test
    public void test_vanity_path_updates_do_not_reload_multiple_times() throws IOException {
        Resource parent = mock(Resource.class, "parent");
        when(parent.getPath()).thenReturn("/foo/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found1"));
        when(resourceResolver.getResource(parent.getPath())).thenReturn(parent);

        Resource child = mock(Resource.class, "jcr:content");
        when(child.getPath()).thenReturn("/foo/parent/jcr:content");
        when(child.getName()).thenReturn("jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found2"));
        when(child.getParent()).thenReturn(parent);
        when(parent.getChild(child.getName())).thenReturn(child);
        when(resourceResolver.getResource(child.getPath())).thenReturn(child);

        Resource child2 = mock(Resource.class, "child2");
        when(child2.getPath()).thenReturn("/foo/parent/child2");
        when(child2.getName()).thenReturn("child2");
        when(child2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found3"));
        when(child2.getParent()).thenReturn(parent);
        when(parent.getChild(child2.getName())).thenReturn(child2);
        when(resourceResolver.getResource(child2.getPath())).thenReturn(child2);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> Collections.emptyIterator());

        initializeVanityPaths();

        // till now, we have exactly one event, generated by the MapEntries constructor
        Mockito.verify(eventAdmin, Mockito.times(1)).postEvent(ArgumentMatchers.any(Event.class));

        // 3 updates at the same onChange call
        mapEntries.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, parent.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child2.getPath(), false)
                ));

        // 6 entries for the vanity path
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(6, entries.size());

        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found1")));
        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found2")));
        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found3")));

        // an additional single event is sent for all 3 added vanity paths
        Mockito.verify(eventAdmin, Mockito.times(2)).postEvent(ArgumentMatchers.any(Event.class));
    }

    @Test
    public void test_vanity_path_registration_include_exclude() throws IOException {
        final String[] validPaths = {"/libs/somewhere", "/libs/a/b", "/foo/a", "/baa/a"};
        final String[] invalidPaths = {"/libs/denied/a", "/libs/denied/b/c", "/nowhere"};

        final List<Resource> resources = new ArrayList<>();
        for(final String val : validPaths) {
            resources.add(getVanityPathResource(val));
        }
        for(final String val : invalidPaths) {
            resources.add(getVanityPathResource(val));
        }

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            String query = invocation.getArguments()[0].toString();
            if (matchesPagedQuery(query)) {
                String path = extractStartPath(query);
                resources.sort(vanityResourceComparator);
                return resources.stream().filter(e -> getFirstVanityPath(e).compareTo(path) > 0).iterator();
            } else
            if (query.contains("sling:vanityPath")) {
                return resources.iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        initializeVanityPaths();

        List<MapEntry> entries = mapEntries.getResolveMaps();
        // each valid resource results in 2 entries
        assertEquals(validPaths.length * 2, entries.size());

        final Set<String> resultSet = new HashSet<>();
        for(final String p : validPaths) {
            resultSet.add(p + "$1");
            resultSet.add(p + ".html");
        }
        for (final MapEntry entry : entries) {
            assertTrue(resultSet.remove(entry.getRedirect()[0]));
        }
    }

    @Test
    public void test_getActualContentPath() throws Exception {

        Method method = MapEntries.class.getDeclaredMethod("getActualContentPath", String.class);
        method.setAccessible(true);

        String actualContent = (String) method.invoke(mapEntries, "/content");
        assertEquals("/content", actualContent);

        actualContent = (String) method.invoke(mapEntries, "/content/jcr:content");
        assertEquals("/content", actualContent);
    }

    @Test
    public void test_getMapEntryRedirect() throws Exception {

        Method method = MapEntries.class.getDeclaredMethod("getMapEntryRedirect", MapEntry.class);
        method.setAccessible(true);

        MapEntry mapEntry = new MapEntry("/content", -1, false, 0, "/content");
        String actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content$1");
        actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content.html");
        actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doAddVanity() throws Exception {
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        //bad vanity
        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(resourceResolver.getResource("/badVanityPath")).thenReturn(badVanityPath);
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource.invoke(mapEntries, "/badVanityPath", new AtomicBoolean());


        assertEquals(2, entries.size());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(1, vanityTargets.size());

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(4, counter.longValue());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doAddVanity_1() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(10L);

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        //bad vanity
        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(resourceResolver.getResource("/badVanityPath")).thenReturn(badVanityPath);
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource.invoke(mapEntries, "/badVanityPath", new AtomicBoolean());


        assertEquals(2, entries.size());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(1, vanityTargets.size());

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(4, counter.longValue());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }


    @SuppressWarnings("unchecked")
    @Test
    public void test_doUpdateVanity() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method updateResource = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        //update vanity path
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPathUpdated"));
        updateResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNotNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPathUpdated", vanityTargets.get("/justVanityPath").get(0));

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getValueMap()).thenReturn(buildValueMap());

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContent", vanityTargets.get("/vanityPathOnJcrContent").get(0));

        //update vanity path
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContentUpdated", vanityTargets.get("/vanityPathOnJcrContent").get(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doRemoveVanity() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Method method1 = MapEntries.VanityPathHandler.class.getDeclaredMethod("doRemoveVanity", String.class);
        method1.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        //remove vanity path
        method1.invoke(mapEntries.vph, "/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertEquals(1,vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContent", vanityTargets.get("/vanityPathOnJcrContent").get(0));

        //remove vanity path
        method1.invoke(mapEntries.vph, "/vanityPathOnJcrContent/jcr:content");

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));

    }
/*
    @SuppressWarnings("unchecked")
    @Test
    public void test_doUpdateVanityOrder() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        Method method = MapEntries.class.getDeclaredMethod("doAddVanity", String.class);
        method.setAccessible(true);

        Method method1 = MapEntries.class.getDeclaredMethod("doUpdateVanityOrder", String.class, boolean.class);
        method1.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        method.invoke(mapEntries, "/justVanityPath");

        Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        method.invoke(mapEntries, "/justVanityPath2");

        assertEquals(2, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));

        Iterator <MapEntry> iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 1000));
        method1.invoke(mapEntries, "/justVanityPath",false);

        iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));
        method1.invoke(mapEntries, "/justVanityPath",true);

        iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());
    }
*/

    @Test
    public void test_isValidVanityPath() throws Exception {
        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("isValidVanityPath", String.class);
        method.setAccessible(true);

        assertFalse((Boolean)method.invoke(mapEntries.vph, "/jcr:system/node"));

        assertTrue((Boolean)method.invoke(mapEntries.vph, "/justVanityPath"));
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_1() throws Exception {

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/notExisting");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_2() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(this.isMaxCachedVanityPathEntriesStartup ? 2 : 0, counter.longValue());

        final Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        method.invoke(mapEntries.vph, "/target/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(this.isMaxCachedVanityPathEntriesStartup ? 4 : 0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_3() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_4() throws Exception {

        final Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(badVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/content/mypage/en-us-{132");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_5() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        final Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        method.invoke(mapEntries.vph, "/target/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_loadVanityPaths() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("loadVanityPaths", ResourceResolver.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, resourceResolver);

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_loadVanityPaths_1() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        Method method = MapEntries.VanityPathHandler.class.getDeclaredMethod("loadVanityPaths", ResourceResolver.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, resourceResolver);

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getMapEntryList() throws Exception {

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());

        final Resource justVanityPath = mock(Resource.class,
                "justVanityPath");

        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);

        when(justVanityPath.getPath()).thenReturn("/justVanityPath");

        when(justVanityPath.getName()).thenReturn("justVanityPath");

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath",
                "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(),
                eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    if
                    (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        Method method =
                MapEntries.VanityPathHandler.class.getDeclaredMethod("getMapEntryList", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/target/justVanityPath");

        final int expected = 2;

        entries = mapEntries.getResolveMaps();
        assertEquals(expected, entries.size());

        Field vanityCounter =
                MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(expected, counter.longValue());

        method.invoke(mapEntries.vph, "/target/justVanityPath");

        entries = mapEntries.getResolveMaps();
        assertEquals(expected, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(expected, counter.longValue());
    }

    @Test
    //SLING-4883
    public void test_concurrent_getResolveMapsIterator() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                return Collections.singleton(justVanityPath).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });


        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        ArrayList<DataFuture> list = new ArrayList<>();
        for (int i =0;i<10;i++) {
            list.add(createDataFuture(pool, mapEntries));
        }

       for (DataFuture df : list) {
           df.future.get();
        }

    }

    @Test
    public void test_vanitypath_disabled() throws Exception {
        // initialize with having vanity path disabled - must not throw errors here or on disposal
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(false);

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        mapEntries.initializeAliases();
    }

    // utilities for testing vanity path queries

    private static String VPQSTART = "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] WHERE NOT isdescendantnode('/jcr:system') AND [sling:vanityPath] IS NOT NULL AND FIRST([sling:vanityPath]) >= '";
    private static String VPQEND = "' ORDER BY FIRST([sling:vanityPath])";

    private boolean matchesPagedQuery(String query) {
        return query.startsWith(VPQSTART) && query.endsWith(VPQEND);
    }

    private String extractStartPath(String query) {
        String remainder = query.substring(VPQSTART.length());
        return remainder.substring(0, remainder.length() - VPQEND.length());
    }

    private String getFirstVanityPath(Resource r) {
        String[] vp = r.getValueMap().get("sling:vanityPath", new String[0]);
        return vp.length == 0 ? "": vp[0];
    }

    private final Comparator<Resource> vanityResourceComparator = (o1, o2) -> {
        String s1 = getFirstVanityPath(o1);
        String s2 = getFirstVanityPath(o2);
        return s1.compareTo(s2);
    };
}