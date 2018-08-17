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
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import javax.servlet.http.HttpServletRequest;

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

public class ResourceMapperImpl implements ResourceMapper {
    
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ResourceResolverImpl resolver;
    private final ResourceDecoratorTracker resourceDecorator;
    private final MapEntriesHandler mapEntries;
    private final boolean optimizedAliasResolutionEnabled;
    private final Object namespaceMangler;
    

    public ResourceMapperImpl(ResourceResolverImpl resolver, ResourceDecoratorTracker resourceDecorator, 
            MapEntriesHandler mapEntries, boolean optimizedAliasResolutionEnabled, Object namespaceMangler) {
        this.resolver = resolver;
        this.resourceDecorator = resourceDecorator;
        this.mapEntries = mapEntries;
        this.optimizedAliasResolutionEnabled = optimizedAliasResolutionEnabled;
        this.namespaceMangler = namespaceMangler;
    }

    @Override
    public String getMapping(String resourcePath) {
        return getMapping(resourcePath, null);
    }

    @Override
    public String getMapping(String resourcePath, HttpServletRequest request) {
        
        Collection<String> mappings = getAllMappings(resourcePath, request);
        if ( mappings.isEmpty() )
            return null;
        
        return mappings.iterator().next();
    }

    @Override
    public Collection<String> getAllMappings(String resourcePath) {
        return getAllMappings(resourcePath, null);
    }

    @Override
    public Collection<String> getAllMappings(String resourcePath, HttpServletRequest request) {
        
        resolver.checkClosed();
        
        // A note on the usage of the 'mappings' variable and the order of the results
        //
        // The API contract of the ResourceMapper does not specify the order in which the elements are returned
        // As an implementation detail however the getMapping method picks the first element of the return value
        // as the 'winner'.
        // 
        // Therefore we take care to add the entries in a very particular order, which preserves backwards 
        // compatibility with the existing implementation. Previously the order was
        //
        //   resource path → alias → mapping (with alias potentially being null)
        //
        // To ensure we keep the same behaviour but expose all possible mappings, we now have the following
        // flow
        // 
        //  resource path → mapping
        //  resource path → alias
        //  alias → mapping
        //
        // After all are processed we reverse the order to preserve the logic of the old ResourceResolver.map() method (last
        // found wins) and also make sure that no duplicates are added.
        //
        // There is some room for improvement here by using a data structure that does not need reversing ( ArrayList
        // .add moves the elements every time ) or reversal of duplicates but since we will have a small number of 
        // entries ( <= 4 ) the time spent here should be negligible.
        
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
            logger.debug("map: Splitting resource path '{}' into '{}' and '{}'", new Object[] { resourcePath, mappedPath,
                    fragmentQuery });
        } else {
            fragmentQuery = null;
            mappedPath = resourcePath;
        }

        final RequestContext requestContext = new RequestContext(request, resourcePath);
        ParsedParameters parsed = new ParsedParameters(mappedPath);
        
        // 2. add the requested path itself
        mappings.add(mappedPath);

        // 3. load mappings from the resource path
        populateMappingsFromMapEntries(mappings, mappedPath, requestContext);
        
        
        // 4. load aliases
        final Resource nonDecoratedResource = resolver.resolveInternal(parsed.getRawPath(), parsed.getParameters());
        if (nonDecoratedResource != null) {
            String alias = loadAliasIfApplicable(nonDecoratedResource);
            
            // 5. load mappings for alias
            if ( alias != null )
                mappings.add(alias);
            populateMappingsFromMapEntries(mappings, alias, requestContext);
        }

        // 6. apply context path if needed
        mappings.replaceAll(new ApplyContextPath(request));
       
        // 7. set back the fragment query if needed
        if ( fragmentQuery != null ) {
            mappings.replaceAll(new UnaryOperator<String>() {
                @Override
                public String apply(String mappedPath) {
                        return mappedPath.concat(fragmentQuery);
                }
            });
        }

        mappings.forEach( path -> {
            logger.debug("map: Returning URL {} as mapping for path {}", path, resourcePath);    
        });
        
        Collections.reverse(mappings);
        
