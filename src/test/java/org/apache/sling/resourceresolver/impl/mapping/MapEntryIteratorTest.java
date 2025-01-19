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

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class MapEntryIteratorTest {

    private MapEntries.MapEntryIterator empty = new MapEntries.MapEntryIterator(null, List.of(), key -> Collections.emptyList(), true);

    private MapEntry xyz =
            new MapEntry("/xyz", -1, false, -1, "/foo", "/bar");

    private MapEntry xyzAbc =
            new MapEntry("/xyz/def/abc", -1, false, -1, "/qux");

    private MapEntry global =
            new MapEntry("/foo/global/long", -1, false, -1, "bla");

    private Map<String, List<MapEntry>> xyzMap = Map.of("/xyz", List.of(xyz));

    private Map<String, List<MapEntry>> xyzAbcMap = Map.of("/xyz", List.of(xyz), "/xyz/def/abc", List.of(xyzAbc));

    private MapEntries.MapEntryIterator vpOnlyIterator =
            new MapEntries.MapEntryIterator("/xyz",
                    List.of(),
                    key -> List.of(xyz),
                    true);

    private MapEntries.MapEntryIterator vpHierarchyOnlyIterator =
            new MapEntries.MapEntryIterator("/xyz/def/abc",
                    List.of(),
                    key -> xyzAbcMap.get(key),
                    true);

    private MapEntries.MapEntryIterator noVpIterator =
            new MapEntries.MapEntryIterator("/xyz",
                    List.of(xyz),
                    key -> Collections.emptyList(),
                    true);


    @Test
    public void testExhausted() {
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class,
                () -> empty.next());
    }

    @Test
    public void testRemove() {
        assertFalse(empty.hasNext());
        assertThrows(UnsupportedOperationException.class,
                () -> empty.remove());
    }

    @Test
    public void testOnlyOneEntry() {
        MapEntry first = noVpIterator.next();
        assertFalse(noVpIterator.hasNext());
        assertEquals("^/xyz", first.getPattern());
        assertEquals(2, first.getRedirect().length);
        assertEquals("/foo", first.getRedirect()[0]);
        assertEquals("/bar", first.getRedirect()[1]);
    }

    @Test
    public void testOnlyOneVanityPath() {
        MapEntry first = vpOnlyIterator.next();
        assertFalse(vpOnlyIterator.hasNext());
        assertEquals("^/xyz", first.getPattern());
        assertEquals(2, first.getRedirect().length);
        assertEquals("/foo", first.getRedirect()[0]);
        assertEquals("/bar", first.getRedirect()[1]);
    }

    @Test
    public void testHierarchyVanityPath() {
        MapEntry first = vpHierarchyOnlyIterator.next();
        MapEntry second = vpHierarchyOnlyIterator.next();
        assertFalse(vpHierarchyOnlyIterator.hasNext());
        assertEquals("^/xyz/def/abc", first.getPattern());
        assertEquals(1, first.getRedirect().length);
        assertEquals("/qux", first.getRedirect()[0]);
        assertEquals("^/xyz", second.getPattern());
        assertEquals(2, second.getRedirect().length);
        assertEquals("/foo", second.getRedirect()[0]);
        assertEquals("/bar", second.getRedirect()[1]);
    }

    @Test
    public void testBothIteratorVpFirst() {
        MapEntries.MapEntryIterator bothIteratorVpFirst = new MapEntries.MapEntryIterator("/xyz",
                List.of(global),
                key -> xyzMap.get(key),
                true
        );

        MapEntry first = bothIteratorVpFirst.next();
        MapEntry second = bothIteratorVpFirst.next();
        assertFalse(bothIteratorVpFirst.hasNext());
        assertEquals("^/xyz", first.getPattern());
        assertEquals(2, first.getRedirect().length);
        assertEquals("/foo", first.getRedirect()[0]);
        assertEquals("/bar", first.getRedirect()[1]);
        assertEquals("^/foo/global/long", second.getPattern());
        assertEquals(1, second.getRedirect().length);
        assertEquals("bla", second.getRedirect()[0]);
    }

    @Test
    public void testBothIteratorVpDefault() {
        MapEntries.MapEntryIterator bothIteratorVpDefault = new MapEntries.MapEntryIterator("/xyz",
                List.of(global),
                key -> xyzMap.get(key),
                false
        );

        MapEntry first = bothIteratorVpDefault.next();
        MapEntry second = bothIteratorVpDefault.next();
        assertFalse(bothIteratorVpDefault.hasNext());

        assertEquals("^/foo/global/long", first.getPattern());
        assertEquals(1, first.getRedirect().length);
        assertEquals("bla", first.getRedirect()[0]);
        assertEquals("^/xyz", second.getPattern());
        assertEquals(2, second.getRedirect().length);
        assertEquals("/foo", second.getRedirect()[0]);
        assertEquals("/bar", second.getRedirect()[1]);
    }
}
