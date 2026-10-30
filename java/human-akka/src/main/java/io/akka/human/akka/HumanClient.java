package io.akka.human.akka;

import akka.javasdk.client.ComponentClient;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Pattern;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Raised;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.ControlSettings;
import io.akka.human.engine.HaltState;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.Proofs;
import io.akka.human.engine.Qualification;
import io.akka.human.engine.Raise;
import io.akka.human.engine.Refusal;
import io.akka.human.engine.Rules;
import io.akka.human.engine.Verdicts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * {@link Human} over a component client: raising, following and closing events from service code,
 * and the acts a person starts (halts, restarts, flags, contests, overrides). The endpoint uses the
 * same methods, so a call from code and a call over HTTP follow one path.
 *
 * <p>Calls block on the component client, which is safe on the virtual threads components run on;
 * the {@link Human} methods answer completed stages.
 */
public final class HumanClient implements Human {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_GENERATIONS = 10_000;

  private final ComponentClient client;
  private final HumanLibrary library;

  public HumanClient(ComponentClient client, HumanPlugins plugins) {
    this.client = client;
    this.library = HumanLibrary.of(plugins);
  }

  // ---- Human

  @Override
  public CompletionStage<Raised> raise(RaiseRequest request) {
    return run(() -> {
      var answer = open(request);
      if (answer.refused()) throw new IllegalArgumentException(answer.refusal().error());
      var event = answer.event();
      var settled = event.outcome() != null && event.outcome().byStanding() ? Optional.of(decision(event))
          : Optional.<Decision>empty();
      return new Raised(event.id(), event.state().name(), event.trigger().occurrences(), settled);
    });
  }

  @Override
  public CompletionStage<Optional<Decision>> outcome(String eventId) {
    return run(() -> {
      var event = find(eventId).event();
      return event == null || event.pending() ? Optional.empty() : Optional.of(decision(event));
    });
  }

  @Override
  public CompletionStage<Void> withdraw(String eventId, Reviewer proposer, String reason) {
    return run(() -> {
      var answer = client.forEventSourcedEntity(eventId).method(HumanEventEntity::withdraw)
          .invoke(new HumanEventEntity.ByProposer(proposer, reason, library.now()));
      if (answer.refused()) throw new IllegalStateException(answer.refusal().error());
      return null;
    });
  }

  @Override
  public CompletionStage<Void> supersede(String eventId, String reason) {
    return run(() -> {
      var answer = client.forEventSourcedEntity(eventId).method(HumanEventEntity::supersede)
          .invoke(new HumanEventEntity.Close("system:superseded", reason, library.now()));
      if (answer.refused()) throw new IllegalStateException(answer.refusal().error());
      return null;
    });
  }

  @Override
  public CompletionStage<Boolean> halted(String target) {
    return run(() -> halt(target).halted());
  }

  // ---- raising

  /**
   * Raises an event, or adds an occurrence to the pending event with the same dedupe key. An active
   * standing decision on the control and match key settles a new event at once.
   */
  public HumanEventEntity.Answer open(RaiseRequest request) {
    var settings = library.controls().find(request.control()).orElse(null);
    if (settings == null) return refused(Refusal.notFound("control " + request.control() + " is not declared"));
    var problem = Raise.check(settings, request);
    if (problem != null) return refused(problem);
    var now = library.now();
    var standing = standingFor(settings, request, now);
    if (request.dedupeKey() == null) return raiseAs(newId(now), settings, request, standing, now);
    for (int generation = 1; generation <= MAX_GENERATIONS; generation++) {
      var answer = raiseAs(dedupeId(settings, request.dedupeKey(), generation), settings, request, standing, now);
      if (!answer.refused() || answer.refusal().kind() != Refusal.Kind.CONFLICT) return answer;
    }
    return refused(Refusal.conflict("too many events closed under dedupe key " + request.dedupeKey(), null));
  }

  private HumanEventEntity.Answer raiseAs(String id, ControlSettings settings, RaiseRequest request,
      StandingDecisionsEntity.Found standing, Instant now) {
    var event = Raise.event(id, settings, request, library.context(), now);
    var decision = standing == null ? null : standing.decision();
    return client.forEventSourcedEntity(id).method(HumanEventEntity::raise).invoke(new HumanEventEntity.Raise(event,
        decision == null ? null : decision.id(), decision == null ? null : decision.action()));
  }

