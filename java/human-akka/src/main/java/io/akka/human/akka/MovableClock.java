package io.akka.human.akka;

import io.akka.human.api.spi.HumanClock;
import java.time.Duration;

/**
 * A clock the development routes can move ({@code POST /oversight/dev/clock}). Real timers still run
 * on real time; {@code POST /oversight/dev/tick} acts on what is due at the moved time.
 */
public interface MovableClock extends HumanClock {

  Duration offset();

  void advance(Duration by);

  void reset();
}
