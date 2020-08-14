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
package org.apache.sling.resourceresolver.impl.urimapping.defaultmappers;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.uri.SlingUri;
import org.apache.sling.spi.urimapping.MappingChainContext;
import org.apache.sling.spi.urimapping.SlingUriMapper;
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
@Designate(ocd = EnsureHtmlExtensionMapper.Config.class)
public class EnsureHtmlExtensionMapper implements SlingUriMapper {
    private static final Logger LOG = LoggerFactory.getLogger(EnsureHtmlExtensionMapper.class);

    @ObjectClassDefinition(name = "Apache Sling Resource Mapper: Ensure HTML Extension", description = "Ensures html extension in links without extension")
    public @interface Config {

        @AttributeDefinition(name = "Include Regex", description = "Regex to restrict paths where the extension should automatically be added")
        String includeRegex();
    }

    private String includeRegex;

    @Activate
    public void activate(Config config) {
        includeRegex = config.includeRegex();
        LOG.info("Automatic addition of html extension active for paths {}", StringUtils.defaultIfBlank(includeRegex, "<all>"));
    }

    private boolean matchesRegex(String path) {
        return StringUtils.isBlank(includeRegex) || path.matches(includeRegex);
    }

    @Override
    public SlingUri resolve(@NotNull SlingUri slingUri, HttpServletRequest request, MappingChainContext context) {
        return slingUri;
    }

    @Override
    public SlingUri map(@NotNull SlingUri slingUri, HttpServletRequest request, MappingChainContext context) {
        if (StringUtils.isNotBlank(slingUri.getResourcePath())
                && !StringUtils.contains(slingUri.getResourcePath(), ".")
                && StringUtils.isBlank(slingUri.getExtension())
                && matchesRegex(slingUri.getResourcePath())) {
            LOG.debug("Adding extension to URL {} with path={} and ext={}", slingUri, slingUri.getResourcePath(),
                    slingUri.getExtension());
            return slingUri.adjust(b -> b.setExtension("html"));
        } else {
            return slingUri;
        }
    }

}
