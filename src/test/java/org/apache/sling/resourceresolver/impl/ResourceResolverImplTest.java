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
package org.apache.sling.resourceresolver.impl;

import javax.jcr.Session;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.sling.api.SlingException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.security.ResourceAccessSecurity;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;

import static java.util.Arrays.asList;
import static org.apache.sling.resourceresolver.impl.MockedResourceResolverImplTest.createRPHandler;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

public class ResourceResolverImplTest {

    private CommonResourceResolverFactoryImpl commonFactory;

    private ResourceResolver resResolver;

    private ResourceResolverFactoryImpl resFac;

    private ResourceProviderTracker resourceProviderTracker;

    private ResourceAccessSecurityTracker resourceAccessSecurityTracker;

    @Before
    public void setup() throws LoginException {
        ResourceProvider<?> rp = new ResourceProvider<Object>() {

            @Override
            public Resource getResource(
                    ResolveContext<Object> ctx, String path, ResourceContext rCtx, Resource parent) {
                return null;
            }

            @Override
            public Iterator<Resource> listChildren(ResolveContext<Object> ctx, Resource parent) {
                return null;
            }
        };

        ResourceProvider<?> rp2 = new ResourceProvider<Object>() {

            @Override
            public Resource getResource(
                    ResolveContext<Object> ctx, String path, ResourceContext rCtx, Resource parent) {
                return null;
            }

            @Override
            public Iterator<Resource> listChildren(ResolveContext<Object> ctx, Resource parent) {
                return null;
            }

            @Override
            public boolean orderBefore(
                    final @NotNull ResolveContext<Object> ctx,
                    final @NotNull Resource parent,
                    final @NotNull String name,
                    final @Nullable String followingSiblingName)
                    throws PersistenceException {
                return true;
            }
        };
        List<ResourceProviderHandler> handlers =
                asList(createRPHandler(rp, "rp", 0, "/"), createRPHandler(rp2, "rp2", 100, "/rp2"));
        resourceProviderTracker = mock(ResourceProviderTracker.class);
        ResourceProviderStorage storage = new ResourceProviderStorage(handlers);
        when(resourceProviderTracker.getResourceProviderStorage()).thenReturn(storage);
        resourceAccessSecurityTracker = new ResourceAccessSecurityTracker();
        ResourceResolverFactoryActivator activator = new ResourceResolverFactoryActivator();
        activator.resourceProviderTracker = resourceProviderTracker;
        activator.resourceAccessSecurityTracker = resourceAccessSecurityTracker;
        commonFactory = new CommonResourceResolverFactoryImpl(activator);
        final Bundle usingBundle = mock(Bundle.class);
        resFac = new ResourceResolverFactoryImpl(commonFactory, usingBundle, null);
        resResolver = resFac.getAdministrativeResourceResolver(null);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testClose() throws Exception {
        final ResourceResolver rr = new ResourceResolverImpl(commonFactory, false, null, resourceProviderTracker);
        assertTrue(rr.isLive());
        rr.close();
        assertFalse(rr.isLive());
        // close is always allowed to be called
        rr.close();
        assertFalse(rr.isLive());
        // now check all public method - they should all throw!
        try {
            rr.adaptTo(Session.class);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.clone(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.findResources("a", "b");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getAttribute("a");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getAttributeNames();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getResource(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getResource(null, "/a");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getSearchPath();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getUserID();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.listChildren(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.map("/somepath");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.map((HttpServletRequest) null, "/somepath");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.queryResources("a", "b");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve((javax.servlet.http.HttpServletRequest) null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve("/path");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve((HttpServletRequest) null, "/path");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testCloseWithStackTraceLogging() throws Exception {
        ResourceResolverFactoryConfig config = mock(ResourceResolverFactoryConfig.class);
        when(config.resource_resolver_log_closing()).thenReturn(true);
        ResourceResolverFactoryActivator rrfa = spy(new ResourceResolverFactoryActivator());
        setField(rrfa, "config", config);
        CommonResourceResolverFactoryImpl crrfi = new CommonResourceResolverFactoryImpl(rrfa);
        final ResourceResolver rr = new ResourceResolverImpl(crrfi, false, null, resourceProviderTracker);
        assertTrue(rr.isLive());
        rr.close();
        assertFalse(rr.isLive());
        // close is always allowed to be called
        rr.close();
        assertFalse(rr.isLive());
        // now check all public method - they should all throw!
        try {
            rr.adaptTo(Session.class);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.clone(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.findResources("a", "b");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getAttribute("a");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getAttributeNames();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getResource(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getResource(null, "/a");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getSearchPath();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.getUserID();
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.listChildren(null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.map("/somepath");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.map((HttpServletRequest) null, "/somepath");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.queryResources("a", "b");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve((javax.servlet.http.HttpServletRequest) null);
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve("/path");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
        try {
            rr.resolve((HttpServletRequest) null, "/path");
            fail();
        } catch (final IllegalStateException ise) {
            // expected
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testBasicAPIAssumptions() throws Exception {

        // null resource is accessing /, which exists of course
        final Resource res00 = resResolver.resolve((String) null);
        assertNotNull(res00);
        assertTrue("Resource must be NonExistingResource", res00 instanceof NonExistingResource);
        assertEquals("Null path is expected to return root", "/", res00.getPath());

        // relative paths are treated as if absolute
        final String path01 = "relPath/relPath";
        final Resource res01 = resResolver.resolve(path01);
        assertNotNull(res01);
        assertEquals("Expecting absolute path for relative path", "/" + path01, res01.getPath());
        assertTrue("Resource must be NonExistingResource", res01 instanceof NonExistingResource);

        final String no_resource_path = "/no_resource/at/this/location";
        final Resource res02 = resResolver.resolve(no_resource_path);
        assertNotNull(res02);
        assertEquals("Expecting absolute path for relative path", no_resource_path, res02.getPath());
        assertTrue("Resource must be NonExistingResource", res01 instanceof NonExistingResource);

        try {
            resResolver.resolve((javax.servlet.http.HttpServletRequest) null);
            fail("Expected NullPointerException trying to resolve null request");
        } catch (NullPointerException npe) {
            // expected
        }

        final Resource res0 = resResolver.resolve((HttpServletRequest) null, no_resource_path);
        assertNotNull("Expecting resource if resolution fails", res0);
        assertTrue("Resource must be NonExistingResource", res0 instanceof NonExistingResource);
        assertEquals("Path must be the original path", no_resource_path, res0.getPath());

        final javax.servlet.http.HttpServletRequest req1 = mock(javax.servlet.http.HttpServletRequest.class);
        when(req1.getProtocol()).thenReturn("http");
        when(req1.getServerName()).thenReturn("localhost");
        when(req1.getPathInfo()).thenReturn(no_resource_path);

        final Resource res1 = resResolver.resolve(req1);
        assertNotNull("Expecting resource if resolution fails", res1);
        assertTrue("Resource must be NonExistingResource", res1 instanceof NonExistingResource);
        assertEquals("Path must be the original path", no_resource_path, res1.getPath());

        final javax.servlet.http.HttpServletRequest req2 = mock(javax.servlet.http.HttpServletRequest.class);
        when(req2.getProtocol()).thenReturn("http");
        when(req2.getServerName()).thenReturn("localhost");
        when(req2.getPathInfo()).thenReturn(null);
        final Resource res2 = resResolver.resolve(req2);
        assertNotNull("Expecting resource if resolution fails", res2);
        assertTrue("Resource must be NonExistingResource", res2 instanceof NonExistingResource);
        assertEquals("Path must be the the root path", "/", res2.getPath());

        final Resource res3 = resResolver.getResource(null);
        assertNull("Expected null resource for null path", res3);

        final Resource res4 = resResolver.getResource(null, null);
        assertNull("Expected null resource for null path", res4);

        final Resource res5 = resResolver.getResource(res01, null);
        assertNull("Expected null resource for null path", res5);
    }

    @Test
    public void test_clone_based_on_anonymous() throws Exception {
        final ResourceResolver anon0 = resFac.getResourceResolver((Map<String, Object>) null);
        // no session
        final Session anon0Session = anon0.adaptTo(Session.class);
        assertNull("Session should not be available", anon0Session);
        // no user information, so user id is null
        assertEquals(null, anon0.getUserID());

        // same user and workspace
        final ResourceResolver anon1 = anon0.clone(null);
        final Session anon1Session = anon1.adaptTo(Session.class);
        assertEquals(anon0.getUserID(), anon1.getUserID());
        assertNull("Session should not be available", anon1Session);
        anon1.close();

        // same workspace but admin user
        final Map<String, Object> admin0Cred = new HashMap<>();
        admin0Cred.put(ResourceResolverFactory.USER, "admin");
        admin0Cred.put(ResourceResolverFactory.PASSWORD, "admin".toCharArray());
        final ResourceResolver admin0 = anon0.clone(admin0Cred);
        assertEquals("admin", admin0.getUserID());
        admin0.close();

        anon0.close();
    }

    @Test
    public void test_clone_based_on_admin() throws Exception {
        final ResourceResolver admin0 = resFac.getAdministrativeResourceResolver((Map<String, Object>) null);
        // no user information, so user id is null
        assertEquals(null, admin0.getUserID());

        // same user and workspace
        final ResourceResolver admin1 = admin0.clone(null);
        assertEquals(admin0.getUserID(), admin1.getUserID());
        admin1.close();

        // same workspace but anonymous user
        final Map<String, Object> anon0Cred = new HashMap<>();
        anon0Cred.put(ResourceResolverFactory.USER, "anonymous");
        final ResourceResolver anon0 = admin0.clone(anon0Cred);
        assertEquals("anonymous", anon0.getUserID());
        anon0.close();

        admin0.close();
    }

    @Test
    public void test_attributes_from_authInfo() throws Exception {
        final Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.USER, "admin");
        authInfo.put(ResourceResolverFactory.PASSWORD, "admin".toCharArray());
        authInfo.put("testAttributeString", "AStringValue");
        authInfo.put("testAttributeNumber", 999);
        final ResourceResolver rr = resFac.getResourceResolver(authInfo);

        assertEquals("AStringValue", rr.getAttribute("testAttributeString"));
        assertEquals(999, rr.getAttribute("testAttributeNumber"));
        assertEquals("admin", rr.getAttribute(ResourceResolverFactory.USER));
        assertNull(rr.getAttribute(ResourceResolverFactory.PASSWORD));

        final HashSet<String> validNames = new HashSet<>();
        validNames.add(ResourceResolverFactory.USER);
        validNames.add("testAttributeString");
        validNames.add("testAttributeNumber");
        final Iterator<String> names = rr.getAttributeNames();
        assertTrue(validNames.remove(names.next()));
        assertTrue(validNames.remove(names.next()));
        assertTrue(validNames.remove(names.next()));
        assertFalse("Expect no more names", names.hasNext());
        assertTrue("Expect validNames set to be empty now", validNames.isEmpty());

        rr.close();
    }

    @Test
    public void testBasicCrud() throws Exception {
        final Resource r = mock(Resource.class);
        when(r.getPath()).thenReturn("/some");
        try {
            this.resResolver.create(null, "a", null);
            fail("Null parent resource should throw NPE");
        } catch (final NullPointerException npe) {
            // correct
        }
        try {
            this.resResolver.create(r, null, null);
            fail("Null name should throw NPE");
        } catch (final NullPointerException npe) {
            // correct
        }
        try {
            this.resResolver.create(r, "a/b", null);
            fail("Slash in name should throw illegal argument exception");
        } catch (final IllegalArgumentException pe) {
            // correct
        }
        try {
            this.resResolver.create(r, "....", null);
            fail("Only dots in name should throw illegal argument exception");
        } catch (final IllegalArgumentException pe) {
            // correct
        }
        try {
            this.resResolver.create(r, "a.b", null);
            fail("This should be unsupported.");
        } catch (final UnsupportedOperationException uoe) {
            // correct
        }
    }

    @Test
    public void testOrderBefore() throws PersistenceException {
        // final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();
        Resource root = resResolver.resolve("/");
        // different RPs
        try {
            resResolver.orderBefore(root, "rp1", "rp2");
            fail("Different resource providers should throw");
        } catch (final UnsupportedOperationException uoe) {
            // correct
        }
        try {
            // same RP but not implementing orderBefore
            assertTrue(resResolver.orderBefore(root, "rp1", "rp3"));
        } catch (final UnsupportedOperationException uoe) {
            // correct
        }
        // same RP supporting orderBefore
        root = resResolver.resolve("/rp2");
        assertTrue(resResolver.orderBefore(root, "test", "test2"));
        // same RP supporting but not allowing orderBefore
        ResourceAccessSecurity ras = mock(ResourceAccessSecurity.class);
        resourceAccessSecurityTracker.applicationResourceAccessSecurity = ras;
        try {
            resResolver.orderBefore(root, "test", "test2");
        } catch (final PersistenceException pe) {
            // correct
        }
        // same RP supporting and allowing orderBefore
        when(ras.canOrderChildren(Mockito.any())).thenReturn(true);
        assertTrue(resResolver.orderBefore(root, "test", "test2"));
    }

    @Test
    public void test_getResourceSuperType() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();

        // the resources to test
        final Resource r = resolver.add(new SyntheticResource(resolver, "/a", "a:b"));
        final Resource r2 = resolver.add(new SyntheticResource(resolver, "/a2", "a:c"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/a/b", "x:y", "t:c"));

        assertEquals("t:c", resolver.getParentResourceType(r.getResourceType()));
        assertNull(resolver.getParentResourceType(r2.getResourceType()));
    }

    @Test
    public void testIsResourceType() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();

        final Resource r = resolver.add(new SyntheticResourceWithSupertype(resolver, "/a", "a:b", "d:e"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/d/e", "x:y", "t:c"));

        assertTrue(resolver.isResourceType(r, "a:b"));
        assertTrue(resolver.isResourceType(r, "d:e"));
        assertFalse(resolver.isResourceType(r, "x:y"));
        assertTrue(resolver.isResourceType(r, "t:c"));
        assertFalse(resolver.isResourceType(r, "h:p"));
    }

    @Test
    public void testIsResourceTypeCached() throws Exception {
        final PathBasedResourceResolverImpl resolver = Mockito.spy(getPathBasedResourceResolver());
        final Resource r1 = resolver.add(new SyntheticResource(resolver, "/a", "a:b"));
        final Resource r2 = resolver.add(new SyntheticResourceWithSupertype(resolver, "/b", "a:b", "c:d"));

        // 1st lookup needs to get through, 2nd will be taken from cache
        assertTrue(resolver.isResourceType(r1, "a:b"));
        Mockito.verify(resolver, Mockito.times(1)).isResourceTypeInternal(eq(r1), eq("a:b"));
        assertTrue(resolver.isResourceType(r1, "a:b"));
        Mockito.verify(resolver, Mockito.times(1)).isResourceTypeInternal(eq(r1), eq("a:b"));

        resolver.refresh();
        assertTrue(resolver.isResourceType(r1, "a:b"));
        Mockito.verify(resolver, Mockito.times(2)).isResourceTypeInternal(eq(r1), eq("a:b"));

        // make sure that resources with the same resourceType but different resourceSuperType are
        // treated differently
        assertTrue(resolver.isResourceType(r2, "c:d"));
        assertFalse(resolver.isResourceType(r1, "c:d"));
    }

    @Test
    public void testIsResourceTypeWithPaths() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();

        /**
         * prepare resource type hierarchy
         * /types/1
         *  +- /types/2
         *    +- /types/3
         */
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/1", "/types/component", "/types/2"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/2", "/types/component", "/types/3"));
        resolver.add(new SyntheticResource(resolver, "/types/3", "/types/component"));

        Resource resourceT1 = resolver.add(new SyntheticResource(resolver, "/resourceT1", "/types/1"));
        Resource resourceT2 = resolver.add(new SyntheticResource(resolver, "/resourceT2", "/types/2"));
        Resource resourceT3 = resolver.add(new SyntheticResource(resolver, "/resourceT3", "/types/3"));

        assertTrue(resolver.isResourceType(resourceT1, "/types/1"));
        assertTrue(resolver.isResourceType(resourceT1, "/types/2"));
        assertTrue(resolver.isResourceType(resourceT1, "/types/3"));
        assertFalse(resolver.isResourceType(resourceT1, "/types/component"));
        assertFalse(resolver.isResourceType(resourceT1, "/types/unknown"));

        assertFalse(resolver.isResourceType(resourceT2, "/types/1"));
        assertTrue(resolver.isResourceType(resourceT2, "/types/2"));
        assertTrue(resolver.isResourceType(resourceT2, "/types/3"));
        assertFalse(resolver.isResourceType(resourceT2, "/types/component"));
        assertFalse(resolver.isResourceType(resourceT2, "/types/unknown"));

        assertFalse(resolver.isResourceType(resourceT3, "/types/1"));
        assertFalse(resolver.isResourceType(resourceT3, "/types/2"));
        assertTrue(resolver.isResourceType(resourceT3, "/types/3"));
        assertFalse(resolver.isResourceType(resourceT3, "/types/component"));
        assertFalse(resolver.isResourceType(resourceT3, "/types/unknown"));
    }

    /**
     * @see <a href="https://issues.apache.org/jira/browse/SLING-6327">SLING-6327</a>
     */
    @Test
    public void testIsResourceTypeWithMixedAbsoluteAndRelativePaths() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver(new String[] {"/apps/", "/libs/"});

        Resource resourceT1 = resolver.add(new SyntheticResource(resolver, "/resourceT1", "types/1"));
        Resource resourceT2 = resolver.add(new SyntheticResource(resolver, "/resourceT2", "/apps/types/2"));
        Resource resourceT3 = resolver.add(new SyntheticResource(resolver, "/resourceT3", "/libs/types/3"));
        Resource resourceT4 = resolver.add(new SyntheticResource(resolver, "/resourceT3", "/someprefix/types/4"));

        assertTrue(resolver.isResourceType(resourceT1, "/libs/types/1"));
        assertTrue(resolver.isResourceType(resourceT1, "/apps/types/1"));
        assertTrue(resolver.isResourceType(resourceT1, "types/1"));

        assertTrue(resolver.isResourceType(resourceT2, "/apps/types/2"));
        assertTrue(resolver.isResourceType(resourceT2, "types/2"));
        assertTrue(resolver.isResourceType(resourceT2, "/libs/types/2"));

        assertTrue(resolver.isResourceType(resourceT3, "/apps/types/3"));
        assertTrue(resolver.isResourceType(resourceT3, "types/3"));
        assertTrue(resolver.isResourceType(resourceT3, "/libs/types/3"));

        assertFalse(resolver.isResourceType(resourceT4, "/apps/types/4"));
        assertFalse(resolver.isResourceType(resourceT4, "types/4"));
        assertFalse(resolver.isResourceType(resourceT4, "/libs/types/4"));
        assertTrue(resolver.isResourceType(resourceT4, "/someprefix/types/4"));
    }

    @Test(expected = SlingException.class)
    public void testIsResourceCyclicHierarchyDirect() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();

        /**
         * prepare resource type hierarchy
         * /types/1  <---+
         *  +- /types/2 -+
         */
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/1", "/types/component", "/types/2"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/2", "/types/component", "/types/1"));

        Resource resource = resolver.add(new SyntheticResource(resolver, "/resourceT1", "/types/1"));

        assertTrue(resolver.isResourceType(resource, "/types/1"));
        assertTrue(resolver.isResourceType(resource, "/types/2"));

        // this should throw a SlingException when detecting the cyclic hierarchy
        resolver.isResourceType(resource, "/types/unknown");
    }

    @Test(expected = SlingException.class)
    public void testIsResourceCyclicHierarchyIndirect() {
        final PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();

        /**
         * prepare resource type hierarchy
         * /types/1   <----+
         *  +- /types/2    |
         *    +- /types/3 -+
         */
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/1", "/types/component", "/types/2"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/2", "/types/component", "/types/3"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/3", "/types/component", "/types/1"));

        Resource resource = resolver.add(new SyntheticResource(resolver, "/resourceT1", "/types/1"));

        assertTrue(resolver.isResourceType(resource, "/types/1"));
        assertTrue(resolver.isResourceType(resource, "/types/2"));
        assertTrue(resolver.isResourceType(resource, "/types/3"));

        // this should throw a SlingException when detecting the cyclic hierarchy
        resolver.isResourceType(resource, "/types/unknown");
    }

    @Test
    public void testGetPropertyMap() throws IOException {
        // not having a map must not change the behavior
        PathBasedResourceResolverImpl resolver = getPathBasedResourceResolver();
        resolver.close();

        // use the propertyMap
        resolver = getPathBasedResourceResolver();
        Object value1 = new String("value1");
        Closeable value2 = Mockito.spy(new Closeable() {
            @Override
            public void close() {
                // do nothing
            }
        });
        Closeable valueWithException = Mockito.spy(new Closeable() {
            @Override
            public void close() {
                throw new RuntimeException("RuntimeExceptions in close must be handled");
            }
        });
        assertNotNull(resolver.getPropertyMap());
        resolver.getPropertyMap().put("key1", value1);
        resolver.getPropertyMap().put("key2", value2);
        resolver.getPropertyMap().put("key3", valueWithException);

        resolver.close();
        assertNotNull(resolver.getPropertyMap());
        assertTrue(resolver.getPropertyMap().isEmpty());
        Mockito.verify(value2, Mockito.times(1)).close();
        Mockito.verify(valueWithException, Mockito.times(1)).close();
    }

    @Test
    public void testGetParentResourceType() {
        final PathBasedResourceResolverImpl resolver = Mockito.spy(getPathBasedResourceResolver());

        resolver.add(new SyntheticResourceWithSupertype(resolver, "/types/1", "/types/component", "/types/2"));
        resolver.add(new SyntheticResourceWithSupertype(resolver, "/content/type1", "/types/1", null));

        assertEquals("/types/2", resolver.getParentResourceType(resolver.getResource("/types/1")));
        assertEquals("/types/2", resolver.getParentResourceType(resolver.getResource("/content/type1")));

        // Ensure that the next call will be served from the cache
        resolver.getParentResourceType(resolver.getResource("/types/1"));
        Mockito.verify(resolver, times(2)).getParentResourceTypeInternal(any(Resource.class));
    }

    private PathBasedResourceResolverImpl getPathBasedResourceResolver() {
        return getPathBasedResourceResolver(new String[] {""});
    }

    private PathBasedResourceResolverImpl getPathBasedResourceResolver(String[] searchPaths) {
        try {
            final List<ResourceResolver> resolvers = new ArrayList<>();
            final PathBasedResourceResolverImpl resolver =
                    new PathBasedResourceResolverImpl(resolvers, resourceProviderTracker, searchPaths);
            resolvers.add(resolver);
            return resolver;
        } catch (LoginException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static class PathBasedResourceResolverImpl extends ResourceResolverImpl {

        private final Map<String, Resource> resources = new HashMap<>();

        public PathBasedResourceResolverImpl(
                final List<ResourceResolver> resolvers,
                final ResourceProviderTracker resourceProviderTracker,
                final String[] searchPaths)
                throws LoginException {
            this(
                    new CommonResourceResolverFactoryImpl(new ResourceResolverFactoryActivator()) {
                        @Override
                        public ResourceResolver getAdministrativeResourceResolver(
                                Map<String, Object> authenticationInfo) throws LoginException {
                            return resolvers.get(0);
                        }

                        @Override
                        public ResourceResolver getServiceResourceResolver(Map<String, Object> authenticationInfo)
                                throws LoginException {
                            return resolvers.get(0);
                        }

                        @Override
                        public List<String> getSearchPath() {
                            return Arrays.asList(searchPaths);
                        }
                    },
                    resourceProviderTracker);
        }

        public PathBasedResourceResolverImpl(
                CommonResourceResolverFactoryImpl factory, ResourceProviderTracker resourceProviderTracker)
                throws LoginException {
            super(factory, false, null, resourceProviderTracker);
        }

        public Resource add(final Resource r) {
            this.resources.put(r.getPath(), r);
            return r;
        }

        @Override
        public Resource getResource(final String path) {
            final String p = (path.startsWith("/") ? path : "/" + path);
            return this.resources.get(p);
        }
    }

    private static class SyntheticResourceWithSupertype extends SyntheticResource {

        private final String resourceSuperType;

        public SyntheticResourceWithSupertype(
                ResourceResolver resourceResolver, String path, String resourceType, String resourceSuperType) {
            super(resourceResolver, path, resourceType);
            this.resourceSuperType = resourceSuperType;
        }

        @Override
        public String getResourceSuperType() {
            return this.resourceSuperType;
        }
    }

    public static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }
}