  private StandingDecisionsEntity.Found standingFor(ControlSettings settings, RaiseRequest request, Instant now) {
    if (request.matchKey() == null && request.subject().sessionId() == null) return null;
    return client.forKeyValueEntity(StandingDecisionsEntity.ID).method(StandingDecisionsEntity::match)
        .invoke(new StandingDecisionsEntity.Match(settings.control().rowId(), request.matchKey(),
            request.subject().sessionId(), now));
  }

  // ---- reading

  public HumanEventEntity.Answer find(String eventId) {
    return client.forEventSourcedEntity(eventId).method(HumanEventEntity::get).invoke();
  }

  public HaltEntity.Current halt(String target) {
    return client.forKeyValueEntity(target).method(HaltEntity::get).invoke();
  }

  /** The final outcome as whoever waits hears it. */
  public static Decision decision(HumanEvent e) {
    var o = e.outcome();
    var verdict = Verdicts.of(e);
    return new Decision(e.id(), e.settings().control().rowId(), e.settings().pattern(), o.action(), o.edits(),
        o.replacement(), o.rationale(), e.deciders(), o.sticky(), o.slaOutcome(),
        verdict == null ? null : verdict.conclusion(), o.standingDecisionId());
  }

  // ---- acts a person starts

  /**
   * A halt: one authorised person exercises it at once. A restart ({@code lift}) raises an event under
   * the control's review mode, which can be approved only while every safe-state check passes; the
   * person who asks for it proposes it, so the control's independence rules bar them from approving it.
   *
   * @param target the service or capability to stop or restart; null takes the control's halt_config target
   */
  public HumanEventEntity.Answer halt(Reviewer reviewer, String control, String target, boolean lift, String reason) {
    var named = library.controls().find(control).orElse(null);
    if (named == null) return refused(Refusal.notFound("control " + control + " is not declared"));
    if (!named.haltRestart()) return refused(Refusal.invalid(control + " is not a halt-restart control"));
    var settings = library.controls().haltControl(named, lift).orElse(null);
    if (settings == null) {
      return refused(Refusal.invalid("no halt-restart control of " + named.haltTarget() + " allows "
          + (lift ? "APPROVE, so none restarts it" : "HALT, so none halts it")));
    }
    if (blank(reason)) return refused(Refusal.invalid("a reason is required"));
    var haltTarget = blank(target) ? named.haltTarget() : target;
    var current = halt(haltTarget);
    var now = library.now();
    var scope = library.haltScope(haltTarget, settings);
    var checks = scope.safeStateChecks(haltTarget).toCompletableFuture().join();
    var subject = Subject.system((lift ? "Restart " : "Halt ") + haltTarget, settings.haltScope(), haltTarget,
            settings.haltAffectedCalls(), lift)
        .with("halted", current.halted())
        .with("safeStateChecks", checks.entrySet().stream()
            .map(c -> (Object) Subject.fields("name", c.getKey(), "passed", c.getValue())).toList());
    if (scope.callsPerMinute(haltTarget) != null) subject = subject.with("callsPerMinute", scope.callsPerMinute(haltTarget));
    var under = settings.control().rowId();
    if (!lift) {
      if (current.halted()) {
        return refused(Refusal.conflict(haltTarget + " is already halted", "DECIDED"));
      }
      var actor = library.actor(reviewer);
      var refusal = Qualification.refusal(settings, settings.exercisers(), actor, Action.HALT, now);
      if (refusal != null) return refused(Refusal.forbidden(refusal));
      var raised = open(RaiseRequest.of(Pattern.HALT_RESTART, "Halt " + haltTarget + " now?").control(under)
          .subject(subject).trigger(new Trigger("person", null, reason, List.of(), List.of())).build());
      if (raised.refused()) return raised;
      var id = raised.event().id();
      var decided = client.forEventSourcedEntity(id).method(HumanEventEntity::decide).invoke(new HumanEventEntity.Decide(
          actor, new Rules.DecisionRequest(reviewer, Action.HALT, reason, null, null, null, List.of(), null), Map.of(), now));
      if (decided.refused()) return decided;
      client.forKeyValueEntity(haltTarget).method(HaltEntity::exercise).invoke(new HaltEntity.Exercise(
          HaltState.notHalted(settings, haltTarget), reviewer.subject(), reason, id, Proofs.haltRecordId(id), now));
      scope.halted(haltTarget, reason).toCompletableFuture().join();
      return decided;
    }
    if (!current.halted()) return refused(Refusal.conflict(haltTarget + " is not halted", "APPLIED"));
    var pendingLift = current.state().liftEventId() == null ? null : find(current.state().liftEventId()).event();
    if (pendingLift != null && pendingLift.pending()) return new HumanEventEntity.Answer(pendingLift, null);
    var raised = open(RaiseRequest.of(Pattern.HALT_RESTART, "Restart " + haltTarget + "?").control(under)
        .subject(subject.with("haltEventId", current.state().eventId()))
        .check("Every safe-state check passes.", "subject")
        .trigger(new Trigger("person", null, reviewer.subject() + " asks to restart: " + reason, List.of(), List.of()))
        .proposedBy(reviewer.subject())
        .build());
    if (!raised.refused()) {
      client.forKeyValueEntity(haltTarget).method(HaltEntity::liftRaised).invoke(raised.event().id());
    }
    return raised;
  }

