package io.akka.human.samples.contextlayer.api;

import akka.http.javadsl.model.HttpResponse;
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
import io.akka.human.samples.contextlayer.application.ConnectorPackEntity;
import io.akka.human.samples.contextlayer.application.DomainConfigEntity;
import io.akka.human.samples.contextlayer.application.IdentityEntity;
import io.akka.human.samples.contextlayer.application.IndexEntity;
import io.akka.human.samples.contextlayer.application.QuestionEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Sources, links, connectors, data domains and questions: records an identity rule matched wait for a
 * link review (custom-cl0004); connector versions go live on a second approver (custom-cl0006) and out
 * of service on one (custom-cl0041); domains are administered (custom-cl0042) and joined
 * (custom-cl0014); a drafted question runs only when a person runs it (custom-cl0043).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ConnectEndpoint extends AbstractHttpEndpoint {

  public record RecordRequest(String rule, String key, String entityType, String source, String ref, String loadedBy,
      Map<String, String> fields) {}

  /** A link as the Links screen shows it: the two records side by side, and what agrees and differs. */
  public record LinkView(String clusterId, String rule, String status, IdentityEntity.Member left,
      IdentityEntity.Member right, List<String> agree, List<String> differ, String reviewedBy, String eventId) {}

  public record VersionRequest(String hash, String by) {}

  public record ActivateRequest(String mode, String by) {}

  public record DeactivateRequest(String by, String reason) {}

  public record DomainRequest(List<String> entityTypes, List<String> readerRoles, String by, String reason) {}

  public record ReasonRequest(String by, String reason) {}

  public record JoinRequest(String a, String b, String by, String reason) {}

  public record QuestionRequest(String text, String by) {}

  private final ComponentClient client;
  private final HumanClient human;

  public ConnectEndpoint(ComponentClient client, HumanClient human) {
    this.client = client;
    this.human = human;
  }

  // ---- links

  /**
   * A record a source loaded under an identity rule's key. When the key's cluster holds records from
   * more than one source, a link review is raised; a review still pending for other records is
   * superseded, so a decision is never made on records the reviewer was not shown.
   */
  @Post("/identity/records")
  public LinkView record(RecordRequest request) {
    var clusterId = request.rule() + "-" + hash(request.key());
    var cluster = client.forKeyValueEntity(clusterId).method(IdentityEntity::add).invoke(new IdentityEntity.Add(
        request.rule(), new IdentityEntity.Member(request.source(), request.ref(), request.loadedBy(), request.fields())));
    client.forKeyValueEntity("links").method(IndexEntity::add).invoke(clusterId);
    if (cluster.members().size() < 2) return view(cluster);
    if (cluster.eventId() != null) {
      var previous = human.find(cluster.eventId()).event();
      if (previous != null && previous.pending()) {
        human.supersede(cluster.eventId(), "the records changed").toCompletableFuture().join();
      }
    }
    var left = cluster.members().getFirst();
    var right = cluster.members().getLast();
    var compared = compare(left, right);
    var raised = human.raise(Patterns.matchAdjudication("Are the records that match on " + request.rule()
            + " the same " + request.entityType() + "?")
        .control(ContextLayerSetup.LINK_REVIEW)
        .subject(Subject.records("Are " + cluster.members().size() + " records matching on " + request.rule()
                + " the same thing?", request.entityType(), side(left), side(right))
            .with("agree", compared.get(0)).with("differ", compared.get(1)).with("method", request.rule())
            .with("fingerprint", cluster.fingerprint()).withRef(clusterId))
        .check("The records share details that identify one person or thing.", "subject")
        .check("No record is of something else.", "subject")
        .trigger(Trigger.rule("workflow", request.rule(), "The match is not certain enough to join the records without a person."))
        .proposedBy(left.loadedBy())
        .builtBy(right.loadedBy())
        .dedupeKey("link-" + clusterId + "-" + cluster.fingerprint())
        .effect(Action.APPROVE, "The records are treated as one.")
        .effect(Action.DENY, "The records stay apart.")
        .resumes(Resume.handler(ContextLayerSetup.LINK_HANDLER, clusterId))
        .build()).toCompletableFuture().join();
    return view(client.forKeyValueEntity(clusterId).method(IdentityEntity::reviewRaised).invoke(raised.eventId()));
  }

  @Get("/links")
  public List<LinkView> links() {
    return Responses.ids(client, "links").stream()
        .map(id -> view(client.forKeyValueEntity(id).method(IdentityEntity::get).invoke())).toList();
  }

  @Get("/links/{clusterId}")
  public LinkView link(String clusterId) {
    return view(client.forKeyValueEntity(clusterId).method(IdentityEntity::get).invoke());
  }

  // ---- connectors

  @Post("/connectors/{pack}/versions")
  public ConnectorPackEntity.Pack importVersion(String pack, VersionRequest request) {
    client.forKeyValueEntity("connectors").method(IndexEntity::add).invoke(pack);
    return client.forKeyValueEntity(pack).method(ConnectorPackEntity::importVersion)
        .invoke(new ConnectorPackEntity.Import(request.hash(), request.by()));
  }

  /** Asks to put the latest version SHADOW or LIVE; an approver other than its importer decides. */
  @Post("/connectors/{pack}/activate")
  public HttpResponse activate(String pack, ActivateRequest request) {
    var current = client.forKeyValueEntity(pack).method(ConnectorPackEntity::get).invoke();
    if (!List.of("SHADOW", "LIVE").contains(request.mode())) return Responses.unprocessable("the mode is SHADOW or LIVE");
    var version = current.latest();
    return Responses.raised(human.raise(Patterns.changeAuthorization("Set connector " + pack + " " + version.hash()
            + " to " + request.mode() + "?")
        .control(ContextLayerSetup.CONNECTOR_ACTIVATION)
        .subject(Subject.change("Connector " + pack + " to " + request.mode(), pack, "activate", pack + "/" + version.hash(),
                Map.of("mode", current.mode()), Map.of("mode", request.mode(), "hash", version.hash()))
            .with("proposedBy", version.importedBy()).with("builtBy", version.importedBy()).withRef(pack))
        .trigger(Trigger.rule("change-request", "activate", request.by() + " asked to set the connector to " + request.mode()))
        .proposedBy(version.importedBy())
        .resumes(Resume.handler(ContextLayerSetup.CONNECTOR_HANDLER, pack + "/" + request.mode() + "/" + version.hash()))
        .build()).toCompletableFuture().join());
  }

  @Post("/connectors/{pack}/deactivate")
  public HttpResponse deactivate(String pack, DeactivateRequest request) {
    var current = client.forKeyValueEntity(pack).method(ConnectorPackEntity::get).invoke();
    return Responses.raised(human.raise(Patterns.changeAuthorization("Take connector " + pack + " out of service?")
        .control(ContextLayerSetup.CONNECTOR_DEACTIVATION)
        .subject(Subject.change("Deactivate connector " + pack, pack, "deactivate", pack, Map.of("mode", current.mode()),
                Map.of("mode", "INACTIVE", "hash", "")).with("proposedBy", request.by()).withRef(pack))
        .trigger(Trigger.rule("change-request", "deactivate", request.reason()))
        .proposedBy(request.by())
        .resumes(Resume.handler(ContextLayerSetup.CONNECTOR_HANDLER, pack + "/INACTIVE"))
        .build()).toCompletableFuture().join());
  }

  @Get("/connectors")
  public List<ConnectorPackEntity.Pack> connectors() {
    return Responses.ids(client, "connectors").stream()
        .map(id -> client.forKeyValueEntity(id).method(ConnectorPackEntity::get).invoke()).toList();
  }

  @Get("/connectors/{pack}")
  public ConnectorPackEntity.Pack connector(String pack) {
    return client.forKeyValueEntity(pack).method(ConnectorPackEntity::get).invoke();
  }

  // ---- data domains

  @Post("/domains/{name}")
  public HttpResponse setDomain(String name, DomainRequest request) {
    var current = domains().domains().get(name);
    return Responses.raised(administer("set-domain", name, "Set data domain " + name + " to "
            + String.join(", ", request.entityTypes()),
        current == null ? Map.of("name", name, "exists", "no") : Map.of("name", name, "entityTypes", String.join(",", current.entityTypes())),
        Map.of("name", name, "entityTypes", String.join(",", request.entityTypes()),
            "readerRoles", String.join(",", request.readerRoles() == null ? List.of() : request.readerRoles()),
            "changedBy", request.by()),
        request.by(), request.reason()));
  }

  @Post("/domains/{name}/remove")
  public HttpResponse removeDomain(String name, ReasonRequest request) {
    return Responses.raised(administer("remove-domain", name, "Remove data domain " + name, Map.of("name", name),
        Map.of("name", name, "exists", "no"), request.by(), request.reason()));
  }

  @Get("/domains")
  public DomainConfigEntity.Config domainList() {
    return domains();
  }

  /** Asks to let one question link two domains; whoever last changed either domain may not approve it. */
  @Post("/join-rules")
  public HttpResponse join(JoinRequest request) {
    var config = domains();
    var a = config.domains().get(request.a());
    var b = config.domains().get(request.b());
    if (a == null || b == null) return Responses.unprocessable("both data domains have to exist");
    return Responses.raised(human.raise(Patterns.changeAuthorization("Should one question be able to link the "
            + request.a() + " and " + request.b() + " data domains?")
        .control(ContextLayerSetup.DOMAIN_JOIN)
        .subject(Subject.change("Allow questions to link " + request.a() + " and " + request.b(), "domains", "join",
                request.a() + "~" + request.b(), Map.of("joined", "no"), Map.of("a", request.a(), "b", request.b()))
            .with("proposedBy", a.changedBy()).with("builtBy", b.changedBy()).withRef(request.a() + "~" + request.b()))
        .trigger(Trigger.rule("change-request", "join", request.by() + " asked to join the domains: " + request.reason()))
        .proposedBy(a.changedBy())
        .builtBy(b.changedBy())
        .resumes(Resume.handler(ContextLayerSetup.JOIN_HANDLER, request.a() + "/" + request.b()))
        .build()).toCompletableFuture().join());
  }

  @Post("/join-rules/revoke")
  public HttpResponse revokeJoin(JoinRequest request) {
    return Responses.raised(administer("revoke-join", request.a() + "~" + request.b(), "Stop questions linking "
            + request.a() + " and " + request.b(), Map.of("joined", "yes"), Map.of("a", request.a(), "b", request.b()),
        request.by(), request.reason()));
  }

  // ---- questions

  /** Drafts the question; it runs only when an operator runs it through /oversight. */
  @Post("/questions")
  public HttpResponse ask(QuestionRequest request) {
    var id = "q-" + UUID.randomUUID().toString().substring(0, 8);
    var draft = QuestionEntity.draft(request.text());
    var raised = human.raise(Patterns.actionApproval("Run this drafted question?")
        .control(ContextLayerSetup.RUN_QUESTION)
        .subject(Subject.action("Run the question \"" + request.text() + "\"", "run-question",
            Map.of("query", draft, "question", request.text())).withAgent("question-drafter").withRef(id))
        .check("The query asks only what the question asks.", "subject")
        .trigger(Trigger.rule("workflow", "drafted", request.by() + " asked a question; a model drafted it."))
        .proposedBy("agent:question-drafter")
        .effect(Action.APPROVE, "The query runs, recorded as run by you.")
        .resumes(Resume.handler(ContextLayerSetup.QUESTION_HANDLER, id))
        .build()).toCompletableFuture().join();
    client.forKeyValueEntity(id).method(QuestionEntity::ask)
        .invoke(new QuestionEntity.Ask(request.text(), request.by(), raised.eventId()));
    client.forKeyValueEntity("questions").method(IndexEntity::add).invoke(id);
    return Responses.raised(raised);
  }

  @Get("/questions/{id}")
  public QuestionEntity.Question question(String id) {
    return client.forKeyValueEntity(id).method(QuestionEntity::get).invoke();
  }

  // ---- internals

  private io.akka.human.api.Raised administer(String change, String ref, String summary, Map<String, String> before,
      Map<String, String> after, String by, String reason) {
    return human.raise(Patterns.changeAuthorization(summary + "?")
        .control(ContextLayerSetup.DOMAIN_ADMINISTRATION)
        .subject(Subject.change(summary, "domains", change, ref, new LinkedHashMap<String, Object>(before),
                new LinkedHashMap<String, Object>(after))
            .with("proposedBy", by).withRef(ref))
        .trigger(Trigger.rule("change-request", change, reason == null ? by + " asked for it" : reason))
        .proposedBy(by)
        .resumes(Resume.handler(ContextLayerSetup.DOMAIN_HANDLER, change + "/" + ref))
        .build()).toCompletableFuture().join();
  }

  private DomainConfigEntity.Config domains() {
    return client.forKeyValueEntity(DomainConfigEntity.ID).method(DomainConfigEntity::get).invoke();
  }

  private static LinkView view(IdentityEntity.Cluster c) {
    var left = c.members().isEmpty() ? null : c.members().getFirst();
    var right = c.members().size() < 2 ? null : c.members().getLast();
    var compared = left == null || right == null ? List.<List<String>>of(List.of(), List.of()) : compare(left, right);
    return new LinkView(c.id(), c.rule(), c.status(), left, right, compared.get(0), compared.get(1), c.reviewedBy(),
        c.eventId());
  }

  /** The fields the two records agree on, and the ones they differ on. */
  private static List<List<String>> compare(IdentityEntity.Member left, IdentityEntity.Member right) {
    var names = new TreeSet<String>(left.fields().keySet());
    names.addAll(right.fields().keySet());
    var agree = names.stream().filter(n -> java.util.Objects.equals(left.fields().get(n), right.fields().get(n))).toList();
    var differ = names.stream().filter(n -> !agree.contains(n)).toList();
    return List.of(agree, differ);
  }

  private static Map<String, Object> side(IdentityEntity.Member m) {
    var side = new LinkedHashMap<String, Object>();
    side.put("source", m.source());
    side.put("ref", m.ref());
    side.put("loadedBy", m.loadedBy());
    side.put("fields", m.fields());
    return side;
  }

  /** The key is the matched text, often an email address; ids carry a hash of it instead. */
  private static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
