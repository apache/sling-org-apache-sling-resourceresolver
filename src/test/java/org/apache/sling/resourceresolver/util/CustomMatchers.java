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
package org.apache.sling.resourceresolver.util;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.StringDescription;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Custom version of Hamcrest matchers that fix the generics to make them usable.
 */
public class CustomMatchers {


    @SafeVarargs
    @NotNull
    public static <T> Matcher<T> allOf(Matcher<? extends T> first, Matcher<? extends T>... more) {
        final List<Matcher<? extends T>> matchers = Stream.concat(Stream.of(first), Stream.of(more)).collect(Collectors.toList());
        return new AllOfMatcher<>(matchers);
    }

    public static <T> Matcher<Iterable<? extends T>> hasItem(T item) {
        return hasItem(Matchers.equalTo(item));
    }

    public static <T> Matcher<Iterable<? extends T>> hasItem(Matcher<? extends T> elementMatcher) {
        return new HasItemMatcher<>(elementMatcher);
    }


    private static <T> Stream<? extends T> toStream(Iterable<? extends T> iterable) {
        return StreamSupport.stream(iterable.spliterator(), false);
    };

    private static class AllOfMatcher<T> extends TypeSafeDiagnosingMatcher<T> {
        private final Collection<Matcher<? extends T>> matchers;

        AllOfMatcher(Collection<Matcher<? extends T>> matchers) {
            this.matchers = matchers;
        }

        @Override
        protected boolean matchesSafely(T item, Description mismatchDescription) {
            return matchers.stream()
                    .filter(matcher -> !matcher.matches(item))
                    .peek(matcher -> {
                        mismatchDescription.appendDescriptionOf(matcher).appendText(" ");
                        matcher.describeMismatch(item, mismatchDescription);
                    })
                    .findFirst()
                    .isEmpty();
        }

        @Override
        public void describeTo(Description description) {
            description.appendList("(", " " + "and" + " ", ")", matchers);
        }
    }

    private static class HasItemMatcher<T> extends TypeSafeDiagnosingMatcher<Iterable<? extends T>> {

        private final Matcher<? extends T> elementMatcher;

        public HasItemMatcher(Matcher<? extends T> elementMatcher) {
            this.elementMatcher = elementMatcher;
        }

        @Override
        protected boolean matchesSafely(Iterable<? extends T> iterable, Description mismatchDescription) {
            if (toStream(iterable).limit(1).count() == 0) {
                mismatchDescription.appendText("was empty");
                return false;
            }

            if (toStream(iterable).anyMatch(elementMatcher::matches)) {
                return true;
            }

            mismatchDescription.appendText(
                    toStream(iterable)
                            .map(item -> describeItemMismatch(elementMatcher, item))
                            .collect(Collectors.joining(", ", "mismatches were: [", "]")));
            return false;
        }

        @NotNull
        private String describeItemMismatch(Matcher<? extends T> elementMatcher1, T item) {
            final StringDescription desc = new StringDescription();
            elementMatcher1.describeMismatch(item, desc);
            return desc.toString();
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("an iterable containing ").appendDescriptionOf(elementMatcher);
        }
    }
}
