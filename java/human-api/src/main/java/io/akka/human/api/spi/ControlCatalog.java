package io.akka.human.api.spi;

import java.util.List;
import java.util.Optional;

/**
 * The human controls the service declares, and the sanitizer controls beside them, read from its
 * signed bundle or a YAML file.
 */
public interface ControlCatalog {

  List<HumanControl> controls();

  default Optional<HumanControl> find(String id) {
    return controls().stream().filter(c -> c.id().equals(id)).findFirst();
  }

  /** Identifies the catalog's content; every proof record carries it. */
  String digest();

  /** The sanitizer controls the service declares; none unless the catalog names them. */
  default List<SanitizerControl> sanitizers() {
    return List.of();
  }

  /** Whether a sanitizer control masks what a reviewer is shown. */
  default boolean masksHumanDisplay() {
    return sanitizers().stream().anyMatch(s -> SanitizerControl.BEFORE_HUMAN_DISPLAY.equals(s.injectionPoint()));
  }
}
