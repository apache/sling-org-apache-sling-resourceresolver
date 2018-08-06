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
    }
}
