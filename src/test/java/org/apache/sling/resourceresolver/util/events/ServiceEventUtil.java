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
package org.apache.sling.resourceresolver.util.events;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.osgi.framework.ServiceEvent;

public class ServiceEventUtil {

    public static Matcher<ServiceEventDTO> registration(Class<?> clazz) {
        return Matchers.equalTo(ServiceEventDTO.create(ServiceEvent.REGISTERED, clazz));
    }

    public static Matcher<ServiceEventDTO> unregistration(Class<?> clazz) {
        return Matchers.equalTo(ServiceEventDTO.create(ServiceEvent.UNREGISTERING, clazz));
    }

    public static class ServiceEventDTO {

        private static final String[] SERVICE_EVENT_TYPES = new String[9];

        static {
            SERVICE_EVENT_TYPES[ServiceEvent.REGISTERED] = "REGISTERED";
            SERVICE_EVENT_TYPES[ServiceEvent.UNREGISTERING] = "UNREGISTERING";
            SERVICE_EVENT_TYPES[ServiceEvent.MODIFIED] = "MODIFIED";
            SERVICE_EVENT_TYPES[ServiceEvent.MODIFIED_ENDMATCH] = "MODIFIED_ENDMATCH";
        }

        private final int eventType;

        private final Set<String> classNames;

        private ServiceEventDTO(int eventType, Set<String> classNames) {
            this.eventType = eventType;
            this.classNames = Collections.unmodifiableSet(classNames);
        }

        public static ServiceEventDTO create(int eventType, Class<?>... classes) {
            final Set<String> classNames =
                    Stream.of(classes).map(Class::getName).collect(Collectors.toCollection(TreeSet::new));
            return new ServiceEventDTO(eventType, classNames);
        }

        public static ServiceEventDTO create(ServiceEvent event) {
            final int eventType = event.getType();
            final Object objectClass = event.getServiceReference().getProperty("objectClass");
            final Set<String> classNames;
            if (objectClass instanceof String[]) {
                classNames = new TreeSet<>(Arrays.asList((String[]) objectClass));
            } else {
                classNames = Collections.emptySet();
            }
            return new ServiceEventDTO(eventType, classNames);
        }

        public String getEventType() {
            return SERVICE_EVENT_TYPES[eventType];
        }

        public Set<String> getClasses() {
            return classNames;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ServiceEventDTO that = (ServiceEventDTO) o;
            return eventType == that.eventType && Objects.equals(classNames, that.classNames);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventType, classNames);
        }

        @Override
        public String toString() {
            return String.format("ServiceEvent(%s, %s)", getEventType(), classNames);
        }
    }
}
