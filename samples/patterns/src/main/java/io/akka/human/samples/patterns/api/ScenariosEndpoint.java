package io.akka.human.samples.patterns.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanEventEntity;
import io.akka.human.akka.HumanTasks;
import io.akka.human.api.Action;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.local.ApprovalGuardrail;
import io.akka.human.samples.patterns.PatternsSetup;
import io.akka.human.samples.patterns.application.AttestationSchedule;
import io.akka.human.samples.patterns.application.HandOffWorkflow;
import io.akka.human.samples.patterns.application.NoticeWorkflow;
import io.akka.human.samples.patterns.application.PaymentWorkflow;
import io.akka.human.samples.patterns.application.QualityMonitor;
import io.akka.human.samples.patterns.application.RecordsEntity;
import io.akka.human.samples.patterns.application.ReferralMonitor;
import io.akka.human.samples.patterns.application.RolloutWorkflow;
import io.akka.human.samples.patterns.application.SafeStateEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The domain side of each pattern: the calls that make the service raise its fourteen controls the
 * way each pattern is used. People then decide the events through {@code /oversight}. See the README
 * for each route.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class ScenariosEndpoint extends AbstractHttpEndpoint {

  public record Started(String id) {}

  public record Raised(String eventId, String state) {}

  public record PaymentRequest(String supplier, int amount, String currency, boolean resultUnknown) {}

  public record NoticeRequest(String applicant, String text, String decisionRef) {}

  public record RolloutRequest(String model, String from, String to, String proposedBy, String builtBy, double trafficShare) {}

  public record HandOffRequest(String channel, String reason, List<String> userSaid) {}

  public record WaiverRequest(String ruleId, String ruleText, String scope, String until, String requestedBy,
      String justification, List<String> compensating) {}

  public record MatchRequest(String entityType, Map<String, Object> left, Map<String, Object> right, String loadedBy,
      double score) {}

  public record ContestRequest(String appellant, String grounds) {}

  public record OverrideRequest(Reviewer reviewer, String action, String rationale, String replacement) {}

  public record FlagRequest(String by, String note, String output) {}

  public record Reading(double referralShare) {}

  public record SafeState(boolean backlogDrained) {}

  public record AgentApprovalRequest(String agentId, String tool, Map<String, Object> arguments) {}

  public record AgentApproval(String taskId, String eventId) {}

  public record TaskState(String taskId, String status, Object result, String failureReason) {}

  public record ToolCall(String agentId, String sessionId, String tool, String arguments) {}

  public record ToolCallDecision(String decision, String reason) {}

  private final ComponentClient client;
  private final HumanClient human;
  private final TimerScheduler timers;

  public ScenariosEndpoint(ComponentClient client, HumanClient human, TimerScheduler timers) {
    this.client = client;
    this.human = human;
    this.timers = timers;
  }

  // ---- waiting for a person: workflows

  @Post("/payments")
  public Started pay(PaymentRequest request) {
    var id = "pay-" + UUID.randomUUID();
    client.forWorkflow(id).method(PaymentWorkflow::start).invoke(new PaymentWorkflow.Start(request.supplier(),
        request.amount(), request.currency() == null ? "EUR" : request.currency(), request.resultUnknown()));
    return new Started(id);
  }

  @Get("/payments/{id}")
  public PaymentWorkflow.Payment payment(String id) {
    return client.forWorkflow(id).method(PaymentWorkflow::get).invoke();
  }

  @Post("/notices")
  public Started notice(NoticeRequest request) {
    var id = "notice-" + UUID.randomUUID();
    client.forWorkflow(id).method(NoticeWorkflow::start)
        .invoke(new NoticeWorkflow.Start(request.applicant(), request.text(), request.decisionRef()));
    return new Started(id);
  }

  @Get("/notices/{id}")
  public NoticeWorkflow.Notice notice(String id) {
    return client.forWorkflow(id).method(NoticeWorkflow::get).invoke();
  }

  @Post("/rollouts")
  public Started rollout(RolloutRequest request) {
    var id = "rollout-" + UUID.randomUUID();
    client.forWorkflow(id).method(RolloutWorkflow::start).invoke(new RolloutWorkflow.Start(request.model(),
        request.from(), request.to(), request.proposedBy(), request.builtBy(), request.trafficShare()));
    return new Started(id);
  }

  @Get("/rollouts/{id}")
  public RolloutWorkflow.Rollout rollout(String id) {
    return client.forWorkflow(id).method(RolloutWorkflow::get).invoke();
  }

  @Post("/handoffs")
  public Started handOff(HandOffRequest request) {
    var id = "conversation-" + UUID.randomUUID();
    client.forWorkflow(id).method(HandOffWorkflow::start).invoke(new HandOffWorkflow.Start(request.channel(),
        request.reason() == null ? "user-asked" : request.reason(), request.userSaid()));
    return new Started(id);
  }

  @Get("/handoffs/{id}")
  public HandOffWorkflow.Conversation handOff(String id) {
    return client.forWorkflow(id).method(HandOffWorkflow::get).invoke();
  }

  // ---- waiting for a person: an apply handler carries the decision out

  @Post("/waivers")
  public Raised waiver(WaiverRequest request) {
    var id = "waiver-" + UUID.randomUUID();
    var until = Instant.parse(request.until());
    return raised(human.raise(Patterns.exception("Set aside " + request.ruleId() + " for " + request.scope() + " until "
            + request.until() + "?")
        .control("pt-0004")
        .subject(Subject.exception("Waive " + request.ruleId() + " for " + request.scope(), request.ruleId(),
                request.ruleText(), request.scope(), until)
            .with("requestedBy", request.requestedBy())
            .with("justification", request.justification())
            .with("from", Instant.now().toString())
            .with("compensating", request.compensating() == null ? List.of() : request.compensating())
            .withRef(id))
        .trigger(Trigger.rule("person", request.ruleId(), request.justification()))
        .proposedBy(request.requestedBy())
        .resumes(Resume.handler("waiver", id))
        .build()));
  }

  @Post("/matches")
  public Raised match(MatchRequest request) {
    var id = "match-" + UUID.randomUUID();
    return raised(human.raise(Patterns.matchAdjudication("Are these two " + request.entityType() + " records one?")
        .control("pt-0005")
        .subject(Subject.records("Possible duplicate " + request.entityType(), request.entityType(),
                side(request.left(), request.loadedBy()), side(request.right(), request.loadedBy()))
            .with("score", request.score()).with("method", "name and address similarity").withRef(id))
        .trigger(Trigger.rule("workflow", "match.score between 0.6 and 0.9", "The matcher could not decide."))
        .builtBy(request.loadedBy())
        .resumes(Resume.handler("match", id))
        .build()));
  }

  // ---- a person starts it

  @Post("/decisions/{ref}/contest")
  public HttpResponse contest(String ref, ContestRequest request) {
    var appellant = new Reviewer(request.appellant(), request.appellant(), List.of(), "patterns", false);
    return answer(human.contest(appellant, "pt-0007", ref, request.grounds(), request.appellant()));
  }

  @Post("/outputs/{ref}/override")
  public HttpResponse override(String ref, OverrideRequest request) {
    return answer(human.override(request.reviewer(), "pt-0008", ref, Action.valueOf(request.action()),
        request.rationale(), request.replacement()));
  }

  @Post("/replies/{flowId}/flag")
  public HttpResponse flag(String flowId, FlagRequest request) {
    var by = new Reviewer(request.by(), request.by(), List.of(), "patterns", false);
    return answer(human.flag(by, request.note(), flowId, null, request.output(), "pt-0009"));
  }

  // ---- raised and carried on

  @Post("/monitor/referral-share")
  public HttpResponse referralShare(Reading reading) {
    timers.createSingleTimer("referral-reading-" + UUID.randomUUID(), Duration.ZERO,
        client.forTimedAction().method(ReferralMonitor::check).deferred(new ReferralMonitor.Reading(reading.referralShare())));
    return HttpResponses.ok();
  }

  @Post("/attestations/run")
  public HttpResponse attest() {
    timers.createSingleTimer(AttestationSchedule.TIMER, Duration.ZERO,
        client.forTimedAction().method(AttestationSchedule::run).deferred());
    return HttpResponses.ok();
  }

  @Post("/quality/check")
  public HttpResponse checkQuality() {
    timers.createSingleTimer(QualityMonitor.TIMER, Duration.ZERO,
        client.forTimedAction().method(QualityMonitor::check).deferred());
    return HttpResponses.ok();
  }

  @Get("/records/{id}")
  public HttpResponse record(String id) {
    var found = client.forKeyValueEntity(id).method(RecordsEntity::get).invoke().record();
    return found == null ? HttpResponses.notFound() : HttpResponses.ok(found);
  }

  // ---- halt and restart of the eligibility capability

  @Put("/eligibility/safe-state")
  public SafeStateEntity.SafeState safeState(SafeState state) {
    return client.forKeyValueEntity(PatternsSetup.ELIGIBILITY).method(SafeStateEntity::set)
        .invoke(new SafeStateEntity.SafeState(state.backlogDrained()));
  }

  // ---- an autonomous agent's approval, and a tool call that needs one

  /** Creates the unassigned approval task an agent's work would depend on, and raises its event. */
  @Post("/agent-approvals")
  public AgentApproval agentApproval(AgentApprovalRequest request) {
    var taskId = "approval-" + UUID.randomUUID();
    client.forTask(taskId).create(HumanTasks.DECISION.instructions("A person approves the call to " + request.tool()));
    var raised = human.raise(Patterns.actionApproval("Should " + request.agentId() + " call " + request.tool() + "?")
        .control("pt-0001")
        .subject(Subject.action("Call " + request.tool(), request.tool(), request.arguments()).withAgent(request.agentId()))
        .trigger(Trigger.rule("tool-policy", request.tool(), request.agentId() + " asked to call " + request.tool()))
        .proposedBy("agent:" + request.agentId())
        .resumes(Resume.task(taskId))
        .build()).toCompletableFuture().join();
    return new AgentApproval(taskId, raised.eventId());
  }

  @Get("/agent-approvals/{taskId}")
  public TaskState agentApproval(String taskId) {
    var task = client.forTask(taskId).get(HumanTasks.DECISION);
    return new TaskState(taskId, task.status().name(), task.result().orElse(null), task.failureReason().orElse(null));
  }

  /** What the tool-call approval guardrail decides for this call, as an agent's tool loop would see it. */
  @Post("/tool-calls")
  public ToolCallDecision toolCall(ToolCall call) {
    var decision = new ApprovalGuardrail("pt-0001", true).decide(call.agentId(), call.sessionId(), call.tool(),
        call.arguments());
    return switch (decision) {
      case akka.javasdk.agent.Decision.Allow a -> new ToolCallDecision("allow", a.reason());
      case akka.javasdk.agent.Decision.Deny d -> new ToolCallDecision("deny", d.reason());
      case akka.javasdk.agent.Decision.Fail f -> new ToolCallDecision("fail", f.reason());
      default -> new ToolCallDecision("fail", "unknown decision");
    };
  }

  // ---- internals

  private static Map<String, Object> side(Map<String, Object> record, String loadedBy) {
    return Subject.fields("source", record.getOrDefault("source", "crm"), "ref", record.getOrDefault("ref", "?"),
        "loadedBy", loadedBy, "fields", record.getOrDefault("fields", record));
  }

  private static Raised raised(java.util.concurrent.CompletionStage<io.akka.human.api.Raised> stage) {
    var raised = stage.toCompletableFuture().join();
    return new Raised(raised.eventId(), raised.state());
  }

  private static HttpResponse answer(HumanEventEntity.Answer answer) {
    if (answer.refused()) {
      return HttpResponse.create().withStatus(answer.refusal().status())
          .withEntity(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
              ("{\"error\":" + quote(answer.refusal().error()) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return HttpResponses.ok(new Raised(answer.event().id(), answer.event().state().name()));
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
