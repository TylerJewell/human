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
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.engine.HaltState;
import io.akka.human.samples.contextlayer.ContextLayerSetup;
import io.akka.human.samples.contextlayer.application.ActionLedgerEntity;
import io.akka.human.samples.contextlayer.application.ActionWorkflow;
import io.akka.human.samples.contextlayer.application.AssistantEntity;
import io.akka.human.samples.contextlayer.application.IndexEntity;
import io.akka.human.samples.contextlayer.application.PlaybookRunWorkflow;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Playbooks ("assistants"): releases (custom-cl0005), stage changes (custom-cl0040), the emergency halt
 * (custom-cl0011) and the two-person restart (custom-cl0012), the actions a playbook asks for
 * (custom-cl0003, then custom-cl0044 when an outcome is unknown), and runs whose failing steps are handed
 * to an operator (custom-cl0045).
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class PlaybooksEndpoint extends AbstractHttpEndpoint {

  public record CreateRequest(String id, String name, String by) {}

  public record ReleaseRequest(String version, String by) {}

  public record StageRequest(String stage, String by) {}

  public record HaltRequest(Reviewer reviewer, String reason) {}

  public record ActionRequest(String actionType, String payload, String risk, String requestedBy) {}

  public record RunRequest(List<String> steps, List<String> failing, String by) {}

  /** A playbook as the Playbooks screen shows it, with the halt that stops it, if any. */
  public record PlaybookView(AssistantEntity.Assistant playbook, boolean halted, HaltState halt) {}

  private final ComponentClient client;
  private final HumanClient human;

  public PlaybooksEndpoint(ComponentClient client, HumanClient human) {
    this.client = client;
    this.human = human;
  }

  @Post("/assistants")
  public PlaybookView create(CreateRequest request) {
    var id = request.id() == null ? "pb-" + UUID.randomUUID().toString().substring(0, 8) : request.id();
    client.forKeyValueEntity(id).method(AssistantEntity::create).invoke(new AssistantEntity.Create(request.name(), request.by()));
    client.forKeyValueEntity("assistants").method(IndexEntity::add).invoke(id);
    return view(id);
  }

  @Get("/assistants")
  public List<PlaybookView> list() {
    return Responses.ids(client, "assistants").stream().map(this::view).toList();
  }

  @Get("/assistants/{id}")
  public PlaybookView get(String id) {
    return view(id);
  }

  /** Proposes a release; it becomes runnable when an approver approves it. */
  @Post("/assistants/{id}/releases")
  public HttpResponse release(String id, ReleaseRequest request) {
    var playbook = client.forKeyValueEntity(id).method(AssistantEntity::get).invoke();
    var releaseId = "rel-" + UUID.randomUUID().toString().substring(0, 8);
    var current = playbook.runnableRelease();
    var raised = human.raise(Patterns.changeAuthorization("Make release " + request.version() + " of playbook "
            + playbook.name() + " runnable?")
        .control(ContextLayerSetup.RELEASE_APPROVAL)
        .subject(Subject.change("Release " + request.version() + " of " + playbook.name(), "playbooks", "release",
                releaseId, Map.of("release", current == null ? "none" : current.version()), Map.of("release", request.version()))
            .with("proposedBy", request.by()).with("stage", playbook.stage()).withAgent(id).withRef(id + "/" + releaseId))
        .trigger(Trigger.rule("change-request", "release", request.by() + " proposed release " + request.version()))
        .proposedBy(request.by())
        .effect(Action.APPROVE, "The release becomes runnable.")
        .resumes(Resume.handler(ContextLayerSetup.RELEASE_HANDLER, id + "/" + releaseId))
        .build()).toCompletableFuture().join();
    client.forKeyValueEntity(id).method(AssistantEntity::proposeRelease)
        .invoke(new AssistantEntity.Propose(releaseId, request.version(), request.by(), raised.eventId()));
    return Responses.raised(raised);
  }

  /** Proposes a stage change; SHADOW needs a release and LIVE an approved one. */
  @Post("/assistants/{id}/stage")
  public HttpResponse stage(String id, StageRequest request) {
    var playbook = client.forKeyValueEntity(id).method(AssistantEntity::get).invoke();
    var problem = AssistantEntity.stageProblem(playbook, request.stage());
    if (problem != null) return Responses.unprocessable(problem);
    return Responses.raised(human.raise(Patterns.changeAuthorization("Move playbook " + playbook.name() + " from "
            + playbook.stage() + " to " + request.stage() + "?")
        .control(ContextLayerSetup.STAGE_CHANGE)
        .subject(Subject.change("Stage of " + playbook.name() + " to " + request.stage(), "playbooks", "stage", id,
                Map.of("stage", playbook.stage()), Map.of("stage", request.stage()))
            .with("proposedBy", request.by()).withAgent(id).withRef(id))
        .trigger(Trigger.rule("change-request", "stage", request.by() + " asked to move the playbook to " + request.stage()))
        .proposedBy(request.by())
        .resumes(Resume.handler(ContextLayerSetup.STAGE_HANDLER, id + "/" + request.stage()))
        .build()).toCompletableFuture().join());
  }

  /** One approver stops the playbook at once. */
  @Post("/assistants/{id}/halt")
  public HttpResponse halt(String id, HaltRequest request) {
    return Responses.answer(human.halt(request.reviewer(), ContextLayerSetup.HALT, id, false, request.reason()));
  }

  /** Asks to restart a halted playbook; two approvers decide it through /oversight. */
  @Post("/assistants/{id}/restart")
  public HttpResponse restart(String id, HaltRequest request) {
    return Responses.answer(human.halt(request.reviewer(), ContextLayerSetup.RESTART, id, true, request.reason()));
  }

  @Post("/assistants/{id}/actions")
  public Map<String, String> requestAction(String id, ActionRequest request) {
    var key = "act-" + UUID.randomUUID().toString().substring(0, 8);
    client.forWorkflow(key).method(ActionWorkflow::start).invoke(new ActionWorkflow.Start(id, request.actionType(),
        request.payload(), request.risk() == null ? "MEDIUM" : request.risk(), request.requestedBy()));
    client.forKeyValueEntity("actions").method(IndexEntity::add).invoke(key);
    return Map.of("actionKey", key);
  }

  @Get("/actions")
  public List<ActionLedgerEntity.Action> actions() {
    return Responses.ids(client, "actions").stream()
        .map(key -> client.forKeyValueEntity(key).method(ActionLedgerEntity::get).invoke()).toList();
  }

  @Get("/actions/{key}")
  public ActionLedgerEntity.Action action(String key) {
    return client.forKeyValueEntity(key).method(ActionLedgerEntity::get).invoke();
  }

  @Post("/assistants/{id}/runs")
  public Map<String, String> run(String id, RunRequest request) {
    var runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
    client.forWorkflow(runId).method(PlaybookRunWorkflow::start)
        .invoke(new PlaybookRunWorkflow.Start(id, request.steps(), request.failing(), request.by()));
    client.forKeyValueEntity("runs").method(IndexEntity::add).invoke(runId);
    return Map.of("runId", runId);
  }

  @Get("/runs/{id}")
  public PlaybookRunWorkflow.Run runState(String id) {
    return client.forWorkflow(id).method(PlaybookRunWorkflow::get).invoke();
  }

  private PlaybookView view(String id) {
    var playbook = client.forKeyValueEntity(id).method(AssistantEntity::get).invoke();
    var own = human.halt(id);
    var all = human.halt(ContextLayerSetup.ALL_PLAYBOOKS);
    var halt = own.halted() ? own.state() : all.halted() ? all.state() : null;
    return new PlaybookView(playbook, halt != null, halt);
  }
}
