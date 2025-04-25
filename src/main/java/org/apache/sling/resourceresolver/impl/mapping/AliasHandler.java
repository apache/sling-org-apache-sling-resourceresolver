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
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All things related to the handling of aliases.
 */
class AliasHandler {

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_CONTENT_PREFIX = JCR_CONTENT + "/";

    private static final String JCR_CONTENT_SUFFIX = "/" + JCR_CONTENT;

    private MapConfigurationProvider factory;

    private final ReentrantLock initializing;

    private final Logger log = LoggerFactory.getLogger(AliasHandler.class);

    // keep track of some defunct aliases for diagnostics (thus size-limited)
    private static final int MAX_REPORT_DEFUNCT_ALIASES = 50;

    private final Runnable doUpdateConfiguration;
    private final Runnable sendChangeEvent;

    /**
     * The key of the map is the parent path, while the value is a map with the
     * resource name as key and the actual aliases as values
     */
    @NotNull Map<String, Map<String, Collection<String>>> aliasMapsMap;

    boolean mapIsInitialized = false;

    final AtomicLong aliasResourcesOnStartup;
    final AtomicLong detectedConflictingAliases;
    final AtomicLong detectedInvalidAliases;

    public AliasHandler(
            MapConfigurationProvider factory,
            ReentrantLock initializing,
            Runnable doUpdateConfiguration,
            Runnable sendChangeEvent) {
        this.factory = factory;
        this.initializing = initializing;
        this.aliasMapsMap = new ConcurrentHashMap<>();
        this.doUpdateConfiguration = doUpdateConfiguration;
        this.sendChangeEvent = sendChangeEvent;

        this.aliasResourcesOnStartup = new AtomicLong(0);
        this.detectedConflictingAliases = new AtomicLong(0);
        this.detectedInvalidAliases = new AtomicLong(0);
    }

    public void dispose() {
        this.factory = null;
    }

    /**
     * Actual initializer. Guards itself against concurrent use by using a
     * ReentrantLock. Does nothing if the resource resolver has already been
     * null-ed.
     */
    protected void initializeAliases() {

        this.initializing.lock();
        try {
            this.mapIsInitialized = false;

            // already disposed?
            if (this.factory == null) {
                return;
            }

            List<String> conflictingAliases = new ArrayList<>();
            List<String> invalidAliases = new ArrayList<>();

            // optimization made in SLING-2521
            if (this.factory.isOptimizeAliasResolutionEnabled()) {
                try {
                    this.aliasMapsMap = this.loadAliases(conflictingAliases, invalidAliases);
                    this.mapIsInitialized = true;

                    // warn if there are more than a few defunct aliases
                    if (conflictingAliases.size() >= MAX_REPORT_DEFUNCT_ALIASES) {
                        log.warn(
                                "There are {} conflicting aliases; excerpt: {}",
                                conflictingAliases.size(),
                                conflictingAliases);
                    } else if (!conflictingAliases.isEmpty()) {
                        log.warn("There are {} conflicting aliases: {}", conflictingAliases.size(), conflictingAliases);
                    }

                    if (invalidAliases.size() >= MAX_REPORT_DEFUNCT_ALIASES) {
                        log.warn("There are {} invalid aliases; excerpt: {}", invalidAliases.size(), invalidAliases);
                    } else if (!invalidAliases.isEmpty()) {
                        log.warn("There are {} invalid aliases: {}", invalidAliases.size(), invalidAliases);
                    }
                } catch (final Exception e) {
                    this.aliasMapsMap = new ConcurrentHashMap<>();
                    logDisableAliasOptimization(e);
                }
            }

            doUpdateConfiguration.run();
            sendChangeEvent.run();
        } finally {

            this.initializing.unlock();
        }
    }

    boolean usesCache() {
        return this.mapIsInitialized;
    }

    boolean doAddAlias(final Resource resource) {
        return loadAlias(resource, this.aliasMapsMap, null, null);
    }

