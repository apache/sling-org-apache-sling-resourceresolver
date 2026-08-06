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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PagedQueryIteratorTest extends AbstractMappingMapEntriesTest {

    private static final String PROPNAME = "prop";

    @SuppressWarnings("unchecked")
    @Override
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.openMocks(this).close();

        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
    }

    @Test
    public void testEmptyQuery() {
        when(resourceResolver.findResources("empty", "JCR-SQL2")).thenReturn(Collections.emptyIterator());
        Iterator<Resource> it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "empty", 2000);
        assertFalse(it.hasNext());
    }

    @Test(expected = QuerySyntaxException.class)
    public void testMalformedQuery() {
        when(resourceResolver.findResources(eq("malformed"), eq("JCR-SQL2")))
                .thenThrow(new QuerySyntaxException("x", "y", "z"));
        new PagedQueryIterator("alias", PROPNAME, resourceResolver, "malformed", 2000);
    }

    @Test
    public void testSimple() {
        String[] expected = new String[] {"a", "b", "c"};
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources(eq("simple"), eq("JCR-SQL2"))).thenReturn(expectedResources.iterator());
        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "simple", 2000);
        for (String key : expected) {
            assertEquals(key, getFirstValueOf(it.next(), PROPNAME));
        }
        assertFalse(it.hasNext());
        assertEquals("", it.getWarning());
    }

    @Test
    public void testSimpleWrongOrder() {
        // SLING-13284: out-of-order results (from a stale async index) should not abort iteration
        String[] expected = new String[] {"a", "b", "d", "c"};
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources(eq("testSimpleWrongOrder"), eq("JCR-SQL2")))
                .thenReturn(expectedResources.iterator());
        PagedQueryIterator it =
                new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testSimpleWrongOrder", 2000);
        checkResult(it, expected);
    }

    @Test
    public void testSimpleWrongType() {
        try (TestLogger logger =
                TestLogger.createStartedFor(PagedQueryIterator.class).contains("unexpected")) {

            String[] expected = new String[] {"a", "b", "c"};
            Collection<Resource> expectedResources = toResourceList(expected);

            Date oneMore = new Date(0);
            ValueMap properties = new ValueMapDecorator(Map.of(PROPNAME, new Date[] {oneMore}));
            Resource r = mock(Resource.class);
            when(r.getValueMap()).thenReturn(properties);

            expectedResources.add(r);

            when(resourceResolver.findResources(eq("testSimpleWrongType"), eq("JCR-SQL2")))
                    .thenReturn(expectedResources.iterator());
            PagedQueryIterator it =
                    new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testSimpleWrongType", 2000);

            String[] expWithOneMore = Arrays.copyOf(expected, expected.length + 1);
            // implementation detail: this assumes the way Sling converts Dates to Strings
            expWithOneMore[expected.length] = oneMore.toInstant().toString();

            checkResult(it, expWithOneMore);

            // implementation detail: assumes format of log message
            List<String> logEntries = logger.stopAndGetLogs();
            assertTrue(
                    "Log should contain 'class [Ljava.util.Date;', but got: " + logEntries,
                    logEntries.toString().contains("class [Ljava.util.Date;"));
        }
    }

    @Test
    public void testSimpleWrongResultAfterKey() {
        // SLING-13284: out-of-order results across page boundaries should not abort iteration
        String[] expected = new String[] {"x", "x", "a", "a"};
        Collection<Resource> expectedResources = toResourceList(expected);
        when(resourceResolver.findResources("testSimpleWrongResultAfterKey", "JCR-SQL2"))
                .thenReturn(expectedResources.iterator());
        PagedQueryIterator it =
                new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testSimpleWrongResultAfterKey", 1);
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    public void testWrongResultBelowPageBoundary() {
        // SLING-13284: a result on a new page has a value below the page boundary key.
        // Page 1: ["b", "c"] with pageSize=1 — "c" triggers page break (lastKey="c").
        // Page 2: returns "a" which is < lastKey "c" — must not abort.
        Collection<Resource> page1 = toResourceList("b", "c");
        Collection<Resource> page2 = toResourceList("a");
        when(resourceResolver.findResources("boundary ''", "JCR-SQL2")).thenReturn(page1.iterator());
        when(resourceResolver.findResources("boundary 'c'", "JCR-SQL2")).thenReturn(page2.iterator());

        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "boundary '%s'", 1);
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    public void testStaleAsyncIndexDoesNotAbortIteration() {
        // SLING-13284: Reproduces the scenario where an async index delivers rows in an order
        // that no longer matches the live property values (e.g. sling:alias was rewritten
        // between the last index cycle and a restart). All rows must still be processed.
        // The descending order simulates what happens when the index sorts by a stale
        // first([sling:alias]) value that no longer matches the current values[0].
        String[] staleOrder = new String[] {"ayacucho-fleeces", "ayacucho-bamboo", "ayacucho"};
        Collection<Resource> resources = toResourceList(staleOrder);
        when(resourceResolver.findResources("staleIndex", "JCR-SQL2")).thenReturn(resources.iterator());

        PagedQueryIterator it = new PagedQueryIterator("alias", PROPNAME, resourceResolver, "staleIndex", 2000);
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals("all rows must be processed even when order doesn't match", 3, count);
    }

    @Test
    public void testPagedWithEmpty() {
        String[] expected = new String[] {"", "a", "b", "c", "d"};
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        when(resourceResolver.findResources("testPagedWithEmpty ''", "JCR-SQL2"))
                .thenReturn(expectedFilteredResources.iterator());
        PagedQueryIterator it =
                new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testPagedWithEmpty '%s'", 2000);
        checkResult(it, expected);
        assertEquals("", it.getWarning());
    }

    @Test
    public void testPagedLargePage() {
        final int cnt = 140;
        final int pageSize = 5;
        String[] expected = new String[cnt];
        Arrays.fill(expected, "a");
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        when(resourceResolver.findResources("testPagedLargePage ''", "JCR-SQL2"))
                .thenReturn(expectedFilteredResources.iterator());
        PagedQueryIterator it =
                new PagedQueryIterator("alias", PROPNAME, resourceResolver, "testPagedLargePage '%s'", pageSize);
        checkResult(it, expected);
        assertEquals(
                "Largest number of alias entries with the same 'first' selector exceeds expectation of " + pageSize * 10
                        + " (value 'a' appears " + cnt + " times)",
                it.getWarning());
    }

    @Test
    public void testPagedResourcesOnPageBoundaryLost() {
        String[] expected = new String[] {"a", "a", "a", "a", "a", "a", "b", "c", "d"};
        Collection<Resource> expectedResources = toResourceList(expected);
        Collection<Resource> expectedFilteredResources = filter("", expectedResources);
        Collection<Resource> expectedFilteredResourcesA = filter("a", expectedResources);
        Collection<Resource> expectedFilteredResourcesB = filter("b", expectedResources);
        Collection<Resource> expectedFilteredResourcesC = filter("c", expectedResources);
        Collection<Resource> expectedFilteredResourcesD = filter("d", expectedResources);
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost ''"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResources.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'a'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesA.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'b'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesB.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'c'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesC.iterator());
        when(resourceResolver.findResources(eq("testPagedResourcesOnPageBoundaryLost 'd'"), eq("JCR-SQL2")))
                .thenReturn(expectedFilteredResourcesD.iterator());
        Iterator<Resource> it = new PagedQueryIterator(
                "alias", PROPNAME, resourceResolver, "testPagedResourcesOnPageBoundaryLost '%s'", 5);

        checkResult(it, expected);
    }

    private static Collection<Resource> toResourceList(String... keys) {
        Collection<Resource> result = new ArrayList<>();
        for (String key : keys) {
            ValueMap m = mock(ValueMap.class);
            when(m.get(eq(PROPNAME), any(Object.class))).thenReturn(new String[] {key});
            Resource r = mock(Resource.class);
            when(r.getValueMap()).thenReturn(m);
            result.add(r);
        }
        return result;
    }

    private static Collection<Resource> filter(String key, Collection<Resource> input) {
        Predicate<Resource> filter = r -> getFirstValueOf(r, PROPNAME).compareTo(key) >= 0;
        return input.stream().filter(filter).collect(Collectors.toList());
    }

    private static String getFirstValueOf(Resource r, String propname) {
        return r.getValueMap().get(propname, new String[0])[0];
    }

    private static void checkResult(Iterator<Resource> it, String... expected) {
        int pos = 0;
        for (String key : expected) {
            assertEquals("expects " + key + " at position " + pos, key, getFirstValueOf(it.next(), PROPNAME));
            pos += 1;
        }
        assertFalse(it.hasNext());
    }

    // inspired by Oak LogCustomizer, to be factored out when needed
    private static class TestLogger implements AutoCloseable {

        private final Appender<ILoggingEvent> customLogger;

        private final Logger logger;
        private String matchContainsMessage;
        private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

        private TestLogger(Class<?> clazz) {
            this.logger = getLogger(clazz);

            this.customLogger = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent e) {
                    String message = e.getFormattedMessage();
                    if (matchContainsMessage == null || message.contains(matchContainsMessage)) {
                        logs.add(message);
                    }
                }
            };

            this.customLogger.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        }

        public static TestLogger createStartedFor(Class<?> clazz) {
            TestLogger logger = new TestLogger(clazz);
            logger.customLogger.start();
            logger.logger.addAppender(logger.customLogger);
            return logger;
        }

        public TestLogger contains(String matchContainsMessage) {
            this.matchContainsMessage = matchContainsMessage;
            return this;
        }

        public List<String> stopAndGetLogs() {
            logger.detachAppender(customLogger);
            customLogger.stop();
            return logs;
        }

        public void close() {
            logger.detachAppender(customLogger);
            customLogger.stop();
            logs.clear();
        }

        private static Logger getLogger(Class<?> clazz) {
            return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(clazz);
        }
    }
}
