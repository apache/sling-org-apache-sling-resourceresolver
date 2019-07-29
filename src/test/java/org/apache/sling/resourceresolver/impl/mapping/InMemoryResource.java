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

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.AbstractResource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

public class InMemoryResource extends AbstractResource {
        
        private final String path;
        private final ResourceMetadata metadata;
        private final ResourceResolver resolver;
        private final Map<String, Object> properties = new HashMap<>();

        public InMemoryResource(String path, ResourceResolver resolver, Map<String, Object> properties) {
            
            if ( path == null )
                throw new IllegalArgumentException("path is null");
            
            if ( resolver == null )
                throw new IllegalArgumentException("resovler is null");
            
            this.path = path;
            this.metadata = new ResourceMetadata();
            this.metadata.setResolutionPath(path);;
            this.resolver = resolver;
            this.properties.putAll(properties);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getResourceType() {
            return getValueMap().get("sling:resourceType", String.class);
        }

        @Override
        public String getResourceSuperType() {
            return getValueMap().get("sling:resourceSuperType", String.class);
        }

        @Override
        public ResourceMetadata getResourceMetadata() {
            return metadata;
        }

        @Override
        public ResourceResolver getResourceResolver() {
            return resolver;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if(type == ValueMap.class || type == Map.class) {
                return (AdapterType) new ValueMapDecorator(properties);
            }
            return super.adaptTo(type);
        }
        
        public InMemoryResource set(String prop, Object val) {
            properties.put(prop, val);
            
            return this;
        }
        
        @Override
        public String toString() {
            return getClass().getSimpleName() + " : [ path = " + path + " ]";
        }
}