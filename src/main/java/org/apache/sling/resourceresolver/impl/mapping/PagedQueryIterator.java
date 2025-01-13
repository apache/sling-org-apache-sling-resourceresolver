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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import static org.apache.sling.resourceresolver.impl.mapping.MapEntries.queryLiteral;

/**
 * Utility class for running paged queries.
 */
public class PagedQueryIterator implements Iterator<Resource> {

    /** default log, using MapEntries for backwards compatibility */
    private final Logger log = LoggerFactory.getLogger(MapEntries.class);

    private final String subject;
    private final String propertyName;
    private final ResourceResolver resolver;
    private final String query;
    private final int pageSize;

    private String lastKey = "";
    private String lastValue = null;
    private Iterator<Resource> it;
    private int count = 0;
    private int page = 0;
    private Resource next = null;
    private final String[] defaultValue = new String[0];
    private int largestPage = 0;
    private String largestKeyValue = "";
    private int largestKeyCount = 0;
    private int currentKeyCount = 0;

    /**
     * @param subject name of the query, will be used only for logging
     * @param propertyName name of multivalued string property to query on (used for diagnostics)
     * @param resolver resource resolver
     * @param query query string in SQL2 syntax
     * @param pageSize page size (start a new query after page size is exceeded)
     */
    public PagedQueryIterator(String subject, String propertyName, ResourceResolver resolver, String query, int pageSize) {
        this.subject = subject;
        this.propertyName = propertyName;
        this.resolver = resolver;
        this.query = query;
        this.pageSize = pageSize;
        nextPage();
    }

    private void nextPage() {
        count = 0;
        String formattedQuery = String.format(query, queryLiteral(lastKey));
        log.debug("start {} query (page {}): {}", subject, page, formattedQuery);
        long queryStart = System.nanoTime();
        this.it = resolver.findResources(formattedQuery, "JCR-SQL2");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end {} query (page {}); elapsed {}ms", subject, page, TimeUnit.NANOSECONDS.toMillis(queryElapsed));
        page += 1;
    }

    private Resource getNext() throws NoSuchElementException {
        Resource resource = it.next();
        count += 1;
        final String[] values = resource.getValueMap().get(propertyName, defaultValue);

        if (values.length > 0) {
            String value = values[0];
            if (value.compareTo(lastKey) < 0) {
                String message = String.format("unexpected query result in page %d, %s of '%s' despite querying for > '%s'",
                        (page - 1), propertyName, value, lastKey);
                log.error(message);
                throw new QueryImplementationException(message);
            }
            if (lastValue != null && value.compareTo(lastValue) < 0) {
                String message = String.format("unexpected query result in page %d, property name '%s', got '%s', last value was '%s'",
                        (page - 1), propertyName, value, lastValue);
                log.error(message);
                throw new QueryImplementationException(message);
            }

            // keep information about large key counts
            if (value.equals(lastValue)) {
                currentKeyCount += 1;
            } else {
                if (currentKeyCount > largestKeyCount) {
                    largestKeyCount = currentKeyCount + 1;
                    largestKeyValue = lastValue;
                }
                currentKeyCount = 0;
            }

            // start next page?
            if (count > pageSize && !value.equals(lastValue)) {
                updatePageStats();
                lastKey = value;
                nextPage();
                return getNext();
            }
            lastValue = value;
        }

        return resource;
    }

    @Override
    public boolean hasNext() {
        if (next == null) {
            try {
                next = getNext();
            } catch (NoSuchElementException ex) {
                if (currentKeyCount > largestKeyCount) {
                    largestKeyCount = currentKeyCount + 1;
                    largestKeyValue = lastValue;
                }
                updatePageStats();
                // there are no more
                next = null;
            }
        }
        return next != null;
    }

    @Override
    public Resource next() throws NoSuchElementException {
        Resource result = next != null ? next : getNext();
        next = null;
        return result;
    }

    private void updatePageStats() {
        largestPage = Math.max(largestPage, count - 1);
        log.debug("read {} query (page {}); {} entries, last key was: {}, largest page so far: {}", subject, page - 1,
                count, lastKey, largestPage);
    }

    public @NotNull String getStatistics() {
        return String.format(" (max. page size: %d, number of pages: %d)", largestPage, page);
    }

    public @NotNull String getWarning() {
        int warnAt = pageSize * 10;
        if (largestKeyCount > warnAt) {
            return String.format(
                    "Largest number of %s entries with the same 'first' selector exceeds expectation of %d (value '%s' appears %d times)",
                    subject, warnAt, largestKeyValue, largestKeyCount);
        } else {
            return "";
        }
    }

    /**
     * Thrown when the underlying repository misbehaves with respect to sorting on multivalued properties.
     */
    public static class QueryImplementationException extends RuntimeException {
        public QueryImplementationException(String message) {
            super(message);
        }
    }
}
