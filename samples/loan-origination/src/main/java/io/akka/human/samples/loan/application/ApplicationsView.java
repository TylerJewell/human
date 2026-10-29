package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One row per application: the underwriters' queue, and the records the S2 and S4 monitors and the
 * eligibility halt's safe-state checks read.
 */
@Component(id = "loan-applications")
public class ApplicationsView extends View {

  /**
   * @param assessedAt when the eligibility assessor ran
   * @param referred whether the application went to an underwriter
   * @param noticeBlockedAt when the guardrail blocked its notice draft
   */
  public record Row(String id, String applicant, int amount, String status, Optional<Instant> assessedAt,
      boolean referred, Optional<String> reviewEventId, Optional<Instant> noticeBlockedAt) {}

  public record Rows(List<Row> entries) {}

  public record Since(Instant since) {}

  public record WithStatus(String status) {}

  @Consume.FromKeyValueEntity(ApplicationEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onUpdate(LoanApplication a) {
      var assessment = Optional.ofNullable(a.assessment());
      return effects().updateRow(new Row(a.id(), a.applicant(), a.amount(), a.status(),
          assessment.map(LoanApplication.Assessment::assessedAt), assessment.map(LoanApplication.Assessment::referred)
          .orElse(false), assessment.map(LoanApplication.Assessment::reviewEventId), Optional.ofNullable(a.noticeBlockedAt())));
    }
  }

  @Query("SELECT * AS entries FROM loan_applications")
  public QueryEffect<Rows> all() {
    return queryResult();
  }

  @Query("SELECT * AS entries FROM loan_applications WHERE status = :status")
  public QueryEffect<Rows> withStatus(WithStatus status) {
    return queryResult();
  }

  @Query("SELECT * AS entries FROM loan_applications WHERE assessedAt >= :since")
  public QueryEffect<Rows> assessedSince(Since since) {
    return queryResult();
  }

  @Query("SELECT * AS entries FROM loan_applications WHERE noticeBlockedAt >= :since")
  public QueryEffect<Rows> blockedSince(Since since) {
    return queryResult();
  }
}
