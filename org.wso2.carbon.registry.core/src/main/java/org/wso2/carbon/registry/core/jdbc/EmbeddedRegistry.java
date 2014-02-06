/*
 * Copyright (c) 2008, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.registry.core.jdbc;

import java.io.Reader;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.cache.Cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.registry.core.CollectionImpl;
import org.wso2.carbon.registry.core.CommentImpl;
//import org.wso2.carbon.registry.core.LogEntryCollection;
import org.wso2.carbon.registry.core.ResourceImpl;
import org.wso2.carbon.registry.core.caching.RegistryCacheKey;
import org.wso2.carbon.registry.core.config.DataBaseConfiguration;
import org.wso2.carbon.registry.core.config.Mount;
import org.wso2.carbon.registry.core.config.RegistryContext;
import org.wso2.carbon.registry.core.config.RemoteConfiguration;
import org.wso2.carbon.registry.core.dao.AssociationDAO;
import org.wso2.carbon.registry.core.dao.CommentsDAO;
import org.wso2.carbon.registry.core.dao.LogsDAO;
import org.wso2.carbon.registry.core.dao.RatingsDAO;
import org.wso2.carbon.registry.core.dao.TagsDAO;
import org.wso2.carbon.registry.core.dataaccess.DataAccessManager;
import org.wso2.carbon.registry.core.exceptions.RepositoryServerContentException;
import org.wso2.carbon.registry.core.exceptions.RepositorySessionException;
import org.wso2.carbon.registry.core.jdbc.handlers.HandlerLifecycleManager;
import org.wso2.carbon.registry.core.jdbc.handlers.HandlerManager;
import org.wso2.carbon.registry.core.jdbc.queries.QueryProcessorManager;
import org.wso2.carbon.registry.core.jdbc.utils.DumpReader;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.core.statistics.query.DBQueryStatisticsLog;
import org.wso2.carbon.registry.core.statistics.query.StatisticsRecord;
import org.wso2.carbon.registry.core.utils.AuthorizationUtils;
import org.wso2.carbon.registry.core.utils.InternalConstants;
import org.wso2.carbon.registry.core.utils.InternalUtils;
import org.wso2.carbon.registry.core.utils.VersionedPath;
import org.wso2.carbon.repository.ActionConstants;
import org.wso2.carbon.repository.Activity;
import org.wso2.carbon.repository.Aspect;
import org.wso2.carbon.repository.Association;
import org.wso2.carbon.repository.Collection;
import org.wso2.carbon.repository.Comment;
import org.wso2.carbon.repository.GhostResource;
import org.wso2.carbon.repository.Registry;
import org.wso2.carbon.repository.RegistryService;
import org.wso2.carbon.repository.RepositoryConstants;
import org.wso2.carbon.repository.Resource;
import org.wso2.carbon.repository.ResourcePath;
import org.wso2.carbon.repository.Tag;
import org.wso2.carbon.repository.TaggedResourcePath;
import org.wso2.carbon.repository.config.StaticConfiguration;
import org.wso2.carbon.repository.exceptions.RepositoryAuthException;
import org.wso2.carbon.repository.exceptions.RepositoryErrorCodes;
import org.wso2.carbon.repository.exceptions.RepositoryException;
import org.wso2.carbon.repository.exceptions.RepositoryResourceNotFoundException;
import org.wso2.carbon.repository.exceptions.RepositoryUserContentException;
import org.wso2.carbon.repository.handlers.Handler;
import org.wso2.carbon.repository.handlers.RequestContext;
import org.wso2.carbon.repository.utils.RepositoryUtils;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

/**
 * This is a core class of the Embedded JDBC Based implementation of the Registry. This will be used
 * mostly as the back-end by other Registry implementations. This can use either an in-memory
 * database or a external database configured using data source.
 */
public class EmbeddedRegistry implements Registry {

    private static final Log log = LogFactory.getLog(EmbeddedRegistry.class);

    // The instance of the logger to be used to log database query statistics.
    private static final Log dbQueryLog = DBQueryStatisticsLog.getLog();

    private static List<String> statEnabledOperations = new LinkedList<String>();

    private DataAccessManager dataAccessManager = null;

    // The executor service used to create threads to record connection statistics.
    private static ExecutorService executor = null;

    /**
     * Repository instance. This is used to handle basic resource storage operations of current
     * versions.
     */
    private Repository repository;
    /**
     * VersionRepository instance. This is used to handle resource storage operations of archived
     * (old) versions.
     */
    private VersionRepository versionRepository;

    /**
     * This is used to execute custom queries.
     */
    private QueryProcessorManager queryProcessorManager;

    /**
     * This is used to interact with the database layer to perform resource related operations.
     */
    private CommentsDAO commentsDAO;
    private RatingsDAO ratingsDAO;
    private TagsDAO tagsDAO;
    /**
     * This is used to interact with the database layer to perform associations related operations.
     */
    private AssociationDAO associationDAO = null;
    /**
     * This is used to interact with the database layer to perform activity logging related
     * operations.
     */
    private LogsDAO logsDAO = null;

    /**
     * The URL of the WS-Eventing Service
     */
    private String defaultEventingServiceURL;

    /**
     * Dictionary of URLs of the WS-Eventing Services
     */
    //TODO: Write new comparator for this map
    private Map<String, String> eventingServiceURLs = new TreeMap<String, String>();


    /**
     * Reference to the RegistryContext instance.
     */
    private RegistryContext registryContext;

    /**
     * UserRealm instance
     */
    private RealmService realmService = null;
    
    /** Shazni
     * RegistryService inside all the registry
     */
    private RegistryService registryService = null;
    private String resourceMediaTypes = null;
    private String collectionMediaTypes = null;
    private String customUIMediaTypes = null;
    
    // Following are for CacheBackRegistry
    
    private Map<String, String> cacheIds = new HashMap<String, String>();
    private Map<String, DataBaseConfiguration> dbConfigs = new HashMap<String, DataBaseConfiguration>();
    private Map<String, String> pathMap = new HashMap<String, String>();
    
    private int tenantId = MultitenantConstants.INVALID_TENANT_ID; 
    
    // -------------------------------------

    /**
     * jdbcDir uses in content indexing
     */
//    private JdbcDirectory jdbcDir;

    static {
        if (dbQueryLog.isDebugEnabled()) {
            initializeStatisticsLogging();
        }
        String property = System.getProperty("carbon.registry.statistics.operations");
        if (property != null) {
            statEnabledOperations.addAll(Arrays.asList(property.split(",")));
        }
    }

