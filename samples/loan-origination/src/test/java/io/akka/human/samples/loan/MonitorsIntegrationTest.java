package io.akka.human.samples.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.human.akka.HumanQueries;
import io.akka.human.api.Action;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.Verdicts;
import io.akka.human.samples.loan.application.MonitorEntity;
import io.akka.human.samples.loan.application.Monitors;
import io.akka.human.samples.loan.application.NoticeWorkflow;
import io.akka.human.samples.loan.domain.LoanApplication;
import io.akka.human.testkit.HumanTestKit;
import java.util.Map;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * S2, S3 and S4: each monitor fires from the service's own records, raises its control's event, and
 * records what the person decided. Each test adds applications until its monitor fires, so the tests
 * do not depend on each other's order.
 */
public class MonitorsIntegrationTest extends LoanTestSupport {

  private static final int ENOUGH = 60;

  @Test
  public void referralDriftIsTriagedAndAHaltProposed() {
    var reading = until(Monitors.DRIFT, () -> {
      var id = apply(CLEAN);
      awaitApplication(id, a -> a.status().equals(LoanApplication.APPROVED));
    });
    var eventId = reading.get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("triage", event.settings().pattern().id());
    assertEquals("referral-share", event.subject().detail().get("metric"));

    HumanTestKit.ok(kit.claim(eventId, DEVOS), "claim");
    kit.advance(java.time.Duration.ofSeconds(20));
    HumanTestKit.ok(kit.decide(eventId, DEVOS, Action.HALT, "The assessor approves too much on its own."), "halt");
    var answer = Awaitility.await().atMost(WAIT).until(() -> monitor(Monitors.DRIFT).answer(), a -> a != null);
    assertTrue(answer.startsWith("halt proposed in he-"), answer);
    var proposal = kit.event(answer.substring("halt proposed in ".length(), answer.indexOf(" by ")));
    assertEquals("halt-restart", proposal.settings().pattern().id());
    assertTrue(proposal.pending());
    assertEquals(eventId, proposal.related().getFirst().eventId());

    var again = reading(Monitors.DRIFT);
    assertEquals(eventId, again.get("eventId").asText(), "an answered episode raises nothing new");
  }

  @Test
  public void rushedUnderwritingReviewsGoToTheRiskOfficer() {
    var watched = Monitors.RUSHED + ":custom-5a1e00000009";
    var reading = until(watched, () -> {
      var reviewId = awaitReview(apply(BORDERLINE));
      HumanTestKit.ok(kit.claim(reviewId, MOREAU), "claim");
      var rushed = HumanTestKit.ok(kit.decide(reviewId, MOREAU, Action.APPROVE, "Looks fine."), "decide");
      assertEquals("breached", Verdicts.of(rushed).conclusion());
    });
    var eventId = reading.get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("oversight-quality", event.settings().pattern().id());
    assertEquals("custom-5a1e00000009", event.subject().ref());
    assertEquals(403, kit.claim(eventId, OSEI).refusal().status());

    kit.claimAndDecide(eventId, DEVOS, Action.APPROVE, "Underwriters reminded to read the case file first.");
    var answer = Awaitility.await().atMost(WAIT).until(() -> monitor(watched).answer(), a -> a != null);
    assertEquals("reset by " + DEVOS.subject(), answer);
  }

  @Test
  public void burstsOfProtectedCharacteristicsBlocksGoToCompliance() {
    var reading = until(Monitors.BLOCKS, () -> {
      var id = apply(BORDERLINE);
      kit.claimAndDecide(awaitReview(id), LIND, Action.DENY, "Her illness makes the term too long.");
      Awaitility.await().atMost(WAIT).ignoreExceptions()
          .until(() -> componentClient.forWorkflow(id).method(NoticeWorkflow::get).invoke().status(), "HELD"::equals);
    });
    var eventId = reading.get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("triage", event.settings().pattern().id());
    assertEquals("compliance", event.queue());
    assertTrue(((Number) event.subject().detail().get("value")).doubleValue() > 5);

    kit.claimAndDecide(eventId, CHEN, Action.APPROVE, "Underwriters told to keep health out of rationales.");
    var answer = Awaitility.await().atMost(WAIT).until(() -> monitor(Monitors.BLOCKS).answer(), a -> a != null);
    assertEquals("acknowledged by " + CHEN.subject(), answer);
    assertNotNull(pendingOf("custom-5a1e00000008"));
  }

  // ---- helpers

  /** Adds to the records until the monitor fires; answers its reading. */
  private JsonNode until(String monitor, Runnable add) {
    for (int i = 0; i < ENOUGH; i++) {
      add.run();
      var reading = reading(monitor);
      if (reading != null && reading.get("fires").asBoolean()) return reading;
    }
    throw new AssertionError(monitor + " did not fire");
  }

  private JsonNode reading(String monitor) {
    var run = post("/monitors/run", Map.of());
    assertEquals(200, run.status(), run.body()::toString);
    for (var reading : run.body().get("readings")) {
      if (reading.get("monitor").asText().equals(monitor)) return reading;
    }
    return null;
  }

  private MonitorEntity.Monitor monitor(String key) {
    return componentClient.forKeyValueEntity(key).method(MonitorEntity::get).invoke();
  }

  private HumanEvent pendingOf(String control) {
    Supplier<HumanEvent> find = () -> HumanQueries.pending(componentClient, testKit.getMaterializer()).stream()
        .filter(e -> e.settings().control().rowId().equals(control)).findFirst().orElse(null);
    return Awaitility.await().atMost(WAIT).until(find::get, java.util.Objects::nonNull);
  }
}
