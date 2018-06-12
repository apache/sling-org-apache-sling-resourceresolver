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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.resourceresolver.impl.providers.tree.PathTree;
import org.apache.sling.spi.resource.provider.ResourceProvider;

/**
 * The resource provider storage contains all available handlers
 * and keeps a list of handlers for specific categories to avoid
 * iterating over all handlers for the different use cases.
 */
public class ResourceProviderStorage {

    private final List<ResourceProviderHandler> allHandlers;

    private final List<ResourceProviderHandler> authRequiredHandlers;

    private final List<ResourceProviderHandler> adaptableHandlers;

    private final List<ResourceProviderHandler> attributableHandlers;

    private final List<ResourceProviderHandler> languageQueryableHandlers;

    private final PathTree<ResourceProviderHandler> handlersTree;

    public ResourceProviderStorage(List<ResourceProviderHandler> handlers) {
        this.allHandlers = handlers;
        this.authRequiredHandlers = new ArrayList<>();
        this.adaptableHandlers = new ArrayList<>();
        this.attributableHandlers = new ArrayList<>();
        this.languageQueryableHandlers = new ArrayList<>();
        for (ResourceProviderHandler h : allHandlers) {
            ResourceProviderInfo info = h.getInfo();
            if (info.getAuthType() == AuthType.required) {
                this.authRequiredHandlers.add(h);
            }
            if (info.isAdaptable()) {
                this.adaptableHandlers.add(h);
            }
            if (info.isAttributable()) {
                this.attributableHandlers.add(h);
            }
            final ResourceProvider<Object> rp = h.getResourceProvider();
            if (rp != null && rp.getQueryLanguageProvider() != null) {
                this.languageQueryableHandlers.add(h);
            }
        }
        Collections.sort(this.adaptableHandlers, new Comparator<ResourceProviderHandler>() {

            @Override
            public int compare(final ResourceProviderHandler o1, final ResourceProviderHandler o2) {
                final ResourceProviderInfo i1 = o1.getInfo();
                final ResourceProviderInfo i2 = o2.getInfo();
                if ( i1 == null ) {
                    return i2 == null ? 0 : -1;
                }
                if ( i2 == null ) {
                    return 1;
                }
                return i2.getServiceReference().compareTo(i1.getServiceReference());
            }
        });
        this.handlersTree = new PathTree<>(handlers);
    }

    public List<ResourceProviderHandler> getAllHandlers() {
        return allHandlers;
    }

    public List<ResourceProviderHandler> getAuthRequiredHandlers() {
        return authRequiredHandlers;
    }

    public List<ResourceProviderHandler> getAdaptableHandlers() {
        return adaptableHandlers;
    }

    public List<ResourceProviderHandler> getAttributableHandlers() {
        return attributableHandlers;
    }

    public List<ResourceProviderHandler> getLanguageQueryableHandlers() {
        return languageQueryableHandlers;
    }

    public PathTree<ResourceProviderHandler> getTree() {
        return handlersTree;
    }
}
