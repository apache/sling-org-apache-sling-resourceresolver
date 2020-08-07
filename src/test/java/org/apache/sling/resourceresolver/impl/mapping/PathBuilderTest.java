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

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.*;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.Test;

public class PathBuilderTest {

    @Test
    public void buildRootPath() {
        
        List<String> paths = new PathBuilder().toPaths();
        
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, Matchers.hasItem("/"));
    }

    @Test
    public void buildSubPathWithMissingAliases() {
        
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(singletonList(null), "bar");
        builder.insertSegment(singletonList(""), "foo");
        List<String> paths = builder.toPaths();
        
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, Matchers.hasItem("/foo/bar"));
    }

    @Test
    public void buildSubPathWithMixedAliases() {
        
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(emptyList(), "bar");
        builder.insertSegment(singletonList("super"), "foo");
        List<String> paths = builder.toPaths();
        
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, Matchers.hasItem("/super/bar"));
    }
    
    @Test
    public void buildSubPathWithResolutionInfo() {
        
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(emptyList(), "bar");
        builder.insertSegment(emptyList(), "foo");
        builder.setResolutionPathInfo("/baz");
        
        List<String> paths = builder.toPaths();
        
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, Matchers.hasItem("/foo/bar/baz"));        
    }
    
    @Test
    public void buildSubPathWithMultipleAliases() {
        
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(emptyList(), "bar");
        builder.insertSegment(asList("alias1", "alias2"), "foo");
        
        List<String> paths = builder.toPaths();
        
        assertThat(paths, Matchers.hasSize(2));
        assertThat(paths, Matchers.hasItems("/alias1/bar", "/alias2/bar"));
    }

    @Test
    public void buildSubPathWithComplexAliasesSetup() {
        
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(asList("4a", "4b", "4c"), "4");
        builder.insertSegment(emptyList(), "3");
        builder.insertSegment(asList("2a", "2b"), "2");
        builder.insertSegment(asList("1a", "1b"), "1");
        
        List<String> paths = builder.toPaths();
        
        assertThat(paths, Matchers.hasSize(12));
        assertThat(paths, Matchers.hasItems(
                "/1a/2a/3/4a",
                "/1a/2a/3/4b",
                "/1a/2a/3/4c",
                "/1a/2b/3/4a",
                "/1a/2b/3/4b",
                "/1a/2b/3/4c",
                "/1b/2a/3/4a",
                "/1b/2a/3/4b",
                "/1b/2a/3/4c",
                "/1b/2b/3/4a",
                "/1b/2b/3/4b",
                "/1b/2b/3/4c"
        ));
    }
}
