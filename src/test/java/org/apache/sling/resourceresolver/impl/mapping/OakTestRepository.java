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

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.Value;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import org.apache.jackrabbit.api.JackrabbitRepository;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.index.AsyncIndexUpdate;
import org.apache.jackrabbit.oak.plugins.index.CompositeIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.counter.NodeCounterEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexEditorProvider;
import org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProvider;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.query.QueryEngineSettings;
import org.apache.jackrabbit.oak.spi.commit.Observer;
import org.apache.jackrabbit.oak.spi.query.QueryIndexProvider;
import org.apache.sling.jcr.api.SlingRepository;
import org.slf4j.LoggerFactory;

/**
 * An in-memory Oak repository (with the Lucene index provider) for tests that
 * need the real query engine, exposed as a {@link SlingRepository} so that the
 * JCR resource provider can be used on top of it.
 * <p>
 * Lucene indexes on the {@value #ASYNC_LANE} lane are only updated when
 * {@link #runAsyncIndexing()} is called, so a test controls exactly when the
 * index catches up with the repository (and can therefore test with a stale
 * index).
 */
public class OakTestRepository implements AutoCloseable {

    static final String ASYNC_LANE = "async";

    private static final String ADMIN = "admin";

    /**
     * Log levels while the repository is open: Oak logs a lot at debug level (the
     * default when running the tests), and the tests resolve thousands of paths.
     */
    private static final Map<String, Level> LOG_LEVELS = Map.of(
            "org.apache.jackrabbit", Level.WARN,
            "org.apache.lucene", Level.WARN,
            "org.apache.sling", Level.INFO);

    private final Map<ch.qos.logback.classic.Logger, Level> previousLogLevels = new HashMap<>();

    private final MemoryNodeStore nodeStore = new MemoryNodeStore();
    private final LuceneIndexProvider indexProvider = new LuceneIndexProvider();
    private final LuceneIndexEditorProvider indexEditorProvider = new LuceneIndexEditorProvider();
    private final AsyncIndexUpdate asyncIndexUpdate;
    private final Repository repository;
    private final Session session;

    OakTestRepository() throws RepositoryException {
        this(new QueryEngineSettings().getLimitInMemory());
    }

    /**
     * @param limitInMemory the maximum number of rows the query engine sorts in
     *     memory; a query that needs to sort more rows (because no index can sort
     *     them) fails with an {@link UnsupportedOperationException}
     */
    OakTestRepository(long limitInMemory) throws RepositoryException {
        LOG_LEVELS.forEach((name, level) -> {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
            previousLogLevels.put(logger, logger.getLevel());
            logger.setLevel(level);
        });
        QueryEngineSettings querySettings = new QueryEngineSettings();
        querySettings.setLimitInMemory(limitInMemory);
        Oak oak = new Oak(nodeStore);
        repository = new Jcr(oak)
                .with((QueryIndexProvider) indexProvider)
                .with((Observer) indexProvider)
                .with(indexEditorProvider)
                .with(querySettings)
                .createRepository();
        // the default content has an async node counter index as well
        asyncIndexUpdate = new AsyncIndexUpdate(
                ASYNC_LANE,
                nodeStore,
                CompositeIndexEditorProvider.compose(List.of(indexEditorProvider, new NodeCounterEditorProvider())));
        session = loginAdmin(null);
        session.getWorkspace()
                .getNamespaceRegistry()
                .registerNamespace("sling", "http://sling.apache.org/jcr/sling/1.0");
    }

    /**
     * @return an admin session, used by the tests to create content
     */
    Session getSession() {
        return session;
    }

    /**
     * @return the repository as a {@link SlingRepository}; all service logins
     *     get an admin session
     */
    SlingRepository getSlingRepository() {
        return new OakSlingRepository();
    }

    /**
     * Let the async lane catch up with the current repository state.
     */
    void runAsyncIndexing() {
        asyncIndexUpdate.run();
        if (asyncIndexUpdate.isFailing()) {
            throw new IllegalStateException("async indexing failed");
        }
    }

