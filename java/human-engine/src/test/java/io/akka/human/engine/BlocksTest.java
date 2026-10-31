package io.akka.human.engine;

import static io.akka.human.engine.Fx.CATALOG;
import static io.akka.human.engine.Fx.CONTEXT;
import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.PATEL;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.actor;
import static io.akka.human.engine.Fx.at;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.custom;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.decision;
import static io.akka.human.engine.Fx.humanControl;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.refused;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Subject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An oversight-quality event blocks settling decisions on the control it judges until it is settled. */
class BlocksTest {

  private static final Duration P2D = Duration.ofDays(2);

  /** pt-0014's finding against pt-0001, raised at {@code at}. */
  private static HumanEvent finding(ControlSettings quality, Instant at) {
    return raise(quality, request(quality).subject(Subject.behaviour("below-minimum-share on pt-0001 is 0.4", "below-minimum-share",
        0.4, 0.2, ">").withRef("pt-0001")).build(), at);
  }

  private static HumanEvent finding() {
    return finding(control("pt-0014"), T0);
  }

  private static HumanEvent payment() {
    return raise(control("pt-0001"));
  }

  private static Map<String, String> blocks(HumanEvent... events) {
    return Blocks.of(List.of(events));
  }

  @Test
  void theSettingIsOnForOversightQualityAndOffElsewhere() {
    assertTrue(control("pt-0014").blockDecisions());
    assertFalse(control("pt-0001").blockDecisions());
    assertFalse(custom("oversight-quality", "human-monitoring", "behaviour", "routing",
        Map.of("roles", List.of("ai-system-owner")), "block_decisions", false).blockDecisions());

    var elsewhere = humanControl("x-block", "action-approval", "human-system-authorization", "action", "routing",
        Map.of("roles", List.of("ai-system-owner")), "block_decisions", true);
    assertEquals(List.of("x-block: block_decisions is true or false, on an oversight-quality control"),
        Settings.problems(elsewhere));
    var notBoolean = humanControl("x-yes", "oversight-quality", "human-monitoring", "behaviour", "routing",
        Map.of("roles", List.of("ai-system-owner")), "block_decisions", "yes");
    assertEquals(List.of("x-yes: block_decisions is true or false, on an oversight-quality control"),
        Settings.problems(notBoolean));
  }

  @Test
  void anOpenFindingBlocksThePendingEventsOfTheControlItJudges() {
    var quality = finding();
    var pending = payment();
    var blocks = blocks(quality, pending);
    assertEquals(Map.of("pt-0001", quality.id()), blocks);
    assertEquals(quality.id(), Blocks.blocking(pending, blocks));
    assertNull(Blocks.blocking(quality, blocks), "the finding itself is not blocked");
    assertNull(Blocks.blocking(raise(control("pt-0002")), blocks));

    var closed = ok(pending, Rules.kill(pending, "system", "gone", seconds(5)));
    assertNull(Blocks.blocking(closed, blocks), "a closed event is never blocked");
  }

  @Test
  void resetRetrainAndReassignLiftTheBlock() {
    for (var action : List.of(Action.APPROVE, Action.REPLAN, Action.REDIRECT)) {
      var settled = decide(finding(), TYLER, action, seconds(30));
      assertEquals(State.DECIDED, settled.state());
      assertEquals(Map.of(), blocks(settled), action + " lifts it");
    }
  }

  @Test
  void aTimeoutKeepsTheBlock() {
    var timedOut = tick(finding(), at(P2D));
    assertEquals(State.TIMED_OUT, timedOut.state(), "pt-0014 has one queue, so its deadline closes the finding");
    assertEquals(Map.of("pt-0001", timedOut.id()), blocks(timedOut));

    var chained = custom("oversight-quality", "human-monitoring", "behaviour", "routing",
        Map.of("roles", List.of("ai-system-owner")), "sla_target", "P2D", "escalation_chain", List.of(
            Map.of("queue", "system-owners", "roles", List.of("ai-system-owner")),
            Map.of("queue", "head-of-operations", "roles", List.of("head-of-operations"), "after", "P1D")));
    var escalated = tick(finding(chained, T0), at(P2D));
    assertEquals(State.ESCALATED, escalated.state());
    assertEquals(Map.of("pt-0001", escalated.id()), blocks(escalated));
  }

  @Test
  void aKilledOrWithdrawnFindingReleasesTheBlock() {
    var quality = finding();
    assertEquals(Map.of(), blocks(ok(quality, Rules.kill(quality, "system:dev-reset", "reset", seconds(5)))));
    var admin = new io.akka.human.api.Reviewer(PATEL, PATEL, List.of("admin"), "test", false);
    assertEquals(Map.of(), blocks(ok(quality, Rules.withdraw(quality, admin, "raised in error", seconds(5)))));
  }

