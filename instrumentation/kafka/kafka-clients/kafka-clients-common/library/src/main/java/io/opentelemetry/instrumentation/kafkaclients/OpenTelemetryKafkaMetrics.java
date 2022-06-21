/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.kafkaclients;

import static io.opentelemetry.instrumentation.kafkaclients.InstrumentDescriptor.INSTRUMENT_TYPE_DOUBLE_OBSERVABLE_COUNTER;
import static io.opentelemetry.instrumentation.kafkaclients.InstrumentDescriptor.INSTRUMENT_TYPE_DOUBLE_OBSERVABLE_GAUGE;
import static java.lang.System.lineSeparator;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;

/**
 * A {@link MetricsReporter} which bridges Kafka metrics to OpenTelemetry metrics.
 *
 * <p>To use, configure OpenTelemetry instance via {@link #setOpenTelemetry(OpenTelemetry)}, and
 * include a reference to this class in kafka producer or consumer configuration, i.e.:
 *
 * <pre>{@code
 * //    Map<String, Object> config = new HashMap<>();
 * //    // Register OpenTelemetryKafkaMetrics as reporter
 * //    config.put(ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG, OpenTelemetryKafkaMetrics.class.getName());
 * //    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ...);
 * //    ...
 * //    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(config)) { ... }
 * }</pre>
 */
public class OpenTelemetryKafkaMetrics implements MetricsReporter {

  private static final Logger logger = Logger.getLogger(OpenTelemetryKafkaMetrics.class.getName());
  private static final Set<KafkaMetricId> seenMetrics = ConcurrentHashMap.newKeySet();

  @Nullable private static volatile Meter meter;

  private static final Map<RegisteredInstrument, AutoCloseable> instrumentMap =
      new ConcurrentHashMap<>();

  /**
   * Setup OpenTelemetry. This should be called as early in the application lifecycle as possible.
   * Kafka metrics that are observed before this is called may not be bridged.
   */
  public static void setOpenTelemetry(OpenTelemetry openTelemetry) {
    meter = openTelemetry.getMeter("io.opentelemetry.kafka-clients");
  }

  /**
   * Reset for test by reseting the {@link #meter} to {@code null} and closing all registered
   * instruments.
   */
  static void resetForTest() {
    meter = null;
    closeAllInstruments();
  }

  /**
   * Print a table mapping kafka metrics to equivalent OpenTelemetry metrics, in markdown format.
   */
  static void printMappingTable() {
    StringBuilder sb = new StringBuilder();
    // Append table headers
    sb.append(
            "| # | Metric Group | Metric Name | Attribute Keys | Instrument Name | Instrument Description | Instrument Type |")
        .append(lineSeparator())
        .append(
            "|---|--------------|-------------|----------------|-----------------|------------------------|-----------------|")
        .append(lineSeparator());
    Map<String, List<KafkaMetricId>> kafkaMetricsByGroup =
        seenMetrics.stream().collect(groupingBy(KafkaMetricId::getGroup));
    int count = 1;
    // Iterate through groups in alpha order
    for (String group : kafkaMetricsByGroup.keySet().stream().sorted().collect(toList())) {
      List<KafkaMetricId> kafkaMetricIds =
          kafkaMetricsByGroup.get(group).stream()
              .sorted(comparing(KafkaMetricId::getName))
              .collect(toList());
      // Iterate through metrics in alpha order by name
      for (KafkaMetricId kafkaMetricId : kafkaMetricIds) {
        Optional<InstrumentDescriptor> descriptor =
            Optional.ofNullable(KafkaMetricRegistry.getInstrumentDescriptor(kafkaMetricId));
        // Append table row
        sb.append(
            String.format(
                "| %s | %s | %s | %s | %s | %s | %s |%n",
                count,
                "`" + group + "`",
                "`" + kafkaMetricId.getName() + "`",
                String.join(",", kafkaMetricId.getAttributeKeys()),
                descriptor.map(i -> "`" + i.getName() + "`").orElse(""),
                descriptor.map(InstrumentDescriptor::getDescription).orElse(""),
                descriptor.map(i -> "`" + i.getInstrumentType() + "`").orElse("")));
        count++;
      }
    }
    logger.log(Level.INFO, "Mapping table" + System.lineSeparator() + sb);
  }

