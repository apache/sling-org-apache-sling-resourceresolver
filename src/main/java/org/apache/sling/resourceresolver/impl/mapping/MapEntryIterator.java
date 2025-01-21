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

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

class MapEntryIterator implements Iterator<MapEntry> {

    private String key;

    private MapEntry next;

    private final Iterator<MapEntry> globalListIterator;
    private MapEntry nextGlobal;

    private Iterator<MapEntry> specialIterator;
    private MapEntry nextSpecial;

    private boolean vanityPathPrecedence;
    private final Function<String, List<MapEntry>> getCurrentMapEntryForVanityPath;

    public MapEntryIterator(final String startKey, @NotNull final List<MapEntry> globalList,
                            final Function<String, List<MapEntry>> getCurrentMapEntryForVanityPath,
                            final boolean vanityPathPrecedence) {
        this.key = startKey;
        this.globalListIterator = globalList.iterator();
        this.vanityPathPrecedence = vanityPathPrecedence;
        this.getCurrentMapEntryForVanityPath = getCurrentMapEntryForVanityPath;
        this.seek();
    }

    /**
     * @see java.util.Iterator#hasNext()
     */
    @Override
    public boolean hasNext() {
        return this.next != null;
    }

    /**
     * @see java.util.Iterator#next()
     */
    @Override
    public MapEntry next() {
        if (this.next == null) {
            throw new NoSuchElementException();
        }
        final MapEntry result = this.next;
        this.seek();
        return result;
    }

    /**
     * @see java.util.Iterator#remove()
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    private void seek() {
        if (this.nextGlobal == null && this.globalListIterator.hasNext()) {
            this.nextGlobal = this.globalListIterator.next();
        }
        if (this.nextSpecial == null) {
            if (specialIterator != null && !specialIterator.hasNext()) {
                specialIterator = null;
            }
            while (specialIterator == null && key != null) {
                // remove selectors and extension
                final int lastSlashPos = key.lastIndexOf('/');
                final int lastDotPos = key.indexOf('.', lastSlashPos);
                if (lastDotPos != -1) {
                    key = key.substring(0, lastDotPos);
                }

                final List<MapEntry> special = this.getCurrentMapEntryForVanityPath.apply(this.key);
                if (special != null) {
                    specialIterator = special.iterator();
                }

                // recurse to the parent
                if (key.length() > 1) {
                    final int lastSlash = key.lastIndexOf("/");
                    if (lastSlash == 0) {
                        key = null;
                    } else {
                        key = key.substring(0, lastSlash);
                    }
                } else {
                    key = null;
                }
            }
            if (this.specialIterator != null && this.specialIterator.hasNext()) {
                this.nextSpecial = this.specialIterator.next();
            }
        }
        if (this.nextSpecial == null) {
            this.next = this.nextGlobal;
            this.nextGlobal = null;
        } else if (!this.vanityPathPrecedence){
            if (this.nextGlobal == null) {
                this.next = this.nextSpecial;
                this.nextSpecial = null;
            } else if (this.nextGlobal.getPattern().length() >= this.nextSpecial.getPattern().length()) {
                this.next = this.nextGlobal;
                this.nextGlobal = null;

            }else {
                this.next = this.nextSpecial;
                this.nextSpecial = null;
            }
        } else {
            this.next = this.nextSpecial;
            this.nextSpecial = null;
        }
    }
}
