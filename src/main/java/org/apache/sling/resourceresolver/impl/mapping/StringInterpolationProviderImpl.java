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

import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Designate(ocd = StringInterpolationProviderConfiguration.class)
@Component
public class StringInterpolationProviderImpl
    implements StringInterpolationProvider
{
    private static final String TYPE_ENV = "env";

    private static final String TYPE_PROP = "prop";

    private static final String TYPE_CONFIG = "config";

    private static final String DIRECTIVE_DEFAULT = "default";

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private Map<String, String> placeholderEntries = new HashMap<>();
    private BundleContext context;

    // ---------- SCR Integration ---------------------------------------------

    /**
     * Activates this component (called by SCR before)
     */
    @Activate
    protected void activate(final BundleContext bundleContext, final StringInterpolationProviderConfiguration config) {
        this.context = bundleContext;

        String[] valueMap = config.placeHolderKeyValuePairs();
        Map<String, String> newMap = new HashMap<>();
        for(String line: valueMap) {
            // Ignore no lines, empty lines and comments
            if(line != null && !line.isEmpty() && line.charAt(0) != '#') {
                int index = line.indexOf('=');
                if (index <= 0) {
                    logger.warn("Placeholder Entry does not contain a key: '{}' -> ignored", line);
                } else if (index > line.length() - 2) {
                    logger.warn("Placeholder Entry does not contain a value: '{}' -> ignored", line);
                } else {
                    newMap.put(line.substring(0, index), line.substring(index + 1));
                }
            }
        }
        this.placeholderEntries = newMap;
    }

    /**
     * Modifies this component (called by SCR to update this component)
     */
    @Modified
    protected void modified(final BundleContext bundleContext, final StringInterpolationProviderConfiguration config) {
        this.activate(bundleContext, config);
    }

    /**
     * Deactivates this component (called by SCR to take out of service)
     */
    @Deactivate
    protected void deactivate(final BundleContext bundleContext) {
        this.context = null;
        this.placeholderEntries = new HashMap<>();
    }

    /**
     * This is the method that is used by the Map Entries service to substitute values with
     * the proper format
     * @param text Text to be converted
     * @return Should be either the substituted text or the original given text
     */
    @Override
    public String substitute(String text) {
        logger.trace("Substitute: '{}'", text);
        Object result = Interpolator.replace(text, (type, name, dir) -> {
            String v = null;
            if (TYPE_ENV.equals(type)) {
                v = getVariableFromEnvironment(name);
            } else if (TYPE_PROP.equals(type)) {
                v = getVariableFromProperty(name);
            } else if(TYPE_CONFIG.equals(type)){
                v = getVariableFromBundleConfiguration(name);
            }
            if (v == null) {
                v = dir.get(DIRECTIVE_DEFAULT);
            }
            logger.trace("Return substitution value: '{}'", v);
            return v;
        });
        logger.trace("Substitute result: '{}'", result);
        return result == null ? null : result.toString();
    }

    String getVariableFromEnvironment(final String name) {
        return System.getenv(name);
    }

    String getVariableFromProperty(final String name) { return context == null ? null : context.getProperty(name); }

    String getVariableFromBundleConfiguration(final String name) { return placeholderEntries.get(name); }
}
