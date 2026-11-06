package io.akka.human.samples.loan.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Reviewer;
import io.akka.human.samples.loan.LoanSetup;
import io.akka.human.samples.loan.application.ApplicationEntity;
import io.akka.human.samples.loan.application.DeadlineExtensionWorkflow;
import io.akka.human.samples.loan.application.EvalSetEntity;
import io.akka.human.samples.loan.application.SupportSessionEntity;
import io.akka.human.samples.loan.domain.ApplicantSupport;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.util.List;
import java.util.UUID;

/**
 * The applicant support assistant: a turn of a conversation about one application, in which the
 * assistant may move the documents deadline (S1); and the flag on its latest reply (S7).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class SupportEndpoint extends AbstractHttpEndpoint {

  public static final String FLAG_CONTROL = "custom-4c293fe7fd2e";

  /** {@code sessionId}: null starts a conversation. */
  public record TurnRequest(String sessionId, String applicationId, String message) {}

  /** @param extensionId the extension request that waits for the loan system owner, when the turn made one */
  public record TurnReply(String sessionId, String reply, String extensionId) {}

  /** @param by who flags the reply */
  public record FlagRequest(String by, String note) {}

  public record Flagged(String eventId, String state) {}

  private final ComponentClient client;
  private final HumanClient human;

  public SupportEndpoint(ComponentClient client, HumanClient human) {
    this.client = client;
    this.human = human;
  }

  @Post("/support/turns")
  public TurnReply turn(TurnRequest r) {
    if (r.applicationId() == null || r.message() == null || r.message().isBlank()) {
      throw HttpException.badRequest("applicationId and message are required");
    }
    var sessionId = r.sessionId() == null || r.sessionId().isBlank() ? "session-" + shortId() : r.sessionId();
    var entity = client.forKeyValueEntity(r.applicationId());
    var app = entity.method(ApplicationEntity::get).invoke();
    String reply;
    String extensionId = null;
    switch (ApplicantSupport.answer(r.message(), app)) {
      case ApplicantSupport.Turn.Reply answer -> reply = answer.text();
      case ApplicantSupport.Turn.ExtendDeadline ask when ask.days() <= ApplicantSupport.DAYS_ALONE -> {
        var moved = entity.method(ApplicationEntity::extension).invoke(new LoanApplication.Extension("extension-" + shortId(),
            ask.days(), "APPLIED", null, ask.days(), "within the assistant's authority"));
        reply = ApplicantSupport.extended(ask.days(), moved);
      }
      case ApplicantSupport.Turn.ExtendDeadline ask -> {
        extensionId = "extension-" + shortId();
        client.forWorkflow(extensionId).method(DeadlineExtensionWorkflow::start)
            .invoke(new DeadlineExtensionWorkflow.Start(r.applicationId(), ask.days(), ask.reason(), sessionId));
        reply = ApplicantSupport.waiting(ask.days(), extensionId);
      }
    }
    client.forKeyValueEntity(sessionId).method(SupportSessionEntity::turn)
        .invoke(new SupportSessionEntity.Turn(r.applicationId(), r.message(), reply));
    return new TurnReply(sessionId, reply, extensionId);
  }

  @Get("/support/sessions/{id}")
  public SupportSessionEntity.Session session(String id) {
    return client.forKeyValueEntity(id).method(SupportSessionEntity::get).invoke();
  }

  /** Someone flags the assistant's latest reply in the session as wrong. */
  @Post("/support/sessions/{id}/flag")
  public Flagged flag(String id, FlagRequest r) {
    if (r.by() == null || r.by().isBlank()) throw HttpException.badRequest("say who flags the reply (by)");
    var session = session(id);
    var by = new Reviewer(r.by(), r.by(), List.of(), LoanSetup.SERVICE, false);
    var answer = human.flag(by, r.note(), null, id, session.latestReply(), FLAG_CONTROL);
    if (answer.refused()) throw HttpException.error(StatusCodes.get(answer.refusal().status()), answer.refusal().error());
    return new Flagged(answer.event().id(), answer.event().state().name());
  }

  @Get("/support/eval-set")
  public EvalSetEntity.EvalSet evalSet() {
    return client.forKeyValueEntity(EvalSetEntity.ID).method(EvalSetEntity::get).invoke();
  }

  @Get("/deadline-extensions/{id}")
  public DeadlineExtensionWorkflow.Request extension(String id) {
    return client.forWorkflow(id).method(DeadlineExtensionWorkflow::get).invoke();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
