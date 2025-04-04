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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.TreeBidiMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceDecorator;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.resource.runtime.RuntimeService;
import org.apache.sling.resourceresolver.impl.helper.ResourceDecoratorTracker;
import org.apache.sling.resourceresolver.impl.mapping.MapEntries;
import org.apache.sling.resourceresolver.impl.mapping.Mapping;
import org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider;
import org.apache.sling.resourceresolver.impl.observation.ResourceChangeListenerWhiteboard;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderTracker.ChangeListener;
import org.apache.sling.resourceresolver.impl.providers.RuntimeServiceImpl;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ResourceResolverFactoryActivator/code> keeps track of required services for the
 * resource resolver factory.
 * One all required providers and provider factories are available a resource resolver factory
 * is registered.
 *
 */
@Designate(ocd = ResourceResolverFactoryConfig.class)
@Component(name = "org.apache.sling.jcr.resource.internal.JcrResourceResolverFactoryImpl")
public class ResourceResolverFactoryActivator {

    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** Tracker for the resource decorators. */
    private final ResourceDecoratorTracker resourceDecoratorTracker = new ResourceDecoratorTracker();

    /** all mappings */
    private volatile Mapping[] mappings;

    /** The fake URLs */
    private volatile BidiMap<String, String> virtualURLMap;

    /** the search path for ResourceResolver.getResource(String) */
    private volatile List<String> searchPath = Collections.emptyList();

    /** the root location of the /etc/map entries */
    private volatile String mapRoot;

    private volatile String mapRootPrefix;

    /** Event admin. */
    @Reference
    EventAdmin eventAdmin;

    /** String Interpolation Provider. */
    @Reference
    StringInterpolationProvider stringInterpolationProvider;

    /** Service User Mapper */
    @Reference
    ServiceUserMapper serviceUserMapper;

    @Reference
    ResourceAccessSecurityTracker resourceAccessSecurityTracker;

    @SuppressWarnings("java:S3077")
    @Reference(
            cardinality = ReferenceCardinality.OPTIONAL,
            policyOption = ReferencePolicyOption.GREEDY,
            policy = ReferencePolicy.DYNAMIC)
    private volatile ResourceResolverMetrics metrics;

    volatile ResourceProviderTracker resourceProviderTracker;

    volatile ResourceChangeListenerWhiteboard changeListenerWhiteboard;

    /** Bundle Context */
    private volatile BundleContext bundleContext;

    private volatile ResourceResolverFactoryConfig config = DEFAULT_CONFIG;

    @SuppressWarnings("java:S3077")
    private volatile Set<String> allowedAliasLocations = Collections.emptySet();

    /** Observation paths */
    private volatile Path[] observationPaths;

    private final FactoryRegistrationHandler factoryRegistrationHandler = new FactoryRegistrationHandler();

    private final VanityPathConfigurer vanityPathConfigurer = new VanityPathConfigurer();

    {
        vanityPathConfigurer.setConfiguration(DEFAULT_CONFIG, null);
    }

    /**
     * Get the resource decorator tracker.
     */
    public ResourceDecoratorTracker getResourceDecoratorTracker() {
        return this.resourceDecoratorTracker;
    }

    public ResourceAccessSecurityTracker getResourceAccessSecurityTracker() {
        return this.resourceAccessSecurityTracker;
    }

    public EventAdmin getEventAdmin() {
        return this.eventAdmin;
    }

    public StringInterpolationProvider getStringInterpolationProvider() {
        return stringInterpolationProvider;
    }

    public Optional<ResourceResolverMetrics> getResourceResolverMetrics() {
        return Optional.ofNullable(this.metrics);
    }

    /**
     * This method is called from {@link MapEntries}
     */
    public BidiMap<String, String> getVirtualURLMap() {
        return virtualURLMap;
    }

    /**
     * This method is called from {@link MapEntries}
     */
    public Mapping[] getMappings() {
        return mappings;
    }

    public List<String> getSearchPath() {
        return searchPath;
    }

    public boolean isMangleNamespacePrefixes() {
        return config.resource_resolver_manglenamespaces();
    }

    public String getMapRoot() {
        return mapRoot;
    }

    public boolean isMapConfiguration(String path) {
        return path.equals(this.getMapRoot()) || path.startsWith(this.mapRootPrefix);
    }

    public boolean isOptimizeAliasResolutionEnabled() {
        return this.config.resource_resolver_optimize_alias_resolution();
    }

    public Set<String> getAllowedAliasLocations() {
        return this.allowedAliasLocations;
    }

    public boolean isLogUnclosedResourceResolvers() {
        return this.config.resource_resolver_log_unclosed();
    }

