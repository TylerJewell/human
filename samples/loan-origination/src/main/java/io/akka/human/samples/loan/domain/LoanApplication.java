package io.akka.human.samples.loan.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One loan application, from intake to its final decision: the documents, the documents deadline and
 * its extensions, the assessment, the decision, the adverse-action notice and the history.
 *
 * @param assessment null until the application is assessed or referred without an assessment
 * @param outcome null until an underwriter, or the automatic-approval rule, decides
 * @param noticeBlockedAt when the protected-characteristics guardrail blocked the notice draft
 * @param notice the adverse-action notice as issued; null until issued
 */
public record LoanApplication(
    String id,
    String applicant,
    String nationalId,
    int amount,
    int termMonths,
    int monthlyIncome,
    int monthlyDebts,
    List<String> documents,
    String status,
    Instant documentsDue,
    Assessment assessment,
    Outcome outcome,
    Instant noticeBlockedAt,
    Notice notice,
    List<Extension> extensions,
    List<Step> history) {

  public static final List<String> REQUIRED_DOCUMENTS = List.of("PAYSLIP", "BANK_STATEMENT");
  public static final Duration DOCUMENTS_WINDOW = Duration.ofDays(14);
  public static final Duration CONTEST_WINDOW = Duration.ofDays(30);

  public static final String DOCUMENTS_REQUESTED = "DOCUMENTS_REQUESTED";
  public static final String UNDER_ASSESSMENT = "UNDER_ASSESSMENT";
  public static final String AWAITING_HUMAN_REVIEW = "AWAITING_HUMAN_REVIEW";
  public static final String APPROVED = "APPROVED";
  public static final String DECLINED = "DECLINED";
  /** Nobody decided the review before its last queue's deadline; the application is not decided. */
  public static final String REVIEW_LAPSED = "REVIEW_LAPSED";

  /**
   * @param assessedAt when the eligibility assessor ran; null when a halt refused it
   * @param recommendation APPROVE, REFER or DECLINE; null when not assessed
   * @param reasons decline reason codes the assessment found
   * @param referredBecause why a person decides, when one does
   * @param reviewEventId the underwriting review, when referred
   */
  public record Assessment(
      Instant assessedAt,
      String recommendation,
      double riskScore,
      double debtToIncome,
      List<String> reasons,
      boolean referred,
      String referredBecause,
      String reviewEventId) {}

  /**
   * @param decision APPROVED, DECLINED or REVIEW_LAPSED
   * @param by who decided: a person's subject, or {@code rule:automatic-approval}
   * @param eventId the oversight event the decision was made in; null for the automatic rule
   */
  public record Outcome(String decision, String by, String rationale, List<String> reasons, String eventId, Instant at) {}

  /** @param issuedAs DRAFT, REVIEWED, AMENDED or STANDARD */
  public record Notice(String text, String issuedAs, String model, Instant at) {}

  /** @param status APPLIED, AWAITING_APPROVAL or REFUSED; {@code daysApplied} is what the deadline moved */
  public record Extension(String id, int days, String status, String eventId, int daysApplied, String note) {}

  public record Step(Instant at, String what) {}

  public static LoanApplication submitted(String id, String applicant, String nationalId, int amount, int termMonths,
      int monthlyIncome, int monthlyDebts, Instant now) {
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, List.of(),
        DOCUMENTS_REQUESTED, now.plus(DOCUMENTS_WINDOW), null, null, null, null, List.of(),
        List.of(new Step(now, "Submitted; payslip and bank statement requested")));
  }

  public boolean documentsComplete() {
    return documents.containsAll(REQUIRED_DOCUMENTS);
  }

  public List<String> missingDocuments() {
    return REQUIRED_DOCUMENTS.stream().filter(d -> !documents.contains(d)).toList();
  }

  public LoanApplication withDocument(String type, Instant now) {
    if (documents.contains(type)) return this;
    var next = new ArrayList<>(documents);
    next.add(type);
    var moved = new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts,
        List.copyOf(next), status, documentsDue, assessment, outcome, noticeBlockedAt, notice, extensions, history);
    return moved.step(now, type + " received");
  }

  public LoanApplication withStatus(String next, Instant now, String what) {
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        next, documentsDue, assessment, outcome, noticeBlockedAt, notice, extensions, history).step(now, what);
  }

  public LoanApplication withAssessment(Assessment next, Instant now) {
    var status = next.referred() ? AWAITING_HUMAN_REVIEW : UNDER_ASSESSMENT;
    var what = next.assessedAt() == null ? "Referred to an underwriter: " + next.referredBecause()
        : "Assessed: " + next.recommendation() + (next.referred() ? "; referred to an underwriter" : "");
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        status, documentsDue, next, outcome, noticeBlockedAt, notice, extensions, history).step(now, what);
  }

  public LoanApplication withOutcome(Outcome next, Instant now) {
    var what = next.decision() + " by " + next.by() + (next.rationale().isBlank() ? "" : ": " + next.rationale());
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        next.decision(), documentsDue, assessment, next, noticeBlockedAt, notice, extensions, history).step(now, what);
  }

  public LoanApplication withNoticeBlocked(Instant now) {
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        status, documentsDue, assessment, outcome, now, notice, extensions, history)
        .step(now, "The notice draft was blocked for a protected characteristic and held for review");
  }

  public LoanApplication withNotice(Notice next) {
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        status, documentsDue, assessment, outcome, noticeBlockedAt, next, extensions, history)
        .step(next.at(), "Adverse-action notice issued (" + next.issuedAs() + ")");
  }

  /** Adds the extension, or replaces the one with its id; an applied extension moves the deadline. */
  public LoanApplication withExtension(Extension next, Instant now) {
    var list = new ArrayList<>(extensions.stream().filter(e -> !e.id().equals(next.id())).toList());
    list.add(next);
    var due = documentsDue.plus(Duration.ofDays(next.daysApplied()));
    var what = switch (next.status()) {
      case "APPLIED" -> "Documents deadline moved by " + next.daysApplied() + " days";
      case "AWAITING_APPROVAL" -> "An extension of " + next.days() + " days waits for the loan system owner";
      default -> "An extension of " + next.days() + " days was refused";
    };
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        status, due, assessment, outcome, noticeBlockedAt, notice, List.copyOf(list), history).step(now, what);
  }

  public LoanApplication step(Instant at, String what) {
    var next = new ArrayList<>(history);
    next.add(new Step(at, what));
    return new LoanApplication(id, applicant, nationalId, amount, termMonths, monthlyIncome, monthlyDebts, documents,
        status, documentsDue, assessment, outcome, noticeBlockedAt, notice, extensions, List.copyOf(next));
  }
}
