/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Replace place holders in a string
 */
public class Interpolator {

    private static final Logger LOGGER = LoggerFactory.getLogger(Interpolator.class);

    public static final char END = ']';

    public static final String START = "$[";

    public static final char NAME_SEPARATOR = ':';
    public static final char DIRECTIVES_SEPARATOR = ';';
    public static final char DIRECTIVES_VALUE_SEPARATOR = '=';

    public static final char ESCAPE = '\\';

    /**
     * The value for the replacement is returned by this provider
     */
    @FunctionalInterface
    public static interface Provider {

        Object provide(String type, String name, Map<String, String> directives);
    }

    /**
     * Replace all place holders
     *
     * @param value    Value with place holders
     * @param provider Provider for providing the values
     * @return Replaced object (or original value)
     */
    public static Object replace(final String value, final Provider provider) {
        String result = value;
        int start = -1;
        while (start < result.length()) {
            start = result.indexOf(START, start);
            if (start == -1) {
                // no placeholder found -> end
                LOGGER.trace("No Start ({}) found in: '{}'", START, result);
                start = result.length();
                continue;
            }

            boolean replace = true;
            if (start > 0 && result.charAt(start - 1) == ESCAPE) {
                if (start == 1 || result.charAt(start - 2) != ESCAPE) {
                    LOGGER.trace("Escape ({}) found in: '{}'", ESCAPE, result);
                    replace = false;
                }
            }

            if (!replace) {
                // placeholder is escaped -> remove placeholder and continue
                result = result.substring(0, start - 1).concat(result.substring(start));
                start = start + START.length();
                continue;
            }

            int count = 1;
            int index = start + START.length();
            while (index < result.length() && count > 0) {
                if (result.charAt(index) == START.charAt(1) && result.charAt(index - 1) == START.charAt(0)) {
                    count++;
                } else if (result.charAt(index) == END) {
                    count--;
                }
                index++;
            }

            if (count > 0) {
                LOGGER.trace("No End ({}) found in: '{}' (count: '{}')", END, result, count);
                // no matching end found -> end
                start = result.length();
                continue;
            }

            final String key = result.substring(start + START.length(), index - 1);
            final int sep = key.indexOf(NAME_SEPARATOR);
            if (sep == -1) {
                // invalid key
                start = index;
                continue;
            }

            final String type = key.substring(0, sep);
            final String postfix = key.substring(sep + 1);
            LOGGER.trace("Type: '{}', postfix: '{}'", type, postfix);

            final int dirPos = postfix.indexOf(DIRECTIVES_SEPARATOR);
            final Map<String, String> directives;
            final String name;
            if (dirPos == -1) {
                name = postfix;
                directives = Collections.emptyMap();
                LOGGER.trace("No Directives");
            } else {
                name = postfix.substring(0, dirPos);
                directives = new HashMap<>();

                for (String dir : postfix.substring(dirPos + 1).split(DIRECTIVES_SEPARATOR + "")) {
                    String[] kv = dir.split(DIRECTIVES_VALUE_SEPARATOR + "");
                    if (kv.length == 2) {
                        directives.put(kv[0], kv[1]);
                    }
                }
                LOGGER.trace("Defaults: '{}'", directives);
            }

            // recursive replacement
            final Object newName = replace(name, provider);

            Object replacement = provider.provide(type, newName.toString(), directives);
            if (replacement == null) {
                // no replacement found -> leave as is and continue
                LOGGER.trace("No Replacements found for: '{}'", newName);
                start = index;
            } else {
                if (!(replacement instanceof String)) {
                    if (start == 0 && index == result.length()) {
                        return replacement;
                    }
                }
                // replace and continue with replacement
                result = result.substring(0, start).concat(replacement.toString()).concat(result.substring(index));
                LOGGER.trace("Replacements found for: '{}': '{}'", newName, result);
            }
        }
        return result;
    }
}