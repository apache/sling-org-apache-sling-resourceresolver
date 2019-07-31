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

    /**
     * Replaces any placeholders with the replacement value
     *
     * @param text Text to be substituted
     * @return Substituted string
     */
    String substitute(String text);
}
