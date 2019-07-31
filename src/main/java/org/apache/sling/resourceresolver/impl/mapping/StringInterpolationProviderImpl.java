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

import org.apache.commons.lang3.text.StrSubstitutor;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;

@Designate(ocd = StringInterpolationProviderConfiguration.class)
@Component(name = "org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider")
public class StringInterpolationProviderImpl
    implements StringInterpolationProvider
{
    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public static final StringInterpolationProviderConfiguration DEFAULT_CONFIG = new StringInterpolationProviderConfigurationImpl();

    private Map<String, String> placeholderEntries = new HashMap<>();
    private StrSubstitutor substitutor = new StrSubstitutor();

    // ---------- SCR Integration ---------------------------------------------

    /**
     * Activates this component (called by SCR before)
     */
    @Activate
    protected void activate(final StringInterpolationProviderConfiguration config) {
        String prefix = config.substitutionPrefix();
        String suffix = config.substitutionSuffix();
        char escapeCharacter = config.substitutionEscapeCharacter();
        boolean substitudeInVariables = config.substitutionInVariables();

        String[] valueMap = config.placeHolderKeyValuePairs();
        // Clear out any existing values
        placeholderEntries.clear();
        for(String line: valueMap) {
            // Ignore no or empty lines
            if(line != null && !line.isEmpty()) {
                // Ignore comments
                if(line.charAt(0) != '#') {
                    int index = line.indexOf('=');
                    if (index <= 0) {
                        logger.warn("Placeholder Entry does not contain a key: '{}' -> ignored", line);
                    } else if (index > line.length() - 2) {
                        logger.warn("Placeholder Entry does not contain a value: '{}' -> ignored", line);
                    } else {
                        placeholderEntries.put(line.substring(0, index), line.substring(index + 1));
                    }
                }
            }
        }

        substitutor = new StrSubstitutor(
            placeholderEntries,
            prefix,
            suffix,
            escapeCharacter
        );
        substitutor.setEnableSubstitutionInVariables(substitudeInVariables);
    }

    /**
     * Modifies this component (called by SCR to update this component)
     */
    @Modified
    protected void modified(final StringInterpolationProviderConfiguration config) {
        this.activate(config);
    }

    /**
     * Deactivates this component (called by SCR to take out of service)
     */
    @Deactivate
    protected void deactivate() {
        activate(DEFAULT_CONFIG);
    }

    @Override
    public String substitute(String text) {
        return substitutor.replace(text);
    }

    private static class StringInterpolationProviderConfigurationImpl
        implements StringInterpolationProviderConfiguration
    {
        @Override
        public String substitutionPrefix() {
            return DEFAULT_PREFIX;
        }

        @Override
        public String substitutionSuffix() {
            return DEFAULT_SUFFIX;
        }

        @Override
        public char substitutionEscapeCharacter() {
            return DEFAULT_ESCAPE_CHARACTER;
        }

        @Override
        public boolean substitutionInVariables() {
            return DEFAULT_IN_VARIABLE_SUBSTITUTION;
        }

        @Override
        public String[] placeHolderKeyValuePairs() {
            return new String[0];
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return ObjectClassDefinition.class;
        }
    }
}
