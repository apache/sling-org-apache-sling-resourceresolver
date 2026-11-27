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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryActivator;
import org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.osgi.junit5.OsgiContext;
import org.apache.sling.testing.mock.osgi.junit5.OsgiContextExtension;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.util.tracker.ServiceTracker;

import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_NAME;
import static org.apache.sling.spi.resource.provider.ResourceProvider.PROPERTY_ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ResolutionWithInaccessiblePathsTest {

    @RegisterExtension
    OsgiContextExtension osgiContextExtension = new OsgiContextExtension();

    @ParameterizedTest(name = "[{index}] inaccessible path: {0}")
    @ValueSource(
            strings = {
                "none (equivalent to admin access)",
                "/",
                "/content",
                "/content/en",
                "/content/en/solutions",
                "/content/en/solutions/airlines",
                "/content/en/solutions/airlines/products"
            })
    void simpleResolution(String inaccessiblePath, OsgiContext ctx) throws InterruptedException, LoginException {
        ResourceResolverFactory resourceResolverFactory = registerResourceResolverfactory(ctx, p -> {
            Stream.of(
                            "/",
                            "/content",
                            "/content/en",
                            "/content/en/solutions",
                            "/content/en/solutions/airlines",
                            "/content/en/solutions/airlines/products")
                    .filter(path -> !Objects.equals(path, inaccessiblePath))
                    .forEach(p::putResource);
            p.putResource("/content/en/solutions/airlines/products/wings");
        });

        ResourceResolver resolver = resourceResolverFactory.getResourceResolver(Collections.emptyMap());
        Resource resource = resolver.resolve("/content/en/solutions/airlines/products/wings.html");
        assertEquals(
                "/content/en/solutions/airlines/products/wings",
                resource.getResourceMetadata().getResolutionPath());
        assertEquals(".html", resource.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/en/solutions/airlines/products/wings", resource.getPath());
    }

    @ParameterizedTest(name = "[{index}] inaccessible path: {0}")
    @ValueSource(strings = {"none (equivalent to admin access)", "/", "/content", "/content/es"})
    void aliasResolution(String inaccessiblePath, OsgiContext ctx) throws InterruptedException, LoginException {
        ResourceResolverFactory resourceResolverFactory = registerResourceResolverfactory(ctx, p -> {
            Stream.of("/", "/content", "/content/es")
                    .filter(path -> !Objects.equals(path, inaccessiblePath))
                    .forEach(p::putResource);
            p.putResource("/content/es/solutions");
            p.putResource("/content/es/solutions/airlines", "sling:alias", "aerolineas");
            p.putResource("/content/es/solutions/airlines/products", "sling:alias", "productos");
            p.putResource("/content/es/solutions/airlines/products/wings", "sling:alias", "alas");
        });

        ResourceResolver resolver = resourceResolverFactory.getResourceResolver(Collections.emptyMap());
        Resource resource;

        resource = resolver.resolve("/content/es/solutions/aerolineas/productos/alas.html");
        assertEquals(
                "/content/es/solutions/aerolineas/productos/alas",
                resource.getResourceMetadata().getResolutionPath());
        assertEquals(".html", resource.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/es/solutions/airlines/products/wings", resource.getPath());

        resource = resolver.resolve("/content/es/solutions/aerolineas/productos/wings.mobile.html");
        assertEquals(
                "/content/es/solutions/aerolineas/productos/wings",
                resource.getResourceMetadata().getResolutionPath());
        assertEquals(".mobile.html", resource.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/es/solutions/airlines/products/wings", resource.getPath());

        resource = resolver.resolve("/content/es/solutions/airlines/productos/alas.json");
        assertEquals(
                "/content/es/solutions/airlines/productos/alas",
                resource.getResourceMetadata().getResolutionPath());
        assertEquals(".json", resource.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/es/solutions/airlines/products/wings", resource.getPath());
    }

    @ParameterizedTest(name = "[{index}] inaccessible path: {0}")
    @ValueSource(strings = {"none (equivalent to admin access)", "/", "/content"})
    void emptySegmentResolution(String inaccessiblePath, OsgiContext ctx) throws InterruptedException, LoginException {
        ResourceResolverFactory resourceResolverFactory = registerResourceResolverfactory(ctx, p -> {
            Stream.of("/", "/content", "/content/en")
                    .filter(path -> !Objects.equals(path, inaccessiblePath))
                    .forEach(p::putResource);
        });

        ResourceResolver resolver = resourceResolverFactory.getResourceResolver(Collections.emptyMap());
        Resource resource = resolver.resolve("//content/en.html"); // leading double slash
        assertEquals("/content/en", resource.getResourceMetadata().getResolutionPath());
        assertEquals(".html", resource.getResourceMetadata().getResolutionPathInfo());
        assertEquals("/content/en", resource.getPath());
    }

    private static ResourceResolverFactory registerResourceResolverfactory(
            OsgiContext ctx, Consumer<InMemoryResourceProvider> resourceInitializer) throws InterruptedException {
        ctx.registerInjectActivateService(new ServiceUserMapperImpl());
        ctx.registerInjectActivateService(new ResourceAccessSecurityTracker());
        ctx.registerInjectActivateService(new StringInterpolationProviderImpl());

        InMemoryResourceProvider provider = new InMemoryResourceProvider(false);
        resourceInitializer.accept(provider);

        // we fake the fact that we are the JCR resource provider since it's the required one
        ctx.registerService(ResourceProvider.class, provider, PROPERTY_ROOT, "/", PROPERTY_NAME, "JCR");

        Map<String, Object> properties = new HashMap<>();
        properties.put("resource.resolver.optimize.alias.resolution", true);
        properties.put("resource.resolver.alias.cache.in.background", false);
        properties.put("resource.resolver.mapping", new String[] {"/:/"});
        ctx.registerInjectActivateService(ResourceResolverFactoryActivator.class, properties);

        final ResourceResolverFactory factory;
        final ServiceTracker<ResourceResolverFactory, ResourceResolverFactory> tracker =
                new ServiceTracker<>(ctx.bundleContext(), ResourceResolverFactory.class, null);
        try {
            tracker.open();
            factory = tracker.waitForService(TimeUnit.SECONDS.toMillis(300));
        } finally {
            tracker.close();
        }

        assertNotNull(factory);
        return factory;
    }
}
