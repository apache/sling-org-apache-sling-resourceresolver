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
package org.apache.sling.resourceresolver.impl.helper;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.collections4.iterators.IteratorChain;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.path.PathBuilder;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo.Mode;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorage;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderStorageProvider;
import org.apache.sling.resourceresolver.impl.providers.stateful.AuthenticatedResourceProvider;
import org.apache.sling.resourceresolver.impl.providers.tree.Node;
import org.apache.sling.resourceresolver.impl.providers.tree.PathTree;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class takes a number of {@link AuthenticatedResourceProvider} objects and
 * exposes it as one such object. Provider appropriate for the given operation
 * is chosen basing on its {@link org.apache.sling.resourceresolver.impl.providers.ResourceProviderInfo#getPath()}
 * (more specific first) and service ranking.
 *
 * Like a resource resolver itself, this class is not thread safe.
 */
public class ResourceResolverControl {

    private static final Logger logger = LoggerFactory.getLogger(ResourceResolverControl.class);

    private static final String[] FORBIDDEN_ATTRIBUTES = new String[] {
        ResourceResolverFactory.PASSWORD, ResourceProvider.AUTH_SERVICE_BUNDLE, ResourceResolverFactory.SUBSERVICE
    };

    /** Is this a resource resolver for an admin? */
    private final boolean isAdmin;

    /** The authentication info. */
    private final Map<String, Object> authenticationInfo;

    /** Resource type resource resolver (admin resolver) */
    private volatile ResourceResolver resourceTypeResourceResolver;

    /** Flag for handling multiple calls to close. */
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private final ResourceProviderStorageProvider resourceProviderTracker;

    private final Map<ResourceProviderHandler, Object> authenticatedProviders;

    /**
     * Create a new resource resolver context.
     * @param isAdmin Admin resource resolver?
     * @param authenticationInfo The auth info
     * @param resourceProviderTracker Tracker for all providers
     */
    public ResourceResolverControl(
            final boolean isAdmin,
            final Map<String, Object> authenticationInfo,
            final ResourceProviderStorageProvider resourceProviderTracker) {
        this.authenticatedProviders = new IdentityHashMap<>();
        this.authenticationInfo = authenticationInfo;
        this.isAdmin = isAdmin;
        this.resourceProviderTracker = resourceProviderTracker;
    }

    /**
     * Is this an admin resource resolver?
     * @return{@code true} if it is an admin resource resolver
     */
    public boolean isAdmin() {
        return isAdmin;
    }

    /**
     * The authentication info
     * @return The map with the auth info
     */
    public Map<String, Object> getAuthenticationInfo() {
        return this.authenticationInfo;
    }

    /**
     * Is this already closed?
     * @return {@code true} if it is closed.
     */
    public boolean isClosed() {
        return this.isClosed.get();
    }

    /**
     * Logs out from all providers.
     */
    private void logout() {
        for (final Map.Entry<ResourceProviderHandler, Object> entry : this.authenticatedProviders.entrySet()) {
            try {
                final ResourceProvider<Object> rp = entry.getKey().getResourceProvider();
                if (rp != null) {
                    rp.logout(entry.getValue());
                } else if (entry.getValue() instanceof Closeable) {
                    ((Closeable) entry.getValue()).close();
                }
            } catch (final Throwable t) {
                // we ignore everything from there to not stop this thread
            }
        }
        this.authenticatedProviders.clear();
    }

    /**
     * Refreshes all refreshable providers as well as the resolver used for resource types.
     * @param context The context
     */
    public void refresh(@NotNull final ResourceResolverContext context) {
        for (final AuthenticatedResourceProvider p :
                context.getProviderManager().getAllUsedRefreshable()) {
            p.refresh();
        }
        if (this.resourceTypeResourceResolver != null) {
            this.resourceTypeResourceResolver.refresh();
        }
    }

    /**
     * Returns {@code true} if all providers are live.
     * @param context The context
     * @return {@code true} if all providers are live
     */
    public boolean isLive(@NotNull final ResourceResolverContext context) {
        for (final AuthenticatedResourceProvider p :
                context.getProviderManager().getAllAuthenticated()) {
            if (!p.isLive()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns parent from the most appropriate resource provider accepting the
     * given children.
     *
     * In some cases the {@link SyntheticResource} can be returned if no
     * resource provider returns parent for this child. See
     * {@link #getResource(ResourceResolverContext, String, Resource, Map, boolean)} for more details
     * @param context The context
     * @param parentPath The parent path
     * @param child The child resource
     * @return The parent or {@code null}
     */
    public Resource getParent(
            @NotNull final ResourceResolverContext context,
            @NotNull final String parentPath,
            @NotNull final Resource child) {
        final AuthenticatedResourceProvider childProvider = getBestMatchingProvider(context, child.getPath());
        final AuthenticatedResourceProvider parentProvider = getBestMatchingProvider(context, parentPath);
        if (parentProvider != null) {
            final Resource parentCandidate;
            if (childProvider == parentProvider) {
                parentCandidate = parentProvider.getParent(child);
            } else {
                parentCandidate = parentProvider.getResource(parentPath, null, null);
            }
            if (parentCandidate != null) {
                return parentCandidate;
            }
        }

        if (isIntermediatePath(parentPath)) {
            return new SyntheticResource(
                    context.getResourceResolver(), parentPath, ResourceProvider.RESOURCE_TYPE_SYNTHETIC);
        }
        return null;
    }

    /**
     * Returns resource from the most appropriate resource provider.
     * <p>
     * If there's no such provider and the path is a part of some resource
     * provider path, then the {@link SyntheticResource} will be returned. For
     * instance, if we have resource provider under
     * {@code /libs/sling/servlet/default/GET.servlet} and no resource provider
     * returns a resource for {@code /libs/sling/servlet/default}, then the
     * {@link SyntheticResource} will be returned to provide a consistent
     * resource tree.
     * </p>
     * <p>
     * The same behaviour occurs in {@link #getParent(ResourceResolverContext, String, Resource)} and
     * {@link #listChildren(ResourceResolverContext, Resource)}.
     * </p>
     * @param context The context
     * @param path Resource path
     * @param parent Parent resource
     * @param parameters Additional parameters
     * @param isResolve Whether this is a resolve or get call
     * @return The resource or {@code null}
     */
    public Resource getResource(
            final ResourceResolverContext context,
            String path,
            Resource parent,
            Map<String, String> parameters,
            boolean isResolve) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') {
            logger.debug("Not absolute {}", path);
            return null; // path must be absolute
        }

        final AuthenticatedResourceProvider provider = this.getBestMatchingProvider(context, path);
        if (provider != null) {
            final Resource resourceCandidate = provider.getResource(path, parent, parameters);
            if (resourceCandidate != null) {
                return resourceCandidate;
            }
        }

        // query: /libs/sling/servlet/default
        // resource Provider: libs/sling/servlet/default/GET.servlet
        // list will match libs, sling, servlet, default
        // and there will be no resource provider at the end
        // SLING-3482 : this is only done for getResource but not resolve
        //              as it is important e.g. for servlet resolution
        //              to get the parent resource for resource traversal.
        if (!isResolve && isIntermediatePath(path)) {
            logger.debug("Resolved Synthetic {}", path);
            return new SyntheticResource(context.getResourceResolver(), path, ResourceProvider.RESOURCE_TYPE_SYNTHETIC);
        }
        logger.debug("Resource null {} ", path);
        return null;
    }

    /**
     * Helper method to check for intermediate paths
     * @param fullPath The full path
     * @return {@code true} if it is an intermediate path
     */
    private boolean isIntermediatePath(final String fullPath) {
        return getResourceProviderStorage().getTree().getNode(fullPath) != null;
    }

    /**
     * This method asks all matching resource providers for the children iterators,
     * merges them, adds {@link SyntheticResource}s (see
     * {@link #getResource(ResourceResolverContext, String, Resource, Map, boolean)} for more details),
     * filters out the duplicates and returns the resulting iterator. All
     * transformations are done lazily, during the {@link Iterator#hasNext()}
     * invocation on the result.
     * @param context The context
     * @param parent The parent resource
     * @return Iterator
     */
    public Iterator<Resource> listChildren(final ResourceResolverContext context, final Resource parent) {
        final String parentPath = parent.getPath();

        // 3 sources are combined: children of the provider which owns 'parent',
        // providers which are directly mounted at a child path,
        // synthetic resources for providers mounted at a lower level

        // children of the 'parent' provider
        Iterator<Resource> realChildren = null;
        final AuthenticatedResourceProvider provider = this.getBestMatchingProvider(context, parentPath);
        if (provider != null) {
            realChildren = provider.listChildren(parent);
        }
        return listChildrenInternal(
                context, getResourceProviderStorage().getTree().getNode(parentPath), parent, realChildren);
    }

    /**
     * Internal method
     * @param context The context
     * @param node The node
     * @param parent The parent
     * @param realChildren The children
     * @return The children
     */
    @SuppressWarnings("unchecked")
    public Iterator<Resource> listChildrenInternal(
            final ResourceResolverContext context,
            final Node<ResourceProviderHandler> node,
            final Resource parent,
            final Iterator<Resource> realChildren) {

        final Set<String> visitedNames = new HashSet<>();

        IteratorChain<Resource> chain = new IteratorChain<>();

        // synthetic and providers are done in one loop
        if (node != null) {
            final List<Resource> syntheticList = new ArrayList<>();
            final List<Resource> providerList = new ArrayList<>();

            for (final Entry<String, Node<ResourceProviderHandler>> entry :
                    node.getChildren().entrySet()) {
                final String name = entry.getKey();
                final ResourceProviderHandler handler = entry.getValue().getValue();
                PathBuilder pathBuilder = new PathBuilder(parent.getPath());
                pathBuilder.append(name);
                final String childPath = pathBuilder.toString();
                if (handler == null) {
                    syntheticList.add(new SyntheticResource(
                            context.getResourceResolver(), childPath, ResourceProvider.RESOURCE_TYPE_SYNTHETIC));
                } else {
                    Resource rsrc = null;
                    try {
                        final AuthenticatedResourceProvider rp =
                                context.getProviderManager().getOrCreateProvider(handler, this);
                        rsrc = rp == null ? null : rp.getResource(childPath, parent, null);
                    } catch (final LoginException ignore) {
                        // ignore
                    }
                    if (rsrc != null) {
                        providerList.add(rsrc);
                    } else {
                        // if there is a child provider underneath, we need to create a synthetic resource
                        // otherwise we need to make sure that no one else is providing this child
                        if (!entry.getValue().getChildren().isEmpty()) {
                            syntheticList.add(new SyntheticResource(
                                    context.getResourceResolver(),
                                    childPath,
                                    ResourceProvider.RESOURCE_TYPE_SYNTHETIC));
                        } else {
                            visitedNames.add(name);
                        }
                    }
                }
            }
            if (!providerList.isEmpty()) {
                chain.addIterator(providerList.iterator());
            }
            if (realChildren != null) {
                chain.addIterator(realChildren);
            }
            if (!syntheticList.isEmpty()) {
                chain.addIterator(syntheticList.iterator());
            }
        } else if (realChildren != null) {
            chain.addIterator(realChildren);
        }
        if (chain.size() == 0) {
            return Collections.EMPTY_LIST.iterator();
        }
        return new UniqueResourceIterator(visitedNames, chain);
    }

    /**
     * Returns the union of all attribute names.
     * @param context The context
     * @return The attribute names
     */
    public Collection<String> getAttributeNames(final ResourceResolverContext context) {
        final Set<String> names = new LinkedHashSet<>();
        for (final AuthenticatedResourceProvider p : context.getProviderManager()
                .getAllBestEffort(getResourceProviderStorage().getAttributableHandlers(), this)) {
            p.getAttributeNames(names);
        }
        if (this.authenticationInfo != null) {
            names.addAll(authenticationInfo.keySet());
        }
        for (final String key : FORBIDDEN_ATTRIBUTES) {
            names.remove(key);
        }
        return names;
    }

    /**
     * Returns the first non-null result of the
     * {@link AuthenticatedResourceProvider#getAttribute(String)} invocation on
     * the providers.
     * @param context The context
     * @param name Attribute name
     * @return Attribute value or {@code null}
     */
    public Object getAttribute(final ResourceResolverContext context, final String name) {
        for (final String key : FORBIDDEN_ATTRIBUTES) {
            if (key.equals(name)) {
                return null;
            }
        }
        for (final AuthenticatedResourceProvider p : context.getProviderManager()
                .getAllBestEffort(getResourceProviderStorage().getAttributableHandlers(), this)) {
            final Object attribute = p.getAttribute(name);
            if (attribute != null) {
                return attribute;
            }
        }
        return this.authenticationInfo != null ? this.authenticationInfo.get(name) : null;
    }

    /**
     * Create a resource.
     *
     * @param context The context
     * @param path The resource path
     * @param properties The resource properties
     * @return The new resource
     * @throws UnsupportedOperationException
     *             If creation is not allowed/possible
     * @throws PersistenceException
     *             If creation fails
     */
    public Resource create(
            final ResourceResolverContext context, final String path, final Map<String, Object> properties)
            throws PersistenceException {
        final AuthenticatedResourceProvider provider = getBestMatchingModifiableProvider(context, path);
        if (provider != null) {
            final Resource creationResultResource = provider.create(context.getResourceResolver(), path, properties);
            if (creationResultResource != null) {
                return creationResultResource;
            }
        }
        throw new UnsupportedOperationException(
                "create '" + ResourceUtil.getName(path) + "' at " + ResourceUtil.getParent(path));
    }

    /**
     * Order resources
     * @param context The context
     * @param parent The parent
     * @param name Resource name
     * @param followingSiblingName Following sibling name
     * @return {@code true} if ordering succeeded
     * @throws UnsupportedOperationException If the operation is not supported#
     * @throws PersistenceException If ordering fails
     * @throws IllegalArgumentException If input parameters are wrong
     * @see ResourceResolver#orderBefore(Resource, String, String)
     */
    public boolean orderBefore(
            @NotNull final ResourceResolverContext context,
            @NotNull final Resource parent,
            @NotNull final String name,
            @Nullable final String followingSiblingName)
            throws UnsupportedOperationException, PersistenceException, IllegalArgumentException {
        final AuthenticatedResourceProvider provider = getBestMatchingModifiableProvider(context, parent.getPath());
        if (provider != null) {
            // make sure all children are from the same provider and both names are valid
            AuthenticatedResourceProvider childProvider =
                    getBestMatchingModifiableProvider(context, createDescendantPath(parent.getPath(), name));
            if (childProvider == null) {
                throw new IllegalArgumentException(
                        "The given name '" + name + "' is not a valid child name in resource " + parent.getPath());
            }
            if (provider != childProvider) {
                throw new UnsupportedOperationException("orderBefore '" + name + "' at " + parent.getPath()
                        + " not supported as children are not provided by the same provider as the parent resource");
            }
            if (followingSiblingName != null) {
                childProvider = getBestMatchingModifiableProvider(
                        context, createDescendantPath(parent.getPath(), followingSiblingName));
                if (childProvider == null) {
                    throw new IllegalArgumentException("The given name '" + followingSiblingName
                            + "' is not a valid child name in resource " + parent.getPath());
                }
                if (provider != childProvider) {
                    throw new UnsupportedOperationException(
                            "orderBefore '" + name + "', '" + followingSiblingName + "' at " + parent.getPath()
                                    + " not supported as sibling child resources are not provided by the same provider");
                }
            }
            return provider.orderBefore(parent, name, followingSiblingName);
        }
        throw new UnsupportedOperationException("orderBefore '" + name + "' at " + parent.getPath());
    }

    /**
     * Create a child path
     * @param path Path
     * @param descendantName Child name
     * @return Path
     */
    public static final String createDescendantPath(String path, String descendantName) {
        if (path.endsWith("/")) {
            return path + descendantName;
        } else {
            return path + "/" + descendantName;
        }
    }

    /**
     * Delete the resource. Iterate over all modifiable ResourceProviders
     * giving each an opportunity to delete the resource if they are able.
     *
     * @param context The context
     * @param resource The resource to delete
     * @throws NullPointerException
     *             if resource is null
     * @throws UnsupportedOperationException
     *             If deletion is not allowed/possible
     * @throws PersistenceException
     *             If deletion fails
     */
    public void delete(final ResourceResolverContext context, final Resource resource) throws PersistenceException {
        final String path = resource.getPath();
        final AuthenticatedResourceProvider provider = getBestMatchingModifiableProvider(context, path);
        if (provider != null) {
            provider.delete(resource);
            return;
        }
        throw new UnsupportedOperationException("delete at '" + path + "'");
    }

    /**
     * Revert changes on all modifiable ResourceProviders.
     * @param context The context
     */
    public void revert(final ResourceResolverContext context) {
        for (final AuthenticatedResourceProvider p :
                context.getProviderManager().getAllUsedModifiable()) {
            p.revert();
        }
    }

    /**
     * Commit changes on all modifiable ResourceProviders.
     * @param context The context
     * @throws PersistenceException If operation fails
     */
    public void commit(final ResourceResolverContext context) throws PersistenceException {
        for (final AuthenticatedResourceProvider p :
                context.getProviderManager().getAllUsedModifiable()) {
            p.commit();
        }
    }

    /**
     * Check if any modifiable ResourceProvider has uncommited changes.
     * @param context The context
     * @return {@code true} if there are uncommited changes
     */
    public boolean hasChanges(final ResourceResolverContext context) {
        for (final AuthenticatedResourceProvider p :
                context.getProviderManager().getAllUsedModifiable()) {
            if (p.hasChanges()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the union of query languages supported by the providers.
     * @param context The context
     * @return The supported query languages
     */
    public String[] getSupportedLanguages(final ResourceResolverContext context) {
        final Set<String> supportedLanguages = new LinkedHashSet<>();
        for (AuthenticatedResourceProvider p : context.getProviderManager()
                .getAllBestEffort(getResourceProviderStorage().getLanguageQueryableHandlers(), this)) {
            supportedLanguages.addAll(Arrays.asList(p.getSupportedLanguages()));
        }
        return supportedLanguages.toArray(new String[supportedLanguages.size()]);
    }

    /**
     * Queries all resource providers and combines the results.
     * @param context The context
     * @param query The query
     * @param language The language
     * @return The result
     */
    public Iterator<Resource> findResources(
            final ResourceResolverContext context, final String query, final String language) {
        final List<AuthenticatedResourceProvider> queryableRP = getQueryableProviders(context, language);
        final List<Iterator<Resource>> iterators = new ArrayList<>(queryableRP.size());
        for (AuthenticatedResourceProvider p : queryableRP) {
            iterators.add(p.findResources(query, language));
        }
        return new ChainedIterator<>(iterators.iterator());
    }

    private List<AuthenticatedResourceProvider> getQueryableProviders(
            final ResourceResolverContext context, final String language) {
        final List<AuthenticatedResourceProvider> queryableProviders = new ArrayList<>();
        for (final AuthenticatedResourceProvider p : context.getProviderManager()
                .getAllBestEffort(getResourceProviderStorage().getLanguageQueryableHandlers(), this)) {
            if (ArrayUtils.contains(p.getSupportedLanguages(), language)) {
                queryableProviders.add(p);
            }
        }
        return queryableProviders;
    }

    /**
     * Queries all resource providers and combines the results.
     * @param context The context
     * @param query The query
     * @param language The language
     * @return The result
     */
    public Iterator<Map<String, Object>> queryResources(
            final ResourceResolverContext context, final String query, final String language) {
        final List<AuthenticatedResourceProvider> queryableRP = getQueryableProviders(context, language);
        final List<Iterator<Map<String, Object>>> iterators = new ArrayList<>(queryableRP.size());
        for (AuthenticatedResourceProvider p : queryableRP) {
            iterators.add(p.queryResources(query, language));
        }
        return new ChainedIterator<>(iterators.iterator());
    }

    /**
     * Returns the first non-null result of the adaptTo() method invoked on the
     * providers.
     * @param context The context
     * @param type The type to adapt to
     * @param <AdapterType> The type to adapt to
     * @return The object or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(final ResourceResolverContext context, Class<AdapterType> type) {
        for (AuthenticatedResourceProvider p : context.getProviderManager()
                .getAllBestEffort(getResourceProviderStorage().getAdaptableHandlers(), this)) {
            final Object adaptee = p.adaptTo(type);
            if (adaptee != null) {
                return (AdapterType) adaptee;
            }
        }
        return null;
    }

    private AuthenticatedResourceProvider checkExistence(
            final ResourceResolverContext context, final String path, final String type) throws PersistenceException {
        final Node<ResourceProviderHandler> node =
                getResourceProviderStorage().getTree().getBestMatchingNode(path);
        if (node == null) {
            throw new PersistenceException(type.concat(" resource does not exist."), null, path, null);
        }
        AuthenticatedResourceProvider provider = null;
        try {
            provider = context.getProviderManager().getOrCreateProvider(node.getValue(), this);
        } catch (LoginException e) {
            // ignore
        }
        if (provider == null) {
            throw new PersistenceException(type.concat(" resource does not exist."), null, path, null);
        }
        final Resource rsrc = provider.getResource(path, null, null);
        if (rsrc == null) {
            throw new PersistenceException(type.concat(" resource does not exist."), null, path, null);
        }
        return provider;
    }

    /**
     * Check source and destination for operations
     * @param context The context
     * @param srcAbsPath The source
     * @param destAbsPath The destination
     * @return The responsible provider or {@code null}
     * @throws PersistenceException If something goes wrong
     */
    public AuthenticatedResourceProvider checkSourceAndDest(
            final ResourceResolverContext context, final String srcAbsPath, final String destAbsPath)
            throws PersistenceException {
        // check source
        final AuthenticatedResourceProvider srcProvider = checkExistence(context, srcAbsPath, "Source");
        // check destination
        final AuthenticatedResourceProvider destProvider = checkExistence(context, destAbsPath, "Destination");

        // check for sub providers of src and dest
        if (srcProvider == destProvider) {
            final PathTree<ResourceProviderHandler> providerTree =
                    getResourceProviderStorage().getTree();
            final boolean sourceHasProvider = hasSubProvider(context, providerTree.getNode(srcAbsPath));
            final boolean destHasProvider = hasSubProvider(context, providerTree.getNode(destAbsPath));
            if (!(sourceHasProvider || destHasProvider)) {
                return srcProvider;
            }
        }
        return null;
    }

    private boolean hasSubProvider(ResourceResolverContext context, Node<ResourceProviderHandler> node) {
        if (node != null) {
            for (final Node<ResourceProviderHandler> childNode :
                    node.getChildren().values()) {
                final ResourceProviderHandler handler = childNode.getValue();
                if (handler != null) {
                    try {
                        context.getProviderManager().getOrCreateProvider(handler, this);
                        return true;
                    } catch (final LoginException ignore) {
                        // ignore
                    }
                }
                if (hasSubProvider(context, childNode)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void copy(
            final ResourceResolverContext context,
            final Resource src,
            final String dstPath,
            final List<Resource> newNodes)
            throws PersistenceException {
        final ValueMap vm = src.getValueMap();
        final String createPath = new PathBuilder(dstPath).append(src.getName()).toString();
        newNodes.add(this.create(context, createPath, vm));
        for (final Resource c : src.getChildren()) {
            copy(context, c, createPath, newNodes);
        }
    }

    /**
     * Tries to find a resource provider accepting both paths and invokes
     * {@link AuthenticatedResourceProvider#copy(String, String)} method on it.
     * Returns false if there's no such provider.
     * @param context The context
     * @param srcAbsPath The source
     * @param destAbsPath The destination
     * @return The copied resources
     * @throws PersistenceException if operation fails
     */
    public Resource copy(final ResourceResolverContext context, final String srcAbsPath, final String destAbsPath)
            throws PersistenceException {
        final AuthenticatedResourceProvider optimizedSourceProvider =
                checkSourceAndDest(context, srcAbsPath, destAbsPath);
        if (optimizedSourceProvider != null && optimizedSourceProvider.copy(srcAbsPath, destAbsPath)) {
            return this.getResource(context, destAbsPath + '/' + ResourceUtil.getName(srcAbsPath), null, null, false);
        }

        final Resource srcResource = this.getResource(context, srcAbsPath, null, null, false);
        final List<Resource> newResources = new ArrayList<>();
        boolean rollback = true;
        try {
            this.copy(context, srcResource, destAbsPath, newResources);
            rollback = false;
            return newResources.get(0);
        } finally {
            if (rollback) {
                for (final Resource rsrc : newResources) {
                    this.delete(context, rsrc);
                }
            }
        }
    }

    /**
     * Tries to find a resource provider accepting both paths and invokes
     * {@link AuthenticatedResourceProvider#move(String, String)} method on it.
     * Returns false if there's no such provider.
     * @param context The context
     * @param srcAbsPath The source
     * @param destAbsPath The destination
     * @return The moved resource
     * @throws PersistenceException if operation fails
     */
    public Resource move(final ResourceResolverContext context, String srcAbsPath, String destAbsPath)
            throws PersistenceException {
        final AuthenticatedResourceProvider optimizedSourceProvider =
                checkSourceAndDest(context, srcAbsPath, destAbsPath);
        if (optimizedSourceProvider != null && optimizedSourceProvider.move(srcAbsPath, destAbsPath)) {
            return this.getResource(context, destAbsPath + '/' + ResourceUtil.getName(srcAbsPath), null, null, false);
        }
        final Resource srcResource = this.getResource(context, srcAbsPath, null, null, false);
        final List<Resource> newResources = new ArrayList<>();
        boolean rollback = true;
        try {
            this.copy(context, srcResource, destAbsPath, newResources);
            this.delete(context, srcResource);
            rollback = false;
            return newResources.get(0);
        } finally {
            if (rollback) {
                for (final Resource rsrc : newResources) {
                    this.delete(context, rsrc);
                }
            }
        }
    }

    /**
     * Get the provider storage
     * @return The provider storage
     */
    public ResourceProviderStorage getResourceProviderStorage() {
        return this.resourceProviderTracker.getResourceProviderStorage();
    }

    /**
     * Get best matching provider
     * @param context The context
     * @param path The path
     * @return The best matching provider or {@code null}
     */
    private @Nullable AuthenticatedResourceProvider getBestMatchingProvider(
            final ResourceResolverContext context, final String path) {
        try {
            final Node<ResourceProviderHandler> node = resourceProviderTracker
                    .getResourceProviderStorage()
                    .getTree()
                    .getBestMatchingNode(path);
            return node == null ? null : context.getProviderManager().getOrCreateProvider(node.getValue(), this);
        } catch (final LoginException le) {
            // ignore
            return null;
        }
    }

    /**
     * Get best modifiable matching provider
     * @param context The context
     * @param path The path
     * @return The modifiable provider or {@code null}
     */
    @Nullable
    AuthenticatedResourceProvider getBestMatchingModifiableProvider(
            final ResourceResolverContext context, final String path) {
        String resourcePath = path;
        do {
            final Node<ResourceProviderHandler> node = resourceProviderTracker
                    .getResourceProviderStorage()
                    .getTree()
                    .getBestMatchingNode(resourcePath);
            if (node.getValue().getInfo().isModifiable()) {
                try {
                    return context.getProviderManager().getOrCreateProvider(node.getValue(), this);
                } catch (final LoginException le) {
                    // ignore
                    return null;
                }
            }
            if (node.getValue().getInfo().getMode() == Mode.PASSTHROUGH) {
                resourcePath = ResourceUtil.getParent(resourcePath);
            } else {
                resourcePath = null;
            }
        } while (resourcePath != null);
        return null;
    }

    /**
     * Close all dynamic resource providers.
     */
    public void close() {
        if (this.isClosed.compareAndSet(false, true)) {
            this.logout();
            if (this.resourceTypeResourceResolver != null) {
                try {
                    this.resourceTypeResourceResolver.close();
                } catch (final Throwable t) {
                    // the resolver (or the underlying provider) might already be terminated (bundle stopped etc.)
                    // so we ignore anything from here
                }
                this.resourceTypeResourceResolver = null;
            }
        }
    }

    private ResourceResolver getResourceTypeResourceResolver(
            final ResourceResolverFactory factory, final ResourceResolver resolver) {
        if (this.isAdmin) {
            return resolver;
        } else {
            if (this.resourceTypeResourceResolver == null) {
                try {
                    // make sure we're getting the resourceTypeResourceResolver on behalf of
                    // the resourceresolver bundle
                    final Bundle bundle = FrameworkUtil.getBundle(ResourceResolverControl.class);
                    final Map<String, Object> authenticationInfo = new HashMap<>();
                    authenticationInfo.put(ResourceProvider.AUTH_SERVICE_BUNDLE, bundle);
                    authenticationInfo.put(ResourceResolverFactory.SUBSERVICE, "hierarchy");
                    this.resourceTypeResourceResolver = factory.getServiceResourceResolver(authenticationInfo);
                } catch (final LoginException e) {
                    throw new IllegalStateException("Failed to create resource-type ResourceResolver", e);
                }
            }
            return this.resourceTypeResourceResolver;
        }
    }

    /**
     * Get the parent resource type
     *
     * @param factory The factory
     * @param resolver The resolver
     * @param resourceType The type
     * @return The parent resource type
     * @see org.apache.sling.api.resource.ResourceResolver#getParentResourceType(java.lang.String)
     */
    public String getParentResourceType(
            final ResourceResolverFactory factory, final ResourceResolver resolver, final String resourceType) {
        // normalize resource type to a path string
        final String rtPath = (resourceType == null ? null : ResourceUtil.resourceTypeToPath(resourceType));
        // get the resource type resource and check its super type
        String resourceSuperType = null;

        if (rtPath != null) {
            ResourceResolver adminResolver = this.getResourceTypeResourceResolver(factory, resolver);
            if (adminResolver != null) {
                final Resource rtResource = adminResolver.getResource(rtPath);
                if (rtResource != null) {
                    resourceSuperType = rtResource.getResourceSuperType();
                }
            }
        }
        return resourceSuperType;
    }

    /**
     * Returns {@link #getProperty(Resource, String, Class) getProperty(res,
     * propName, String.class)}
     *
     * @param res The resource to access the property from
     * @param propName The name of the property to access
     * @return The property as a {@code String} or {@code null} if the property
     *         does not exist or cannot be converted into a {@code String}
     */
    public static String getProperty(final Resource res, final String propName) {
        return getProperty(res, propName, String.class);
    }

    /**
     * Returns the value of the name property of the resource converted to the
     * requested {@code type}.
     * <p>
     * If the resource itself does not have the property, the property is looked
     * up in the {@code jcr:content} child node. This access is done through the
     * same {@code ValueMap} as is used to access the property directly. This
     * generally only works for JCR based {@code ValueMap} instances which
     * provide access to relative path property names. This may not work in non
     * JCR {@code ValueMap}, however in non JCR envs there is usually no
     * "jcr:content" child node anyway
     *
     * @param res The resource to access the property from
     * @param propName The name of the property to access
     * @param type The type into which to convert the property
     * @param <Type> The type
     * @return The property converted to the requested {@code type} or
     *         {@code null} if the property does not exist or cannot be
     *         converted into the requested {@code type}
     */
    public static <Type> Type getProperty(final Resource res, final String propName, final Class<Type> type) {

        // check the property in the resource itself
        final ValueMap props = res.adaptTo(ValueMap.class);
        if (props != null) {
            Type prop = props.get(propName, type);
            if (prop != null) {
                return prop;
            }
            // otherwise, check it in the jcr:content child resource
            // This is a special case checking for JCR based resources
            // we directly use the deep resolution of properties of the
            // JCR value map implementation - this does not work
            // in non JCR environments, however in non JCR envs there
            // is usually no "jcr:content" child node anyway
            prop = props.get("jcr:content/" + propName, type);
            return prop;
        }

        return null;
    }

    /**
     * Register authetnticated provider
     * @param handler The handler
     * @param providerState The state
     */
    public void registerAuthenticatedProvider(
            @NotNull ResourceProviderHandler handler, @Nullable Object providerState) {
        this.authenticatedProviders.put(handler, providerState);
    }

    /**
     * Clear all authenticated providers
     */
    public void clearAuthenticatedProviders() {
        this.authenticatedProviders.clear();
    }
}
