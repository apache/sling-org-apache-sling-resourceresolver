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

import junit.framework.TestCase;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.resourceresolver.impl.SimpleValueMapImpl;
import org.apache.sling.resourceresolver.impl.helper.RedirectResource;
import org.apache.sling.resourceresolver.impl.mapping.MapEntry;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProviderConfiguration;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.BundleContext;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MockTestUtil {

    static final String PROP_SLING_TARGET = "sling:target";
    static final String PROP_SLING_STATUS = "sling:status";

    public static void checkRedirectResource(Resource redirect, String target, int status) {
        assertThat("Not a Redirect Resource", redirect, instanceOf(RedirectResource.class));
        RedirectResource redirectResource = (RedirectResource) redirect;
        ValueMap values = redirectResource.adaptTo(ValueMap.class);
        assertEquals("Redirect Target is wrong", target, values.get(PROP_SLING_TARGET, String.class));
        assertEquals("Redirect Status is wrong", new Integer(status), values.get(PROP_SLING_STATUS, Integer.class));
    }

    public static void checkNonExistingResource(Resource redirect, String path) {
        assertThat("Not a Non Existing Resource", redirect, instanceOf(NonExistingResource.class));
        NonExistingResource nonExistingResource = (NonExistingResource) redirect;
        if(path != null) {
            assertEquals("Wrong Path for Non Existing Resource", path, nonExistingResource.getPath());
        }
    }

    public static void checkInternalResource(Resource internal, String path) {
        assertEquals("Wrong Path for Resource", path, internal.getPath());
    }

    /**
     * Extract the name from a resource path
     *
     * @param fullPath Full / Aboslute path to the resource
     * @return Name of the resource
     */
    public static String getResourceName(String fullPath) {
        int n = fullPath.lastIndexOf("/");
        return fullPath.substring(n + 1);
    }

    /**
     * Creates a Mock Http Servlet Request
     * @param url Absolute URL to be used to get the method, host and port
     * @return Http Servlet Request if the url is valid otherwise null
     */
    public static HttpServletRequest createRequestFromUrl(String url) {
        int index = url.indexOf("://");
        if(index > 0) {
            String method = url.substring(0, index);
            int port = 80;
            int index2 = url.indexOf(":", index + 3);
            int index3 = url.indexOf("/", index2 > index ? index2 : index + 3);
            String host = "";
            if (index2 > 0) {
                port = new Integer(url.substring(index2 + 1, index3));
                host = url.substring(index + 3, index2);
            } else {
                if(index3 > 0) {
                    host = url.substring(index + 3, index3);
                } else {
                    host = url.substring(index + 3);
                }
            }
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getScheme()).thenReturn(method);
            when(request.getServerName()).thenReturn(host);
            when(request.getServerPort()).thenReturn(port);
            return request;
        }
        return null;
    }

    /**
     * Build a resource with path, parent, provider and resource resolver.
     *
     * @param fullPath         Full Path of the Resource
     * @param parent           Parent of this resource but it can be null
     * @param resourceResolver Resource Resolver of this resource
     * @param provider         Resource Provider Instance
     * @param properties       Key / Value pair for resource properties (the number of strings must be even)
     * @return Mock Resource able to handle addition of children later on
     */
    @SuppressWarnings("unchecked")
    public static Resource buildResource(String fullPath, Resource parent, ResourceResolver resourceResolver, ResourceProvider<?> provider, String... properties) {
        if (properties != null && properties.length % 2 != 0) {
            throw new IllegalArgumentException("List of Resource Properties must be an even number: " + asList(properties));
        }
        Resource resource = mock(Resource.class, withSettings().name(getResourceName(fullPath)).extraInterfaces(ResourceChildrenAccessor.class));
        when(resource.getName()).thenReturn(getResourceName(fullPath));
        when(resource.getPath()).thenReturn(fullPath);
        ResourceMetadata resourceMetadata = new ResourceMetadata();
        when(resource.getResourceMetadata()).thenReturn(resourceMetadata);
        when(resource.getResourceResolver()).thenReturn(resourceResolver);

        if (parent != null) {
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
        if (provider != null) {
            when(provider.listChildren(Mockito.any(ResolveContext.class), Mockito.eq(resource))).thenAnswer(new Answer<Iterator<Resource>>() {
                @Override
                public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                    return childrenList.iterator();
                }
            });
            when(provider.getResource(Mockito.any(ResolveContext.class), Mockito.eq(fullPath), Mockito.any(ResourceContext.class), Mockito.any(Resource.class))).thenReturn(resource);
        }
        if (properties != null) {
            ValueMap vm = new SimpleValueMapImpl();
            for (int i = 0; i < properties.length; i += 2) {
                resourceMetadata.put(properties[i], properties[i + 1]);
                vm.put(properties[i], properties[i + 1]);
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
     * Calls a private method that has no parameter like a getter
     *
     * @param methodName Name of the method
     * @param target Target instance
     * @return Object that is returned from the method call
     *
     * @throws UnsupportedOperationException If the call failed because it method is not found, has no access or invocation failed
     */
    public static <T> T callInaccessibleMethod(String methodName, Class<T> returnType, Object target) {
        return callInaccessibleMethod(methodName, returnType, target, new Class[] {}, new Object[] {});
    }

    /**
     * Calls a private method that has one parameter like a setter method
     *
     * @param methodName Name of the method
     * @param target Target instance
     * @param paramsType Parameter Type which cannot be null
     * @param param Parameter Value
     * @return Object that is returned from the method call
     *
     * @throws UnsupportedOperationException If the call failed because it method is not found, has no access or invocation failed
     */
    public static <T> T callInaccessibleMethod(String methodName, Class<T> returnType, Object target, Class paramsType, Object param) {
        return callInaccessibleMethod(methodName, returnType, target, new Class[] {paramsType}, new Object[] {param});
    }

    /**
     * Calls a private method that has none or one parameter like a setter method
     *
     * ATTENTION: If parameter types of values is null then both are set to null. Also the length of the arrays must
     * be the same
     *
     * @param methodName Name of the method
     * @param target Target instance
     * @param parameterTypes Parameter Types which must not be null
     * @param parameters Parameter Values which must not be null
     * @return Object that is returned from the method call
     *
     * @throws IllegalArgumentException If the parameter types and values do not match
     * @throws UnsupportedOperationException If the call failed because it method is not found, has no access or invocation failed
     */
    public static <T> T callInaccessibleMethod(String methodName, Class<T> returnType, Object target, Class[] parameterTypes, Object[] parameters) {
        if(parameterTypes != null && parameters != null) {
            if(parameters.length != parameterTypes.length) { throw new IllegalArgumentException("Number of Parameter Types and Values were not the same"); }
        } else {
            throw new IllegalArgumentException("Parameter Type and Value Array cannot be null");
        }
        try {
            return getInaccessibleMethod(methodName, returnType, target, parameterTypes).call(parameters);
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException("Failed to access method: " + methodName, e);
        } catch (InvocationTargetException e) {
            throw new UnsupportedOperationException("Failed to invoke method: " + methodName, e);
        }
    }

    public static <T> MethodWrapper<T> getInaccessibleMethod(String methodName, Class<T> returnType, Object target, Class...parameterTypes) {
        return new MethodWrapper(methodName, returnType, target, parameterTypes);
    }

    public static class MethodWrapper<T> {
        private Method method;
        private Object target;

        public MethodWrapper(String methodName, Class<T> returnType, Object target, Class[] parameterTypes) {
            try {
                this.method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
                this.method.setAccessible(true);
                this.target = target;
                if(returnType == null && !this.method.getReturnType().equals(Void.TYPE)) {
                    throw new IllegalArgumentException("Return Type is null but method does not return Void but: " + this.method.getReturnType());
                }
                if(returnType != null && !returnType.isAssignableFrom(this.method.getReturnType())) {
                    throw new IllegalArgumentException("Return Type is not assignable to: " + returnType + ", it returns this: " + this.method.getReturnType());
                }
            } catch (NoSuchMethodException e) {
                throw new UnsupportedOperationException("Failed to find method: " + methodName, e);
            }
        }

        public T call(Object...parameters) throws InvocationTargetException, IllegalAccessException {
            return (T) method.invoke(target, parameters);
        }
    }

    /**
     * Sets the value of a private field
     *
     * @param fieldName Name of the field to be set
     * @param target Target instance
     * @param fieldValue Value to be set
     *
     * @throws UnsupportedOperationException If the call failed because it field is not found or has no access
     */
    public static void setInaccessibleField(String fieldName, Object target, Object fieldValue) throws NoSuchMethodException {
        try {
            getInaccessibleFieldWrapper(fieldName, target, Object.class).set(fieldValue);
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException("Failed to access field: " + fieldName, e);
        }
    }

    public static <T> T getInaccessibleField(String fieldName, Object target, Class<T> type) {
        try {
            return getInaccessibleFieldWrapper(fieldName, target, type).get();
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException("Failed to access field: " + fieldName, e);
        }
    }

    public static <T> FieldWrapper<T> getInaccessibleFieldWrapper(String fieldName, Object target, Class<T> type) {
        try {
            return new FieldWrapper(fieldName, target, type);
        } catch (NoSuchFieldException e) {
            throw new UnsupportedOperationException("Failed to find field: " + fieldName, e);
        }
    }

    public static StringInterpolationProviderConfiguration createStringInterpolationProviderConfiguration() {
        StringInterpolationProviderConfiguration answer = mock(StringInterpolationProviderConfiguration.class);
        when(answer.placeHolderKeyValuePairs()).thenReturn(new String[] {});
        return answer;
    }

    public static void setupStringInterpolationProvider(
        StringInterpolationProvider provider, StringInterpolationProviderConfiguration configuration, final String[] placeholderValues
    ) {
        when(configuration.placeHolderKeyValuePairs()).thenReturn(placeholderValues);
        BundleContext context = mock(BundleContext.class);
        callInaccessibleMethod("activate", Void.TYPE, provider,
            new Class[] {BundleContext.class, StringInterpolationProviderConfiguration.class},
            new Object[] {context, configuration}
        );
    }

    public static class FieldWrapper<T> {
        private Field field;
        private Object target;

        public FieldWrapper(String fieldName, Object target, Class<T> type) throws NoSuchFieldException {
            this.field = target.getClass().getDeclaredField(fieldName);
            this.field.setAccessible(true);
            this.target = target;
        }

        public void set(T parameter) throws IllegalAccessException {
            field.set(target, parameter);
        }

        public T get() throws IllegalAccessException {
            return (T) field.get(target);
        }
    }

    /**
     * Iterator to piggyback the list of Resources onto a Resource Mock
     * so that we can add children to them and create the iterators after
     * everything is setup
     */
    static interface ResourceChildrenAccessor {
        public List<Resource> getChildrenList();
    }

    /**
     * Defines the Result of the Etc Mapping for easy testing
     */
    public static class ExpectedEtcMapping {
        List<ExpectedEtcMapEntry> expectedEtcMapEntries = new ArrayList<>();

        public ExpectedEtcMapping() {}

        public ExpectedEtcMapping(String...expectedMapping) {
            if(expectedMapping.length % 2 != 0) {
                throw new IllegalArgumentException("Expect an even number of strings with pattern / redirect");
            }
            int size = expectedMapping.length / 2;
            for(int i = 0; i < size; i++ ) {
                expectedEtcMapEntries.add(new ExpectedEtcMapEntry(expectedMapping[2 * i], expectedMapping[2 * i + 1]));
            }
        }

        public ExpectedEtcMapping addEtcMapEntry(String pattern, String redirect) {
            addEtcMapEntry(pattern, false, redirect);
            return this;
        }
        public ExpectedEtcMapping addEtcMapEntry(String pattern, boolean internal, String redirect) {
            expectedEtcMapEntries.add(new ExpectedEtcMapEntry(pattern, internal, redirect));
            return this;
        }

        public void assertEtcMap(String title, List<MapEntry> mapEntries) {
            assertEquals("Wrong Number of Mappings for: " + title, expectedEtcMapEntries.size(), mapEntries.size());
            ArrayList<MapEntry> actual = new ArrayList<>(mapEntries);
            ArrayList<ExpectedEtcMapEntry> expected = new ArrayList<>(expectedEtcMapEntries);
            for(MapEntry actualMapEntry: actual) {
                ExpectedEtcMapEntry expectedFound = null;
                for(ExpectedEtcMapEntry expectedEtcMapEntry: expected) {
                    if(expectedEtcMapEntry.pattern.equals(actualMapEntry.getPattern())) {
                        expectedFound = expectedEtcMapEntry;
                        break;
                    }
                }
                if(expectedFound == null) {
                    TestCase.fail("This pattern (" + actualMapEntry.getPattern() + ") is not expected for: " + title);
                }
                expectedFound.assertEtcMap(title, actualMapEntry);
                expected.remove(expectedFound);
            }
            for(ExpectedEtcMapEntry expectedEtcMapEntry: expected) {
                TestCase.fail("Expected Map Entry (" + expectedEtcMapEntry.pattern + ") not provided for: " + title);
            }
        }
    }

    public static class ExpectedEtcMapEntry {
        private String pattern;
        private boolean internal;
        private String redirect;

        public ExpectedEtcMapEntry(String pattern, String redirect) {
            this(pattern, false, redirect);
        }

        public ExpectedEtcMapEntry(String pattern, boolean internal, String redirect) {
            this.pattern = pattern;
            this.internal = internal;
            this.redirect = redirect;
        }

        public void assertEtcMap(String title, MapEntry mapEntry) {
            assertEquals("Wrong Pattern for " + title, pattern, mapEntry.getPattern());
            List<String> givenRedirects = new ArrayList<>(Arrays.asList(mapEntry.getRedirect()));
            assertEquals("Wrong Number of Redirects for: " + title, 1, givenRedirects.size());
            assertEquals("Wrong Redirect for: " + title, this.redirect, givenRedirects.get(0));
            assertEquals("Wrong Redirect Type (ext/int) for: " + title, this.internal, mapEntry.isInternal());
        }
    }
}
