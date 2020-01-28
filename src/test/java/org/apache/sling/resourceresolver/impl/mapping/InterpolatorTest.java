/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InterpolatorTest {

    private static final String TYPE_CONFIG = "config";

    private static final String DIRECTIVE_DEFAULT = "default";

    @Test
    public void test_unclosed_placeholder() {
        String match = "$[config:test;default=one$[config:another;default=two]";
        Object answer = Interpolator.replace(
            match,
            (type, name, dir) -> {
                String v = null;
                if (TYPE_CONFIG.equals(type)){
                    v = getVariableFromBundleConfiguration(name);
                }
                if (v == null) {
                    v = dir.get(DIRECTIVE_DEFAULT);
                }
                return v;
            }
        );
        assertTrue("Answer must be a string", answer instanceof String);
        assertEquals("Nothing should have been changed", match, answer);
    }

    @Test
    public void test_no_type() {
        String match = "$[test;default=one]";
        Object answer = Interpolator.replace(
            match,
            (type, name, dir) -> {
                String v = null;
                if (TYPE_CONFIG.equals(type)){
                    v = getVariableFromBundleConfiguration(name);
                }
                if (v == null) {
                    v = dir.get(DIRECTIVE_DEFAULT);
                }
                return v;
            }
        );
        assertTrue("Answer must be a string", answer instanceof String);
        assertEquals("Nothing should have been changed", match, answer);
    }

    @Test
    public void test_not_a_string() {
        String match = "$[config:test]";
        Object answer = Interpolator.replace(
            match,
            (type, name, dir) -> {
                Object v = null;
                if (TYPE_CONFIG.equals(type)){
                    v = new Integer(1);
                }
                return v;
            }
        );
        assertTrue("Answer must be a Integer", answer instanceof Integer);
        assertEquals("Nothing should have been changed", 1, answer);
    }

    private String getVariableFromBundleConfiguration(String name) {
        return "'" + name + "'";
    }
}
