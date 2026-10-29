package io.akka.human.engine;

import static io.akka.human.engine.Fx.DEVOS;
import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.OKAFOR;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.actor;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.refused;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VotesTest {

  private static HumanEvent change() {
    var s = control("pt-0003");
    return raise(s, request(s).proposedBy("agent:rollouts").builtBy("trainer").build());
  }

  @Test
  void twoPersonWaitsForASecondIndependentVote() {
    var first = decide(change(), TYLER, Action.APPROVE, minutes(1));
    assertEquals(State.AWAITING_SECOND, first.state());
    assertTrue(first.pending());
    assertEquals(1, first.roundVotes().size());
    assertEquals("APPROVE, 1 of 2", first.history().getLast().note());
    assertEquals(State.AWAITING_SECOND, first.openState());

    var again = refused(Rules.decide(first, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE), Map.of(),
        minutes(2)), 409);
    assertEquals("you have already voted in this round", again.error());
    refused(Rules.claim(first, actor(TYLER), minutes(2)), 409);

    var second = new Rules.DecisionRequest(reviewer(DEVOS), Action.APPROVE, "Eval holds", null, null, seconds(30),
        List.of(), null);
    var decided = ok(first, Rules.decide(first, actor(DEVOS), second, Map.of(), minutes(2)));
    assertEquals(State.DECIDED, decided.state());
    assertEquals(90, decided.decisions().getLast().reviewSeconds());
    assertEquals("APPROVE, 2 of 2", decided.history().getLast().note());
    assertEquals(List.of(TYLER, DEVOS), decided.deciders());
    assertEquals(Verdicts.UPHELD, Verdicts.of(decided).conclusion());
  }

  @Test
  void aVoteOtherThanApproveSettlesUnderTheDefaultSplitRule() {
    var denied = decide(change(), TYLER, Action.DENY, minutes(1));
    assertEquals(State.DECIDED, denied.state());
    assertEquals(Action.DENY, denied.outcome().action());

    var split = decide(decide(change(), TYLER, Action.APPROVE, minutes(1)), DEVOS, Action.DENY, minutes(2));
    assertEquals(State.DECIDED, split.state());
    assertEquals(Action.DENY, split.outcome().action());
    assertEquals("Split vote; settled DENY", split.history().getLast().note());
  }

  @Test
  void aSplitVoteSettlesDenyEvenWhenTheDissentApproves() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer"), "review_mode", "TWO_PERSON"));
    var review = raise(s);
    var editVote = new Rules.DecisionRequest(reviewer(LIND), Action.EDIT, "Amend the wording",
        Map.of("summary", "reworded"), null, null, List.of(), null);
    var afterEdit = ok(review, Rules.decide(review, actor(LIND), editVote, Map.of(), minutes(1)));
    assertEquals(State.AWAITING_SECOND, afterEdit.state());
    var split = decide(afterEdit, OKAFOR, Action.APPROVE, minutes(2));
    assertEquals(Action.DENY, split.outcome().action());
    assertEquals("Split vote; settled DENY", split.history().getLast().note());
    assertEquals(null, split.outcome().edits());
  }

  @Test
  void nOfMSettlesOnTheRequiredAgreeingVotes() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer"), "review_mode", "N_OF_M", "required", 2, "of", 3));
    var first = decide(raise(s), LIND, Action.APPROVE, minutes(1));
    assertEquals(State.AWAITING_SECOND, first.state());
    var decided = decide(first, OKAFOR, Action.APPROVE, minutes(2));
    assertEquals(State.DECIDED, decided.state());
    assertEquals(Action.APPROVE, decided.outcome().action());
    assertEquals(3, s.routing().of());
    assertEquals(2, s.routing().required());
  }

  @Test
  void aSplitVoteEscalatesWhenTheControlSaysSoAndSettlesDenyAtTheLastQueue() {
    var s = Fx.custom("change-authorization", "human-system-authorization", "change",
        "routing", Map.of("roles", List.of("model-risk-owner"), "review_mode", "TWO_PERSON", "split_vote", "ESCALATE"),
        "sla_target", "P1D",
        "escalation_chain", List.of(Map.of("queue", "board", "roles", List.of("model-risk-owner")),
            Map.of("queue", "heads", "roles", List.of("head-of-operations"), "after", "P1D")));
    var split = decide(decide(raise(s), TYLER, Action.APPROVE, minutes(1)), DEVOS, Action.DENY, minutes(2));
    assertEquals(State.ESCALATED, split.state());
    assertEquals(1, split.level());
    assertEquals("heads", split.queue());
    assertEquals(List.of("head-of-operations"), split.currentRoles());
    assertEquals(2, split.decisions().size());
    assertEquals(List.of(), split.roundVotes());
    assertEquals(minutes(2).plus(Duration.ofDays(1)), split.dueAt());
    assertEquals("Escalated to heads: split vote", split.history().getLast().note());
    refused(Rules.claim(split, actor(DEVOS), minutes(3)), 403);

    var first = decide(split, TYLER, Action.APPROVE, minutes(3));
    assertEquals(State.AWAITING_SECOND, first.state());
    var decided = decide(first, LIND, Action.APPROVE, minutes(4));
    assertEquals(State.DECIDED, decided.state());
    assertEquals(List.of(TYLER, LIND), decided.deciders());

    var lastSplit = decide(first, LIND, Action.DENY, minutes(4));
    assertEquals(State.DECIDED, lastSplit.state());
    assertEquals(Action.DENY, lastSplit.outcome().action());
  }

  @Test
  void consensusStartsANewRoundWhenTheVotesDisagree() {
    var s = Fx.custom("change-authorization", "human-system-authorization", "change",
        "routing", Map.of("roles", List.of("model-risk-owner"), "review_mode", "CONSENSUS", "required", 2));
    var first = decide(raise(s), TYLER, Action.APPROVE, minutes(1));
    assertEquals(State.AWAITING_SECOND, first.state());
    var disagree = decide(first, DEVOS, Action.DENY, minutes(2));
    assertEquals(State.IN_REVIEW, disagree.state());
    assertTrue(disagree.pending());
    assertEquals(2, disagree.roundStart());
    assertEquals(List.of(), disagree.roundVotes());

    var again = decide(decide(disagree, TYLER, Action.APPROVE, minutes(3)), DEVOS, Action.APPROVE, minutes(4));
    assertEquals(State.DECIDED, again.state());
    assertEquals(4, again.decisions().size());
    assertEquals(List.of(TYLER, DEVOS), again.deciders());

    var bothDeny = decide(decide(raise(s), TYLER, Action.DENY, minutes(1)), DEVOS, Action.DENY, minutes(2));
    assertEquals(Action.DENY, bothDeny.outcome().action());
  }

  @Test
  void aClaimDuringASecondVoteHoldsTheEventForThatVoter() {
    var first = decide(change(), TYLER, Action.APPROVE, minutes(1));
    var held = Fx.claim(first, DEVOS, minutes(2));
    assertEquals(State.CLAIMED, held.state());
    var released = ok(held, Rules.release(held, reviewer(DEVOS), minutes(3)));
    assertEquals(State.AWAITING_SECOND, released.state());
    assertEquals(State.AWAITING_SECOND, held.stateAt(minutes(32)));
    var decided = decide(held, DEVOS, Action.APPROVE, minutes(4));
    assertEquals(120, decided.decisions().getLast().reviewSeconds());
    assertEquals(T0.plus(Duration.ofMinutes(2)), decided.decisions().getLast().presentedAt());
  }
}
