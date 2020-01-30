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
package org.apache.sling.resourceresolver.impl.providers;

import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.resource.path.PathSet;
import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.Fixture;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker.ChangeListener;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker.ObservationReporterGenerator;
import org.apache.sling.spi.resource.provider.ObservationReporter;
import org.apache.sling.spi.resource.provider.ObserverConfiguration;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.event.EventAdmin;

public class ResourceProviderTrackerTest {

    @Rule
    public OsgiContext context = new OsgiContext();

    private EventAdmin eventAdmin;
    private ResourceProviderInfo rp2Info;
    private Fixture fixture;

    @Before
    public void prepare() throws Exception {
        eventAdmin = context.getService(EventAdmin.class);
        fixture = new Fixture(context.bundleContext());
    }

    private ResourceProviderTracker registerDefaultResourceProviderTracker() throws Exception {
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp = mock(ResourceProvider.class);
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp2 = mock(ResourceProvider.class);
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp3 = mock(ResourceProvider.class);

        fixture.registerResourceProvider(rp, "/", AuthType.no);
        rp2Info = fixture.registerResourceProvider(rp2, "/path", AuthType.lazy);
        fixture.registerResourceProvider(rp3, "invalid", AuthType.no);

        ResourceProviderTracker tracker = new ResourceProviderTracker();
        tracker.setObservationReporterGenerator(new SimpleObservationReporterGenerator(new DoNothingObservationReporter()));
        tracker.activate(context.bundleContext(), eventAdmin, new DoNothingChangeListener());
        return tracker;
    }

    @Test
    public void activate() throws Exception {
        ResourceProviderTracker tracker = registerDefaultResourceProviderTracker();

        // since the OSGi mocks are asynchronous we don't have to wait for the changes to propagate

        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(2));

