package io.akka.human.api.spi;

import java.util.List;
import java.util.Optional;

/**
 * Who may oversee. Stands in for platform request 2 (reviewers' roles from the identity provider):
 * the default reads the overseer registry file at startup.
 */
public interface OverseerDirectory {

  List<Overseer> overseers();

  default Optional<Overseer> find(String subject) {
    return overseers().stream().filter(o -> o.subject().equals(subject)).findFirst();
  }
}