  /** Someone marks an output as wrong; a triager accepts it as an evaluation case or dismisses it. */
  public HumanEventEntity.Answer flag(Reviewer reviewer, String note, String flowId, String sessionId, String output,
      String control) {
    var settings = control == null
        ? library.controls().firstOf(Pattern.FLAG_FEEDBACK).orElse(null)
        : library.controls().find(control).orElse(null);
    if (settings == null) return refused(Refusal.notFound("no flag-feedback control " + (control == null ? "" : control)));
    if (blank(note)) return refused(Refusal.invalid("say what is wrong"));
    var subject = Subject.output("Flagged: " + shorten(note), output == null ? "(the output was not given)" : output)
        .with("flag", Subject.fields("by", reviewer.subject(), "note", note))
        .with("flowId", flowId)
        .withFlow(flowId)
        .withSession(sessionId);
    return open(RaiseRequest.of(Pattern.FLAG_FEEDBACK, "Is this output wrong, and should it become an evaluation case?")
        .control(settings.control().rowId()).subject(subject)
        .trigger(new Trigger("person", null, note, List.of(), List.of())).proposedBy(reviewer.subject()).build());
  }

  /** The person affected by a decision asks for a human review of it. */
  public HumanEventEntity.Answer contest(Reviewer reviewer, String control, String ref, String grounds, String appellant) {
    var settings = library.controls().find(control).orElse(null);
    if (settings == null) return refused(Refusal.notFound("control " + control + " is not declared"));
    if (settings.pattern() != Pattern.CONTEST_APPEAL) return refused(Refusal.invalid(control + " is not a contest-appeal control"));
    if (blank(ref)) return refused(Refusal.invalid("say which decision is contested (ref)"));
    if (blank(grounds)) return refused(Refusal.invalid("the grounds are required"));
    var contested = eventIfOwned(ref);
    var original = new LinkedHashMap<String, Object>();
    original.put("ref", ref);
    String firstReviewer = null;
    if (contested != null && contested.outcome() != null) {
      original.put("decision", contested.outcome().action() == null ? "none" : contested.outcome().action().name());
      original.put("summary", contested.subject().summary());
      if (!contested.deciders().isEmpty()) {
        firstReviewer = contested.deciders().getLast();
        original.put("decidedBy", firstReviewer);
      }
      original.put("decidedAt", contested.closedAt().toString());
    } else {
      original.put("decision", "unknown");
      original.put("summary", ref);
    }
    var who = appellant == null ? reviewer.subject() : appellant;
    // The person hearing the appeal reads what was decided; the ref stays on the subject.
    var about = "“" + original.get("summary") + "”";
    var subject = Subject.appeal("Appeal against " + about, original, grounds)
        .with("appellant", Subject.fields("ref", who))
        .with("filedAt", library.now().toString())
        .withRef(ref);
    return open(RaiseRequest.of(Pattern.CONTEST_APPEAL, "Should the decision on " + about + " stand?").control(control)
        .subject(subject).trigger(new Trigger("person", null, grounds, List.of(), List.of()))
        .proposedBy(who).firstReviewer(firstReviewer).build());
  }

