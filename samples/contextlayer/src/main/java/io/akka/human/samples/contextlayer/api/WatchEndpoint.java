package io.akka.human.samples.contextlayer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Action;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.samples.contextlayer.ContextLayerSetup;
import io.akka.human.samples.contextlayer.application.CaseEntity;
import io.akka.human.samples.contextlayer.application.IndexEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watchers and the cases they open. A reading past the watcher's threshold opens a case, which a
 * reviewer resolves or dismisses (custom-cl0046); further readings while it is open add to it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class WatchEndpoint extends AbstractHttpEndpoint {

  /**
   * One reading of a watcher's metric.
   *
   * @param comparison how the value crosses the threshold: {@code >}, {@code >=}, {@code <} or {@code <=}
   * @param headline the fields a case list shows, such as the customer or claim concerned
   */
  public record Reading(String title, String metric, double value, double threshold, String comparison,
      Map<String, String> headline) {}

  /** What a reading did: opened a case, added to the open one, or nothing. */
  public record Observed(String caseId, String eventId, boolean crossed) {}

  private final ComponentClient client;
  private final HumanClient human;

  public WatchEndpoint(ComponentClient client, HumanClient human) {
    this.client = client;
    this.human = human;
  }

  @Post("/watchers/{watcherId}/readings")
  public Observed reading(String watcherId, Reading reading) {
    var comparison = reading.comparison() == null ? ">" : reading.comparison();
    if (!crosses(reading.value(), comparison, reading.threshold())) return new Observed(null, null, false);
    var cases = client.forKeyValueEntity("watcher-" + watcherId).method(IndexEntity::list).invoke().ids();
    var open = cases.isEmpty() ? null : client.forKeyValueEntity(cases.getLast()).method(CaseEntity::get).invoke();
    var headline = reading.headline() == null ? Map.<String, String>of() : reading.headline();
    String caseId;
    if (open != null && open.status().equals("OPEN")) {
      caseId = open.id();
      client.forKeyValueEntity(caseId).method(CaseEntity::reading).invoke(new CaseEntity.Reading(headline));
    } else {
      caseId = "case-" + UUID.randomUUID().toString().substring(0, 8);
    }
    var raised = human.raise(Patterns.triage(reading.title() + ": resolve or dismiss the case?")
        .control(ContextLayerSetup.CASE_RESOLUTION)
        .subject(Subject.behaviour(reading.title(), reading.metric(), reading.value(), reading.threshold(), comparison)
            .with("contributors", headline.entrySet().stream()
                .map(f -> (Object) Map.of("kind", f.getKey(), "ref", f.getValue())).toList())
            .withRef(caseId))
        .trigger(new Trigger("monitor", reading.metric() + " " + comparison + " " + reading.threshold(),
            "Watcher " + watcherId + " read " + reading.value(),
            List.of(new Trigger.Value(reading.metric(), reading.value(), reading.threshold())), List.of()))
        .dedupeKey("case-" + caseId)
        .effect(Action.APPROVE, "The case is resolved.")
        .effect(Action.DENY, "The case is dismissed.")
        .resumes(Resume.handler(ContextLayerSetup.CASE_HANDLER, caseId))
        .build()).toCompletableFuture().join();
    if (open == null || !open.status().equals("OPEN")) {
      client.forKeyValueEntity(caseId).method(CaseEntity::open).invoke(new CaseEntity.Case(caseId, watcherId,
          reading.title(), headline, "OPEN", Instant.now(), 1, null, null, raised.eventId()));
      client.forKeyValueEntity("watcher-" + watcherId).method(IndexEntity::add).invoke(caseId);
      client.forKeyValueEntity("cases").method(IndexEntity::add).invoke(caseId);
    }
    return new Observed(caseId, raised.eventId(), true);
  }

  @Get("/cases")
  public List<CaseEntity.Case> cases() {
    return Responses.ids(client, "cases").stream()
        .map(id -> client.forKeyValueEntity(id).method(CaseEntity::get).invoke()).toList();
  }

  @Get("/cases/{id}")
  public CaseEntity.Case caseOf(String id) {
    return client.forKeyValueEntity(id).method(CaseEntity::get).invoke();
  }

  private static boolean crosses(double value, String comparison, double threshold) {
    return switch (comparison) {
      case ">=" -> value >= threshold;
      case "<" -> value < threshold;
      case "<=" -> value <= threshold;
      default -> value > threshold;
    };
  }
}
