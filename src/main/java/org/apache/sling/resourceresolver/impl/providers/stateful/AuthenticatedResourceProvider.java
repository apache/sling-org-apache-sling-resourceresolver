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
package org.apache.sling.resourceresolver.impl.providers.stateful;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.security.AccessSecurityException;
import org.apache.sling.api.security.ResourceAccessSecurity;
import org.apache.sling.resourceresolver.impl.ResourceAccessSecurityTracker;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.helper.AbstractIterator;
import org.apache.sling.resourceresolver.impl.providers.ResourceProviderHandler;
import org.apache.sling.spi.resource.provider.QueryLanguageProvider;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This {@link AuthenticatedResourceProvider} implementation keeps a resource
 * provider and the authentication information (through the {@link ResolveContext}).
 *
 * The methods are similar to those of {@link ResourceProvider}.
 */
public class AuthenticatedResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(ResourceResolverImpl.class);

    public static final AuthenticatedResourceProvider UNAUTHENTICATED_PROVIDER = new AuthenticatedResourceProvider();

    private final ResourceProviderHandler providerHandler;

    private final ResolveContext<Object> resolveContext;

    private final ResourceAccessSecurityTracker tracker;

    private final boolean useRAS;

    /**
     * Constructor
     * @param providerHandler the providerHandler
     * @param useRAS useRAS
     * @param resolveContext resolveContext
     * @param tracker tracker
     */
    public AuthenticatedResourceProvider(
            @NotNull final ResourceProviderHandler providerHandler,
            final boolean useRAS,
            @NotNull final ResolveContext<Object> resolveContext,
            @NotNull final ResourceAccessSecurityTracker tracker) {
        this.providerHandler = providerHandler;
        this.resolveContext = resolveContext;
        this.tracker = tracker;
        this.useRAS = useRAS;
    }

    private AuthenticatedResourceProvider() {
        this.providerHandler = null;
        this.resolveContext = null;
        this.tracker = null;
        this.useRAS = false;
    }

    /**
     * Get the resolve context.
     * @return The resolve context
     */
    public @NotNull ResolveContext<Object> getResolveContext() {
        return this.resolveContext;
    }

    /**
     * @see ResourceProvider#refresh(ResolveContext)
     */
    public void refresh() {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            rp.refresh(this.resolveContext);
        }
    }

    /**
     * Check if the provider is live
     * @return {@code true} If live
     * @see ResourceProvider#isLive(ResolveContext)
     */
    public boolean isLive() {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.isLive(this.resolveContext);
        }
        return false;
    }

    /**
     * Get the parent resource
     * @param child The child
     * @return The parent
     * @see ResourceProvider#getParent(ResolveContext, Resource)
     */
    public Resource getParent(final Resource child) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return wrapResource(rp.getParent(this.resolveContext, child));
        }
        return null;
    }

    /**
     * @see ResourceProvider#getResource(ResolveContext, String, ResourceContext, Resource)
     * @param path the path
     * @param parent parent
     * @param parameters parameters
     * @return the resource
     */
    public Resource getResource(final String path, final Resource parent, final Map<String, String> parameters) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp == null) {
            return null;
        }
        final ResourceContext resourceContext;
        if (parameters != null) {
            resourceContext = new ResourceContext() {

                @Override
                public Map<String, String> getResolveParameters() {
                    return parameters;
                }
            };
        } else {
            resourceContext = ResourceContext.EMPTY_CONTEXT;
        }
        return wrapResource(rp.getResource(this.resolveContext, path, resourceContext, parent));
    }

    /**
     * @see ResourceProvider#listChildren(ResolveContext, Resource)
     * @param parent parent
     * @return the iterator
     */
    public Iterator<Resource> listChildren(final Resource parent) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return wrapIterator(rp.listChildren(this.resolveContext, parent));
        }
        return null;
    }

    /**
     * @see ResourceProvider#getAttributeNames(ResolveContext)
     * @param attributeNames attributeNames
     */
    public void getAttributeNames(final Set<String> attributeNames) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            Collection<String> rpAttributeNames = rp.getAttributeNames(this.resolveContext);
            if (rpAttributeNames != null) {
                attributeNames.addAll(rpAttributeNames);
            }
        }
    }

    /**
     * @see ResourceProvider#getAttribute(ResolveContext, String)
     * @param name name
     * @return the attribute
     */
    public Object getAttribute(final String name) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.getAttribute(this.resolveContext, name);
        }
        return null;
    }

    /**
     * @see ResourceProvider#create(ResolveContext, String, Map)
     * @param resolver the resolver
     * @param path path
     * @param properties properties
     * @return the resource
     * @throws PersistenceException in case of problems
     */
    public Resource create(final ResourceResolver resolver, final String path, final Map<String, Object> properties)
            throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null && this.canCreate(resolver, path)) {
            return rp.create(this.resolveContext, path, properties);
        }
        return null;
    }

    /**
     * @see ResourceProvider#orderBefore(ResolveContext, Resource, String, String)
     * @param parent parent
     * @param name name
     * @param followingSiblingName followingSiblingName
     * @return true if the order was changed, false if the order was correct already before
     * @throws PersistenceException in case of problems
     */
    public boolean orderBefore(
            final @NotNull Resource parent, final @NotNull String name, final @Nullable String followingSiblingName)
            throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null && this.canOrderChildren(parent)) {
            return rp.orderBefore(this.resolveContext, parent, name, followingSiblingName);
        } else {
            throw new PersistenceException("Unable to order child resources of " + parent.getPath());
        }
    }

    /**
     * @see ResourceProvider#delete(ResolveContext, Resource)
     * @param resource resource
     * @throws PersistenceException in case of problems
     */
    public void delete(final Resource resource) throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null && this.canDelete(resource)) {
            rp.delete(this.resolveContext, resource);
        } else {
            throw new PersistenceException("Unable to delete resource " + resource.getPath());
        }
    }

    /**
     * @see ResourceProvider#revert(ResolveContext)
     */
    public void revert() {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            rp.revert(this.resolveContext);
        }
    }

    /**
     * @see ResourceProvider#commit(ResolveContext)
     * @throws PersistenceException in case of problems
     */
    public void commit() throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            rp.commit(this.resolveContext);
        }
    }

    /**
     * @see ResourceProvider#hasChanges(ResolveContext)
     * @return true if there are transient changes
     */
    public boolean hasChanges() {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.hasChanges(this.resolveContext);
        }
        return false;
    }

    /**
     * @see ResourceProvider#getQueryLanguageProvider()
     * @return the QueryLanguageProvider
     */
    private QueryLanguageProvider<Object> getQueryLanguageProvider() {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.getQueryLanguageProvider();
        }
        return null;
    }

    /**
     * @see QueryLanguageProvider#getSupportedLanguages(ResolveContext)
     * @return array with the support query languages
     */
    public String[] getSupportedLanguages() {
        final QueryLanguageProvider<Object> jcrQueryProvider = getQueryLanguageProvider();
        if (jcrQueryProvider == null) {
            return null;
        }
        return jcrQueryProvider.getSupportedLanguages(this.resolveContext);
    }

    /**
     * @see QueryLanguageProvider#findResources(ResolveContext, String, String)
     * @param query the query
     * @param language the language of the query
     * @return an iterator covering the found resources
     */
    public Iterator<Resource> findResources(final String query, final String language) {
        final QueryLanguageProvider<Object> jcrQueryProvider = getQueryLanguageProvider();
        if (jcrQueryProvider == null) {
            return null;
        }
        return wrapIterator(
                jcrQueryProvider.findResources(this.resolveContext, transformQuery(query, language), language));
    }

    /**
     * @see QueryLanguageProvider#queryResources(ResolveContext, String, String)
     * @param query the query
     * @param language the language of the query
     * @return a map with the result
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Iterator<Map<String, Object>> queryResources(final String query, final String language) {
        final QueryLanguageProvider<Object> jcrQueryProvider = getQueryLanguageProvider();
        if (jcrQueryProvider == null) {
            return null;
        }
        return (Iterator)
                jcrQueryProvider.queryResources(this.resolveContext, transformQuery(query, language), language);
    }

    /**
     * @see ResourceProvider#adaptTo(ResolveContext, Class)
     * @param type the type to convert to
     * @param <AdapterType> the adapter target
     * @return the adapter target or {code}null{code} if the adaption failed
     */
    public <AdapterType> AdapterType adaptTo(final Class<AdapterType> type) {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.adaptTo(this.resolveContext, type);
        }
        return null;
    }

    /**
     * @see ResourceProvider#copy(ResolveContext, String, String)
     * @param srcAbsPath the absolute source path
     * @param destAbsPath the absolute target path
     * @return true if the copy succeeded, false otherwise
     * @throws PersistenceException in case of problems
     */
    public boolean copy(final String srcAbsPath, final String destAbsPath) throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.copy(this.resolveContext, srcAbsPath, destAbsPath);
        }
        return false;
    }

    /**
     * @see ResourceProvider#move(ResolveContext, String, String)
     * @param srcAbsPath the absolute source path
     * @param destAbsPath the absolute target path
     * @return true if the move succeeded, false otherwise
     * @throws PersistenceException in case of problems
     */
    public boolean move(final String srcAbsPath, final String destAbsPath) throws PersistenceException {
        final ResourceProvider<Object> rp = this.providerHandler.getResourceProvider();
        if (rp != null) {
            return rp.move(this.resolveContext, srcAbsPath, destAbsPath);
        }
        return false;
    }

    private boolean canCreate(final ResourceResolver resolver, final String path) {
        boolean allowed = true;
        if (this.useRAS) {
            final ResourceAccessSecurity security = tracker.getProviderResourceAccessSecurity();
            if (security != null) {
                allowed = security.canCreate(path, resolver);
            } else {
                allowed = false;
            }
        }

        if (allowed) {
            final ResourceAccessSecurity security = tracker.getApplicationResourceAccessSecurity();
            if (security != null) {
                allowed = security.canCreate(path, resolver);
            }
        }
        return allowed;
    }

    private boolean canOrderChildren(final Resource resource) {
        boolean allowed = true;
        if (this.useRAS) {
            final ResourceAccessSecurity security = tracker.getProviderResourceAccessSecurity();
            if (security != null) {
                allowed = security.canOrderChildren(resource);
            } else {
                allowed = false;
            }
        }

        if (allowed) {
            final ResourceAccessSecurity security = tracker.getApplicationResourceAccessSecurity();
            if (security != null) {
                allowed = security.canOrderChildren(resource);
            }
        }
        return allowed;
    }

    private boolean canDelete(final Resource resource) {
        boolean allowed = true;
        if (this.useRAS) {
            final ResourceAccessSecurity security = tracker.getProviderResourceAccessSecurity();
            if (security != null) {
                allowed = security.canDelete(resource);
            } else {
                allowed = false;
            }
        }

        if (allowed) {
            final ResourceAccessSecurity security = tracker.getApplicationResourceAccessSecurity();
            if (security != null) {
                allowed = security.canDelete(resource);
            }
        }
        return allowed;
    }

    /**
     * applies resource access security if configured
     */
    private String transformQuery(final String query, final String language) {
        String returnValue = query;

        if (this.useRAS) {
            final ResourceAccessSecurity resourceAccessSecurity = tracker.getProviderResourceAccessSecurity();
            if (resourceAccessSecurity != null) {
                try {
                    returnValue = resourceAccessSecurity.transformQuery(
                            returnValue, language, this.resolveContext.getResourceResolver());
                } catch (AccessSecurityException e) {
                    logger.error(
                            "AccessSecurityException occurred while trying to transform the query {} (language {}).",
                            new Object[] {query, language},
                            e);
                }
            }
        }

        final ResourceAccessSecurity resourceAccessSecurity = tracker.getApplicationResourceAccessSecurity();
        if (resourceAccessSecurity != null) {
            try {
                returnValue = resourceAccessSecurity.transformQuery(
                        returnValue, language, this.resolveContext.getResourceResolver());
            } catch (AccessSecurityException e) {
                logger.error(
                        "AccessSecurityException occurred while trying to transform the query {} (language {}).",
                        new Object[] {query, language},
                        e);
            }
        }

        return returnValue;
    }

    /**
     * Wrap a resource with additional resource access security
     * @param rsrc The resource or {@code null}.
     * @return The wrapped resource or {@code null}
     */
    private @Nullable Resource wrapResource(@Nullable Resource rsrc) {
        Resource returnValue = null;

        if (useRAS && rsrc != null) {
            final ResourceAccessSecurity resourceAccessSecurity = tracker.getProviderResourceAccessSecurity();
            if (resourceAccessSecurity != null) {
                returnValue = resourceAccessSecurity.getReadableResource(rsrc);
            }
        } else {
            returnValue = rsrc;
        }

        if (returnValue != null) {
            final ResourceAccessSecurity resourceAccessSecurity = tracker.getApplicationResourceAccessSecurity();
            if (resourceAccessSecurity != null) {
                returnValue = resourceAccessSecurity.getReadableResource(returnValue);
            }
        }

        return returnValue;
    }

    private Iterator<Resource> wrapIterator(Iterator<Resource> iterator) {
        if (iterator == null) {
            return iterator;
        } else {
            return new SecureIterator(iterator);
        }
    }

    private class SecureIterator extends AbstractIterator<Resource> {

        private final Iterator<Resource> iterator;

        public SecureIterator(Iterator<Resource> iterator) {
            this.iterator = iterator;
        }

        @Override
        protected Resource seek() {
            while (iterator.hasNext()) {
                final Resource resource = wrapResource(iterator.next());
                if (resource != null) {
                    return resource;
                }
            }
            return null;
        }
    }

    @Override
    public String toString() {
        return "[" + getClass().getSimpleName() + "# rp: " + this.providerHandler.getResourceProvider() + "]";
    }
}