    private static synchronized void initializeStatisticsLogging() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                executor.shutdownNow();
            }
        });
    }

    /**
     * Default constructor. Embedded registry should be configured using the {@link #configure}
     * method, if it is instantiated using this constructor.
     */
    public EmbeddedRegistry() {
    }

    /**
     * Constructs a Embedded registry to be used with secure registries. Default authorizations will
     * be applied on the resources created using this registry.
     *
     * @param registryContext RegistryContext containing our configuration and data access manager.
     *                        Note that this may or may not be the same data source as the User
     *                        Manager.
     * @param realmService    User manager realm service handle authorizations.
     *
     * @throws RepositoryException If something went wrong
     */
    public EmbeddedRegistry(RegistryContext registryContext, RealmService realmService, RegistryService registryService)
            throws RepositoryException {
        this.registryContext = registryContext;
        this.dataAccessManager = registryContext.getDataAccessManager();
        this.logsDAO = dataAccessManager.getDAOManager().getLogsDAO();
        this.associationDAO = dataAccessManager.getDAOManager().getAssociationDAO();
        this.realmService = realmService;
        this.registryService = registryService;
        init();
    }

    /**
     * Configures and initiates the Embedded registry with a (new) data source and a realm. This is
     * useful for changing underlying databases at run-time.
     *
     * @param dataAccessManager the data access manager to use
     * @param realmService      the User manager realm service handle authorizations.
     *
     * @throws RepositoryException If something went wrong while init
     */
    public void configure(DataAccessManager dataAccessManager, RealmService realmService)
            throws RepositoryException {
        this.dataAccessManager = dataAccessManager;
        this.logsDAO = dataAccessManager.getDAOManager().getLogsDAO();
        this.associationDAO = dataAccessManager.getDAOManager().getAssociationDAO();
        this.realmService = realmService;
        init();
    }

    // initializing the registry, filling repository, versionRepository + initializing handlers
    // (This contains the code that used to be in EmbeddedRegistry constructor
    private void init() throws RepositoryException {
        beginDBQueryLog(2);
        if (log.isTraceEnabled()) {
            log.trace("Initializing main registry");
        }

        if (registryContext == null) {
            registryContext = RegistryContext.getBaseInstance(realmService, registryService);
        }
        registryContext.setDataAccessManager(dataAccessManager);

        if (log.isTraceEnabled()) {
            log.trace("Initializing the version repository.");
        }
        versionRepository = new VersionRepository(dataAccessManager);

        if (log.isTraceEnabled()) {
            log.trace("Initializing the repository.");
        }
        repository = new Repository(dataAccessManager,
                versionRepository, registryContext.isVersionOnChange(),
                new RecursionRepository(this));

        if (log.isTraceEnabled()) {
            log.trace("Initializing the query manager for processing custom queries.");
        }
        queryProcessorManager = new QueryProcessorManager(dataAccessManager, registryContext);

        registryContext.setRepository(repository);
        registryContext.setVersionRepository(versionRepository);
        registryContext.setQueryProcessorManager(queryProcessorManager);

        if (log.isTraceEnabled()) {
            log.trace("Initialing the content indexing system.");
        }

//        initializeIndex();
//        registryContext.setJdbcDir(jdbcDir);

        if (log.isTraceEnabled()) {
            log.trace("Initializing DAOs depending on the static configurations.");
        }
        commentsDAO = dataAccessManager.getDAOManager().getCommentsDAO(
                StaticConfiguration.isVersioningComments());
        ratingsDAO = dataAccessManager.getDAOManager().getRatingsDAO(
                StaticConfiguration.isVersioningRatings());
        tagsDAO = dataAccessManager.getDAOManager().getTagsDAO(
                StaticConfiguration.isVersioningTags());

        if (log.isTraceEnabled()) {
            log.trace("Main registry initialized successfully.");
        }
        endDBQueryLog(2);
        
        // For Caching ------------------------------------------------------------------
        
        //RegistryContext registryContext = RegistryContext.getBaseInstance();
    	
        if (registryContext != null && registryContext.isCacheEnabled()) {
	        for (Mount mount : registryContext.getMounts()) {
	            for(RemoteConfiguration configuration : registryContext.getRemoteInstances()) {
	                if (configuration.getDbConfig() != null &&
	                        mount.getInstanceId().equals(configuration.getId())) {
	                    dbConfigs.put(mount.getPath(),
	                            registryContext.getDBConfig(configuration.getDbConfig()));
	                    pathMap.put(mount.getPath(), mount.getTargetPath());
	                } else if (configuration.getCacheId() != null &&
	                        mount.getInstanceId().equals(configuration.getId())) {
	                    cacheIds.put(mount.getPath(), configuration.getCacheId());
	                    pathMap.put(mount.getPath(), mount.getTargetPath());
	                }
	            }
	        }
        }
    }

    // Starts logging database query statistics.
    private void beginDBQueryLog(int level) {
        if (dbQueryLog.isDebugEnabled()) {
            StackTraceElement traceElement = Thread.currentThread().getStackTrace()[level];
            String methodName = traceElement.getMethodName();
            if (!statEnabledOperations.isEmpty() && statEnabledOperations.contains(methodName)) {
                if (traceElement.getClassName().equals(this.getClass().getCanonicalName())) {
                    StatisticsRecord statisticsRecord = DBQueryStatisticsLog.getStatisticsRecord();
                    if (statisticsRecord.increment() == 0) {
                        DBQueryStatisticsLog.clearStatisticsRecord();
                        statisticsRecord = DBQueryStatisticsLog.getStatisticsRecord();
                        statisticsRecord.increment();
                        statisticsRecord.setOperation(methodName);
                    }
                }
            }
        }
    }

    // Finishes logging database query statistics.
    private void endDBQueryLog(int level) {
        if (dbQueryLog.isDebugEnabled()) {
            StackTraceElement traceElement = Thread.currentThread().getStackTrace()[level];
            String methodName = traceElement.getMethodName();
            if (!statEnabledOperations.isEmpty() && statEnabledOperations.contains(methodName)) {
                if (traceElement.getClassName().equals(this.getClass().getCanonicalName())) {
                    StatisticsRecord statisticsRecord = DBQueryStatisticsLog.getStatisticsRecord();
                    if (statisticsRecord.decrement() == 0) {
                        final StatisticsRecord clone = new StatisticsRecord(statisticsRecord);
                        Runnable runnable = new Runnable() {
                            public void run() {
                                if (clone.getTableRecords().length > 0) {
                                    dbQueryLog.debug("");
                                    dbQueryLog.debug(
                                            "---------------------------------------------------");
                                    dbQueryLog.debug("Registry Operation: " +
                                            clone.getOperation());
                                    dbQueryLog.debug("");
                                    for (String record : clone.getTableRecords()) {
                                        dbQueryLog.debug("Tables Accessed: " + record);
                                    }
                                    if (Boolean.toString(true).equals(
                                            System.getProperty(
                                                    "carbon.registry.statistics.output." +
                                                            "queries.executed"))) {
                                        dbQueryLog.debug("");
                                        StringBuffer sb = new StringBuffer();
                                        for (String query : clone.getQueries()) {
                                            sb.append("\n").append(query);
                                        }
                                        dbQueryLog.debug("Queries Executed:" + sb.toString());
                                    }
                                    dbQueryLog.debug(
                                            "---------------------------------------------------");
                                    dbQueryLog.debug("");
                                }
                            }
                        };
                        if (executor != null) {
                            executor.submit(runnable);
                        } else {
                            initializeStatisticsLogging();
                            executor.submit(runnable);
                        }
                        DBQueryStatisticsLog.clearStatisticsRecord();
                    }
                }
            }
        }
    }

    // TODO: Add this back once this is in a working state.
    @SuppressWarnings("unused")
    private void initializeIndex() throws RepositoryException {

        /*Connection conn = null;
        try {
            if (log.isTraceEnabled()) {
                log.trace("Initialing database for content indexing.");
            }
            conn = dataSource.getConnection();
            ResultSet rs = conn.getMetaData().getTables(null, null, "REG_CONTENT_INDEX", null);
            jdbcDir = new JdbcDirectory(dataSource, DialectFactory.getDialect(dataSource)
                    , "REG_CONTENT_INDEX");
            if (!rs.next()) {
                if (log.isTraceEnabled()) {
                    log.trace("Creating the database tables storing indexes.");
                }
                jdbcDir.create();
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Database tables for storing indexes are already created.");
                }
            }
            rs.close();

            if (log.isTraceEnabled()) {
                log.trace("Database for content indexing is initialized successfully.");
            }

        } catch (SQLException e) {

            // Note if Registry is running on multiple nodes in a cluster sharing a same database:
            // When nodes are started at the same time, the first node that reaches database
            // creation code, tries to create tables. But other nodes may also perform the check and
            // finds out that tables are not created (if the first node has not yet committed the
            // table creation operation). Then they may also try to create tables and only one node
            // succeeds in this process. All other nodes may fail in this table creation operation,
            // if they encounter this concurrency scenario. But all other nodes should continue,
            // if the tables are created by some other node.

            boolean indexTableCreated = false;
            try {
                ResultSet rs = conn.getMetaData().getTables(null, null, "REG_CONTENT_INDEX", null);
                if (rs.next()) {
                    indexTableCreated = true;
                }
                rs.close();

            } catch (SQLException e1) {

                String msg = "Failed to check the existence of the table REG_CONTENT_INDEX.";
                log.error(msg, e1);
            }

            if (indexTableCreated) {

                String msg = "Attempt to create database tables for content index is " +
                        "unsuccessful. But all the required database tables are already created. " +
                        "If this Registry instance is started in a clustered environment " +
                        "simultaneously with other Registry nodes, this is an expected situation " +
                        "and considered as a successful start-up of this node. Complete error " +
                        "is also logged for reference. Ignore this error log if this " +
                        "instance is started in a cluster.";
                if (log.isDebugEnabled()) {
                    log.debug(msg, e);
                }

            } else {
                String msg = "Failed to create table to store content index.";
                log.error(msg, e);
                throw new RepositoryException(msg, e);
            }

        } catch (IOException e) {

            String msg = "Failed to create table to store content index.";
            log.error(msg, e);
            throw new RepositoryException(msg, e);

        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                String msg = "Failed to close the connection used to create database tables " +
                        "for content index. Caused by: " + e.getMessage();
                log.error(msg, e);
            }
        }*/
    }


    public void beginTransaction() throws RepositoryException {
        beginDBQueryLog(3);
        dataAccessManager.getTransactionManager().beginTransaction();
    }

    public void rollbackTransaction() throws RepositoryException {
        dataAccessManager.getTransactionManager().rollbackTransaction();
        endDBQueryLog(3);
    }

    public void commitTransaction() throws RepositoryException {
        dataAccessManager.getTransactionManager().commitTransaction();
        endDBQueryLog(3);
    }

    public RegistryContext getRegistryContext() {
        beginDBQueryLog(2);
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        // We need to set the path of the registry that is making the request for the registry
        // context so that the handler manager can figure out which handler to invoke. In here,
        // we use the chroot of the registry as the path.
        String chroot = CurrentSession.getChroot();
        if (chroot == null) {
            chroot = "/";
        } else if (!chroot.endsWith("/")) {
            chroot += "/";
        }
        context.setResourcePath(new ResourcePath(chroot));
        
        RegistryContext output = ((HandlerLifecycleManager) registryContext.getHandlerManager()).getRegistryContext(context);
        endDBQueryLog(2);
        if (output != null) {
            return output;
        }
        return registryContext;
    }

    public Resource newResource() throws RepositoryException {
        beginDBQueryLog(2);
        try {
            ResourceImpl resource = new ResourceImpl();
            resource.setAuthorUserName(CurrentSession.getUser());
            return resource;
        } finally {
            endDBQueryLog(2);
        }
    }

    public Collection newCollection() throws RepositoryException {
        beginDBQueryLog(2);
        try {
            CollectionImpl coll = new CollectionImpl();
            coll.setAuthorUserName(CurrentSession.getUser());
            return coll;
        } finally {
            endDBQueryLog(2);
        }
    }

    private Resource getResource(String path) throws RepositoryException {    // Renamed to getResource from get. Made to private
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);

            // check if this path refers to a resource referred by a URL query (e.g. comment)
            context.setResourcePath(resourcePath);

            Resource resource = registryContext.getHandlerManager().get(context);

            if (!context.isSimulation()) {
                // resource may have been fetched from the repository, to be used by handlers. if
                // it is done, it has to be stored in the request context. we can just use that
                // resource without fetching it again from the repository.
                if (resource == null) {
                    resource = context.getResource();
                }

                if (resource == null) {
                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
                    if (versionedPath.getVersion() == -1) {
                        resource = repository.get(resourcePath.getPath());
                    } else {
                        resource = versionRepository.get(versionedPath);
                    }
                }

                if (resource == null) {
                    throw new RepositoryResourceNotFoundException(path, RepositoryErrorCodes.RESOURCE_PATH_ERROR);
                }

                context.setResource(resource);

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).get(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }

            return resource;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).get(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public Resource getMetaData(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
        try {
            // starting the transactional operation wrapper
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);
            Resource resource;

            VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
            if (versionedPath.getVersion() == -1) {
                resource = repository.getMetaData(resourcePath.getPath());
            } else {
                resource = versionRepository.getMetaData(versionedPath);
            }

            if (resource == null) {
                throw new RepositoryResourceNotFoundException(path, RepositoryErrorCodes.RESOURCE_PATH_ERROR);
            }

            // transaction successfully finished
            transactionSucceeded = true;

            return resource;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

//    public String importResource(String suggestedPath, String sourceURL,
//                                 org.wso2.carbon.repository.api.Resource resource)
//            throws org.wso2.carbon.repository.api.exceptions.RepositoryException {
//        return importResource(suggestedPath, sourceURL, (Resource) resource);
//    }

    private Collection getCollection(String path, int start, int pageSize) throws RepositoryException { // Renamed to getCollection from get. Made to private
        boolean transactionSucceeded = false;
        try {
            // starting the transactional operation wrapper
            beginTransaction();

            Collection collection;

            ResourcePath resourcePath = new ResourcePath(path);
            VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
            if (versionedPath.getVersion() == -1) {
                collection = repository.get(resourcePath.getPath(), start, pageSize);
            } else {
                collection = versionRepository.get(versionedPath, start, pageSize);
            }

            // transaction successfully finished
            transactionSucceeded = true;

            return collection;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

    private boolean checkResourceExists(String path) throws RepositoryException { // Renamed to checkResourceExists from resourceExist and made to private
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            // starting the transactional operation wrapper
            ResourcePath resourcePath = new ResourcePath(path);

            context.setResourcePath(resourcePath);
            boolean output = registryContext.getHandlerManager().resourceExists(context);

            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);

                    output = (versionedPath.getVersion() == -1) ?
                            repository.resourceExists(resourcePath.getPath()) :
                            versionRepository.resourceExists(versionedPath);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).resourceExists(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }

            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).resourceExists(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

//    public String put(String suggestedPath, org.wso2.carbon.repository.api.Resource resource)
//            throws org.wso2.carbon.repository.api.exceptions.RepositoryException {
//        return put(suggestedPath, (Resource) resource);
//    }

    public String put(String suggestedPath, Resource resource) throws RepositoryException {

        boolean transactionSucceeded = false;
        //RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        boolean mustPutChild = false;
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(suggestedPath);

            context.setResourcePath(resourcePath);
            context.setResource(resource);

            if (repository.resourceExists(suggestedPath)) {
                context.setOldResource(repository.get(suggestedPath));
            }

            if (!RepositoryConstants.ROOT_PATH.equals(resourcePath.getPath())) {
                mustPutChild = true;
                registryContext.getHandlerManager().putChild(context);
            }

            registryContext.getHandlerManager().put(context);

            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                String actualPath = context.getActualPath();

                if (!context.isProcessingComplete()) {
                    ((ResourceImpl) resource).prepareContentForPut();

                    actualPath = suggestedPath;
                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        repository.put(suggestedPath, resource);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                }

                if (mustPutChild) {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.COMMIT_HANDLER_PHASE).putChild(context);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).put(context);
                transactionSucceeded = true;

                if (actualPath == null) {
                    return suggestedPath;
                } else {
                    return actualPath;
                }
            } else {
                return suggestedPath;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    if (mustPutChild) {
                        registryContext.getHandlerManager(
                                HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).putChild(context);
                    }
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).put(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String importResource(String suggestedPath, String sourceURL, Resource metaResource)
            throws RepositoryException {
        boolean transactionSucceeded = false;
        //RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        //RequestContext importChildContext = new RequestContext(this, repository, versionRepository);
        RequestContext importChildContext = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(suggestedPath);

            importChildContext.setResourcePath(resourcePath);

            context.setResourcePath(resourcePath);
            context.setSourceURL(sourceURL);
            context.setResource(metaResource);

            if (repository.resourceExists(suggestedPath)) {
                Resource resource = repository.get(suggestedPath);
                importChildContext.setOldResource(resource);
                context.setOldResource(resource);
            }

            registryContext.getHandlerManager().importChild(importChildContext);
            registryContext.getHandlerManager().importResource(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                String savedPath = context.getActualPath();

                if (!context.isProcessingComplete()) {
                    savedPath = suggestedPath;

                    // if some handlers have updated the meta data *without completing the request* we should
                    // capture the updated meta data here.
                    if (context.getResource() != null) {
                        metaResource = context.getResource();
                    }

                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        repository.importResource(resourcePath.getPath(), sourceURL, metaResource);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                }

                if (savedPath != null) {
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                savedPath, CurrentSession.getUser(), Activity.UPDATE, null);
                    }
                }
                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).importChild(
                        importChildContext);
                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).importResource(context);
                // transaction successfully finished
                transactionSucceeded = true;

                if (savedPath != null) {
                    return savedPath;
                }
            }
            return suggestedPath;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).importChild(
                            importChildContext);
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).importResource(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void delete(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);
//            context.setRegistryContext(registryContext);
            context.setResourcePath(resourcePath);

            registryContext.getHandlerManager().delete(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete() &&
                        repository.resourceExists(resourcePath.getPath())) {
                    repository.delete(resourcePath.getPath());
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                resourcePath.getPath(), CurrentSession.getUser(),
                                Activity.DELETE_RESOURCE,
                                null);
                    }
                }
                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).delete(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).delete(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }

    }

    public String rename(String currentPath, String newName) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(currentPath);
