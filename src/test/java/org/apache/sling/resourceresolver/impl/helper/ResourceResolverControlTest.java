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
package org.apache.sling.resourceresolver.impl.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.resourceresolver.impl.Fixture;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.SimpleValueMapImpl;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorageProvider;
import org.apache.sling.resourceresolver.impl.providers.stateful.AuthenticatedResourceProvider;
import org.apache.sling.resourceresolver.impl.providers.stateful.ProviderManager;
import org.apache.sling.resourceresolver.impl.providers.tree.PathTree;
import org.apache.sling.spi.resource.provider.QueryLanguageProvider;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.BundleContext;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;

@SuppressWarnings("unchecked")
public class ResourceResolverControlTest {

    private static final String TEST_ATTRIBUTE = "some.test.attribute";

    private static final List<String> TEST_FORBIDDEN_ATTRIBUTES = new ArrayList<String>();

    static {
            TEST_FORBIDDEN_ATTRIBUTES.add(ResourceResolverFactory.PASSWORD);
            TEST_FORBIDDEN_ATTRIBUTES.add(ResourceProvider.AUTH_SERVICE_BUNDLE);
            TEST_FORBIDDEN_ATTRIBUTES.add(ResourceResolverFactory.SUBSERVICE);
    }

    // query language names
    private static final String QL_MOCK = "MockQueryLanguage";
    private static final String QL_ANOTHER_MOCK = "AnotherMockQueryLanguage";
    private static final String QL_NOOP = "NoopQueryLanguage";

    // query definitions
    private static final String QUERY_MOCK_FIND_ALL = "FIND ALL";

    private ResourceResolverControl crp;
    private List<ResourceProviderHandler> handlers;
    private ResourceProvider<Object> subProvider;
    private Map<String, Object> authInfo;
    private ResourceProvider<Object> rootProvider;
    private Resource subProviderResource;
    private Resource somethingResource;
    private Resource someRootResource;
    private Resource somePathRootResource;
    private Resource somePathResource;
    private ResourceResolverContext context;

    @Before
    public void prepare() throws Exception {

        BundleContext bc = MockOsgi.newBundleContext();

        Fixture fixture = new Fixture(bc);

        // sub-provider
        subProvider = Mockito.mock(ResourceProvider.class);
        ResourceProviderInfo info = fixture.registerResourceProvider(subProvider, "/some/path", AuthType.required);
        ResourceProviderHandler handler = new ResourceProviderHandler(info, bc.getService(info.getServiceReference()));
        // second sub provider
        ResourceProvider<?> subProvider2 = Mockito.mock(ResourceProvider.class);
        ResourceProviderInfo info2 = fixture.registerResourceProvider(subProvider2, "/foo/path", AuthType.required);
        ResourceProviderHandler handler2 = new ResourceProviderHandler(info2, bc.getService(info2.getServiceReference()));

        when(subProvider.getQueryLanguageProvider()).thenReturn(new SimpleQueryLanguageProvider(QL_MOCK, QL_ANOTHER_MOCK) {
            @Override
            public Iterator<ValueMap> queryResources(ResolveContext<Object> ctx, String query, String language) {
                if ( query.equals(QUERY_MOCK_FIND_ALL) && language.equals(QL_MOCK)) {
                    SimpleValueMapImpl valueMap = new SimpleValueMapImpl();
                    valueMap.put("key", "value");
                    return Collections.<ValueMap> singletonList(valueMap).iterator();
                }

                throw new UnsupportedOperationException();
            }

            @Override
            public Iterator<Resource> findResources(ResolveContext<Object> ctx, String query, String language) {

                if ( query.equals(QUERY_MOCK_FIND_ALL) && language.equals(QL_MOCK)) {
                    return Collections.<Resource> singletonList(newMockResource("/some/path/object")).iterator();
                }

                throw new UnsupportedOperationException();

            }
        });
        handler.activate();
        handler2.activate();

        rootProvider = mock(ResourceProvider.class);
        ResourceProviderInfo rootInfo = fixture.registerResourceProvider(rootProvider, "/", AuthType.required);
        ResourceProviderHandler rootHandler = new ResourceProviderHandler(rootInfo, bc.getService(rootInfo.getServiceReference()));
        when(rootProvider.getQueryLanguageProvider()).thenReturn(new SimpleQueryLanguageProvider(QL_NOOP));
        rootHandler.activate();

        // configure mock resources
        Resource root = configureResourceAt(rootProvider, "/");
        somethingResource = configureResourceAt(rootProvider, "/something");
        subProviderResource = configureResourceAt(subProvider, "/some/path/object");
        someRootResource = configureResourceAt(rootProvider, "/some");
        somePathResource = configureResourceAt(subProvider, "/some/path");
        somePathRootResource = configureResourceAt(rootProvider, "/some/path");

        // configure query at '/'
        when(rootProvider.listChildren((ResolveContext<Object>) nullable(ResolveContext.class), Mockito.eq(root))).thenReturn(Arrays.asList(somethingResource, someRootResource).iterator());
        when(rootProvider.listChildren((ResolveContext<Object>) any(ResolveContext.class), Mockito.eq(someRootResource))).thenReturn(Arrays.asList(somePathRootResource).iterator());
        when(rootProvider.getResource((ResolveContext<Object>) any(ResolveContext.class), Mockito.eq("/some/path"), any(), any())).thenReturn(somePathResource);

        ResourceResolver rr = mock(ResourceResolver.class);
        ResourceAccessSecurityTracker securityTracker = Mockito.mock(ResourceAccessSecurityTracker.class);
        authInfo = getAuthInfo();

        handlers = Arrays.asList(rootHandler, handler, handler2);
        final ResourceProviderStorage storage = new ResourceProviderStorage(handlers);

        crp = new ResourceResolverControl(false, authInfo, new ResourceProviderStorageProvider() {

            @Override
            public ResourceProviderStorage getResourceProviderStorage() {
                return storage;
            }
        });
        context = new ResourceResolverContext(rr, securityTracker);
    }

