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
package org.apache.sling.resourceresolver.util;

import java.util.Collections;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.resource.mapping.PathToUriMappingService;
import org.apache.sling.api.uri.SlingUri;
import org.apache.sling.api.uri.SlingUriBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class TestPathToUriMappingService implements PathToUriMappingService {

    static class MappingResult implements PathToUriMappingService.Result {
        private final @NotNull SlingUri slingUri;

        MappingResult(@NotNull SlingUri slingUri) {
            this.slingUri = slingUri;
        }

        @Override
        public @NotNull SlingUri getUri() {
            return slingUri;
        }

        @Override
        public @NotNull Map<String, SlingUri> getIntermediateMappings() {
            return Collections.emptyMap();
        }
    }

    @Override
    public Result resolve(@Nullable HttpServletRequest request, @NotNull String path) {
        return new MappingResult(SlingUriBuilder.parse(path, null).build());
    }

    @Override
    public Result map(@Nullable HttpServletRequest request, @NotNull String path) {
        return new MappingResult(SlingUriBuilder.parse(path, null).build());
    }
}