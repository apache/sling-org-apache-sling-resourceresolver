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

import java.util.List;

/**
 * This class provides placeholders for Sling configuration settings
 * that depend on the environment like host names / ports for dev, test,
 * qa, staging, prod systems
 *
 * Placeholders are enclosed in Starting and Ending Delimiters (see PLACEHOLDER_START/END_TOKEN)
 * The name of the placeholder can contain any character except opening or closing
 * brackets (no nesting).
 */
public interface StringInterpolationProvider {

    enum STATUS {found, unknown, none};

    public static final String PLACEHOLDER_START_TOKEN = "${";
    public static final String PLACEHOLDER_END_TOKEN = "}";
    /**
     * Checks if the given values contains a placeholder and if that placeholder is known
     * @param value String to check
     * @return Indicator if the given strings contains a known, unknown or no placeholders
     */
    Check hasPlaceholder(String value);

    /**
     * Replaces any placeholders with the replacement value
     * ATTENTION: it is assumed that the string was checked and STATUS.found was returned.
     * Any known placeholders will be replaced with an empty string
     *
     * @param check Instance returned by has placeholder method
     * @return Resolve string
     */
    String resolve(Check check);

    public class Check {
        private STATUS status;
        private List<PlaceholderContext> placeholderContextList;
        private String line;

        public Check(STATUS status, String line, List<PlaceholderContext> placeholderContextList) {
            this.status = status;
            this.line = line;
            this.placeholderContextList = placeholderContextList;
        }

        public STATUS getStatus() { return status; }

        public List<PlaceholderContext> getPlaceholderContextList() {
            return placeholderContextList;
        }

        public String getLine() {
            return line;
        }
    }

    public class PlaceholderContext {
        private int start;
        private int end;
        private String name;

        public PlaceholderContext(int start, int end, String name) {
            this.start = start;
            this.end = end;
            this.name = name;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getName() {
            return name;
        }
    }
}