    public boolean shouldLogResourceResolverClosing() {
        return this.config.resource_resolver_log_closing();
    }

    public Path[] getObservationPaths() {
        return this.observationPaths;
    }

    public VanityPathConfigurer getVanityPathConfigurer() {
        return this.vanityPathConfigurer;
    }

    // ---------- SCR Integration ---------------------------------------------

    /**
     * Activates this component (called by SCR before)
     */
    @Activate
    protected void activate(
            final BundleContext bundleContext,
            final ResourceResolverFactoryConfig config,
            final VanityPathConfigurer.DeprecatedVanityConfig deprecatedVanityConfig) {
        this.vanityPathConfigurer.setConfiguration(config, deprecatedVanityConfig);
        this.bundleContext = bundleContext;
        this.config = config;

        final BidiMap<String, String> virtuals = new TreeBidiMap<>();
        for (int i = 0;
                config.resource_resolver_virtual() != null && i < config.resource_resolver_virtual().length;
                i++) {
            final String[] parts = Mapping.split(config.resource_resolver_virtual()[i]);
            virtuals.put(parts[0], parts[2]);
        }
        virtualURLMap = virtuals;

        final List<Mapping> maps = new ArrayList<>();
        for (int i = 0;
                config.resource_resolver_mapping() != null && i < config.resource_resolver_mapping().length;
                i++) {
            maps.add(new Mapping(config.resource_resolver_mapping()[i]));
        }
        final Mapping[] tmp = maps.toArray(new Mapping[maps.size()]);

        // check whether direct mappings are allowed
        if (config.resource_resolver_allowDirect()) {
            final Mapping[] tmp2 = new Mapping[tmp.length + 1];
            tmp2[0] = Mapping.DIRECT;
            System.arraycopy(tmp, 0, tmp2, 1, tmp.length);
            mappings = tmp2;
        } else {
            mappings = tmp;
        }

        // from configuration if available
        final List<String> searchPathList = new ArrayList<>();
        if (config.resource_resolver_searchpath() != null && config.resource_resolver_searchpath().length > 0) {
            for (String path : config.resource_resolver_searchpath()) {
                // ensure leading slash
                if (!path.startsWith("/")) {
                    path = "/".concat(path);
                }
                // ensure trailing slash
                if (!path.endsWith("/")) {
                    path = path.concat("/");
                }
                searchPathList.add(path);
            }
        }
        if (searchPathList.isEmpty()) {
            searchPathList.add("/");
        }
        this.searchPath = Collections.unmodifiableList(searchPathList);

        // the root of the resolver mappings
        mapRoot = config.resource_resolver_map_location();
        mapRootPrefix = mapRoot + '/';

        final String[] paths = config.resource_resolver_map_observation();
        this.observationPaths = new Path[paths.length];
        for (int i = 0; i < paths.length; i++) {
            this.observationPaths[i] = new Path(paths[i]);
        }

        // optimize alias path allow list
        String[] aliasLocationsPrefix = config.resource_resolver_allowed_alias_locations();
        if (aliasLocationsPrefix != null) {
            final Set<String> prefixSet = new TreeSet<>();
            for (final String prefix : aliasLocationsPrefix) {
                String value = prefix.trim();
                if (!value.isEmpty()) {
                    if (value.startsWith("/")) { // absolute path should be given
                        // path must not end with "/" to be valid absolute path
                        prefixSet.add(StringUtils.removeEnd(value, "/"));
                    } else {
                        logger.warn(
                                "Path [{}] is ignored. As only absolute paths are allowed for alias optimization",
                                value);
                    }
                }
            }
            if (!prefixSet.isEmpty()) {
                this.allowedAliasLocations = Collections.unmodifiableSet(prefixSet);
            }
        }
        if (!config.resource_resolver_optimize_alias_resolution()) {
            logger.warn(
                    "The non-optimized alias resolution is used, which has been found to have problems (see SLING-12025). "
                            + "Please migrate to the optimized alias resolution, as the non-optimized version will be removed");
        }

        // for testing: if we run unit test, both trackers are set from the outside
        final boolean hasPreRegisteredResourceProviderTracker = this.resourceProviderTracker != null;
        if (!hasPreRegisteredResourceProviderTracker) {
            this.resourceProviderTracker = new ResourceProviderTracker();
            this.changeListenerWhiteboard = new ResourceChangeListenerWhiteboard();
            this.changeListenerWhiteboard.activate(this.bundleContext, this.resourceProviderTracker, searchPath);
        }

        // check for required property
        Set<String> requiredResourceProvidersLegacy = getStringSet(config.resource_resolver_required_providers());
        Set<String> requiredResourceProviderNames = getStringSet(config.resource_resolver_required_providernames());

        final FactoryPreconditions factoryPreconditions = new FactoryPreconditions(
                resourceProviderTracker, requiredResourceProviderNames, requiredResourceProvidersLegacy);
        factoryRegistrationHandler.configure(this, factoryPreconditions);

        if (!hasPreRegisteredResourceProviderTracker) {
            this.resourceProviderTracker.activate(this.bundleContext, this.eventAdmin, new ChangeListener() {

                @Override
                public void providerAdded() {
                    factoryRegistrationHandler.maybeRegisterFactory();
                }

                @Override
                public void providerRemoved(final boolean stateful, final boolean isUsed) {
                    if (isUsed && (stateful || config.resource_resolver_providerhandling_paranoid())) {
                        factoryRegistrationHandler.unregisterFactory();
                    }
                    factoryRegistrationHandler.maybeRegisterFactory();
                }
            });
        }
    }

