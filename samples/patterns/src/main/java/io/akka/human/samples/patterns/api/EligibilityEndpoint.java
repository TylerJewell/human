package io.akka.human.samples.patterns.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import io.akka.human.akka.HumanClient;
import io.akka.human.samples.patterns.PatternsSetup;
import java.time.Instant;

/**
 * The eligibility-assessment capability that halt-restart (pt-0012) stops. While a halt stops it,
 * every assessment is refused with 503 and the halt's details; a restart approved by two safety
 * officers, once the backlog is drained, lets assessments run again.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/eligibility")
public class EligibilityEndpoint extends AbstractHttpEndpoint {

  public record Assess(String applicant, int income) {}

  public record Assessment(String applicant, boolean eligible, boolean referred, Instant assessedAt) {}

  public record Refused(String error, String haltedBy, String eventId) {}

  private final HumanClient human;

  public EligibilityEndpoint(HumanClient human) {
    this.human = human;
  }

  @Post("/assess")
  public HttpResponse assess(Assess request) {
    var halt = human.halt(PatternsSetup.ELIGIBILITY);
    if (halt.halted()) {
      return HttpResponses.of(StatusCodes.SERVICE_UNAVAILABLE, akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
          akka.javasdk.JsonSupport.encodeToAkkaByteString(new Refused(PatternsSetup.ELIGIBILITY + " is halted",
              halt.state().by(), halt.state().eventId())).toArray());
    }
    var eligible = request.income() < 30_000;
    return HttpResponses.ok(new Assessment(request.applicant(), eligible, request.income() > 25_000 && eligible,
        Instant.now()));
  }
}
