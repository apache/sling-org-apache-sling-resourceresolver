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
package org.apache.sling.resourceresolver.impl.mappingchain;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.mapping.spi.MappingChainContext;
import org.apache.sling.api.resource.uri.ResourceUri;

class MappingChainContextInternal implements MappingChainContext {

    private final ResourceResolver resourceResolver;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, ResourceUri> intermediateMappings = new LinkedHashMap<>();
    private boolean skipRemainingChain = false;

    MappingChainContextInternal(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @Override
    public ResourceResolver getResourceResolver() {
        return resourceResolver;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public void skipRemainingChain() {
        skipRemainingChain = true;
    }

    @Override
    public Map<String, ResourceUri> getIntermediateMappings() {
        return Collections.unmodifiableMap(intermediateMappings);
    }

    boolean isMarkedAsSkipRemainingChain() {
        return skipRemainingChain;
    }

    void addIntermediaMapping(String name, ResourceUri resourceUri) {
        intermediateMappings.put(name, resourceUri);
    }

}