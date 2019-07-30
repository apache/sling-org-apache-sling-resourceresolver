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
import org.apache.sling.resourceresolver.impl.CommonResourceResolverFactoryImpl;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryActivator;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryImpl;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import static java.util.Arrays.asList;
import static org.apache.sling.resourceresolver.impl.MockedResourceResolverImplTest.createRPHandler;
import static org.apache.sling.resourceresolver.impl.ResourceResolverImpl.PROP_REDIRECT_INTERNAL;
import static org.apache.sling.resourceresolver.impl.mapping.MapEntries.PROP_REDIRECT_EXTERNAL;
import static org.apache.sling.resourceresolver.util.MockTestUtil.ExpectedEtcMapping;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * These tests are for the /etc/map setup of the Map Entries when
 * an /etc/map is present.
 */
public class EtcMappingMapEntriesTest extends AbstractMappingMapEntriesTest {

    @Test
    public void root_node_to_content_mapping() throws Exception {
        setupEtcMapResource("localhost.8080", http,PROP_REDIRECT_EXTERNAL, "/content/simple-node");

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/localhost.8080/", "/content/simple-node/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for simple node", mapEntries.getResolveMaps());
    }

    @Test
    public void match_to_content_mapping() throws Exception {
        setupEtcMapResource("test-node", http,
            PROP_REG_EXP, "localhost.8080/",
            PROP_REDIRECT_EXTERNAL, "/content/simple-match/"
        );

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/localhost.8080/", "/content/simple-match/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for simple match", mapEntries.getResolveMaps());
    }

    // The following tests are based on the example from the https://sling.apache.org/documentation/the-sling-engine/mappings-for-resource-resolution.html page

    @Test
    public void internal_to_external_node_mapping() throws Exception {
        setupEtcMapResource("example.com.80", http,PROP_REDIRECT_EXTERNAL, "http://www.example.com/");

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/example.com.80/", "http://www.example.com/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for internal to external based on node", mapEntries.getResolveMaps());
    }

    @Test
    public void internal_root_to_content_node_mapping() throws Exception {
        setupEtcMapResource("www.example.com.80", http,PROP_REDIRECT_INTERNAL, "/example");

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping().addEtcMapEntry("^http/www.example.com.80/", true, "/example/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for internal root to content", mapEntries.getResolveMaps());
    }

    @Test
    public void host_redirect_match_mapping() throws Exception {
        setupEtcMapResource("any_example.com.80", http,
            PROP_REG_EXP, ".+\\.example\\.com\\.80",
            PROP_REDIRECT_EXTERNAL, "http://www.example.com/"
        );

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping().addEtcMapEntry("^http/.+\\.example\\.com\\.80", false, "http://www.example.com/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for host redirect match mapping", mapEntries.getResolveMaps());
    }

