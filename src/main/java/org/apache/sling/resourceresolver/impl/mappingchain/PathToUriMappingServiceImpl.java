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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.mapping.PathToUriMappingService;
import org.apache.sling.api.resource.uri.ResourceUri;
import org.apache.sling.api.resource.uri.ResourceUriBuilder;
import org.apache.sling.spi.resource.mapping.ResourceUriMapper;
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

@Component
public class PathToUriMappingServiceImpl implements PathToUriMappingService {

    private static final Logger LOG = LoggerFactory.getLogger(PathToUriMappingServiceImpl.class);

    private static final String KEY_INTIAL = "intial";
    private static final String SUBSERVICE_NAME_MAPPING = "mapping";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    volatile List<ResourceUriMapper> resourceMappers;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    volatile ResourceResolverFactory resourceResolverFactory;

    @Activate
    public void activate() {

        if (resourceMappers != null) {
            String resourceMappersStr = resourceMappers.stream().map(rm -> rm.getClass().toString()).collect(Collectors.joining("\n"));
            LOG.info("Available Resource Mappers:\n{}", resourceMappersStr);
        } else {
            LOG.info("No Resource Mappers available");
        }
    }

    public List<String> getAvailableResourceMappers() {
        return resourceMappers.stream().map(rm -> rm.getClass().toString()).collect(Collectors.toList());
    }

    @NotNull
    public MappingChainResult map(@Nullable HttpServletRequest request, @NotNull String resourcePath) {

        try (ResourceResolver rr = getResourceResolver()) {
            MappingChainContextInternal mappingContext = new MappingChainContextInternal(rr);
            ResourceUri resourceUri = ResourceUriBuilder.forPath(resourcePath).build();
            addIntermediateMapping(mappingContext, resourceUri, KEY_INTIAL);
            if (resourceMappers != null) {
                for (ResourceUriMapper mapper : resourceMappers) {
                    String mapperName = mapper.getClass().getName();
                    LOG.trace("map(): Using {} for {}", mapperName, resourceUri);
                    ResourceUri input = resourceUri;
                    resourceUri = mapper.map(resourceUri, request, mappingContext);
                    if (input != resourceUri) {
                        addIntermediateMapping(mappingContext, resourceUri, mapperName);
                    }

                    if (chancelChain(mappingContext, mapperName)) {
                        break;
                    }
                }
            }

            if (LOG.isDebugEnabled()) {
                logResult(mappingContext, "map()");
            }

            return new MappingChainResult(resourceUri, mappingContext.getContextHints(), mappingContext.getIntermediateMappings());
        }
    }

    @NotNull
    public MappingChainResult resolve(@Nullable HttpServletRequest request, @NotNull String path) {

        try (ResourceResolver rr = getResourceResolver()) {
            MappingChainContextInternal mappingContext = new MappingChainContextInternal(rr);

            ResourceUri resourceUri = ResourceUriBuilder.parse(path, rr).build();
            addIntermediateMapping(mappingContext, resourceUri, KEY_INTIAL);
            if (resourceMappers != null) {
                for (ResourceUriMapper mapper : reversedList(resourceMappers)) {
                    String mapperName = mapper.getClass().getName();
                    LOG.trace("resolve(): Using {} for {}", mapperName, resourceUri);
                    ResourceUri input = resourceUri;
                    resourceUri = mapper.resolve(resourceUri, request, mappingContext);
                    if (input != resourceUri) {
                        addIntermediateMapping(mappingContext, resourceUri, mapperName);
                    }

                    if (chancelChain(mappingContext, mapperName)) {
                        break;
                    }
                }
            }

            if (LOG.isDebugEnabled()) {
                logResult(mappingContext, "resolve()");
            }

            return new MappingChainResult(resourceUri, mappingContext.getContextHints(), mappingContext.getIntermediateMappings());
        }

    }


    private boolean chancelChain(MappingChainContextInternal mappingContext, String name) {
        if (mappingContext.isMarkedAsSkipRemainingChain()) {
            LOG.debug("{} cancelled remaining chain", name);
            return true;
        } else {
            return false;
        }
    }

    private void addIntermediateMapping(MappingChainContextInternal mappingContext, ResourceUri resourceUri, String name) {
        mappingContext.addIntermediateMapping(name, resourceUri);
        LOG.trace("{} ajusted ResourceUri: {}", name, resourceUri);
    }

    private <T> List<T> reversedList(List<T> list) {
        List<T> reversedList = new ArrayList<>(list);
        Collections.reverse(reversedList);
        return reversedList;
    }

    private void logResult(MappingChainContextInternal mappingContext, String method) {
        Map<String, ResourceUri> intermediateMappings = mappingContext.getIntermediateMappings();
        if (intermediateMappings.size() == 1) {
            LOG.debug("\n{}:\nUNCHANGED {}", method, intermediateMappings.values().iterator().next());
        } else {
            Set<ContextHint> contextHints = mappingContext.getContextHints();
            LOG.debug("\n{}:\n{}{}", method,
                    intermediateMappings.entrySet().stream()
                            .map(e -> StringUtils.leftPad(e.getKey().replaceAll("([a-z])[^.]*\\.(?=.*\\..*$)", "$1."), 50) + " -> "
                                    + e.getValue())
                            .collect(Collectors.joining("\n")),
                    !contextHints.isEmpty()
                            ? ("\nHints: " + contextHints.stream().map(ContextHint::getName).collect(Collectors.joining(",")))
                            : "");
        }
    }

    protected ResourceResolver getResourceResolver() {

        try {
            final Map<String, Object> authenticationInfo = new HashMap<>();
            authenticationInfo.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME_MAPPING);
            ResourceResolver serviceResourceResolver = resourceResolverFactory.getServiceResourceResolver(authenticationInfo);
            return serviceResourceResolver;
        } catch (LoginException e) {
            throw new IllegalStateException("Cannot create ResourceResolver: " + e, e);
        }
    }

}