  @Test
  void theLatestFindingAgainstTheControlDecides() {
    var old = tick(finding(), at(P2D));
    var latest = finding(control("pt-0014"), at(P2D.plusHours(1)));
    assertEquals(Map.of("pt-0001", latest.id()), blocks(old, latest));
    var reset = decide(latest, TYLER, Action.APPROVE, at(P2D.plusHours(2)));
    assertEquals(Map.of(), blocks(old, reset), "settling the latest finding lifts the block the timed-out one held");
  }

  @Test
  void onlyARoutedFindingWithTheSettingOnBlocks() {
    var off = custom("oversight-quality", "human-monitoring", "behaviour", "routing",
        Map.of("roles", List.of("ai-system-owner")), "block_decisions", false);
    assertEquals(Map.of(), blocks(finding(off, T0)));

    var preProduction = new Raise.Context("patterns", "pre-production", CATALOG.digest(), CONTEXT.reportsTo());
    var s = control("pt-0014");
    var warned = Raise.event("he-warn", s, request(s).subject(Subject.behaviour("share", "below-minimum-share", 0.4, 0.2,
        ">").withRef("pt-0001")).build(), preProduction, T0);
    assertEquals("warn", warned.strength());
    assertEquals(Map.of(), blocks(warned), "a finding of strength warn does not block");

    var unreferenced = raise(s);
    assertNull(unreferenced.subject().ref());
    assertEquals(Map.of(), blocks(unreferenced), "a finding that names no control judges none");
  }

  @Test
  void aBlockedEventRefusesSettlingDecisionsAndTakesTheRest() {
    var quality = finding();
    var e = claim(payment(), TYLER, T0);
    var refusal = refused(Rules.decide(e, actor(TYLER), decision(reviewer(TYLER), Action.APPROVE), Map.of(),
        quality.id(), seconds(15)), 409);
    assertEquals(quality.id(), refusal.blockedBy());
    assertEquals("CLAIMED", refusal.state());
    assertTrue(refusal.error().contains(quality.id()), refusal.error());
    refused(Rules.decide(e, actor(TYLER), decision(reviewer(TYLER), Action.DENY), Map.of(), quality.id(), seconds(15)),
        409);

    var asked = ok(e, Rules.decide(e, actor(TYLER), decision(reviewer(TYLER), Action.REQUEST_MORE_INFO), Map.of(),
        quality.id(), seconds(15)));
    assertEquals(1, asked.infoRequests().size());
    var escalated = ok(e, Rules.decide(e, actor(TYLER), decision(reviewer(TYLER), Action.ESCALATE), Map.of(),
        quality.id(), seconds(15)));
    assertEquals(State.ESCALATED, escalated.state());
    var recused = ok(e, Rules.decide(e, actor(TYLER), decision(reviewer(TYLER), Action.RECUSE), Map.of(),
        quality.id(), seconds(15)));
    assertEquals(List.of(TYLER), recused.recused());

    var change = control("pt-0003");
    var proposed = claim(raise(change, request(change).proposedBy(LIND).build()), Fx.DEVOS, T0);
    var sentBack = ok(proposed, Rules.decide(proposed, actor(Fx.DEVOS), decision(reviewer(Fx.DEVOS), Action.SEND_BACK),
        Map.of(), quality.id(), seconds(15)));
    assertEquals(State.CHANGES_REQUESTED, sentBack.state());
  }

  @Test
  void exercisingAHaltIsNeverBlocked() {
    var halt = raise(control("pt-0012"));
    var halted = ok(halt, Rules.decide(halt, actor(PATEL), decision(reviewer(PATEL), Action.HALT), Map.of(), "he-quality",
        seconds(1)));
    assertEquals(Action.HALT, halted.outcome().action());
  }

  @Test
  void theContractCarriesTheBlock() {
    var quality = finding();
    var e = payment();
    var json = Json.event(e, T0, quality.id());
    assertEquals(quality.id(), json.get("blockedBy"));
    SchemaTest.assertConforms("HumanEvent", json);
    assertNull(Json.event(e, T0).get("blockedBy"));

    var error = Json.error(Refusal.blocked("pt-0001 is blocked", "OPEN", quality.id()));
    assertEquals(quality.id(), error.get("blockedBy"));
    SchemaTest.assertConforms("Error", error);

    var settings = Json.controlSettings(control("pt-0014"));
    assertEquals(true, settings.get("blockDecisions"));
    SchemaTest.assertConforms("ControlSettings", settings);
  }
}
