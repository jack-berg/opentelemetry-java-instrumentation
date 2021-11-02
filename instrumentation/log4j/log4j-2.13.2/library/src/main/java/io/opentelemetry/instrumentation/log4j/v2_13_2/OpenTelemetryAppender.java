/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.log4j.v2_13_2;

import io.opentelemetry.sdk.logs.LogEmitter;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;

@Plugin(
    name = OpenTelemetryAppender.PLUGIN_NAME,
    category = Core.CATEGORY_NAME,
    elementType = Appender.ELEMENT_TYPE)
public class OpenTelemetryAppender extends AbstractAppender {

  static final String PLUGIN_NAME = "OpenTelemetry";

  @PluginBuilderFactory
  public static <B extends Builder<B>> B newBuilder() {
    return new Builder<B>().asBuilder();
  }

  public static class Builder<B extends Builder<B>> extends AbstractAppender.Builder<B>
      implements org.apache.logging.log4j.core.util.Builder<OpenTelemetryAppender> {

    @Override
    public OpenTelemetryAppender build() {
      OpenTelemetryAppender appender =
          new OpenTelemetryAppender(
              getName(), getLayout(), getFilter(), isIgnoreExceptions(), getPropertyArray());
      OpenTelemetryLog4j.registerInstance(appender);
      return appender;
    }
  }

  private final AtomicReference<LogEmitter> logEmitterRef = new AtomicReference<>();

  private OpenTelemetryAppender(
      String name,
      Layout<? extends Serializable> layout,
      Filter filter,
      boolean ignoreExceptions,
      Property[] properties) {
    super(name, filter, layout, ignoreExceptions, properties);
  }

  @Override
  public void append(LogEvent event) {
    LogEmitter logEmitter = logEmitterRef.get();
    if (logEmitter == null) {
      // appender hasn't been initialized
      return;
    }
    LogEventMapper.toLogBuilder(logEmitter, event).emit();
  }

  void initialize(LogEmitter logEmitter) {
    if (!logEmitterRef.compareAndSet(null, logEmitter)) {
      throw new IllegalStateException("OpenTelemetryAppender has already been initialized.");
    }
  }
}
