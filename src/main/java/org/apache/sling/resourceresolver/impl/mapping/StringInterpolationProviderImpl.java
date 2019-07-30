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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

@Designate(ocd = StringInterpolationProviderConfiguration.class)
@Component(name = "org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider")
public class StringInterpolationProviderImpl
    implements StringInterpolationProvider
{
    /** Logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public static StringInterpolationProviderConfiguration DEFAULT_CONFIG;

    static {
        final InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(final Object obj, final Method calledMethod, final Object[] args)
                throws Throwable {
                if ( calledMethod.getDeclaringClass().isAssignableFrom(StringInterpolationProviderConfiguration.class) ) {
                    return calledMethod.getDefaultValue();
                }
                if ( calledMethod.getDeclaringClass() == Object.class ) {
                    if ( calledMethod.getName().equals("toString") && (args == null || args.length == 0) ) {
                        return "Generated @" + StringInterpolationProviderConfiguration.class.getName() + " instance";
                    }
                    if ( calledMethod.getName().equals("hashCode") && (args == null || args.length == 0) ) {
                        return this.hashCode();
                    }
                    if ( calledMethod.getName().equals("equals") && args != null && args.length == 1 ) {
                        return Boolean.FALSE;
                    }
                }
                throw new InternalError("unexpected method dispatched: " + calledMethod);
            }
        };
        DEFAULT_CONFIG = (StringInterpolationProviderConfiguration) Proxy.newProxyInstance(
            StringInterpolationProviderConfiguration.class.getClassLoader(),
            new Class[] { StringInterpolationProviderConfiguration.class },
            handler
        );
    }

//    private StringInterpolationProviderConfiguration config = DEFAULT_CONFIG;
    private Map<String, String> placeholderEntries = new HashMap<>();
    private StrSubstitutor substitutor = new StrSubstitutor();

    // ---------- SCR Integration ---------------------------------------------

    /**
     * Activates this component (called by SCR before)
     */
    @Activate
    protected void activate(final StringInterpolationProviderConfiguration config) {
        String prefix = config.substitution_prefix();
        String suffix = config.substitution_suffix();
        char escapeCharacter = config.substitution_escape_character();
        boolean substitudeInVariables = config.substitution_in_variables();

        String[] valueMap = config.place_holder_key_value_pairs();
        for(String line: valueMap) {
            // Ignore no or empty lines
            if(line == null || line.isEmpty()) { continue; }
            // Ignore comments
            if(line.charAt(0) == '#') { continue; }
            int index = line.indexOf('=');
            if(index <= 0) {
                logger.warn("Placeholder Entry does not contain a key: '{}' -> ignored", line);
                continue;
            }
            if(index > line.length() - 2) {
                logger.warn("Placeholder Entry does not contain a value: '{}' -> ignored", line);
                continue;
            }
            placeholderEntries.put(line.substring(0, index), line.substring(index + 1));
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
}
