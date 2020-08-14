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
package org.apache.sling.resourceresolver.impl.mappingchain.defaultmappers;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.mapping.spi.MappingChainContext;
import org.apache.sling.api.resource.mapping.spi.ResourceToUriMapper;
import org.apache.sling.api.resource.uri.ResourceUri;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = BasePathMapper.Config.class, factory = true)
public class BasePathMapper implements ResourceToUriMapper {
    private static final Logger LOG = LoggerFactory.getLogger(BasePathMapper.class);

    @ObjectClassDefinition(name = "Apache Sling Resource Mapper: Base Path", description = "Maps a base path in both ways")
    public @interface Config {
        @AttributeDefinition(name = "Content Base Path", description = "Content Base Path to be remove when mapping and to be added when resolving.")
        String basePath();
    }

    private String basePath;

    @Activate
    public void activate(Config config) {
        basePath = config.basePath();
        LOG.info("Automatic addition of html extension active for paths {}", basePath);
    }

    @Override
    public ResourceUri resolve(@NotNull ResourceUri resourceUri, HttpServletRequest request, MappingChainContext context) {

        String expandedPath = basePath + resourceUri.getResourcePath();
        if (context.getResourceResolver().getResource(expandedPath) != null) {
            return resourceUri.adjust(b -> b.setResourcePath(expandedPath));
        } else {
            return resourceUri;
        }
    }

    @Override
    public ResourceUri map(@NotNull ResourceUri resourceUri, HttpServletRequest request, MappingChainContext context) {

        if (StringUtils.startsWith(resourceUri.getResourcePath(), basePath)) {
            return resourceUri.adjust(b -> b.setResourcePath(StringUtils.substringAfter(resourceUri.getResourcePath(), basePath)));
        } else {
            return resourceUri;
        }

    }

}
