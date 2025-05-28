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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.mapping.ResourceMapper;
import org.apache.sling.resourceresolver.impl.JcrNamespaceMangler;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.helper.ResourceDecoratorTracker;
import org.apache.sling.resourceresolver.impl.helper.ResourceResolverControl;
import org.apache.sling.resourceresolver.impl.helper.URI;
import org.apache.sling.resourceresolver.impl.helper.URIException;
import org.apache.sling.resourceresolver.impl.params.ParsedParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

public class ResourceMapperImpl implements ResourceMapper {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ResourceResolverImpl resolver;
    private final ResourceDecoratorTracker resourceDecorator;
    private final MapEntriesHandler mapEntries;
    private final Object namespaceMangler;

    public ResourceMapperImpl(
            ResourceResolverImpl resolver,
            ResourceDecoratorTracker resourceDecorator,
            MapEntriesHandler mapEntries,
            Object namespaceMangler) {
        this.resolver = resolver;
        this.resourceDecorator = resourceDecorator;
        this.mapEntries = mapEntries;
        this.namespaceMangler = namespaceMangler;
    }

    @Override
    public String getMapping(String resourcePath) {
        return getMapping(resourcePath, (HttpServletRequest) null);
    }

    @Override
    public String getMapping(String resourcePath, HttpServletRequest request) {
        Collection<String> mappings = getAllMappings(resourcePath, request);
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("No mapping returned by getAllMappings(...)");
        }

