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
package org.apache.sling.resourceresolver.impl.providers;

import org.apache.sling.api.resource.runtime.dto.AuthType;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.ServiceReference;
import org.osgi.util.converter.Converter;
import org.osgi.util.converter.Converters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Information about a registered resource provider
 */
public class ResourceProviderInfo implements Comparable<ResourceProviderInfo> {

    public enum Mode {
        OVERLAY,
        PASSTHROUGH
    }

    private static final Logger logger = LoggerFactory.getLogger(ResourceProviderInfo.class);

    @SuppressWarnings("rawtypes")
    private final ServiceReference<ResourceProvider> ref;

    private final String path;

    private final String name;

    private final boolean useResourceAccessSecurity;

    private final AuthType authType;

    private final boolean modifiable;

    private final boolean adaptable;

    private final boolean refreshable;

    private final boolean attributable;

    private final Mode mode;

    @SuppressWarnings("rawtypes")
    public ResourceProviderInfo(final ServiceReference<ResourceProvider> ref) {
        final Converter c = Converters.standardConverter();
        this.ref = ref;
        this.path = c.convert(ref.getProperty(ResourceProvider.PROPERTY_ROOT))
                .defaultValue("")
                .to(String.class);
        this.name = c.convert(ref.getProperty(ResourceProvider.PROPERTY_NAME)).to(String.class);
        this.useResourceAccessSecurity = c.convert(
                        ref.getProperty(ResourceProvider.PROPERTY_USE_RESOURCE_ACCESS_SECURITY))
                .to(boolean.class);
        final String authType = c.convert(ref.getProperty(ResourceProvider.PROPERTY_AUTHENTICATE))
                .defaultValue(AuthType.no.name())
                .to(String.class);
        AuthType aType = null;
        try {
            aType = AuthType.valueOf(authType);
        } catch (final IllegalArgumentException iae) {
            logger.error("Illegal auth type {} for resource provider {} ({})", authType, name, ref);
        }
        this.authType = aType;
        this.modifiable =
                c.convert(ref.getProperty(ResourceProvider.PROPERTY_MODIFIABLE)).to(boolean.class);
        this.adaptable =
                c.convert(ref.getProperty(ResourceProvider.PROPERTY_ADAPTABLE)).to(boolean.class);
        this.refreshable = c.convert(ref.getProperty(ResourceProvider.PROPERTY_REFRESHABLE))
                .to(boolean.class);
        this.attributable = c.convert(ref.getProperty(ResourceProvider.PROPERTY_ATTRIBUTABLE))
                .to(boolean.class);
        final String modeValue = c.convert(ref.getProperty(ResourceProvider.PROPERTY_MODE))
                .defaultValue(ResourceProvider.MODE_OVERLAY)
                .to(String.class)
                .toUpperCase();
        Mode mode = null;
        try {
            mode = Mode.valueOf(modeValue);
        } catch (final IllegalArgumentException iae) {
            logger.error("Illegal mode {} for resource provider {} ({})", modeValue, name, ref);
        }
        this.mode = mode;
        if (!path.startsWith("/")) {
            logger.error("Path {} does not start with / for resource provider {} ({})", path, name, ref);
        }
    }

    public boolean isValid() {
        // TODO - do real path check
        if (!path.startsWith("/")) {
            logger.debug("ResourceProvider path does not start with /, invalid: {}", path);
            return false;
        }
        if (this.authType == null) {
            logger.debug("ResourceProvider has null authType, invalid");
            return false;
        }
        if (this.mode == null) {
            logger.debug("ResourceProvider has null mode, invalid");
            return false;
        }
        return true;
    }

    @SuppressWarnings("rawtypes")
    public ServiceReference<ResourceProvider> getServiceReference() {
        return this.ref;
    }

    public String getPath() {
        return this.path;
    }

    public Mode getMode() {
        return this.mode;
    }

    @Override
    public int compareTo(final ResourceProviderInfo o) {
        int result = path.compareTo(o.path);
        if (result == 0) {
            result = o.ref.compareTo(ref);
        }
        return result;
    }

    @Override
    public String toString() {
        return "ResourceProviderInfo [ref=" + ref + ", path=" + path + ", useResourceAccessSecurity="
                + useResourceAccessSecurity + ", authType=" + authType + ", modifiable=" + modifiable + "]";
    }

    public AuthType getAuthType() {
        return this.authType;
    }

    public boolean isModifiable() {
        return this.modifiable;
    }

    public boolean isAdaptable() {
        return adaptable;
    }

    public boolean isRefreshable() {
        return refreshable;
    }

    public boolean isAttributable() {
        return attributable;
    }

    public String getName() {
        return this.name;
    }

    public boolean getUseResourceAccessSecurity() {
        return this.useResourceAccessSecurity;
    }
}
