/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.resourceresolver.util;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.resourceresolver.impl.SimpleValueMapImpl;
import org.apache.sling.resourceresolver.impl.mapping.AbstractMappingMapEntriesTest;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static java.util.Arrays.asList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MockTestUtil {

    /**
     * Extract the name from a resource path
     * @param fullPath Full / Aboslute path to the resource
     * @return Name of the resource
     */
    public static String getResourceName(String fullPath) {
        int n = fullPath.lastIndexOf("/");
        return fullPath.substring(n+1);
    }

    /**
     * Build a resource with path, parent, provider and resource resolver.
     * @param fullPath Full Path of the Resource
     * @param parent Parent of this resource but it can be null
     * @param resourceResolver Resource Resolver of this resource
     * @param provider Resource Provider Instance
     * @param properties Key / Value pair for resource properties (the number of strings must be even)
     * @return
     */
    @SuppressWarnings("unchecked")
    private Resource buildResource(String fullPath, Resource parent, ResourceResolver resourceResolver, ResourceProvider<?> provider, String ... properties) {
        if(properties != null && properties.length % 2 != 0) { throw new IllegalArgumentException("List of Resource Properties must be an even number: " + asList(properties)); }
        Resource resource = mock(Resource.class, withSettings().name(getResourceName(fullPath)).extraInterfaces(ResourceChildrenAccessor.class));
        when(resource.getName()).thenReturn(getResourceName(fullPath));
        when(resource.getPath()).thenReturn(fullPath);
        ResourceMetadata resourceMetadata = new ResourceMetadata();
        when(resource.getResourceMetadata()).thenReturn(resourceMetadata);
        when(resource.getResourceResolver()).thenReturn(resourceResolver);

        if(parent != null) {
            List<Resource> childList = ((ResourceChildrenAccessor) parent).getChildrenList();
            childList.add(resource);
        }
        final List<Resource> childrenList = new ArrayList<>();
        when(((ResourceChildrenAccessor) resource).getChildrenList()).thenReturn(childrenList);
        // Delay the children list iterator to make sure all children are added beforehand
        // Iterators have a modCount that is set when created. Any changes to the underlying list will
        // change that modCount and the usage of the iterator will fail due to Concurrent Modification Exception
        when(resource.listChildren()).thenAnswer(new Answer<Iterator<Resource>>() {
            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                return childrenList.iterator();
            }
        });

        // register the resource with the provider
        if ( provider != null ) {
            when(provider.listChildren(Mockito.any(ResolveContext.class), Mockito.eq(resource))).thenAnswer(new Answer<Iterator<Resource>>() {
                @Override
                public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                    return childrenList.iterator();
                }
            });
            when(provider.getResource(Mockito.any(ResolveContext.class), Mockito.eq(fullPath), Mockito.any(ResourceContext.class), Mockito.any(Resource.class))).thenReturn(resource);
        }
        if ( properties != null ) {
            ValueMap vm = new SimpleValueMapImpl();
            for ( int i=0; i < properties.length; i+=2) {
                resourceMetadata.put(properties[i], properties[i+1]);
                vm.put(properties[i], properties[i+1]);
            }
            when(resource.getValueMap()).thenReturn(vm);
            when(resource.adaptTo(Mockito.eq(ValueMap.class))).thenReturn(vm);
        } else {
            when(resource.getValueMap()).thenReturn(ValueMapDecorator.EMPTY);
            when(resource.adaptTo(Mockito.eq(ValueMap.class))).thenReturn(ValueMapDecorator.EMPTY);
        }

        return resource;
    }

    /**
     * Iterator to piggyback the list of Resources onto a Resource Mock
     * so that we can add children to them and create the iterators after
     * everything is setup
     */
    static interface ResourceChildrenAccessor {
        public List<Resource> getChildrenList();
    }

}
