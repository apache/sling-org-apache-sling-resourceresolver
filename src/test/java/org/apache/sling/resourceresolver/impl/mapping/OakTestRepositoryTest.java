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

import javax.jcr.Node;
import javax.jcr.Session;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.resource.internal.helper.jcr.JcrResourceProvider;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverFactoryActivator;
import org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl;
import org.apache.sling.testing.mock.osgi.MockOsgi;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Minimal test of {@link OakTestRepository}.
 * <p>
 * Used to verify/test aspects of Sling behavior on top of a JCR repository.
 */
public class OakTestRepositoryTest {

    private final OsgiContext context = new OsgiContext();

    private OakTestRepository repository;

    /** the repository is created before, and shut down after, the OSGi services */
    @Rule
    public final RuleChain rules = RuleChain.outerRule(new ExternalResource() {
                @Override
                protected void before() throws Throwable {
                    repository = new OakTestRepository();
                }

                @Override
                protected void after() {
                    if (repository != null) {
                        repository.close();
                    }
                }
            })
            .around(context);

    private ResourceResolverFactoryActivator activator;
    private ResourceResolver resolver;

    @Before
    public void setUp() throws Exception {
        startResourceResolver();
    }

    @After
    public void tearDown() {
        if (resolver != null) {
            resolver.close();
        }
        // the activator is not a service, so OSGi Mock does not deactivate it on shutdown
        if (activator != null) {
            MockOsgi.deactivate(activator, context.bundleContext());
        }
    }

    @Test
    public void simpleNodeAndPropertyCreate() throws Exception {
        Session session = repository.getSession();
        Node root = session.getRootNode();
        Node test = root.addNode("test", "nt:unstructured");
        session.save();
        Resource resource = resolver.getResource(test.getPath());
        assertEquals(test.getPath(), resource.getPath());
        test.setProperty("foo", "bar");
        session.save();
        assertEquals("bar", resource.getValueMap().get("foo", String.class));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Register the JCR resource provider (on top of the Oak repository) and the
     * resource resolver factory, and wait for the factory to be available.
     */
    private void startResourceResolver() throws Exception {
        context.registerService(SlingRepository.class, repository.getSlingRepository());
        context.registerInjectActivateService(new JcrResourceProvider());
        context.registerInjectActivateService(new ServiceUserMapperImpl());
        context.registerInjectActivateService(new ResourceAccessSecurityTracker());
        context.registerInjectActivateService(new StringInterpolationProviderImpl());
        activator = context.registerInjectActivateService(new ResourceResolverFactoryActivator(), new HashMap<>());
        openResolver();
    }

    private void openResolver() throws InterruptedException, LoginException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        ServiceReference<ResourceResolverFactory> reference;

        while ((reference = context.bundleContext().getServiceReference(ResourceResolverFactory.class)) == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("resource resolver factory not (re-)registered");
            }
            Thread.sleep(50);
        }
        ResourceResolverFactory factory = context.bundleContext().getService(reference);
        assertNotNull(factory);
        resolver = factory.getServiceResourceResolver(null);
    }
}