        return new LinkedHashSet<>(mappings);
    }

    private String loadAliasIfApplicable(final Resource nonDecoratedResource) {
        //Invoke the decorator for the resolved resource
        Resource res = resourceDecorator.decorate(nonDecoratedResource);

        // keep, what we might have cut off in internal resolution
        final String resolutionPathInfo = res.getResourceMetadata().getResolutionPathInfo();

        logger.debug("map: Path maps to resource {} with path info {}", res, resolutionPathInfo);

        // find aliases for segments. we can't walk the parent chain
        // since the request session might not have permissions to
        // read all parents SLING-2093
        final LinkedList<String> names = new LinkedList<>();

        Resource current = res;
        String path = res.getPath();
        while (path != null) {
            String alias = null;
            if (current != null && !path.endsWith(ResourceResolverImpl.JCR_CONTENT_LEAF)) {
                if (optimizedAliasResolutionEnabled) {
                    logger.debug("map: Optimize Alias Resolution is Enabled");
                    String parentPath = ResourceUtil.getParent(path);
                    if (parentPath != null) {
                        final Map<String, String> aliases = mapEntries.getAliasMap(parentPath);
                        if (aliases!= null && aliases.containsValue(current.getName())) {
                            for (String key:aliases.keySet()) {
                                if (current.getName().equals(aliases.get(key))) {
                                    alias = key;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    logger.debug("map: Optimize Alias Resolution is Disabled");
                    alias = ResourceResolverControl.getProperty(current, ResourceResolverImpl.PROP_ALIAS);
                }
            }
            if (alias == null || alias.length() == 0) {
                alias = ResourceUtil.getName(path);
            }
            names.add(alias);
            path = ResourceUtil.getParent(path);
            if ("/".equals(path)) {
                path = null;
            } else if (path != null) {
                current = res.getResourceResolver().resolve(path);
            }
        }

        // build path from segment names
        final StringBuilder buf = new StringBuilder();

        // construct the path from the segments (or root if none)
        if (names.isEmpty()) {
            buf.append('/');
        } else {
            while (!names.isEmpty()) {
                buf.append('/');
                buf.append(names.removeLast());
            }
        }

        // reappend the resolutionPathInfo
        if (resolutionPathInfo != null) {
            buf.append(resolutionPathInfo);
        }

        // and then we have the mapped path to work on
        String mappedPath = buf.toString();

        logger.debug("map: Alias mapping resolves to path {}", mappedPath);
        
        return mappedPath;
    }

    private void populateMappingsFromMapEntries(List<String> mappings, String mappedPath,
            final RequestContext requestContext) {
        boolean mappedPathIsUrl = false;
        for (final MapEntry mapEntry : mapEntries.getMapMaps()) {
            final String[] mappedPaths = mapEntry.replace(mappedPath);
            if (mappedPaths != null) {

                logger.debug("map: Match for Entry {}", mapEntry);

                mappedPathIsUrl = !mapEntry.isInternal();

                if (mappedPathIsUrl && requestContext.hasUri() ) {

                    mappedPath = null;

                    for (final String candidate : mappedPaths) {
                        if (candidate.startsWith(requestContext.getUri())) {
                            mappedPath = candidate.substring(requestContext.getUri().length() - 1);
                            mappedPathIsUrl = false;
                            logger.debug("map: Found host specific mapping {} resolving to {}", candidate, mappedPath);
                            break;
                        } else if (candidate.startsWith(requestContext.getSchemeWithPrefix()) && mappedPath == null) {
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
                
                mappings.add(mappedPath);

                break;
            }
        }
    }
    
    private String mangleNamespaces(String absPath) {
        if ( absPath != null && namespaceMangler != null && namespaceMangler instanceof JcrNamespaceMangler ) {
            absPath = ((JcrNamespaceMangler) namespaceMangler).mangleNamespaces(resolver, logger, absPath);
        }

        return absPath;
    }
    
    private class RequestContext {
        
        private final String uri;
        private final String schemeWithPrefix;
        
        private RequestContext(HttpServletRequest request, String resourcePath) {
            if ( request != null ) {
                this.uri = MapEntry.getURI(request.getScheme(), request.getServerName(), request.getServerPort(), "/");
                this.schemeWithPrefix = request.getScheme().concat("://");
                logger.debug("map: Mapping path {} for {} (at least with scheme prefix {})", new Object[] { resourcePath,
                        uri, schemeWithPrefix });
            } else {
                this.uri = null;
                this.schemeWithPrefix = null;
                logger.debug("map: Mapping path {} for default", resourcePath);
            }
            
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
    }
    
    private class ApplyContextPath implements UnaryOperator<String> {
        
        private final HttpServletRequest req;
        
        private ApplyContextPath(HttpServletRequest req) {
            this.req = req;
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
                path = mangleNamespaces(uri.getPath());

                // 2. prepend servlet context path if we have a request
                if (req != null && req.getContextPath() != null && req.getContextPath().length() > 0) {
                    path = req.getContextPath().concat(path);
                }
                // update the path part of the URI
                uri.setPath(path);

                mappedPath = uri.toString();
            } catch (final URIException e) {
                logger.warn("map: Unable to mangle namespaces for " + mappedPath + " returning unmangled", e);
            }

            return mappedPath;
        }
    }
}
