package io.akka.human.engine;

import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.at;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One test per row of the contract's "Settling and verdicts" table, the verdict's source, and the
 * verdict as Verify's verdict schema records it.
 */
class VerdictsTest {

  private static void assertVerdict(HumanEvent e, String slaOutcome, String conclusion, List<String> reasons) {
    assertEquals(slaOutcome, e.outcome().slaOutcome());
    var verdict = Verdicts.of(e);
    assertEquals(conclusion, verdict.conclusion());
    assertEquals(reasons, verdict.reasons());
  }

  @Test
  void aPendingEventHasNoVerdict() {
    assertNull(Verdicts.of(raise(control("pt-00001"))));
  }

  @Test
  void decidedInTimeIsUpheld() {
    var e = decide(claim(raise(control("pt-00001")), TYLER, T0), TYLER, Action.DENY, seconds(15));
    assertVerdict(e, "COMPLETED", "upheld", List.of());
    var v = Verdicts.of(e);
    assertEquals("vd-" + e.id(), v.id());
    assertEquals(e.id(), v.eventId());
    assertEquals(seconds(15), v.at());

    var doc = Json.verdict(e);
    assertEquals(1, doc.get("schemaVersion"));
    assertEquals("vd-" + e.id(), doc.get("verdictId"));
    assertEquals(Map.of("rowId", "pt-00001", "corpusRef", "aws-agentic-scoping.scope2-human-approval",
        "bundle", Map.of("systemId", "patterns", "draftRevision", 1)), doc.get("control"));
    assertEquals("pre-production", doc.get("environment"));
    assertEquals(seconds(15).toString(), doc.get("observedAt"));
    assertEquals("upheld", doc.get("conclusion"));
    assertEquals("deny", doc.get("outcome"));
    assertEquals(Map.of("kind", "runtime", "componentId", "patterns"), doc.get("source"));
    var detail = detail(doc);
    assertEquals("decided", detail.get("status"));
    assertEquals("action-approval", detail.get("pattern"));
    assertEquals("hitl", detail.get("mode"));
    assertEquals(Map.of("id", e.id(), "queue", "system-owners"), detail.get("workItem"));
    assertEquals(true, detail.get("authorized"));
    assertEquals(TYLER, ((Map<?, ?>) detail.get("reviewer")).get("subject"));
    assertEquals("deny", detail.get("action"));
    assertEquals(Map.of("target", "PT30M", "outcome", "met"), detail.get("sla"));
    assertNull(detail.get("votes"));
  }

  private static Map<?, ?> detail(Map<String, Object> verdict) {
    return (Map<?, ?>) verdict.get("detail");
  }

  /** The example's digest was computed by Verify's own tool (tools/verdicts/seal.py). */
  @Test
  void theDigestIsVerifysDigest() throws IOException {
    try (InputStream in = VerdictsTest.class.getClassLoader().getResourceAsStream("spec/examples/Verdict.upheld.json")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> example = new ObjectMapper().readValue(in, Map.class);
      var integrity = (Map<?, ?>) example.remove("integrity");
      assertEquals(integrity.get("digest"), Canonical.digest(example));
    }
  }

  @Test
  void canonicalJsonSortsNamesAndEscapesOnlyWhatJsonRequires() {
    var value = new LinkedHashMap<String, Object>();
    value.put("b", Arrays.asList(1, true, null, 3L));
    value.put("a", "é\n\u0001\"");
    value.put("A", Map.of("z", 1));
    assertEquals("{\"A\":{\"z\":1},\"a\":\"é\\n\\u0001\\\"\",\"b\":[1,true,null,3]}", Canonical.of(value));
  }

  @Test
  void theDigestCoversEveryOtherField() {
    var doc = Json.verdict(decide(claim(raise(control("pt-00001")), TYLER, T0), TYLER, Action.APPROVE, minutes(1)));
    var body = new LinkedHashMap<>(doc);
    var integrity = (Map<?, ?>) body.remove("integrity");
    assertEquals(Canonical.digest(body), integrity.get("digest"));
    body.put("conclusion", "breached");
    assertNotEquals(Canonical.digest(body), integrity.get("digest"));
  }

  @Test
  void decidedAfterTheDeadlineIsBreachedLate() {
    var e = decide(claim(raise(control("pt-00001")), TYLER, minutes(29)), TYLER, Action.APPROVE, minutes(31));
    assertVerdict(e, "COMPLETED", "breached", List.of("late"));
  }

  @Test
  void decidedUnderTheMinimumWithWarnIsBreachedBelowMinimum() {
    var s = Fx.custom("hand-off", "human-domain-decision", "conversation",
        "routing", Map.of("roles", List.of("duty-officer")), "minimum_review_seconds", 30);
    var e = decide(claim(raise(s), Fx.MOREAU, T0), Fx.MOREAU, Action.APPROVE, seconds(3));
    assertVerdict(e, "COMPLETED", "breached", List.of("below-minimum"));
  }

