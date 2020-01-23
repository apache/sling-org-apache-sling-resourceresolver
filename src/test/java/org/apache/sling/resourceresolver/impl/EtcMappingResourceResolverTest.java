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
package org.apache.sling.resourceresolver.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider;
import org.apache.sling.resourceresolver.impl.mapping.MapEntries;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProviderConfiguration;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProviderImpl;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.apache.sling.resourceresolver.impl.MockedResourceResolverImplTest.createRPHandler;
import static org.apache.sling.resourceresolver.impl.ResourceResolverImpl.PROP_REDIRECT_INTERNAL;
import static org.apache.sling.resourceresolver.impl.mapping.MapEntries.PROP_REDIRECT_EXTERNAL;
import static org.apache.sling.resourceresolver.util.MockTestUtil.ExpectedEtcMapping;
import static org.apache.sling.resourceresolver.util.MockTestUtil.buildResource;
import static org.apache.sling.resourceresolver.util.MockTestUtil.callInaccessibleMethod;
import static org.apache.sling.resourceresolver.util.MockTestUtil.checkInternalResource;
import static org.apache.sling.resourceresolver.util.MockTestUtil.checkRedirectResource;
import static org.apache.sling.resourceresolver.util.MockTestUtil.createRequestFromUrl;
import static org.apache.sling.resourceresolver.util.MockTestUtil.createStringInterpolationProviderConfiguration;
import static org.apache.sling.resourceresolver.util.MockTestUtil.setInaccessibleField;
import static org.apache.sling.resourceresolver.util.MockTestUtil.setupStringInterpolationProvider;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * These are the same tests as in the EtcMappingMapEntriesTest but in this
 * class we are actually mocking the Resource Resolver Factory and its classes
 * and we test the mapping and resource resolution through the resource resolver
 * rather the MapEntries.
 */
public class EtcMappingResourceResolverTest {

    static final String PROP_REG_EXP = "sling:match";

    @Mock
    ResourceResolverFactory resourceResolverFactory;

    @Mock
    BundleContext bundleContext;

    @Mock
    Bundle bundle;

    @Mock
    EventAdmin eventAdmin;

    @Mock
    ResourceResolver resourceResolver;

    @Mock
    ResourceProvider<?> resourceProvider;

    StringInterpolationProviderConfiguration stringInterpolationProviderConfiguration;

    StringInterpolationProvider stringInterpolationProvider = new StringInterpolationProviderImpl();
    MapEntries mapEntries;

    File vanityBloomFilterFile;

    CommonResourceResolverFactoryImpl commonFactory;

    Resource etc;
    Resource map;
    Resource http;

    Map<String, Map<String, String>> aliasMap;

    @SuppressWarnings({"unchecked"})
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        List<MapConfigurationProvider.VanityPathConfig> configs = getVanityPathConfigs();
        vanityBloomFilterFile = new File("target/test-classes/resourcesvanityBloomFilter.txt");
        List<ResourceProviderHandler> handlers = asList(createRPHandler(resourceProvider, "rp1", 0, "/"));
        ResourceProviderTracker resourceProviderTracker = mock(ResourceProviderTracker.class);
        ResourceProviderStorage storage = new ResourceProviderStorage(handlers);
        when(resourceProviderTracker.getResourceProviderStorage()).thenReturn(storage);
        ResourceResolverFactoryActivator activator = new ResourceResolverFactoryActivator();
        // These fields on the Activator a package private so we need reflection to access them
        setInaccessibleField("resourceProviderTracker", activator, resourceProviderTracker);
        setInaccessibleField("resourceAccessSecurityTracker", activator, new ResourceAccessSecurityTracker());
        setInaccessibleField("bundleContext", activator, bundleContext);
        stringInterpolationProviderConfiguration = createStringInterpolationProviderConfiguration();
        setInaccessibleField("stringInterpolationProvider", activator, stringInterpolationProvider);
        setInaccessibleField("mapRoot", activator, "/etc/map");
        setInaccessibleField("mapRootPrefix", activator, "/etc/map");
        setInaccessibleField("observationPaths", activator, new Path[] {new Path("/")});
        ServiceUserMapper serviceUserMapper = mock(ServiceUserMapper.class);
        setInaccessibleField("serviceUserMapper", activator, serviceUserMapper);
        commonFactory = spy(new CommonResourceResolverFactoryImpl(activator));
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(bundleContext.getDataFile("vanityBloomFilter.txt")).thenReturn(vanityBloomFilterFile);
        when(serviceUserMapper.getServiceUserID(any(Bundle.class),anyString())).thenReturn("mapping");
        // Activate method is package private so we use reflection to to call it
        callInaccessibleMethod("activate", null, commonFactory, BundleContext.class, bundleContext);
        final Bundle usingBundle = mock(Bundle.class);
        resourceResolverFactory = new ResourceResolverFactoryImpl(commonFactory, usingBundle, null);
        resourceResolver = resourceResolverFactory.getAdministrativeResourceResolver(null);