    /** Return test auth info */
    private Map<String, Object> getAuthInfo() {
        final Map<String, Object> result = new HashMap<String, Object>();

        // Add all forbidden attributes to be able to verify that
        // they are masked
        for(String str : TEST_FORBIDDEN_ATTRIBUTES) {
            result.put(str, "should be hidden");
        }

        result.put(TEST_ATTRIBUTE, "is " + TEST_ATTRIBUTE);

        return result;
    }

    /**
     * Configures the provider to return a mock resource for the specified path
     * @return
     */
    private <T> Resource configureResourceAt(ResourceProvider<T> provider, String path) {

        Resource mockResource = newMockResource(path);

        when(provider.getResource((ResolveContext<T>) nullable(ResolveContext.class), Mockito.eq(path), nullable(ResourceContext.class), nullable(Resource.class)))
            .thenReturn(mockResource);

        return mockResource;
    }

    private Resource newMockResource(String path) {

        Resource mockResource = mock(Resource.class);
        when(mockResource.getPath()).thenReturn(path);
        when(mockResource.getName()).thenReturn(ResourceUtil.getName(path));
        when(mockResource.getResourceMetadata()).thenReturn(mock(ResourceMetadata.class));
        when(mockResource.getChildren()).thenReturn(Collections.<Resource> emptyList());

        return mockResource;
    }

    private Resource newSyntheticResource(final String path) {
        return new SyntheticResource(null, path, "type");
    }

    /**
     * Verifies that login and logout calls are invoked as expected on
     * ResourceProviders with authType = {@link AuthType#required}
     */
    @Test
    public void loginLogout() throws LoginException {

        context.getProviderManager().authenticateAll(handlers, crp);

        verify(subProvider).authenticate(authInfo);

        crp.close();

        verify(subProvider).logout(mockContext());
    }

    private ResolveContext<Object> mockContext() {
        return (ResolveContext<Object>) nullable(ResolveContext.class);
    }

    /**
     * Verifies that a synthetic resource is returned for a path which holds no
     * actual resource but is an ancestor of another resource provider
     */
    @Test
    public void getResource_synthetic() {

        Resource resource = crp.getResource(context, "/foo", null, null, false);

        assertTrue("Not a syntethic resource : " + resource, ResourceUtil.isSyntheticResource(resource));
    }

