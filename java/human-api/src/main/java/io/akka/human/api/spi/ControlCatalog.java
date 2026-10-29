package io.akka.human.api.spi;

import java.util.List;
import java.util.Optional;

/** The human controls the service declares, read from its signed bundle or a YAML file. */
public interface ControlCatalog {

  List<HumanControl> controls();

  default Optional<HumanControl> find(String id) {
    return controls().stream().filter(c -> c.id().equals(id)).findFirst();
  }

  /** Identifies the catalog's content; every proof record carries it. */
  String digest();
}
