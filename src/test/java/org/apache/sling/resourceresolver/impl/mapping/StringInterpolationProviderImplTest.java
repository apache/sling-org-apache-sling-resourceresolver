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
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.BundleContext;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class StringInterpolationProviderImplTest {

    @Mock
    private BundleContext bundleContext;

    @Mock
    private StringInterpolationProviderConfiguration stringInterpolationProviderConfiguration;

    @SuppressWarnings({ "unchecked" })
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void test_interpolator_simple() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:one]", values);
        assertEquals("Wrong Replacement", "two", substitute);
    }

    @Test
    public void test_interpolator_with_type() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:one]", values);
        assertEquals("Wrong Replacement (with type)", "two", substitute);
    }

    @Test
    public void test_interpolator_no_match() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:two]", values);
        assertEquals("Should not been replaced", "$[config:two]", substitute);
    }

    @Test
    public void test_interpolator_no_match_with_default() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:two;default=three]", values);
        assertEquals("Should have been default for no match", "three", substitute);
    }

    @Test
    public void test_interpolator_full_with_match() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:one;default=three]", values);
        assertEquals("Wrong Replacement", "two", substitute);
    }

    @Test
    public void test_interpolator_full_with_default() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        String substitute = interpolate("$[config:two;default=three]", values);
        assertEquals("Should have been default for no match", "three", substitute);
    }

    private String interpolate(final String text, final Map<String,String> mappings) {
        Object result = Interpolator.replace(text, (type, name, dir) -> {
            Object answer = mappings.get(name);
            if(answer == null) {
                answer = dir.get("default");
            }
            return answer;
        });
        return result == null ? null : result.toString();
    }

    @Test
    public void test_simple_one_placeholder() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two", substituted);
    }

    @Test
    public void test_simple_text_one_placeholder() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here is $[config:one], too";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here is two, too", substituted);
    }

    @Test
    public void test_two_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one] with another $[config:three]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two with another four", substituted);
    }

    @Test
    public void test_three_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes $[config:one] with another $[config:three] equals $[config:five], horray!";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here comes two with another four equals six, horray!", substituted);
    }

    @Test
    public void test_no_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes is a text with no placeholders!";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here comes is a text with no placeholders!", substituted);
    }

    @Test
    public void test_unkown_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes $[config:unkown] placeholders!";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here comes $[config:unkown] placeholders!", substituted);
    }

    @Test
    public void test_trailing_slash_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "siv.one=test-value"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:siv.one]/";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "test-value/", substituted);
    }

    @Test
    public void test_escape_character() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "\\$[config:one]=$[config:one]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "$[config:one]=two", substituted);
    }

    @Test
    public void test_in_variables_substitution() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=three"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:$[config:one]]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "three", substituted);
    }

    @Test
    public void test_in_variables_substitution2() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "onetwo=three"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one$[config:one]]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "three", substituted);
    }

    @Test
    public void test_bad_placeholder_key_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two", "=two"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two$[config:two]", substituted);
    }

    @Test
    public void test_bad_placeholder_value_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two="}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two$[config:two]", substituted);
    }

    @Test
    public void test_comment_in_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "# Next One", "two=four"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]-$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
    }

    @Test
    public void test_empty_line_in_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "", "two=four"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]-$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
    }

    @Test
    public void test_no_configuration() {
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();

        String line = "$[config:one]$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", line, substituted);
    }

    @Test
    public void test_modify_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two="}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=three"}
        );
        placeholderProvider.modified(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]-$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-three", substituted);
    }

    @Test
    public void test_deactivate_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=four"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "$[config:one]-$[config:two]";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
        placeholderProvider.deactivate(bundleContext);
        substituted = placeholderProvider.substitute(line);
        assertEquals("Line should not be substituted because service was deactivated", line, substituted);
    }
}