  /** A person replaces, sets aside or reverses a system output, with a reason; decided at once. */
  public HumanEventEntity.Answer override(Reviewer reviewer, String control, String ref, Action action, String rationale,
      String replacement) {
    var settings = library.controls().find(control).orElse(null);
    if (settings == null) return refused(Refusal.notFound("control " + control + " is not declared"));
    if (settings.pattern() != Pattern.OVERRIDE) return refused(Refusal.invalid(control + " is not an override control"));
    if (blank(ref)) return refused(Refusal.invalid("say which output is overridden (ref)"));
    if (action == null || !settings.allowedActions().contains(action)) {
      return refused(Refusal.invalid("the action must be one of " + settings.allowedActions()));
    }
    if (settings.rationaleRequired() && blank(rationale)) return refused(Refusal.invalid("a rationale is required"));
    if (action == Action.OVERRIDE && blank(replacement)) return refused(Refusal.invalid("an override needs the replacement output"));
    var now = library.now();
    var actor = library.actor(reviewer);
    var refusal = Qualification.refusal(settings, settings.rolesAt(0), actor, action, now);
    if (refusal != null) return refused(Refusal.forbidden(refusal));
    var overridden = eventIfOwned(ref);
    var output = overridden == null ? ref : String.valueOf(overridden.subject().detail().getOrDefault("output",
        overridden.subject().summary()));
    var subject = Subject.output(action.name().charAt(0) + action.name().substring(1).toLowerCase() + " of " + ref, output)
        .with("replacement", replacement)
        .with("decision", Subject.fields("ref", ref, "summary", overridden == null ? ref : overridden.subject().summary()))
        .withRef(ref);
    var raised = open(RaiseRequest.of(Pattern.OVERRIDE, "Should the output of " + ref + " be set aside?").control(control)
        .subject(subject).trigger(new Trigger("person", null, rationale, List.of(), List.of())).build());
    if (raised.refused()) return raised;
    return client.forEventSourcedEntity(raised.event().id()).method(HumanEventEntity::decide).invoke(
        new HumanEventEntity.Decide(actor, new Rules.DecisionRequest(reviewer, action, rationale, null, null, null,
            List.of(), replacement), Map.of(), now));
  }

  /**
   * The accountable owner reopens a closed event: it becomes REOPENED and a new event, with the id
   * {@code <id>-r}, takes the question up again under the control's current settings. Answers the
   * reopened event.
   */
  public HumanEventEntity.Answer reopen(Reviewer reviewer, String eventId, String reason) {
    var newId = eventId + "-r";
    var answer = client.forEventSourcedEntity(eventId).method(HumanEventEntity::reopen)
        .invoke(new HumanEventEntity.Reopen(reviewer, reason, newId, library.now()));
    if (answer.refused()) return answer;
    var old = answer.event();
    var settings = library.controls().find(old.settings().control().rowId()).orElse(old.settings());
    var request = new RaiseRequest(null, old.settings().control().rowId(), old.ask(), old.subject(),
        new Trigger("person", null, reviewer.subject() + " reopened " + eventId + ": " + reason, List.of(), List.of()),
        old.proposedBy(), old.builtBy(), old.firstReviewer(), null, old.matchKey(), old.aiRecommendation(), old.effects(),
        old.evidence().stream().map(HumanClient::strings).toList(),
        List.of(Map.of("eventId", eventId, "relation", "reopens")), null, old.simulated());
    var now = library.now();
    client.forEventSourcedEntity(newId).method(HumanEventEntity::raise)
        .invoke(new HumanEventEntity.Raise(Raise.event(newId, settings, request, library.context(), now), null, null));
    return answer;
  }

  // ---- internals

  private static Map<String, String> strings(Map<String, Object> map) {
    var out = new LinkedHashMap<String, String>();
    map.forEach((k, v) -> out.put(k, String.valueOf(v)));
    return out;
  }

  private HumanEvent eventIfOwned(String id) {
    if (!id.startsWith("he-")) return null;
    return find(id).event();
  }

  private static HumanEventEntity.Answer refused(Refusal refusal) {
    return new HumanEventEntity.Answer(null, refusal);
  }

  static String newId(Instant now) {
    var bytes = new byte[4];
    RANDOM.nextBytes(bytes);
    return "he-" + DAY.format(now) + "-" + HexFormat.of().formatHex(bytes);
  }

  /** The same key on the same control names the same event, and the next generation once it closes. */
  static String dedupeId(ControlSettings settings, String key, int generation) {
    try {
      var hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      var base = "he-" + settings.control().rowId() + "-" + HexFormat.of().formatHex(hash).substring(0, 12);
      return generation == 1 ? base : base + "-" + generation;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String shorten(String s) {
    return s.length() <= 80 ? s : s.substring(0, 77) + "...";
  }

  private static <T> CompletionStage<T> run(Supplier<T> work) {
    try {
      return CompletableFuture.completedFuture(work.get());
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
