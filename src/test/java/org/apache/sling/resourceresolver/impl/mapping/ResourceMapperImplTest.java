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

import javax.servlet.http.HttpServletRequest;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.mapping.ResourceMapper;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryActivator;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mockito;
import org.osgi.util.tracker.ServiceTracker;

import static org.apache.sling.resourceresolver.impl.ResourceResolverImpl.PROP_ALIAS;
import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_NAME;
import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_ROOT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates that the {@link ResourceMapperImpl} correctly queries all sources of mappings
 *
 * <p>This test ensures that in case more than one mappings is possible, for instance:
 *
 * <ol>
 *   <li>path for an existing resource</li>
 *   <li>alias</li>
 *   <li>/etc/map entries</li>
 * </ol>
 *
 * all are correctly considered and included in the relevant method calls.
 * </p>
 *
 * <p>This test should not exhaustively test all mapping scenarios, other tests in this
 * module and the Sling ITs cover that.</p>
 *
 */
@RunWith(Parameterized.class)
public class ResourceMapperImplTest {

    @Parameters(name = "optimized alias resolution = {0} / paged query support = {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{false, false}, {false, true}, {true, false}, {true, true}});
    }

    @Rule
    public final OsgiContext ctx = new OsgiContext();

    private final boolean optimiseAliasResolution;
    private final boolean pagedQuerySupport;
    private HttpServletRequest req;
    private ResourceResolver resolver;

    public ResourceMapperImplTest(boolean optimiseAliasResolution, boolean pagedQuerySupport) {
        this.optimiseAliasResolution = optimiseAliasResolution;
        this.pagedQuerySupport = pagedQuerySupport;
    }

    @Before
    public void prepare() throws LoginException, InterruptedException {

        ctx.registerInjectActivateService(new ServiceUserMapperImpl());
        ctx.registerInjectActivateService(new ResourceAccessSecurityTracker());
        ctx.registerInjectActivateService(new StringInterpolationProviderImpl());

        InMemoryResourceProvider resourceProvider = new InMemoryResourceProvider(pagedQuerySupport);
        resourceProvider.putResource("/"); // root
        resourceProvider.putResource("/here"); // regular page
        resourceProvider.putResource("/there", PROP_ALIAS, "alias-value"); // with alias
        resourceProvider.putResource(
                "/there-multiple", PROP_ALIAS, "alias-value-3", "alias-value-4"); // with multivalued alias
        resourceProvider.putResource("/somewhere", PROP_ALIAS, "alias-value-2"); // with alias and also /etc/map
        resourceProvider.putResource("/there/that"); // parent has alias
        resourceProvider.putResource("/content1");
        resourceProvider.putResource("/content1/jcr:content", PROP_ALIAS, "jcr:content-alias"); // jcr:content resource
        resourceProvider.putResource("/content");
        resourceProvider.putResource("/content/very");
        resourceProvider.putResource("/content/very/deep");
        resourceProvider.putResource("/content/very/deep/path");
        resourceProvider.putResource("/content/very/deep/path/with");
        resourceProvider.putResource("/content/very/deep/path/with/resources");
        resourceProvider.putResource("/content/virtual");
        resourceProvider.putResource("/content/virtual/foo"); // matches virtual.host.com.80 mapping entry
        resourceProvider.putResource("/parent", PROP_ALIAS, "alias-parent"); // parent has alias
        resourceProvider.putResource("/parent/child", PROP_ALIAS, "alias-child"); // child has alias
        resourceProvider.putResource(
                "/parent/child-multiple", PROP_ALIAS, "alias-child-1", "alias-child-2"); // child has multiple alias
        resourceProvider.putResource("/vain", "sling:vanityPath", "/vanity-a", "/vanity-b"); // vanity path

        // Tests to complete coverage of vanity path formats; test expectations based on behavior as of Jan 2023, not
        // necessarily common sense
        resourceProvider.putResource(
                "/vain-ext", "sling:vanityPath", "/vanity-a/foo.txt", "/vanity.bar/foo"); // vanity path with extensions
        resourceProvider.putResource("/vain-empty", "sling:vanityPath", ""); // vanity path empty
        resourceProvider.putResource("/vain-relative", "sling:vanityPath", "foobar"); // vanity path not absolute
        resourceProvider.putResource(
                "/vain-url", "sling:vanityPath", "https://example.com/", "https://example.com/foo");
        resourceProvider.putResource("/vain-url-invalid", "sling:vanityPath", "://pathOfMalformed");
        resourceProvider.putResource("/vain-url-nopath", "sling:vanityPath", "https://example.com");

        // build /etc/map structure
        resourceProvider.putResource("/etc");
        resourceProvider.putResource("/etc/map");
        resourceProvider.putResource("/etc/map/http");
        resourceProvider.putResource(
                "/etc/map/http/localhost_any",
                "sling:internalRedirect",
                "/somewhere",
                "sling:match",
                "localhost.8080/everywhere");
        resourceProvider.putResource("/etc/map/http/virtual.host.com.80", "sling:internalRedirect", "/content/virtual");

        // we fake the fact that we are the JCR resource provider since it's the required one
        ctx.registerService(ResourceProvider.class, resourceProvider, PROPERTY_ROOT, "/", PROPERTY_NAME, "JCR");
        // disable optimised alias resolution as it relies on JCR queries
        ctx.registerInjectActivateService(
                new ResourceResolverFactoryActivator(),
                "resource.resolver.optimize.alias.resolution",
                optimiseAliasResolution);

        final ResourceResolverFactory factory;
        final ServiceTracker<ResourceResolverFactory, ResourceResolverFactory> tracker =
                new ServiceTracker<>(ctx.bundleContext(), ResourceResolverFactory.class, null);
        try {
            tracker.open();
            factory = tracker.waitForService(TimeUnit.SECONDS.toMillis(5));
        } finally {
            tracker.close();
        }

        assertNotNull(factory);

        resolver = factory.getResourceResolver(null);

        req = mock(HttpServletRequest.class);
        when(req.getScheme()).thenReturn("http");
        when(req.getServerName()).thenReturn("localhost");
        when(req.getServerPort()).thenReturn(8080);
        when(req.getContextPath()).thenReturn("/app");
    }