        fixture.unregisterResourceProvider(rp2Info);

        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(1));
    }

    @Test
    public void deactivate() throws Exception {
        ResourceProviderTracker tracker = registerDefaultResourceProviderTracker();

        tracker.deactivate();

        assertThat(tracker.getResourceProviderStorage().getAllHandlers(), hasSize(0));
    }

    @Test
    public void testActivationDeactivation() throws Exception {
        final ResourceProviderTracker tracker = new ResourceProviderTracker();
        tracker.setObservationReporterGenerator(new SimpleObservationReporterGenerator(new DoNothingObservationReporter()));

        // create boolean markers for the listener
        final AtomicBoolean addedCalled = new AtomicBoolean(false);
        final AtomicBoolean removedCalled = new AtomicBoolean(false);

        final ChangeListener listener = new ChangeListener() {

            @Override
            public void providerAdded() {
                addedCalled.set(true);
            }

            @Override
            public void providerRemoved(String name, String pid, boolean stateful, boolean used) {
                removedCalled.set(true);
            }

        };
        // activate and check that no listener is called yet
        tracker.activate(context.bundleContext(), eventAdmin, listener);
        assertFalse(addedCalled.get());
        assertFalse(removedCalled.get());

        // add a new resource provider
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp = mock(ResourceProvider.class);
        final ResourceProviderInfo info = fixture.registerResourceProvider(rp, "/", AuthType.no);

        // check added is called but not removed
        assertTrue(addedCalled.get());
        assertFalse(removedCalled.get());

        // verify a single provider
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(1));

        // reset boolean markers
        addedCalled.set(false);
        removedCalled.set(false);

        // remove provider
        fixture.unregisterResourceProvider(info);

        // verify removed is called but not added
        assertTrue(removedCalled.get());
        assertFalse(addedCalled.get());

        // no provider anymore
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(0));
    }

    @Test
    public void testReactivation() throws Exception {
        final ResourceProviderTracker tracker = new ResourceProviderTracker();
        tracker.setObservationReporterGenerator(new SimpleObservationReporterGenerator(new DoNothingObservationReporter()));

        // create boolean markers for the listener
        final AtomicBoolean addedCalled = new AtomicBoolean(false);
        final AtomicBoolean removedCalled = new AtomicBoolean(false);

        final ChangeListener listener = new ChangeListener() {

            @Override
            public void providerAdded() {
                addedCalled.set(true);
            }

            @Override
            public void providerRemoved(String name, String pid, boolean stateful, boolean used) {
                removedCalled.set(true);
            }

        };
        // activate and check that no listener is called yet
        tracker.activate(context.bundleContext(), eventAdmin, listener);
        assertFalse(addedCalled.get());
        assertFalse(removedCalled.get());

        // activate and check that no listener is called yet
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp = mock(ResourceProvider.class);
        final ResourceProviderInfo info = fixture.registerResourceProvider(rp, "/", AuthType.no);

        // check added is called but not removed
        assertTrue(addedCalled.get());
        assertFalse(removedCalled.get());

        // verify a single provider
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(1));

        // reset boolean markers
        addedCalled.set(false);
        removedCalled.set(false);

        // add overlay provider with higher service ranking
        @SuppressWarnings("unchecked")
        ResourceProvider<Object> rp2 = mock(ResourceProvider.class);
        final ResourceProviderInfo infoOverlay = fixture.registerResourceProvider(rp2, "/", AuthType.no, 1000);

        // check added and removed is called
        assertTrue(addedCalled.get());
        assertTrue(removedCalled.get());

        // verify a single provider
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(1));

        // reset boolean markers
        addedCalled.set(false);
        removedCalled.set(false);

        // unregister overlay provider
        fixture.unregisterResourceProvider(infoOverlay);

        // check added and removed is called
        assertTrue(addedCalled.get());
        assertTrue(removedCalled.get());

        // verify a single provider
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(1));

        // reset boolean markers
        addedCalled.set(false);
        removedCalled.set(false);

        // unregister first provider
        fixture.unregisterResourceProvider(info);

        // check removed is called but not added
        assertTrue(removedCalled.get());
        assertFalse(addedCalled.get());

        // verify no provider
        assertThat(tracker.getResourceProviderStorage().getAllHandlers().size(), equalTo(0));
    }

    /**
     * This test verifies that shadowing of Resource observation is deterministic when ResourceProviders get registered and unregistered,
     * meaning it is independent of the order in which those events happen.
     * <p>
     * It does so by
     * 1) registering a ResourceProvider A on a deeper path then root (shadowing root)
     * 2) registering a ResourceProvider B on root
     * 3) unregistering the ResourceProvider A
     * 4) and registering the ResoucreProvider A
     * <p>
     * This guarantees in both cases (A before B and B before A) the same excludes are applied in the ObservationReporter.
     *
     * @throws InvalidSyntaxException
     */
    @Test
    public void testDeterministicObservationShadowing() throws InvalidSyntaxException {
        final ResourceProviderTracker tracker = new ResourceProviderTracker();
        final Map<String, List<String>> excludeSets = new HashMap<>();

        tracker.activate(context.bundleContext(), eventAdmin, null);
        tracker.setObservationReporterGenerator(new SimpleObservationReporterGenerator(new DoNothingObservationReporter()) {
            @Override
            public ObservationReporter create(Path path, PathSet excludes) {
                List<String> excludeSetsPerPath = excludeSets.get(path.getPath());
                if (excludeSetsPerPath == null) {
                    excludeSetsPerPath = new ArrayList<>(1);
                    excludeSets.put(path.getPath(), excludeSetsPerPath);
                }

                excludeSetsPerPath.clear();
                for (Path exclude : excludes) {
                    excludeSetsPerPath.add(exclude.getPath());
                }

                return super.create(path, excludes);
            }
        });

        ResourceProvider<?> rp = mock(ResourceProvider.class);
        ResourceProviderInfo info;
        // register RP on /path, empty exclude set expected
        info = fixture.registerResourceProvider(rp, "/path", AuthType.no);
        assertNull(excludeSets.get("/"));
        assertThat(excludeSets.get("/path"), hasSize(0));
        // register RP on /, expect /path excluded
        fixture.registerResourceProvider(rp, "/", AuthType.no);
        assertThat(excludeSets.get("/"), hasSize(1));
        assertThat(excludeSets.get("/"), contains("/path"));
        assertThat(excludeSets.get("/path"), hasSize(0));
        // unregister RP on /path,  empty exclude set expected
        fixture.unregisterResourceProvider(info);
        assertThat(excludeSets.get("/"), hasSize(0));
        assertThat(excludeSets.get("/path"), hasSize(0));
        // register RP on /path again, expect /path excluded
        fixture.registerResourceProvider(rp, "/path", AuthType.no);
        assertThat(excludeSets.get("/"), hasSize(1));
        assertThat(excludeSets.get("/"), contains("/path"));
        assertThat(excludeSets.get("/path"), hasSize(0));
    }

    @Test
    public void testUpdateOnlyOnIntersectingProviders() throws InvalidSyntaxException {
        final ResourceProviderTracker tracker = new ResourceProviderTracker();

        tracker.activate(context.bundleContext(), eventAdmin, null);
        tracker.setObservationReporterGenerator(new SimpleObservationReporterGenerator(new DoNothingObservationReporter()));

        ResourceProvider<?> rootRp = mock(ResourceProvider.class);
        ResourceProvider<?> fooRp = mock(ResourceProvider.class);
        ResourceProvider<?> barRp = mock(ResourceProvider.class);
        ResourceProvider<?> foobarRp = mock(ResourceProvider.class);

        ResourceProviderInfo info;

        // register RPs and verify how often update() gets called
        fixture.registerResourceProvider(rootRp, "/", AuthType.no);
        verify(rootRp, never()).update(anyLong());
        fixture.registerResourceProvider(fooRp, "/foo", AuthType.no);
        verify(rootRp, times(1)).update(anyLong());
        verify(fooRp, never()).update(anyLong());
        info = fixture.registerResourceProvider(barRp, "/bar", AuthType.no);
        verify(rootRp, times(2)).update(anyLong());
        verify(fooRp, never()).update(anyLong());
        verify(barRp, never()).update(anyLong());
        fixture.unregisterResourceProvider(info);
        verify(rootRp, times(3)).update(anyLong());
        verify(fooRp, never()).update(anyLong());
        verify(barRp, never()).update(anyLong());
        fixture.registerResourceProvider(foobarRp, "/foo/bar", AuthType.no);
        verify(rootRp, times(4)).update(anyLong());
        verify(fooRp, times(1)).update(anyLong());
        verify(barRp, never()).update(anyLong());
        verify(foobarRp, never()).update(anyLong());
    }

    @Test
    public void fillDto() throws Exception {
        ResourceProviderTracker tracker = registerDefaultResourceProviderTracker();

        RuntimeDTO dto = new RuntimeDTO();

        tracker.fill(dto);

        assertThat( dto.providers, arrayWithSize(2));
        assertThat( dto.failedProviders, arrayWithSize(1));
    }

    static class DoNothingObservationReporter implements ObservationReporter {
        @Override
        public void reportChanges(Iterable<ResourceChange> changes, boolean distribute) {
        }

        @Override
        public void reportChanges(ObserverConfiguration config, Iterable<ResourceChange> changes, boolean distribute) {
        }

        @Override
        public List<ObserverConfiguration> getObserverConfigurations() {
            return Collections.emptyList();
        }
    }

    static class SimpleObservationReporterGenerator implements ObservationReporterGenerator {
        private final ObservationReporter reporter;

        SimpleObservationReporterGenerator(ObservationReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public ObservationReporter createProviderReporter() {
            return reporter;
        }

        @Override
        public ObservationReporter create(Path path, PathSet excludes) {
            return reporter;
        }
    }

    static final class DoNothingChangeListener implements ChangeListener {

        @Override
        public void providerAdded() {
            // TODO Auto-generated method stub

        }

        @Override
        public void providerRemoved(String name, String pid, boolean stateful, boolean used) {
            // TODO Auto-generated method stub

        }
    }
}