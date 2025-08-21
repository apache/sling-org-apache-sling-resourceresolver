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
import java.lang.reflect.InvocationTargetException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider.VanityPathConfig;
import org.junit.After;
import org.junit.Assume;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests related to {@link MapEntries} that are specific to vanity paths.
 */
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

    @Parameters(name = "isMaxCachedVanityPathEntriesStartup={0}, isVanityPathCacheInitInBackground={1}")
    public static Collection<Object[]> data() {
        return List.of(new Object[][] {
            {false, true},
            {true, false},
            {true, false}
        });
    }

    public VanityPathMapEntriesTest(
            boolean isMaxCachedVanityPathEntriesStartup, boolean isVanityPathCacheInitInBackground) {
        this.isMaxCachedVanityPathEntriesStartup = isMaxCachedVanityPathEntriesStartup;
        this.isVanityPathCacheInitInBackground = isVanityPathCacheInitInBackground;
    }

    private AutoCloseable mockCloser;

    @Override
    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        this.mockCloser = MockitoAnnotations.openMocks(this);

        final List<VanityPathConfig> configs = new ArrayList<>();
        configs.add(new VanityPathConfig("/libs/", false));
        configs.add(new VanityPathConfig("/libs/denied", true));
        configs.add(new VanityPathConfig("/foo/", false));
        configs.add(new VanityPathConfig("/baa/", false));
        configs.add(new VanityPathConfig("/justVanityPath", false));
        configs.add(new VanityPathConfig("/justVanityPath2", false));
        configs.add(new VanityPathConfig("/badVanityPath", false));
        configs.add(new VanityPathConfig("/simpleVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath301", false));
        configs.add(new VanityPathConfig("/vanityPathOnJcrContent", false));
        configs.add(new VanityPathConfig("/eventTest", false));

        Collections.sort(configs);
        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceUserAuthenticationInfo(anyString()))
                .thenReturn(Map.of());
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(configs);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup())
                .thenReturn(isMaxCachedVanityPathEntriesStartup);
        when(resourceResolverFactory.isVanityPathCacheInitInBackground()).thenReturn(isVanityPathCacheInitInBackground);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(Collections.emptyIterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(Collections.emptyIterator());
        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Collections.emptySet());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(
                resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);
        waitForBgInit();
    }

    // wait for background thread to complete
    private void waitForBgInit() {
        long start = System.currentTimeMillis();
        while (!mapEntries.vph.isReady()) {
            // give up after five seconds
            assertFalse("init should be done withing five seconds", System.currentTimeMillis() - start > 5000);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    // get vanity paths (after waiting for bg init to complete)
    private void initializeVanityPaths() {
        mapEntries.vph.initializeVanityPaths();
        waitForBgInit();
    }

    private static AtomicLong getVanityCounter(MapEntries mapEntries)
            throws NoSuchFieldException, IllegalAccessException {
        Field vanityCounter = VanityPathHandler.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        return (AtomicLong) vanityCounter.get(mapEntries.vph);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> getVanityTargets(MapEntries mapEntries)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = VanityPathHandler.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        return (Map<String, List<String>>) field.get(mapEntries.vph);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<MapEntry>> getResolveMapsMap(MapEntries mapEntries)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = MapEntries.class.getDeclaredField("resolveMapsMap");
        field.setAccessible(true);
        return (Map<String, List<MapEntry>>) field.get(mapEntries);
    }

    private static void getVanityPaths(MapEntries mapEntries, String path)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = VanityPathHandler.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, path);
    }

    private static void addResource(MapEntries mapEntries, String path, AtomicBoolean bool)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private static void loadVanityPaths(MapEntries mapEntries, ResourceResolver resourceResolver)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = VanityPathHandler.class.getDeclaredMethod("loadVanityPaths", ResourceResolver.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, resourceResolver);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mapEntries.dispose();
        mockCloser.close();
    }

    @Test
    public void test_simple_vanity_path() {
        String vanityPath = "/xyz";
        String containerName = "foo";
        String childName = "child";
        String oneMore = "one-more";
        prepareMapEntriesForVanityPath(false, false, containerName, childName, oneMore, vanityPath);

        initializeVanityPaths();

        Map<String, List<String>> vanityMap = mapEntries.getVanityPathMappings();
        assertNotNull(vanityMap);
        assertEquals(
                vanityPath, vanityMap.get("/" + containerName + "/" + childName).get(0));
        assertEquals(2, vanityMap.size());
        assertNotNull(vanityMap.get("/" + containerName + "/" + oneMore));
    }

    // see SLING-12620
    @Test
    public void test_simple_vanity_path_support_with_null_parent() {
        String vanityPath = "/xyz";
        String containerName = "foo";
        String childName = "child";
        String oneMore = "one-more";
        prepareMapEntriesForVanityPath(true, true, containerName, childName, oneMore, vanityPath);

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
    private void prepareMapEntriesForVanityPath(
            boolean onJcrContent,
            boolean withNullParent,
            String containerName,
            String childName,
            String additionalChildName,
            String vanityPath) {

        Resource parent = createMockedResource("/" + containerName);

        Resource vanity = createMockedResource("/" + containerName + "/" + childName);
        when(vanity.getParent()).thenReturn(withNullParent && !onJcrContent ? null : parent);

        Resource content = createMockedResource("/" + containerName + "/" + childName + "/jcr:content");
        when(content.getParent()).thenReturn(withNullParent && onJcrContent ? null : vanity);

        Resource oneMore = createMockedResource(parent, additionalChildName);
        when(oneMore.getValueMap())
                .thenReturn(buildValueMap(VanityPathHandler.PROP_VANITY_PATH, vanityPath + "/onemore"));

        Resource vanityPropHolder = onJcrContent ? content : vanity;

        when(vanityPropHolder.getValueMap()).thenReturn(buildValueMap(VanityPathHandler.PROP_VANITY_PATH, vanityPath));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesPagedQuery(query)) {
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

        List<Resource> resources = new ArrayList<>();

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));
        resources.add(justVanityPath);

        Resource badVanityPath = createMockedResource("/badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));
        resources.add(badVanityPath);

        Resource redirectingVanityPath = createMockedResource("/redirectingVanityPath");
        when(redirectingVanityPath.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/redirectingVanityPath", "sling:redirect", true));
        resources.add(redirectingVanityPath);

        Resource redirectingVanityPath301 = createMockedResource("/redirectingVanityPath301");
        when(redirectingVanityPath301.getValueMap())
                .thenReturn(buildValueMap(
                        "sling:vanityPath",
                        "/target/redirectingVanityPath301",
                        "sling:redirect",
                        true,
                        "sling:redirectStatus",
                        301));
        resources.add(redirectingVanityPath301);

        Resource vanityPathOnJcrContentParent = createMockedResource("/vanityPathOnJcrContent");
        Resource vanityPathOnJcrContent = createMockedResource(vanityPathOnJcrContentParent, "jcr:content");
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));
        resources.add(vanityPathOnJcrContent);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesPagedQuery(query)) {
                        String path = extractStartPath(query);
                        resources.sort(vanityResourceComparator);
                        return resources.stream()
                                .filter(e -> getFirstVanityPath(e).compareTo(path) > 0)
                                .iterator();
                    } else if (query.equals(VPQ_SIMPLE)) {
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

        assertEquals(4, getVanityTargets(mapEntries).size());
    }

    @Test
    public void test_vanity_path_updates() {
        Resource parent = createMockedResource("/foo/parent");
        when(parent.getValueMap()).thenReturn(new ValueMapDecorator(Collections.emptyMap()));

        Resource child = createMockedResource(parent, "jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> Collections.emptyIterator());

        initializeVanityPaths();

        // map entries should have no alias atm
        assertTrue(mapEntries.getResolveMaps().isEmpty());

        // add parent
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.ADDED, parent.getPath(), false)));
        assertTrue(mapEntries.getResolveMaps().isEmpty());

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
        assertTrue(mapEntries.getResolveMaps().isEmpty());

        // remove parent - still empty
        when(resourceResolver.getResource(parent.getPath())).thenReturn(null);
        mapEntries.onChange(List.of(new ResourceChange(ChangeType.REMOVED, parent.getPath(), false)));
        assertTrue(mapEntries.getResolveMaps().isEmpty());
    }

    @Test
    public void test_vanity_path_updates_do_not_reload_multiple_times() {
        Resource parent = createMockedResource("/foo/parent");
        when(parent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found1"));

        Resource child = createMockedResource(parent, "/foo/parent/jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found2"));

        Resource child2 = createMockedResource(parent, "child2");
        when(child2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found3"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> Collections.emptyIterator());

        initializeVanityPaths();

        // till now, we have exactly one event, generated by the MapEntries constructor
        Mockito.verify(eventAdmin, Mockito.times(1)).postEvent(ArgumentMatchers.any(Event.class));

        // 3 updates at the same onChange call
        mapEntries.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, parent.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child2.getPath(), false)));

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
    public void test_vanity_path_registration_include_exclude() {
        final String[] validPaths = {"/libs/somewhere", "/libs/a/b", "/foo/a", "/baa/a"};
        final String[] invalidPaths = {"/libs/denied/a", "/libs/denied/b/c", "/nowhere"};

        final List<Resource> resources = new ArrayList<>();
        for (final String val : validPaths) {
            resources.add(getVanityPathResource(val));
        }
        for (final String val : invalidPaths) {
            resources.add(getVanityPathResource(val));
        }

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesPagedQuery(query)) {
                        String path = extractStartPath(query);
                        resources.sort(vanityResourceComparator);
                        return resources.stream()
                                .filter(e -> getFirstVanityPath(e).compareTo(path) > 0)
                                .iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        initializeVanityPaths();

        List<MapEntry> entries = mapEntries.getResolveMaps();
        // each valid resource results in 2 entries
        assertEquals(validPaths.length * 2, entries.size());

        final Set<String> resultSet = new HashSet<>();
        for (final String p : validPaths) {
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

        Method method = VanityPathHandler.class.getDeclaredMethod("getMapEntryRedirect", MapEntry.class);
        method.setAccessible(true);

        MapEntry mapEntry = new MapEntry("/content", -1, false, 0, "/content");
        String actualContent = (String) method.invoke(mapEntries.vph, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content$1");
        actualContent = (String) method.invoke(mapEntries.vph, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content.html");
        actualContent = (String) method.invoke(mapEntries.vph, mapEntry);
        assertEquals("/content", actualContent);
    }

    @Test
    public void test_doAddVanity() throws Exception {
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        assertEquals(0, getVanityTargets(mapEntries).size());

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        assertEquals(2, getVanityCounter(mapEntries).longValue());

        // bad vanity
        Resource badVanityPath = createMockedResource("/badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource(mapEntries, "/badVanityPath", new AtomicBoolean());

        assertEquals(2, entries.size());
        assertEquals(1, getVanityTargets(mapEntries).size());

        // vanity under jcr:content
        Resource vanityPathOnJcrContentParent = createMockedResource("/vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = createMockedResource(vanityPathOnJcrContentParent, "jcr:content");
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        assertEquals(4, getVanityCounter(mapEntries).longValue());

        Map<String, List<String>> vanityTargets = getVanityTargets(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }

    @Test
    public void test_doAddVanity_1() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(10L);

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        assertEquals(0, getVanityTargets(mapEntries).size());

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        assertEquals(2, getVanityCounter(mapEntries).longValue());

        // bad vanity
        Resource badVanityPath = createMockedResource("/badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource(mapEntries, "/badVanityPath", new AtomicBoolean());

        assertEquals(2, entries.size());

        assertEquals(1, getVanityTargets(mapEntries).size());

        // vanity under jcr:content
        Resource vanityPathOnJcrContentParent = createMockedResource("/vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = createMockedResource(vanityPathOnJcrContentParent, "jcr:content");
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        assertEquals(4, getVanityCounter(mapEntries).longValue());

        Map<String, List<String>> vanityTargets = getVanityTargets(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }

    @Test
    public void test_doUpdateVanity() throws Exception {
        Map<String, List<MapEntry>> resolveMapsMap = getResolveMapsMap(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Map<String, List<String>> vanityTargets = getVanityTargets(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method updateResource =
                MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals(
                "/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        // update vanity path
        when(justVanityPath.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPathUpdated"));
        updateResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNotNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals(
                "/target/justVanityPathUpdated",
                vanityTargets.get("/justVanityPath").get(0));

        // vanity under jcr:content
        Resource vanityPathOnJcrContentParent = createMockedResource("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getValueMap()).thenReturn(buildValueMap());

        Resource vanityPathOnJcrContent = createMockedResource(vanityPathOnJcrContentParent, "jcr:content");
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals(
                "/target/vanityPathOnJcrContent",
                vanityTargets.get("/vanityPathOnJcrContent").get(0));

        // update vanity path
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals(
                "/target/vanityPathOnJcrContentUpdated",
                vanityTargets.get("/vanityPathOnJcrContent").get(0));
    }

    @Test
    public void test_doRemoveVanity() throws Exception {
        Map<String, List<MapEntry>> resolveMapsMap = getResolveMapsMap(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Map<String, List<String>> vanityTargets = getVanityTargets(mapEntries);
        assertEquals(0, vanityTargets.size());

        Method method1 = VanityPathHandler.class.getDeclaredMethod("doRemoveVanity", String.class);
        method1.setAccessible(true);

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, getVanityCounter(mapEntries).longValue());
        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals(
                "/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        // remove vanity path
        method1.invoke(mapEntries.vph, "/justVanityPath");

        assertEquals(0, getVanityCounter(mapEntries).longValue());

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));

        // vanity under jcr:content
        Resource vanityPathOnJcrContentParent = createMockedResource("/vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = createMockedResource(vanityPathOnJcrContentParent, "jcr:content");
        when(vanityPathOnJcrContent.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals(
                "/target/vanityPathOnJcrContent",
                vanityTargets.get("/vanityPathOnJcrContent").get(0));

        // remove vanity path
        method1.invoke(mapEntries.vph, "/vanityPathOnJcrContent/jcr:content");

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
    }
    /*
        @SuppressWarnings("unchecked")
        @Test
        public void test_doUpdateVanityOrder() throws Exception {
            Map<String, List<MapEntry>> resolveMapsMap = getResolveMapsMap(mapEntries);
            assertEquals(1, resolveMapsMap.size());

            Map<String, List<String>> vanityTargets = getVanityTargets(mapEntries);
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
        Method method = VanityPathHandler.class.getDeclaredMethod("isValidVanityPath", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(mapEntries.vph, "/jcr:system/node"));

        assertTrue((Boolean) method.invoke(mapEntries.vph, "/justVanityPath"));
    }

    @Test
    // SLING-4891
    public void test_getVanityPaths_1() throws Exception {

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        getVanityPaths(mapEntries, "/notExisting");

        assertEquals(0, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_getVanityPaths_2() throws Exception {

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        getVanityPaths(mapEntries, "/target/justVanityPath");

        assertEquals(
                this.isMaxCachedVanityPathEntriesStartup ? 2 : 0,
                getVanityCounter(mapEntries).longValue());

        Resource justVanityPath2 = createMockedResource("/justVanityPath2");
        when(justVanityPath2.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath", "sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        getVanityPaths(mapEntries, "/target/justVanityPath");

        assertEquals(
                this.isMaxCachedVanityPathEntriesStartup ? 4 : 0,
                getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_getVanityPaths_3() throws Exception {

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        getVanityPaths(mapEntries, "/target/justVanityPath");

        assertEquals(0, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_getVanityPaths_4() throws Exception {

        Resource badVanityPath = createMockedResource("/badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(badVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);

        getVanityPaths(mapEntries, "/content/mypage/en-us-{132");

        assertEquals(0, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_getVanityPaths_5() throws Exception {

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        getVanityPaths(mapEntries, "/target/justVanityPath");

        assertEquals(2, getVanityCounter(mapEntries).longValue());

        Resource justVanityPath2 = createMockedResource("/justVanityPath2");
        when(justVanityPath2.getValueMap())
                .thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath", "sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        getVanityPaths(mapEntries, "/target/justVanityPath");
        assertEquals(2, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_loadVanityPaths() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesPagedQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        loadVanityPaths(mapEntries, resourceResolver);

        assertEquals(2, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4891
    public void test_loadVanityPaths_1() throws Exception {

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesPagedQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        loadVanityPaths(mapEntries, resourceResolver);

        assertEquals(2, getVanityCounter(mapEntries).longValue());
    }

    @Test
    public void test_getMapEntryListWithoutExtension() throws Exception {
        test_getMapEntryList(false);
    }

    @Test
    public void test_getMapEntryListWithExtension() throws Exception {
        test_getMapEntryList(true);
    }

    private void test_getMapEntryList(boolean withExtension) throws Exception {

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        String vpName = "justVanityPath" + (withExtension ? ".txt" : "");

        Resource justVanityPath = createMockedResource("/" + vpName);
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        Method method = VanityPathHandler.class.getDeclaredMethod("getMapEntryList", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries.vph, "/target/justVanityPath");

        final int expected = 2;

        entries = mapEntries.getResolveMaps();
        assertEquals(expected, entries.size());

        if (withExtension) {
            assertEquals(
                    "^[^/]+/[^/]+/target/justVanityPath\\.txt", entries.get(0).getPattern());
            assertEquals("^[^/]+/[^/]+/target/justVanityPath$", entries.get(1).getPattern());
        } else {
            assertEquals(
                    "^[^/]+/[^/]+/target/justVanityPath(\\..*)", entries.get(0).getPattern());
            assertEquals("^[^/]+/[^/]+/target/justVanityPath$", entries.get(1).getPattern());
        }

        assertEquals(expected, getVanityCounter(mapEntries).longValue());

        method.invoke(mapEntries.vph, "/target/justVanityPath");

        entries = mapEntries.getResolveMaps();
        assertEquals(expected, entries.size());

        assertEquals(expected, getVanityCounter(mapEntries).longValue());
    }

    @Test
    // SLING-4883
    public void test_concurrent_getResolveMapsIterator() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);

        Resource justVanityPath = createMockedResource("/justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    if (matchesSpecificQuery(query)) {
                        return Collections.singleton(justVanityPath).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        ArrayList<DataFuture> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
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

        mapEntries = new MapEntries(
                resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        mapEntries.ah.initializeAliases();
    }

    @Test
    public void test_bg_init_fallback_while_not_ready() {
        // this is only applicable when background init is enabled
        assumeTrue(this.isVanityPathCacheInitInBackground);

        List<String> queries = Collections.synchronizedList(new ArrayList<>());

        Lock lock = new ReentrantLock();
        lock.lock();

        String targetPath = "/foo";

        Resource simpleVanityResource = createMockedResource("/simpleVanityPath");
        when(simpleVanityResource.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", targetPath));

        // exactly one resource found, both on regular (init) or specific query
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArguments()[0].toString();
                    try {
                        lock.lock();
                        queries.add(query);
                        if (matchesSpecificQueryFor(query, targetPath) || matchesPagedQuery(query)) {
                            return Collections.singleton(simpleVanityResource).iterator();
                        } else {
                            return Collections.emptyIterator();
                        }
                    } finally {
                        lock.unlock();
                    }
                });

        Iterator<MapEntry> mit;
        int expectedQueryCount = 0;
        int expectedCacheHits = 0;
        int expectedCacheMisses = 0;

        checkCounters("before tests", queries, expectedQueryCount, expectedCacheHits, expectedCacheMisses);

        mapEntries.vph.initializeVanityPaths();
        assertFalse("VPH should not be ready until unblocked", mapEntries.vph.isReady());
        checkCounters(
                "after launch of background init", queries, expectedQueryCount, expectedCacheHits, expectedCacheMisses);

        // do a forced lookup while background init runs, but is blocked
        mit = mapEntries.vph.getCurrentMapEntryForVanityPath(targetPath);
        expectedQueryCount += 1;
        expectedCacheMisses += 1;
        assertNotNull("map entry expected from query to repository", mit);
        checkCounters("after first forced lookup", queries, expectedQueryCount, expectedCacheHits, expectedCacheMisses);

        // intermediate map does not contain vanity path
        Map<String, List<String>> intermediateVanityMap = mapEntries.getVanityPathMappings();
        assertFalse(
                "while bg init is running, vanity map should not be updated yet",
                intermediateVanityMap.containsKey("/simpleVanityPath"));

        // do a forced lookup for a non-existing resource while init runs
        mit = mapEntries.vph.getCurrentMapEntryForVanityPath(targetPath + "-notfound");
        expectedQueryCount += 1;
        expectedCacheMisses += 1;
        assertNull("should be null for non-existing resource", mit);
        checkCounters(
                "after first forced lookup for non-existing resource",
                queries,
                expectedQueryCount,
                expectedCacheHits,
                expectedCacheMisses);

        // do a second forced lookup for a non-existing resource while init runs
        mit = mapEntries.vph.getCurrentMapEntryForVanityPath(targetPath + "-notfound");
        expectedCacheHits += 1;
        assertNull("should be null for non-existing resource", mit);
        checkCounters(
                "after second forced lookup for the same non-existing resource",
                queries,
                expectedQueryCount,
                expectedCacheHits,
                expectedCacheMisses);

        // try another forced lookup, should be cached
        mit = mapEntries.vph.getCurrentMapEntryForVanityPath(targetPath);
        expectedCacheHits += 1;
        assertNotNull(mit);
        checkCounters(
                "after second forced lookup for existing resource",
                queries,
                expectedQueryCount,
                expectedCacheHits,
                expectedCacheMisses);

        // send a change event for a resource with vanity path;
        // this will be queued while init is running and then processed later on
        Resource eventTest = createMockedResource("/eventTest");
        when(eventTest.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/baa"));

        // target for the resource got which we sent the event
        createMockedResource("/baa");

        mapEntries.onChange(List.of(new ResourceChange(ChangeType.ADDED, eventTest.getPath(), false)));

        // let initializer run, then wait until finished
        lock.unlock();
        waitForBgInit();
        expectedQueryCount += 1;

        // now one more query should have happened
        checkCounters("after initializer run", queries, expectedQueryCount, expectedCacheHits, expectedCacheMisses);

        // final map contains both vps (from direct lookup and from event)
        Map<String, List<String>> finalVanityMap = mapEntries.getVanityPathMappings();
        assertTrue(finalVanityMap.get("/simpleVanityPath").contains(targetPath));
        assertTrue(finalVanityMap.get("/eventTest").contains("/baa"));
    }

    @Test
    public void test_remove_vanity_path_during_bg_init() {
        Assume.assumeTrue(
                "simulation of resource removal during bg init only meaningful in 'bg init' case",
                resourceResolverFactory.isVanityPathCacheInitInBackground());

        Resource root = createMockedResource("/");
        Resource foo = createMockedResource(root, "foo");
        Resource leaf = createMockedResource(foo, "leaf");

        when(leaf.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/bar"));

        CountDownLatch greenLight = new CountDownLatch(1);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    greenLight.await();
                    return Set.of(leaf).iterator();
                });

        VanityPathHandler vph = mapEntries.vph;
        vph.initializeVanityPaths();
        assertFalse(vph.isReady());

        // bg init will wait until we give green light
        mapEntries.onChange(List.of(new ResourceChange(ResourceChange.ChangeType.REMOVED, leaf.getPath(), false)));

        greenLight.countDown();
        waitForBgInit();

        assertTrue(vph.isReady());

        Map<String, List<String>> vanityPathMappings = mapEntries.getVanityPathMappings();
        List<String> mappings = vanityPathMappings.get("/foo/leaf");

        assertNull("expected no (null) mapping for /foo/leaf", mappings);
    }

    // checks for the expected list of queries and the cache statistics
    private void checkCounters(
            String testName,
            List<String> queries,
            int expectedQueries,
            int expectedCacheHits,
            int expectedCacheMisses) {
        assertEquals(testName + " - queries: " + dumpQueries(queries), expectedQueries, queries.size());
        assertEquals(testName + " - cache hits", expectedCacheHits, mapEntries.vph.temporaryResolveMapsMapHits.get());
        assertEquals(
                testName + " - cache misses", expectedCacheMisses, mapEntries.vph.temporaryResolveMapsMapMisses.get());
    }

    private static String dumpQueries(List<String> queries) {
        return queries.size() + " queries (" + queries + ")";
    }

    @Test
    public void test_runtime_exception_during_init() {
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenThrow(new RuntimeException("forced to check behavior for fatal init errors"));
        // should not fail
        mapEntries.vph.initializeVanityPaths();
        // but state should not change to "ready"
        assertThrows("init should not be reported to be done", AssertionError.class, this::waitForBgInit);
    }

    // utilities for testing vanity path queries

    // used for paged query of all
    private static final String VPQ_PAGED_START =
            "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] WHERE "
                    + QueryBuildHelper.excludeSystemPath()
                    + " AND [sling:vanityPath] IS NOT NULL AND FIRST([sling:vanityPath]) >= '";
    private static final String VPQ_PAGED_END = "' ORDER BY FIRST([sling:vanityPath])";

    private static final Pattern VPQ_PAGED_PATTERN =
            Pattern.compile(Pattern.quote(VPQ_PAGED_START) + "(?<path>\\p{Alnum}*)" + Pattern.quote(VPQ_PAGED_END));

    // used when paged query not available
    private static final String VPQ_SIMPLE =
            "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base]" + " WHERE "
                    + QueryBuildHelper.excludeSystemPath() + " AND [sling:vanityPath] IS NOT NULL";

    // used when checking for specific vanity paths
    private static final String VPQ_SPECIFIC_START =
            "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] WHERE "
                    + QueryBuildHelper.excludeSystemPath() + " AND ([sling:vanityPath]='";
    private static final String VPQ_SPECIFIC_MIDDLE = "' OR [sling:vanityPath]='";
    private static final String VPQ_SPECIFIC_END = "') ORDER BY [sling:vanityOrder] DESC";

    private static final Pattern VPQ_SPECIFIC_PATTERN =
            Pattern.compile(Pattern.quote(VPQ_SPECIFIC_START) + "(?<path1>[/\\p{Alnum}]*)"
                    + Pattern.quote(VPQ_SPECIFIC_MIDDLE)
                    + "(?<path2>[/\\p{Alnum}]*)"
                    + Pattern.quote(VPQ_SPECIFIC_END));

    // sanity test on matcher
    @Test
    public void testMatcher() {
        assertTrue(VPQ_PAGED_PATTERN.matcher(VPQ_PAGED_START + VPQ_PAGED_END).matches());
        assertTrue(VPQ_PAGED_PATTERN
                .matcher(VPQ_PAGED_START + "xyz" + VPQ_PAGED_END)
                .matches());
        assertEquals(
                1,
                VPQ_PAGED_PATTERN
                        .matcher(VPQ_PAGED_START + "xyz" + VPQ_PAGED_END)
                        .groupCount());
        Matcher m1 = VPQ_PAGED_PATTERN.matcher(VPQ_PAGED_START + "xyz" + VPQ_PAGED_END);
        assertTrue(m1.find());
        assertEquals("xyz", m1.group("path"));

        Matcher m2 = VPQ_SPECIFIC_PATTERN.matcher(
                VPQ_SPECIFIC_START + "x/y" + VPQ_SPECIFIC_MIDDLE + "/x/y" + VPQ_SPECIFIC_END);
        assertTrue(m2.find());
        assertEquals("x/y", m2.group("path1"));
        assertEquals("/x/y", m2.group("path2"));
    }

    private boolean matchesPagedQuery(String query) {
        return VPQ_PAGED_PATTERN.matcher(query).matches();
    }

    private String extractStartPath(String query) {
        Matcher m = VPQ_PAGED_PATTERN.matcher(query);
        return m.find() ? m.group("path") : "";
    }

    private boolean matchesSpecificQuery(String query) {
        return VPQ_SPECIFIC_PATTERN.matcher(query).matches();
    }

    private boolean matchesSpecificQueryFor(String query, String path) {
        Matcher m = VPQ_SPECIFIC_PATTERN.matcher(query);
        if (!m.find()) {
            return false;
        } else {
            return path.equals(m.group("path1")) || path.equals(m.group("path2"));
        }
    }

    private String getFirstVanityPath(Resource r) {
        String[] vp = r.getValueMap().get("sling:vanityPath", new String[0]);
        return vp.length == 0 ? "" : vp[0];
    }

    private final Comparator<Resource> vanityResourceComparator = (o1, o2) -> {
        String s1 = getFirstVanityPath(o1);
        String s2 = getFirstVanityPath(o2);
        return s1.compareTo(s2);
    };

    private Resource createMockedResource(Resource parent, String name) {

        String path =
                ResourceUtil.normalize(parent.getPath() + (parent.getPath().equals("/") ? "" : "/") + name);
        Resource result = mock(Resource.class, "mock for " + path);

        // the basics
        when(result.getName()).thenReturn(ResourceUtil.getName(path));
        when(result.getPath()).thenReturn(path);

        // need to be specified later
        when(result.getValueMap()).thenReturn(ValueMap.EMPTY);

        // attach to resource resolver
        when(resourceResolver.getResource(path)).thenReturn(result);

        attachChildResource(parent, result);

        return result;
    }

    private Resource createMockedResource(String path) {
        Resource result = mock(Resource.class, "mock for " + path);

        // the basics
        when(result.getName()).thenReturn(ResourceUtil.getName(path));
        when(result.getPath()).thenReturn(path);

        // need to be attached later
        when(result.getChildren()).thenReturn(Set.of());
        when(result.getChild(anyString())).thenReturn(null);
        when(result.getParent()).thenReturn(null);

        // need to be specified later
        when(result.getValueMap()).thenReturn(ValueMap.EMPTY);

        // attach to resource resolver
        when(resourceResolver.getResource(path)).thenReturn(result);

        return result;
    }

    private void attachChildResource(Resource parent, Resource child) {

        List<Resource> newChildren = new ArrayList<>();
        parent.getChildren().forEach(newChildren::add);
        newChildren.add(child);

        when(parent.getChildren()).thenReturn(newChildren);
        when(parent.getChild(child.getName())).thenReturn(child);

        when(child.getParent()).thenReturn(parent);
    }
}
