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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;

public class MapTracker {

    private static final MapTracker INSTANCE = new MapTracker();

    public static MapTracker get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<Key, AtomicInteger> calls = new ConcurrentHashMap<>();

    public void trackMapCall(String resourcePath, HttpServletRequest request) {
        calls.computeIfAbsent(new Key(resourcePath, request), path -> new AtomicInteger(0)).incrementAndGet();
    }

    public void dump(PrintWriter pw) {
        pw.println("--- SUMMARY OF RECORDED MAP CALLS ---");

        calls.entrySet()
            .stream()
            .sorted( (first, second) -> Integer.compare(second.getValue().get(), first.getValue().get()) )
            .forEachOrdered( entry -> pw.printf("%10d\t%s\t%s%n", entry.getValue().get(), entry.getKey().getResourcePath(), entry.getKey().getRequestPath()));
        pw.println("--- END ---");
    }

    public void clear() {
        calls.clear();
    }

    static class Key {
        private static final List<String> IGNORED_CLASS_NAMES = Arrays.asList(
                ResourceResolverImpl.class.getName(),
                ResourceMapperImpl.class.getName(),
                MapTracker.class.getName(),
                MapTracker.Key.class.getName());

        private static final List<String> IGNORED_PACKAGE_PREFIXES = Arrays.asList(
                "sun.reflect.",
                "java.lang.reflect."
        );

        private final String resourcePath;
        private final String requestor;

        public Key(String resourcePath, HttpServletRequest request) {
            this.resourcePath = resourcePath;
            this.requestor = request != null ? "REQUEST:" + request.getRequestURI() : "SERVICE:"+inferService();
        }

        private String inferService() {
            Throwable origin = new RuntimeException().fillInStackTrace();
            List<String> serviceChain = new ArrayList<>();
            for ( StackTraceElement elem : origin.getStackTrace() ) {
                if ( IGNORED_CLASS_NAMES.contains(elem.getClassName() ))
                    continue;

                boolean isIgnoredPackage = IGNORED_PACKAGE_PREFIXES.stream()
                        .anyMatch( prefix -> elem.getClassName().startsWith(prefix) );

                if ( isIgnoredPackage )
                    continue;
                serviceChain.add(elem.getClassName() + "." + elem.getMethodName());

                if ( serviceChain.size() == 3) {
                    return StringUtils.join(serviceChain, "|");
                }
            }

            if ( !serviceChain.isEmpty() )
                return StringUtils.join(serviceChain, "|");

            return "UNKNOWN";

        }


        @Override
        public int hashCode() {
            return Objects.hash(requestor, resourcePath);
        }

        public String getRequestPath() {
            return requestor;
        }

        public String getResourcePath() {
            return resourcePath;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Key other = (Key) obj;
            return Objects.equals(requestor, other.requestor) && Objects.equals(resourcePath, other.resourcePath);
        }


    }
}