  @Override
  public void init(List<KafkaMetric> metrics) {
    metrics.forEach(this::metricChange);
  }

  @Override
  public void metricChange(KafkaMetric metric) {
    KafkaMetricId kafkaMetricId = KafkaMetricId.create(metric);
    seenMetrics.add(kafkaMetricId);
    Meter currentMeter = meter;
    if (currentMeter == null) {
      logger.log(Level.FINEST, "Metric changed but meter not set: {0}", kafkaMetricId);
      return;
    }

    InstrumentDescriptor instrumentDescriptor =
        KafkaMetricRegistry.getInstrumentDescriptor(kafkaMetricId);
    if (instrumentDescriptor == null) {
      logger.log(Level.FINEST, "Metric changed but cannot map to instrument: {0}", kafkaMetricId);
      return;
    }

    RegisteredInstrument registeredInstrument =
        RegisteredInstrument.create(kafkaMetricId, toAttributes(metric));

    instrumentMap.compute(
        registeredInstrument,
        (registeredInstrument1, autoCloseable) -> {
          if (autoCloseable != null) {
            logger.log(Level.FINEST, "Replacing instrument {0}", registeredInstrument1);
            try {
              autoCloseable.close();
            } catch (Exception e) {
              logger.log(Level.WARNING, "An error occurred closing instrument", e);
            }
          } else {
            logger.log(Level.FINEST, "Adding instrument {0}", registeredInstrument1);
          }
          return createObservable(
              currentMeter, registeredInstrument1, instrumentDescriptor, metric);
        });
  }

  private static AutoCloseable createObservable(
      Meter meter,
      RegisteredInstrument registeredInstrument,
      InstrumentDescriptor instrumentDescriptor,
      KafkaMetric kafkaMetric) {
    Consumer<ObservableDoubleMeasurement> callback =
        observableMeasurement ->
            observableMeasurement.record(kafkaMetric.value(), registeredInstrument.getAttributes());
    switch (instrumentDescriptor.getInstrumentType()) {
      case INSTRUMENT_TYPE_DOUBLE_OBSERVABLE_GAUGE:
        return meter
            .gaugeBuilder(instrumentDescriptor.getName())
            .setDescription(instrumentDescriptor.getDescription())
            .buildWithCallback(callback);
      case INSTRUMENT_TYPE_DOUBLE_OBSERVABLE_COUNTER:
        return meter
            .counterBuilder(instrumentDescriptor.getName())
            .setDescription(instrumentDescriptor.getDescription())
            .ofDoubles()
            .buildWithCallback(callback);
      default: // Continue below to throw
    }
    // TODO: add support for other instrument types and value types as needed for new instruments
    // registered in KafkaMetricRegistry.
    // This should not happen.
    throw new IllegalStateException("Unrecognized instrument type. This is a bug.");
  }

  private static Attributes toAttributes(KafkaMetric kafkaMetric) {
    AttributesBuilder attributesBuilder = Attributes.builder();
    kafkaMetric.metricName().tags().forEach(attributesBuilder::put);
    return attributesBuilder.build();
  }

  @Override
  public void metricRemoval(KafkaMetric metric) {
    KafkaMetricId kafkaMetricId = KafkaMetricId.create(metric);
    seenMetrics.add(kafkaMetricId);
    logger.log(Level.FINEST, "Metric removed: " + kafkaMetricId);
    AutoCloseable observable =
        instrumentMap.remove(RegisteredInstrument.create(kafkaMetricId, toAttributes(metric)));
    if (observable != null) {
      closeInstrument(observable);
    }
  }

  @Override
  public void close() {
    closeAllInstruments();
  }

  static void closeAllInstruments() {
    for (Iterator<Map.Entry<RegisteredInstrument, AutoCloseable>> it =
            instrumentMap.entrySet().iterator();
        it.hasNext(); ) {
      closeInstrument(it.next().getValue());
      it.remove();
    }
  }

  private static void closeInstrument(AutoCloseable observable) {
    try {
      observable.close();
    } catch (Exception e) {
      throw new IllegalStateException("Error occurred closing instrument", e);
    }
  }

  @Override
  public void configure(Map<String, ?> configs) {}
}
