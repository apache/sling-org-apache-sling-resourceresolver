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
package org.apache.sling.resourceresolver.impl.urimapping;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.uri.SlingUri;
import org.apache.sling.spi.urimapping.MappingChainContext;

class MappingChainContextInternal implements MappingChainContext {

    private final ResourceResolver resourceResolver;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, SlingUri> intermediateMappings = new LinkedHashMap<>();
    private boolean skipRemainingChain = false;


    MappingChainContextInternal(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @Override
    public void skipRemainingChain() {
        skipRemainingChain = true;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Map<String, SlingUri> getIntermediateMappings() {
        return Collections.unmodifiableMap(intermediateMappings);
    }

    @Override
    public ResourceResolver getResourceResolver() {
        return resourceResolver;
    }

    boolean isMarkedAsSkipRemainingChain() {
        return skipRemainingChain;
    }

    void addIntermediateMapping(String name, SlingUri resourceUri) {
        intermediateMappings.put(name, resourceUri);
    }


}