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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.resourceresolver.impl.legacy.LegacyResourceProviderWhiteboard;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class which checks whether all conditions for registering
 * the resource resolver factory are fulfilled.
 */
public class FactoryPreconditions {

    private static final Logger LOG = LoggerFactory.getLogger(FactoryPreconditions.class);

    private static final class RequiredProvider {
        public String name;
        public String pid;
        public Filter filter;
    };

    private final ResourceProviderTracker tracker;

    private final List<RequiredProvider> requiredProviders;

    public FactoryPreconditions(
            final ResourceProviderTracker tracker,
            final Set<String> requiredResourceProviderNames,
            final Set<String> requiredResourceProvidersLegacy) {
        this.tracker = tracker;
        this.requiredProviders = initRequiredProviders(requiredResourceProviderNames, requiredResourceProvidersLegacy);
    }

    @NotNull
    private List<RequiredProvider> initRequiredProviders(
            Set<String> requiredResourceProviderNames, Set<String> requiredResourceProvidersLegacy) {

        boolean hasLegacyRequiredProvider = false;
        if ( requiredResourceProvidersLegacy != null ) {
            hasLegacyRequiredProvider = requiredResourceProvidersLegacy.remove(ResourceResolverFactoryConfig.LEGACY_REQUIRED_PROVIDER_PID);
            if ( !requiredResourceProvidersLegacy.isEmpty() ) {
                LOG.error("ResourceResolverFactory is using deprecated required providers configuration " +
                        "(resource.resolver.required.providers). Please change to use the property " +
                        "resource.resolver.required.providernames for values: {}", requiredResourceProvidersLegacy);
            } else {
                requiredResourceProvidersLegacy = null;
            }
        }

        if ( hasLegacyRequiredProvider ) {
            if (requiredResourceProviderNames == null) {
                requiredResourceProviderNames = new HashSet<>();
            }

            // add default if not already present
            final boolean hasRequiredProvider = !requiredResourceProviderNames.add(ResourceResolverFactoryConfig.REQUIRED_PROVIDER_NAME);
            if ( hasRequiredProvider ) {
                LOG.warn("ResourceResolverFactory is using deprecated required providers configuration " +
                        "(resource.resolver.required.providers) with value '{}'. Please remove this configuration " +
                        "property. '{}' is already contained in the property resource.resolver.required.providernames.",
                        ResourceResolverFactoryConfig.LEGACY_REQUIRED_PROVIDER_PID,
                        ResourceResolverFactoryConfig.REQUIRED_PROVIDER_NAME);
            } else {
                LOG.warn("ResourceResolverFactory is using deprecated required providers configuration " +
                        "(resource.resolver.required.providers) with value '{}'. Please remove this configuration " +
                        "property and add '{}' to the property resource.resolver.required.providernames.",
                        ResourceResolverFactoryConfig.LEGACY_REQUIRED_PROVIDER_PID,
                        ResourceResolverFactoryConfig.REQUIRED_PROVIDER_NAME);
            }
        }

        final List<RequiredProvider> rps = new ArrayList<>();
        if (requiredResourceProvidersLegacy != null) {
            final Logger logger = LoggerFactory.getLogger(getClass());
            for (final String value : requiredResourceProvidersLegacy) {
                RequiredProvider rp = new RequiredProvider();

                if (value.startsWith("(")) {
                    try {
                        rp.filter = FrameworkUtil.createFilter(value);
                    } catch (final InvalidSyntaxException e) {
                        logger.warn("Ignoring invalid filter syntax for required provider: " + value, e);
                        rp = null;
                    }
                } else {
                    rp.pid = value;
                }
                if (rp != null) {
                    rps.add(rp);
                }
            }
        }
        if (requiredResourceProviderNames != null) {
            for (final String value : requiredResourceProviderNames) {
                final RequiredProvider rp = new RequiredProvider();
                rp.name = value;
                rps.add(rp);
            }
        }
        return rps;
    }

    public boolean checkPreconditions() {
        boolean canRegister = true;
        for (final RequiredProvider rp : requiredProviders) {
            canRegister = false;
            for (final ResourceProviderHandler h : tracker.getResourceProviderStorage().getAllHandlers()) {
                final ResourceProviderInfo info = h.getInfo();
                if (info == null) {
                    // provider has been deactivated in the meantime
                    // ignore and continue
                    continue;
                }
                @SuppressWarnings("rawtypes") final ServiceReference ref = info.getServiceReference();
                final Object servicePid = ref.getProperty(Constants.SERVICE_PID);
                if (rp.name != null && rp.name.equals(info.getName())) {
                    canRegister = true;
                    break;
                } else if (rp.filter != null && rp.filter.match(ref)) {
                    canRegister = true;
                    break;
                } else if (rp.pid != null && rp.pid.equals(servicePid)) {
                    canRegister = true;
                    break;
                } else if (rp.pid != null && rp.pid.equals(ref.getProperty(LegacyResourceProviderWhiteboard.ORIGINAL_SERVICE_PID))) {
                    canRegister = true;
                    break;
                }
            }
            if (!canRegister) {
                break;
            }
        }
        return canRegister;
    }
}