    /**
     * Modifies this component (called by SCR to update this component)
     */
    @Modified
    protected void modified(
            final BundleContext bundleContext,
            final ResourceResolverFactoryConfig config,
            final VanityPathConfigurer.DeprecatedVanityConfig deprecatedVanityConfig) {
        this.deactivateInternal();
        this.activate(bundleContext, config, deprecatedVanityConfig);
    }

    /**
     * Deactivates this component (called by SCR to take out of service)
     */
    @Deactivate
    protected void deactivate() {
        // factoryRegistrationHandler must be closed before bundleContext is set to null
        this.factoryRegistrationHandler.close();
        this.bundleContext = null;
        deactivateInternal();
    }

    private void deactivateInternal() {
        this.config = DEFAULT_CONFIG;
        this.vanityPathConfigurer.setConfiguration(DEFAULT_CONFIG, null);
        this.changeListenerWhiteboard.deactivate();
        this.changeListenerWhiteboard = null;
        this.resourceProviderTracker.deactivate();
        this.resourceProviderTracker = null;
        this.resourceDecoratorTracker.close();
    }

    /**
     * Get the runtime service
     * @return The runtime service
     */
    public RuntimeService getRuntimeService() {
        return new RuntimeServiceImpl(this.getResourceProviderTracker());
    }

    public ServiceUserMapper getServiceUserMapper() {
        return this.serviceUserMapper;
    }

    public BundleContext getBundleContext() {
        return this.bundleContext;
    }

    /**
     * Bind a resource decorator.
     */
    @Reference(
            service = ResourceDecorator.class,
            cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC)
    protected void bindResourceDecorator(
            final ResourceDecorator decorator, final ServiceReference<ResourceDecorator> ref) {
        this.resourceDecoratorTracker.bindResourceDecorator(decorator, ref);
    }

    /**
     * Unbind a resource decorator.
     */
    protected void unbindResourceDecorator(final ResourceDecorator decorator) {
        this.resourceDecoratorTracker.unbindResourceDecorator(decorator);
    }

    /**
     * Get the resource provider tracker
     * @return The tracker
     */
    public ResourceProviderTracker getResourceProviderTracker() {
        return resourceProviderTracker;
    }

    /**
     * Utility method to create a set out of a string array
     */
    private Set<String> getStringSet(final String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        final Set<String> set = new HashSet<>();
        for (final String val : values) {
            if (val != null && !val.trim().isEmpty()) {
                set.add(val.trim());
            }
        }
        return set.isEmpty() ? null : set;
    }

    public static ResourceResolverFactoryConfig DEFAULT_CONFIG;

    static {
        final InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(final Object obj, final Method calledMethod, final Object[] args) throws Throwable {
                if (calledMethod.getDeclaringClass().isAssignableFrom(ResourceResolverFactoryConfig.class)) {
                    return calledMethod.getDefaultValue();
                }
                if (calledMethod.getDeclaringClass() == Object.class) {
                    if (calledMethod.getName().equals("toString") && (args == null || args.length == 0)) {
                        return "Generated @" + ResourceResolverFactoryConfig.class.getName() + " instance";
                    }
                    if (calledMethod.getName().equals("hashCode") && (args == null || args.length == 0)) {
                        return this.hashCode();
                    }
                    if (calledMethod.getName().equals("equals") && args != null && args.length == 1) {
                        return Boolean.FALSE;
                    }
                }
                throw new InternalError("unexpected method dispatched: " + calledMethod);
            }
        };
        DEFAULT_CONFIG = (ResourceResolverFactoryConfig) Proxy.newProxyInstance(
                ResourceResolverFactoryConfig.class.getClassLoader(),
                new Class[] {ResourceResolverFactoryConfig.class},
                handler);
    }
}
