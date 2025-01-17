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

public class MapEntryIteratorTest {

    private MapEntryIterator empty = new MapEntryIterator(null, List.of(), key -> Collections.emptyIterator(), true);

    private MapEntry xyz =
            new MapEntry("/xyz", -1, false, -1, "/foo", "/bar");

    private MapEntry xyz_abc =
            new MapEntry("/xyz/def/abc", -1, false, -1, "/qux");

    private MapEntry global =
            new MapEntry("/foo/global", -1, false, -1, "bla");

    private Map<String, List<MapEntry>> xyz_map = Map.of("/xyz", List.of(xyz));

    private Map<String, List<MapEntry>> xyz_abc_map = Map.of("/xyz", List.of(xyz), "/xyz/def/abc", List.of(xyz_abc));

    private MapEntryIterator vpOnlyIterator =
            new MapEntryIterator("/xyz",
                    List.of(),
                    key -> List.of(xyz).iterator(),
                    true);

    private MapEntryIterator vpHierarchyOnlyIterator =
            new MapEntryIterator("/xyz/def/abc",
                    List.of(),
                    key -> xyz_abc_map.get(key) == null ? Collections.emptyIterator() : xyz_abc_map.get(key).iterator(),
                    true);

    private MapEntryIterator noVpIterator =
            new MapEntryIterator("/xyz",
                    List.of(xyz),
                    key -> Collections.emptyIterator(),
                    true);

    private MapEntryIterator bothIteratorVpFirst = new MapEntryIterator("/xyz",
            List.of(global),
            key ->  xyz_map.get(key) == null ? Collections.emptyIterator() : xyz_map.get(key).iterator(),
            true
            );

    private MapEntryIterator bothIteratorVpLast = new MapEntryIterator("/xyz",
            List.of(global),
            key ->  xyz_map.get(key) == null ? Collections.emptyIterator() : xyz_map.get(key).iterator(),
            false
    );

    @Test(expected = NoSuchElementException.class)
    public void testExhausted() {
        assertFalse(empty.hasNext());
        empty.next();
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testRemove() {
        assertFalse(empty.hasNext());
        empty.remove();
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
        MapEntry first = bothIteratorVpFirst.next();
        MapEntry second = bothIteratorVpFirst.next();
        assertFalse(bothIteratorVpFirst.hasNext());
        assertEquals("^/xyz", first.getPattern());
        assertEquals(2, first.getRedirect().length);
        assertEquals("/foo", first.getRedirect()[0]);
        assertEquals("/bar", first.getRedirect()[1]);
        assertEquals("^/foo/global", second.getPattern());
        assertEquals(1, second.getRedirect().length);
        assertEquals("bla", second.getRedirect()[0]);
    }

    @Test
    public void testBothIteratorVpLast() {
        MapEntry second = bothIteratorVpLast.next();
        MapEntry first = bothIteratorVpLast.next();
        assertFalse(bothIteratorVpLast.hasNext());
        assertEquals("^/xyz", first.getPattern());
        assertEquals(2, first.getRedirect().length);
        assertEquals("/foo", first.getRedirect()[0]);
        assertEquals("/bar", first.getRedirect()[1]);
        assertEquals("^/foo/global", second.getPattern());
        assertEquals(1, second.getRedirect().length);
        assertEquals("bla", second.getRedirect()[0]);
    }
}
