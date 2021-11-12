/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.log4j.v2_13_2;

import io.opentelemetry.sdk.logs.LogEmitter;
import io.opentelemetry.sdk.logs.SdkLogEmitterProvider;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.concurrent.GuardedBy;

public final class OpenTelemetryLog4j {

  private static final Object LOCK = new Object();

  @GuardedBy("LOCK")
  private static LogEmitter logEmitter;

  @GuardedBy("LOCK")
  private static final List<OpenTelemetryAppender> APPENDERS = new ArrayList<>();

  public static void initialize(SdkLogEmitterProvider sdkLogEmitterProvider) {
    LogEmitter logEmitter;
    List<OpenTelemetryAppender> instances;
    synchronized (LOCK) {
      if (OpenTelemetryLog4j.logEmitter != null) {
        throw new IllegalStateException("SdkLogEmitterProvider has already been set.");
      }
      logEmitter =
          sdkLogEmitterProvider.logEmitterBuilder(OpenTelemetryLog4j.class.getName()).build();
      OpenTelemetryLog4j.logEmitter = logEmitter;
      instances = new ArrayList<>(APPENDERS);
    }
    for (OpenTelemetryAppender instance : instances) {
      instance.initialize(logEmitter);
    }
  }

  static void registerInstance(OpenTelemetryAppender appender) {
    synchronized (LOCK) {
      if (logEmitter != null) {
        appender.initialize(logEmitter);
      }
      APPENDERS.add(appender);
    }
  }

  // Visible for testing
  static void resetForTest() {
    synchronized (LOCK) {
      logEmitter = null;
      APPENDERS.clear();
    }
  }

  private OpenTelemetryLog4j() {}
}
