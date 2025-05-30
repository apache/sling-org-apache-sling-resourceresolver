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
package org.apache.sling.resourceresolver.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.Bundle;

/**
 * The <code>ResourceResolverFactoryImpl</code> is the {@link ResourceResolverFactory} service
 * providing the following
 * functionality:
 * <ul>
 * <li><code>ResourceResolverFactory</code> service
 * <li>Fires OSGi EventAdmin events on behalf of internal helper objects
 * </ul>
 *
 */
public class ResourceResolverFactoryImpl implements ResourceResolverFactory {

    private final CommonResourceResolverFactoryImpl commonFactory;

    private final ServiceUserMapper serviceUserMapper;

    private final Bundle usingBundle;

    public ResourceResolverFactoryImpl(
            final CommonResourceResolverFactoryImpl commonFactory,
            final Bundle usingBundle,
            final ServiceUserMapper serviceUserMapper) {
        this.commonFactory = commonFactory;
        this.serviceUserMapper = serviceUserMapper;
        this.usingBundle = usingBundle;
    }

    // ---------- Resource Resolver Factory ------------------------------------

    /**
     * @see org.apache.sling.api.resource.ResourceResolverFactory#getServiceResourceResolver(java.util.Map)
     */
    @Override
    public ResourceResolver getServiceResourceResolver(final Map<String, Object> passedAuthenticationInfo)
            throws LoginException {
        final Map<String, Object> authenticationInfo =
                CommonResourceResolverFactoryImpl.sanitizeAuthenticationInfo(passedAuthenticationInfo, PASSWORD);
        final Object info = authenticationInfo.get(SUBSERVICE);
        final String subServiceName = (info instanceof String) ? (String) info : null;

        // Ensure a mapped user or principal name(s): If no user/principal names is/are
        // defined for a bundle acting as a service, the user may be null. We can decide whether
        // this should yield guest access or no access at all. For now
        // no access is granted if there is no service user defined for
        // the bundle.
        final Iterable<String> principalNames =
                this.serviceUserMapper.getServicePrincipalNames(this.usingBundle, subServiceName);
        if (principalNames == null) {
            final String userName = this.serviceUserMapper.getServiceUserID(this.usingBundle, subServiceName);
            if (userName == null) {
                throw new LoginException("Cannot derive user name for bundle " + this.usingBundle + " and sub service "
                        + subServiceName);
            } else {
                // ensure proper user name
                authenticationInfo.put(ResourceResolverFactory.USER, userName);
            }
        }
        // ensure proper service bundle
        authenticationInfo.put(ResourceProvider.AUTH_SERVICE_BUNDLE, this.usingBundle);

        return commonFactory.getResourceResolverInternal(authenticationInfo, false);
    }

    /**
     * @see org.apache.sling.api.resource.ResourceResolverFactory#getResourceResolver(java.util.Map)
     */
    @Override
    public ResourceResolver getResourceResolver(final Map<String, Object> authenticationInfo) throws LoginException {
        return commonFactory.getResourceResolver(authenticationInfo);
    }

    /**
     * @see org.apache.sling.api.resource.ResourceResolverFactory#getAdministrativeResourceResolver(java.util.Map)
     */
    @Override
    public ResourceResolver getAdministrativeResourceResolver(Map<String, Object> authenticationInfo)
            throws LoginException {
        // usingBundle is required as bundles must now be allow listed to use this method
        if (usingBundle == null) {
            throw new LoginException("usingBundle is null");
        }
        if (authenticationInfo == null) {
            authenticationInfo = new HashMap<>();
        }
        authenticationInfo.put(ResourceProvider.AUTH_SERVICE_BUNDLE, this.usingBundle);
        return commonFactory.getAdministrativeResourceResolver(authenticationInfo);
    }

    /**
     * @see org.apache.sling.api.resource.ResourceResolverFactory#getThreadResourceResolver()
     */
    @Override
    public ResourceResolver getThreadResourceResolver() {
        return commonFactory.getThreadResourceResolver();
    }

    /**
     * @see org.apache.sling.api.resource.ResourceResolverFactory#getSearchPath()
     */
    @Override
    public List<String> getSearchPath() {
        return commonFactory.getSearchPath();
    }
}
