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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FactoryPreconditionsTest {

    @Test
    public void testNoRequiredProviders() {
        final ResourceProviderTracker tracker = Mockito.mock(ResourceProviderTracker.class);
        final ResourceProviderStorage storage = Mockito.mock(ResourceProviderStorage.class);
        Mockito.when(tracker.getResourceProviderStorage()).thenReturn(storage);

        FactoryPreconditions conditions = new FactoryPreconditions(tracker, null, null);

        assertTrue(conditions.checkPreconditions());

        conditions = new FactoryPreconditions(tracker, Collections.<String>emptySet(), Collections.<String>emptySet());

        assertTrue(conditions.checkPreconditions());
    }

    private List<ResourceProviderHandler> getResourceProviderHandlers(String[] pids) {
        final List<ResourceProviderHandler> result = new ArrayList<ResourceProviderHandler>();

        for (final String p : pids) {
            final ResourceProviderHandler handler = Mockito.mock(ResourceProviderHandler.class);
            final ResourceProviderInfo info = Mockito.mock(ResourceProviderInfo.class);
            final ServiceReference ref = Mockito.mock(ServiceReference.class);

            Mockito.when(handler.getInfo()).thenReturn(info);
            Mockito.when(info.getServiceReference()).thenReturn(ref);
            Mockito.when(ref.getProperty(Constants.SERVICE_PID)).thenReturn(p);

            result.add(handler);
        }
        return result;
    }

    private List<ResourceProviderHandler> getResourceProviderHandlersWithNames(String[] names) {
        final List<ResourceProviderHandler> result = new ArrayList<ResourceProviderHandler>();

        for (final String n : names) {
            final ResourceProviderHandler handler = Mockito.mock(ResourceProviderHandler.class);
            final ResourceProviderInfo info = Mockito.mock(ResourceProviderInfo.class);
            Mockito.when(info.getName()).thenReturn(n);
            final ServiceReference ref = Mockito.mock(ServiceReference.class);

            Mockito.when(handler.getInfo()).thenReturn(info);
            Mockito.when(info.getServiceReference()).thenReturn(ref);

            result.add(handler);
        }
        return result;
    }

    @Test
    public void testPIDs() {
        final ResourceProviderTracker tracker = Mockito.mock(ResourceProviderTracker.class);
        final ResourceProviderStorage storage = Mockito.mock(ResourceProviderStorage.class);
        Mockito.when(tracker.getResourceProviderStorage()).thenReturn(storage);

        FactoryPreconditions conditions =
                new FactoryPreconditions(tracker, null, new HashSet<>(Arrays.asList("pid1", "pid3")));

        final List<ResourceProviderHandler> handlers1 = getResourceProviderHandlers(new String[] {"pid2"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers1);
        assertFalse(conditions.checkPreconditions());

        final List<ResourceProviderHandler> handlers2 =
                getResourceProviderHandlers(new String[] {"pid1", "pid2", "pid3"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers2);
        assertTrue(conditions.checkPreconditions());

        final List<ResourceProviderHandler> handlers3 = getResourceProviderHandlers(new String[] {"pid1"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers3);
        assertFalse(conditions.checkPreconditions());
    }

    @Test
    public void testNames() {
        final ResourceProviderTracker tracker = Mockito.mock(ResourceProviderTracker.class);
        final ResourceProviderStorage storage = Mockito.mock(ResourceProviderStorage.class);
        Mockito.when(tracker.getResourceProviderStorage()).thenReturn(storage);

        FactoryPreconditions conditions =
                new FactoryPreconditions(tracker, new HashSet<>(Arrays.asList("n1", "n2")), null);

        final List<ResourceProviderHandler> handlers1 = getResourceProviderHandlersWithNames(new String[] {"n2"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers1);
        assertFalse(conditions.checkPreconditions());

        final List<ResourceProviderHandler> handlers2 =
                getResourceProviderHandlersWithNames(new String[] {"n1", "n2", "n3"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers2);
        assertTrue(conditions.checkPreconditions());

        final List<ResourceProviderHandler> handlers3 = getResourceProviderHandlersWithNames(new String[] {"n1"});
        Mockito.when(storage.getAllHandlers()).thenReturn(handlers3);
        assertFalse(conditions.checkPreconditions());
    }
}