    /**
     * Verifies that a getResource call for a missing resource returns null
     */
    @Test
    public void getResource_missing() {
        assertNull(crp.getResource(context, "/nothing", null, null, false));
    }

    /**
     * Verifies that a resource is returned when it should be
     */
    @Test
    public void getResource_found() {
        assertNotNull(crp.getResource(context, "/something", null, null, false));
        assertNotNull(crp.getResource(context, "/some/path/object", null, null, false));
    }


    /**
     * Verifies that the existing parent of a resource is found
     */
    @Test
    public void getParent_found() {
        Resource parent = crp.getParent(context, ResourceUtil.getParent(somethingResource.getPath()), somethingResource);
        assertNotNull(parent);
        assertEquals("parent.path", "/", parent.getPath());
    }



    /**
     * Verifies that a synthetic parent is returned for a resource without an actual parent
     */
    @Test
    public void getParent_synthetic() {
        Resource parent = crp.getParent(context, ResourceUtil.getParent(subProviderResource.getPath()), subProviderResource);
        assertNotNull(parent);
        assertTrue("parent is a synthetic resource", ResourceUtil.isSyntheticResource(parent));
    }

    /**
     * Test parent from a different provider
     */
    @Test
    public void getParent_differentProviders() {
        final Resource childResource = mock(Resource.class);
        when(childResource.getPath()).thenReturn("/some/path");
        when(subProvider.getResource((ResolveContext<Object>) nullable(ResolveContext.class), Mockito.eq("/some/path"), nullable(ResourceContext.class), (Resource)Mockito.eq(null))).thenReturn(childResource);

        final Resource parentResource = mock(Resource.class);
        when(parentResource.getPath()).thenReturn("/some");
        when(rootProvider.getResource((ResolveContext<Object>) nullable(ResolveContext.class), Mockito.eq("/some"), nullable(ResourceContext.class), (Resource)Mockito.eq(null))).thenReturn(parentResource);

        Resource child = crp.getResource(context, "/some/path", null, null, false);
        assertNotNull(child);
        assertTrue(childResource == child);

        Resource parent = crp.getParent(context, ResourceUtil.getParent(child.getPath()), child);
        assertNotNull(parent);
        assertTrue(parentResource == parent);
    }

    /**
     * Verifies that listing the children at root lists both the synthetic and the 'real' children
     */
    @Test
    public void listChildren_root() {
        Resource root = crp.getResource(context, "/", null, null, false);
        Iterator<Resource> children = crp.listChildren(context, root);

        Map<String, Resource> all = new HashMap<String, Resource>();
        while ( children.hasNext() ) {
            Resource child = children.next();
            all.put(child.getPath(), child);
        }

        assertEquals(3, all.entrySet().size());
        assertNotNull("Resource at /something", all.get("/something"));
        assertNotNull("Resource at /some", all.get("/some"));
        assertNotNull("Resource at /foo", all.get("/foo"));
    }

    /**
     * Verifies listing the children at a level below the root
     */
    @Test
    public void listChildren_lowerLevel() {

        Resource root = crp.getResource(context, "/some", null, null, false);
        Iterator<Resource> children = crp.listChildren(context, root);
        Map<String, Resource> all = new HashMap<String, Resource>();

        while ( children.hasNext() ) {
            Resource child = children.next();
            all.put(child.getPath(), child);
        }

        assertEquals(1, all.entrySet().size());
        assertNotNull("Resource at /some/path", all.get("/some/path"));
        assertSame(somePathResource, all.get("/some/path"));
    }

    /**
     * Checks that its correctly calculated whether a copy/move can be done with the
     * same provider
     *
     * @throws PersistenceException
     */
    @Test
    public void checkProvidersForCopyMove() throws PersistenceException {
        // first check same provider at root
        configureResourceAt(rootProvider, "/a");
        configureResourceAt(rootProvider, "/b");
        assertNotNull(crp.checkSourceAndDest(context, "/a", "/b"));

        // second check different providers
        assertNull(crp.checkSourceAndDest(context, "/some/path/object", "/"));
    }

