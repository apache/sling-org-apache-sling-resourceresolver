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
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility to construct paths from segments, starting with the leaf-most ones
 *
 */
public class PathBuilder {
    
    // visible for testing
    static List<String> cartesianJoin(List<List<String>> segments) {
        
        return cartesianJoin0(0, segments).stream()
                .map( StringBuilder::toString )
                .collect( Collectors.toList() );
    }
    
    private static List<StringBuilder> cartesianJoin0(int index, List<List<String>> segments) {
        List<StringBuilder> out = new ArrayList<>();
        if ( index == segments.size() ) {
            out.add(new StringBuilder());
        } else {
            for ( String segment : segments.get(index) ) {
                for (StringBuilder sb : cartesianJoin0(index + 1, segments) ) {
                    // TODO - this is sub-optimal, as we are copying the array for each move
                    sb.insert(0, '/' + segment);
                    out.add(sb);
                }
            }
        }
        
        return out;
    }

    private List<String> segments = new ArrayList<>();
    private String toAppend;
    
    /**
     * Inserts a new segment as the parent of the existing ones
     * 
     * @param alias the alias, ignored if null or empty
     * @param name the name
     */
    public void insertSegment(@Nullable String alias, @NotNull String name) {
        segments.add(alias != null && alias.length() != 0 ? alias : name);
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
    public String toPath() {
        StringBuilder sb = new StringBuilder();
        sb.append('/');
        for ( int i = segments.size() - 1 ; i >= 0 ; i-- ) {
            sb.append(segments.get(i));
            if ( i == 1 )
                sb.append('/');
        }
        
        if ( toAppend != null )
            sb.append(toAppend);
        
        return sb.toString();
    }
}
