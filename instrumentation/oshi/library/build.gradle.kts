plugins {
  id("otel.library-instrumentation")
}

dependencies {
  library("com.github.oshi:oshi-core:5.8.0")

  testImplementation(project(":instrumentation:oshi:testing"))
}
