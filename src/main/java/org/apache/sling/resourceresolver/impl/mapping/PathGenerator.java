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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility to generate all possible paths from segments (names)
 * 
 * <p>This class expects to be supplied segments starting with the top-most ones (leaves)
 * up until, but excluding, the root.</p>
 * 
 * <p>It generates all possible path combinations using a cartesian product that accummulates
 * using a {@link StringBuilder} instead of a set, to prevent intermediate object creation.</p>
 *
 */
public class PathGenerator {
    
    private static List<String> cartesianJoin(List<List<String>> segments, String toAppend) {
        
        return cartesianJoin0(0, segments, toAppend).stream()
                .map( StringBuilder::toString )
                .collect( Collectors.toList() );
    }
    
    private static List<StringBuilder> cartesianJoin0(int index, List<List<String>> segments, String toAppend) {
        List<StringBuilder> out = new ArrayList<>();
        if ( index == segments.size() ) {
            out.add(new StringBuilder("/"));
        } else {
            for ( String segment : segments.get(index) ) {
                for (StringBuilder sb : cartesianJoin0(index + 1, segments, toAppend) ) {
                    sb.append(segment);
                    if ( index != 0 )
                        sb.append('/');
                    else if ( toAppend != null )
                        sb.append(toAppend);
                    out.add(sb);
                }
            }
        }
        
        return out;
    }

    private List<List<String>> segments = new ArrayList<>();
    private String toAppend;
    
    /**
     * Inserts a new segment as the parent of the existing ones
     * 
     * @param alias the list of aliases
     * @param name the name
     */
    public void insertSegment(@NotNull List<String> alias, @NotNull String name) {
        
        // TODO - can we avoid filtering here?
        List<String> filtered = alias.stream()
            .filter( e -> e != null && ! e.isEmpty() )
            .collect(Collectors.toList());
        
        segments.add(!filtered.isEmpty() ? alias : Collections.singletonList(name));
    }
    
    /**
     * Sets the resolution info, to be appended at the end
     * 
     * @param resolutionInfo the resolution info to append, ignored if null or empty
     */
    public void setResolutionPathInfo(@Nullable String resolutionInfo) {
        this.toAppend = resolutionInfo;
    }
    
    /**
     * Generates all possible paths
     * 
     * @return a list of paths containing at least one entry
     */
    public List<String> generatePaths() {
        return cartesianJoin(segments, toAppend);
    }
}
