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

import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ResourceProviderInfoTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void testValidInfo() {
        final ServiceReference<ResourceProvider> ref = Mockito.mock(ServiceReference.class);
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_ROOT)).thenReturn("/test");

        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        assertEquals("/test", info.getPath());
        assertNull(info.getName());
        assertEquals(ResourceProviderInfo.Mode.OVERLAY, info.getMode());
        assertEquals(AuthType.no, info.getAuthType());
        assertTrue(info.isValid());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void testValidAuthInfo() {
        final ServiceReference<ResourceProvider> ref = Mockito.mock(ServiceReference.class);
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_ROOT)).thenReturn("/test");
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_AUTHENTICATE)).thenReturn(AuthType.lazy.name());

        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        assertEquals("/test", info.getPath());
        assertNull(info.getName());
        assertEquals(ResourceProviderInfo.Mode.OVERLAY, info.getMode());
        assertEquals(AuthType.lazy, info.getAuthType());
        assertTrue(info.isValid());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void testInvalidAuthInfo() {
        final ServiceReference<ResourceProvider> ref = Mockito.mock(ServiceReference.class);
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_ROOT)).thenReturn("/test");
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_AUTHENTICATE)).thenReturn("hello");

        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        assertEquals("/test", info.getPath());
        assertNull(info.getName());
        assertEquals(ResourceProviderInfo.Mode.OVERLAY, info.getMode());
        assertNull(info.getAuthType());
        assertFalse(info.isValid());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void testValidMode() {
        final ServiceReference<ResourceProvider> ref = Mockito.mock(ServiceReference.class);
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_ROOT)).thenReturn("/test");
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_MODE))
                .thenReturn(ResourceProviderInfo.Mode.PASSTHROUGH.name().toLowerCase());

        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        assertEquals("/test", info.getPath());
        assertNull(info.getName());
        assertEquals(ResourceProviderInfo.Mode.PASSTHROUGH, info.getMode());
        assertEquals(AuthType.no, info.getAuthType());
        assertTrue(info.isValid());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    public void testInvalidMode() {
        final ServiceReference<ResourceProvider> ref = Mockito.mock(ServiceReference.class);
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_ROOT)).thenReturn("/test");
        Mockito.when(ref.getProperty(ResourceProvider.PROPERTY_MODE)).thenReturn("hello");

        final ResourceProviderInfo info = new ResourceProviderInfo(ref);
        assertEquals("/test", info.getPath());
        assertNull(info.getName());
        assertNull(info.getMode());
        assertEquals(AuthType.no, info.getAuthType());
        assertFalse(info.isValid());
    }
}