//            context.setRegistryContext(registryContext);
            context.setSourcePath(currentPath);
            context.setTargetPath(newName);
            String newPath = registryContext.getHandlerManager().rename(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        newPath = repository.rename(resourcePath, newName);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                }
                if (context.isLoggingActivity()) {
                    registryContext.getLogWriter().addLog(
                            newPath, CurrentSession.getUser(), Activity.RENAME, currentPath);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).rename(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return newPath;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).rename(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String move(String currentPath, String newPath) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath currentResourcePath = new ResourcePath(currentPath);
            context.setSourcePath(currentPath);
            context.setTargetPath(newPath);
//            context.setRegistryContext(registryContext);
            String movedPath = registryContext.getHandlerManager().move(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        movedPath = repository.move(currentResourcePath, newPath);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                }
                if (context.isLoggingActivity()) {
                    registryContext.getLogWriter().addLog(
                            newPath, CurrentSession.getUser(), Activity.MOVE, currentPath);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).move(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return movedPath;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).move(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String copy(String sourcePath, String targetPath) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath sourceResourcePath = new ResourcePath(sourcePath);
            ResourcePath targetResourcePath = new ResourcePath(targetPath);
            context.setSourcePath(sourcePath);
            context.setTargetPath(targetPath);
            String copiedPath = registryContext.getHandlerManager().copy(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        copiedPath = repository.copy(sourceResourcePath, targetResourcePath);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                }
                if (context.isLoggingActivity()) {
                    registryContext.getLogWriter().addLog(
                            sourcePath, CurrentSession.getUser(), Activity.COPY, targetPath);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).copy(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return copiedPath;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).copy(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void createVersion(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);

            context.setResourcePath(resourcePath);
            registryContext.getHandlerManager().createVersion(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    versionRepository.createSnapshot(resourcePath, true, true);
                }
                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).createVersion(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).createVersion(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String[] getVersions(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setResourcePath(new ResourcePath(path));
            String[] output = registryContext.getHandlerManager().getVersions(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    output = versionRepository.getVersions(path);
                }
                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getVersions(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getVersions(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void restoreVersion(String versionPath) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setVersionPath(versionPath);
            ResourcePath versionedResourcePath = new ResourcePath(versionPath);
            String path = versionedResourcePath.getPath();
            if (repository.resourceExists(path)) {
                context.setOldResource(repository.get(path));
            }
            registryContext.getHandlerManager().restoreVersion(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    if (repository.resourceExists(path)) {
                        // if the target already have resources, delete them..
                        repository.prepareVersionRestore(path);
                    }
                    versionRepository.restoreVersion(versionedResourcePath);
                    VersionedPath versionedPath =
                    		InternalUtils.getVersionedPath(versionedResourcePath);
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                path, CurrentSession.getUser(), Activity.RESTORE,
                                Long.toString(versionedPath.getVersion()));
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).restoreVersion(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).restoreVersion(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    ////////////////////////////////////////////////////////
    // Extensible searching API
    ////////////////////////////////////////////////////////

    public Collection executeQuery(String path, Map parameters) throws RepositoryException {
        boolean transactionSucceeded = false;
        boolean remote = false;
        if (parameters.get("remote") != null) {
            parameters.remove("remote");
            remote = true;
        }
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        Resource query = null;
        try {
            // start the transaction
            beginTransaction();

            Registry systemRegistry = new UserRegistry(CarbonConstants.REGISTRY_SYSTEM_USERNAME, CurrentSession.getTenantId(), this, realmService, null);
            // we have to get the stored query without checking the user permissions.
            // all query actions are blocked for all users. they are allowed to read the
            // queries, only when executing them.
            if (path != null) {
                String purePath = InternalUtils.getPureResourcePath(path);
                if (systemRegistry.resourceExists(purePath)) {
                    query = systemRegistry.get(purePath);
                    // If no media type was specified, the query should not work at all.
                    // This is also used in the remote registry scenario, where we send '/' as the
                    // query path, when path is null.
                    if (query != null && (query.getMediaType() == null ||
                            query.getMediaType().length() == 0)) {
                        query = null;
                    }
                }
            }
            /*// transaction succeeded
            transactionSucceeded = true;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
        // new transaction
        transactionSucceeded = false;
        context = new RequestContext(this, repository, versionRepository);
        try {
            // start the transaction
            beginTransaction();*/

            if (path != null) {
                context.setResourcePath(new ResourcePath(path));
            }
            context.setResource(query);
            context.setQueryParameters(parameters);
            Collection output = registryContext.getHandlerManager().executeQuery(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    if (query == null) {
                        query = newResource();
                        String mediaType = (String) parameters.get("mediaType");
                        query.setMediaType(mediaType != null ? mediaType :
                                RepositoryConstants.SQL_QUERY_MEDIA_TYPE);
                    }
                    //Resource query = repository.get(purePath);

                    Collection temp = queryProcessorManager.executeQuery(this, query, parameters);
                    Set<String> results = new LinkedHashSet<String>();
                    if (output != null) {
                        String[] children = output.getChildren();
                        if (children != null) {
                            for (String child : children) {
                                if (child != null && (remote || resourceExists(child))) {
                                    results.add(child);
                                }
                            }
                        }

                        if (temp != null) {
                            children = temp.getChildren();
                            if (children != null) {
                                for (String child : children) {
                                    if (child != null && (remote || resourceExists(child))) {
                                        results.add(child);
                                    }
                                }
                            }
                        } else {
                            temp = output;
                        }
                        temp.setContent(results.toArray(new String[results.size()]));
                    }
                    output = temp;
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).executeQuery(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).executeQuery(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public Activity[] getLogs(String resourcePath, int action, String userName, Date from,
                              Date to, boolean recentFirst) throws RepositoryException {
        boolean transactionSucceeded = false;
        try {
            // start the transaction
            beginTransaction();

            List logEntryList =
                    logsDAO.getLogs(resourcePath, action, userName, from, to, recentFirst);

            // We go on two iterations to avoid null values in the following array. Need better way
            // in a single iteration
            for (int i = logEntryList.size() - 1; i >= 0; i--) {
            	Activity logEntry = (Activity) logEntryList.get(i);
                if (logEntry == null) {
                    logEntryList.remove(i);
                }
            }

            Activity[] logEntries = new Activity[logEntryList.size()];
            for (int i = 0; i < logEntryList.size(); i++) {
                logEntries[i] = (Activity) logEntryList.get(i);
            }

            // transaction succeeded
            transactionSucceeded = true;

            return logEntries;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

    public Collection searchContent(String keywords) throws RepositoryException {

        /*RequestContext context = new RequestContext(this, repository, versionRepository);

        context.setKeywords(keywords);
        Collection output = registryContext.getHandlerManager().searchContent(context);
        if (!context.isSimulation()) {
            if (!context.isProcessingComplete()) {
                try {
                    Searcher searcher = new IndexSearcher(registryContext.getJdbcDir());
                    Query query =
                            new QueryParser("content", new StandardAnalyzer()).parse(keywords);
                    Hits hits = searcher.search(query);
                    org.wso2.carbon.registry.core.Collection collection = new CollectionImpl();
                    String[] paths = new String[hits.length()];
                    for (int i = 0; i < hits.length(); i++) {
                        paths[i] = hits.doc(i).get("id");
                    }
                    collection.setContent(paths);
                    output = collection;
                } catch (IOException e) {
                    String msg = "Failed to search content";
                    log.error(msg, e);
                    throw new RepositoryException(msg, e);
                } catch (ParseException e) {
                    String msg = "Failed to parse the query";
                    log.error(msg, e);
                    throw new RepositoryException(msg, e);
                }
            }
        }
        return output;*/
        return null;
    }

    public void createLink(String path, String target) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            if (path.equals(target)) {
                String msg = "Path and target are same, path = target = " + path +
                        ". You can't create a symbolic link to itself.";
                log.error(msg);
                throw new RepositoryServerContentException(msg);
            }
            // first put the data..
            Resource oldResource = repository.getMetaData(target);
            Resource resource;
            if (repository.resourceExists(path)) {
                resource = repository.get(path);
                resource.addProperty(/*RepositoryConstants.*/ InternalConstants.REGISTRY_EXISTING_RESOURCE, "true");
            } else if (oldResource != null) {
                if (oldResource instanceof Collection) {
                    resource = new CollectionImpl();
                } else {
                    resource = new ResourceImpl();
                }
            } else {
                resource = new CollectionImpl();
            }
            resource.addProperty(RepositoryConstants.REGISTRY_NON_RECURSIVE, "true");
            resource.addProperty(/*RepositoryConstants.*/ InternalConstants.REGISTRY_LINK_RESTORATION,
                    path + RepositoryConstants.URL_SEPARATOR + target +
                            RepositoryConstants.URL_SEPARATOR + CurrentSession.getUser());
            resource.setMediaType(/*RepositoryConstants.*/ InternalConstants.LINK_MEDIA_TYPE);
            try {
                CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY, false);
                repository.put(path, resource);
            } finally {
                CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
            }
            resource.discard();
            HandlerManager hm = registryContext.getHandlerManager();

            ResourcePath resourcePath = new ResourcePath(path);
            context.setResourcePath(resourcePath);
            context.setTargetPath(target);
            hm.createLink(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
//                    RepositoryUtils.registerHandlerForSymbolicLinks(registryContext, path, target,
//                            CurrentSession.getUser());
                    InternalUtils.registerHandlerForSymbolicLinks(this, path, target,
                            CurrentSession.getUser());

                    String author = CurrentSession.getUser();
                    InternalUtils.addMountEntry(InternalUtils.getSystemRegistry(this),
                            registryContext, path, target, false, author);


                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(path, CurrentSession.getUser(),
                        		Activity.CREATE_SYMBOLIC_LINK,
                                target);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).createLink(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).createLink(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void createLink(String path, String target, String targetSubPath)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            Resource resource;

            if (repository.resourceExists(path)) {
                resource = repository.get(path);
                resource.addProperty(/*RepositoryConstants.*/ InternalConstants.REGISTRY_EXISTING_RESOURCE, "true");
            } else {
                resource = new CollectionImpl();
            }
            resource.addProperty(RepositoryConstants.REGISTRY_NON_RECURSIVE, "true");
            resource.addProperty(/*RepositoryConstants.*/ InternalConstants.REGISTRY_LINK_RESTORATION,
                    path + RepositoryConstants.URL_SEPARATOR + target +
                            RepositoryConstants.URL_SEPARATOR + targetSubPath +
                            RepositoryConstants.URL_SEPARATOR + CurrentSession.getUser());
            resource.setMediaType(/*RepositoryConstants.*/ InternalConstants.LINK_MEDIA_TYPE);
            try {
                CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY, false);
                repository.put(path, resource);
            } finally {
                CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
            }
            resource.discard();

            HandlerManager hm = registryContext.getHandlerManager();

            ResourcePath resourcePath = new ResourcePath(path);
            context.setResourcePath(resourcePath);
            context.setTargetPath(target);
            context.setTargetSubPath(targetSubPath);
            hm.createLink(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
//                    RepositoryUtils.registerHandlerForRemoteLinks(registryContext, path, target,
//                            targetSubPath, CurrentSession.getUser());
                    InternalUtils.registerHandlerForRemoteLinks(this, path, target,
                            targetSubPath, CurrentSession.getUser());

                    String author = CurrentSession.getUser();
                    InternalUtils.addMountEntry(InternalUtils.getSystemRegistry(this),
                            registryContext, path, target, targetSubPath, author);

                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                path, CurrentSession.getUser(), Activity.CREATE_REMOTE_LINK,
                                target + ";" + targetSubPath);
                    }
                }

                registryContext.getHandlerManager(
                            HandlerLifecycleManager.COMMIT_HANDLER_PHASE).createLink(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).createLink(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void removeLink(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);
            context.setResourcePath(resourcePath);
            registryContext.getHandlerManager().removeLink(context);

            // we will be removing the symlink handlers to remove
            Handler handlerToRemove = (Handler) context.getProperty(
                    /*RepositoryConstants.*/ InternalConstants.SYMLINK_TO_REMOVE_PROPERTY_NAME);
            if (handlerToRemove != null) {
                registryContext.getHandlerManager().removeHandler(handlerToRemove,
                        HandlerLifecycleManager.TENANT_SPECIFIC_SYSTEM_HANDLER_PHASE);
            }

            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    try {
                        Collection mountCollection = (Collection) getResource(
                        		InternalUtils.getAbsolutePath(registryContext,
                                        RepositoryConstants.LOCAL_REPOSITORY_BASE_PATH +
                                                RepositoryConstants.SYSTEM_MOUNT_PATH));
                        String[] mountResources = mountCollection.getChildren();
                        Resource resource = null;
                        for (String mountResource : mountResources) {
                            String mountResName =
                                    mountResource.substring(mountResource.lastIndexOf('/') + 1);
                            String relativePath = InternalUtils.getRelativePath(registryContext,
                                    path);
                            if (mountResName.equals(relativePath.replace("/", "-"))) {
                                resource = getResource(mountResource);
                                break;
                            }
                        }

                        if (resource == null) {
                            String msg = "Couldn't find the mount point to remove. ";
                            log.error(msg);
                            throw new RepositoryException(msg);
                        }

                        InternalUtils.getSystemRegistry(this).delete(resource.getPath());
                    } catch (RepositoryResourceNotFoundException ignored) {
                        // There can be situations where the mount resource is not found. In that
                        // case, we can simply ignore this exception being thrown. An example of
                        // such a situation is found in CARBON-12002.
                    }
                    if (repository.resourceExists(path)) {
                        Resource r = repository.get(path);
                        if (!Boolean.toString(true).equals(
                                r.getProperty(/*RepositoryConstants.*/ InternalConstants.REGISTRY_EXISTING_RESOURCE))) {
                            repository.delete(path);
                        }
                    }

                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                path, CurrentSession.getUser(), Activity.REMOVE_LINK, null);
                    }
                }

                registryContext.getHandlerManager(
                            HandlerLifecycleManager.COMMIT_HANDLER_PHASE).removeLink(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).removeLink(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }

    }

    // check in, check out functionality

    public void restore(String path, Reader reader) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setDumpingReader(reader);
            context.setResourcePath(new ResourcePath(path));
            registryContext.getHandlerManager().restore(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    try {
                        CurrentSession.setAttribute(Repository.IS_LOGGING_ACTIVITY,
                                context.isLoggingActivity());
                        repository.restore(path, reader);
                    } finally {
                        CurrentSession.removeAttribute(Repository.IS_LOGGING_ACTIVITY);
                    }
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                path, CurrentSession.getUser(), Activity.RESTORE, null);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).restore(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).restore(context);
                } finally {
                    rollbackTransaction();
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("total read: " + DumpReader.getTotalRead());
                log.debug("total buffered: " + DumpReader.getTotalBuffered());
                log.debug("maximum buffer size: " + DumpReader.getMaxBufferedSize());
                log.debug("total buffer read size: " + DumpReader.getTotalBufferedRead());
            }
        }
    }

    public void dump(String path, Writer writer) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setResourcePath(new ResourcePath(path));
            context.setDumpingWriter(writer);
            registryContext.getHandlerManager().dump(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    repository.dump(path, writer);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).dump(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).dump(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String getEventingServiceURL(String path) throws RepositoryException {
        if (path == null || eventingServiceURLs.size() == 0) {
            return defaultEventingServiceURL;
        }
        Set<Map.Entry<String, String>> entries = eventingServiceURLs.entrySet();
        for (Map.Entry<String, String> e : entries) {
            if (e.getValue() == null) {
                // Clean-up step
                eventingServiceURLs.remove(e.getKey());
            } else if (path.matches(e.getKey())) {
                return e.getValue();
            }
        }
        return defaultEventingServiceURL;

    }

    public void setEventingServiceURL(String path, String eventingServiceURL)
            throws RepositoryException {
        if (path == null) {
            this.defaultEventingServiceURL = eventingServiceURL;
        } else {
            this.eventingServiceURLs.put(path, eventingServiceURL);
        }
    }
    
    public boolean removeVersionHistory(String path, long snapshotId)
    		throws RepositoryException {    	
    	
    	boolean transactionSucceeded = false;    	
        
        try {
            // start the transaction
            beginTransaction();

            versionRepository.removeVersionHistory(path, snapshotId);
            
            // transaction succeeded            
            transactionSucceeded = true;
            
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {                
                
            	rollbackTransaction();                
            }
        }   	
    	
    	return false;
    }
    
    public RegistryService getRegistryService() {
    	return registryService;
    }
	
	public Repository getRepository() {
		return repository;
	}
	
	// Caching features ------------------------------------------------------------------------
	
    private static Cache<RegistryCacheKey, GhostResource> getCache() {
        return InternalUtils.getResourceCache(/*RepositoryConstants.*/InternalConstants.REGISTRY_CACHE_BACKED_ID);
    }
    
    /**
     * This method used to calculate the cache key
     *
     * @param registry Registry
     * @param path     Resource path
     *
     * @return RegistryCacheKey
     */
    private RegistryCacheKey getRegistryCacheKey(Registry registry, String path) {
        String connectionId = "";

        int tenantId;
        if (this.tenantId == MultitenantConstants.INVALID_TENANT_ID) {
            tenantId = CurrentSession.getTenantId();
            if (tenantId == MultitenantConstants.INVALID_TENANT_ID) {
                tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            }
        } else {
            tenantId = this.tenantId;
        }
        
        String resourceCachePath;
    	
    	RegistryContext registryContext = InternalUtils.getRegistryContext(registry);
    	
//        RegistryContext registryContext = ((EmbeddedRegistry) registry).getRegistryContext();
        if (registryContext == null) {
            registryContext = RegistryContext.getBaseInstance();
        }
        if (registry instanceof EmbeddedRegistry) {
            resourceCachePath = path;
        } else {
            resourceCachePath = InternalUtils.getAbsolutePath(registryContext, path);
        }
        DataBaseConfiguration dataBaseConfiguration = null;
        if (dbConfigs.size() > 0) {
            for (String sourcePath : dbConfigs.keySet()) {
                if (resourceCachePath.startsWith(sourcePath)) {
                    resourceCachePath = pathMap.get(sourcePath) + resourceCachePath.substring(sourcePath.length());
                    dataBaseConfiguration = dbConfigs.get(sourcePath);
                    break;
                }
            }
        } else if (cacheIds.size() > 0) {
            for (String sourcePath : cacheIds.keySet()) {
                if (resourceCachePath.startsWith(sourcePath)) {
                    resourceCachePath = pathMap.get(sourcePath) + resourceCachePath.substring(sourcePath.length());
                    connectionId = cacheIds.get(sourcePath);
                    break;
                }
            }
        }
        if (connectionId.length() == 0) {
            if (dataBaseConfiguration == null) {
                dataBaseConfiguration = registryContext.getDefaultDataBaseConfiguration();
            }
            if (dataBaseConfiguration != null) {
                connectionId = (dataBaseConfiguration.getUserName() != null
                        ? dataBaseConfiguration.getUserName().split("@")[0]:dataBaseConfiguration.getUserName()) + "@" + dataBaseConfiguration.getDbUrl();
            }
        }

        return InternalUtils.buildRegistryCacheKey(connectionId, tenantId, resourceCachePath);
    }
    
    @SuppressWarnings("unchecked")
    public Resource get(String path) throws RepositoryException {
    	//RegistryContext registryContext = InternalUtils.getRegistryContext(this);
    	
        if (registryContext != null && registryContext.isCacheEnabled())
        {
            //if (registry.getRegistryContext().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
        	if (getRegistryService().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
                return getResource(path);
            }
            Resource resource;
            RegistryCacheKey registryCacheKey = getRegistryCacheKey(this, path);

            Object ghostResourceObject;
            Cache<RegistryCacheKey, GhostResource> cache = getCache();
            if ((ghostResourceObject = cache.get(registryCacheKey)) == null) {
                resource = getResource(path);
                if (resource.getProperty(RepositoryConstants.REGISTRY_LINK) == null ||
                        resource.getProperty(RepositoryConstants.REGISTRY_MOUNT) != null) {
                    cache.put(registryCacheKey, new GhostResource<Resource>(resource));
                }
            } else {
                if (!AuthorizationUtils.authorize(path, ActionConstants.GET)) {
                    String msg = "User " + CurrentSession.getUser() + " is not authorized to " +
                            "read the resource " + path + ".";
                    log.warn(msg);
                    throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                }
                GhostResource<Resource> ghostResource =
                        (GhostResource<Resource>) ghostResourceObject;
                resource = ghostResource.getResource();
                if (resource == null) {
                    resource = getResource(path);
                    if (resource.getProperty(RepositoryConstants.REGISTRY_LINK) == null ||
                            resource.getProperty(RepositoryConstants.REGISTRY_MOUNT) != null) {
                        ghostResource.setResource(resource);
                    }
                }
            }
            return resource;
        } else {
        	return getResource(path);
        }
    }
    
    @SuppressWarnings("unchecked")
    public Collection get(String path, int start, int pageSize) throws RepositoryException {
    	//RegistryContext registryContext = InternalUtils.getRegistryContext(this);
    	
        if (registryContext != null && registryContext.isCacheEnabled()) {
            //if (registry.getRegistryContext().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
        	if (getRegistryService().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
                return getCollection(path, start, pageSize);
            }
            Collection collection;
            RegistryCacheKey registryCacheKey = getRegistryCacheKey(this, path +
                    ";start=" + start + ";pageSize=" + pageSize);

            Cache<RegistryCacheKey, GhostResource> cache = getCache();
            if (!cache.containsKey(registryCacheKey)) {
                collection = getCollection(path, start, pageSize);
                if (collection.getProperty(RepositoryConstants.REGISTRY_LINK) == null) {
                    cache.put(registryCacheKey, new GhostResource<Resource>(collection));
                }
            } else {
                if (!AuthorizationUtils.authorize(path, ActionConstants.GET)) {
                    String msg = "User " + CurrentSession.getUser() + " is not authorized to " +
                            "read the resource " + path + ".";
                    log.warn(msg);
                    throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                }
                GhostResource<Resource> ghostResource =
                        (GhostResource<Resource>) cache.get(registryCacheKey);
                collection = (Collection) ghostResource.getResource();
                if (collection == null) {
                    collection = getCollection(path, start, pageSize);
                    if (collection.getProperty(RepositoryConstants.REGISTRY_LINK) == null) {
                        ghostResource.setResource(collection);
                    }
                }
            }
            return collection;       	
        } else {
        	return getCollection(path, start, pageSize);
        }
    }
    
    public boolean resourceExists(String path) throws RepositoryException {
//      if (registry.getRegistryContext().isNoCachePath(path)) {
  	if (getRegistryService().isNoCachePath(path)) {
          return checkResourceExists(path);
      }
      Cache<RegistryCacheKey, GhostResource> cache = getCache();
      RegistryCacheKey registryCacheKey = getRegistryCacheKey(this, path);
      if (cache.containsKey(registryCacheKey)) {
          return true;
      } else if (checkResourceExists(path)) {
          cache.put(registryCacheKey, new GhostResource<Resource>(null));
          return true;
      }
      return false;
  }
    
    // test whether this request was made specifically for a tag, comment or a rating.
    private boolean isCommunityFeatureRequest(String path) {
        if (path == null) {
            return false;
        }
        String resourcePath = new ResourcePath(path).getPath();
        if (path.length() > resourcePath.length()) {
            String fragment = path.substring(resourcePath.length());
            for (String temp : new String[] {"tags", "comments", "ratings"}) {
                if (fragment.contains(temp)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public void setTenantId(int tenantId) {
    	this.tenantId = tenantId ;
    }
	
    // Following methods are deprecated and eventually move out of the code ---------------------------------------------------------
    
    ////////////////////////////////////////////////////////
    // Associations
    ////////////////////////////////////////////////////////

    public void addAssociation(String sourcePath, String targetPath, String associationType)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            // Source and target of associations may or may not be resources in the registry. If they
            // don't refer to a resource, they can contain any string value. But if they refer to
            // resources, values should be proper resource paths.

            ResourcePath sourceResourcePath = new ResourcePath(sourcePath);
            if (repository.resourceExists(sourceResourcePath.getPath())) {
                sourcePath = sourceResourcePath.getPathWithVersion();
            }

            ResourcePath targetResourcePath = new ResourcePath(targetPath);
            if (repository.resourceExists(targetResourcePath.getPath())) {
                targetPath = targetResourcePath.getPathWithVersion();
            }

            context.setSourcePath(sourcePath);
            context.setTargetPath(targetPath);
            context.setAssociationType(associationType);
            context.setOldAssociationsOnSource(getAllAssociations(sourcePath));
            context.setOldAssociationsOnTarget(getAllAssociations(targetPath));

            registryContext.getHandlerManager().addAssociation(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    associationDAO.addAssociation(sourcePath, targetPath, associationType);
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                sourcePath, CurrentSession.getUser(), Activity.ADD_ASSOCIATION,
                                associationType + ";" + targetPath);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).addAssociation(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).addAssociation(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void removeAssociation(String sourcePath, String targetPath, String associationType)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            // Source and target of associations may or may not be resources in the registry. If they
            // don't refer to a resource, they can contain any string value. But if they refer to
            // resources, values should be proper resource paths.

            ResourcePath sourceResourcePath = new ResourcePath(sourcePath);
            if (repository.resourceExists(sourceResourcePath.getPath())) {
                sourcePath = sourceResourcePath.getPathWithVersion();
            }

            ResourcePath targetResourcePath = new ResourcePath(targetPath);
            if (repository.resourceExists(targetResourcePath.getPath())) {
                targetPath = targetResourcePath.getPathWithVersion();
            }

            context.setSourcePath(sourcePath);
            context.setTargetPath(targetPath);
            context.setAssociationType(associationType);
            context.setOldAssociationsOnSource(getAllAssociations(sourcePath));
            context.setOldAssociationsOnTarget(getAllAssociations(targetPath));

            registryContext.getHandlerManager().removeAssociation(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    associationDAO.removeAssociation(sourcePath, targetPath, associationType);
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(
                                sourcePath, CurrentSession.getUser(), Activity.REMOVE_ASSOCIATION,
                                associationType + ";" + targetPath);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).removeAssociation(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).removeAssociation(
                            context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public Association[] getAllAssociations(String resourcePath) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            // Source and target of associations may or may not be resources in the registry. If they
            // don't refer to a resource, they can contain any string value. But if they refer to
            // resources, values should be proper resource paths.

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (repository.resourceExists(processedPath.getPath())) {
                resourcePath = processedPath.getPathWithVersion();
            }

            context.setResourcePath(new ResourcePath(resourcePath));

            Association[] associations =
                    registryContext.getHandlerManager().getAllAssociations(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    associations = associationDAO.getAllAssociations(resourcePath);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getAllAssociations(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return associations;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getAllAssociations(
                            context);
                } finally {
                    rollbackTransaction();
                }
            }
        }

    }

    public Association[] getAssociations(String resourcePath, String associationType)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            // Source and target of associations may or may not be resources in the registry. If they
            // don't refer to a resource, they can contain any string value. But if they refer to
            // resources, values should be proper resource paths.

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (repository.resourceExists(processedPath.getPath())) {
                resourcePath = processedPath.getPathWithVersion();
            }

            context.setResourcePath(new ResourcePath(resourcePath));
            context.setAssociationType(associationType);

            Association[] associations =
                    registryContext.getHandlerManager().getAssociations(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    associations =
                            associationDAO.getAllAssociationsForType(resourcePath, associationType);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getAssociations(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return associations;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getAssociations(
                            context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }
    
    ////////////////////////////////////////////////////////
    // Tagging
    ////////////////////////////////////////////////////////

    public void applyTag(String resourcePath, String tag) throws RepositoryException {
        final String ILLEGAL_CHARACTERS_FOR_TAG = ".*[~!@#;%^*+={}\\|\\\\<>\"\'].*";
        Pattern illegalCharactersPattern = Pattern.compile(ILLEGAL_CHARACTERS_FOR_TAG);
        if (illegalCharactersPattern.matcher(tag).matches()) {  //"[~!@#;%^*()+={}[]|\\<>\"\']"
            throw new RepositoryUserContentException("The tag '" + tag + "' contains one or more illegal " +
                    "characters (~!@#;%^*()+={}|\\<>\"\')", RepositoryErrorCodes.INVALID_CHARACTER_IN_NAME);
        }
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {

            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);

            context.setResourcePath(processedPath);
            context.setTag(tag);
            context.setOldTags(getTags(processedPath.getPath()));

            registryContext.getHandlerManager().applyTag(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    if (!processedPath.isCurrentVersion()) {
                        String msg = "Failed to apply tag to the resource " + processedPath +
                                ". Given path refers to an archived version of the resource.";
                        log.error(msg);
                        throw new RepositoryServerContentException(msg);
                    }

                    resourcePath = processedPath.getPath();

                    // break the comma separated words into multiple tags
                    String[] tags = tag.split(",");

                    ResourceImpl resource = tagsDAO.getResourceWithMinimumData(resourcePath);
                    if (resource == null) {
                        String msg = "Failed to apply tag " + tag + " on resource " + resourcePath +
                                ". Resource " + resourcePath + " does not exist.";
                        log.error(msg);
                        throw new RepositoryUserContentException(msg, RepositoryErrorCodes.INVALID_PATH_PROVIDED);
                    }

                    String userName = CurrentSession.getUser();

                    if (!AuthorizationUtils.authorize(resource.getPath(), ActionConstants.GET)) {
                        String msg = "Failed to apply tag " + tag + " on resource " + resourcePath +
                                ". User " + userName + " is not authorized to read the resource.";
                        log.error(msg);
                        throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                    }

                    for (int i = 0; i < tags.length; i++) {

                        tags[i] = tags[i].trim();

                        if (tags[i].length() == 0 || tags[i].equals(" ")) {
                            continue;
                        }

                        if (tagsDAO.taggingExists(tags[i], resource, userName)) {
                            // Already there, don't bother doing it again.
                            continue;
                        }

                        tagsDAO.addTagging(tags[i], resource, userName);
                        if (context.isLoggingActivity()) {
                            registryContext.getLogWriter().addLog(
                                    resourcePath, userName, Activity.TAG, tags[i]);
                        }
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).applyTag(context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).applyTag(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public TaggedResourcePath[] getResourcePathsWithTag(String tag) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setTag(tag);

            TaggedResourcePath[] output =
                    registryContext.getHandlerManager().getResourcePathsWithTag(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    // break the tags from spaces
                    String[] tags = tag.trim().split(",");

                    for (int i = 0; i < tags.length; i++) {
                        tags[i] = tags[i].trim();
                    }

                    Map<String, TaggedResourcePath> taggedPaths =
                            new HashMap<String, TaggedResourcePath>();

                    // We know that the paths list contains strings.
                    @SuppressWarnings("unchecked")
                    List<String> pathList = tagsDAO.getPathsWithAnyTag(tags);
                    for (String path : pathList) {
                        TaggedResourcePath taggedResourcePath = new TaggedResourcePath();
                        taggedResourcePath.setResourcePath(path);

                        ResourceImpl resourceImpl = tagsDAO.getResourceWithMinimumData(path);

                        for (String currentTag : tags) {
                            long count = tagsDAO.getTagCount(resourceImpl, currentTag);
                            taggedResourcePath.addTagCount(currentTag, count);
                            taggedPaths.put(currentTag + path, taggedResourcePath);
                        }
                    }
                    if (output == null || output.length == 0) {
                        java.util.Collection<TaggedResourcePath> taggedPathsSet =
                                taggedPaths.values();
                        output = taggedPathsSet.toArray(
                                new TaggedResourcePath[taggedPathsSet.size()]);
                    } else {
                        for (TaggedResourcePath taggedResourcePath : output) {
                            for (String currentTag : taggedResourcePath.getTagCounts().keySet()) {
                                taggedPaths.put(currentTag + taggedResourcePath.getResourcePath(),
                                        taggedResourcePath);
                            }
                        }
                        java.util.Collection<TaggedResourcePath> taggedPathsSet =
                                taggedPaths.values();
                        output = taggedPathsSet.toArray(
                                new TaggedResourcePath[taggedPathsSet.size()]);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getResourcePathsWithTag(
                        context);
                // transaction successfully finished
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getResourcePathsWithTag(
                            context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public Tag[] getTags(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            context.setResourcePath(new ResourcePath(path));

            Tag[] output = registryContext.getHandlerManager().getTags(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    path = InternalUtils.prepareGeneralPath(path);

                    ResourcePath resourcePath = new ResourcePath(path);
                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
                    ResourceImpl resourceImpl;
                    if (versionedPath.getVersion() == -1) {
                        resourceImpl = tagsDAO.getResourceWithMinimumData(resourcePath.getPath());
                    } else {
                        resourceImpl = (ResourceImpl) versionRepository.getMetaData(versionedPath);
                    }

                    if (resourceImpl == null) {
                        String msg = "Failed to get tags of " + path +
                                ". The resource doesn't exists.";
                        log.debug(msg);
                        output = new Tag[0];
                    } else {
                        output = tagsDAO.getTagsWithCount(resourceImpl);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getTags(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getTags(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void removeTag(String path, String tag) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(path);

            context.setResourcePath(processedPath);
            context.setTag(tag);
            context.setOldTags(getTags(processedPath.getPath()));

            registryContext.getHandlerManager().removeTag(context);
            if (!context.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    if (!processedPath.isCurrentVersion()) {
                        String msg = "Failed to remove tag from the resource " + processedPath +
                                ". Given path refers to an archived version of the resource.";
                        log.error(msg);
                        throw new RepositoryServerContentException(msg);
                    }

                    path = processedPath.getPath();
                    path = InternalUtils.prepareGeneralPath(path);

                    String user = CurrentSession.getUser();
                    UserRealm userRealm = CurrentSession.getUserRealm();

                    boolean adminUser = false;
                    // getting the realm config to get admin role, user details
                    RealmConfiguration realmConfig;
                    try {
                        realmConfig = userRealm.getRealmConfiguration();
                    } catch (UserStoreException e) {
                        String msg = "Failed to retrieve realm configuration.";
                        log.error(msg, e);
                        throw new RepositorySessionException(msg, e);
                    }

                    // check is the user belongs to the admin role
                    try {
                        String[] roles = userRealm.getUserStoreManager().getRoleListOfUser(user);
                        String adminRoleName = realmConfig.getAdminRoleName();
                        if (RepositoryUtils.containsString(adminRoleName, roles)) {
                            adminUser = true;
                        }

                    } catch (UserStoreException e) {

                        String msg = "Failed to get roles of the current user. " +
                                "User will be considered as non-admin user.\n" + e.getMessage();
                        log.error(msg, e);
                        adminUser = false;
                    }

                    // check if the user is the admin user
                    // TODO - do we really need to do this check?  Won't this user always be in the
                    // admin role?

                    String adminUsername = realmConfig.getAdminUserName();
                    if (adminUsername.equals(user)) {
                        adminUser = true;
                    }


                    if (adminUser) {
                        ResourceImpl resource = tagsDAO.getResourceWithMinimumData(path);
                        tagsDAO.removeTags(resource, tag);
                    } else {
                        ResourceImpl resource = (ResourceImpl) repository.getMetaData(path);

                        String author = resource.getAuthorUserName();
                        if (user.equals(author)) {
                            tagsDAO.removeTags(resource, tag);
                        } else {
                            tagsDAO.removeTags(resource, tag, user);
                        }
                    }
                    if (context.isLoggingActivity()) {
                        registryContext.getLogWriter().addLog(path, user, Activity.REMOVE_TAG, tag);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).removeTag(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).removeTag(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public String addComment(String resourcePath, org.wso2.carbon.repository.Comment comment)
            throws org.wso2.carbon.repository.exceptions.RepositoryException {
        return addComment(resourcePath, (CommentImpl) comment);
    }

    ////////////////////////////////////////////////////////
    // Commenting
    ////////////////////////////////////////////////////////

    public String addComment(String resourcePath, CommentImpl comment) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);

            context.setResourcePath(processedPath);
            context.setComment(comment);
            context.setOldComments(getComments(processedPath.getPath()));
            String output = registryContext.getHandlerManager().addComment(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    if (!processedPath.isCurrentVersion()) {
                        String msg = "Failed to add comment to the resource " + processedPath +
                                ". Given path refers to an archived version of the resource.";
                        log.error(msg);
                        throw new RepositoryServerContentException(msg);
                    }

                    resourcePath = processedPath.getPath();

                    String userName = CurrentSession.getUser();

                    if (!AuthorizationUtils.authorize(resourcePath, ActionConstants.GET)) {
                        String msg =
                                "Failed to apply comment " + comment.getText() + " on resource " +
                                        resourcePath + ". User " + userName +
                                        " is not authorized to read the " +
                                        "resource.";
                        log.error(msg);
                        throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                    }

                    try {
                        ResourceImpl resource =
                                commentsDAO.getResourceWithMinimumData(resourcePath);
                        if (resource == null) {
                            String msg =
                                    "Failed to add comment " + comment.getText() + " to resource " +
                                            resourcePath + ". Resource " + resourcePath +
                                            " does not exist.";
                            log.error(msg);
                            throw new RepositoryResourceNotFoundException(msg, RepositoryErrorCodes.RESOURCE_UNAVAILABLE);
                        }

                        int commentId = commentsDAO.addComment(resource, userName, comment);
                        String commentPath =
                                resourcePath + RepositoryConstants.URL_SEPARATOR + "comments:" +
                                        commentId;
                        if (context.isLoggingActivity()) {
                            registryContext.getLogWriter().addLog(
                                    resourcePath, userName, Activity.COMMENT, comment.getText());
                        }

                        output = commentPath;
                    } catch (RepositoryException e) {
                        String msg =
                                "Failed to add comment " + comment.getText() + " on resource " +
                                        resourcePath + ". User " + userName + ".";
                        log.error(msg);
                        throw new RepositoryServerContentException(msg);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).addComment(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).addComment(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void editComment(String commentPath, String text) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(commentPath);

            CommentImpl comment = new CommentImpl();
            comment.setCommentPath(commentPath);
            comment.setText(text);

            context.setResourcePath(processedPath);
            context.setComment(comment);
            context.setOldComments(getComments(commentPath));
            registryContext.getHandlerManager().editComment(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    String userName = CurrentSession.getUser();
                    String[] parts = commentPath.split(RepositoryConstants.URL_SEPARATOR);
                    if (parts.length == 2) {
                        String resourcePath = parts[0];
                        String commentPart = parts[1];
                        if (!AuthorizationUtils.authorize(resourcePath, ActionConstants.GET)) {
                            String msg = "Failed to edit comment " + text + " on resource " +
                                    resourcePath + ". User " + userName +
                                    " is not authorized to read " +
                                    "the resource.";
                            log.error(msg);
                            throw new RepositoryException(msg);
                        }
                        if (commentPart.startsWith("comments:")) {
                            String commentId = commentPart.substring(9);
                            commentsDAO.updateComment(Long.parseLong(commentId), text);
                        }
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).editComment(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).editComment(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void removeComment(String commentPath) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(commentPath);

            CommentImpl comment = new CommentImpl();
            comment.setCommentPath(commentPath);

            context.setResourcePath(processedPath);
            context.setComment(comment);
            context.setOldComments(getComments(commentPath));
            registryContext.getHandlerManager().removeComment(context);

            if (!context.isProcessingComplete()) {

                if (!processedPath.isCurrentVersion()) {
                    String msg = "Failed to remove tag from the resource " + processedPath +
                            ". Given path refers to an archived version of the resource.";
                    log.error(msg);
                    throw new RepositoryServerContentException(msg);
                }

                String user = CurrentSession.getUser();
                UserRealm userRealm = CurrentSession.getUserRealm();

                boolean adminUser = false;
                // getting the realm config to get admin role, user details
                RealmConfiguration realmConfig;
                try {
                    realmConfig = userRealm.getRealmConfiguration();
                } catch (UserStoreException e) {
                    String msg = "Failed to retrieve realm configuration.";
                    log.error(msg, e);
                    throw new RepositorySessionException(msg, e);
                }

                // check is the user belongs to the admin role
                try {
                    String[] roles = userRealm.getUserStoreManager().getRoleListOfUser(user);
                    String adminRoleName = realmConfig.getAdminRoleName();
                    if (RepositoryUtils.containsString(adminRoleName, roles)) {
                        adminUser = true;
                    }

                } catch (UserStoreException e) {

                    String msg = "Failed to get roles of the current user. " +
                            "User will be considered as non-admin user.\n" + e.getMessage();
                    log.error(msg, e);
                    adminUser = false;
                }

                // check if the user is the admin user
                // TODO - do we really need to do this check?  Won't this user always be in the
                // admin role?

                String adminUsername = realmConfig.getAdminUserName();
                if (adminUsername.equals(user)) {
                    adminUser = true;
                }

                String[] parts = commentPath.split(RepositoryConstants.URL_SEPARATOR);
                String commentPart = parts[1];
                String commentId = null;
                if (parts.length == 2 && commentPart.startsWith("comments:")) {
                    commentId = parts[1].substring(9);
                }

                CommentImpl temp = commentsDAO.getComment(Long.parseLong(commentId),
                        processedPath.getPath());
                if (adminUser) {
                    commentsDAO.deleteComment(Long.parseLong(commentId));
                } else {
                    ResourceImpl resource =
                            (ResourceImpl) repository.getMetaData(processedPath.getPath());

                    String author = resource.getAuthorUserName();
                    if (user.equals(author)) {
                        commentsDAO.deleteComment(Long.parseLong(commentId));
                    } else {
                        if (temp != null && user.equals(temp.getUser())) {
                            commentsDAO.deleteComment(Long.parseLong(commentId));
                        } else {
                            String msg = "User: " + user +
                                    " is not authorized to delete the comment on the resource: " +
                                    processedPath.getPath();
                            log.warn(msg);
                            throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                        }
                    }
                }
                if (context.isLoggingActivity()) {
                    if (temp != null) {
                        registryContext.getLogWriter().addLog(processedPath.getPath(),
                                user, Activity.DELETE_COMMENT, temp.getText());
                    } else {
                        registryContext.getLogWriter().addLog(processedPath.getPath(),
                                user, Activity.DELETE_COMMENT, commentPath);
                    }
                }
            }
            registryContext.getHandlerManager(
                    HandlerLifecycleManager.COMMIT_HANDLER_PHASE).removeComment(context);
            // transaction succeeded
            transactionSucceeded = true;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).removeComment(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public Comment[] getComments(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath resourcePath = new ResourcePath(path);

            context.setResourcePath(resourcePath);
            Comment[] output = registryContext.getHandlerManager().getComments(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
                    ResourceImpl resourceImpl;
                    if (versionedPath.getVersion() == -1) {
                        resourceImpl =
                                commentsDAO.getResourceWithMinimumData(resourcePath.getPath());
                    } else {
                        resourceImpl = (ResourceImpl) versionRepository.getMetaData(versionedPath);
                    }

                    if (resourceImpl == null) {
                        output = new CommentImpl[0];
                    } else {
                        output = commentsDAO.getComments(resourceImpl);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getComments(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return output;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getComments(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    ////////////////////////////////////////////////////////
    // Ratings
    ////////////////////////////////////////////////////////

    public void rateResource(String resourcePath, int rating) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            String userName = CurrentSession.getUser();

            context.setResourcePath(new ResourcePath(resourcePath));
            context.setRating(rating);
            context.setOldRating(getRating(resourcePath, userName));
            context.setOldAverageRating(getAverageRating(resourcePath));
            registryContext.getHandlerManager().rateResource(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    ResourcePath processedPath = new ResourcePath(resourcePath);
                    if (!processedPath.isCurrentVersion()) {
                        String msg = "Failed to apply rating to the resource " + processedPath +
                                ". Given path refers to an archived version of the resource.";
                        log.error(msg);
                        throw new RepositoryServerContentException(msg);
                    }

                    resourcePath = processedPath.getPath();

                    ResourceImpl resourceImpl = ratingsDAO.getResourceWithMinimumData(resourcePath);

                    if (resourceImpl != null) {

                        if (!AuthorizationUtils.authorize(resourcePath, ActionConstants.GET)) {
                            String msg =
                                    "Failed to rate resource " + resourcePath + " with rating " +
                                            rating +
                                            ". User " + userName +
                                            " is not authorized to read the resource.";
                            log.error(msg);
                            throw new RepositoryAuthException(msg, RepositoryErrorCodes.USER_NOT_AUTHORISED);
                        }

                        // check for the existing rating.
                        int rateID = ratingsDAO.getRateID(resourceImpl, userName);
                        if (rateID > -1) {
                            if (rating == 0) {
                                ratingsDAO.removeRating(resourceImpl,rateID);
                            } else {
                                ratingsDAO.updateRating(resourceImpl, rateID, rating);
                            }

                            if (log.isDebugEnabled()) {
                                log.debug("Updated the rating on the resource " +
                                        resourcePath + " by user " + userName);
                            }
                        } else {
                            ratingsDAO.addRating(resourceImpl, userName, rating);
                        }

                        if (context.isLoggingActivity()) {
                            registryContext.getLogWriter().addLog(resourcePath,
                                    userName, Activity.RATING, Integer.toString(rating));
                        }

                    } else {
                        String msg = "Rate on Null resource " + resourcePath;
                        log.error(msg);
                        throw new RepositoryResourceNotFoundException(msg, RepositoryErrorCodes.RESOURCE_UNAVAILABLE);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).rateResource(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).rateResource(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public float getAverageRating(String path) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();
            ResourcePath resourcePath = new ResourcePath(path);

            context.setResourcePath(resourcePath);
            float rating = registryContext.getHandlerManager().getAverageRating(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
                    ResourceImpl resourceImpl;
                    if (versionedPath.getVersion() == -1) {
                        resourceImpl =
                                ratingsDAO.getResourceWithMinimumData(resourcePath.getPath());
                    } else {
                        resourceImpl = (ResourceImpl) versionRepository.getMetaData(versionedPath);
                    }

                    if (resourceImpl == null) {
                        String msg = "Rate on Null resource " + path;
                        log.debug(msg);
                        rating = 0;
                    } else {
                        rating = ratingsDAO.getAverageRating(resourceImpl);
                    }
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getAverageRating(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return rating;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getAverageRating(
                            context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public int getRating(String path, String userName) throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();
            ResourcePath resourcePath = new ResourcePath(path);

            context.setResourcePath(resourcePath);
            context.setUserName(userName);
            int rating = registryContext.getHandlerManager().getRating(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {

                    VersionedPath versionedPath = InternalUtils.getVersionedPath(resourcePath);
                    ResourceImpl resourceImpl;
                    if (versionedPath.getVersion() == -1) {
                        resourceImpl =
                                ratingsDAO.getResourceWithMinimumData(resourcePath.getPath());
                    } else {
                        resourceImpl = (ResourceImpl) versionRepository.getMetaData(versionedPath);
                    }
                    if (resourceImpl == null) {
                        String msg = "Rate on Null resource " + path;
                        log.error(msg);
                        throw new RepositoryResourceNotFoundException(msg, RepositoryErrorCodes.RESOURCE_UNAVAILABLE);
                    }
                    rating = ratingsDAO.getRating(resourceImpl, userName);
                }

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).getRating(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
            return rating;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).getRating(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }
    
    /*
    public LogEntryCollection getLogCollection(String resourcePath,
                                               int action,
                                               String userName,
                                               Date from,
                                               Date to,
                                               boolean recentFirst) throws RepositoryException {
        boolean transactionSucceeded = false;
        try {
            // start the transaction
            beginTransaction();

            LogEntryCollection logEntryCollection = new LogEntryCollection();
            logEntryCollection.setLogCount(
                    logsDAO.getLogsCount(resourcePath, action, userName, from, to, recentFirst));

            logEntryCollection.setDataAccessManager(dataAccessManager);
            logEntryCollection.setResourcePath(resourcePath);
            logEntryCollection.setAction(action);
            logEntryCollection.setUserName(userName);
            logEntryCollection.setFrom(from);
            logEntryCollection.setTo(to);
            logEntryCollection.setRecentFirst(recentFirst);

            // transaction succeeded
            transactionSucceeded = true;

            return logEntryCollection;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }*/

    public String[] getAvailableAspects() {
        // we are using CallerTenantId instead of tenantId to preserve the tenant information
        // even this is called from a system registry, which anyway make just tenantId always = 0,
        // but keep the callerTenantId value to it is callers real tenant id
        return registryContext.getAspectNames(CurrentSession.getCallerTenantId());
    }

    public void associateAspect(String resourcePath, String aspectName) throws RepositoryException {

        boolean transactionSucceeded = false;
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (!processedPath.isCurrentVersion()) {
                String msg = "Failed to associate aspectName to the resource " + processedPath +
                        ". Given path refers to an archived version of the resource.";
                log.error(msg);
                throw new RepositoryServerContentException(msg);
            }

            resourcePath = processedPath.getPath();

            //TODO need to do the security validation here
            Resource resource = getResource(resourcePath);
            if ((resource.getAspects() == null) || (!resource.getAspects().contains(aspectName))) {
                Aspect aspect = getAspect(aspectName);
                if (aspect == null) {
                    throw new RepositoryResourceNotFoundException("Couldn't find aspectName '" + aspectName + "'", RepositoryErrorCodes.RESOURCE_UNAVAILABLE);
                }
                aspect.associate(resource, this);
                resource.addAspect(aspectName);
                String path = resource.getPath();
                put(path, resource);
                registryContext.getLogWriter().addLog(
                        path, CurrentSession.getUser(), Activity.ASSOCIATE_ASPECT,
                        aspectName);
            }

            // transaction succeeded
            transactionSucceeded = true;

        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

    private Aspect getAspect(String name) throws RepositoryException {
        // we are using CallerTenantId instead of tenantId to preserve the tenant information
        // even this is called from a system registry, which anyway make just tenantId always = 0,
        // but keep the callerTenantId value to it is callers real tenant id
        return registryContext.getAspect(name, CurrentSession.getCallerTenantId());
    }

    public void invokeAspect(String resourcePath, String aspectName, String action)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (!processedPath.isCurrentVersion()) {
                String msg = "Failed to invoke aspect of the resource " + processedPath +
                        ". Given path refers to an archived version of the resource.";
                log.error(msg);
                throw new RepositoryServerContentException(msg);
            }
            resourcePath = processedPath.getPath();

            Resource resource = getResource(resourcePath);

            Aspect aspect = getResourceAspect(resource, aspectName);
            context.setOldResource(get(resourcePath));
            context.setResource(resource);

            //        List aspectNames = resource.getPropertyValues(Aspect.AVAILABLE_ASPECTS);
            //        if (aspectNames == null) {
            //            throw new RepositoryException("No aspect are associated with the resource");
            //        }
            
            context.setAspect(aspect); // Shazni
            context.setAction(action);
            registryContext.getHandlerManager().invokeAspect(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    aspect.invoke(context, action);
                }
                resource.discard();

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).invokeAspect(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).invokeAspect(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    public void invokeAspect(String resourcePath, String aspectName, String action,
                             Map<String, String> parameters)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (!processedPath.isCurrentVersion()) {
                String msg = "Failed to invoke aspect of the resource " + processedPath +
                        ". Given path refers to an archived version of the resource.";
                log.error(msg);
                throw new RepositoryServerContentException(msg);
            }
            resourcePath = processedPath.getPath();

            Resource resource = getResource(resourcePath);

            Aspect aspect = getResourceAspect(resource, aspectName);
            context.setOldResource(get(resourcePath));
            context.setResource(resource);

            for (Map.Entry<String, String> e : parameters.entrySet()) {
                context.setProperty(e.getKey(), e.getValue());
            }

            context.setProperty("parameterNames",
                    Collections.unmodifiableSet(parameters.keySet()));

            //        List aspectNames = resource.getPropertyValues(Aspect.AVAILABLE_ASPECTS);
            //        if (aspectNames == null) {
            //            throw new RepositoryException("No aspect are associated with the resource");
            //        }
            
            context.setAspect(aspect); // Shazni
            context.setAction(action);
            registryContext.getHandlerManager().invokeAspect(context);
            if (!context.isSimulation()) {
//            if (!InternalUtils.isSimulation()) {
                if (!context.isProcessingComplete()) {
                    aspect.invoke(context, action, parameters);
                }
                resource.discard();

                registryContext.getHandlerManager(
                        HandlerLifecycleManager.COMMIT_HANDLER_PHASE).invokeAspect(context);
                // transaction succeeded
                transactionSucceeded = true;
            }
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                try {
                    registryContext.getHandlerManager(
                            HandlerLifecycleManager.ROLLBACK_HANDLER_PHASE).invokeAspect(context);
                } finally {
                    rollbackTransaction();
                }
            }
        }
    }

    private Aspect getResourceAspect(Resource resource, String aspectName)
            throws RepositoryException {

        boolean transactionSucceeded = false;
        try {
            // start the transaction
            beginTransaction();

            Aspect aspect = getAspect(aspectName);
            if (aspect == null) {
                throw new RepositoryResourceNotFoundException("Aspect '" + aspectName + "' is not registered!", RepositoryErrorCodes.RESOURCE_UNAVAILABLE);
            }

            // Confirm this aspect is associated with this Resource
            List<String> resourceAspects = resource.getAspects();
            if (resourceAspects == null || !resourceAspects.contains(aspectName)) {
                throw new RepositoryServerContentException("Resource at '" + resource.getPath() +
                        "' not associated with aspect '" + aspectName + "'");
            }

            // transaction succeeded
            transactionSucceeded = true;

            return aspect;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

    public String[] getAspectActions(String resourcePath, String aspectName)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();

            ResourcePath processedPath = new ResourcePath(resourcePath);
            if (!processedPath.isCurrentVersion()) {
                String msg = "Failed to get aspect actions of the resource " + processedPath +
                        ". Given path refers to an archived version of the resource.";
                log.error(msg);
                throw new RepositoryServerContentException(msg);
            }
            resourcePath = processedPath.getPath();

            Resource resource = getResource(resourcePath);
            Aspect aspect = getResourceAspect(resource, aspectName);

            context.setResource(resource);
            String[] actions = aspect.getAvailableActions(context);

            // transaction succeeded
            transactionSucceeded = true;

            return actions;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }
    
    public boolean addAspect(String name, Aspect aspect)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();
            // we are using CallerTenantId instead of tenantId to preserve the tenant information
            // even this is called from a system registry, which anyway makes tenantId always = 0,
            // but keep the callerTenantId value to it is callers real tenant id
            registryContext.addAspect(name, aspect, CurrentSession.getCallerTenantId());
            if (!context.isProcessingComplete()) {
                // transaction succeeded
                transactionSucceeded = true;
                return true;
            }
            return false;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }

    public boolean removeAspect(String name)
            throws RepositoryException {
        boolean transactionSucceeded = false;
//        RequestContext context = new RequestContext(this, repository, versionRepository);
        RequestContext context = new RequestContext(this);
        try {
            // start the transaction
            beginTransaction();
            // we are using CallerTenantId instead of tenantId to preserve the tenant information
            // even this is called from a system registry, which anyway makes tenantId always = 0,
            // but keep the callerTenantId value to it is callers real tenant id
            registryContext.removeAspect(name, CurrentSession.getCallerTenantId());
            if (!context.isProcessingComplete()) {
                // transaction succeeded
                transactionSucceeded = true;
                return true;
            }
            return false;
        } finally {
            if (transactionSucceeded) {
                commitTransaction();
            } else {
                rollbackTransaction();
            }
        }
    }
    
	@Override
	public String getResourceMediaTypes() throws RepositoryException {
		return resourceMediaTypes;
	}

	@Override
	public void setResourceMediaTypes(String resourceMediaTypes) throws RepositoryException {
		this.resourceMediaTypes = resourceMediaTypes;
	}
	
	@Override
	public String getCollectionMediaTypes() throws RepositoryException {
		return collectionMediaTypes;
	}

	@Override
	public void setCollectionMediaTypes(String collectionMediaTypes) throws RepositoryException {
		this.collectionMediaTypes = collectionMediaTypes;
	}
	
    public String getCustomUIMediaTypes() throws RepositoryException {
    	return customUIMediaTypes;
    }


    public void setCustomUIMediaTypes(String customUIMediaTypes) throws RepositoryException {
    	this.customUIMediaTypes = customUIMediaTypes;
    }
}
