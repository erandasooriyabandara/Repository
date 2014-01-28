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
package org.wso2.carbon.registry.core.caching;

import java.io.Reader;
import java.io.Writer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.cache.Cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.registry.core.CommentImpl;
import org.wso2.carbon.registry.core.config.DataBaseConfiguration;
import org.wso2.carbon.registry.core.config.Mount;
import org.wso2.carbon.registry.core.config.RegistryContext;
import org.wso2.carbon.registry.core.jdbc.EmbeddedRegistry;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.core.utils.AuthorizationUtils;
import org.wso2.carbon.registry.core.utils.InternalUtils;
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
import org.wso2.carbon.repository.config.RemoteConfiguration;
import org.wso2.carbon.repository.exceptions.RepositoryAuthException;
import org.wso2.carbon.repository.exceptions.RepositoryErrorCodes;
import org.wso2.carbon.repository.exceptions.RepositoryException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

/**
 * CacheBackedRegistry has wrapped from original Registry interface to support caching
 */
public class CacheBackedRegistry implements Registry {

    /**
     * wrapped this original registry object from CachedBackedRegistry
     */
    private Registry registry;

    private int tenantId = MultitenantConstants.INVALID_TENANT_ID;

    private Map<String, String> cacheIds =
            new HashMap<String, String>();
    private Map<String, DataBaseConfiguration> dbConfigs =
            new HashMap<String, DataBaseConfiguration>();
    private Map<String, String> pathMap =
            new HashMap<String, String>();

    private static Cache<RegistryCacheKey, GhostResource> getCache() {
        return InternalUtils.getResourceCache(RepositoryConstants.REGISTRY_CACHE_BACKED_ID);
    }

    private static final Log log = LogFactory.getLog(CacheBackedRegistry.class);

    public CacheBackedRegistry(Registry registry) {
        this.registry = registry;
        RegistryContext registryContext = RegistryContext.getBaseInstance();
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

    public CacheBackedRegistry(Registry registry, int tenantId) {
        this(registry);
        this.tenantId = tenantId;
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
        //if (registry.getRegistryContext().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
    	if (registry.getRegistryService().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
            return registry.get(path);
        }
        Resource resource;
        RegistryCacheKey registryCacheKey = getRegistryCacheKey(registry, path);

        Object ghostResourceObject;
        Cache<RegistryCacheKey, GhostResource> cache = getCache();
        if ((ghostResourceObject = cache.get(registryCacheKey)) == null) {
            resource = registry.get(path);
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
                resource = registry.get(path);
                if (resource.getProperty(RepositoryConstants.REGISTRY_LINK) == null ||
                        resource.getProperty(RepositoryConstants.REGISTRY_MOUNT) != null) {
                    ghostResource.setResource(resource);
                }
            }
        }
        return resource;
    }

    @SuppressWarnings("unchecked")
    public Collection get(String path, int start, int pageSize) throws RepositoryException {
        //if (registry.getRegistryContext().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
    	if (registry.getRegistryService().isNoCachePath(path) || isCommunityFeatureRequest(path)) {
            return registry.get(path, start, pageSize);
        }
        Collection collection;
        RegistryCacheKey registryCacheKey = getRegistryCacheKey(registry, path +
                ";start=" + start + ";pageSize=" + pageSize);

        Cache<RegistryCacheKey, GhostResource> cache = getCache();
        if (!cache.containsKey(registryCacheKey)) {
            collection = registry.get(path, start, pageSize);
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
                collection = registry.get(path, start, pageSize);
                if (collection.getProperty(RepositoryConstants.REGISTRY_LINK) == null) {
                    ghostResource.setResource(collection);
                }
            }
        }
        return collection;
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

    public boolean resourceExists(String path) throws RepositoryException {
//        if (registry.getRegistryContext().isNoCachePath(path)) {
    	if (registry.getRegistryService().isNoCachePath(path)) {
            return registry.resourceExists(path);
        }
        Cache<RegistryCacheKey, GhostResource> cache = getCache();
        RegistryCacheKey registryCacheKey = getRegistryCacheKey(registry, path);
        if (cache.containsKey(registryCacheKey)) {
            return true;
        } else if (registry.resourceExists(path)) {
            cache.put(registryCacheKey, new GhostResource<Resource>(null));
            return true;
        }
        return false;
    }