    /**
     * Remove all aliases for the content path
     *
     * @param contentPath The content path
     * @param path        Optional sub path of the vanity path
     * @return {@code true} if a change happened
     */
    boolean removeAlias(
            ResourceResolver resolver, final String contentPath, final String path, final Runnable notifyOfChange) {

        final String resourcePath = computeResourcePath(contentPath, path);

        if (resourcePath == null) {
            // early exit
            return false;
        }

        this.initializing.lock();

        try {
            final Map<String, Collection<String>> aliasMapEntry = aliasMapsMap.get(contentPath);
            if (aliasMapEntry != null) {
                notifyOfChange.run();
                handleAliasRemoval(resolver, contentPath, resourcePath, aliasMapEntry);
            }
            return aliasMapEntry != null;
        } finally {
            this.initializing.unlock();
        }
    }

    // if path is specified we first need to find out if it is
    // a direct child of content path but not jcr:content, or a jcr:content child of a direct child
    // otherwise we can discard the event
    private static @Nullable String computeResourcePath(@NotNull String contentPath, @Nullable String path) {
        String resourcePath = null;

        if (path != null && path.length() > contentPath.length()) {
            // path -> (contentPath + subPath)
            final String subPath = path.substring(contentPath.length() + 1);
            final int firstSlash = subPath.indexOf('/');

            if (firstSlash == -1) {
                // no slash in subPath
                if (!subPath.equals(JCR_CONTENT)) {
                    resourcePath = path;
                }
            } else if (subPath.lastIndexOf('/') == firstSlash) {
                // exactly one slash in subPath
                if (!subPath.startsWith(JCR_CONTENT_PREFIX) && subPath.endsWith(JCR_CONTENT_SUFFIX)) {
                    resourcePath = ResourceUtil.getParent(path);
                }
            }
        } else {
            resourcePath = contentPath;
        }

        return resourcePath;
    }

    private void handleAliasRemoval(
            @Nullable ResourceResolver resolver,
            @NotNull String contentPath,
            @NotNull String resourcePath,
            @NotNull Map<String, Collection<String>> aliasMapEntry) {
        String prefix = contentPath.endsWith("/") ? contentPath : contentPath + "/";
        if (aliasMapEntry.entrySet().removeIf(e -> (prefix + e.getKey()).startsWith(resourcePath))
                && (aliasMapEntry.isEmpty())) {
            this.aliasMapsMap.remove(contentPath);
        }

        Resource containingResource = resolver != null ? resolver.getResource(resourcePath) : null;

        if (containingResource != null) {
            if (containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                doAddAlias(containingResource);
            }
            final Resource child = containingResource.getChild(JCR_CONTENT);
            if (child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                doAddAlias(child);
            }
        }
    }

    /**
     * Update alias from a resource
     *
     * @param resource The resource
     * @return {@code true} if any change
     */
    boolean doUpdateAlias(final Resource resource) {

        // resource containing the alias
        final Resource containingResource = getResourceToBeAliased(resource);

        if (containingResource != null) {
            final String containingResourceName = containingResource.getName();
            final String parentPath = ResourceUtil.getParent(containingResource.getPath());

            final Map<String, Collection<String>> aliasMapEntry =
                    parentPath == null ? null : aliasMapsMap.get(parentPath);
            if (aliasMapEntry != null) {
                aliasMapEntry.remove(containingResourceName);
                if (aliasMapEntry.isEmpty()) {
                    this.aliasMapsMap.remove(parentPath);
                }
            }

            boolean changed = aliasMapEntry != null;

            if (containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                changed |= doAddAlias(containingResource);
            }
            final Resource child = containingResource.getChild(JCR_CONTENT);
            if (child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                changed |= doAddAlias(child);
            }

            return changed;
        } else {
            log.warn("containingResource is null for alias on {}, skipping.", resource.getPath());
        }

        return false;
    }

    public @NotNull Map<String, Collection<String>> getAliasMap(final String parentPath) {
        Map<String, Collection<String>> aliasMapForParent = aliasMapsMap.get(parentPath);
        return aliasMapForParent != null ? aliasMapForParent : Collections.emptyMap();
    }

