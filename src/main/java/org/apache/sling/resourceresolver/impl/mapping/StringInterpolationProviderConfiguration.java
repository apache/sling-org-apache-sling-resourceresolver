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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Apache Sling String Interpolation Provider",
    description = "Configures the String Interpolation Provider key/value pairs"
)
public @interface StringInterpolationProviderConfiguration {

    @AttributeDefinition(
        name = "Placeholder Values",
        description = "A list of key / value pairs separated by a equal (=) sign. " +
            "The key is not permitted to contain a '=' sign as the first occurrence of '=' " +
            "separates the key from the value. If no '=' is found the entry " +
            "is ignored")
    String[] placeHolderKeyValuePairs() default {"phv.default.host.name=localhost"};
}
