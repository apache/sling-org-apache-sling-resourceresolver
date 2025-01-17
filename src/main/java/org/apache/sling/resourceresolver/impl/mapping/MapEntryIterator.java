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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

public class MapEntryIterator implements Iterator<MapEntry> {

    /** Key for the global list. */
    private static final String GLOBAL_LIST_KEY = "*";

    private final Map<String, List<MapEntry>> resolveMapsMap;

    private String key;

    private MapEntry next;

    private final Iterator<MapEntry> globalListIterator;
    private MapEntry nextGlobal;

    private Iterator<MapEntry> specialIterator;
    private final Function<String, List<MapEntry>> getCurrentMapEntryForVanityPath;
    private MapEntry nextSpecial;

    private boolean vanityPathPrecedence;

    public MapEntryIterator(final String startKey, final Map<String, List<MapEntry>> resolveMapsMap,
                            final Function<String, List<MapEntry>> getCurrentMapEntryForVanityPath,
                            final boolean vanityPathPrecedence) {
        this.key = startKey;
        this.resolveMapsMap = resolveMapsMap;
        this.globalListIterator = this.resolveMapsMap.get(GLOBAL_LIST_KEY).iterator();
        this.vanityPathPrecedence = vanityPathPrecedence;
        this.getCurrentMapEntryForVanityPath = getCurrentMapEntryForVanityPath;
        this.seek();
    }

    @Override
    public boolean hasNext() {
        return this.next != null;
    }

    @Override
    public MapEntry next() {
        if (this.next == null) {
            throw new NoSuchElementException();
        }
        final MapEntry result = this.next;
        this.seek();
        return result;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    private void seek() {
        if (this.nextGlobal == null && this.globalListIterator.hasNext()) {
            this.nextGlobal = this.globalListIterator.next();
        } else if (this.nextSpecial == null) {
            if (specialIterator != null && !specialIterator.hasNext()) {
                specialIterator = null;
            }
            while (specialIterator == null && key != null) {

                key = removeSelectorsAndExtensionFromKey(key);

                final List<MapEntry> special = this.getCurrentMapEntryForVanityPath.apply(key);

                if (special != null) {
                    specialIterator = special.iterator();
                }

                key = recurseToParent(key);
            }

            if (this.specialIterator != null && this.specialIterator.hasNext()) {
                this.nextSpecial = this.specialIterator.next();
            }
        }

        if (this.nextSpecial == null) {
            this.next = this.nextGlobal;
            this.nextGlobal = null;
        } else if (!this.vanityPathPrecedence && this.nextGlobal != null && this.nextGlobal.getPattern().length() >= this.nextSpecial.getPattern().length()) {
            this.next = this.nextGlobal;
            this.nextGlobal = null;
        } else {
            this.next = this.nextSpecial;
            this.nextSpecial = null;
        }
    }

    private String recurseToParent(String value) {
        if (value.length() > 1) {
            final int lastSlash = value.lastIndexOf("/");
            if (lastSlash == 0) {
                value = null;
            } else {
                value = value.substring(0, lastSlash);
            }
        } else {
            value = null;
        }
        return value;
    }

    private String removeSelectorsAndExtensionFromKey(String value) {
        final int lastSlashPos = value.lastIndexOf('/');
        final int lastDotPos = value.indexOf('.', lastSlashPos);
        if (lastDotPos != -1) {
            value = value.substring(0, lastDotPos);
        }
        return value;
    }
}