    /**
     * Load aliases - Search for all nodes (except under /jcr:system) below
     * configured alias locations having the sling:alias property
     */
    private Map<String, Map<String, Collection<String>>> loadAliases(
            List<String> conflictingAliases, List<String> invalidAliases) {

        final Map<String, Map<String, Collection<String>>> map = new ConcurrentHashMap<>();

        try (final ResourceResolver resolver =
                factory.getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"))) {
            final String baseQueryString = generateAliasQuery();

            Iterator<Resource> it;
            try {
                final String queryStringWithSort =
                        baseQueryString + " AND FIRST([sling:alias]) >= '%s' ORDER BY FIRST([sling:alias])";
                it = new PagedQueryIterator("alias", "sling:alias", resolver, queryStringWithSort, 2000);
            } catch (QuerySyntaxException ex) {
                log.debug("sort with first() not supported, falling back to base query", ex);
                it = queryUnpaged(baseQueryString, resolver);
            } catch (UnsupportedOperationException ex) {
                log.debug("query failed as unsupported, retrying without paging/sorting", ex);
                it = queryUnpaged(baseQueryString, resolver);
            }

            log.debug("alias initialization - start");
            long count = 0;
            long processStart = System.nanoTime();
            while (it.hasNext()) {
                count += 1;
                loadAlias(it.next(), map, conflictingAliases, invalidAliases);
            }
            long processElapsed = System.nanoTime() - processStart;
            long resourcePerSecond = (count * TimeUnit.SECONDS.toNanos(1) / (processElapsed == 0 ? 1 : processElapsed));

            String diagnostics = "";
            if (it instanceof PagedQueryIterator) {
                PagedQueryIterator pit = (PagedQueryIterator) it;

                if (!pit.getWarning().isEmpty()) {
                    log.warn(pit.getWarning());
                }

                diagnostics = pit.getStatistics();
            }

            log.info(
                    "alias initialization - completed, processed {} resources with sling:alias properties in {}ms (~{} resource/s){}",
                    count,
                    TimeUnit.NANOSECONDS.toMillis(processElapsed),
                    resourcePerSecond,
                    diagnostics);

            this.aliasResourcesOnStartup.set(count);
        } catch (LoginException ex) {
            log.error("Alias init failed", ex);
        }

        return map;
    }

    /*
     * generate alias query based on configured alias locations
     */
    private String generateAliasQuery() {
        final Set<String> allowedLocations = this.factory.getAllowedAliasLocations();

        StringBuilder baseQuery = new StringBuilder("SELECT [sling:alias] FROM [nt:base] WHERE");

        if (allowedLocations.isEmpty()) {
            baseQuery.append(" ").append(QueryBuildHelper.excludeSystemPath());
        } else {
            Iterator<String> pathIterator = allowedLocations.iterator();
            baseQuery.append(" (");
            String sep = "";
            while (pathIterator.hasNext()) {
                String prefix = pathIterator.next();
                baseQuery
                        .append(sep)
                        .append("isdescendantnode('")
                        .append(QueryBuildHelper.escapeString(prefix))
                        .append("')");
                sep = " OR ";
            }
            baseQuery.append(")");
        }

        baseQuery.append(" AND [sling:alias] IS NOT NULL");
        return baseQuery.toString();
    }

    /**
     * Load alias given a resource
     */
    private boolean loadAlias(
            final Resource resource,
            Map<String, Map<String, Collection<String>>> map,
            List<String> conflictingAliases,
            List<String> invalidAliases) {

        // resource containing the alias
        final Resource containingResource = getResourceToBeAliased(resource);

        if (containingResource == null) {
            log.warn("containingResource is null for alias on {}, skipping.", resource.getPath());
            return false;
        } else {
            final Resource parent = containingResource.getParent();

            if (parent == null) {
                log.warn(
                        "{} is null for alias on {}, skipping.",
                        containingResource == resource ? "parent" : "grandparent",
                        resource.getPath());
                return false;
            } else {
                final String[] aliasArray = resource.getValueMap().get(ResourceResolverImpl.PROP_ALIAS, String[].class);
                if (aliasArray == null) {
                    return false;
                } else {
                    return loadAliasFromArray(
                            aliasArray,
                            map,
                            conflictingAliases,
                            invalidAliases,
                            containingResource.getName(),
                            parent.getPath());
                }
            }
        }
    }

