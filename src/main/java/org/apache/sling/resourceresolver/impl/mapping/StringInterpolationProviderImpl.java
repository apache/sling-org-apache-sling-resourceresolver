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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private BundleContext bundleContext;
    private StringInterpolationProviderConfiguration config = DEFAULT_CONFIG;
    private Map<String, String> placeholderEntries = new HashMap<>();

    // ---------- SCR Integration ---------------------------------------------

    /**
     * Activates this component (called by SCR before)
     */
    @Activate
    protected void activate(final BundleContext bundleContext, final StringInterpolationProviderConfiguration config) {
        this.bundleContext = bundleContext;
        this.config = config;
        for(String line: this.config.place_holder_key_value_pairs()) {
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
    }

    /**
     * Modifies this component (called by SCR to update this component)
     */
    @Modified
    protected void modified(final BundleContext bundleContext, final StringInterpolationProviderConfiguration config) {
        this.deactivate();
        this.activate(bundleContext, config);
    }

    /**
     * Deactivates this component (called by SCR to take out of service)
     */
    @Deactivate
    protected void deactivate() {
        this.bundleContext = null;
        this.config = DEFAULT_CONFIG;
    }

    @Override
    public Check hasPlaceholder(String line) {
        STATUS status = STATUS.none;
        List<PlaceholderContext> placeholderContextList = parseLine(line);
        for(PlaceholderContext placeholderContext: placeholderContextList) {
            String name = placeholderContext.getName();
            if(!placeholderEntries.containsKey(name)) {
                logger.warn("Placeholder: '{}' not found in list of Placeholders: '{}'", name, placeholderEntries);
                status = STATUS.unknown;
            }
            status = status == STATUS.none ? STATUS.found : status;
        }
        return new Check(status, line, placeholderContextList);
    }

    @Override
    public String resolve(Check check) {
        if(check.getStatus() == STATUS.unknown) {
            logger.warn("Line: '{}' contains unknown placeholders -> ignored", check.getLine());
            return check.getLine();
        }
        List<PlaceholderContext> placeholderContextList = check.getPlaceholderContextList();
        String line = check.getLine();
        String answer = "";
        if(placeholderContextList.isEmpty()) {
            answer = line;
        } else {
            // The carret is the position in the source line. It is used to copy regular text
            int carret = 0;
            for (PlaceholderContext context : check.getPlaceholderContextList()) {
                int start = context.getStart();
                if(start > carret) {
                    // There is text between the current position in the source and the next placeholder
                    // so copy this into the target line
                    String text = line.substring(carret, start);
                    answer += text;
                    carret += text.length();
                }
                int end = context.getEnd();
                String name = context.getName();
                String value = placeholderEntries.get(name);
                // Add placeholder value into the target line
                answer += value;
                carret = carret + end - start + PLACEHOLDER_END_TOKEN.length();
            }
            if(carret < line.length()) {
                // There is some text left after the last placeholder so copy this to the target line
                answer += line.substring(carret);
            }
        }
        return answer;
    }

    private List<PlaceholderContext> parseLine(String line) {
        List<PlaceholderContext> answer = new ArrayList<>();
        int index = -2;
        if(line != null && !line.isEmpty()) {
            while(true) {
                index = line.indexOf(PLACEHOLDER_START_TOKEN, index + 1);
                if (index < 0) {
                    break;
                }
                int index2 = line.indexOf(PLACEHOLDER_END_TOKEN, index);
                if(index2 < 0) {
                    logger.warn("Given Line: '{}' contains an unclosed placeholder -> ignored", line);
                    continue;
                }
                answer.add(
                    new PlaceholderContext(
                        index,
                        index2,
                        line.substring(index + PLACEHOLDER_START_TOKEN.length(), index2)
                    )
                );
            }
        }
        return answer;
    }
}