    @Test
    public void nested_internal_mixed_mapping() throws Exception {
        Resource localhost = setupEtcMapResource("localhost_any", http,
            PROP_REG_EXP, "localhost\\.\\d*",
            PROP_REDIRECT_INTERNAL, "/content"
        );
        setupEtcMapResource("cgi-bin", localhost, PROP_REDIRECT_INTERNAL, "/scripts");
        setupEtcMapResource("gateway", localhost, PROP_REDIRECT_INTERNAL, "http://gbiv.com");
        setupEtcMapResource("(stories)", localhost, PROP_REDIRECT_INTERNAL, "/anecdotes/$1");

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping()
            .addEtcMapEntry("^http/localhost\\.\\d*", true, "/content")
            .addEtcMapEntry("^http/localhost\\.\\d*/cgi-bin/", true, "/scripts/")
            .addEtcMapEntry("^http/localhost\\.\\d*/gateway/", true, "http://gbiv.com/")
            .addEtcMapEntry("^http/localhost\\.\\d*/(stories)/", true, "/anecdotes/$1/");
        expectedEtcMapping.assertEtcMap("Etc Mapping for nested internal mixed mapping", mapEntries.getResolveMaps());

        // Not really an etc-map resource but it is good for now
        final Resource test = setupEtcMapResource("/scripts", "test");
        ResourceProvider<?> rp = new ResourceProvider<Object>() {
            @Override
            public Resource getResource(ResolveContext<Object> ctx, String path, ResourceContext rCtx, Resource parent) {
                if(path.equals("/scripts/test")) {
                    return test;
                }
                if(path.startsWith(map.getPath())) {
                    return findMapping(map, path);
                }
                return null;
            }

            private Resource findMapping(Resource parent, String path) {
                if(parent.getPath().equals(path)) {
                    return parent;
                }
                Iterator<Resource> i = parent.listChildren();
                while(i.hasNext()) {
                    Resource child = i.next();
                    if(path.equals(child.getPath())) {
                        return child;
                    } else {
                        return findMapping(child, path);
                    }
                }
                return null;
            }

            @Override
            public Iterator<Resource> listChildren(ResolveContext<Object> ctx, Resource parent) {
                if(parent.getPath().startsWith(map.getPath())) {
                    return parent.listChildren();
                }
                return null;
            }
        };

        List<ResourceProviderHandler> handlers = asList(createRPHandler(rp, "rp1", 0, "/"));
        ResourceProviderTracker resourceProviderTracker = mock(ResourceProviderTracker.class);
        ResourceProviderStorage storage = new ResourceProviderStorage(handlers);
        when(resourceProviderTracker.getResourceProviderStorage()).thenReturn(storage);
        ResourceResolverFactoryActivator activator = spy(new ResourceResolverFactoryActivator());
        // Both 'resourceProviderTracker' and 'resourceAccessSecurityTracker' are package private and so we cannot
        // set them here. Intercept the call to obtain them and provide the desired value
        when(activator.getResourceProviderTracker()).thenReturn(resourceProviderTracker);
        when(activator.getResourceAccessSecurityTracker()).thenReturn(new ResourceAccessSecurityTracker());
        when(activator.getBundleContext()).thenReturn(bundleContext);
        when(activator.getStringInterpolationProvider()).thenReturn(stringInterpolationProvider);
        when(activator.getMapRoot()).thenReturn("/etc/map");
        when(activator.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        CommonResourceResolverFactoryImpl commonFactory = spy(new CommonResourceResolverFactoryImpl(activator));
        when(bundleContext.getBundle()).thenReturn(bundle);
        ServiceUserMapper serviceUserMapper = mock(ServiceUserMapper.class);
        when(activator.getServiceUserMapper()).thenReturn(serviceUserMapper);
        when(serviceUserMapper.getServiceUserID(any(Bundle.class),anyString())).thenReturn("mapping");
        Method method = CommonResourceResolverFactoryImpl.class.getDeclaredMethod("activate", BundleContext.class);
        method.setAccessible(true);
        method.invoke(commonFactory, bundleContext);
        final Bundle usingBundle = mock(Bundle.class);
        ResourceResolverFactoryImpl resFac = new ResourceResolverFactoryImpl(commonFactory, usingBundle, null);
        ResourceResolver resResolver = resFac.getAdministrativeResourceResolver(null);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);
        Resource mappedResource = resResolver.resolve(request, "/cgi-bin/test.html");
        String path = mappedResource.getPath();
        assertEquals("Wrong Resolved Path", "/scripts/test", path);
    }

//    @Test
//    public void regex_map_internal_mapping() throws Exception {
//        setupEtcMapResource("regexmap", http,
//            PROP_REG_EXP, "$1.example.com/$2",
//            PROP_REDIRECT_INTERNAL, "/content/([^/]+)/(.*)"
//        );
//
//        mapEntries.doInit();
//        // Regex Mappings are ignored for the Resolve Map
//        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping();
////            .addEtcMapEntry("^http/$1.example.com/$2", true, "/content/([^/]+)/(.*)");
//        expectedEtcMapping.assertEtcMap("Etc Mapping for regex map internal mapping", mapEntries.getResolveMaps());
//
//        ResourceProvider<?> rp = new ResourceProvider<Object>() {
//
//            @Override
//            public Resource getResource(ResolveContext<Object> ctx, String path, ResourceContext rCtx, Resource parent) {
//                return null;
//            }
//
//            @Override
//            public Iterator<Resource> listChildren(ResolveContext<Object> ctx, Resource parent) {
//                return null;
//            }
//        };
//
//        List<ResourceProviderHandler> handlers = asList(createRPHandler(rp, "rp1", 0, "/"));
//        ResourceProviderTracker resourceProviderTracker = mock(ResourceProviderTracker.class);
//        ResourceProviderStorage storage = new ResourceProviderStorage(handlers);
//        when(resourceProviderTracker.getResourceProviderStorage()).thenReturn(storage);
//        ResourceResolverFactoryActivator activator = spy(new ResourceResolverFactoryActivator());
//        when(activator.getResourceProviderTracker()).thenReturn(resourceProviderTracker);
////        activator.resourceProviderTracker = resourceProviderTracker;
//        when(activator.getResourceAccessSecurityTracker()).thenReturn(new ResourceAccessSecurityTracker());
////        activator.resourceAccessSecurityTracker = new ResourceAccessSecurityTracker();
//        CommonResourceResolverFactoryImpl commonFactory = new CommonResourceResolverFactoryImpl(activator);
//        final Bundle usingBundle = mock(Bundle.class);
//        ResourceResolverFactoryImpl resFac = new ResourceResolverFactoryImpl(commonFactory, usingBundle, null);
//        ResourceResolver resResolver = resFac.getAdministrativeResourceResolver(null);
//
//        HttpServletRequest request = mock(HttpServletRequest.class);
//        when(request.getScheme()).thenReturn("http");
//        when(request.getServerName()).thenReturn("a.example.com");
//        when(request.getServerPort()).thenReturn(80);
//        Resource mappedResource = resResolver.resolve(request, "/b.html");
//        String path = mappedResource.getPath();
//    }
}
