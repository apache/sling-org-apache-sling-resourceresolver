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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests related to {@link MapEntries} that are specific to aliases.
 */
@RunWith(Parameterized.class)
public class AliasMapEntriesTest extends AbstractMappingMapEntriesTest {

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

    private Map<String, Map<String, String>> aliasMap;
    private AtomicLong detectedInvalidAliases;
    private AtomicLong detectedConflictingAliases;

    private static final Runnable NOOP = () -> {};

    private final boolean isOptimizeAliasResolutionEnabled;

    private final boolean isAliasCacheInitInBackground;

    @Parameterized.Parameters(name = "isOptimizeAliasResolutionEnabled={0},isAliasCacheInitInBackground{1}")
    public static Collection<Object[]> data() {
        // (optimized==false && backgroundInit == false) does not need to be tested
        return List.of(new Object[][] {{false, false}, {true, false}, {true, true}});
    }

    public AliasMapEntriesTest(boolean isOptimizeAliasResolutionEnabled, boolean isAliasCacheInitInBackground) {
        this.isOptimizeAliasResolutionEnabled = isOptimizeAliasResolutionEnabled;
        this.isAliasCacheInitInBackground = isAliasCacheInitInBackground;
    }

    private AutoCloseable mockCloser;

    @Override
    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        this.mockCloser = MockitoAnnotations.openMocks(this);

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);

        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Set.of());
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(isOptimizeAliasResolutionEnabled);
        when(resourceResolverFactory.isAliasCacheInitInBackground()).thenReturn(isAliasCacheInitInBackground);
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);

        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(Collections.emptyIterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(Collections.emptyIterator());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(
                resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        waitForBgInit();

        final Field aliasMapField = AliasHandler.class.getDeclaredField("aliasMapsMap");
        aliasMapField.setAccessible(true);
        this.aliasMap = (Map<String, Map<String, String>>) aliasMapField.get(mapEntries.ah);

        final Field detectedInvalidAliasesField = AliasHandler.class.getDeclaredField("detectedInvalidAliases");
        detectedInvalidAliasesField.setAccessible(true);
        this.detectedInvalidAliases = (AtomicLong) detectedInvalidAliasesField.get(mapEntries.ah);

        final Field detectedConflictingAliasesField = AliasHandler.class.getDeclaredField("detectedConflictingAliases");
        detectedConflictingAliasesField.setAccessible(true);
        this.detectedConflictingAliases = (AtomicLong) detectedConflictingAliasesField.get(mapEntries.ah);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mapEntries.dispose();
        mockCloser.close();
    }

    // wait for background thread to complete
    private void waitForBgInit() {
        if (this.isOptimizeAliasResolutionEnabled) {
            long start = System.currentTimeMillis();
            while (!mapEntries.ah.isReady()) {
                // give up after five seconds
                assertFalse("init should be done withing five seconds", System.currentTimeMillis() - start > 5000);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // ignored
                }
            }
        }
    }

    private static void addResource(MapEntries mapEntries, String path, AtomicBoolean bool)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private static void removeResource(MapEntries mapEntries, String path, AtomicBoolean bool)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("removeResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private static void removeAlias(
            MapEntries mapEntries,
            ResourceResolver resourceResolver,
            String contentPath,
            String path,
            Runnable callback)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = AliasHandler.class.getDeclaredMethod(
                "removeAlias", ResourceResolver.class, String.class, String.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(mapEntries.ah, resourceResolver, contentPath, path, callback);
    }

    private static void updateResource(MapEntries mapEntries, String path, AtomicBoolean bool)
            throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private void internal_test_simple_alias_support(boolean onJcrContent, boolean cached) {
        Resource parent = prepareMapEntriesForAlias(onJcrContent, false, !cached, false, "alias");
        mapEntries.ah.initializeAliases();

        Map<String, Collection<String>> aliasMapString = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapString);
        assertTrue(aliasMapString.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapString.get("child"));

        Map<String, Collection<String>> aliasMapResource = mapEntries.getAliasMap(parent);
        assertNotNull(aliasMapResource);
        assertTrue(aliasMapResource.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapResource.get("child"));
    }

    @Test
    public void test_simple_alias_support() {
        internal_test_simple_alias_support(false, true);
    }

    @Test
    public void test_simple_alias_support_uncached() {
        internal_test_simple_alias_support(false, false);
    }

    @Test
    public void test_simple_alias_support_on_jcr_content() {
        internal_test_simple_alias_support(true, true);
    }

    private void internal_test_simple_multi_alias_support(boolean onJcrContent) {
        prepareMapEntriesForAlias(onJcrContent, false, "foo", "bar");
        mapEntries.ah.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("child"));
        assertEquals(List.of("foo", "bar"), aliasMap.get("child"));
    }

    @Test
    public void internal_test_simple_alias_support_throwing_unsupported_operation_exception_exception() {
        prepareMapEntriesForAlias(false, false, true, false, "foo", "bar");
        mapEntries.ah.initializeAliases();
        assertFalse(mapEntries.ah.usesCache());
    }

    @Test
    public void internal_test_simple_alias_support_throwing_query_syntax_exception_exception() {
        Assume.assumeTrue(
                "simulation of query exceptions only meaningful in 'optimized' case",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        prepareMapEntriesForAlias(false, false, false, true, "foo", "bar");
        mapEntries.ah.initializeAliases();
        waitForBgInit();
        assertTrue(mapEntries.ah.usesCache());
    }

    @Test
    public void test_simple_multi_alias_support() {
        internal_test_simple_multi_alias_support(false);
    }

    @Test
    public void test_simple_multi_alias_support_on_jcr_content() {
        internal_test_simple_multi_alias_support(true);
    }

    @Test
    public void test_simple_multi_alias_support_with_null_parent() {
        // see SLING-12383
        prepareMapEntriesForAlias(true, true, "foo", "bar");
        mapEntries.ah.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertFalse(aliasMap.containsKey("child"));
    }

    @Test
    public void test_simple_multi_alias_support_with_blank_and_invalid() {
        // invalid aliases filtered out
        prepareMapEntriesForAlias(false, false, "", "foo", ".", "bar", "x/y", "qux", " ");
        mapEntries.ah.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("child"));
        assertEquals(List.of("foo", "bar", "qux", " "), aliasMap.get("child"));
        assertEquals(3, detectedInvalidAliases.get());
    }

    @Test
    public void test_alias_support_invalid() {
        List<String> invalidAliases = List.of(".", "..", "foo/bar", "# foo", "");
        for (String invalidAlias : invalidAliases) {
            prepareMapEntriesForAlias(false, false, invalidAlias);
            mapEntries.ah.initializeAliases();
            waitForBgInit();
            Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
            assertEquals(Collections.emptyMap(), aliasMap);
        }
        assertEquals(invalidAliases.size(), detectedInvalidAliases.get());
    }

    private void prepareMapEntriesForAlias(boolean onJcrContent, boolean withNullParent, String... aliases) {
        prepareMapEntriesForAlias(onJcrContent, withNullParent, false, false, aliases);
    }

    private Resource prepareMapEntriesForAlias(
            boolean onJcrContent,
            boolean withNullParent,
            boolean queryAlwaysThrows,
            boolean pagedQueryThrows,
            String... aliases) {

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");
        Resource content = createMockedResource(result, "jcr:content");

        when(result.getParent()).thenReturn(withNullParent && !onJcrContent ? null : parent);
        when(content.getParent()).thenReturn(withNullParent && onJcrContent ? null : result);

        Resource aliasResource = onJcrContent ? content : result;

        when(aliasResource.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, aliases));

        when(resourceResolver.getResource(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.equals(parent.getPath())) {
                return parent;
            } else if (path.equals(result.getPath())) {
                return result;
            } else if (path.equals(content.getPath())) {
                return content;
            } else {
                return null;
            }
        });

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArgument(0);
                    if (queryAlwaysThrows) {
                        throw new UnsupportedOperationException("test case configured to always throw: " + query);
                    } else {
                        if (pagedQueryThrows && matchesPagedQuery(query)) {
                            throw new QuerySyntaxException(
                                    "test case configured to throw for paged queries", query, "JCR-SQL2");
                        } else if (query.equals(AQ_SIMPLE) || matchesPagedQuery(query)) {
                            return List.of(aliasResource).iterator();
                        } else {
                            throw new RuntimeException("unexpected query: " + query);
                        }
                    }
                });

        return parent;
    }

    @Test
    public void test_that_duplicate_alias_does_not_replace_first_alias() {

        // note that this test depends on the order of nodes returned
        // on getChildren

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        Resource secondResult = createMockedResource(parent, "child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArgument(0);
                    if (query.equals(AQ_SIMPLE) || matchesPagedQuery(query)) {
                        return Arrays.asList(result, secondResult).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        mapEntries.ah.initializeAliases();

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue("map should contain 'child': " + aliasMap, aliasMap.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMap.get("child"));
        assertEquals(1, detectedConflictingAliases.get());
    }

    // checks that alias lists for "x" and "x/jcr:content" are merged
    private void internal_test_alias_on_parent_and_on_content_child(boolean cached) {
        Resource parent = createMockedResource("/parent");
        Resource node = createMockedResource(parent, "node");
        Resource content = createMockedResource(node, "jcr:content");

        when(node.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));
        when(content.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "contentalias"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArgument(0);
                    if (query.equals(AQ_SIMPLE) || matchesPagedQuery(query)) {
                        return List.of(node, content).iterator();
                    } else {
                        return Collections.emptyIterator();
                    }
                });

        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(cached);
        mapEntries.ah.initializeAliases();

        // "/parent" has aliases both from "/parent/node" and "/parent/node/jcr:content"
        Map<String, Collection<String>> parentAliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull("alias map should never be null", parentAliasMap);
        assertTrue("should have entry for node 'node'", parentAliasMap.containsKey("node"));
        List<@NotNull String> nodeAliases = List.of("alias", "contentalias");
        assertEquals(
                "alias map " + parentAliasMap + " should have " + nodeAliases.size() + " entries",
                nodeAliases.size(),
                parentAliasMap.get("node").size());
        assertTrue("alias", parentAliasMap.get("node").containsAll(nodeAliases));

        // "/parent/node" has no aliases
        Map<String, Collection<String>> nodeAliasMap = mapEntries.getAliasMap("/parent/node");
        assertNotNull("alias map should never be null", nodeAliasMap);
        assertEquals(0, nodeAliasMap.size());
    }

    // checks that alias lists for "x" and "x/jcr:content" are merged
    @Test
    public void test_alias_on_parent_and_on_content_child_cached() {
        internal_test_alias_on_parent_and_on_content_child(true);
    }

    // checks that alias lists for "x" and "x/jcr:content" are merged
    @Test
    public void test_alias_on_parent_and_on_content_child_uncached() {
        internal_test_alias_on_parent_and_on_content_child(false);
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
    public void test_allowed_locations_query() {
        Assume.assumeTrue(
                "allowed alias locations only processed in 'optimized' mode",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Set.of("/a", "/'b'"));
        Set<String> queryMade = new HashSet<>();
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2")))
                .thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                    String query = invocation.getArgument(0);
                    if (query.contains("alias")) {
                        queryMade.add(query);
                    }
                    return Collections.emptyIterator();
                });

        mapEntries.ah.initializeAliases();
        waitForBgInit();

        assertFalse("seems no alias query was made", queryMade.isEmpty());
        String match1 = "(isdescendantnode('/a') OR isdescendantnode('/''b'''))";
        String match2 = "(isdescendantnode('/''b''') OR isdescendantnode('/a'))";
        String actual = queryMade.iterator().next();
        assertTrue(
                "query should contain '" + match1 + "' (or reversed), but was: '" + actual + "'",
                actual.contains(match1) || actual.contains(match2));
    }

    // SLING-3727
    @Test
    public void test_doAddAliasAttributesWithDisableAliasOptimization() throws Exception {
        Assume.assumeFalse(
                "checks behaviour for non-optimized case only",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
    }

    // SLING-3727
    @Test
    public void test_doUpdateAttributesWithDisableAliasOptimization() throws Exception {
        Assume.assumeFalse(
                "checks behaviour for non-optimized case only",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        Map<String, Collection<String>> aliasMapBefore = mapEntries.getAliasMap("/parent");
        assertEquals(1, aliasMapBefore.size());

        // this simulates an add event, but that is immaterial here as there is no cache anyway
        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapAfter = mapEntries.getAliasMap("/parent");
        assertEquals(1, aliasMapAfter.size());

        assertEquals(aliasMapBefore, aliasMapAfter);
    }

    // SLING-3727
    @Test
    public void test_doRemoveAttributesWithDisableAliasOptimization() throws Exception {
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(
                resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        Map<String, Collection<String>> aliasMapBefore = mapEntries.getAliasMap("/parent");
        assertEquals(1, aliasMapBefore.size());

        // this simulates a remove event, but that is immaterial here as there is no cache anyway
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child", NOOP);

        Map<String, Collection<String>> aliasMapAfter = mapEntries.getAliasMap("/parent");
        assertEquals(1, aliasMapAfter.size());

        assertEquals(aliasMapBefore, aliasMapAfter);
    }

    @Test
    public void test_doAddAlias() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        // test_that_duplicate_alias_does_not_replace_first_alias
        Resource secondResult = createMockedResource(parent, "child2");

        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        // testing jcr:content node
        Resource jcrContentResult = createMockedResource(result, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size()); // only 2 aliases for 1 child resource
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());
    }

    @Test
    public void test_doAddAlias2() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/");
        Resource result = createMockedResource(parent, "parent");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        // test_that_duplicate_alias_does_not_replace_first_alias
        Resource secondResult = createMockedResource(parent, "parent2");

        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        // testing jcr:content node
        Resource jcrContentResult = createMockedResource(result, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        // trying to add invalid alias path
        Resource invalidResourcePath = createMockedResource(parent, "notallowedparent");

        when(invalidResourcePath.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/notallowedparent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("parent"));
        assertEquals(1, aliasMap.size());
    }

    @Test
    public void test_doUpdateAlias() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        updateResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasUpdated"));

        updateResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        // testing jcr:content node update
        Resource jcrContentResult = createMockedResource(result, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated", "aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated", "aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        // re-update alias
        updateResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated", "aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        // add another node with different alias and check that the update doesn't break anything (see also SLING-3728)
        Resource secondResult = createMockedResource(parent, "child2");

        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias2"));

        updateResource(mapEntries, "/parent/child2", new AtomicBoolean());
        assertEquals(1, aliasMap.size());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated", "aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, null));
        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        Resource child = createMockedResource(parent, "child");

        when(child.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        // re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent/child")).thenReturn(child);
        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias2() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        Resource result = createMockedResource(parent, "child");

        when(result.getValueMap()).thenReturn(buildValueMap());

        // testing jcr:content node removal
        Resource jcrContentResult = createMockedResource(result, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child/jcr:content", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        // re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child/jcr:content", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias3() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parentRsrc = createMockedResource("/parent");
        Resource childRsrc = createMockedResource(parentRsrc, "child");

        when(childRsrc.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Resource jcrContentResult = createMockedResource(childRsrc, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        // test with two nodes
        assertEquals(1, aliasMap.size());
        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("child"));

        // remove child jcr:content node
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(childRsrc.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child/jcr:content", NOOP);

        // test with one node
        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        // re-add the node and test /parent/child
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        // STOP
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("child"));

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child", NOOP);
        when(resourceResolver.getResource("/parent/child")).thenReturn(childRsrc);
        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("child"));

        // re-add the node and test node removal
        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasJcrContent", "alias"), aliasMapEntry.get("child"));
        assertEquals(1, aliasMapEntry.size());

        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(childRsrc.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child/jcr:content", NOOP);

        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());

        // re-add the node and test node removal for  /parent/child
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias", "aliasJcrContent"), aliasMapEntry.get("child"));
        assertEquals(1, aliasMapEntry.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/parent", "/parent/child", NOOP);

        assertEquals(0, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);
    }

    @Test
    public void test_doRemoveAlias4() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/");
        Resource result = createMockedResource(parent, "parent");

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/", "/parent", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        // re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        addResource(mapEntries, "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/", "/parent", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias5() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/");
        Resource result = createMockedResource(parent, "parent");

        when(result.getValueMap()).thenReturn(buildValueMap());

        // testing jcr:content node removal
        Resource jcrContentResult = createMockedResource(result, "jcr:content");

        when(jcrContentResult.getValueMap())
                .thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, resourceResolver, "/", "/parent/jcr:content", NOOP);

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    // SLING-10476
    @Test
    public void test_doNotRemoveAliasWhenJCRContentDeletedInParentPath() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        when(parent.getValueMap()).thenReturn(buildValueMap());

        Resource container = createMockedResource(parent, "container");

        when(container.getValueMap()).thenReturn(buildValueMap());

        Resource jcrContent = createMockedResource(container, "jcr:content");

        when(jcrContent.getValueMap()).thenReturn(buildValueMap());

        Resource childContainer = createMockedResource(container, "childContainer");

        when(childContainer.getValueMap()).thenReturn(buildValueMap());

        Resource grandChild = createMockedResource(childContainer, "grandChild");

        when(grandChild.getValueMap()).thenReturn(buildValueMap());

        Resource grandChildJcrContent = createMockedResource(grandChild, "jcr:content");

        when(grandChildJcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "gc"));

        addResource(mapEntries, grandChildJcrContent.getPath(), new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent/container/childContainer");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("grandChild"));
        assertEquals(List.of("gc"), aliasMapEntry.get("grandChild"));

        // delete the jcr:content present in a parent path
        when(container.getChild("jcr:content")).thenReturn(null);
        removeResource(mapEntries, jcrContent.getPath(), new AtomicBoolean());

        // Alias of the other resources under the same parent of deleted jcr:content, should not be deleted
        aliasMapEntry = mapEntries.getAliasMap("/parent/container/childContainer");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("grandChild"));
        assertEquals(List.of("gc"), aliasMapEntry.get("grandChild"));
    }

    @Test
    public void test_doRemoveAliasFromSibling() throws Exception {
        Assume.assumeTrue(
                "observation events have no effect when no cache is used",
                resourceResolverFactory.isOptimizeAliasResolutionEnabled());

        assertEquals(0, aliasMap.size());

        Resource parent = createMockedResource("/parent");
        Resource child1 = createMockedResource(parent, "child1");
        Resource child1JcrContent = createMockedResource(child1, "jcr:content");

        when(child1JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test1"));

        addResource(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));

        assertEquals(1, aliasMap.size());

        Resource child2 = createMockedResource(parent, "child2");
        Resource child2JcrContent = createMockedResource(child2, "jcr:content");

        when(child2JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test2"));

        addResource(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertTrue(aliasMapEntry.containsKey("child2"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));
        assertEquals(List.of("test2"), aliasMapEntry.get("child2"));

        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        Resource child2JcrContentChild = createMockedResource(child2, "test");

        removeResource(mapEntries, child2JcrContentChild.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertTrue(aliasMapEntry.containsKey("child2"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));
        assertEquals(List.of("test2"), aliasMapEntry.get("child2"));

        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        when(child2.getChild("jcr:content")).thenReturn(null);

        removeResource(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));

        assertEquals(1, aliasMap.size());
        assertEquals(1, mapEntries.getAliasMap("/parent").size());

        when(child1.getChild("jcr:content")).thenReturn(null);

        removeResource(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        addResource(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());
        addResource(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertTrue(aliasMapEntry.containsKey("child2"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));
        assertEquals(List.of("test2"), aliasMapEntry.get("child2"));

        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        removeResource(mapEntries, parent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);
    }

    @Test
    public void test_doRemoveAliasNullResolver() throws Exception {
        removeAlias(mapEntries, null, "/x", null, NOOP);
    }

    @Test
    public void test_initAliasesAfterDispose() {
        AliasHandler ah = mapEntries.ah;
        mapEntries.dispose();
        ah.initializeAliases();
        assertFalse("alias handler should not have set up cache", ah.usesCache());
    }

    // utilities for testing alias queries

    // used for paged query of all
    private static final String AQ_PAGED_START =
            "SELECT [sling:alias] FROM [nt:base] WHERE NOT isdescendantnode('/jcr:system') AND [sling:alias] IS NOT NULL AND FIRST([sling:alias]) >= '";
    private static final String AQ_PAGED_END = "' ORDER BY FIRST([sling:alias])";

    private static final Pattern AQ_PAGED_PATTERN =
            Pattern.compile(Pattern.quote(AQ_PAGED_START) + "(?<path>\\p{Alnum}*)" + Pattern.quote(AQ_PAGED_END));

    // used when paged query not available
    private static final String AQ_SIMPLE =
            "SELECT [sling:alias] FROM [nt:base] WHERE NOT isdescendantnode('/jcr:system') AND [sling:alias] IS NOT NULL";

    // sanity test on matcher
    @Test
    public void testMatcher() {
        assertTrue(AQ_PAGED_PATTERN.matcher(AQ_PAGED_START + AQ_PAGED_END).matches());
        assertTrue(
                AQ_PAGED_PATTERN.matcher(AQ_PAGED_START + "xyz" + AQ_PAGED_END).matches());
        assertEquals(
                1,
                AQ_PAGED_PATTERN.matcher(AQ_PAGED_START + "xyz" + AQ_PAGED_END).groupCount());
        Matcher m1 = AQ_PAGED_PATTERN.matcher(AQ_PAGED_START + "xyz" + AQ_PAGED_END);
        assertTrue(m1.find());
        assertEquals("xyz", m1.group("path"));
    }

    private boolean matchesPagedQuery(String query) {
        return AQ_PAGED_PATTERN.matcher(query).matches();
    }

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
