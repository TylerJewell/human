package io.akka.human.samples.loan.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.samples.loan.domain.EligibilityAssessor;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Underwriting of one application once its documents are in: the credit file and affordability, the
 * eligibility assessor's recommendation, and the rule that approves only low-risk, in-policy cases
 * alone. Every other case waits for an underwriter (custom-5a1e00000009), and for a senior
 * underwriter once the standard queue's deadline passes. While a halt stops the eligibility
 * assessment, the assessor is not asked and every case goes to an underwriter. The workflow id is the
 * application id.
 */
@Component(id = "underwriting")
public class UnderwritingWorkflow extends Workflow<UnderwritingWorkflow.Underwriting> {

  public static final String RESUMER = "underwriting";
  public static final String CONTROL = "custom-5a1e00000009";
  public static final String AUTOMATIC = "rule:automatic-approval";
  public static final String HALTED = "AUTOMATED_ASSESSMENT_HALTED";
  /** custom-5a1e00000009 has 2 days at standard review, then 3 at senior review. */
  static final Duration BACKSTOP = Duration.ofDays(6);

  /** {@code status}: ASSESSING, AWAITING_REVIEW, DECIDED or NO_ANSWER. */
  public record Underwriting(String applicationId, String status, String eventId, Decision decision) {}

  private final Human human;
  private final ComponentClient client;
  private final String id;

