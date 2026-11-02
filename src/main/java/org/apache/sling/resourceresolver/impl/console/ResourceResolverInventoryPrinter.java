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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.felix.inventory.Format;
import org.apache.felix.inventory.InventoryPrinter;
import org.apache.sling.api.resource.runtime.RuntimeService;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderDTO;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderFailureDTO;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.CommonResourceResolverFactoryImpl;
import org.apache.sling.resourceresolver.impl.mapping.MapEntriesHandler;
import org.apache.sling.resourceresolver.impl.mapping.MapEntry;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * Structured companion to the existing jcrresolver text inventory.
 */
public final class ResourceResolverInventoryPrinter implements InventoryPrinter {

    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final BundleContext context;
    private final CommonResourceResolverFactoryImpl factory;
    private final RuntimeService runtime;

    ResourceResolverInventoryPrinter(
            BundleContext context, CommonResourceResolverFactoryImpl factory, RuntimeService runtime) {
        this.context = context;
        this.factory = factory;
        this.runtime = runtime;
    }

    public static ServiceRegistration<InventoryPrinter> register(
            BundleContext context, CommonResourceResolverFactoryImpl factory, RuntimeService runtime) {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(InventoryPrinter.NAME, "sling-resourceresolver");
        properties.put(InventoryPrinter.TITLE, "Sling Resource Resolver");
        properties.put(InventoryPrinter.FORMAT, Format.JSON.toString());
        return context.registerService(
                InventoryPrinter.class, new ResourceResolverInventoryPrinter(context, factory, runtime), properties);
    }

    @Override
    public void print(PrintWriter writer, Format format, boolean isZip) {
        if (format != Format.JSON) {
            return;
        }
        RuntimeDTO dto = runtime.getRuntimeDTO();
        Map<Long, Long> bundleIds = bundleIds();
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ResourceProviderDTO provider : dto.providers) {
            providers.add(provider(provider, bundleIds));
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (ResourceProviderFailureDTO provider : dto.failedProviders) {
            Map<String, Object> failure = provider(provider, bundleIds);
            failure.put("reason", provider.reason == null ? null : provider.reason.name());
            failures.add(failure);
        }
        MapEntriesHandler entries = factory.getMapEntries();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("searchPath", factory.getSearchPath());
        result.put("resourceProviders", providers);
        result.put("failedResourceProviders", failures);
        result.put("resolverMapEntries", mappings(entries.getResolveMaps()));
        result.put("mappingMapEntries", mappings(entries.getMapMaps()));
        writer.print(JSON.toJson(result));
    }

    private Map<Long, Long> bundleIds() {
        Map<Long, Long> result = new HashMap<>();
        try {
            ServiceReference<?>[] references = context.getAllServiceReferences(ResourceProvider.class.getName(), null);
            if (references != null) {
                for (ServiceReference<?> reference : references) {
                    Bundle bundle = reference.getBundle();
                    if (bundle != null) {
                        result.put((Long) reference.getProperty(Constants.SERVICE_ID), bundle.getBundleId());
                    }
                }
            }
        } catch (InvalidSyntaxException e) {
            throw new IllegalStateException("Unable to enumerate resource providers", e);
        }
        return result;
    }

    private Map<String, Object> provider(ResourceProviderDTO dto, Map<Long, Long> bundleIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", dto.name);
        result.put("path", dto.path);
        result.put("serviceId", dto.serviceId);
        result.put("bundleId", bundleIds.get(dto.serviceId));
        result.put("authType", dto.authType == null ? null : dto.authType.name());
        result.put("adaptable", dto.adaptable);
        result.put("attributable", dto.attributable);
        result.put("modifiable", dto.modifiable);
        result.put("refreshable", dto.refreshable);
        result.put("supportsQueryLanguage", dto.supportsQueryLanguage);
        result.put("useResourceAccessSecurity", dto.useResourceAccessSecurity);
        return result;
    }

    private List<Map<String, Object>> mappings(Collection<MapEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MapEntry entry : entries) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("pattern", entry.getPattern());
            mapping.put("replacements", entry.getRedirect());
            mapping.put("internal", entry.isInternal());
            mapping.put("status", entry.isInternal() ? null : entry.getStatus());
            result.add(mapping);
        }
        return result;
    }
}
