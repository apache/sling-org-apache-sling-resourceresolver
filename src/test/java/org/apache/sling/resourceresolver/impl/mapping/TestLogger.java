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
import java.util.Collections;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

// inspired by Oak's LogCustomizer
public class TestLogger implements AutoCloseable {
    private final Appender<ILoggingEvent> customLogger;

    private final Logger delegate;
    private String matchContainsMessage;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    private TestLogger(Class<?> clazz) {
        this.delegate = getLogger(clazz);

        this.customLogger = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent e) {
                String message = e.getFormattedMessage();
                if (matchContainsMessage == null || message.contains(matchContainsMessage)) {
                    logs.add(message);
                }
            }
        };

        this.customLogger.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    public static TestLogger create(Class<?> clazz) {
        return new TestLogger(clazz);
    }

    public TestLogger start() {
        if (delegate == null) {
            throw new IllegalStateException();
        }
        this.delegate.addAppender(this.customLogger);
        this.customLogger.start();
        return this;
    }

    public TestLogger contains(String matchContainsMessage) {
        this.matchContainsMessage = matchContainsMessage;
        return this;
    }

    public List<String> stopAndGetLogs() {
        if (this.delegate == null) {
            throw new IllegalStateException();
        }
        delegate.detachAppender(customLogger);
        customLogger.stop();
        return logs;
    }

    public void close() {
        if (this.delegate == null) {
            throw new IllegalStateException();
        }
        delegate.detachAppender(customLogger);
        customLogger.stop();
        logs.clear();
    }

    private static Logger getLogger(Class<?> clazz) {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(clazz);
    }
}
