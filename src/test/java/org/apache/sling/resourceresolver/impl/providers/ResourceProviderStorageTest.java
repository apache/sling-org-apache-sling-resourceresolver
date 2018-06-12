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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.Test;
import org.osgi.framework.ServiceReference;

public class ResourceProviderStorageTest {

    @Test public void testAdaptableOrdering() throws Exception {
        final ServiceReference r1 = mock(ServiceReference.class);
        when(r1.getProperty(ResourceProvider.PROPERTY_ADAPTABLE)).thenReturn(Boolean.TRUE);
        final ServiceReference r2 = mock(ServiceReference.class);
        when(r2.getProperty(ResourceProvider.PROPERTY_ADAPTABLE)).thenReturn(Boolean.TRUE);
        final ServiceReference r3 = mock(ServiceReference.class);
        when(r3.getProperty(ResourceProvider.PROPERTY_ADAPTABLE)).thenReturn(Boolean.TRUE);
        when(r1.compareTo(r1)).thenReturn(0);
        when(r1.compareTo(r2)).thenReturn(-1);
        when(r1.compareTo(r3)).thenReturn(-1);
        when(r2.compareTo(r2)).thenReturn(0);
        when(r2.compareTo(r1)).thenReturn(1);
        when(r2.compareTo(r3)).thenReturn(-1);
        when(r3.compareTo(r3)).thenReturn(0);
        when(r3.compareTo(r1)).thenReturn(1);
        when(r3.compareTo(r2)).thenReturn(1);

        final ResourceProviderInfo i1 = new ResourceProviderInfo(r1);
        final ResourceProviderInfo i2 = new ResourceProviderInfo(r2);
        final ResourceProviderInfo i3 = new ResourceProviderInfo(r3);

        final List<ResourceProviderHandler> handlers = new ArrayList<>();
        // first in right order
        handlers.add(new ResourceProviderHandler(null, i3));
        handlers.add(new ResourceProviderHandler(null, i2));
        handlers.add(new ResourceProviderHandler(null, i1));

        final List<ResourceProviderHandler> correctOrder = new ArrayList<>(handlers);
        assertEquals(correctOrder, new ResourceProviderStorage(handlers).getAdaptableHandlers());

        // reverse order
        handlers.clear();
        handlers.add(correctOrder.get(2));
        handlers.add(correctOrder.get(1));
        handlers.add(correctOrder.get(0));
        assertEquals(correctOrder, new ResourceProviderStorage(handlers).getAdaptableHandlers());

        // arbitrary order
        handlers.clear();
        handlers.add(correctOrder.get(2));
        handlers.add(correctOrder.get(0));
        handlers.add(correctOrder.get(1));
        assertEquals(correctOrder, new ResourceProviderStorage(handlers).getAdaptableHandlers());
    }
}