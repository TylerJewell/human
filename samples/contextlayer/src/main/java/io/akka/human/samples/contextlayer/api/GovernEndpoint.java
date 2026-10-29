package io.akka.human.samples.contextlayer.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanLibrary;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.samples.contextlayer.ContextLayerSetup;
import io.akka.human.samples.contextlayer.application.ControlClaimsEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * What people state about the bundle's controls: a waiver until a date at most 365 days away
 * (custom-cl0047), an acceptance that holds while the catalog is unchanged (custom-cl0048), and evidence
 * assertions, one revision each (custom-cl0049).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/controls")
public class GovernEndpoint extends AbstractHttpEndpoint {

  static final Duration LONGEST_WAIVER = Duration.ofDays(365);

  public record WaiverRequest(String by, String reason, String until) {}

  public record AcceptanceRequest(String by, String reason) {}

  public record AssertionRequest(String by, List<String> records, List<String> documents, String reason) {}

  /**
   * A control's claims as they stand now: whether its waiver is in force, whether its acceptance is
   * for the catalog in force ({@code current}), an older one ({@code stale}) or missing ({@code none}).
   */
  public record ClaimsView(String rowId, ControlClaimsEntity.Waiver waiver, boolean waived,
      ControlClaimsEntity.Acceptance acceptance, String acceptanceState, String catalogDigest,
      List<ControlClaimsEntity.Assertion> assertions) {}

  private final ComponentClient client;
  private final HumanClient human;
  private final HumanLibrary library;

  public GovernEndpoint(ComponentClient client, HumanClient human, HumanPlugins plugins) {
    this.client = client;
    this.human = human;
    this.library = HumanLibrary.of(plugins);
  }

  @Post("/{rowId}/waivers")
  public HttpResponse waive(String rowId, WaiverRequest request) {
    var control = library.controls().find(rowId).orElse(null);
    if (control == null) return HttpResponses.notFound();
    Instant until;
    try {
      until = Instant.parse(request.until());
    } catch (DateTimeParseException | NullPointerException e) {
      return Responses.unprocessable("until must be an ISO 8601 instant");
    }
    var now = library.now();
    if (!until.isAfter(now)) return Responses.unprocessable("a waiver ends in the future");
    if (until.isAfter(now.plus(LONGEST_WAIVER))) return Responses.unprocessable("a waiver ends at most 365 days from now");
    return Responses.raised(human.raise(Patterns.exception("Waive " + rowId + " (" + control.control().name() + ") until "
            + request.until() + "?")
        .control(ContextLayerSetup.CONTROL_WAIVER)
        .subject(Subject.exception("Waive " + control.control().name(), rowId, control.control().name(), "project", until)
            .with("requestedBy", request.by()).with("justification", request.reason()).with("from", now.toString())
            .withRef(rowId))
        .trigger(Trigger.rule("person", "waiver", request.reason()))
        .proposedBy(request.by())
        .resumes(Resume.handler(ContextLayerSetup.WAIVER_HANDLER, rowId))
        .build()).toCompletableFuture().join());
  }

  /** Asks to accept the control for the project under the catalog in force. */
  @Post("/{rowId}/acceptance")
  public HttpResponse accept(String rowId, AcceptanceRequest request) {
    var control = library.controls().find(rowId).orElse(null);
    if (control == null) return HttpResponses.notFound();
    var digest = library.controls().digest();
    return Responses.raised(human.raise(Patterns.changeAuthorization("Accept " + rowId + " (" + control.control().name()
            + ") for the project?")
        .control(ContextLayerSetup.CONTROL_ACCEPTANCE)
        .subject(Subject.change("Accept " + control.control().name(), "controls", "acceptance", rowId,
                Map.of("accepted", "no"), Map.of("accepted", "yes", "digest", digest))
            .with("proposedBy", request.by()).withRef(rowId))
        .trigger(Trigger.rule("person", "acceptance", request.reason()))
        .proposedBy(request.by())
        .matchKey("accept:" + rowId + ":" + digest)
        .resumes(Resume.handler(ContextLayerSetup.ACCEPTANCE_HANDLER, rowId))
        .build()).toCompletableFuture().join());
  }

  @Post("/{rowId}/assertions")
  public HttpResponse assertEvidence(String rowId, AssertionRequest request) {
    var control = library.controls().find(rowId).orElse(null);
    if (control == null) return HttpResponses.notFound();
    var records = request.records() == null ? List.<String>of() : request.records();
    var documents = request.documents() == null ? List.<String>of() : request.documents();
    return Responses.raised(human.raise(Patterns.changeAuthorization("Assert the evidence of " + rowId + "?")
        .control(ContextLayerSetup.EVIDENCE_ASSERTION)
        .subject(Subject.change("Evidence for " + control.control().name(), "controls", "assertion", rowId,
                Map.of("revision", String.valueOf(claims(rowId).assertions().size())),
                Map.of("records", String.join(",", records), "documents", String.join(",", documents)))
            .with("proposedBy", request.by()).withRef(rowId))
        .trigger(Trigger.rule("person", "assertion", request.reason()))
        .proposedBy(request.by())
        .resumes(Resume.handler(ContextLayerSetup.ASSERTION_HANDLER, rowId))
        .build()).toCompletableFuture().join());
  }

  @Get("/{rowId}/claims")
  public ClaimsView claimsOf(String rowId) {
    var c = claims(rowId);
    var digest = library.controls().digest();
    var waived = c.waiver() != null && library.now().isBefore(c.waiver().until());
    var state = c.acceptance() == null ? "none" : c.acceptance().digest().equals(digest) ? "current" : "stale";
    return new ClaimsView(rowId, c.waiver(), waived, c.acceptance(), state, digest, c.assertions());
  }

  private ControlClaimsEntity.Claims claims(String rowId) {
    return client.forKeyValueEntity(rowId).method(ControlClaimsEntity::get).invoke();
  }
}