    public Resource getMetaData(String path) throws RepositoryException {
        return registry.getMetaData(path);
    }

    public String importResource(String suggestedPath, String sourceURL, Resource resource)
            throws RepositoryException {
        return registry.importResource(suggestedPath, sourceURL, resource);
    }

    public String rename(String currentPath, String newName) throws RepositoryException {
        return registry.rename(currentPath, newName);
    }

    public String move(String currentPath, String newPath) throws RepositoryException {
        return registry.move(currentPath, newPath);
    }

    public String copy(String sourcePath, String targetPath) throws RepositoryException {
        return registry.copy(sourcePath, targetPath);
    }

    public void createVersion(String path) throws RepositoryException {
        registry.createVersion(path);
    }

    public String[] getVersions(String path) throws RepositoryException {
        return registry.getVersions(path);
    }

    public void restoreVersion(String versionPath) throws RepositoryException {
        registry.restoreVersion(versionPath);
    }

    public void addAssociation(String sourcePath, String targetPath, String associationType)
            throws RepositoryException {
        registry.addAssociation(sourcePath, targetPath, associationType);
    }

    public void removeAssociation(String sourcePath, String targetPath, String associationType)
            throws RepositoryException {
        registry.removeAssociation(sourcePath, targetPath, associationType);
    }

    public Association[] getAllAssociations(String resourcePath) throws RepositoryException {
        return registry.getAllAssociations(resourcePath);
    }

    public Association[] getAssociations(String resourcePath, String associationType)
            throws RepositoryException {
        return registry.getAssociations(resourcePath, associationType);
    }

    public void applyTag(String resourcePath, String tag) throws RepositoryException {
        registry.applyTag(resourcePath, tag);
    }

    public TaggedResourcePath[] getResourcePathsWithTag(String tag) throws RepositoryException {
        return registry.getResourcePathsWithTag(tag);
    }

    public Tag[] getTags(String resourcePath) throws RepositoryException {
        return registry.getTags(resourcePath);
    }

    public void removeTag(String path, String tag) throws RepositoryException {
        registry.removeTag(path, tag);
    }

    public String addComment(String resourcePath, CommentImpl comment) throws RepositoryException {
        return registry.addComment(resourcePath, comment);
    }

    public void editComment(String commentPath, String text) throws RepositoryException {
        registry.editComment(commentPath, text);
    }

    public void removeComment(String commentPath) throws RepositoryException {
        registry.removeComment(commentPath);
    }

    public Comment[] getComments(String resourcePath) throws RepositoryException {
        return registry.getComments(resourcePath);
    }

    public void rateResource(String resourcePath, int rating) throws RepositoryException {
        registry.rateResource(resourcePath, rating);
    }

    public float getAverageRating(String resourcePath) throws RepositoryException {
        return registry.getAverageRating(resourcePath);
    }

    public int getRating(String path, String userName) throws RepositoryException {
        return registry.getRating(path, userName);
    }

    public Collection executeQuery(String path, Map parameters) throws RepositoryException {
        return registry.executeQuery(path, parameters);
    }

    public Activity[] getLogs(String resourcePath, int action, String userName, Date from, Date to,
                              boolean recentFirst) throws RepositoryException {
        return registry.getLogs(resourcePath, action, userName, from, to, recentFirst);
    }

//    public LogEntryCollection getLogCollection(String resourcePath, int action, String userName,
//                                               Date from, Date to, boolean recentFirst)
//            throws RepositoryException {
//        return registry.getLogCollection(resourcePath, action, userName, from, to, recentFirst);
//    }

    public String[] getAvailableAspects() {
        return registry.getAvailableAspects();
    }

    public void associateAspect(String resourcePath, String aspect) throws RepositoryException {
        registry.associateAspect(resourcePath, aspect);
    }

