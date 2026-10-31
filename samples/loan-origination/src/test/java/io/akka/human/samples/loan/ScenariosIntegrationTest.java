package io.akka.human.samples.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.engine.Rules;
import io.akka.human.engine.State;
import io.akka.human.samples.loan.application.ContestWorkflow;
import io.akka.human.samples.loan.application.DeadlineExtensionWorkflow;
import io.akka.human.samples.loan.application.EvalSetEntity;
import io.akka.human.samples.loan.application.NoticeWorkflow;
import io.akka.human.samples.loan.application.NoticeWritingEntity;
import io.akka.human.samples.loan.application.UnderwritingWorkflow;
import io.akka.human.samples.loan.domain.EligibilityAssessor;
import io.akka.human.samples.loan.domain.LoanApplication;
import io.akka.human.testkit.HumanTestKit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * S1, S5, S6, S7 and the domain reviews, each raised from the domain flow, decided through the
 * library as people would, and carried out by the service.
 */
public class ScenariosIntegrationTest extends LoanTestSupport {

  @Test
  public void anApplicationTheRuleApprovesNeedsNoPerson() {
    var id = apply(CLEAN);
    var approved = awaitApplication(id, a -> a.status().equals(LoanApplication.APPROVED));
    assertEquals(UnderwritingWorkflow.AUTOMATIC, approved.outcome().by());
    assertFalse(approved.assessment().referred());
  }

  @Test
  public void aReferredApplicationIsDecidedByAnUnderwriter() {
    var id = apply(STRETCHED);
    var eventId = awaitReview(id);
    var event = kit.event(eventId);
    assertEquals("decision-review", event.settings().pattern().id());
    assertEquals("standard-review", event.queue());
    assertEquals("DENY", event.aiRecommendation().get("action"));
    assertEquals(EligibilityAssessor.AGENT, event.aiRecommendation().get("agent"));
    assertEquals(403, kit.claim(eventId, OSEI).refusal().status());

    kit.claimAndDecide(eventId, MOREAU, Action.APPROVE, "Income verified; the instalment is affordable.");
    var approved = awaitApplication(id, a -> a.status().equals(LoanApplication.APPROVED));
    assertEquals(MOREAU.subject(), approved.outcome().by());
    assertEquals(eventId, approved.outcome().eventId());
  }

  @Test
  public void aDeclineIsNoticedAndAContestOverturnsIt() {
    var id = apply(BORDERLINE);
    kit.claimAndDecide(awaitReview(id), LIND, Action.DENY, "Debt-to-income is too high for the term asked.");
    var declined = awaitApplication(id, a -> a.notice() != null);
    assertEquals(LoanApplication.DECLINED, declined.status());
    assertEquals("DRAFT", declined.notice().issuedAs());
    assertTrue(declined.notice().text().contains("Debt-to-income is too high"), declined.notice().text());

    assertEquals(200, post("/applications/" + id + "/contest",
        Map.of("statement", "My car loan was repaid last month; the bank confirms it in writing.")).status());
    var appealId = Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forWorkflow(id).method(ContestWorkflow::get).invoke(),
            c -> c.status().equals("AWAITING_REVIEW")).eventId();
    var appeal = kit.event(appealId);
    assertEquals("contest-appeal", appeal.settings().pattern().id());
    assertTrue(appeal.independentOf().containsAll(List.of(LIND.subject(), TYLER.subject())), appeal.independentOf()::toString);
    assertEquals(403, kit.claim(appealId, LIND).refusal().status());

