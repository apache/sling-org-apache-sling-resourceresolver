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

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.function.Supplier;

import org.apache.sling.commons.metrics.Counter;
import org.apache.sling.commons.metrics.Gauge;
import org.apache.sling.commons.metrics.MetricsService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 *  Export metrics for the resource resolver bundle:
 *
 *  org.apache.sling.resourceresolver.numberOfVanityPaths -- the total number of vanity paths in the cache
 *  org.apache.sling.resourceresolver.numberOfVanityPathLookups -- the total number of vanity path lookups
 *  org.apache.sling.resourceresolver.numberOfVanityPathBloomNegatives -- the total number of vanity path lookups filtered by the bloom filter
 *  org.apache.sling.resourceresolver.numberOfVanityPathBloomFalsePositives -- the total number of vanity path lookup that passed the bloom filter but were false positives
 *  org.apache.sling.resourceresolver.numberOfAliases -- the total number of aliases
 *  org.apache.sling.resourceresolver.unclosedResourceResolvers -- the total number of unclosed resource resolvers
 *
 */


@Component(service=ResourceResolverMetrics.class)
public class ResourceResolverMetrics {
    
    protected static final String METRICS_PREFIX = "org.apache.sling.resourceresolver";
    
    @Reference
    MetricsService metricsService;
    
    private static final Supplier<Long> ZERO_SUPPLIER = () -> 0L;
    
    // number of vanity paths
    private ServiceRegistration<Gauge<Long>> numberOfVanityPathsGauge;
    private Supplier<Long> numberOfVanityPathsSupplier = ZERO_SUPPLIER;

    // total number of vanity path lookups
    private ServiceRegistration<Gauge<Long>> numberOfVanityPathLookupsGauge;
    private Supplier<Long> numberOfVanityPathLookupsSupplier = ZERO_SUPPLIER;

    // number of vanity path lookups filtered by Bloom filter
    private ServiceRegistration<Gauge<Long>> numberOfVanityPathBloomNegativeGauge;
    private Supplier<Long> numberOfVanityPathBloomNegativeSupplier = ZERO_SUPPLIER;

    // number of vanity path lookups passing the Bloom filter but being false positives
    private ServiceRegistration<Gauge<Long>> numberOfVanityPathBloomFalsePositiveGauge;
    private Supplier<Long> numberOfVanityPathBloomFalsePositiveSupplier = ZERO_SUPPLIER;

    // number of aliases
    private ServiceRegistration<Gauge<Long>> numberOfAliasesGauge;
    private Supplier<Long> numberOfAliasesSupplier = ZERO_SUPPLIER;
    
    private Counter unclosedResourceResolvers;
    
    
    @Activate
    protected void activate(BundleContext bundleContext) {
        numberOfVanityPathsGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfVanityPaths", () -> numberOfVanityPathsSupplier );
        numberOfVanityPathLookupsGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfVanityPathLookups", () -> numberOfVanityPathLookupsSupplier );
        numberOfVanityPathBloomNegativeGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfVanityPathBloomNegatives", () -> numberOfVanityPathBloomNegativeSupplier );
        numberOfVanityPathBloomFalsePositiveGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfVanityPathBloomFalsePositives", () -> numberOfVanityPathBloomFalsePositiveSupplier );
        numberOfAliasesGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfAliases", () -> numberOfAliasesSupplier );
        unclosedResourceResolvers = metricsService.counter(METRICS_PREFIX  + ".unclosedResourceResolvers");
    }

    @Deactivate
    protected void deactivate() {
        numberOfVanityPathsGauge.unregister();
        numberOfVanityPathLookupsGauge.unregister();
        numberOfVanityPathBloomNegativeGauge.unregister();
        numberOfVanityPathBloomFalsePositiveGauge.unregister();
        numberOfAliasesGauge.unregister();
    }

    /**
     * Set the number of vanity paths in the system
     * @param supplier a supplier returning the number of vanity paths
     */
    public void setNumberOfVanityPathsSupplier(Supplier<Long> supplier) {
        numberOfVanityPathsSupplier = supplier;
    }

    /**
     * Set the number of vanity path lookups in the system
     * @param supplier a supplier returning the number of vanity path lookups
     */
    public void setNumberOfVanityPathLookupsSupplier(Supplier<Long> supplier) {
        numberOfVanityPathLookupsSupplier = supplier;
    }

    /**
     * Set the number of vanity path lookups in the system that were filtered
     * @param supplier a supplier returning the number of vanity path lookups
     */
    public void setNumberOfVanityPathBloomNegativeSupplier(Supplier<Long> supplier) {
        numberOfVanityPathBloomNegativeSupplier = supplier;
    }

    /**
     * Set the number of vanity path lookups in the system that were not catched by the Bloom filter
     * @param supplier a supplier returning the number of vanity path lookups
     */
    public void setNumberOfVanityPathBloomFalsePositiveSupplier(Supplier<Long> supplier) {
        numberOfVanityPathBloomFalsePositiveSupplier = supplier;
    }

    /**
     * Set the number of aliases in the system
     * @param supplier a supplier returning the number of aliases
     */
    public void setNumberOfAliasesSupplier(Supplier<Long> supplier) {
        numberOfAliasesSupplier = supplier;
    }
    
    /**
     * Increment the counter for the number of unresolved resource resolvers
     */
    public void reportUnclosedResourceResolver() {
        unclosedResourceResolvers.increment();
    }
    
    /**
     * Create a gauge metrics.
     *
     * Sling Metrics does not directly offer a gauge as any other type, but only a whiteboard approach
     * @param context the bundlecontext
     * @param name the name of the metric
     * @param supplier a supplier returning a supplier returning the requested value
     * @return the ServiceRegistration for this metric (must be unregistered!)
     */
    @SuppressWarnings("unchecked")
    private ServiceRegistration<Gauge<Long>> registerGauge(BundleContext context, String name, Supplier<Supplier<Long>> supplier) {

        ResourceResolverGauge gauge = new ResourceResolverGauge(supplier);
        @SuppressWarnings("all")
        Dictionary props = new Hashtable();
        props.put(Gauge.NAME, name);
        return context.registerService(Gauge.class, gauge, props);
    }

    public class ResourceResolverGauge implements Gauge<Long> {
        Supplier<Supplier<Long>> supplier;

        public ResourceResolverGauge(Supplier<Supplier<Long>> supplier) {
            this.supplier = supplier;
        }

        @Override
        public Long getValue() {
            return supplier.get().get();
        }
    }
}