        etc = buildResource("/etc", null, resourceResolver, resourceProvider);
        map = buildResource("/etc/map", etc, resourceResolver, resourceProvider);
        http = buildResource("/etc/map/http", map, resourceResolver, resourceProvider);
    }

    List<MapConfigurationProvider.VanityPathConfig> getVanityPathConfigs() {
        return new ArrayList<>();
    }

    /**
     * Changes to the /etc/map in our tests are not taking effect until there is an Change Event issued
     *
     * ATTENTION: this method can only be issued once. After that the Resource Metadata is locked and
     * hence updates will fail
     *
     * @param path Path to the resource root to be refreshed
     * @param isExternal External flag of the ResourceChange event
     */
    void refreshMapEntries(String path, boolean isExternal) {
        ((MapEntries) commonFactory.getMapEntries()).onChange(
            asList(
                new ResourceChange(ResourceChange.ChangeType.ADDED, path, isExternal)
            )
        );
    }

    @Test
    public void root_node_to_content_mapping() throws Exception {
        buildResource(http.getPath() + "/localhost.8080", http, resourceResolver, resourceProvider, PROP_REDIRECT_EXTERNAL, "/content/simple-node");
        // This updates the map entries so that the newly added resources are added.
        // ATTENTION: only call this after all etc-mapping resources are defined as this lock their Resource Meta Data and prevents a re-update
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/localhost.8080/", "/content/simple-node/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for root node to content", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://localhost:8080/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "/content/simple-node/", 302);
    }

    @Test
    public void match_to_content_mapping() throws Exception {
        buildResource("test-node", http, resourceResolver, resourceProvider,
            PROP_REG_EXP, "localhost.8080/",
            PROP_REDIRECT_EXTERNAL, "/content/simple-match/"
        );
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/localhost.8080/", "/content/simple-match/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for match to content", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://localhost:8080/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "/content/simple-match/", 302);
    }

    // The following tests are based on the example from the https://sling.apache.org/documentation/the-sling-engine/mappings-for-resource-resolution.html page

    @Test
    public void internal_to_external_node_mapping() throws Exception {
        buildResource("example.com.80", http, resourceResolver, resourceProvider, PROP_REDIRECT_EXTERNAL, "http://www.example.com/");
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/example.com.80/", "http://www.example.com/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for internal to external based on node", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://example.com/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "http://www.example.com/", 302);
    }

    @Test
    public void internal_root_to_content_node_mapping() throws Exception {
        buildResource("/example", null, resourceResolver, resourceProvider);

        buildResource("www.example.com.80", http, resourceResolver, resourceProvider, PROP_REDIRECT_INTERNAL, "/example");
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping().addEtcMapEntry("^http/www.example.com.80/", true, "/example/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for internal root to content", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://www.example.com:80/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkInternalResource(resolvedResource, "/example");
    }

    @Test
    public void host_redirect_match_mapping() throws Exception {
        buildResource("any_example.com.80", http, resourceResolver, resourceProvider,
            PROP_REG_EXP, ".+\\.example\\.com\\.80",
            PROP_REDIRECT_EXTERNAL, "http://www.example.com/"
        );
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping().addEtcMapEntry("^http/.+\\.example\\.com\\.80", false, "http://www.example.com/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for host redirect match mapping", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://www.example.com");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "http://www.example.com//", 302);
    }

    @Test
    public void nested_internal_mixed_mapping() throws Exception {
        Resource localhost = buildResource("localhost_any", http, resourceResolver, resourceProvider,
            PROP_REG_EXP, "localhost\\.\\d*",
            PROP_REDIRECT_INTERNAL, "/content"
        );
        buildResource("cgi-bin", localhost, resourceResolver, resourceProvider,PROP_REDIRECT_INTERNAL, "/scripts");
        buildResource("gateway", localhost, resourceResolver, resourceProvider,PROP_REDIRECT_INTERNAL, "http://gbiv.com");
        buildResource("(stories)", localhost, resourceResolver, resourceProvider,PROP_REDIRECT_INTERNAL, "/anecdotes/$1");

        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping()
            .addEtcMapEntry("^http/localhost\\.\\d*", true, "/content")
            .addEtcMapEntry("^http/localhost\\.\\d*/cgi-bin/", true, "/scripts/")
            .addEtcMapEntry("^http/localhost\\.\\d*/gateway/", true, "http://gbiv.com/")
            .addEtcMapEntry("^http/localhost\\.\\d*/(stories)/", true, "/anecdotes/$1/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for nested internal mixed mapping", commonFactory.getMapEntries().getResolveMaps());

        buildResource("/content", null, resourceResolver, resourceProvider);
        Resource scripts = buildResource("/scripts", null, resourceResolver, resourceProvider);
        Resource scriptsChild = buildResource("/scripts/child", scripts, resourceResolver, resourceProvider);
        Resource anecdotes = buildResource("/anecdotes", null, resourceResolver, resourceProvider);
        Resource stories = buildResource("/anecdotes/stories", anecdotes, resourceResolver, resourceProvider);

        HttpServletRequest request = createRequestFromUrl("http://localhost:1234/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkInternalResource(resolvedResource, "/content");

        resolvedResource = resourceResolver.resolve(request, "/cgi-bin/");
        checkInternalResource(resolvedResource, "/scripts");
        resolvedResource = resourceResolver.resolve(request, "/cgi-bin/child/");
        checkInternalResource(resolvedResource, "/scripts/child");
//AS TODO: Does not redirect -> investigate later
//        resolvedResource = resourceResolver.resolve(request, "/gateway/");
//        checkRedirectResource(resolvedResource, "http://gbiv.com/", 302);
        resolvedResource = resourceResolver.resolve(request, "/stories/");
        checkInternalResource(resolvedResource, "/anecdotes/stories");
    }

    @Test
    public void simple_node_string_interpolation() throws Exception {
        buildResource("$[config:siv.one]", http, resourceResolver, resourceProvider,PROP_REDIRECT_EXTERNAL, "/content/simple-node");
        setupStringInterpolationProvider(stringInterpolationProvider, stringInterpolationProviderConfiguration, new String[] {"siv.one=test-simple-node.80"});

        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/test-simple-node.80/", "/content/simple-node/");
        expectedEtcMapping.assertEtcMap("String Interpolation for simple match", commonFactory.getMapEntries().getResolveMaps());

        Resource content = buildResource("/content", null, resourceResolver, resourceProvider);
        Resource simpleNode = buildResource("/content/simple-node", content, resourceResolver, resourceProvider);

        HttpServletRequest request = createRequestFromUrl("http://test-simple-node:80/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "/content/simple-node/", 302);
    }

    @Test
    public void simple_match_string_interpolation() throws Exception {
        buildResource("test-node", http, resourceResolver, resourceProvider,
            PROP_REG_EXP, "$[config:siv.one]/",
            PROP_REDIRECT_EXTERNAL, "/content/simple-match/"
        );
        setupStringInterpolationProvider(stringInterpolationProvider, stringInterpolationProviderConfiguration, new String[] {"siv.one=test-simple-match.80"});

        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/test-simple-match.80/", "/content/simple-match/");
        expectedEtcMapping.assertEtcMap("String Interpolation for simple match", commonFactory.getMapEntries().getResolveMaps());

        HttpServletRequest request = createRequestFromUrl("http://test-simple-match:80/");
        Resource resolvedResource = resourceResolver.resolve(request, "/");
        checkRedirectResource(resolvedResource, "/content/simple-match/", 302);
    }

    /**
     * ATTENTION: this tests showcases an erroneous condition of an endless circular mapping in the /etc/map. When
     * this test passes this condition is present. After a fix this test must be adjusted.
     *
     * This confirms an issue with the Etc Mapping where a mapping from a node to a child node (here / to /content)
     * ends up in a endless circular mapping.
     * The only way to recover from this is to go to the OSGi console and change the /etc/map path in the Resource
     * Resolver factory.
     * Either the Etc Mapping discovers this condition and stops it or at least ignores mapping for Composum to allow
     * the /etc/map to be edited.
     */
    @Test
    public void endless_circular_mapping() throws Exception {
        buildResource(http.getPath() + "/localhost.8080", http, resourceResolver, resourceProvider, PROP_REDIRECT_EXTERNAL, "/content");
        refreshMapEntries("/etc/map", true);

        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/localhost.8080/", "/content/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for root node to content", commonFactory.getMapEntries().getResolveMaps());

        buildResource("/content/test", null, resourceResolver, resourceProvider);
        buildResource("/content/content/test", null, resourceResolver, resourceProvider);
        buildResource("/content/content/content/test", null, resourceResolver, resourceProvider);

        HttpServletRequest request = createRequestFromUrl("http://localhost:8080/");
        Resource resolvedResource = resourceResolver.resolve(request, "/test.html");
        checkRedirectResource(resolvedResource, "/content/test.html", 302);

        resolvedResource = resourceResolver.resolve(request, "/content/test.html");
        checkRedirectResource(resolvedResource, "/content/content/test.html", 302);

        resolvedResource = resourceResolver.resolve(request, "/content/content/test.html");
        checkRedirectResource(resolvedResource, "/content/content/content/test.html", 302);
    }
}