  public UnderwritingWorkflow(Human human, ComponentClient client, WorkflowContext context) {
    this.human = human;
    this.client = client;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start() {
    if (currentState() != null) return effects().reply(Done.done());
    return effects().updateState(new Underwriting(id, "ASSESSING", null, null))
        .transitionTo(UnderwritingWorkflow::assess).thenReply(Done.done());
  }

  private StepEffect assess() {
    var app = application();
    var now = Instant.now();
    EligibilityAssessor.Recommendation r = null;
    if (!human.halted(EligibilityAssessor.CAPABILITY).toCompletableFuture().join()) {
      r = EligibilityAssessor.assess(new EligibilityAssessor.Input(app.amount(), app.termMonths(), app.monthlyIncome(),
          app.monthlyDebts(), EligibilityAssessor.creditDefaults(app.nationalId())));
    }
    if (r == null || !EligibilityAssessor.approvesAlone(r, app.amount())) {
      return stepEffects().updateState(new Underwriting(id, "AWAITING_REVIEW", refer(app, r, now), null))
          .thenPause(pauseSetting(BACKSTOP).timeoutHandler(UnderwritingWorkflow::noAnswer));
    }
    entity().method(ApplicationEntity::assessed).invoke(new LoanApplication.Assessment(now, r.recommendation(),
        r.riskScore(), r.debtToIncome(), r.reasons(), false, null, null));
    entity().method(ApplicationEntity::decided).invoke(new LoanApplication.Outcome(LoanApplication.APPROVED, AUTOMATIC,
        r.rationale(), List.of(), null, now));
    return stepEffects().updateState(new Underwriting(id, "DECIDED", null, null)).thenEnd();
  }

  /**
   * Raises the review and records the referral; answers the review's event id. {@code r} is null when
   * the halt refused the assessment.
   */
  private String refer(LoanApplication app, EligibilityAssessor.Recommendation r, Instant now) {
    var because = r == null ? HALTED : "the assessor recommends " + r.recommendation();
    var output = r == null ? "No automated assessment: " + EligibilityAssessor.CAPABILITY + " is halted."
        : "The eligibility assessor recommends " + r.recommendation() + " (risk " + r.riskScore() + "). " + r.rationale();
    var request = Patterns.decisionReview("Approve or decline application " + app.id() + " for " + app.amount()
            + " over " + app.termMonths() + " months?")
        .control(CONTROL)
        .subject(Subject.output("Application " + app.id() + ": " + app.amount() + " over " + app.termMonths() + " months",
                output)
            .with("decision", Subject.fields("ref", app.id(), "summary", "Referred to an underwriter: " + because,
                "affects", app.applicant()))
            .with("facts", Subject.fields("amount", app.amount(), "termMonths", app.termMonths(),
                "monthlyIncome", app.monthlyIncome(), "monthlyDebts", app.monthlyDebts(),
                "instalment", EligibilityAssessor.instalment(app.amount(), app.termMonths()),
                "creditDefaults", EligibilityAssessor.creditDefaults(app.nationalId())))
            .with("reasons", r == null ? List.of() : r.reasons())
            .withAgent(EligibilityAssessor.AGENT).withWorkflow(id).withRef(app.id()))
        .check("The affordability figures support the decision.", "subject")
        .check("The credit file was read.", "subject")
        .trigger(Trigger.rule("workflow", "referred", "Referred to an underwriter: " + because))
        .proposedBy("agent:" + EligibilityAssessor.AGENT)
        .dedupeKey("review-" + app.id())
        .effect(Action.APPROVE, "The loan is approved.")
        .effect(Action.DENY, "The application is declined and an adverse-action notice is prepared.")
        .effect(Action.ESCALATE, "A senior underwriter decides.")
        .resumes(Resume.workflow(RESUMER, id));
    if (r != null && !r.recommendation().equals("REFER")) {
      var approve = r.recommendation().equals("APPROVE");
      request.aiRecommendation(EligibilityAssessor.AGENT, approve ? Action.APPROVE : Action.DENY,
          approve ? 1 - r.riskScore() : r.riskScore(), r.rationale());
    }
    var raised = human.raise(request.build()).toCompletableFuture().join();
    entity().method(ApplicationEntity::assessed).invoke(r == null
        ? new LoanApplication.Assessment(null, null, 0, 0, List.of(), true, HALTED, raised.eventId())
        : new LoanApplication.Assessment(now, r.recommendation(), r.riskScore(), r.debtToIncome(), r.reasons(), true,
            because, raised.eventId()));
    return raised.eventId();
  }

  /** The library's one call with the final outcome; a repeated call finds the workflow moved on. */
  public Effect<Done> decided(Decision decision) {
    var u = currentState();
    if (u == null || !u.status().equals("AWAITING_REVIEW") || !decision.eventId().equals(u.eventId())) {
      return effects().reply(Done.done());
    }
    return effects().updateState(new Underwriting(id, "DECIDED", u.eventId(), decision))
        .transitionTo(UnderwritingWorkflow::record).thenReply(Done.done());
  }

  private StepEffect record() {
    var d = currentState().decision();
    var app = application();
    var now = Instant.now();
    var by = d.deciders().isEmpty() ? "system" : d.deciders().getLast();
    if (d.proceeds()) {
      decide(new LoanApplication.Outcome(LoanApplication.APPROVED, by, d.rationale(), List.of(), d.eventId(), now));
    } else if (d.action() == Action.DENY) {
      var reasons = app.assessment().reasons().isEmpty() ? List.of("OTHER") : app.assessment().reasons();
      decide(new LoanApplication.Outcome(LoanApplication.DECLINED, by, d.rationale(), reasons, d.eventId(), now));
      client.forWorkflow(id).method(NoticeWorkflow::start).invoke(new NoticeWorkflow.Start(reasons, d.rationale()));
    } else {
      decide(new LoanApplication.Outcome(LoanApplication.REVIEW_LAPSED, "system", "no decision: " + d.slaOutcome(),
          List.of(), d.eventId(), now));
    }
    return stepEffects().thenEnd();
  }

  /** The backstop: the library always answers before it, so this means the library could not. */
  public Effect<Done> noAnswer() {
    var u = currentState();
    return effects().updateState(new Underwriting(id, "NO_ANSWER", u.eventId(), null)).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Underwriting> get() {
    if (currentState() == null) return effects().error("no underwriting for " + id);
    return effects().reply(currentState());
  }

  private void decide(LoanApplication.Outcome outcome) {
    entity().method(ApplicationEntity::decided).invoke(outcome);
  }

  private LoanApplication application() {
    return entity().method(ApplicationEntity::get).invoke();
  }

  private akka.javasdk.client.KeyValueEntityClient entity() {
    return client.forKeyValueEntity(id);
  }
}
