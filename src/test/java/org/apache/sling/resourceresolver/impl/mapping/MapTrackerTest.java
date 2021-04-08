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

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

public class MapTrackerTest {

    @Test
    public void smokeTest() {
        MapTracker mt = MapTracker.get();
        mt.clear();

        for (int i = 0; i < 10; i++)
            mt.trackMapCall("/content.html", null);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/content/source.html");

        for (int i = 0; i < 7; i++)
            mt.trackMapCall("/content.html", request);

        for (int i = 0; i < 2; i++)
            mt.trackMapCall("/content/foo.html", null);

        for (int i = 0; i < 5; i++)
            mt.trackMapCall("/content/bar.html", null);

        SomeService svc = new SomeService();

        for (int i = 0; i < 3; i++)
            svc.invoke(mt);

        StringWriter out = new StringWriter();
        mt.dump(new PrintWriter(out));
        mt.dump(new PrintWriter(System.out, true));

        String[] lines = out.toString().split("\\n");

        assertThat("Total lines", lines.length, equalTo(7));
        assertThat("First entry", lines[1],  allOf(containsString("10"), containsString("/content.html")));
        assertThat("First entry", lines[2],  allOf(containsString("7"), containsString("/content.html"), containsString("REQUEST:/content/source.html")));
        assertThat("Third entry", lines[3],  allOf(containsString("5"), containsString("/content/bar.html")));
        assertThat("Fourth entry", lines[4],  allOf(containsString("3"), containsString("/content/bar.html"), containsString("SomeService.invoke")));
        assertThat("Fifth entry", lines[5],  allOf(containsString("2"), containsString("/content/foo.html")));
    }

    static class SomeService {
        public void invoke(MapTracker tracker) {
            tracker.trackMapCall("/content/bar.html", null);
        }
    }
}
