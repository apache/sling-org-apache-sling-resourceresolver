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
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

@Component(service=ResourceResolverMetrics.class, immediate=true)
public class ResourceResolverMetrics {
    
    protected static final String METRICS_PREFIX = "org.apache.sling.resourceresolver";
    
    @Reference
    MetricsService metricsService;
    
    Supplier<Long> NULL_SUPPLIER = () -> 0L;
    
    // number of vanity paths
    private ServiceRegistration<Gauge<Long>> numberOfVanityPathsGauge;
    private Supplier<Long> numberOfVanityPathsSupplier = NULL_SUPPLIER;
    
    // number of aliases
    private ServiceRegistration<Gauge<Long>> numberOfAliasesGauge;
    private Supplier<Long> numberOfAliasesSupplier = NULL_SUPPLIER;
    
    private Counter unclosedResourceResolvers;
    
    
    @Activate
    protected void activate(ComponentContext context) {
        BundleContext bundleContext = context.getBundleContext();
        numberOfVanityPathsGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfVanityPaths", () -> numberOfVanityPathsSupplier );
        numberOfAliasesGauge = registerGauge(bundleContext, METRICS_PREFIX + ".numberOfAliases", () -> numberOfAliasesSupplier );
        unclosedResourceResolvers = metricsService.counter(METRICS_PREFIX  + ".unclosedResourceResolvers");
    }
    
    @Deactivate
    protected void deactivate() {
        numberOfVanityPathsGauge.unregister();
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
     * Set the number of aliases in the system
     * @param supplier a supplier returning the number of aliases
     */
    public void setNumberOfAliasesSupplier(Supplier<Long> supplier) {
        numberOfAliasesSupplier = supplier;
    }
    
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
        @SuppressWarnings("rawtypes")
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
