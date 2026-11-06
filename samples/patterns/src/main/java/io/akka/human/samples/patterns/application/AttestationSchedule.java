package io.akka.human.samples.patterns.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;

/**
 * Asks the model-risk owner each quarter to attest that the eligibility model is still fit (pt-00013).
 * Each run raises the quarter's attestation and sets the timer for the start of the next quarter.
 */
@Component(id = "attestation-schedule")
public class AttestationSchedule extends TimedAction {

  public static final String TIMER = "attest-eligibility-model";
  static final String CADENCE = "P3M";

  private final Human human;
  private final TimerScheduler timers;
  private final ComponentClient client;

  public AttestationSchedule(Human human, TimerScheduler timers, ComponentClient client) {
    this.human = human;
    this.timers = timers;
    this.client = client;
  }

  public Effect run() {
    var now = Instant.now();
    var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var quarter = today.getYear() + "-Q" + today.get(IsoFields.QUARTER_OF_YEAR);
    human.raise(Patterns.attestation("Is the eligibility model still fit for use in " + quarter + "?")
        .control("pt-00013")
        .subject(Subject.attestation("Quarterly attestation of the eligibility model, " + quarter, "model",
                "eligibility-model", "Eligibility model v3",
                List.of(Map.of("kind", "validation-report", "ref", "vr-" + quarter, "summary", "Back-test within tolerance",
                    "asOf", now.toString(), "stale", false)))
            .with("cadence", CADENCE)
            .with("periodEnd", now.plus(Duration.ofDays(14)).toString())
            .with("lastAttestedAt", null)
            .withRef("eligibility-model"))
        .trigger(Trigger.rule("schedule", "quarterly", "The attestation for " + quarter + " is due."))
        .builtBy("model-team")
        .dedupeKey("attest-eligibility-model-" + quarter)
        .build()).toCompletableFuture().join();
    schedule(timers, client, nextQuarter(today));
    return effects().done();
  }

  /** Sets the timer for {@code at}, replacing the one set before. */
  public static void schedule(TimerScheduler timers, ComponentClient client, Instant at) {
    var delay = Duration.between(Instant.now(), at);
    timers.createSingleTimer(TIMER, delay.isNegative() ? Duration.ZERO : delay,
        client.forTimedAction().method(AttestationSchedule::run).deferred());
  }

  public static Instant nextQuarter(LocalDate today) {
    var firstMonth = (today.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1;
    return LocalDate.of(today.getYear(), firstMonth, 1).plusMonths(3).atStartOfDay().toInstant(ZoneOffset.UTC);
  }
}
