package io.akka.human.local;

import io.akka.human.akka.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The system clock plus an offset the development routes move. Thread safe: one instance serves every
 * component of the service.
 */
public final class DevClock implements MovableClock {

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
