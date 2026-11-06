package io.akka.human.samples.loan.application;

import akka.javasdk.client.ComponentClient;
import io.akka.human.api.spi.HaltScope;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * What a halt of the automated eligibility assessment means (custom-d04d2229e3f9). While halted the
 * underwriting workflow asks no assessor and approves nothing alone, so the underwriters' queue fills;
 * a restart may be approved only once no application is between its documents and routing, and the
 * queue is back to {@code maxBacklog}.
 */
public final class EligibilityHaltScope implements HaltScope {

  static final Duration RATE_WINDOW = Duration.ofMinutes(10);

  private final ComponentClient client;
  private final int maxBacklog;

  public EligibilityHaltScope(ComponentClient client, int maxBacklog) {
    this.client = client;
    this.maxBacklog = maxBacklog;
  }

  @Override
  public CompletionStage<Map<String, Boolean>> safeStateChecks(String target) {
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("no application is between its documents and routing", count(LoanApplication.UNDER_ASSESSMENT) == 0);
    checks.put("at most " + maxBacklog + " applications wait for an underwriter",
        count(LoanApplication.AWAITING_HUMAN_REVIEW) <= maxBacklog);
    return CompletableFuture.completedFuture(checks);
  }

  /** Automated assessments per minute over the last ten minutes. */
  @Override
  public Double callsPerMinute(String target) {
    var since = new ApplicationsView.Since(Instant.now().minus(RATE_WINDOW));
    return client.forView().method(ApplicationsView::assessedSince).invoke(since).entries().size() / (double) RATE_WINDOW.toMinutes();
  }

  private int count(String status) {
    return client.forView().method(ApplicationsView::withStatus).invoke(new ApplicationsView.WithStatus(status)).entries().size();
  }
}