    public void invokeAspect(String resourcePath, String aspectName, String action)
            throws RepositoryException {
        registry.invokeAspect(resourcePath, aspectName, action);
    }

    public void invokeAspect(String resourcePath, String aspectName, String action,
                             Map<String, String> parameters)
            throws RepositoryException {
        registry.invokeAspect(resourcePath, aspectName, action, parameters);
    }

    public String[] getAspectActions(String resourcePath, String aspectName)
            throws RepositoryException {
        return registry.getAspectActions(resourcePath, aspectName);
    }

    public RegistryContext getRegistryContext() {
    	RegistryContext registryContext = InternalUtils.getRegistryContext(registry);
        return registryContext;
    }

    public Collection searchContent(String keywords) throws RepositoryException {
        return registry.searchContent(keywords);
    }

    public void createLink(String path, String target) throws RepositoryException {
        registry.createLink(path, target);
    }

    public void createLink(String path, String target, String subTargetPath)
            throws RepositoryException {
        registry.createLink(path, target, subTargetPath);
    }

    public void removeLink(String path) throws RepositoryException {
        registry.removeLink(path);
    }

    public void restore(String path, Reader reader) throws RepositoryException {
        registry.restore(path, reader);
    }

    public void dump(String path, Writer writer) throws RepositoryException {
        registry.dump(path, writer);
    }

    public String getEventingServiceURL(String path) throws RepositoryException {
        return registry.getEventingServiceURL(path);
    }

    public void setEventingServiceURL(String path, String eventingServiceURL)
            throws RepositoryException {
        registry.setEventingServiceURL(path, eventingServiceURL);
    }

    public boolean removeAspect(String aspect) throws RepositoryException {
        return registry.removeAspect(aspect);
    }

    public boolean addAspect(String name, Aspect aspect) throws RepositoryException {
        return registry.addAspect(name, aspect);
    }

    public void beginTransaction() throws RepositoryException {
        registry.beginTransaction();
    }

    public void commitTransaction() throws RepositoryException {
        registry.commitTransaction();
    }

    public void rollbackTransaction() throws RepositoryException {
        registry.rollbackTransaction();
    }

    public Resource newResource() throws RepositoryException {
        return registry.newResource();
    }

    public Collection newCollection() throws RepositoryException {
        return registry.newCollection();
    }

    public String put(String suggestedPath, Resource resource) throws RepositoryException {
        return registry.put(suggestedPath, resource);
    }

    public void delete(String path) throws RepositoryException {
        registry.delete(path);
    }

    public String addComment(String resourcePath, org.wso2.carbon.repository.Comment comment)
            throws org.wso2.carbon.repository.exceptions.RepositoryException {
        return registry.addComment(resourcePath, comment);
    }
    
    public boolean removeVersionHistory(String path, long snapshotId)
    		throws RepositoryException {
    	return registry.removeVersionHistory(path, snapshotId);
    }

	@Override
	public RegistryService getRegistryService() {
		return registry.getRegistryService();
	}
	
    public String getResourceMediaTypes() throws RepositoryException {
    	return registry.getResourceMediaTypes();
    }
    
    public void setResourceMediaTypes(String resourceMediaTypes) throws RepositoryException {
    	registry.setResourceMediaTypes(resourceMediaTypes);
    }
    
    public String getCollectionMediaTypes() throws RepositoryException {
    	return registry.getCollectionMediaTypes();
    }

    public void setCollectionMediaTypes(String collectionMediaTypes) throws RepositoryException {
    	registry.setCollectionMediaTypes(collectionMediaTypes);
    }
    
    public String getCustomUIMediaTypes() throws RepositoryException {
    	return registry.getCustomUIMediaTypes();
    }

    public void setCustomUIMediaTypes(String customUIMediaTypes) throws RepositoryException {
    	registry.setCustomUIMediaTypes(customUIMediaTypes);
    }
    
    public Registry getEmbeddedRegistry() {
    	return registry;
    }

}
