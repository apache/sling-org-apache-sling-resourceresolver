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

import static org.apache.sling.resourceresolver.impl.ResourceResolverImpl.PROP_ALIAS;
import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_NAME;
import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_ROOT;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.mapping.ResourceMapper;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryActivator;
import org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

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
public class ResourceMapperImplTest {
    
    @Rule
    public final OsgiContext ctx = new OsgiContext();

    private HttpServletRequest req;
    private ResourceResolver resolver;

    @Before
    public void prepare() throws LoginException {

        ctx.registerInjectActivateService(new ServiceUserMapperImpl());
        ctx.registerInjectActivateService(new ResourceAccessSecurityTracker());
        ctx.registerInjectActivateService(new StringInterpolationProviderImpl());

        InMemoryResourceProvider resourceProvider = new InMemoryResourceProvider();
        resourceProvider.putResource("/"); // root
        resourceProvider.putResource("/here"); // regular page
        resourceProvider.putResource("/there", PROP_ALIAS, "alias-value"); // with alias
        resourceProvider.putResource("/somewhere", PROP_ALIAS, "alias-value-2"); // with alias and also /etc/map
        resourceProvider.putResource("/there/that"); // parent has alias
        
        // build /etc/map structure
        resourceProvider.putResource("/etc");
        resourceProvider.putResource("/etc/map");
        resourceProvider.putResource("/etc/map/http");
        resourceProvider.putResource("/etc/map/http/localhost_any",
                "sling:internalRedirect", "/somewhere",
                "sling:match", "localhost.8080/everywhere");
        
        // we fake the fact that we are the JCR resource provider since it's the required one
        ctx.registerService(ResourceProvider.class, resourceProvider, PROPERTY_ROOT, "/", PROPERTY_NAME, "JCR");
        // disable optimised alias resolution as it relies on JCR queries
        ctx.registerInjectActivateService(new ResourceResolverFactoryActivator(),
                "resource.resolver.optimize.alias.resolution", false);
        
        ResourceResolverFactory factory = ctx.getService(ResourceResolverFactory.class);
        
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
        if ( resolver != null )
            resolver.close();
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
            .allMappings("http://localhost:8080/everywhere", "/alias-value-2", "/somewhere")
            .allMappingsWithRequest("/app/everywhere","/app/alias-value-2", "/app/somewhere")
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
            .allMappingsWithRequest("/app/alias-value/that","/app/there/that")
            .verify(resolver, req);       
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
            if ( exists ) {
                assertThat("Resource was null but should exist", res, notNullValue());
                assertThat("Resource is non-existing but should exist", ResourceUtil.isNonExistingResource(res), is(false));
            } else {
                assertThat("Resource is neither null nor non-existing", res == null || ResourceUtil.isNonExistingResource(res), is(true));
            }

            // downcast to ensure we're testing the right class
            ResourceMapperImpl mapper = (ResourceMapperImpl) resolver.adaptTo(ResourceMapper.class);
            
            assertThat("Single mapping without request", mapper.getMapping(path), is(singleMapping));
            assertThat("Single mapping with request", mapper.getMapping(path, request), is(singleMappingWithRequest));
            assertThat("All mappings without request", mapper.getAllMappings(path), is(allMappings));
            assertThat("All mappings with request", mapper.getAllMappings(path, request), is(allMappingsWithRequest));
        }

        private void checkConfigured() {
            if ( singleMapping == null )
                throw new IllegalStateException("singleMapping is null");
            if ( singleMappingWithRequest == null )
                throw new IllegalStateException("singleMappingWithRequest is null");
            if ( allMappings == null )
                throw new IllegalStateException("allMappings is null");
            if ( allMappingsWithRequest == null )
                throw new IllegalStateException("allMappingsWithRequest is null");
        }
    }
}