    @After
    public void cleanup() {
        if (resolver != null) resolver.close();
    }

    /**
     * Validates that mappings for an empty return the root path
     *
     * @throws LoginException
     */
    @Test
    public void mapNonExistingEmptyPath() throws LoginException {

        ExpectedMappings.nonExistingResource("")
                .singleMapping("/")
                .singleMappingWithRequest("/app/")
                .allMappings("/")
                .allMappingsWithRequest("/app/")
                .verify(resolver, req);
    }

    /**
     * Validates that mappings for a non-existing resource only contain that resource's path
     *
     * @throws LoginException
     */
    @Test
    public void mapNonExistingPath() throws LoginException {

        ExpectedMappings.nonExistingResource("/not-here")
                .singleMapping("/not-here")
                .singleMappingWithRequest("/app/not-here")
                .allMappings("/not-here")
                .allMappingsWithRequest("/app/not-here")
                .verify(resolver, req);
    }

    /**
     * Validates that mappings for an existing resource only contain that resource's path
     *
     * @throws LoginException
     */
    @Test
    public void mapExistingPath() throws LoginException {

        ExpectedMappings.existingResource("/here")
                .singleMapping("/here")
                .singleMappingWithRequest("/app/here")
                .allMappings("/here")
                .allMappingsWithRequest("/app/here")
                .verify(resolver, req);
    }

    /**
     * Validates that mappings for a existing resource with an alias contain the alias and the resource's path
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithAlias() {

        ExpectedMappings.existingResource("/there")
                .singleMapping("/alias-value")
                .singleMappingWithRequest("/app/alias-value")
                .allMappings("/alias-value", "/there")
                .allMappingsWithRequest("/app/alias-value", "/app/there")
                .verify(resolver, req);
    }

    /**
     * Validates that a jcr:content resource cannot be aliased, but instead its parent resource is
     *
     * @throws LoginException
     */
    @Test
    public void mapJcrContentResourceWithAlias() {

        ExpectedMappings.existingResource("/content1/jcr:content")
                .singleMapping("/jcr:content-alias/jcr:content")
                .singleMappingWithRequest("/app/jcr:content-alias/jcr:content")
                .allMappings("/jcr:content-alias/jcr:content", "/content1/jcr:content")
                .allMappingsWithRequest("/app/content1/jcr:content", "/app/jcr:content-alias/jcr:content")
                .verify(resolver, req);
    }

