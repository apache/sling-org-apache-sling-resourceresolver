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
import javax.jcr.RepositoryException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.osgi.framework.ServiceReference;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Alias cache initialization with an asynchronous index that does not yet reflect
 * the latest alias changes (which is the normal state of an async index).
 * <p>
 * The aliases are read with a query that is sorted, and paged, by the first alias
 * of a node, {@code FIRST([sling:alias])}. The index sorts by the indexed (old)
 * aliases, while the start of the next page is taken from the current alias of the
 * last row read. When those differ, aliases are skipped, or the same page is read
 * again and again.
 */
@Ignore("SLING-13387: Paged query for aliases fails due to stale alias index and shifting sling:alias values")
public class AliasStaleIndexOakTest {

    /** the page size of the alias query */
    private static final int PAGE_SIZE = 2000;

    private static final int NODE_COUNT = PAGE_SIZE + 1000;

    private static final String TEST_ROOT = "/content/test";

    private static final String ALIAS_INIT_COMPLETED = "alias initialization - completed";

    private static final long ALIAS_INIT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);

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

    private final Logger aliasHandlerLogger = (Logger) LoggerFactory.getLogger(AliasHandler.class);

    private final ListAppender<ILoggingEvent> aliasHandlerLog = new ListAppender<>();

    private ResourceResolverFactoryActivator activator;

    private ResourceResolver resolver;

    @After
    public void tearDown() {
        if (resolver != null) {
            resolver.close();
        }
        // the activator is not a service, so OSGi Mock does not deactivate it on shutdown;
        // this also closes the resource resolver of an alias initialization that is still running
        if (activator != null) {
            MockOsgi.deactivate(activator, context.bundleContext());
        }
        aliasHandlerLogger.detachAppender(aliasHandlerLog);
        aliasHandlerLog.stop();
    }

    /**
     * The alias of the node that starts the second page is changed to a value that
     * sorts later: the second page starts at that value, so the aliases of the
     * nodes in between are not loaded.
     */
    @Test
    public void aliasesAreLoadedWhenAnAliasWasChangedAfterIndexing() throws Exception {
        Map<Integer, String[]> changes = Map.of(PAGE_SIZE, new String[] {"zzz-changed"});

        createIndexedAliasesAndChange(false, changes);
        startResourceResolver();

        assertAliasInitializationCompleted();
        assertAllAliasesResolve(false, changes);
    }

    /**
     * Only the order of the aliases of a node is changed (no alias is added or
     * removed): as the first alias is used for paging, this has the same effect.
     */
    @Test
    public void multiValuedAliasesAreLoadedWhenTheirOrderWasChangedAfterIndexing() throws Exception {
        Map<Integer, String[]> changes = Map.of(PAGE_SIZE, new String[] {secondAlias(PAGE_SIZE), alias(PAGE_SIZE)});

        createIndexedAliasesAndChange(true, changes);
        startResourceResolver();

        assertAliasInitializationCompleted();
        assertAllAliasesResolve(true, changes);
    }

    /**
     * The alias of the node that starts the second page is changed to a value that
     * sorts earlier: the second page starts at that value, which returns the same
     * rows again, so the alias initialization never completes.
     */
    @Test
    public void aliasInitializationCompletesWhenAnAliasWasChangedToAnEarlierValueAfterIndexing() throws Exception {
        Map<Integer, String[]> changes = Map.of(PAGE_SIZE, new String[] {"0-changed"});

        createIndexedAliasesAndChange(false, changes);
        startResourceResolver();

        assertAliasInitializationCompleted();
        assertAllAliasesResolve(false, changes);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Create {@link #NODE_COUNT} nodes with aliases and an alias index, let the index
     * catch up, and then change the given aliases, so that the index is stale.
     */
    private void createIndexedAliasesAndChange(boolean multiValued, Map<Integer, String[]> changes)
            throws RepositoryException {
        repository.createAsyncLuceneIndex("slingAlias", "sling:alias", "first([sling:alias])");
        Node root = repository
                .getSession()
                .getRootNode()
                .addNode("content", "nt:unstructured")
                .addNode("test", "nt:unstructured");
        for (int i = 0; i < NODE_COUNT; i++) {
            root.addNode(nodeName(i), "nt:unstructured").setProperty("sling:alias", aliases(i, multiValued));
        }
        repository.getSession().save();
        repository.runAsyncIndexing();

        for (Map.Entry<Integer, String[]> change : changes.entrySet()) {
            repository.getSession().getNode(nodePath(change.getKey())).setProperty("sling:alias", change.getValue());
        }
        repository.getSession().save();
    }

    /**
     * Start the resource resolver factory with the default configuration (aliases
     * are cached, and the cache is initialized in the background).
     */
    private void startResourceResolver() throws Exception {
        aliasHandlerLog.start();
        aliasHandlerLogger.addAppender(aliasHandlerLog);

        Map<String, Object> config = new HashMap<>();
        // no "/content/:/" mapping, so resolve() works on the full path
        config.put("resource.resolver.mapping", new String[] {"/:/"});

        context.registerService(SlingRepository.class, repository.getSlingRepository());
        context.registerInjectActivateService(new JcrResourceProvider());
        context.registerInjectActivateService(new ServiceUserMapperImpl());
        context.registerInjectActivateService(new ResourceAccessSecurityTracker());
        context.registerInjectActivateService(new StringInterpolationProviderImpl());
        activator = context.registerInjectActivateService(new ResourceResolverFactoryActivator(), config);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        ServiceReference<ResourceResolverFactory> reference;
        while ((reference = context.bundleContext().getServiceReference(ResourceResolverFactory.class)) == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("resource resolver factory not registered");
            }
            Thread.sleep(50);
        }
        ResourceResolverFactory factory = context.bundleContext().getService(reference);
        assertNotNull(factory);
        resolver = factory.getServiceResourceResolver(null);
    }

    /**
     * Wait for the (background) alias initialization; until it is completed, aliases
     * are looked up in the repository, so they resolve even if the cache misses them.
     */
    private void assertAliasInitializationCompleted() throws InterruptedException {
        long deadline = System.currentTimeMillis() + ALIAS_INIT_TIMEOUT_MS;
        while (!isAliasInitializationCompleted()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("alias initialization not completed within " + ALIAS_INIT_TIMEOUT_MS + " ms");
            }
            Thread.sleep(50);
        }
    }

    private boolean isAliasInitializationCompleted() {
        return new ArrayList<>(aliasHandlerLog.list)
                .stream().anyMatch(event -> event.getFormattedMessage().startsWith(ALIAS_INIT_COMPLETED));
    }

    private void assertAllAliasesResolve(boolean multiValued, Map<Integer, String[]> changes) {
        List<String> unresolved = new ArrayList<>();
        for (int i = 0; i < NODE_COUNT; i++) {
            for (String alias : changes.getOrDefault(i, aliases(i, multiValued))) {
                if (!nodePath(i)
                        .equals(resolver.resolve(TEST_ROOT + "/" + alias).getPath())) {
                    unresolved.add(nodeName(i) + ":" + alias);
                }
            }
        }
        assertTrue(
                unresolved.size() + " aliases not loaded, starting with "
                        + unresolved.subList(0, Math.min(5, unresolved.size())),
                unresolved.isEmpty());
    }

    private static String[] aliases(int index, boolean multiValued) {
        return multiValued ? new String[] {alias(index), secondAlias(index)} : new String[] {alias(index)};
    }

    private static String nodeName(int index) {
        return String.format("n%05d", index);
    }

    private static String nodePath(int index) {
        return TEST_ROOT + "/" + nodeName(index);
    }

    private static String alias(int index) {
        return String.format("a%05d", index);
    }

    /** the second alias of a node; sorts after all first aliases */
    private static String secondAlias(int index) {
        return String.format("x%05d", index);
    }
}
