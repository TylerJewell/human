package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Instant;

/** One loan application; the workflows record each step of its life here. */
@Component(id = "loan-application")
public class ApplicationEntity extends KeyValueEntity<LoanApplication> {

  public record Submit(String applicant, String nationalId, int amount, int termMonths, int monthlyIncome,
      int monthlyDebts) {}

  public record Status(String status, String what) {}

  public Effect<LoanApplication> submit(Submit s) {
    if (currentState() != null) return effects().reply(currentState());
    var id = commandContext().entityId();
    var app = LoanApplication.submitted(id, s.applicant(), s.nationalId(), s.amount(), s.termMonths(), s.monthlyIncome(),
        s.monthlyDebts(), Instant.now());
    return effects().updateState(app).thenReply(app);
  }

  public Effect<LoanApplication> attach(String type) {
    if (currentState() == null) return effects().error("no such application");
    if (!LoanApplication.REQUIRED_DOCUMENTS.contains(type)) {
      return effects().error("a document is one of " + LoanApplication.REQUIRED_DOCUMENTS);
    }
    var app = currentState().withDocument(type, Instant.now());
    if (app.documentsComplete() && app.status().equals(LoanApplication.DOCUMENTS_REQUESTED)) {
      app = app.withStatus(LoanApplication.UNDER_ASSESSMENT, Instant.now(), "Documents complete; assessment started");
    }
    return effects().updateState(app).thenReply(app);
  }

  public Effect<LoanApplication> extension(LoanApplication.Extension extension) {
    if (currentState() == null) return effects().error("no such application");
    var known = currentState().extensions().stream().filter(e -> e.id().equals(extension.id())).findFirst();
    if (known.isPresent() && !known.get().status().equals("AWAITING_APPROVAL")) return effects().reply(currentState());
    return update(currentState().withExtension(extension, Instant.now()));
  }

  public Effect<LoanApplication> assessed(LoanApplication.Assessment assessment) {
    if (currentState() == null) return effects().error("no such application");
    return update(currentState().withAssessment(assessment, Instant.now()));
  }

  public Effect<LoanApplication> decided(LoanApplication.Outcome outcome) {
    if (currentState() == null) return effects().error("no such application");
    return update(currentState().withOutcome(outcome, Instant.now()));
  }

  public Effect<LoanApplication> noticeBlocked() {
    if (currentState() == null) return effects().error("no such application");
    if (currentState().noticeBlockedAt() != null) return effects().reply(currentState());
    return update(currentState().withNoticeBlocked(Instant.now()));
  }

  public Effect<LoanApplication> noticeIssued(LoanApplication.Notice notice) {
    if (currentState() == null) return effects().error("no such application");
    if (currentState().notice() != null) return effects().reply(currentState());
    return update(currentState().withNotice(notice));
  }

  /** A history line, such as a contest's outcome. */
  public Effect<LoanApplication> note(String what) {
    if (currentState() == null) return effects().error("no such application");
    return update(currentState().step(Instant.now(), what));
  }

  public ReadOnlyEffect<LoanApplication> get() {
    if (currentState() == null) return effects().error("no such application");
    return effects().reply(currentState());
  }

  private Effect<LoanApplication> update(LoanApplication next) {
    return effects().updateState(next).thenReply(next);
  }
}
