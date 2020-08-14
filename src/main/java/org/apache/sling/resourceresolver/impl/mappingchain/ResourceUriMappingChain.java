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

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.mapping.spi.ResourceToUriMapper;
import org.apache.sling.api.resource.uri.ResourceUri;
import org.apache.sling.api.resource.uri.ResourceUriBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = ResourceUriMappingChain.class)
public class ResourceUriMappingChain {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceUriMappingChain.class);

    private static final String KEY_INTIAL = "intial";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    volatile List<ResourceToUriMapper> resourceMappers;

    @Activate
    public void activate() {

        if (resourceMappers != null) {
            String resourceMappersStr = resourceMappers.stream().map(rm -> rm.getClass().toString()).collect(joining("\n"));
            LOG.info("Available Resource Mappers:\n{}", resourceMappersStr);
        } else {
            LOG.info("No Resource Mappers available");
        }
    }

    public List<String> getAvailableResourceMappers() {
        return resourceMappers.stream().map(rm -> {
            return rm.getClass().toString();
        }).collect(Collectors.toList());
    }

    @Nullable
    public MappingChainResult mapToUri(ResourceResolver resourceResolver, HttpServletRequest request, String resourcePath) {

        MappingChainContextInternal mappingContext = new MappingChainContextInternal(resourceResolver);
        ResourceUri resourceUri = ResourceUriBuilder.forPath(resourcePath).build();
        addMapping(mappingContext, resourceUri, KEY_INTIAL);
        if (resourceMappers != null) {
            for (ResourceToUriMapper mapper : resourceMappers) {
                String mapperName = mapper.getClass().getName();
                LOG.debug("Using {} for {}", mapperName, resourceUri);
                ResourceUri input = resourceUri;
                resourceUri = mapper.map(resourceUri, request, mappingContext);
                if (input != resourceUri) {
                    addMapping(mappingContext, resourceUri, mapperName);
                }

                if (chancelChain(mappingContext, mapperName)) {
                    break;
                }
            }
        }
        return new MappingChainResult(resourceUri, mappingContext.getIntermediateMappings());
    }

    @NotNull
    public MappingChainResult resolveToUri(ResourceResolver resourceResolver, HttpServletRequest request, String path) {

        MappingChainContextInternal mappingContext = new MappingChainContextInternal(resourceResolver);

        ResourceUri resourceUri = ResourceUriBuilder.parse(path).build();
        addMapping(mappingContext, resourceUri, KEY_INTIAL);
        if (resourceMappers != null) {
            for (ResourceToUriMapper mapper : reversedList(resourceMappers)) {
                String mapperName = mapper.getClass().getName();

                ResourceUri input = resourceUri;
                resourceUri = mapper.resolve(resourceUri, request, mappingContext);
                if (input != resourceUri) {
                    addMapping(mappingContext, resourceUri, mapperName);
                }

                if (chancelChain(mappingContext, mapperName)) {
                    break;
                }
            }
        }

        return new MappingChainResult(resourceUri, mappingContext.getIntermediateMappings());
    }

    private boolean chancelChain(MappingChainContextInternal mappingContext, String name) {
        if (mappingContext.isMarkedAsSkipRemainingChain()) {
            LOG.debug("ResourceUri[{}]: chain cancelled", name);
            return true;
        } else {
            return false;
        }
    }

    private void addMapping(MappingChainContextInternal mappingContext, ResourceUri resourceUri, String name) {
        mappingContext.addIntermediaMapping(name, resourceUri);
        LOG.debug("ResourceUri[{}]: {}", name, resourceUri);
    }

    private <T> List<T> reversedList(List<T> list) {
        List<T> reversedList = new ArrayList<>(list);
        Collections.reverse(reversedList);
        return reversedList;
    }
}
