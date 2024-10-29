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
    public void testGaugesAliases() {
        // get gauges
        Gauge<Long> numberOfResourcesWithAliasedChildren = getGauge("numberOfResourcesWithAliasedChildren");
        Gauge<Long> numberOfResourcesWithAliasesOnStartup = getGauge("numberOfResourcesWithAliasesOnStartup");
        Gauge<Long> numberOfDetectedInvalidAliasesGauge = getGauge("numberOfDetectedInvalidAliases");
        Gauge<Long> numberOfDetectedConflictingAliases = getGauge("numberOfDetectedConflictingAliases");

        // check initial Values
        assertThat(numberOfResourcesWithAliasedChildren.getValue(), is(0L));
        assertThat(numberOfResourcesWithAliasesOnStartup.getValue(), is(0L));
        assertThat(numberOfDetectedInvalidAliasesGauge.getValue(), is(0L));
        assertThat(numberOfDetectedConflictingAliases.getValue(), is(0L));

        // set values
        metrics.setNumberOfResourcesWithAliasedChildrenSupplier(() -> 8L);
        metrics.setNumberOfResourcesWithAliasesOnStartupSupplier(() -> 9L);
        metrics.setNumberOfDetectedInvalidAliasesSupplier(() -> 10L);
        metrics.setNumberOfDetectedConflictingAliasesSupplier(() -> 11L);

        // check values
        assertThat(numberOfResourcesWithAliasedChildren.getValue(), is(8L));
        assertThat(numberOfResourcesWithAliasesOnStartup.getValue(), is(9L));
        assertThat(numberOfDetectedInvalidAliasesGauge.getValue(), is(10L));
        assertThat(numberOfDetectedConflictingAliases.getValue(), is(11L));
    }

    @Test
    public void testGaugesVanityPaths() {
        // get gauges
        Gauge<Long> numberOfVanityPaths = getGauge("numberOfVanityPaths");
        Gauge<Long> numberOfResourcesWithVanityPathsOnStartup = getGauge("numberOfResourcesWithVanityPathsOnStartup");
        Gauge<Long> numberOfVanityPathLookups = getGauge("numberOfVanityPathLookups");
        Gauge<Long> numberOfVanityPathBloomNegatives = getGauge("numberOfVanityPathBloomNegatives");
        Gauge<Long> numberOfVanityPathBloomFalsePositives = getGauge("numberOfVanityPathBloomFalsePositives");

        // check initial Values
        assertThat(numberOfVanityPaths.getValue(), is(0L));
        assertThat(numberOfResourcesWithVanityPathsOnStartup.getValue(), is(0L));
        assertThat(numberOfVanityPathLookups.getValue(), is(0L));
        assertThat(numberOfVanityPathBloomNegatives.getValue(), is(0L));
        assertThat(numberOfVanityPathBloomFalsePositives.getValue(), is(0L));

        // set values
        metrics.setNumberOfVanityPathsSupplier(() -> 3L);
        metrics.setNumberOfResourcesWithVanityPathsOnStartupSupplier(() -> 4L);
        metrics.setNumberOfVanityPathLookupsSupplier(() -> 5L);
        metrics.setNumberOfVanityPathBloomNegativesSupplier(() -> 6L);
        metrics.setNumberOfVanityPathBloomFalsePositivesSupplier(() -> 7L);

        // check values
        assertThat(numberOfVanityPaths.getValue(), is(3L));
        assertThat(numberOfResourcesWithVanityPathsOnStartup.getValue(), is(4L));
        assertThat(numberOfVanityPathLookups.getValue(), is(5L));
        assertThat(numberOfVanityPathBloomNegatives.getValue(), is(6L));
        assertThat(numberOfVanityPathBloomFalsePositives.getValue(), is(7L));
    }

    private Gauge<Long> getGauge(String name) {
        String filter = String.format("(%s=%s)", Gauge.NAME, ResourceResolverMetrics.METRICS_PREFIX + "." + name);
        @SuppressWarnings("unchecked")
        Gauge<Long>[] result = context.getServices(Gauge.class, filter);
        assertThat(result.length, is(1));
        return result[0];
    }
}