    kit.claimAndDecide(appealId, OKAFOR, Action.REVERSE, "The repaid loan brings debt-to-income within policy.");
    var overturned = awaitApplication(id, a -> a.status().equals(LoanApplication.APPROVED));
    assertEquals(OKAFOR.subject(), overturned.outcome().by());
    assertTrue(overturned.history().stream().anyMatch(s -> s.what().startsWith("DECLINED by " + LIND.subject())));
    assertEquals("OVERTURNED", componentClient.forWorkflow(id).method(ContestWorkflow::get).invoke().status());
  }

  @Test
  public void aNoticeNamingAProtectedCharacteristicIsHeldAndAmended() {
    var id = apply(BORDERLINE);
    kit.claimAndDecide(awaitReview(id), LIND, Action.DENY, "The applicant's health condition makes the term too long.");
    var held = Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forWorkflow(id).method(NoticeWorkflow::get).invoke(), n -> n.status().equals("HELD"));
    var event = kit.event(held.eventId());
    var shown = String.valueOf(event.subject().detail().get("output"));
    assertTrue(shown.contains("[withheld: a health term, en]"), shown);
    assertFalse(shown.contains("health condition"), shown);
    assertNotNull(awaitApplication(id, a -> a.noticeBlockedAt() != null).noticeBlockedAt());

    var corrected = "We cannot offer the loan: the repayments would take too much of your income. You may contest within 30 days.";
    var edits = Map.<String, Object>of("summary", "removed the withheld sentence",
        "patch", List.of(Map.of("op", "replace", "path", "/output", "value", corrected)));
    HumanTestKit.ok(kit.decide(held.eventId(), new Rules.DecisionRequest(CHEN, Action.EDIT, "Rewritten without the condition.",
        edits, null, null, List.of(), null)), "amend");
    var issued = awaitApplication(id, a -> a.notice() != null).notice();
    assertEquals("AMENDED", issued.issuedAs());
    assertEquals(corrected, issued.text());
  }

  @Test
  public void aLongDeadlineExtensionWaitsForTheSystemOwner() {
    var id = submit(BORDERLINE);
    var due = awaitApplication(id, a -> true).documentsDue();
    var shortTurn = post("/support/turns", Map.of("applicationId", id, "message", "Could I have 10 more days?")).body();
    assertTrue(shortTurn.get("extensionId").isNull());
    assertEquals(due.plus(Duration.ofDays(10)), awaitApplication(id, a -> true).documentsDue());

    var session = shortTurn.get("sessionId").asText();
    var turn = post("/support/turns", Map.of("sessionId", session, "applicationId", id,
        "message", "Could I have 28 more days? I am abroad until November.")).body();
    var extensionId = turn.get("extensionId").asText();
    var eventId = Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forWorkflow(extensionId).method(DeadlineExtensionWorkflow::get).invoke(),
            r -> r.status().equals("AWAITING_APPROVAL")).eventId();
    var event = kit.event(eventId);
    assertEquals("action-approval", event.settings().pattern().id());
    assertEquals("APPROVE", event.aiRecommendation().get("action"));
    assertEquals(List.of("agent:applicant-support"), event.independentOf());
    assertEquals(due.plus(Duration.ofDays(10)), awaitApplication(id, a -> true).documentsDue());

    HumanTestKit.ok(kit.claim(eventId, TYLER), "claim");
    kit.advance(Duration.ofSeconds(20));
    var edits = Map.<String, Object>of("summary", "21 days, to the end of November",
        "patch", List.of(Map.of("op", "replace", "path", "/arguments/days", "value", 21)));
    HumanTestKit.ok(kit.decide(eventId, new Rules.DecisionRequest(TYLER, Action.EDIT, "Three weeks covers the trip.", edits,
        null, null, List.of(), null)), "decide");
    var moved = awaitApplication(id, a -> a.documentsDue().equals(due.plus(Duration.ofDays(31))));
    assertEquals("APPLIED", moved.extensions().getLast().status());
    assertEquals(21, moved.extensions().getLast().daysApplied());

    var later = post("/support/turns", Map.of("sessionId", session, "applicationId", id,
        "message", "What happened to my extension request?")).body().get("reply").asText();
    assertTrue(later.contains("moved by 21 days"), later);
  }

  @Test
  public void aNoticeWriterModelChangeNeedsTwoApprovals() {
    var raised = post("/notice-writer/model-changes", Map.of("to", "notice-writer-2", "proposedBy", DEVOS.subject(),
        "builtBy", "ml-platform@example.com", "evaluation", "plain-language 0.81 to 0.88; no protected terms")).body();
    var eventId = raised.get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("change-authorization", event.settings().pattern().id());
    assertTrue(event.independentOf().containsAll(List.of(DEVOS.subject(), "ml-platform@example.com")));

    assertEquals(State.AWAITING_SECOND, kit.claimAndDecide(eventId, TYLER, Action.APPROVE, "The evaluation holds.").state());
    assertEquals("notice-writer-1", writing().model());
    kit.claimAndDecide(eventId, CHEN, Action.APPROVE, "No change to what applicants are told.");
    Awaitility.await().atMost(WAIT).until(() -> writing().model().equals("notice-writer-2"));
  }

  @Test
  public void aSuggestedTemplateIsUsedOnceCounselApprovesIt() {
    var text = "We could not offer this loan because of your credit history.";
    var eventId = post("/notice-writer/templates", Map.of("reason", "ADVERSE_CREDIT_HISTORY", "text", text)).body()
        .get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("change-authorization", event.settings().pattern().id());
    assertEquals(List.of("agent:notice-writer"), event.independentOf());
    HumanTestKit.ok(kit.decide(eventId, OKAFOR, Action.APPROVE, "Plainer, and says the same."), "approve");
    Awaitility.await().atMost(WAIT).until(() -> writing().template("ADVERSE_CREDIT_HISTORY").equals(text));

    var withDefault = new java.util.HashMap<>(BORDERLINE);
    withDefault.put("nationalId", "DEF-7");
    withDefault.put("monthlyDebts", 300);
    var id = apply(withDefault);
    kit.claimAndDecide(awaitReview(id), LIND, Action.DENY, "The recorded default is recent.");
    var notice = awaitApplication(id, a -> a.notice() != null).notice();
    assertTrue(notice.text().startsWith(text), notice.text());
  }

  @Test
  public void aHaltSendsEveryApplicationToAnUnderwriterUntilARestart() {
    drainQueue();
    var halt = post("/oversight/halts", Map.of("reviewer", PATEL, "control", "hl-0006", "lift", false,
        "reason", "The referral share drifted."));
    assertEquals(200, halt.status(), halt.body()::toString);

    var id = apply(CLEAN);
    awaitReview(id);
    var referred = awaitApplication(id, a -> a.assessment() != null);
    assertEquals(UnderwritingWorkflow.HALTED, referred.assessment().referredBecause());
    assertNull(referred.assessment().assessedAt());
    Awaitility.await().atMost(WAIT).until(() -> get("/underwriting/queue").body().get("entries").toString().contains(id));

    var lift = post("/oversight/halts", Map.of("reviewer", MOREAU, "control", "hl-0006", "lift", true,
        "reason", "The model was recalibrated."));
    assertEquals(200, lift.status(), lift.body()::toString);
    var liftId = lift.body().get("id").asText();
    assertEquals(409, post("/oversight/events/" + liftId + "/decision", decision(CHEN, "APPROVE", "ok")).status());

    drainQueue();
    Awaitility.await().atMost(WAIT).until(this::safeToRestart);
    var first = post("/oversight/events/" + liftId + "/decision", decision(CHEN, "APPROVE", "The queue is back to size."));
    assertEquals(200, first.status(), first.body()::toString);
    assertEquals("AWAITING_SECOND", first.body().get("state").asText());
    var second = post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "Agreed."));
    assertEquals(200, second.status(), second.body()::toString);
    assertEquals("DECIDED", second.body().get("state").asText());
    Awaitility.await().atMost(WAIT).until(() -> !kit.human().halted(EligibilityAssessor.CAPABILITY).toCompletableFuture().join());

    var after = apply(CLEAN);
    assertEquals(UnderwritingWorkflow.AUTOMATIC, awaitApplication(after, a -> a.outcome() != null).outcome().by());
  }

  @Test
  public void aFlaggedReplyBecomesAnEvalCase() {
    var id = submit(BORDERLINE);
    var turn = post("/support/turns", Map.of("applicationId", id, "message", "Which documents do you still need?")).body();
    var session = turn.get("sessionId").asText();
    var reply = turn.get("reply").asText();
    var flag = post("/support/sessions/" + session + "/flag", Map.of("by", PATEL.subject(),
        "note", "It should say what each document is for."));
    assertEquals(200, flag.status(), flag.body()::toString);
    var eventId = flag.body().get("eventId").asText();
    var event = kit.event(eventId);
    assertEquals("flag-feedback", event.settings().pattern().id());
    assertEquals(reply, event.subject().detail().get("output"));

    HumanTestKit.ok(kit.decide(eventId, TYLER, Action.APPROVE, "A fair case for the eval set."), "accept");
    var accepted = Awaitility.await().atMost(WAIT).until(
        () -> componentClient.forKeyValueEntity(EvalSetEntity.ID).method(EvalSetEntity::get).invoke().cases().stream()
            .filter(c -> c.eventId().equals(eventId)).findFirst().orElse(null), java.util.Objects::nonNull);
    assertEquals(reply, accepted.reply());
    assertEquals(session, accepted.sessionId());
  }

  // ---- helpers

  private NoticeWritingEntity.Writing writing() {
    return componentClient.forKeyValueEntity(NoticeWritingEntity.ID).method(NoticeWritingEntity::get).invoke();
  }

  /** Whether every safe-state check of the eligibility halt passes, as GET /oversight/halts shows them. */
  private boolean safeToRestart() {
    for (var halt : get("/oversight/halts").body().get("halts")) {
      if (!halt.get("target").asText().equals(EligibilityAssessor.CAPABILITY)) continue;
      for (var check : halt.get("safeStateChecks")) if (!check.get("passed").asBoolean()) return false;
      return true;
    }
    return false;
  }

  /** Approves every application waiting for an underwriter, so the halt's backlog check passes. */
  private void drainQueue() {
    for (var row : get("/underwriting/queue").body().get("entries")) {
      var eventId = row.get("reviewEventId").asText();
      var id = row.get("id").asText();
      kit.claimAndDecide(eventId, LIND, Action.APPROVE, "Cleared from the queue.");
      awaitApplication(id, a -> a.status().equals(LoanApplication.APPROVED));
    }
    Awaitility.await().atMost(WAIT).until(() -> get("/underwriting/queue").body().get("entries").isEmpty());
  }
}