    /**
     * Verifies copying resources between the same ResourceProvider
     *
     * @throws PersistenceException persistence exception
     */
    @Test
    public void copy_sameProvider() throws PersistenceException {

        when(subProvider.copy(mockContext(), Mockito.eq("/some/path/object"), Mockito.eq("/some/path/new")))
            .thenReturn(true);
        configureResourceAt(subProvider, "/some/path/new/object");
        configureResourceAt(subProvider, "/some/path/new");

        Resource resource = crp.copy(context, "/some/path/object", "/some/path/new");

        assertNotNull(resource);
    }

    /**
     * Verifies copying resources between different ResourceProviders
     *
     * @throws PersistenceException persistence exception
     */
    @Test
    public void copy_differentProvider() throws PersistenceException {

        Resource newRes = newMockResource("/object");
        when(rootProvider.create(mockContext(), Mockito.eq("/object"), nullable(Map.class)))
            .thenReturn(newRes);

        Resource resource = crp.copy(context, "/some/path/object", "/");

        assertNotNull(resource);
    }

    /**
     * Verifies moving resources between the same ResourceProvider
     *
     * @throws PersistenceException persistence exception
     */
    @Test
    public void move_sameProvider() throws PersistenceException {

        when(subProvider.move(mockContext(), Mockito.eq("/some/path/object"), Mockito.eq("/some/path/new")))
                .thenReturn(true);
        configureResourceAt(subProvider, "/some/path/new/object");
        configureResourceAt(subProvider, "/some/path/new");

        Resource resource = crp.move(context, "/some/path/object", "/some/path/new");

        assertNotNull(resource);
    }

    /**
     * Verifies moving resources between different ResourceProviders
     *
     * @throws PersistenceException persistence exception
     */
    @Test
    public void move_differentProvider() throws PersistenceException {

        Resource newRes = newMockResource("/object");
        when(rootProvider.create(mockContext(), Mockito.eq("/object"), nullable(Map.class))).thenReturn(newRes);

        Resource resource = crp.move(context, "/some/path/object", "/");

        assertNotNull(resource);

        verify(subProvider).delete(mockContext(), Mockito.eq(subProviderResource));
    }

    /**
     * Verifies listing the query languages
     */
    @Test
    public void queryLanguages() throws PersistenceException {
        final List<String> result = Arrays.asList(crp.getSupportedLanguages(context));
        assertEquals(3, result.size());
        assertTrue(result.contains(QL_NOOP));
        assertTrue(result.contains(QL_MOCK));
        assertTrue(result.contains(QL_ANOTHER_MOCK));
    }

    /**
     * Verifies running a query
     */
    @Test
    public void queryResources() throws PersistenceException {

        Iterator<Map<String, Object>> queryResources = crp.queryResources(context, QUERY_MOCK_FIND_ALL, QL_MOCK);

        int count = 0;

        while ( queryResources.hasNext() ) {
            assertEquals("ValueMap returned from query", "value", queryResources.next().get("key"));
            count++;
        }

        assertEquals("query result count", 1, count);
    }

    /**
     * Verifies finding resources
     */
    @Test
    public void findResource() throws PersistenceException {

        Iterator<Resource> resources = crp.findResources(context, QUERY_MOCK_FIND_ALL, QL_MOCK);

        int count = 0;

        while ( resources.hasNext() ) {
            assertEquals("resources[0].path", "/some/path/object", resources.next().getPath());
            count++;
        }

        assertEquals("query result count", 1, count);
    }

    @Test
    public void forbiddenAttributeNames() {
        for(String name : crp.getAttributeNames(context)) {
            if(TEST_FORBIDDEN_ATTRIBUTES.contains(name)) {
                fail("Attribute " + name + " should not be accessible");
            }
        }
        assertTrue("Expecting non-forbidden attribute", crp.getAttributeNames(context).contains(TEST_ATTRIBUTE));
    }

    @Test
    public void forbiddenAttributeValues() {
        for(String name : TEST_FORBIDDEN_ATTRIBUTES) {
            assertNull("Expecting " + name + " to be hidden", crp.getAttribute(context, name));
        }
        assertEquals("is " + TEST_ATTRIBUTE, crp.getAttribute(context, TEST_ATTRIBUTE));
    }