        return mappings.iterator().next();
    }

    @Override
    public String getMapping(String resourcePath, javax.servlet.http.HttpServletRequest request) {
        Collection<String> mappings = getAllMappings(resourcePath, request);
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("No mapping returned by getAllMappings(...)");
        }

        return mappings.iterator().next();
    }

    @Override
    public Collection<String> getAllMappings(String resourcePath) {
        return getAllMappings(resourcePath, (HttpServletRequest) null);
    }

    @Override
    public Collection<String> getAllMappings(String resourcePath, HttpServletRequest request) {
        resolver.checkClosed();
        final RequestContext requestContext = new RequestContext(request, resourcePath);
        return getAllMappingsInternal(resourcePath, requestContext);
    }

    @Override
    public Collection<String> getAllMappings(String resourcePath, javax.servlet.http.HttpServletRequest request) {
        resolver.checkClosed();
        final RequestContext requestContext = new RequestContext(request, resourcePath);
        return getAllMappingsInternal(resourcePath, requestContext);
    }

    private Collection<String> getAllMappingsInternal(final String resourcePath, final RequestContext requestContext) {

        // A note on the usage of the 'mappings' variable and the order of the results
        //
        // The API contract of the ResourceMapper does not specify the order in which the elements are returned
        // As an implementation detail however the getMapping method picks the first element of the return value
        // as the 'winner'.
        //
        // Therefore we take care to add the entries in a very particular order, which preserves backwards
        // compatibility with the existing implementation. Previously the order was
        //
        //   resource path → aliases → mapping (with aliases potentially being empty)
        //
        // To ensure we keep the same behaviour but expose all possible mappings, we now have the following
        // flow
        //
        //  resource path → mapping
        //  resource path → aliases
        //  aliases → mappings
        //
        // After all are processed we reverse the order to preserve the logic of the old ResourceResolver.map() method
        // (last
        // found wins) and also make sure that no duplicates are added.
        //
        // There is some room for improvement here by using a data structure that does not need reversing ( ArrayList
        // .add moves the elements every time ) or reversal of duplicates but since we will have a small number of
        // entries ( <= 4 in case of single aliases) the time spent here should be negligible.

        List<String> mappings = new ArrayList<>();

        // 1. parse parameters

        // find a fragment or query
        int fragmentQueryMark = resourcePath.indexOf('#');
        if (fragmentQueryMark < 0) {
            fragmentQueryMark = resourcePath.indexOf('?');
        }

        // cut fragment or query off the resource path
        String mappedPath;
        final String fragmentQuery;
        if (fragmentQueryMark >= 0) {
            fragmentQuery = resourcePath.substring(fragmentQueryMark);
            mappedPath = resourcePath.substring(0, fragmentQueryMark);
            logger.debug(
                    "map: Splitting resource path '{}' into '{}' and '{}'", resourcePath, mappedPath, fragmentQuery);
        } else {
            fragmentQuery = null;
            mappedPath = resourcePath;
        }

        ParsedParameters parsed = new ParsedParameters(mappedPath);

        // 2. load mappings from the resource path
        populateMappingsFromMapEntries(mappings, Collections.singletonList(mappedPath), requestContext);

        // 3. load aliases
        final Resource nonDecoratedResource = resolver.resolveInternal(parsed.getRawPath(), parsed.getParameters());
        if (nonDecoratedResource != null) {
            List<String> aliases = loadAliasesIfApplicable(nonDecoratedResource);
            // ensure that the first declared alias will be returned first
            Collections.reverse(aliases);

            // 4. load mappings for alias
            mappings.addAll(aliases);
            populateMappingsFromMapEntries(mappings, aliases, requestContext);
        }

        // 5. add the requested path itself, if not already populated
        if (!mappedPath.isEmpty() && !mappings.contains(mappedPath)) mappings.add(0, mappedPath);

        // 6. add vanity paths
        List<String> vanityPaths = mapEntries.getVanityPathMappings().getOrDefault(mappedPath, Collections.emptyList());
        // vanity paths are prepended to make sure they get returned last
        mappings.addAll(0, vanityPaths);

        // 7. final effort to make sure we have at least one mapped path
        if (mappings.isEmpty()) {
            mappings.add(nonDecoratedResource != null ? nonDecoratedResource.getPath() : "/");
        }

        // 8. apply context path if needed
        mappings.replaceAll(new ApplyContextPath(requestContext));

        // 9. set back the fragment query if needed
        if (fragmentQuery != null) {
            mappings.replaceAll(path -> path.concat(fragmentQuery));
        }

        if (logger.isDebugEnabled()) {
            mappings.forEach(path -> logger.debug("map: Returning URL {} as mapping for path {}", path, resourcePath));
        }

        Collections.reverse(mappings);

        // TODO: add sort order
        return new LinkedHashSet<>(mappings);
    }

    private List<String> loadAliasesIfApplicable(final Resource nonDecoratedResource) {
        // Invoke the decorator for the resolved resource
        Resource res = resourceDecorator.decorate(nonDecoratedResource);

        // keep, what we might have cut off in internal resolution
        final String resolutionPathInfo = res.getResourceMetadata().getResolutionPathInfo();

        logger.debug("map: Path maps to resource {} with path info {}", res, resolutionPathInfo);

        // find aliases for segments. we can't walk the parent chain
        // since the request session might not have permissions to
        // read all parents SLING-2093
        PathGenerator pathBuilder = new PathGenerator();

        // make sure to append resolutionPathInfo, if present
        pathBuilder.setResolutionPathInfo(resolutionPathInfo);

        resolveAliases(res, pathBuilder);

        // and then we have the mapped path to work on
        List<String> mappedPaths = pathBuilder.generatePaths();
        // specifically exclude the resource's path when generating aliases
        // usually this is removed the invoking method if the path matches an existing
        // resource, but for non-existing ones this does not work
        mappedPaths.remove(nonDecoratedResource.getPath());

        logger.debug("map: Alias mapping resolves to paths {}", mappedPaths);

        return mappedPaths;
    }

    private void resolveAliases(Resource res, PathGenerator pathBuilder) {
        Resource current = res;
        String path = res.getPath();
        if (this.mapEntries.isOptimizeAliasResolutionEnabled()) {
            // this code path avoids any creation of Sling Resource objects
            while (path != null) {
                Collection<String> aliases = Collections.emptyList();
                // read alias only if we can read the resources and it's not a jcr:content leaf
                if (!path.endsWith(ResourceResolverImpl.JCR_CONTENT_LEAF)) {
                    aliases = readAliasesOptimized(path);
                }
                // build the path from the name segments or aliases
                pathBuilder.insertSegment(aliases, ResourceUtil.getName(path));
                path = ResourceUtil.getParent(path);
                if ("/".equals(path)) {
                    path = null;
                }
            }
        } else {
            // while here there Resources are resolved
            while (path != null) {
                List<String> aliases = Collections.emptyList();
                // read alias only if we can read the resources and it's not a jcr:content leaf
                if (current != null && !path.endsWith(ResourceResolverImpl.JCR_CONTENT_LEAF)) {
                    aliases = readAliases(path, current);
                }
                // build the path from the name segments or aliases
                pathBuilder.insertSegment(aliases, ResourceUtil.getName(path));
                path = ResourceUtil.getParent(path);
                if ("/".equals(path)) {
                    path = null;
                } else if (path != null) {
                    current = resolver.resolve(path);
                }
            }
        }
    }

    /**
     * Resolve the aliases for the given resource by directly reading the sling:alias property
     * @param path the path of the resource
     * @param current the resource
     * @return
     */
    private List<String> readAliases(String path, Resource current) {
        logger.debug("map: Optimize Alias Resolution is Disabled");
        String[] aliases =
                ResourceResolverControl.getProperty(current, ResourceResolverImpl.PROP_ALIAS, String[].class);
        if (aliases == null || aliases.length == 0) return Collections.emptyList();
        if (aliases.length == 1) return Collections.singletonList(aliases[0]);
        return Arrays.asList(aliases);
    }

    /**
     * Resolve teh aliases for the given resource by a lookup in the mapEntries structure, avoiding
     * any repository access
     * @param path
     * @return
     */
    private Collection<String> readAliasesOptimized(String path) {
        logger.debug("map: Optimize Alias Resolution is Enabled");
        String parentPath = ResourceUtil.getParent(path);
        if (parentPath == null) {
            return Collections.emptyList();
        }
        String name = ResourceUtil.getName(path);

        return mapEntries.getAliasMap(parentPath).getOrDefault(name, Collections.emptyList());
    }

    /** Populate the mappings list with the mapped paths for the given resource paths or aliases
     * 
     * @param mappings the list of mappings to populate
     * @param resourcePaths the resource paths or aliases for which to populate the mappings
     * @param requestContext the request context or {@code null} if no request context is available
     */
    private void populateMappingsFromMapEntries(
            List<String> mappings, List<String> resourcePaths, final RequestContext requestContext) {
        for (String resourcePath : resourcePaths) {
            String mappedPath = getMappedPath(resourcePath, requestContext);
            if (mappedPath != null) {
                mappings.add(mappedPath);
            }
        }
    }

    private String getMappedPath(final String resourcePath, final RequestContext requestContext) {
        // TODO: collect fallback, but prefer host specific mappings
        String mappedPath = null;
        for (final MapEntry mapEntry : mapEntries.getMapMaps()) {
            final String[] mappedPaths = mapEntry.replace(resourcePath);
            if (mappedPaths != null) {

                logger.debug("map: Match for Entry {}", mapEntry);

                if (!mapEntry.isInternal() && requestContext.hasUri()) {

                    mappedPath = null;

                    for (final String candidate : mappedPaths) {
                        // strip off scheme and host if same as request context
                        if (candidate.startsWith(requestContext.getUri())) {
                            mappedPath = candidate.substring(
                                    requestContext.getUri().length() - 1);
                            // this should take precedence over any other mapping
                            logger.debug(
                                    "map: Found host specific mapping {} resolving to {}", candidate, mappedPath);
                            break;
                        } else if (candidate.startsWith(requestContext.getSchemeWithPrefix())
                                && mappedPath == null) {
                            mappedPath = candidate;
                        }
                    }

                    if (mappedPath == null) {
                        mappedPath = mappedPaths[0];
                    }

                } else {

                    // we can only go with assumptions selecting the first entry
                    mappedPath = mappedPaths[0];
                }

                logger.debug("map: MapEntry {} matches, mapped path is {}", mapEntry, mappedPath);
                return mappedPath;
            }
        }
        return mappedPath;
    }

    private class RequestContext {

        private final String uri;
        private final String schemeWithPrefix;
        private final String contextPath;

        private RequestContext(HttpServletRequest request, String resourcePath) {
            if (request != null) {
                this.uri = MapEntry.getURI(request.getScheme(), request.getServerName(), request.getServerPort(), "/");
                this.schemeWithPrefix = request.getScheme().concat("://");
                logger.debug(
                        "map: Mapping path {} for {} (at least with scheme prefix {})",
                        resourcePath,
                        uri,
                        schemeWithPrefix);
            } else {
                this.uri = null;
                this.schemeWithPrefix = null;
                logger.debug("map: Mapping path {} for default", resourcePath);
            }
            this.contextPath = request == null ? null : request.getContextPath();
        }

        private RequestContext(javax.servlet.http.HttpServletRequest request, String resourcePath) {
            if (request != null) {
                this.uri = MapEntry.getURI(request.getScheme(), request.getServerName(), request.getServerPort(), "/");
                this.schemeWithPrefix = request.getScheme().concat("://");
                logger.debug(
                        "map: Mapping path {} for {} (at least with scheme prefix {})",
                        resourcePath,
                        uri,
                        schemeWithPrefix);
            } else {
                this.uri = null;
                this.schemeWithPrefix = null;
                logger.debug("map: Mapping path {} for default", resourcePath);
            }
            this.contextPath = request == null ? null : request.getContextPath();
        }

        public String getUri() {
            return uri;
        }

        public String getSchemeWithPrefix() {
            return schemeWithPrefix;
        }

        public boolean hasUri() {
            return uri != null && schemeWithPrefix != null;
        }

        public String getContextPath() {
            return this.contextPath;
        }
    }

    private class ApplyContextPath implements UnaryOperator<String> {

        private final RequestContext ctx;

        private ApplyContextPath(RequestContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String apply(String path) {

            String mappedPath = path;

            // [scheme:][//authority][path][?query][#fragment]
            try {
                // use commons-httpclient's URI instead of java.net.URI, as it can
                // actually accept *unescaped* URIs, such as the "mappedPath" and
                // return them in proper escaped form, including the path, via
                // toString()
                final URI uri = new URI(path, false);

                // 1. mangle the namespaces in the path
                path = mangleNamespaces(uri.getPath() == null ? "" : uri.getPath());

                // 2. prepend servlet context path if we have a request
                if (ctx.getContextPath() != null && ctx.getContextPath().length() > 0) {
                    path = ctx.getContextPath().concat(path);
                }
                // update the path part of the URI
                uri.setPath(path);

                mappedPath = uri.toString();
            } catch (final URIException e) {
                logger.warn("map: Unable to mangle namespaces for " + mappedPath + " returning unmangled", e);
            }

            return mappedPath;
        }

        private String mangleNamespaces(String absPath) {
            if (absPath != null && namespaceMangler != null && namespaceMangler instanceof JcrNamespaceMangler) {
                absPath = ((JcrNamespaceMangler) namespaceMangler).mangleNamespaces(resolver, logger, absPath);
            }

            return absPath;
        }
    }
}
