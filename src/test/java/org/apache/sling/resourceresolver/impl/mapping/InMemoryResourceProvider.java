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
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.service.component.annotations.Component;

@Component(service = ResourceProvider.class)
public class InMemoryResourceProvider extends ResourceProvider<Void> {
    
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

    public void putResource(String path, String key, Object value, String key2, Object value2) {
        Map<String, Object> props = new HashMap<>();
        props.put(key, value);
        props.put(key2, value2);
        putResource(path, props);
    }
    
    public void putResource(String path, Map<String, Object> props) {
        resources.put(path, props);
    }
    
}