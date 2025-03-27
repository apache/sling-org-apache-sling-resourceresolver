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

import org.apache.sling.resourceresolver.impl.providers.tree.Pathable;
import org.apache.sling.spi.resource.provider.ProviderContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;

/**
 * Holder for a resource provider service.
 */
public class ResourceProviderHandler implements Comparable<ResourceProviderHandler>, Pathable {

    /** The provider context. */
    private final ProviderContextImpl context = new ProviderContextImpl();

    /** The resource provider info. */
    private final ResourceProviderInfo info;

    /** The resource provider. */
    private final ResourceProvider<Object> provider;

    /** Flag to indicate whether the service has been used. */
    private volatile boolean isUsed = false;

    /**
     * Create a new handler
     * @param info Resource provider info.
     * @param provider The resource provider
     */
    public ResourceProviderHandler(final ResourceProviderInfo info, final ResourceProvider<Object> provider) {
        this.info = info;
        this.provider = provider;
    }

    /**
     * Get the resource provider info
     * @return The resource provider info or {@code null} if this handler has been deactivated.
     */
    public ResourceProviderInfo getInfo() {
        return this.info;
    }

    /**
     * Start the resource provder
     */
    public void activate() {
        this.provider.start(context);
        this.isUsed = false;
    }

    /**
     * Deactivate this handler.
     * Unget the provider service.
     */
    public void deactivate() {
        this.provider.stop();
        this.context.update(null, null);
        this.isUsed = false;
    }

    /**
     * Get the resource provider.
     * @return The resource provider or {@code null} if it is not active.
     */
    public ResourceProvider<Object> getResourceProvider() {
        return this.provider;
    }

    /**
     * Get the resource provider and mark it as used.
     * @return The resource provider or {@code null} if it is not active.
     */
    public ResourceProvider<Object> useResourceProvider() {
        this.isUsed = true;
        return this.provider;
    }

    /**
     * Check whether this provider has been used.
     * @return {@code true} if used
     */
    public boolean isUsed() {
        return this.isUsed;
    }

    @Override
    public int compareTo(final ResourceProviderHandler o) {
        final ResourceProviderInfo localInfo = this.info;
        final ResourceProviderInfo otherInfo = o.info;
        if (localInfo == null) {
            if (otherInfo == null) {
                return 0;
            }
            return 1;
        }
        if (otherInfo == null) {
            return -1;
        }
        return localInfo.compareTo(otherInfo);
    }

    /**
     * Get the path where the provider is mounted.
     * @return The mount path.
     */
    @Override
    public String getPath() {
        return this.getInfo().getPath();
    }

    /**
     * Update the provider
     */
    public void update() {
        this.provider.update(ProviderContext.EXCLUDED_PATHS_CHANGED + ProviderContext.OBSERVATION_LISTENER_CHANGED);
    }

    /**
     * Get the provider context.
     * @return The provider context
     */
    public ProviderContextImpl getProviderContext() {
        return this.context;
    }

    @Override
    public String toString() {
        return "[" + getClass().getSimpleName() + "# provider: " + provider + " ]";
    }
}
