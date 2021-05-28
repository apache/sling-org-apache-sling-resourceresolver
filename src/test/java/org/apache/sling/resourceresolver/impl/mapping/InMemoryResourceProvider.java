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

package org.apache.sling.resourceresolver.impl.mapping;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.spi.resource.provider.QueryLanguageProvider;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;

@Component(service = ResourceProvider.class)
public class InMemoryResourceProvider extends ResourceProvider<Void>{
    
    private final Map<String, Map<String, Object>> resources = new HashMap<>();

    @Override
    public Resource getResource(ResolveContext<Void> ctx, String path, ResourceContext resourceContext,
            Resource parent) {
        
        Map<String, Object> vals = resources.get(path);
        if ( vals == null )
            return null;
        
        return new InMemoryResource(path, ctx.getResourceResolver(), vals);
            
    }

    @Override
    public Iterator<Resource> listChildren(ResolveContext<Void> ctx, Resource parent) {

        return resources.entrySet().stream()
            .filter( e -> parent.getPath().equals(ResourceUtil.getParent(e.getKey())) )
            .map( e -> (Resource) new InMemoryResource(e.getKey(), ctx.getResourceResolver(), e.getValue()) )
            .iterator();
    }
    
    public void putResource(String path) {
        putResource(path, Collections.emptyMap());
    }

    public void putResource(String path, String key, Object value) {
        putResource(path, Collections.singletonMap(key, value));
    }

    public void putResource(String path, String key, Object... values) {
        putResource(path, Collections.singletonMap(key, values));
    }

    public void putResource(String path, String key, Object value, String key2, Object value2) {
        Map<String, Object> props = new HashMap<>();
        props.put(key, value);
        props.put(key2, value2);
        putResource(path, props);
    }
    
    public void putResource(String path, Map<String, Object> props) {
        resources.put(path, props);
    }
    
    @Override
    public QueryLanguageProvider<Void> getQueryLanguageProvider() {
        return new QueryLanguageProvider<Void>() {

            @Override
            public String[] getSupportedLanguages(@NotNull ResolveContext<Void> ctx) {
                return new String[] { "sql" };
            }

            @Override
            public Iterator<Resource> findResources(@NotNull ResolveContext<Void> ctx, String query, String language) {
                
                // we don't explicitly filter paths under jcr:system, but we don't expect to have such resources either
                // and this stub provider is not the proper location to test JCR queries
                if  ( "SELECT sling:alias FROM nt:base AS page WHERE (NOT ISDESCENDANTNODE(page,\"/jcr:system\")) AND sling:alias IS NOT NULL".equals(query) ) {
                    return resourcesWithProperty(ctx, "sling:alias")
                        .iterator();
                }
                
                if ( "SELECT sling:vanityPath, sling:redirect, sling:redirectStatus FROM nt:base WHERE sling:vanityPath IS NOT NULL".equals(query) ) {
                    return resourcesWithProperty(ctx, "sling:vanityPath")
                        .iterator();                  
                }

                throw new UnsupportedOperationException("Unsupported query: '" + query + "'");
            }

            private List<Resource> resourcesWithProperty(ResolveContext<Void> ctx, String propertyName) {
                return resources.entrySet().stream()
                    .filter( e -> e.getValue().containsKey(propertyName) )
                    .map( e -> getResource(ctx, e.getKey(), null, null))
                    .collect(Collectors.toList());
            }

            @Override
            public Iterator<ValueMap> queryResources(@NotNull ResolveContext<Void> ctx, String query, String language) {
                throw new UnsupportedOperationException("stub");
            }
        };
    }
}