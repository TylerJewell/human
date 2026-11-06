package io.akka.human.engine;

import static io.akka.human.engine.Fx.CHEN;
import static io.akka.human.engine.Fx.DEVOS;
import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.MOREAU;
import static io.akka.human.engine.Fx.PATEL;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.actor;
import static io.akka.human.engine.Fx.at;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import io.akka.human.api.Action;
import io.akka.human.api.Resume;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Sticky;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Engine-produced JSON against spec/oversight.schema.json, def by def. */
class SchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectNode SCHEMA = load();
  private static final Map<String, JsonSchema> BY_DEF = new ConcurrentHashMap<>();
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true)
      .build();

  private static ObjectNode load() {
    try (InputStream in = SchemaTest.class.getClassLoader().getResourceAsStream("spec/oversight.schema.json")) {
      return (ObjectNode) MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonSchema schema(String def) {
    return BY_DEF.computeIfAbsent(def, d -> {
      if (!SCHEMA.get("$defs").has(d)) throw new IllegalArgumentException("no $defs/" + d);
      var node = SCHEMA.deepCopy();
      node.put("$ref", "#/$defs/" + d);
      return FACTORY.getSchema(node, CONFIG);
    });
  }

  /** Fails with every message when the value does not conform to {@code $defs/def}. */
  static void assertConforms(String def, Object value) {
    JsonNode json = MAPPER.valueToTree(value);
    var errors = schema(def).validate(json);
    assertTrue(errors.isEmpty(), () -> def + " does not conform: " + errors + "\n" + json.toPrettyString());
  }

  // ---- the harness itself

  @Test
  void everyExampleConformsToItsDefinition() throws IOException, URISyntaxException {
    var dir = Path.of(SchemaTest.class.getClassLoader().getResource("spec/examples").toURI());
    int checked = 0;
    try (var files = Files.list(dir)) {
      for (var file : files.toList()) {
        var def = file.getFileName().toString().split("\\.")[0];
        assertConforms(def, MAPPER.readTree(file.toFile()));
        checked++;
      }
    }
    assertTrue(checked >= 20, "examples checked: " + checked);
  }

  @Test
  void theHarnessRejectsWhatTheSchemaRejects() {
    var bad = Map.of("id", "x", "state", "PONDERING");
    assertFalse(schema("HumanEvent").validate(MAPPER.valueToTree(bad)).isEmpty());
    assertFalse(schema("Verdict").validate(MAPPER.valueToTree(Map.of("id", "v", "eventId", "e", "conclusion", "maybe",
        "source", Map.of("kind", "runtime"), "mechanism", "human", "boundary", Map.of("kind", "work-item", "ref", "r"),
        "at", "2026-10-29T14:00:00Z"))).isEmpty());
    assertFalse(schema("Instant").validate(MAPPER.valueToTree("yesterday")).isEmpty());
  }

  // ---- engine output

  /** Events across all fourteen patterns, in pending and closed states. */
  static List<HumanEvent> events() {
    var out = new ArrayList<HumanEvent>();
    for (var s : Fx.CONTROLS.all()) out.add(raise(s));

    var pay = control("pt-00001");
    var payment = raise(pay, request(pay).proposedBy("agent:payments").dedupeKey("pay-1").matchKey("pay:S-77")
        .check("The invoice matches the purchase order.", "subject").check("No section", null)
        .resumes(Resume.workflow("payment", "pay-1")).effect(Action.APPROVE, "The payment is sent")
        .evidence("invoice", "inv-9").related("he-0", "x-follows")
        .aiRecommendation("judge", Action.APPROVE, 0.8, "Matches the PO")
        .trigger(new Trigger("tool-policy", "pay.amount > 1000", "The agent asked to pay 4,200 EUR.",
            List.of(new Trigger.Value("amount", 4200, 1000)), List.of(new Trigger.Condition("amount > 1000", true))))
        .build());
    out.add(payment);
    var claimed = claim(payment, TYLER, T0);
    out.add(claimed);
    var edits = Map.<String, Object>of("summary", "amount 4200 to 4000",
        "patch", List.of(Map.of("op", "replace", "path", "/amount", "value", 4000)));
    var edited = ok(claimed, Rules.decide(claimed, actor(TYLER), new Rules.DecisionRequest(reviewer(TYLER), Action.EDIT,
        "Pay 4,000", edits, Sticky.APPROVE_FOR_PATTERN, null, List.of("The invoice matches the purchase order."), null),
        Map.of(), seconds(20)));
    out.add(edited);
    out.add(ok(edited, Rules.applied(edited, "Resumed payment pay-1", seconds(21))));
    out.add(ok(edited, Rules.applyFailed(edited, "the workflow is gone", seconds(21))));
    out.add(ok(payment, Rules.requestMoreInfo(payment, actor(TYLER), "Which order?", minutes(1))));
    out.add(ok(payment, Rules.extend(payment, actor(TYLER), "PT1H", "Waiting", minutes(1))));
    out.add(ok(payment, Rules.escalate(payment, actor(TYLER), "Above my limit", minutes(1))));
    out.add(tick(payment, minutes(23)));
    out.add(tick(payment, minutes(30)));
    out.add(ok(payment, Rules.withdraw(payment, Reviewer.of("agent:payments"), "Not needed", minutes(2))));
    out.add(ok(payment, Rules.kill(payment, "dev", "reset", minutes(2))));
    out.add(ok(payment, Rules.settleByStanding(payment, Fx.grant("sd-1"), Action.APPROVE, T0)));
    var reopened = ok(edited, Rules.reopen(edited, Reviewer.of("o", "ai-system-owner"), "New facts", "he-new", minutes(3)));
    out.add(reopened);

    var change = control("pt-00003");
    var rollout = raise(change, request(change).proposedBy("agent:rollouts").builtBy("trainer").build());
    var firstVote = decide(rollout, TYLER, Action.APPROVE, minutes(1));
    out.add(firstVote);
    out.add(decide(firstVote, DEVOS, Action.APPROVE, minutes(2)));
    var sentBack = ok(rollout, Rules.sendBack(rollout, actor(TYLER), "Add the eval", minutes(1)));
    out.add(sentBack);
    out.add(ok(sentBack, Rules.resubmit(sentBack, Reviewer.of("agent:rollouts"), "Done", minutes(2))));
    out.add(tick(rollout, at(Duration.ofDays(1))));

    var halt = control("pt-00012");
    out.add(decide(raise(halt), PATEL, Action.HALT, minutes(1)));
    var lift = raise(halt, request(halt).subject(Subject.system("Restart", "capability", "eligibility-assessment",
        "all-runtime-operations", true)).build());
    var liftVote = ok(lift, Rules.decide(lift, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE),
        Map.of("backlog drained", true), minutes(1)));
    out.add(liftVote);
    out.add(ok(liftVote, Rules.decide(liftVote, actor(PATEL), Fx.decision(reviewer(PATEL), Action.APPROVE),
        Map.of("backlog drained", true), minutes(2))));

    var appeal = control("pt-00007");
    var appealed = claim(raise(appeal, request(appeal).firstReviewer(Fx.OKAFOR).build()), LIND, T0);
    out.add(ok(appealed, Rules.recuse(appealed, reviewer(LIND), "Conflict", minutes(1))));
    out.add(decide(appealed, LIND, Action.REVERSE, minutes(1)));

    var override = control("pt-00008");
    out.add(ok(raise(override), Rules.decide(raise(override), actor(PATEL), new Rules.DecisionRequest(reviewer(PATEL),
        Action.OVERRIDE, "Wrong figure", null, null, null, List.of(), "The corrected output"), Map.of(), minutes(1))));

    out.add(tick(raise(control("pt-00009")), at(Duration.ofDays(3))));
    out.add(tick(tick(raise(control("pt-00002")), at(Duration.ofHours(4))), at(Duration.ofHours(8))));
    out.add(tick(raise(control("pt-00013")), at(Duration.ofDays(14))));
    out.add(decide(claim(raise(control("pt-00011")), MOREAU, T0), MOREAU, Action.DECLINE_SYSTEM, minutes(6)));
    out.add(decide(raise(control("pt-00005")), MOREAU, Action.APPROVE, minutes(1)));
    out.add(decide(raise(control("pt-00010")), DEVOS, Action.DISREGARD, minutes(1)));
    out.add(decide(raise(control("pt-00014")), TYLER, Action.REPLAN, minutes(1)));
    out.add(decide(raise(control("pt-00006")), LIND, Action.REPLAN, minutes(1)));
    out.add(decide(raise(control("pt-00004")), CHEN, Action.APPROVE, minutes(1)));
    out.add(raise(Fx.custom("triage", "human-monitoring", "behaviour", "routing", Map.of("roles", List.of("model-risk-owner")),
        "shadow", true)));
    return out;
  }

  @Test
  void eventsRowsAndVerdictsConform() {
    var events = events();
    var patterns = events.stream().map(e -> e.settings().pattern()).distinct().count();
    assertEquals(14, patterns);
    assertTrue(events.stream().anyMatch(e -> Verdicts.of(e) != null));
    for (var now : List.of(T0, minutes(25), at(Duration.ofDays(2)))) {
      for (var e : events) {
        assertConforms("HumanEvent", Json.event(e, now));
        assertConforms("HumanEventRow", Json.row(e, now));
        var verdict = Json.verdict(e);
        if (verdict != null) assertConforms("Verdict", verdict);
      }
    }
    var page = Json.map("events", events.stream().map(e -> Json.event(e, T0)).toList(), "total", events.size());
    assertConforms("EventsPage", page);
    assertConforms("CompanionEventsPage", Json.map("rows", events.stream().map(e -> Json.row(e, T0)).toList(),
        "total", events.size(), "next", null));
  }

  @Test
  void controlSettingsConform() {
    for (var s : Fx.CONTROLS.all()) assertConforms("ControlSettings", Json.controlSettings(s));
    assertConforms("ControlSettings", Json.controlSettings(Fx.custom("action-approval", "human-system-authorization",
        "action", "routing", Map.of("roles", List.of("a"), "review_mode", "N_OF_M", "required", 2, "of", 3))));
  }

  @Test
  void standingHaltsRegistryAndErrorsConform() {
    var pay = control("pt-00001");
    var e = claim(raise(pay, request(pay).matchKey("pay:S-77").build()), TYLER, T0);
    var decided = ok(e, Rules.decide(e, actor(TYLER), new Rules.DecisionRequest(reviewer(TYLER), Action.APPROVE, "ok",
        null, Sticky.APPROVE_FOR_PATTERN, null, List.of(), null), Map.of(), seconds(15)));
    var standing = StandingDecision.from(decided);
    assertConforms("StandingDecision", Json.standing(standing, "payments", minutes(1)));
    assertEquals("payments", Json.standing(standing, "payments", minutes(1)).get("service"));
    assertConforms("StandingDecision", Json.standing(standing.usedFor("he-2").revokedBy(TYLER, minutes(2), "no"), "payments",
        minutes(3)));

    var halt = control("pt-00012");
    var none = HaltState.notHalted(halt);
    assertConforms("Halt", Json.halt(none, Map.of(), null, "patterns"));
    assertConforms("Halt", Json.halt(none.exercised(PATEL, "Drift", "he-1", "pr-he-1-halt", T0).liftRaised("he-2"),
        Map.of("backlog drained", false), 0.0, "patterns"));

    assertConforms("OverseerRegistry", Json.registry(Fx.OVERSEERS.overseers(), Fx.CATALOG.controls(), T0));

    assertConforms("Error", Json.error(Refusal.notFound("event not found")));
    assertConforms("Error", Json.error(Refusal.conflict("claimed by another reviewer", "CLAIMED")));
    assertConforms("Error", Json.error(Refusal.forbidden("not qualified")));
    assertConforms("Error", Json.error(Refusal.invalid("a rationale is required")));
    assertEquals(Json.map("error", "claimed by another reviewer", "state", "CLAIMED"),
        Json.error(Refusal.conflict("claimed by another reviewer", "CLAIMED")));
    assertEquals(Json.map("error", "bad", "errors", List.of("bad")), Json.error(Refusal.invalid("bad")));
  }

  @Test
  void proofRecordsConform() {
    var decided = decide(claim(raise(control("pt-00001")), TYLER, T0), TYLER, Action.APPROVE, seconds(15));
    assertConforms("ProofRecord", Json.proof(Proofs.decision(decided, seconds(16))));
    var timedOut = tick(raise(control("pt-00001")), minutes(30));
    assertConforms("ProofRecord", Json.proof(Proofs.decision(timedOut, minutes(30))));

    var halt = decide(raise(control("pt-00012")), PATEL, Action.HALT, minutes(1));
    var exercised = Proofs.haltExercised(halt, minutes(1));
    assertConforms("ProofRecord", Json.proof(exercised));
    assertConforms("ProofRecord", Json.proof(Proofs.decision(halt, minutes(1))));
    var s = control("pt-00012");
    var lift = raise(s, request(s).subject(Subject.system("Restart", "capability", "eligibility-assessment",
        "all-runtime-operations", true)).build());
    var checks = Map.of("backlog drained", true);
    var first = ok(lift, Rules.decide(lift, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE), checks, minutes(2)));
    var lifted = ok(first, Rules.decide(first, actor(PATEL), Fx.decision(reviewer(PATEL), Action.APPROVE), checks,
        minutes(3)));
    assertConforms("ProofRecord", Json.proof(Proofs.haltLifted(lifted, exercised.recordId(), minutes(4))));
  }

  @Test
  void summaryAndQualityConform() {
    var events = events();
    var halts = List.<Map<String, Object>>of(Json.halt(HaltState.notHalted(control("pt-00012")), Map.of(), null, "patterns"));
    assertConforms("Summary", Figures.summary(events, Fx.CONTROLS, halts, List.of(), minutes(25)));
    assertConforms("Summary", Figures.summary(List.of(), Fx.CONTROLS, List.of(), List.of(), T0));
    assertConforms("Quality", Figures.quality(events, Fx.CONTROLS, s -> Fx.OVERSEERS.find(s).map(o -> o.name()).orElse(null),
        "P30D", minutes(25)));
    assertConforms("Quality", Figures.quality(List.of(), Fx.CONTROLS, s -> null, "P7D", T0));
  }
}
