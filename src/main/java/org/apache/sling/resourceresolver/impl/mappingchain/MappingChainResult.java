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

import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.mapping.PathToUriMappingService;
import org.apache.sling.api.resource.mapping.PathToUriMappingService.ContextHint;
import org.apache.sling.api.resource.uri.ResourceUri;
import org.jetbrains.annotations.NotNull;

class MappingChainResult implements PathToUriMappingService.Result {

    private final ResourceUri resourceUri;
    private final Set<ContextHint> contextHints;
    private final Map<String, ResourceUri> intermediateMappings;

    public MappingChainResult(ResourceUri resourceUri, Set<ContextHint> contextHints, Map<String, ResourceUri> intermediateMappings) {
        this.resourceUri = resourceUri;
        this.contextHints = contextHints;
        this.intermediateMappings = intermediateMappings;
    }

    public ResourceUri getResourceUri() {
        return resourceUri;
    }

    public Map<String, ResourceUri> getIntermediateMappings() {
        return intermediateMappings;
    }

    @Override
    public @NotNull Set<ContextHint> getContextHints() {
        return contextHints;
    }
}