    /**
     * Create an async Lucene index for a (multivalued) string property: an
     * "is not null" index on the property, plus an ordered function index for
     * each of the given expressions (for example {@code first([sling:alias])}),
     * so that queries sorting on these expressions are sorted by the index.
     */
    void createAsyncLuceneIndex(String indexName, String propertyName, String... orderedExpressions)
            throws RepositoryException {
        Node index = session.getNode("/oak:index").addNode(indexName, "oak:QueryIndexDefinition");
        index.setProperty("type", "lucene");
        index.setProperty("async", new String[] {ASYNC_LANE});
        index.setProperty("compatVersion", 2L);
        index.setProperty("evaluatePathRestrictions", true);
        index.setProperty("reindex", true);
        Node properties = index.addNode("indexRules", "nt:unstructured")
                .addNode("nt:base")
                .addNode("properties");
        Node property = properties.addNode("property", "nt:unstructured");
        property.setProperty("name", propertyName);
        property.setProperty("propertyIndex", true);
        for (int i = 0; i < orderedExpressions.length; i++) {
            Node ordered = properties.addNode("ordered" + i, "nt:unstructured");
            ordered.setProperty("function", orderedExpressions[i]);
            ordered.setProperty("propertyIndex", true);
            ordered.setProperty("ordered", true);
        }
        session.save();
    }

    /**
     * @return whether the query engine supports the given condition (for example,
     *     the {@code if()} and {@code exists()} functions were added in OAK-12406)
     */
    boolean supportsCondition(String condition) throws RepositoryException {
        try {
            explain("SELECT * FROM [nt:base] WHERE " + condition);
            return true;
        } catch (InvalidQueryException e) {
            return false;
        }
    }

    /**
     * @return the query plan Oak uses for the given JCR-SQL2 query
     */
    String explain(String query) throws RepositoryException {
        return session.getWorkspace()
                .getQueryManager()
                .createQuery("explain " + query, Query.JCR_SQL2)
                .execute()
                .getRows()
                .nextRow()
                .getValue("plan")
                .getString();
    }

    @Override
    public void close() {
        session.logout();
        asyncIndexUpdate.close();
        ((JackrabbitRepository) repository).shutdown();
        indexProvider.close();
        previousLogLevels.forEach(ch.qos.logback.classic.Logger::setLevel);
    }

    private Session loginAdmin(String workspace) throws RepositoryException {
        return repository.login(new SimpleCredentials(ADMIN, ADMIN.toCharArray()), workspace);
    }

    /**
     * Minimal {@link SlingRepository} on top of the Oak repository (there are no
     * service users in this repository, so service logins are admin logins).
     */
    private class OakSlingRepository implements SlingRepository {

        @Override
        public String getDefaultWorkspace() {
            return null;
        }

        @Override
        public Session loginAdministrative(String workspace) throws RepositoryException {
            return loginAdmin(workspace);
        }

        @Override
        public Session loginService(String subServiceName, String workspace) throws RepositoryException {
            return loginAdmin(workspace);
        }

        @Override
        public Session impersonateFromService(String subServiceName, Credentials credentials, String workspace)
                throws RepositoryException {
            Session admin = loginAdmin(workspace);
            try {
                return admin.impersonate(credentials);
            } finally {
                admin.logout();
            }
        }

        @Override
        public Session login(Credentials credentials, String workspace) throws RepositoryException {
            return repository.login(credentials, workspace);
        }

        @Override
        public Session login(Credentials credentials) throws RepositoryException {
            return repository.login(credentials);
        }

        @Override
        public Session login(String workspace) throws RepositoryException {
            return repository.login(workspace);
        }

        @Override
        public Session login() throws RepositoryException {
            return repository.login();
        }

        @Override
        public String[] getDescriptorKeys() {
            return repository.getDescriptorKeys();
        }

        @Override
        public boolean isStandardDescriptor(String key) {
            return repository.isStandardDescriptor(key);
        }

        @Override
        public boolean isSingleValueDescriptor(String key) {
            return repository.isSingleValueDescriptor(key);
        }

        @Override
        public Value getDescriptorValue(String key) {
            return repository.getDescriptorValue(key);
        }

        @Override
        public Value[] getDescriptorValues(String key) {
            return repository.getDescriptorValues(key);
        }

        @Override
        public String getDescriptor(String key) {
            return repository.getDescriptor(key);
        }
    }
}