    /**
     * Validates that mappings for a existing resource with multiple alias contain the alias and the resource's path
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithMultivaluedAlias() {

        ExpectedMappings.existingResource("/there-multiple")
                .singleMapping("/alias-value-3")
                .singleMappingWithRequest("/app/alias-value-3")
                .allMappings("/alias-value-3", "/alias-value-4", "/there-multiple")
                .allMappingsWithRequest("/app/alias-value-3", "/app/alias-value-4", "/app/there-multiple")
                .verify(resolver, req);
    }

    /**
     * Validates that mappings for a existing resource with an alias and /etc/map entry
     * contain the /etc/map entry, the alias and the resource's path
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithAliasAndEtcMap() {

        ExpectedMappings.existingResource("/somewhere")
                .singleMapping("/alias-value-2")
                .singleMappingWithRequest("/app/alias-value-2")
                .allMappings("/alias-value-2", "http://localhost:8080/everywhere", "/somewhere")
                .allMappingsWithRequest("/app/alias-value-2", "/app/everywhere", "/app/somewhere")
                .verify(resolver, req);
    }

    /**
     * Validates that a resource with an alias on parent has the parent path set
     * to the alias value
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithAliasOnParent() {
        ExpectedMappings.existingResource("/there/that")
                .singleMapping("/alias-value/that")
                .singleMappingWithRequest("/app/alias-value/that")
                .allMappings("/alias-value/that", "/there/that")
                .allMappingsWithRequest("/app/alias-value/that", "/app/there/that")
                .verify(resolver, req);
    }

    @Test
    public void priorityForVHostMappings() {
        // override the default request
        req = mock(HttpServletRequest.class);
        when(req.getScheme()).thenReturn("http");
        when(req.getServerName()).thenReturn("virtual.host.com");
        when(req.getServerPort()).thenReturn(-1);
        when(req.getContextPath()).thenReturn("");
        when(req.getPathInfo()).thenReturn(null);

        ExpectedMappings.existingResource("/content/virtual/foo")
                .singleMapping("http://virtual.host.com/foo")
                .singleMappingWithRequest("/foo")
                .allMappings("http://virtual.host.com/foo", "/content/virtual/foo")
                .allMappingsWithRequest("/foo", "/content/virtual/foo")
                .verify(resolver, req);
    }

    /**
     * Validates that a resource with an alias on parent and on child
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithNestedAlias() {
        ExpectedMappings.existingResource("/parent/child")
                .singleMapping("/alias-parent/alias-child")
                .singleMappingWithRequest("/app/alias-parent/alias-child")
                .allMappings("/alias-parent/alias-child", "/alias-parent/child", "/parent/alias-child", "/parent/child")
                .allMappingsWithRequest(
                        "/app/alias-parent/alias-child",
                        "/app/alias-parent/child",
                        "/app/parent/alias-child",
                        "/app/parent/child")
                .verify(resolver, req);
    }

    /**
     * Validates that a resource with an alias on parent and multiple alias on child
     *
     * @throws LoginException
     */
    @Test
    public void mapResourceWithNestedMultipleAlias() {
        ExpectedMappings.existingResource("/parent/child-multiple")
                .singleMapping("/alias-parent/alias-child-1")
                .singleMappingWithRequest("/app/alias-parent/alias-child-1")
                .allMappings(
                        "/alias-parent/alias-child-1",
                        "/alias-parent/alias-child-2",
                        "/alias-parent/child-multiple",
                        "/parent/alias-child-1",
                        "/parent/alias-child-2",
                        "/parent/child-multiple")
                .allMappingsWithRequest(
                        "/app/alias-parent/alias-child-1",
                        "/app/alias-parent/alias-child-2",
                        "/app/alias-parent/child-multiple",
                        "/app/parent/alias-child-1",
                        "/app/parent/alias-child-2",
                        "/app/parent/child-multiple")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings
     *
     * <p>As vanity paths are alternate paths rather than variations so they will not be returned
     * from the singleMapping() methods.</p>
     */
    @Test
    public void mapResourceWithVanityPaths() {
        ExpectedMappings.existingResource("/vain")
                .singleMapping("/vain")
                .singleMappingWithRequest("/app/vain")
                .allMappings("/vanity-a", "/vanity-b", "/vain")
                .allMappingsWithRequest("/app/vanity-a", "/app/vanity-b", "/app/vain")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings; test removal of extensions.
     */
    @Test
    public void mapResourceWithVanityPathsWithExt() {
        ExpectedMappings.existingResource("/vain-ext")
                .singleMapping("/vain-ext")
                .singleMappingWithRequest("/app/vain-ext")
                .allMappings("/vain-ext", "/vanity.bar/foo", "/vanity-a/foo")
                .allMappingsWithRequest("/app/vain-ext", "/app/vanity.bar/foo", "/app/vanity-a/foo")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings; test empty target
     */
    @Test
    public void mapResourceWithVanityPathsTargetEmpty() {
        ExpectedMappings.existingResource("/vain-empty")
                .singleMapping("/vain-empty")
                .singleMappingWithRequest("/app/vain-empty")
                .allMappings("/vain-empty")
                .allMappingsWithRequest("/app/vain-empty")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings; test non-abs target
     */
    @Test
    public void mapResourceWithVanityPathsTargetNonAbs() {
        ExpectedMappings.existingResource("/vain-relative")
                .singleMapping("/vain-relative")
                .singleMappingWithRequest("/app/vain-relative")
                .allMappings("/vain-relative", "/foobar")
                .allMappingsWithRequest("/app/vain-relative", "/app/foobar")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings, URL shaped variants (see see SLING-11749)
     */
    @Test
    public void mapResourceWithVanityPathsURLTarget() {
        ExpectedMappings.existingResource("/vain-url")
                .singleMapping("/vain-url")
                .singleMappingWithRequest("/app/vain-url")
                .allMappings("/vain-url", "/foo", "/")
                .allMappingsWithRequest("/app/vain-url", "/app/foo", "/app/")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings, URL shaped variants, empty path (see see SLING-11757)
     */
    @Test
    public void mapResourceWithVanityPathsURLTargetNoPath() {
        ExpectedMappings.existingResource("/vain-url-nopath")
                .singleMapping("/vain-url-nopath")
                .singleMappingWithRequest("/app/vain-url-nopath")
                .allMappings("/vain-url-nopath", "")
                .allMappingsWithRequest("/app/vain-url-nopath", "")
                .verify(resolver, req);
    }

    /**
     * Validates that vanity paths are returned as mappings, invalid URL shaped variants (see see SLING-11749)
     * @throws MalformedURLException
     */
    @Test
    public void mapResourceWithVanityPathsInvalidURLTarget() {
        ExpectedMappings.existingResource("/vain-url-invalid")
                .singleMapping("/vain-url-invalid")
                .singleMappingWithRequest("/app/vain-url-invalid")
                .allMappings("/vain-url-invalid")
                .allMappingsWithRequest("/app/vain-url-invalid")
                .verify(resolver, req);
    }

    /**
     * Validates that the mapping for a non-existing resource that is the target of an alias
     * is the alias itself
     */
    @Test
    public void mapAliasTarget() {
        ExpectedMappings.nonExistingResource("/alias-value")
                .singleMapping("/alias-value")
                .singleMappingWithRequest("/app/alias-value")
                .allMappings("/alias-value")
                .allMappingsWithRequest("/app/alias-value")
                .verify(resolver, req);
    }

    /**
     * Validates the mapping for a non-existing resource target with alias on parent and its child
     *
     */
    @Test
    public void mapNestedAliasTarget() {
        ExpectedMappings.nonExistingResource("/alias-parent/alias-child")
                .singleMapping("/alias-parent/alias-child")
                .singleMappingWithRequest("/app/alias-parent/alias-child")
                .allMappings("/alias-parent/alias-child", "/alias-parent/child", "/parent/alias-child")
                .allMappingsWithRequest(
                        "/app/alias-parent/alias-child", "/app/alias-parent/child", "/app/parent/alias-child")
                .verify(resolver, req);
    }

    /**
     * Validates that in case of the optimized lookup less repository access is done
     * @throws Exception
     */
    @Test
    public void mapAliasLookupOptimization() throws Exception {
        ResourceResolverImpl spyResolver = Mockito.spy((ResourceResolverImpl) resolver);

        // inject that spy into the mapper, so we can reason about the repo access
        ResourceMapperImpl mapper = (ResourceMapperImpl) resolver.adaptTo(ResourceMapper.class);
        Field internalResolver = mapper.getClass().getDeclaredField("resolver");
        internalResolver.setAccessible(true);
        internalResolver.set(mapper, spyResolver);

        assertResourceResolverAccess(spyResolver, mapper, "/parent/child"); // alias on both parent and child
        assertResourceResolverAccess(
                spyResolver, mapper, "/alias-parent/alias-child"); // the path consists of 2 aliases
        assertResourceResolverAccess(spyResolver, mapper, "/content/very/deep/path/with/resources"); // deep path
    }

    /**
     * validate the number of repository accesses by the previous operation
     * @param spyResolver the resourceresolver
     * @param mapper the ResourceMapper to use
     * @param path the mapped path without trailing slash
     */
    private void assertResourceResolverAccess(
            ResourceResolverImpl spyResolver, ResourceMapperImpl mapper, String path) {
        mapper.getMapping(path);
        Mockito.verify(spyResolver, Mockito.times(0)).resolve(Mockito.any(String.class));
        Mockito.verify(spyResolver, Mockito.times(1)).resolveInternal(Mockito.any(String.class), Mockito.anyMap());
        if (!this.optimiseAliasResolution) {
            // int pathSegments = (int) path.chars().filter(c -> c == '/').count();
            // we should see here multiple calls to getResource, but the alias
            // handler uses a different instance that is acquired for each interaction
            // maybe something to check
        }
        Mockito.clearInvocations(spyResolver);
    }

    static class ExpectedMappings {

        public static ExpectedMappings existingResource(String path) {
            return new ExpectedMappings(path, true);
        }

        public static ExpectedMappings nonExistingResource(String path) {
            return new ExpectedMappings(path, false);
        }

        private final String path;
        private final boolean exists;

        private String singleMapping;
        private String singleMappingWithRequest;
        private Set<String> allMappings;
        private Set<String> allMappingsWithRequest;

        private ExpectedMappings(String path, boolean exists) {
            this.path = path;
            this.exists = exists;
        }

        public ExpectedMappings singleMapping(String singleMapping) {
            this.singleMapping = singleMapping;

            return this;
        }

        public ExpectedMappings singleMappingWithRequest(String singleMappingWithRequest) {
            this.singleMappingWithRequest = singleMappingWithRequest;

            return this;
        }

        public ExpectedMappings allMappings(String... allMappings) {
            this.allMappings = new HashSet<>(Arrays.asList(allMappings));

            return this;
        }

        public ExpectedMappings allMappingsWithRequest(String... allMappingsWithRequest) {
            this.allMappingsWithRequest = new HashSet<>(Arrays.asList(allMappingsWithRequest));

            return this;
        }

        public void verify(ResourceResolver resolver, HttpServletRequest request) {
            checkConfigured();

            Resource res = resolver.getResource(path);
            if (exists) {
                assertThat("Resource was null but should exist", res, notNullValue());
                assertThat(
                        "Resource is non-existing but should exist",
                        ResourceUtil.isNonExistingResource(res),
                        is(false));
            } else {
                assertThat(
                        "Resource is neither null nor non-existing",
                        res == null || ResourceUtil.isNonExistingResource(res),
                        is(true));
            }

            // downcast to ensure we're testing the right class
            ResourceMapperImpl mapper = (ResourceMapperImpl) resolver.adaptTo(ResourceMapper.class);

            assertThat("Single mapping without request", mapper.getMapping(path), is(singleMapping));
            if (!path.isEmpty()) // an empty path is invalid, hence not testing with a request
            assertThat("Single mapping with request", mapper.getMapping(path, request), is(singleMappingWithRequest));
            assertThat("All mappings without request", mapper.getAllMappings(path), is(allMappings));
            if (!path.isEmpty()) // an empty path is invalid, hence not testing with a request
            assertThat("All mappings with request", mapper.getAllMappings(path, request), is(allMappingsWithRequest));
        }

        private void checkConfigured() {
            if (singleMapping == null) throw new IllegalStateException("singleMapping is null");
            if (singleMappingWithRequest == null) throw new IllegalStateException("singleMappingWithRequest is null");
            if (allMappings == null) throw new IllegalStateException("allMappings is null");
            if (allMappingsWithRequest == null) throw new IllegalStateException("allMappingsWithRequest is null");
        }
    }
}
