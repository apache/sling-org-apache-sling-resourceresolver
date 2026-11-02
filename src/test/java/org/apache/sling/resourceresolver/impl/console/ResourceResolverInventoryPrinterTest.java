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
package org.apache.sling.resourceresolver.impl.console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.apache.felix.inventory.Format;
import org.apache.felix.inventory.InventoryPrinter;
import org.apache.felix.utils.json.JSONParser;
import org.apache.sling.api.resource.runtime.RuntimeService;
import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.api.resource.runtime.dto.FailureReason;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderDTO;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderFailureDTO;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.CommonResourceResolverFactoryImpl;
import org.apache.sling.resourceresolver.impl.mapping.MapEntriesHandler;
import org.apache.sling.resourceresolver.impl.mapping.MapEntry;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceResolverInventoryPrinterTest {

    private final BundleContext context = mock(BundleContext.class);
    private final CommonResourceResolverFactoryImpl factory = mock(CommonResourceResolverFactoryImpl.class);
    private final RuntimeService runtime = mock(RuntimeService.class);
    private final MapEntriesHandler entries = mock(MapEntriesHandler.class);
    private final RuntimeDTO dto = new RuntimeDTO();
    private ResourceResolverInventoryPrinter printer;

    @BeforeEach
    void setUp() {
        dto.providers = new ResourceProviderDTO[0];
        dto.failedProviders = new ResourceProviderFailureDTO[0];
        when(runtime.getRuntimeDTO()).thenReturn(dto);
        when(factory.getSearchPath()).thenReturn(List.of("/apps/", "/libs/"));
        when(factory.getMapEntries()).thenReturn(entries);
        when(entries.getResolveMaps()).thenReturn(List.of());
        when(entries.getMapMaps()).thenReturn(List.of());
        printer = new ResourceResolverInventoryPrinter(context, factory, runtime);
    }

    @Test
    void exportsTypedProvidersAndFailuresWithoutAcquiringServices() throws Exception {
        ResourceProviderDTO provider = new ResourceProviderDTO();
        provider.name = "A \"provider\"\nwith brackets []";
        provider.path = "/";
        provider.serviceId = 42;
        provider.authType = AuthType.required;
        provider.modifiable = true;
        provider.supportsQueryLanguage = true;
        dto.providers = new ResourceProviderDTO[] {provider};
        ResourceProviderFailureDTO failure = new ResourceProviderFailureDTO();
        failure.serviceId = 43;
        failure.reason = FailureReason.shadowed;
        dto.failedProviders = new ResourceProviderFailureDTO[] {failure};
        ServiceReference<?> reference = mock(ServiceReference.class);
        Bundle bundle = mock(Bundle.class);
        when(reference.getProperty(Constants.SERVICE_ID)).thenReturn(42L);
        when(reference.getBundle()).thenReturn(bundle);
        when(bundle.getBundleId()).thenReturn(9L);
        when(context.getAllServiceReferences(ResourceProvider.class.getName(), null))
                .thenReturn(new ServiceReference<?>[] {reference});

        Map<String, Object> json = read();
        Map<?, ?> active = (Map<?, ?>) ((List<?>) json.get("resourceProviders")).get(0);
        assertEquals(provider.name, active.get("name"));
        assertEquals(42L, ((Number) active.get("serviceId")).longValue());
        assertEquals(9L, ((Number) active.get("bundleId")).longValue());
        assertEquals("required", active.get("authType"));
        assertEquals(true, active.get("modifiable"));
        assertEquals(false, active.get("adaptable"));
        assertEquals(true, active.get("supportsQueryLanguage"));
        Map<?, ?> failed = (Map<?, ?>) ((List<?>) json.get("failedResourceProviders")).get(0);
        assertNull(failed.get("name"));
        assertTrue(failed.containsKey("path"));
        assertNull(failed.get("path"));
        assertNull(failed.get("bundleId"));
        assertEquals("shadowed", failed.get("reason"));
        verify(context, never()).getService(any());
    }

    @Test
    void preservesMappingOrderDuplicatePatternsAndExactReplacementStrings() {
        String pattern = "^[^/]+/[^/]+/aem/(.*)$";
        String[] replacements = {"/content/with, commas/[brackets]/$1", "/quote\"/newline\n/$1"};
        MapEntry internal = entry(pattern, replacements, true, -1);
        MapEntry external = entry(pattern, new String[] {"https://example.org/$1"}, false, 302);
        when(entries.getResolveMaps()).thenReturn(List.of(internal, external));
        when(entries.getMapMaps()).thenReturn(List.of(external, internal));

        Map<String, Object> json = read();
        List<?> resolve = (List<?>) json.get("resolverMapEntries");
        List<?> map = (List<?>) json.get("mappingMapEntries");
        assertEquals(2, resolve.size());
        assertEquals(pattern, ((Map<?, ?>) resolve.get(0)).get("pattern"));
        assertEquals(pattern, ((Map<?, ?>) resolve.get(1)).get("pattern"));
        assertEquals(List.of(replacements), ((Map<?, ?>) resolve.get(0)).get("replacements"));
        assertEquals(true, ((Map<?, ?>) resolve.get(0)).get("internal"));
        assertNull(((Map<?, ?>) resolve.get(0)).get("status"));
        assertEquals(false, ((Map<?, ?>) resolve.get(1)).get("internal"));
        assertEquals(302, ((Number) ((Map<?, ?>) resolve.get(1)).get("status")).intValue());
        assertEquals(resolve.get(1), map.get(0));
        assertEquals(resolve.get(0), map.get(1));
    }

    @Test
    void exportsEmptyCollectionsAndSearchPaths() {
        Map<String, Object> json = read();
        assertEquals(List.of("/apps/", "/libs/"), json.get("searchPath"));
        assertEquals(List.of(), json.get("resourceProviders"));
        assertEquals(List.of(), json.get("failedResourceProviders"));
        assertEquals(List.of(), json.get("resolverMapEntries"));
        assertEquals(List.of(), json.get("mappingMapEntries"));
    }

    @Test
    void registersAJsonOnlyCompanionWithoutReplacingTheLegacyTextPrinter() {
        ResourceResolverInventoryPrinter.register(context, factory, runtime);
        verify(context)
                .registerService(
                        eq(InventoryPrinter.class),
                        isA(ResourceResolverInventoryPrinter.class),
                        argThat(properties -> "sling-resourceresolver".equals(properties.get(InventoryPrinter.NAME))
                                && "JSON".equals(properties.get(InventoryPrinter.FORMAT))));
        StringWriter output = new StringWriter();
        printer.print(new PrintWriter(output), Format.TEXT, false);
        assertEquals("", output.toString());
        verifyNoInteractions(runtime);
    }

    private MapEntry entry(String pattern, String[] replacements, boolean internal, int status) {
        MapEntry entry = mock(MapEntry.class);
        when(entry.getPattern()).thenReturn(pattern);
        when(entry.getRedirect()).thenReturn(replacements);
        when(entry.isInternal()).thenReturn(internal);
        when(entry.getStatus()).thenReturn(status);
        return entry;
    }

    private Map<String, Object> read() {
        StringWriter output = new StringWriter();
        printer.print(new PrintWriter(output), Format.JSON, false);
        return new JSONParser(output.toString()).getParsed();
    }
}
