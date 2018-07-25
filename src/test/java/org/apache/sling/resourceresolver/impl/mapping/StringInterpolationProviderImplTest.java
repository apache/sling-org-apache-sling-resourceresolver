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

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;
import static org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider.PLACEHOLDER_START_TOKEN;
import static org.apache.sling.resourceresolver.impl.mapping.StringInterpolationProvider.PLACEHOLDER_END_TOKEN;

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
    public void test_simple_one_placeholder() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = PLACEHOLDER_START_TOKEN + "one" + PLACEHOLDER_END_TOKEN;
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.found, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "two", resolved);
    }

    @Test
    public void test_simple_text_one_placeholder() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here is " + PLACEHOLDER_START_TOKEN + "one" + PLACEHOLDER_END_TOKEN + ", too";
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.found, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "Here is two, too", resolved);
    }

    @Test
    public void test_two_placeholders() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two", "three=four"}
        );
        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = PLACEHOLDER_START_TOKEN + "one" + PLACEHOLDER_END_TOKEN + " with another " + PLACEHOLDER_START_TOKEN + "three" + PLACEHOLDER_END_TOKEN;
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.found, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "two with another four", resolved);
    }

    @Test
    public void test_three_placeholders() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes " + PLACEHOLDER_START_TOKEN + "one" + PLACEHOLDER_END_TOKEN + " with another " + PLACEHOLDER_START_TOKEN + "three" + PLACEHOLDER_END_TOKEN + " equals " + PLACEHOLDER_START_TOKEN + "five" + PLACEHOLDER_END_TOKEN + ", horray!";
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.found, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "Here comes two with another four equals six, horray!", resolved);
    }

    @Test
    public void test_no_placeholders() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes is a text with no placeholders!";
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.none, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "Here comes is a text with no placeholders!", resolved);
    }

    @Test
    public void test_unkown_placeholders() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "one=two", "three=four", "five=six"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = "Here comes " + PLACEHOLDER_START_TOKEN + "unkown" + PLACEHOLDER_END_TOKEN + " placeholders!";
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.unknown, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "Here comes ${unkown} placeholders!", resolved);
    }

    @Test
    public void test_trailing_slash_placeholders() {
        when(stringInterpolationProviderConfiguration.place_holder_key_value_pairs()).thenReturn(
            new String[] { "siv.one=test-value"}
        );

        StringInterpolationProviderImpl placeholderProvider = new StringInterpolationProviderImpl();
        placeholderProvider.activate(bundleContext, stringInterpolationProviderConfiguration);

        String line = PLACEHOLDER_START_TOKEN + "siv.one" + PLACEHOLDER_END_TOKEN + "/";
        StringInterpolationProvider.Check check = placeholderProvider.hasPlaceholder(line);
        assertEquals("Wrong Check status", StringInterpolationProvider.STATUS.found, check.getStatus());
        String resolved = placeholderProvider.resolve(check);
        assertEquals("Wrong resolved line", "test-value/", resolved);
    }
}
