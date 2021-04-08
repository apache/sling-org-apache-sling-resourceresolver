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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MapTracker {

    public static void main(String[] args) {
    }

    private static final MapTracker INSTANCE = new MapTracker();

    public static MapTracker get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    public void trackMapCall(String resourcePath) {
        calls.computeIfAbsent(resourcePath, path -> new AtomicInteger(0)).incrementAndGet();
    }

    public void dump(PrintWriter pw) {
        pw.println("--- SUMMARY OF RECORDED MAP CALLS ---");

        calls.entrySet()
            .stream()
            .sorted( (first, second) -> Integer.compare(second.getValue().get(), first.getValue().get()) )
            .forEachOrdered( entry -> pw.printf("%10d\t%s%n", entry.getValue().get(), entry.getKey()));
        pw.println("--- END ---");
    }

    public void clear() {
        calls.clear();
    }
}
