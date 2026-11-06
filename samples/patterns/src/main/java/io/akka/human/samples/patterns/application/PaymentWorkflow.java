package io.akka.human.samples.patterns.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A payment an agent asks to make. Above 1,000 it waits for action approval (pt-00001); a payment whose
 * result comes back unknown waits for a person to reconcile it (pt-00006), and nothing retries it on
 * its own. Each wait is a pause with a backstop timeout past the control's last escalation, so the
 * workflow hears the outcome from the library exactly once.
 */
@Component(id = "payment")
public class PaymentWorkflow extends Workflow<PaymentWorkflow.Payment> {

  public static final String RESUMER = "payment";
  static final int LIMIT = 1000;
  /** pt-00001 has 30 minutes and an escalation queue of 30 more; pt-00006 has 2 hours and 2 more. */
  static final Duration APPROVAL_BACKSTOP = Duration.ofHours(2);
  static final Duration RECONCILIATION_BACKSTOP = Duration.ofHours(6);

  /** {@code resultUnknown}: the payment provider will not say whether the first attempt went through. */
  public record Start(String supplier, int amount, String currency, boolean resultUnknown) {}

  /**
   * @param status CHECKING, AWAITING_APPROVAL, AWAITING_RECONCILIATION, PAID, NOT_PAID, DENIED or NO_ANSWER
   * @param attempts payment attempts sent
   */
  public record Payment(
      String id,
      String supplier,
      int amount,
      String currency,
      boolean resultUnknown,
      String status,
      String approvalEventId,
      String reconciliationEventId,
      int attempts,
      String note) {

    Payment with(String nextStatus, String nextNote) {
      return new Payment(id, supplier, amount, currency, resultUnknown, nextStatus, approvalEventId,
          reconciliationEventId, attempts, nextNote);
    }
  }

  private final Human human;
  private final String id;

  public PaymentWorkflow(Human human, WorkflowContext context) {
    this.human = human;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    return effects()
        .updateState(new Payment(id, start.supplier(), start.amount(), start.currency(), start.resultUnknown(),
            "CHECKING", null, null, 0, ""))
        .transitionTo(PaymentWorkflow::check)
        .thenReply(Done.done());
  }

  private StepEffect check() {
    return currentState().amount() > LIMIT
        ? stepEffects().thenTransitionTo(PaymentWorkflow::askApproval)
        : stepEffects().thenTransitionTo(PaymentWorkflow::pay);
  }

