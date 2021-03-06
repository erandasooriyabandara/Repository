/*
 * Copyright (c) 2006, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.repository.core.handlers;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.repository.api.RepositoryConstants;
import org.wso2.carbon.repository.api.ResourcePath;
import org.wso2.carbon.repository.api.exceptions.RepositoryErrorCodes;
import org.wso2.carbon.repository.api.exceptions.RepositoryException;
import org.wso2.carbon.repository.api.exceptions.RepositoryUserContentException;
import org.wso2.carbon.repository.core.utils.InternalConstants;
import org.wso2.carbon.repository.core.utils.InternalUtils;

/**
 * Manages execution of EditProcessor implementations of custom UIs. EditProcessors are registered
 * in this class at the initialization of the registry.
 */
public class CustomEditManager {

    private static Log log = LogFactory.getLog(CustomEditManager.class);

    /**
     * Storage of EditProcessor implementations. EditProcessors are stored against edit processor
     * key given in the configuration.
     */
    private Map<String, EditProcessor> editProcessors = new HashMap<String, EditProcessor>();

    /**
     * Creates new edit manager instance for the edit processors' use.
     */
    public CustomEditManager() {
        // we should register built in edit processors here
        editProcessors.put(/*RepositoryConstants.*/ InternalConstants.TEXT_EDIT_PROCESSOR_KEY, new TextEditProcessor());
    }

    /**
     * Registers EditProcessors in the registry.
     *
     * @param processorKey  Edit processor key given in the configuration. This is used to refer
     *                      edit processors by custom UIs.
     * @param editProcessor EditProcessor implementation.
     */
    public void addProcessor(String processorKey, EditProcessor editProcessor) {
        editProcessors.put(processorKey, editProcessor);
    }

    /**
     * Handles edit and new resource requests generated from custom UIs by delegating them to
     * corresponding EditProcessors.
     *
     * @param request  HttpServletRequest containing the request details.
     * @param response HttpServletResponse to be filled with response details.
     *
     * @throws RepositoryException Throws if edit processor key is not specified in the request,
     *                           EditProcessor is not associated with the given key or view type
     *                           parameter is not specified in the request.
     */
    public void process(HttpServletRequest request, HttpServletResponse response) throws RepositoryException {
        String processorKey = request.getParameter(InternalConstants.CUSTOM_EDIT_PROCESSOR_KEY);
        
        if (processorKey == null) {
            String msg = "Processor key is not set in the request, " +
                    "which is generated by a custom edit UI.";
            log.error(msg);
            throw new RepositoryUserContentException(msg, RepositoryErrorCodes.HTTP_REQUEST_ERROR);
        }

        EditProcessor editProcessor = editProcessors.get(processorKey);
        if (editProcessor == null) {
            String msg = "Edit processor is not registered for processing the custom edit requests for key " + processorKey;
            log.error(msg);
            throw new RepositoryUserContentException(msg, RepositoryErrorCodes.HTTP_REQUEST_ERROR);
        }

        String viewType = request.getParameter(InternalConstants.VIEW_TYPE);
        if (viewType == null || "".equals(viewType)) {
            String msg = "View type is not specified in the custom UI edit/new request. " +
                    "Requests made from custom UIs should contain 'view-type' parameter with " +
                    "value set to 'edit' or 'new'";
            log.error(msg);
            throw new RepositoryUserContentException(msg, RepositoryErrorCodes.HTTP_REQUEST_ERROR);
        }

        String viewKey = request.getParameter(/*RepositoryConstants.*/ InternalConstants.VIEW_KEY);

        String path = RepositoryConstants.ROOT_PATH;
        boolean responseSent = false;
        
        if (InternalConstants.EDIT_VIEW_TYPE.equals(viewType)) {
            path = request.getParameter("resourcePath");
            responseSent = editProcessor.processEditContent(path, viewKey, request, response);
        } else if (InternalConstants.NEW_VIEW_TYPE.equals(viewType)) {
            path = request.getParameter("parentPath");
            responseSent = editProcessor.processNewContent(path, viewKey, request, response);
        }

        if (!responseSent) {
            String redirectURL = request.getParameter("redirectURL");
            if (redirectURL != null) {
                InternalUtils.redirect(response, redirectURL);
            } else {
                ResourcePath resourcePath = new ResourcePath(path);
                InternalUtils.redirect(response, "/wso2registry/web" + resourcePath.getPathWithVersion());
            }
        }
    }
}
