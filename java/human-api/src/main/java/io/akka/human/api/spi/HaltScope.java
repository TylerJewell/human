package io.akka.human.api.spi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * What a halt means for one target, supplied by the service that declares it. The library asks for
 * the safe-state checks before a restart can be approved, and tells the service when the target
 * stops and starts.
 */
public interface HaltScope {

  /** Each check's name and whether it passes now. A restart needs them all to pass. */
  CompletionStage<Map<String, Boolean>> safeStateChecks(String target);

  default CompletionStage<Void> halted(String target, String reason) {
    return java.util.concurrent.CompletableFuture.completedFuture(null);
  }

  default CompletionStage<Void> lifted(String target) {
    return java.util.concurrent.CompletableFuture.completedFuture(null);
  }

  /** The calls per minute the target is making, shown beside a halt; null when unknown. */
  default Double callsPerMinute(String target) {
    return null;
  }

  HaltScope ALWAYS_SAFE = target -> java.util.concurrent.CompletableFuture.completedFuture(Map.of());

  static List<String> failing(Map<String, Boolean> checks) {
    return checks.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
  }
}