  @Test
  void aDefaultThatLetsTheSubjectProceedFailsOpen() {
    var s = Fx.custom("exception", "human-system-authorization", "exception",
        "routing", Map.of("roles", List.of("compliance-lead")), "sla_target", "P2D", "timeout_default", "APPROVE");
    var e = tick(raise(s), at(Duration.ofDays(2)));
    assertVerdict(e, "TIMED_OUT", "breached", List.of("fail-open"));
    var doc = Json.verdict(e);
    assertEquals("timed-out", doc.get("outcome"));
    assertEquals(Map.of("behaviour", "fail-open", "defaultApplied", "approve"), detail(doc).get("timeout"));
    assertEquals(Map.of("code", "fail-open", "text", "The review timed out and the default let the subject go ahead."),
        doc.get("reason"));
  }

  @Test
  void aDenyingDefaultOrARaisedTimeoutIsInconclusive() {
    assertVerdict(tick(raise(control("pt-00004")), at(Duration.ofDays(2))), "TIMED_OUT", "inconclusive", List.of());
    assertVerdict(tick(raise(control("pt-00013")), at(Duration.ofDays(14))), "TIMED_OUT", "inconclusive", List.of());
  }

  @Test
  void escalateAtTheLastQueueIsAMissedDeadline() {
    var e = tick(tick(raise(control("pt-00010")), at(Duration.ofHours(8))), at(Duration.ofHours(16)));
    assertEquals(State.TIMED_OUT, e.state());
    assertVerdict(e, "TIMED_OUT", "breached", List.of("missed-deadline"));
    assertEquals(Map.of("behaviour", "escalate", "exhausted", true), detail(Json.verdict(e)).get("timeout"));
  }

  @Test
  void abandonedAndWithdrawnAreInconclusive() {
    assertVerdict(tick(raise(control("pt-00009")), at(Duration.ofDays(3))), "ABANDONED", "inconclusive", List.of());
    var s = control("pt-00005");
    var e = raise(s, request(s).proposedBy("agent:matcher").build());
    var withdrawn = ok(e, Rules.withdraw(e, Reviewer.of("agent:matcher"), "", minutes(1)));
    assertVerdict(withdrawn, "ABANDONED", "inconclusive", List.of());
  }

  @Test
  void killedOrNeverAppliedIsAnError() {
    var killed = ok(raise(control("pt-00006")), Rules.kill(raise(control("pt-00006")), "dev", "", minutes(1)));
    assertVerdict(killed, "KILLED", "error", List.of("killed"));
    var decided = decide(raise(control("pt-00006")), TYLER, Action.APPROVE, minutes(1));
    var failed = ok(decided, Rules.applyFailed(decided, "the payment service is gone", minutes(2)));
    assertVerdict(failed, "COMPLETED", "error", List.of("not-applied"));
  }

  @Test
  void theSourceIsRuntimeRehearsalOrSimulated() {
    var runtime = decide(raise(control("pt-00006")), TYLER, Action.APPROVE, minutes(1));
    assertEquals("runtime", Verdicts.of(runtime).source());

    var e = raise(control("pt-00006"));
    var rehearsing = new Reviewer(TYLER, "Tyler Jewell", Fx.overseer(TYLER).roles(), "human-companion", true);
    var rehearsed = ok(e, Rules.decide(e, new Qualification.Actor(rehearsing, Fx.overseer(TYLER), true),
        Fx.decision(rehearsing, Action.APPROVE), Map.of(), minutes(1)));
    assertEquals(State.DECIDED, rehearsed.state());
    assertEquals("rehearsal", Verdicts.of(rehearsed).source());

    var s = control("pt-00006");
    var simulated = decide(raise(s, request(s).simulated(true).build()), TYLER, Action.APPROVE, minutes(1));
    assertEquals("simulated", Verdicts.of(simulated).source());

    var timedOut = tick(raise(control("pt-00001")), minutes(30));
    assertEquals("runtime", Verdicts.source(timedOut));

    assertEquals(Map.of("kind", "rehearsal", "by", TYLER), Json.verdict(rehearsed).get("source"));
    assertEquals(Map.of("kind", "simulated", "generator", "patterns"), Json.verdict(simulated).get("source"));
  }

  @Test
  void aStandingDecisionUpholds() {
    var e = raise(control("pt-00001"));
    var settled = ok(e, Rules.settleByStanding(e, Fx.grant("sd-9"), Action.APPROVE, T0));
    assertVerdict(settled, "COMPLETED", "upheld", List.of());
    var detail = detail(Json.verdict(settled));
    assertEquals(Map.of("decisionId", "sd-9", "mode", "approve-for-pattern", "grantedBy", TYLER,
        "grantedAt", T0.minus(Duration.ofDays(1)).toString()), detail.get("standing"));
    assertEquals(true, detail.get("authorized"));
    assertNull(detail.get("reviewer"));
  }
}
