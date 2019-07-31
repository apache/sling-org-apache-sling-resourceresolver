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

import static org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProviderImpl.DEFAULT_CONFIG;
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
        when(stringInterpolationProviderConfiguration.substitutionPrefix()).thenReturn("${");
        when(stringInterpolationProviderConfiguration.substitutionSuffix()).thenReturn("}");
        when(stringInterpolationProviderConfiguration.substitutionEscapeCharacter()).thenReturn('$');
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(false);
    }

    @Test
    public void test_strsubstitutor() {
        Map<String,String> values = new HashMap<>();
        values.put("one", "two");
        StrSubstitutor substitutor = new StrSubstitutor(values, "${", "}", '$');
        String substitude = substitutor.replace("${one}");
        assertEquals("Wrong Replacement", "two", substitude);
    }

    @Test
    public void test_simple_one_placeholder() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two", substituted);
    }

    @Test
    public void test_simple_text_one_placeholder() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "Here is ${one}, too";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here is two, too", substituted);
    }

    @Test
    public void test_two_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one} with another ${three}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two with another four", substituted);
    }

    @Test
    public void test_three_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "Here comes ${one} with another ${three} equals ${five}, horray!";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here comes two with another four equals six, horray!", substituted);
    }

    @Test
    public void test_no_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

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
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "Here comes ${unkown} placeholders!";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "Here comes ${unkown} placeholders!", substituted);
    }

    @Test
    public void test_trailing_slash_placeholders() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "siv.one=test-value"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${siv.one}/";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "test-value/", substituted);
    }

    @Test
    public void test_different_suffix_prefix() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "test-me.one=hello"}
        );
        when(stringInterpolationProviderConfiguration.substitutionPrefix()).thenReturn("{{");
        when(stringInterpolationProviderConfiguration.substitutionSuffix()).thenReturn("}}");

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "a-{{test-me.one}}-a";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "a-hello-a", substituted);
    }

    @Test
    public void test_escape_character() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two"}
        );
        when(stringInterpolationProviderConfiguration.substitutionEscapeCharacter()).thenReturn('\\');

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "\\${one}=${one}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "${one}=two", substituted);
    }

    @Test
    public void test_in_variables_substitution() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=three"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${${one}}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "three", substituted);
    }

    @Test
    public void test_in_variables_substitution2() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "onetwo=three"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one${one}}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "three", substituted);
    }

    @Test
    public void test_bad_placeholder_key_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two", "=two"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two${two}", substituted);
    }

    @Test
    public void test_bad_placeholder_value_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two="}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two${two}", substituted);
    }

    @Test
    public void test_comment_in_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "# Next One", "two=four"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}-${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
    }

    @Test
    public void test_empty_line_in_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "", "two=four"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}-${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
    }

    @Test
    public void test_no_configuration() {
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();

        String line = "${one}${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", line, substituted);
    }

    @Test
    public void test_default_configuration() {
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(DEFAULT_CONFIG);

        String line = "${one}${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", line, substituted);
    }

    @Test
    public void test_modify_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two="}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=three"}
        );
        placeholderProvider.modified(stringInterpolationProviderConfiguration);

        String line = "${one}-${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-three", substituted);
    }

    @Test
    public void test_deactivate_configuration() {
        when(stringInterpolationProviderConfiguration.placeHolderKeyValuePairs()).thenReturn(
            new String[] { "one=two", "two=four"}
        );
        when(stringInterpolationProviderConfiguration.substitutionInVariables()).thenReturn(true);

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(stringInterpolationProviderConfiguration);

        String line = "${one}-${two}";
        String substituted = placeholderProvider.substitute(line);
        assertEquals("Wrong resolved line", "two-four", substituted);
        placeholderProvider.deactivate();
        substituted = placeholderProvider.substitute(line);
        assertEquals("Line should not be substituted because service was deactivated", line, substituted);
    }
}
