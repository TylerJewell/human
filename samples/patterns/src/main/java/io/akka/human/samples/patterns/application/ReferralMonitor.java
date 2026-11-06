package io.akka.human.samples.patterns.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.timedaction.TimedAction;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.util.List;

/**
 * Watches the share of eligibility assessments referred to a person. Below 20% against a baseline of
 * 35% it raises a triage event for the model-risk owner (pt-00010); while that event is pending, a
 * repeated reading adds an occurrence to it.
 */
@Component(id = "referral-monitor")
public class ReferralMonitor extends TimedAction {

  static final double THRESHOLD = 20;
  static final double BASELINE = 35;

  /** The referral share in percent over the last 24 hours. */
  public record Reading(double referralShare) {}

  private final Human human;

  public ReferralMonitor(Human human) {
    this.human = human;
  }

  public Effect check(Reading reading) {
    if (reading.referralShare() >= THRESHOLD) return effects().done();
    var value = reading.referralShare();
    human.raise(Patterns.triage("Referral share fell to " + value + "%. Resolve, dismiss or report it?")
        .control("pt-00010")
        .subject(Subject.behaviour("Referral share " + value + "% against a baseline of " + BASELINE + "%",
                "referral-share", value, THRESHOLD, "<")
            .with("unit", "%").with("window", "PT24H").with("baseline", BASELINE)
            .withRef("eligibility-assessment"))
        .trigger(new Trigger("monitor", "referral-share < 20", "The referral share dropped below the threshold.",
            List.of(new Trigger.Value("referral-share", value, THRESHOLD)),
            List.of(new Trigger.Condition("referral-share < 20", true))))
        .dedupeKey("referral-drift")
        .build()).toCompletableFuture().join();
    return effects().done();
  }
}