    @Test
    public void testListChildrenInternalNoRealChildren() throws LoginException {
        final ResourceResolverControl control = new ResourceResolverControl(false, Collections.emptyMap(), null);
        
        final ResourceResolverContext context = Mockito.mock(ResourceResolverContext.class);
        final ProviderManager providerManager = Mockito.mock(ProviderManager.class);
        Mockito.when(context.getProviderManager()).thenReturn(providerManager);
        
        final ResourceProviderHandler root = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(root.getPath()).thenReturn("/");
        final AuthenticatedResourceProvider rootProvider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(root, control)).thenReturn(rootProvider);
        
        final ResourceProviderHandler sub1 = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(sub1.getPath()).thenReturn("/libs/sub1");
        final AuthenticatedResourceProvider sub1Provider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(sub1, control)).thenReturn(sub1Provider);

        final ResourceProviderHandler sub2 = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(sub2.getPath()).thenReturn("/libs/sub1/xy/sub2");
        final AuthenticatedResourceProvider sub2Provider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(sub2, control)).thenReturn(sub2Provider);

        final List<ResourceProviderHandler> handlers = new ArrayList<>();
        handlers.add(root);
        handlers.add(sub1);
        handlers.add(sub2);

        final PathTree<ResourceProviderHandler> tree = new PathTree<>(handlers);
        
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs"), newMockResource("/libs"), null), newSyntheticResource("/libs/sub1") );
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1"), newMockResource("/libs/sub1"), null), newSyntheticResource("/libs/sub1/xy") );
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1/xy"), newMockResource("/libs/sub1/xy"), null) );
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1/xy/sub2"), newMockResource("/libs/sub1/xy/sub2"), null) );
    }

    @Test
    public void testListChildrenInternalRealChildren() throws LoginException {
        final ResourceResolverControl control = new ResourceResolverControl(false, Collections.emptyMap(), null);
        
        final ResourceResolverContext context = Mockito.mock(ResourceResolverContext.class);
        final ProviderManager providerManager = Mockito.mock(ProviderManager.class);
        Mockito.when(context.getProviderManager()).thenReturn(providerManager);
        
        final ResourceProviderHandler root = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(root.getPath()).thenReturn("/");
        final AuthenticatedResourceProvider rootProvider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(root, control)).thenReturn(rootProvider);
        
        final ResourceProviderHandler sub1 = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(sub1.getPath()).thenReturn("/libs/sub1");
        final AuthenticatedResourceProvider sub1Provider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(sub1, control)).thenReturn(sub1Provider);

        final ResourceProviderHandler sub2 = Mockito.mock(ResourceProviderHandler.class);
        Mockito.when(sub2.getPath()).thenReturn("/libs/sub1/xy/sub2");
        final AuthenticatedResourceProvider sub2Provider = Mockito.mock(AuthenticatedResourceProvider.class);
        Mockito.when(providerManager.getOrCreateProvider(sub2, control)).thenReturn(sub2Provider);

        final List<ResourceProviderHandler> handlers = new ArrayList<>();
        handlers.add(root);
        handlers.add(sub1);
        handlers.add(sub2);

        final PathTree<ResourceProviderHandler> tree = new PathTree<>(handlers);
        
        // two resources - not overlapping
        final Resource c1 = newMockResource("/libs/sub1/a");
        final Resource c2 = newMockResource("/libs/sub1/b");

        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1"), newMockResource("/libs/sub1"), 
            Arrays.asList(c1, c2).iterator()), newSyntheticResource("/libs/sub1/xy"), c1, c2 );

        // additional resource, overlapping with synthetic
        final Resource c3 = newMockResource("/libs/sub1/xy");
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1"), newMockResource("/libs/sub1"), 
            Arrays.asList(c1, c2, c3).iterator()), c1, c2, c3 );

        // same as provider, provider not returning resource -> no resource should be returned, provider is shadowing
        final Resource c4 = newMockResource("/libs/sub1/xy/sub2");
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1/xy"), newMockResource("/libs/sub1/xy"), 
            Arrays.asList(c4).iterator()) );

        // same as provider, provider returning resource, provider resource should be returned
        final Resource parent = newMockResource("/libs/sub1/xy");
        final Resource c5 = newMockResource("/libs/sub1/xy/sub2");
        Mockito.when(sub2Provider.getResource("/libs/sub1/xy/sub2", parent, null)).thenReturn(c5);
        assertChildren( control.listChildrenInternal(context, tree.getNode("/libs/sub1/xy"),parent , 
            Arrays.asList(c4).iterator()), c5 );
    }

    private Map<String, Resource> mapChildren(final Iterator<Resource> children) {
        final Map<String, Resource> all = new HashMap<String, Resource>();
        while ( children.hasNext() ) {
            final Resource child = children.next();
            if ( all.containsKey(child.getPath()) ) {
                fail(child.getPath());
            }
            all.put(child.getPath(), child);
        }
        return all;
    }
    
    private void assertChildren(final Iterator<Resource> children, final Resource... resources) {
        final Map<String, Resource> all = mapChildren(children);
        if ( resources == null ) {
            assertTrue(all.isEmpty());
        } else {
            assertEquals("" + all.keySet(), resources.length, all.size());
            for(final Resource rsrc : resources) {
                assertTrue(all.keySet() + " : " + rsrc.getPath(), all.containsKey(rsrc.getPath()));
                if ( ResourceUtil.isSyntheticResource(rsrc)) {
                    assertTrue(ResourceUtil.isSyntheticResource(all.get(rsrc.getPath())));
                } else {
                    assertFalse(ResourceUtil.isSyntheticResource(all.get(rsrc.getPath())));
                    assertSame(rsrc, all.get(rsrc.getPath()));
                }
            }    
        }
    }

    /**
     * Simple test-only QueryLanguageProvider
     *
     */
    private static class SimpleQueryLanguageProvider implements QueryLanguageProvider<Object> {

        private final String[] queryLanguages;

        public SimpleQueryLanguageProvider(String... queryLanguages) {
            this.queryLanguages = queryLanguages;
        }

        @Override
        public String[] getSupportedLanguages(ResolveContext<Object> ctx) {
            return queryLanguages;
        }

        @Override
        public Iterator<ValueMap> queryResources(ResolveContext<Object> ctx, String query, String language) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<Resource> findResources(ResolveContext<Object> ctx, String query, String language) {
            throw new UnsupportedOperationException();
        }
    }

    @Test public void testGetBestMatchingModifiableResourceProviderPassthrough() throws Exception {
        BundleContext bc = MockOsgi.newBundleContext();

        Fixture fixture = new Fixture(bc);

        // root provider
        final ResourceProvider<?> rootProvider = Mockito.mock(ResourceProvider.class);
        ResourceProviderInfo info = fixture.registerResourceProvider(rootProvider, "/", AuthType.required);
        ResourceProviderHandler handler = new ResourceProviderHandler(info, bc.getService(info.getServiceReference()));
        // sub provider
        ResourceProvider<?> subProvider = Mockito.mock(ResourceProvider.class);
        ResourceProviderInfo subInfo = fixture.registerResourceProvider(subProvider, "/libs", AuthType.required, 0, false, ResourceProviderInfo.Mode.PASSTHROUGH);
        ResourceProviderHandler subHandler = new ResourceProviderHandler(subInfo, bc.getService(subInfo.getServiceReference()));

        handler.activate();
        subHandler.activate();

        ResourceResolver rr = mock(ResourceResolver.class);
        ResourceAccessSecurityTracker securityTracker = Mockito.mock(ResourceAccessSecurityTracker.class);
        authInfo = getAuthInfo();

        handlers = Arrays.asList(handler, subHandler);
        final ResourceProviderStorage storage = new ResourceProviderStorage(handlers);

        final ResourceResolverControl control = new ResourceResolverControl(false, getAuthInfo(), new ResourceProviderStorageProvider() {

            @Override
            public ResourceProviderStorage getResourceProviderStorage() {
                return storage;
            }
        });
        final ResourceResolverContext rrContext = new ResourceResolverContext(rr, securityTracker);

        final AuthenticatedResourceProvider p = control.getBestMatchingModifiableProvider(rrContext, "/libs/foo");
        p.create(rr, "/foo", null);
        Mockito.verify(rootProvider).create(nullable(ResolveContext.class), Mockito.eq("/foo"), Mockito.isNull(Map.class));
    }
}
