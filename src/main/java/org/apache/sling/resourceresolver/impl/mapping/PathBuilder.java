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
 * Utility to construct paths from segments, starting with the leaf-most ones
 *
 */
public class PathBuilder {
    
    private static List<String> cartesianJoin(List<List<String>> segments, String toAppend) {
        
        return cartesianJoin0(0, segments).stream()
                .map ( sb -> {
                    if ( toAppend != null )
                        sb.append(toAppend);
                    
                    return sb;
                })
                .map( StringBuilder::toString )
                .collect( Collectors.toList() );
    }
    
    private static List<StringBuilder> cartesianJoin0(int index, List<List<String>> segments) {
        List<StringBuilder> out = new ArrayList<>();
        if ( index == segments.size() ) {
            out.add(new StringBuilder("/"));
        } else {
            for ( String segment : segments.get(index) ) {
                for (StringBuilder sb : cartesianJoin0(index + 1, segments) ) {
                    sb.append(segment);
                    if ( index != 0 )
                        sb.append('/');
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
     * @param alias the alias, ignored if null or empty
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
     * Sets a new value to append, typically the resolution info
     * 
     * @param toAppend the parameters to append, ignored if null or empty
     */
    public void setResolutionPathInfo(@Nullable String toAppend) {
        this.toAppend = toAppend;
    }
    
    /**
     * Constructs a new path from the provided information
     * 
     * @return a path in string form
     */
    public List<String> toPaths() {
        return cartesianJoin(segments, toAppend);
    }
}
