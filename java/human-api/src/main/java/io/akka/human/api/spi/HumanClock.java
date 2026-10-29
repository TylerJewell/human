package io.akka.human.api.spi;

import java.time.Instant;

/** The time the library uses for deadlines, claims and review times. Tests and dev routes move it. */
@FunctionalInterface
public interface HumanClock {
  Instant now();

  HumanClock SYSTEM = Instant::now;
}
