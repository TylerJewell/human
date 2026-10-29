package io.akka.human.api.spi;

import io.akka.human.api.Decision;
import java.util.concurrent.CompletionStage;

/**
 * Calls a waiting workflow back with the final outcome. A service registers one per target name,
 * usually a one-liner over its component client:
 *
 * <pre>{@code
 * (workflowId, decision) -> componentClient.forWorkflow(workflowId)
 *     .method(PaymentWorkflow::decided).invokeAsync(decision).thenApply(done -> null)
 * }</pre>
 */
@FunctionalInterface
public interface Resumer {
  CompletionStage<Void> resume(String id, Decision decision);
}