    /**
     * Load alias given an alias array, return success flag.
     */
    private boolean loadAliasFromArray(
            final String[] aliasArray,
            Map<String, Map<String, Collection<String>>> map,
            List<String> conflictingAliases,
            List<String> invalidAliases,
            final String resourceName,
            final String parentPath) {

        boolean hasAlias = false;

        log.debug("Found alias, total size {}", aliasArray.length);

        // the order matters here, the first alias in the array must come first
        for (final String alias : aliasArray) {
            if (isAliasInvalid(alias)) {
                long invalids = detectedInvalidAliases.incrementAndGet();
                log.warn(
                        "Encountered invalid alias '{}' under parent path '{}' (total so far: {}). Refusing to use it.",
                        alias,
                        parentPath,
                        invalids);
                if (invalidAliases != null && invalids < MAX_REPORT_DEFUNCT_ALIASES) {
                    invalidAliases.add((String.format("'%s'/'%s'", parentPath, alias)));
                }
            } else {
                Map<String, Collection<String>> parentMap =
                        map.computeIfAbsent(parentPath, key -> new ConcurrentHashMap<>());
                Optional<String> siblingResourceNameWithDuplicateAlias = parentMap.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(resourceName)) // ignore entry for the current resource
                        .filter(entry -> entry.getValue().contains(alias))
                        .findFirst()
                        .map(Map.Entry::getKey);
                if (siblingResourceNameWithDuplicateAlias.isPresent()) {
                    long conflicting = detectedConflictingAliases.incrementAndGet();
                    log.warn(
                            "Encountered duplicate alias '{}' under parent path '{}'. Refusing to replace current target '{}' with '{}' (total duplicated aliases so far: {}).",
                            alias,
                            parentPath,
                            siblingResourceNameWithDuplicateAlias.get(),
                            resourceName,
                            conflicting);
                    if (conflictingAliases != null && conflicting < MAX_REPORT_DEFUNCT_ALIASES) {
                        conflictingAliases.add((String.format(
                                "'%s': '%s'/'%s' vs '%s'/'%s'",
                                parentPath, resourceName, alias, siblingResourceNameWithDuplicateAlias.get(), alias)));
                    }
                } else {
                    Collection<String> existingAliases =
                            parentMap.computeIfAbsent(resourceName, name -> new CopyOnWriteArrayList<>());
                    existingAliases.add(alias);
                    hasAlias = true;
                }
            }
        }

        return hasAlias;
    }

    /**
     * Given a resource, check whether the name is "jcr:content", in which case return the parent resource
     *
     * @param resource resource to check
     * @return parent of jcr:content resource (may be null), otherwise the resource itself
     */
    @Nullable
    private Resource getResourceToBeAliased(Resource resource) {
        if (JCR_CONTENT.equals(resource.getName())) {
            return resource.getParent();
        } else {
            return resource;
        }
    }

    /**
     * Check alias syntax
     */
    private boolean isAliasInvalid(String alias) {
        boolean invalid = alias.equals("..") || alias.equals(".") || alias.isEmpty();
        if (!invalid) {
            for (final char c : alias.toCharArray()) {
                // invalid if / or # or a ?
                if (c == '/' || c == '#' || c == '?') {
                    invalid = true;
                    break;
                }
            }
        }
        return invalid;
    }

    private Iterator<Resource> queryUnpaged(String query, ResourceResolver resolver) {
        log.debug("start alias query: {}", query);
        long queryStart = System.nanoTime();
        final Iterator<Resource> it = resolver.findResources(query, "JCR-SQL2");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end alias query; elapsed {}ms", TimeUnit.NANOSECONDS.toMillis(queryElapsed));
        return it;
    }

    private final AtomicLong lastTimeLogged = new AtomicLong(-1);

    void logDisableAliasOptimization(final Exception e) {
        if (e != null) {
            log.error(
                    "Unexpected problem during initialization of optimize alias resolution. Therefore disabling optimize alias resolution. Please fix the problem.",
                    e);
        } else {
            final long now = System.currentTimeMillis();
            long LOGGING_ERROR_PERIOD = TimeUnit.MINUTES.toMillis(5);
            if (now - lastTimeLogged.getAndSet(now) > LOGGING_ERROR_PERIOD) {
                log.error(
                        "A problem occurred during initialization of optimize alias resolution. Optimize alias resolution is disabled. Check the logs for the reported problem.");
            }
        }
    }
}
