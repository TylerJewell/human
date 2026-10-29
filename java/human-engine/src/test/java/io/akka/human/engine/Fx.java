package io.akka.human.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.SubjectKind;
import io.akka.human.api.spi.HumanControl;
import io.akka.human.api.spi.Overseer;
import io.akka.human.engine.Qualification.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The fixtures and short-hands the engine tests share: raise, claim, decide and tick at given times. */
final class Fx {

  static final Instant T0 = Instant.parse("2026-10-29T14:00:00Z");
  static final YamlCatalog CATALOG = YamlCatalog.fromClasspath("spec/fixtures/controls-patterns.yaml");
  static final Controls CONTROLS = Controls.of(CATALOG);
  static final YamlOverseers OVERSEERS = YamlOverseers.fromClasspath("spec/fixtures/overseers.yaml");
  static final Raise.Context CONTEXT = new Raise.Context("patterns", "local", CATALOG.digest(),
      subject -> OVERSEERS.find(subject).map(Overseer::reportsTo).orElse(null));

  static final String TYLER = "tyler.jewell@akka.io";
  static final String DEVOS = "a.devos@example.com";
  static final String CHEN = "r.chen@example.com";
  static final String PATEL = "s.patel@example.com";
  static final String LIND = "j.lind@example.com";
  static final String OKAFOR = "m.okafor@example.com";
  static final String MOREAU = "l.moreau@example.com";
  static final String OSEI = "k.osei@example.com";

  private static final AtomicInteger IDS = new AtomicInteger();

  private Fx() {}

  static ControlSettings control(String id) {
    return CONTROLS.find(id).orElseThrow();
  }

  static HumanControl humanControl(String id, String pattern, String flavor, String kind, Object... settings) {
    var hc = new LinkedHashMap<String, Object>();
    hc.put("pattern", pattern);
    hc.put("subject_kind", kind);
    for (int i = 0; i < settings.length; i += 2) hc.put((String) settings[i], settings[i + 1]);
    Map<String, Object> halt = pattern.equals("halt-restart")
        ? Map.of("scope", "capability", "target", "eligibility-assessment", "exerciser_personae", List.of("safety-officer"),
            "exercise_endpoint", "POST /oversight/halts", "affected_calls", "all-runtime-operations",
            "emits_on_exercise", "HaltExercised")
        : Map.of();
    return new HumanControl(id, "Custom " + pattern, null, flavor, 1, hc, halt);
  }

  /** A control built in the test, checked against the registries before it is resolved. */
  static ControlSettings custom(String pattern, String flavor, String kind, Object... settings) {
    var control = humanControl("x-" + IDS.incrementAndGet(), pattern, flavor, kind, settings);
    var problems = Settings.problems(control);
    assertTrue(problems.isEmpty(), problems.toString());
    return Settings.resolve(control);
  }

  static Subject subjectFor(SubjectKind kind) {
    return switch (kind) {
      case ACTION -> Subject.action("Pay S-77 4,200 EUR", "pay", Map.of("supplier", "S-77", "amount", 4200));
      case BEHAVIOUR -> Subject.behaviour("Referral share fell", "referral-share", 12, 20, "<");
      case CHANGE -> Subject.change("Roll out m-5", "model-registry", "rollout", "rollout-12", Map.of("model", "m-4"),
          Map.of("model", "m-5"));
      case SYSTEM -> Subject.system("Halt eligibility", "capability", "eligibility-assessment", "all-runtime-operations",
          false);
      case OUTPUT -> Subject.output("Adverse notice", "Your application was declined.");
      case RECORDS -> Subject.records("Possible duplicate", "customer",
          Map.of("source", "crm", "ref", "c-1", "fields", Map.of("name", "Ada")),
          Map.of("source", "billing", "ref", "b-9", "fields", Map.of("name", "Ada L.")));
      case APPEAL -> Subject.appeal("Appeal of D-1", Map.of("ref", "D-1", "decision", "DENY", "summary", "Declined"),
          "The income was misread.");
      case EXCEPTION -> Subject.exception("Waive rule R-4", "R-4", "No payments to new suppliers", "supplier S-77",
          T0.plus(Duration.ofDays(30)));
      case CONVERSATION -> Subject.conversation("Customer asks for a person", "chat", "user-asked",
          List.of(Map.of("role", "user", "text", "I want to talk to a person")));
      case ATTESTATION -> Subject.attestation("Quarterly attestation", "model", "m-5", "Eligibility model",
          List.of(Map.of("kind", "eval", "ref", "ev-9")));
    };
  }

  static RaiseRequest.Builder request(ControlSettings s) {
    return RaiseRequest.of(s.pattern(), "Question for " + s.control().rowId() + "?")
        .control(s.control().rowId())
        .subject(subjectFor(s.subjectKind()));
  }

  static HumanEvent raise(ControlSettings s) {
    return raise(s, request(s).build(), T0);
  }

  static HumanEvent raise(ControlSettings s, RaiseRequest r) {
    return raise(s, r, T0);
  }

  static HumanEvent raise(ControlSettings s, RaiseRequest r, Instant at) {
    return Raise.event("he-test-" + IDS.incrementAndGet(), s, r, CONTEXT, at);
  }

  static Overseer overseer(String subject) {
    return OVERSEERS.find(subject).orElseThrow();
  }

  /** The person as the registry knows them, acting with every role they hold there. */
  static Reviewer reviewer(String subject) {
    var o = overseer(subject);
    return new Reviewer(subject, o.name(), o.roles(), "test", false);
  }

  static Actor actor(String subject) {
    return new Actor(reviewer(subject), OVERSEERS.find(subject).orElse(null), true);
  }

  static Actor actor(String subject, String... roles) {
    return new Actor(new Reviewer(subject, subject, List.of(roles), "test", false), OVERSEERS.find(subject).orElse(null),
        true);
  }

  static Rules.DecisionRequest decision(Reviewer reviewer, Action action) {
    return new Rules.DecisionRequest(reviewer, action, "Checked the subject.", null, null, null, List.of(), null);
  }

  static HumanEvent ok(HumanEvent e, Checked<List<HumanEventEvent>> checked) {
    assertNull(checked.refusal(), () -> "refused: " + checked.refusal());
    var next = e;
    for (var event : checked.value()) next = next.apply(event);
    return next;
  }

  static Refusal refused(Checked<?> checked, int status) {
    assertNotNull(checked.refusal(), "expected a refusal");
    assertEquals(status, checked.refusal().status(), checked.refusal().toString());
    return checked.refusal();
  }

  static HumanEvent claim(HumanEvent e, String subject, Instant at) {
    return ok(e, Rules.claim(e, actor(subject), at));
  }

  static HumanEvent decide(HumanEvent e, String subject, Action action, Instant at) {
    return ok(e, Rules.decide(e, actor(subject), decision(reviewer(subject), action), Map.of(), at));
  }

  static HumanEvent tick(HumanEvent e, Instant at) {
    var next = e;
    for (var event : Rules.tick(e, at)) next = next.apply(event);
    return next;
  }

  static Instant at(Duration after) {
    return T0.plus(after);
  }

  static Instant seconds(long n) {
    return T0.plusSeconds(n);
  }

  static Instant minutes(long n) {
    return T0.plus(Duration.ofMinutes(n));
  }
}
