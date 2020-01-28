/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import org.apache.sling.api.resource.Resource;
import org.junit.Test;

import static org.apache.sling.resourceresolver.impl.mapping.MapEntries.PROP_REDIRECT_EXTERNAL;
import static org.apache.sling.resourceresolver.util.MockTestUtil.ExpectedEtcMapping;
import static org.apache.sling.resourceresolver.util.MockTestUtil.setupStringInterpolationProvider;

/**
 * These are tests that are testing the Sling Interpolation Feature (SLING-7768)
 * on the MapEntries level
 */
public class StringInterpolationMapEntriesTest extends AbstractMappingMapEntriesTest {

    @Test
    public void simple_node_string_interpolation() throws Exception {
        // To avoid side effects the String Interpolation uses its own Resource Resolver
        Resource sivOne = setupEtcMapResource("$[config:siv.one]", http,PROP_REDIRECT_EXTERNAL, "/content/simple-node");
        setupStringInterpolationProvider(stringInterpolationProvider, stringInterpolationProviderConfiguration, new String[] {"siv.one=test-simple-node"});

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/test-simple-node/", "/content/simple-node/");
        expectedEtcMapping.assertEtcMap("String Interpolation for simple match", mapEntries.getResolveMaps());
    }

    @Test
    public void simple_match_string_interpolation() throws Exception {
        // To avoid side effects the String Interpolation uses its own Resource Resolver
        Resource sivOne = setupEtcMapResource("test-node", http,
            PROP_REG_EXP, "$[config:siv.one]/",
            PROP_REDIRECT_EXTERNAL, "/content/simple-match/"
        );
        setupStringInterpolationProvider(stringInterpolationProvider, stringInterpolationProviderConfiguration, new String[] {"siv.one=test-simple-match"});

        mapEntries.doInit();
        ExpectedEtcMapping expectedEtcMapping = new ExpectedEtcMapping("^http/test-simple-match/", "/content/simple-match/");
        expectedEtcMapping.assertEtcMap("String Interpolation for simple match", mapEntries.getResolveMaps());
    }
}
