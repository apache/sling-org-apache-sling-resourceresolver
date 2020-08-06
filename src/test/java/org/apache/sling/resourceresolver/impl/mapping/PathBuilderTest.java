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

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.Test;

public class PathBuilderTest {

    @Test
    public void buildRootPath() {
        assertThat(new PathBuilder().toPath(), Matchers.equalTo("/"));
    }

    @Test
    public void buildSubPathWithMissingAliases() {
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(null, "bar");
        builder.insertSegment("", "foo");
        assertThat(builder.toPath(), Matchers.equalTo("/foo/bar"));
    }

    @Test
    public void buildSubPathWithMixedAliases() {
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(null, "bar");
        builder.insertSegment("super", "foo");
        assertThat(builder.toPath(), Matchers.equalTo("/super/bar"));
    }
    
    @Test
    public void buildSubPathWithResolutionInfo() {
        PathBuilder builder = new PathBuilder();
        builder.insertSegment(null, "bar");
        builder.insertSegment(null, "foo");
        builder.setResolutionPathInfo("/baz");
        assertThat(builder.toPath(), Matchers.equalTo("/foo/bar/baz"));
    }
    
    @Test
    public void cartesianJoin_simple() {
        List<String> paths = PathBuilder.cartesianJoin(Arrays.asList( Arrays.asList("1"), Arrays.asList("2") ));
        assertThat(paths, Matchers.hasSize(1));
        assertThat(paths, Matchers.hasItem("/1/2"));
    }

    @Test
    public void cartesianJoin_multiple() {
        List<String> paths = PathBuilder.cartesianJoin(Arrays.asList( Arrays.asList("1a", "1b"), Arrays.asList("2") ));
        assertThat(paths, Matchers.hasSize(2));
        assertThat(paths, Matchers.hasItems("/1a/2", "/1b/2"));
    }
    
    @Test
    public void cartesianJoin_complex() {
        List<String> paths = PathBuilder.cartesianJoin(Arrays.asList( Arrays.asList("1a", "1b"), Arrays.asList("2a", "2b"), Arrays.asList("3"), Arrays.asList("4a", "4b", "4c") ));
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
