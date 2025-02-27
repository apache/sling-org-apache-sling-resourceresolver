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
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    public AliasMapEntriesTest() {
    }

    private AutoCloseable mockCloser;

    @Override
    @SuppressWarnings({ "unchecked" })
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
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(
                Collections.emptyIterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(
                Collections.emptyIterator());
        //when(resourceResolverFactory.getAliasPath()).thenReturn(Arrays.asList("/child"));

        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Set.of());

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        final Field aliasMapField = MapEntries.class.getDeclaredField("aliasMapsMap");
        aliasMapField.setAccessible(true);
        this.aliasMap = (Map<String, Map<String, String>>) aliasMapField.get(mapEntries);

        final Field detectedInvalidAliasesField = MapEntries.class.getDeclaredField("detectedInvalidAliases");
        detectedInvalidAliasesField.setAccessible(true);
        this.detectedInvalidAliases = (AtomicLong) detectedInvalidAliasesField.get(mapEntries);

        final Field detectedConflictingAliasesField = MapEntries.class.getDeclaredField("detectedConflictingAliases");
        detectedConflictingAliasesField.setAccessible(true);
        this.detectedConflictingAliases = (AtomicLong) detectedConflictingAliasesField.get(mapEntries);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mapEntries.dispose();
        mockCloser.close();
    }

    private static void addResource(MapEntries mapEntries, String path, AtomicBoolean bool) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private static void removeResource(MapEntries mapEntries, String path, AtomicBoolean bool) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("removeResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private static void removeAlias(MapEntries mapEntries, String contentPath,  String path, AtomicBoolean bool) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, contentPath, path, bool);
    }

    private static void updateResource(MapEntries mapEntries, String path, AtomicBoolean bool) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        Method method = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(mapEntries, path, bool);
    }

    private void internal_test_simple_alias_support(boolean onJcrContent) {
        prepareMapEntriesForAlias(onJcrContent, false, "alias");
        mapEntries.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("child"));
        assertEquals(List.of("alias"), aliasMap.get("child"));
    }

    @Test
    public void test_simple_alias_support() {
        internal_test_simple_alias_support(false);
    }

    @Test
    public void test_simple_alias_support_on_jcr_content() {
        internal_test_simple_alias_support(true);
    }

    private void internal_test_simple_multi_alias_support(boolean onJcrContent) {
        prepareMapEntriesForAlias(onJcrContent, false, "foo", "bar");
        mapEntries.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("child"));
        assertEquals(List.of("foo", "bar"), aliasMap.get("child"));
    }

    @Test
    public void internal_test_simple_alias_support_throwing_unsupported_operation_exception_exception() {
        prepareMapEntriesForAlias(false, false, UnsupportedOperationException.class, "foo", "bar");
        assertFalse(mapEntries.initializeAliases());
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
        mapEntries.initializeAliases();
        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertFalse(aliasMap.containsKey("child"));
    }

    @Test
    public void test_simple_multi_alias_support_with_blank_and_invalid() {
        // invalid aliases filtered out
        prepareMapEntriesForAlias(false, false, "", "foo", ".", "bar", "x/y", "qux", " ");
        mapEntries.initializeAliases();
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
            mapEntries.initializeAliases();
            Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
            assertEquals(Collections.emptyMap(), aliasMap);
        }
        assertEquals(invalidAliases.size(), detectedInvalidAliases.get());
    }

    private void prepareMapEntriesForAlias(boolean onJcrContent, boolean withNullParent, String... aliases) {
        prepareMapEntriesForAlias(onJcrContent, withNullParent, null, aliases);
    }

    private void prepareMapEntriesForAlias(boolean onJcrContent, boolean withNullParent, Class<? extends Exception> queryThrowsWith,
                                           String... aliases) {
        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        final Resource content = mock(Resource.class);
        final Resource aliasResource = onJcrContent ? content : result;

        when(result.getParent()).thenReturn(withNullParent && !onJcrContent ? null : parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");

        when(content.getParent()).thenReturn(withNullParent && onJcrContent ? null : result);
        when(content.getPath()).thenReturn("/parent/child/jcr:content");
        when(content.getName()).thenReturn("jcr:content");

        when(aliasResource.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, aliases));

        if (queryThrowsWith == null) {
            when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
                if (invocation.getArguments()[0].toString().contains(ResourceResolverImpl.PROP_ALIAS)) {
                    return List.of(aliasResource).iterator();
                } else {
                    return Collections.emptyIterator();
                }
            });
        } else {
            when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenThrow(queryThrowsWith);
        }
    }

    @Test
    public void test_that_duplicate_alias_does_not_replace_first_alias() {
        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        final Resource secondResult = mock(Resource.class);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer((Answer<Iterator<Resource>>) invocation -> {
            if (invocation.getArguments()[0].toString().contains(ResourceResolverImpl.PROP_ALIAS)) {
                return Arrays.asList(result, secondResult).iterator();
            } else {
                return Collections.emptyIterator();
            }
        });

        mapEntries.initializeAliases();

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMap.get("child"));
        assertEquals(1, detectedConflictingAliases.get());
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

    //SLING-3727
    @Test
    public void test_doAddAliasAttributesWithDisableAliasOptimization() throws Exception {
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
    }

    //SLING-3727
    @Test
    public void test_doUpdateAttributesWithDisableAliasOptimization() throws Exception {
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMap);
    }

    //SLING-3727
    @Test
    public void test_doRemoveAttributesWithDisableAliasOptimization() throws Exception {
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        removeAlias(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMap = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMap);
    }

    @Test
    public void test_doAddAlias() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        //test_that_duplicate_alias_does_not_replace_first_alias
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        //testing jcr:content node
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size()); // only 2 aliases for 1 child resource
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias","aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());
    }

    @Test
    public void test_doAddAlias2() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        //test_that_duplicate_alias_does_not_replace_first_alias
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent2");
        when(secondResult.getName()).thenReturn("parent2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(Collections.singletonList("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        //testing jcr:content node
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias","aliasJcrContent"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        //trying to add invalid alias path
        final Resource invalidResourcePath = mock(Resource.class);
        when(resourceResolver.getResource("/notallowedparent")).thenReturn(invalidResourcePath);
        when(invalidResourcePath.getParent()).thenReturn(parent);
        when(invalidResourcePath.getPath()).thenReturn("/notallowedparent");
        when(invalidResourcePath.getName()).thenReturn("notallowedparent");
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
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
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

        //testing jcr:content node update
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated","aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated", "aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        //re-update alias
        updateResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated","aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        //add another node with different alias and check that the update doesn't break anything (see also SLING-3728)
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias2"));

        updateResource(mapEntries, "/parent/child2", new AtomicBoolean());
        assertEquals(1, aliasMap.size());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());

        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasUpdated","aliasJcrContentUpdated"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());


        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, null));
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
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
        // check that alias map is empty
        assertEquals(0, aliasMap.size());

        final Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource child = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(child);
        when(child.getParent()).thenReturn(parent);
        when(child.getPath()).thenReturn("/parent/child");
        when(child.getName()).thenReturn("child");
        when(child.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent/child")).thenReturn(child);
        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("alias"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias2() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap());

        //testing jcr:content node removal
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        addResource(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child"));
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("child"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
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
        removeAlias(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias3() throws Exception {
        assertEquals(0, aliasMap.size());

        final Resource parentRsrc = mock(Resource.class);
        when(parentRsrc.getPath()).thenReturn("/parent");

        final Resource childRsrc = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(childRsrc);
        when(childRsrc.getParent()).thenReturn(parentRsrc);
        when(childRsrc.getPath()).thenReturn("/parent/child");
        when(childRsrc.getName()).thenReturn("child");
        when(childRsrc.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent/child", new AtomicBoolean());

        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(childRsrc);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);

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
        removeAlias(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

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
        removeAlias(mapEntries, "/parent", "/parent/child", new AtomicBoolean());
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
        removeAlias(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

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
        assertEquals(List.of("alias","aliasJcrContent"), aliasMapEntry.get("child"));
        assertEquals(1, aliasMapEntry.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn( null);
        removeAlias(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        assertEquals(0, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertEquals(Collections.emptyMap(), aliasMapEntry);
    }

    @Test
    public void test_doRemoveAlias4() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource(mapEntries, "/parent", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias(mapEntries, "/", "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        addResource(mapEntries, "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("alias"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias(mapEntries, "/", "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias5() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap());

        //testing jcr:content node removal
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        addResource(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("parent"));
        assertEquals(List.of("aliasJcrContent"), aliasMapEntry.get("parent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias(mapEntries, "/", "/parent/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertEquals(Collections.emptyMap(), aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    // SLING-10476
    @Test
    public void test_doNotRemoveAliasWhenJCRContentDeletedInParentPath() throws Exception {
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(parent);
        when(parent.getParent()).thenReturn(parent);
        when(parent.getPath()).thenReturn("/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap());

        final Resource container = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container")).thenReturn(container);
        when(container.getParent()).thenReturn(parent);
        when(container.getPath()).thenReturn("/parent/container");
        when(container.getName()).thenReturn("container");
        when(container.getValueMap()).thenReturn(buildValueMap());
        when(parent.getChild("container")).thenReturn(container);

        final Resource jcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/jcr:content")).thenReturn(jcrContent);
        when(jcrContent.getParent()).thenReturn(container);
        when(jcrContent.getPath()).thenReturn("/parent/container/jcr:content");
        when(jcrContent.getName()).thenReturn("jcr:content");
        when(jcrContent.getValueMap()).thenReturn(buildValueMap());
        when(container.getChild("jcr:content")).thenReturn(jcrContent);

        final Resource childContainer = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer")).thenReturn(childContainer);
        when(childContainer.getParent()).thenReturn(container);
        when(childContainer.getPath()).thenReturn("/parent/container/childContainer");
        when(childContainer.getName()).thenReturn("childContainer");
        when(childContainer.getValueMap()).thenReturn(buildValueMap());
        when(container.getChild("childContainer")).thenReturn(childContainer);

        final Resource grandChild = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer/grandChild")).thenReturn(grandChild);
        when(grandChild.getParent()).thenReturn(childContainer);
        when(grandChild.getPath()).thenReturn("/parent/container/childContainer/grandChild");
        when(grandChild.getName()).thenReturn("grandChild");
        when(grandChild.getValueMap()).thenReturn(buildValueMap());
        when(childContainer.getChild("grandChild")).thenReturn(grandChild);

        final Resource grandChildJcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer/grandChild/jcr:content")).thenReturn(grandChildJcrContent);
        when(grandChildJcrContent.getParent()).thenReturn(grandChild);
        when(grandChildJcrContent.getPath()).thenReturn("/parent/container/childContainer/grandChild/jcr:content");
        when(grandChildJcrContent.getName()).thenReturn("jcr:content");
        when(grandChildJcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "gc"));
        when(grandChild.getChild("jcr:content")).thenReturn(grandChildJcrContent);

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
        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        when(resourceResolver.getResource("/parent")).thenReturn(parent);
        when(parent.getParent()).thenReturn(parent);
        when(parent.getPath()).thenReturn("/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap());

        final Resource child1 = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child1")).thenReturn(child1);
        when(child1.getParent()).thenReturn(parent);
        when(child1.getPath()).thenReturn("/parent/child1");
        when(child1.getName()).thenReturn("child1");
        when(child1.getValueMap()).thenReturn(buildValueMap());

        final Resource child1JcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child1/jcr:content")).thenReturn(child1JcrContent);
        when(child1JcrContent.getParent()).thenReturn(child1);
        when(child1JcrContent.getPath()).thenReturn("/parent/child1/jcr:content");
        when(child1JcrContent.getName()).thenReturn("jcr:content");
        when(child1JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test1"));
        when(child1.getChild("jcr:content")).thenReturn(child1JcrContent);

        when(parent.getChild("child1")).thenReturn(child1);

        addResource(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());

        Map<String, Collection<String>> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));

        assertEquals(1, aliasMap.size());

        final Resource child2 = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(child2);
        when(child2.getParent()).thenReturn(parent);
        when(child2.getPath()).thenReturn("/parent/child2");
        when(child2.getName()).thenReturn("child2");
        when(child2.getValueMap()).thenReturn(buildValueMap());

        final Resource child2JcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2/jcr:content")).thenReturn(child2JcrContent);
        when(child2JcrContent.getParent()).thenReturn(child2);
        when(child2JcrContent.getPath()).thenReturn("/parent/child2/jcr:content");
        when(child2JcrContent.getName()).thenReturn("jcr:content");
        when(child2JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test2"));
        when(child2.getChild("jcr:content")).thenReturn(child2JcrContent);

        when(parent.getChild("child2")).thenReturn(child2);

        addResource(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("child1"));
        assertTrue(aliasMapEntry.containsKey("child2"));
        assertEquals(List.of("test1"), aliasMapEntry.get("child1"));
        assertEquals(List.of("test2"), aliasMapEntry.get("child2"));

        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        final Resource child2JcrContentChild = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2/jcr:content/test")).thenReturn(child2JcrContentChild);
        when(child2JcrContentChild.getParent()).thenReturn(child2);
        when(child2JcrContentChild.getPath()).thenReturn("/parent/child2/jcr:content/test");
        when(child2JcrContentChild.getName()).thenReturn("test");
        when(child2JcrContent.getChild("test")).thenReturn(child2JcrContentChild);

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

        when(child1.getChild("jcr:content")).thenReturn(child1JcrContent);
        addResource(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());
        when(child2.getChild("jcr:content")).thenReturn(child2JcrContent);
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
}
