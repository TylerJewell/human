package io.akka.human.testkit;

import io.akka.human.akka.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The system clock plus an offset a test moves. Give it to the service's {@code HumanPlugins} in the
 * test's dependency provider; deadlines, claims and review times then follow it.
 */
public final class TestClock implements MovableClock {

  private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

  @Override
  public Instant now() {
    return Instant.now().plus(offset.get());
  }

  @Override
  public Duration offset() {
    return offset.get();
  }

  @Override
  public void advance(Duration by) {
    offset.updateAndGet(current -> current.plus(by));
  }

  @Override
  public void reset() {
    offset.set(Duration.ZERO);
  }
}
