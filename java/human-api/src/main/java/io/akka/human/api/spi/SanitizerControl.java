package io.akka.human.api.spi;

import java.util.List;

/**
 * A sanitizer control the service declares beside its human controls: Verify's {@code sanitizer_config},
 * where it runs and the data classes it covers. The service's configured sanitizers
 * ({@code akka.javasdk.sanitization}) do the masking.
 *
 * @param injectionPoint where the sanitizer runs; {@link #BEFORE_HUMAN_DISPLAY} masks what a reviewer is shown
 */
public record SanitizerControl(String id, String name, String injectionPoint, List<String> dataClasses) {

  public static final String BEFORE_HUMAN_DISPLAY = "before-human-display";

  public SanitizerControl {
    dataClasses = dataClasses == null ? List.of() : List.copyOf(dataClasses);
  }
}
