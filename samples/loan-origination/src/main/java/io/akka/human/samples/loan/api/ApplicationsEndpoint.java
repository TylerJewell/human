package io.akka.human.samples.loan.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import io.akka.human.samples.loan.application.ApplicationEntity;
import io.akka.human.samples.loan.application.ApplicationsView;
import io.akka.human.samples.loan.application.ContestWorkflow;
import io.akka.human.samples.loan.application.NoticeWorkflow;
import io.akka.human.samples.loan.application.UnderwritingWorkflow;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Instant;
import java.util.UUID;

/**
 * Applicants apply, attach documents and contest a decline; anyone may follow an application's
 * underwriting, notice and contest, and the underwriters' queue. Underwriters and reviewers decide
 * through {@code /oversight}.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ApplicationsEndpoint extends AbstractHttpEndpoint {

  public record ApplyRequest(String applicant, String nationalId, int amount, int termMonths, int monthlyIncome,
      int monthlyDebts) {}

  public record Created(String id) {}

  /** {@code type}: PAYSLIP or BANK_STATEMENT. */
  public record DocumentRequest(String type, String reference) {}

  public record ContestRequest(String statement) {}

  private final ComponentClient client;

  public ApplicationsEndpoint(ComponentClient client) {
    this.client = client;
  }

  @Post("/applications")
  public Created apply(ApplyRequest r) {
    if (blank(r.applicant()) || blank(r.nationalId()) || r.amount() <= 0 || r.termMonths() <= 0) {
      throw HttpException.badRequest("applicant, nationalId, amount and termMonths are required");
    }
    var id = "app-" + UUID.randomUUID().toString().substring(0, 8);
    client.forKeyValueEntity(id).method(ApplicationEntity::submit).invoke(new ApplicationEntity.Submit(r.applicant(),
        r.nationalId(), r.amount(), r.termMonths(), r.monthlyIncome(), r.monthlyDebts()));
    return new Created(id);
  }

  /** Every application, one row each with its status. */
  @Get("/applications")
  public ApplicationsView.Rows applications() {
    return client.forView().method(ApplicationsView::all).invoke();
  }

  @Get("/applications/{id}")
  public LoanApplication application(String id) {
    return client.forKeyValueEntity(id).method(ApplicationEntity::get).invoke();
  }

  /** Once both documents are in, underwriting starts. */
  @Post("/applications/{id}/documents")
  public LoanApplication attach(String id, DocumentRequest r) {
    if (r.type() == null || !LoanApplication.REQUIRED_DOCUMENTS.contains(r.type())) {
      throw HttpException.badRequest("type is one of " + LoanApplication.REQUIRED_DOCUMENTS);
    }
    var app = client.forKeyValueEntity(id).method(ApplicationEntity::attach).invoke(r.type());
    if (app.documentsComplete()) client.forWorkflow(id).method(UnderwritingWorkflow::start).invoke();
    return app;
  }

  @Get("/applications/{id}/underwriting")
  public UnderwritingWorkflow.Underwriting underwriting(String id) {
    return client.forWorkflow(id).method(UnderwritingWorkflow::get).invoke();
  }

  @Get("/applications/{id}/notice")
  public NoticeWorkflow.Notice notice(String id) {
    return client.forWorkflow(id).method(NoticeWorkflow::get).invoke();
  }

  /** A declined applicant contests, within 30 days of the adverse-action notice. */
  @Post("/applications/{id}/contest")
  public Created contest(String id, ContestRequest r) {
    if (blank(r.statement())) throw HttpException.badRequest("say why the decision is wrong (statement)");
    var app = application(id);
    if (!app.status().equals(LoanApplication.DECLINED) || app.notice() == null) {
      throw HttpException.error(StatusCodes.CONFLICT, "only a declined application with an issued notice can be contested");
    }
    if (Instant.now().isAfter(app.notice().at().plus(LoanApplication.CONTEST_WINDOW))) {
      throw HttpException.error(StatusCodes.CONFLICT, "the 30 days to contest have passed");
    }
    client.forWorkflow(id).method(ContestWorkflow::start).invoke(new ContestWorkflow.Start(r.statement()));
    return new Created(id);
  }

  @Get("/applications/{id}/contest")
  public ContestWorkflow.Contest contest(String id) {
    return client.forWorkflow(id).method(ContestWorkflow::get).invoke();
  }

  /** Applications waiting for an underwriter, each with its review event. */
  @Get("/underwriting/queue")
  public ApplicationsView.Rows queue() {
    return client.forView().method(ApplicationsView::withStatus)
        .invoke(new ApplicationsView.WithStatus(LoanApplication.AWAITING_HUMAN_REVIEW));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
