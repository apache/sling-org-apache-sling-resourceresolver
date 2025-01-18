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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

public class MapEntryIterator implements Iterator<MapEntry> {

    private String key;
    private MapEntry next;

    private MapEntry nextGlobal;
    private MapEntry nextSpecial;

    private final @NotNull Iterator<MapEntry> globalListIterator;
    private @NotNull Iterator<MapEntry> specialIterator = Collections.emptyIterator();

    private final Function<String, Iterator<MapEntry>> getCurrentMapEntryIteratorForVanityPath;

    private final boolean vanityPathPrecedence;

    public MapEntryIterator(final String startKey, @NotNull List<MapEntry> globalList,
                            final Function<String, Iterator<MapEntry>> getCurrentMapEntryIteratorForVanityPath,
                            final boolean vanityPathPrecedence) {
        this.key = startKey;
        this.globalListIterator = globalList.iterator();
        this.vanityPathPrecedence = vanityPathPrecedence;
        this.getCurrentMapEntryIteratorForVanityPath = getCurrentMapEntryIteratorForVanityPath;
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

        // compute candidate for next entry from global list

        if (this.nextGlobal == null && this.globalListIterator.hasNext()) {
            this.nextGlobal = this.globalListIterator.next();
        }

        // compute candidate for next entry from "special" vanity path list

        if (this.nextSpecial == null) {
            // reset specialIterator when exhausted
            if (!this.specialIterator.hasNext()) {
                this.specialIterator = Collections.emptyIterator();
            }

            // given the vanity path in key, walk up the hierarchy until we find
            // map entries for that path (or stop when root is reached)
            while (!this.specialIterator.hasNext() && this.key != null) {
                this.key = removeSelectorsAndExtension(this.key);
                this.specialIterator = this.getCurrentMapEntryIteratorForVanityPath.apply(this.key);
                this.key = getParent(key);
            }

            if (this.specialIterator.hasNext()) {
                this.nextSpecial = this.specialIterator.next();
            }
        }

        // choose based on presence, vanity path preferences and pattern lengths

        if (useNextGlobal()) {
            this.next = this.nextGlobal;
            this.nextGlobal = null;
        } else {
            this.next = this.nextSpecial;
            this.nextSpecial = null;
        }
    }

    private boolean useNextGlobal() {
        if (this.nextSpecial == null) {
            // no next special
            return true;
        } else if (this.nextGlobal == null) {
            // no next global
            return false;
        } else if (this.vanityPathPrecedence) {
            // vanity paths have precedence
            return false;
        } else {
            // decide based on pattern length
            return this.nextGlobal.getPattern().length() >= this.nextSpecial.getPattern().length();
        }
    }

    // return parent path or null when already at root
    private static @Nullable String getParent(@NotNull String path) {
        if (path.length() > 1) {
            final int lastSlash = path.lastIndexOf('/');
            if (lastSlash == 0) {
                path = null;
            } else {
                path = path.substring(0, lastSlash);
            }
        } else {
            path = null;
        }
        return path;
    }

    // remove selectors and extensions
    private static @NotNull String removeSelectorsAndExtension(@NotNull String value) {
        final int lastSlashPos = value.lastIndexOf('/');
        final int lastDotPos = value.indexOf('.', lastSlashPos);
        if (lastDotPos != -1) {
            value = value.substring(0, lastDotPos);
        }
        return value;
    }
}

