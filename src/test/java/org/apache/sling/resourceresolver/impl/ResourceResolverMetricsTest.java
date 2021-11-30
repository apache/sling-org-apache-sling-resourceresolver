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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

import org.apache.sling.commons.metrics.Gauge;
import org.apache.sling.commons.metrics.MetricsService;
import org.apache.sling.testing.mock.osgi.junit.OsgiContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

public class ResourceResolverMetricsTest {
    
    @Rule
    public OsgiContext context = new OsgiContext();
    
    private MetricsService metricsService;
    
    private ResourceResolverMetrics metrics;
    
    @Before
    public void setup() {
        metrics = new ResourceResolverMetrics();
        metricsService = Mockito.mock(MetricsService.class);
        context.registerService(MetricsService.class, metricsService);
        context.registerInjectActivateService(metrics);
    }
    
    @Test
    public void testGauges() {
        Gauge<Long> vanityPaths =  getGauge("numberOfVanityPaths");
        Gauge<Long> aliases = getGauge("numberOfAliases");
        assertThat(vanityPaths.getValue(),is(0L));
        assertThat(aliases.getValue(),is(0L));
        
        metrics.setNumberOfAliasesSupplier(() -> 3L);
        metrics.setNumberOfVanityPathsSupplier(() -> 2L);
        assertThat(vanityPaths.getValue(),is(2L));
        assertThat(aliases.getValue(),is(3L));
        
    }
    
    private Gauge<Long> getGauge(String name) {
        String filter = String.format("(%s=%s)", Gauge.NAME,name);
        Gauge<Long>[] result = context.getServices(Gauge.class,filter);
        assertThat(result.length,is(1));
        return result[0];
    }

}