  private StepEffect askApproval() {
    var p = currentState();
    var raised = human.raise(Patterns.actionApproval(
            "Should the agent pay supplier " + p.supplier() + " " + money(p) + "?")
        .control("pt-00001")
        .subject(Subject.action("Pay " + p.supplier() + " " + money(p), "pay",
                Map.of("supplier", p.supplier(), "amount", p.amount(), "currency", p.currency()))
            .with("authority", Map.of("missing", List.of("amount > " + LIMIT), "required", Map.of("role", "ai-system-owner")))
            .with("recentSteps", List.of())
            .withAgent("payments").withWorkflow(id).withRef(id))
        .check("The invoice matches the purchase order.", "subject")
        .trigger(new Trigger("tool-policy", "pay.amount > " + LIMIT, "The agent asked to pay " + money(p),
            List.of(new Trigger.Value("amount", p.amount(), LIMIT)), List.of(new Trigger.Condition("amount > " + LIMIT, true))))
        .proposedBy("agent:payments")
        .dedupeKey("approve-" + id)
        .matchKey("pay:" + p.supplier())
        .effect(Action.APPROVE, "The payment is sent.")
        .effect(Action.DENY, "The payment is cancelled.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    var next = new Payment(p.id(), p.supplier(), p.amount(), p.currency(), p.resultUnknown(), "AWAITING_APPROVAL",
        raised.eventId(), p.reconciliationEventId(), p.attempts(), "");
    return stepEffects().updateState(next)
        .thenPause(pauseSetting(APPROVAL_BACKSTOP).timeoutHandler(PaymentWorkflow::noAnswer));
  }

  /** Sends the payment; an unknown result waits for a person to say what happened. */
  private StepEffect pay() {
    var p = currentState();
    int attempt = p.attempts() + 1;
    if (!p.resultUnknown()) {
      return stepEffects().updateState(new Payment(p.id(), p.supplier(), p.amount(), p.currency(), false, "PAID",
          p.approvalEventId(), p.reconciliationEventId(), attempt, "sent")).thenEnd();
    }
    var raised = human.raise(Patterns.outcomeReconciliation(
            "Did the payment of " + money(p) + " to " + p.supplier() + " go through?")
        .control("pt-00006")
        .subject(Subject.action("Unknown result: pay " + p.supplier() + " " + money(p), "pay",
                Map.of("supplier", p.supplier(), "amount", p.amount(), "currency", p.currency()))
            .with("sentAt", Instant.now().toString())
            .with("requestedBy", "agent:payments")
            .with("lastKnown", "The provider timed out after accepting the request.")
            .withAgent("payments").withWorkflow(id).withRef(id))
        .trigger(Trigger.rule("workflow", "payment.result == unknown", "The provider did not confirm the payment."))
        .proposedBy("agent:payments")
        .dedupeKey("reconcile-" + id + "-" + attempt)
        .effect(Action.REPLAN, "The payment is sent again.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    var next = new Payment(p.id(), p.supplier(), p.amount(), p.currency(), true, "AWAITING_RECONCILIATION",
        p.approvalEventId(), raised.eventId(), attempt, "result unknown");
    return stepEffects().updateState(next)
        .thenPause(pauseSetting(RECONCILIATION_BACKSTOP).timeoutHandler(PaymentWorkflow::noAnswer));
  }

  /** The library's one call with the final outcome; a repeated call finds the workflow moved on. */
  public Effect<Done> decided(Decision decision) {
    var p = currentState();
    if (p == null) return effects().reply(Done.done());
    if (p.status().equals("AWAITING_APPROVAL") && decision.eventId().equals(p.approvalEventId())) {
      if (!decision.proceeds()) {
        return effects().updateState(p.with("DENIED", why(decision))).end().thenReply(Done.done());
      }
      var amount = editedAmount(decision, p.amount());
      var approved = new Payment(p.id(), p.supplier(), amount, p.currency(), p.resultUnknown(), "PAYING",
          p.approvalEventId(), p.reconciliationEventId(), p.attempts(), why(decision));
      return effects().updateState(approved).transitionTo(PaymentWorkflow::pay).thenReply(Done.done());
    }
    if (p.status().equals("AWAITING_RECONCILIATION") && decision.eventId().equals(p.reconciliationEventId())) {
      if (decision.action() == Action.APPROVE) return effects().updateState(p.with("PAID", why(decision))).end().thenReply(Done.done());
      if (decision.action() == Action.REPLAN) {
        var retry = new Payment(p.id(), p.supplier(), p.amount(), p.currency(), false, "PAYING", p.approvalEventId(),
            p.reconciliationEventId(), p.attempts(), "retried");
        return effects().updateState(retry).transitionTo(PaymentWorkflow::pay).thenReply(Done.done());
      }
      return effects().updateState(p.with("NOT_PAID", why(decision))).end().thenReply(Done.done());
    }
    return effects().reply(Done.done());
  }

  /** The backstop: the library always answers before it, so this means the library could not. */
  public Effect<Done> noAnswer() {
    return effects().updateState(currentState().with("NO_ANSWER", "the backstop timeout passed")).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Payment> get() {
    if (currentState() == null) return effects().error("no such payment");
    return effects().reply(currentState());
  }

  /** The amount as people read it: 4,200 EUR. */
  private static String money(Payment p) {
    return String.format(java.util.Locale.ROOT, "%,d %s", p.amount(), p.currency());
  }

  private static String why(Decision d) {
    if (d.timedOut()) return "timed out" + (d.unanswered() ? "" : "; the default applies: " + d.action());
    if (d.unanswered()) return "no decision: " + d.slaOutcome();
    return d.action() + (d.rationale().isBlank() ? "" : ": " + d.rationale());
  }

  /** EDIT replaces the amount through the patch {@code [{op: replace, path: /amount, value}]}. */
  @SuppressWarnings("unchecked")
  private static int editedAmount(Decision d, int amount) {
    if (d.action() != Action.EDIT || !(d.edits().get("patch") instanceof List<?> patch)) return amount;
    for (var op : patch) {
      var change = (Map<String, Object>) op;
      if ("/amount".equals(change.get("path")) && change.get("value") instanceof Number value) return value.intValue();
    }
    return amount;
  }
}
