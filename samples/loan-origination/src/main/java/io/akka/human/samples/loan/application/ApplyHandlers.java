package io.akka.human.samples.loan.application;

import akka.javasdk.client.ComponentClient;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Patterns;
import io.akka.human.api.Relations;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.api.spi.ApplyHandler;
import io.akka.human.samples.loan.domain.EligibilityAssessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** How the decisions nothing waits on are carried out. Each answers a line saying what was done. */
public final class ApplyHandlers {

  /** The control whose halt a HALT on referral drift proposes. */
  public static final String HALT_CONTROL = "custom-d04d2229e3f9";
  public static final String TEMPLATE = "template";
  public static final String WRITER_MODEL = "notice-writer-model";

  private ApplyHandlers() {}

  /**
   * S2, S3, S4: records what was done with a monitor's event on the monitor (the id is its key). A HALT
   * on referral drift raises a halt of the eligibility assessment for an oversight or safety officer
   * to exercise.
   */
  public static ApplyHandler monitors(ComponentClient client, Supplier<HumanClient> human) {
    return (key, subject, d) -> {
      var answer = d.action() == Action.HALT ? "halt proposed in " + proposeHalt(human.get(), d)
          : d.action() == null ? "unanswered (" + d.slaOutcome() + ")"
          : d.action() == Action.APPROVE ? (key.startsWith(Monitors.RUSHED) ? "reset" : "acknowledged")
          : d.action() == Action.EDIT ? "new minimum review time asked: " + edited(d, "/minimumReviewSeconds")
          : d.action().name().toLowerCase();
      var line = answer + by(d);
      return client.forKeyValueEntity(key).method(MonitorEntity::answered).invokeAsync(line)
          .thenApply(m -> key + " " + line);
    };
  }

  /** S7, for every flag-feedback event: an accepted flag adds the reply to the assistant's eval set. */
  @SuppressWarnings("unchecked")
  public static ApplyHandler flags(ComponentClient client) {
    return (eventId, subject, d) -> {
      if (d.action() != Action.APPROVE) return done(d.unanswered() ? "flag unanswered" : "flag dismissed" + by(d));
      var flag = (Map<String, Object>) subject.detail().getOrDefault("flag", Map.of());
      var accepted = new EvalSetEntity.Case(d.eventId(), subject.sessionId(), String.valueOf(subject.detail().get("output")),
          String.valueOf(flag.get("by")), String.valueOf(flag.get("note")), decider(d));
      return client.forKeyValueEntity(EvalSetEntity.ID).method(EvalSetEntity::add).invokeAsync(accepted)
          .thenApply(set -> "added to the " + EvalSetEntity.ID + " eval set as case " + set.cases().size());
    };
  }

  /** The template review: an approved template is used for its decline reason (the id). */
  @SuppressWarnings("unchecked")
  public static ApplyHandler templates(ComponentClient client) {
    return (reason, subject, d) -> {
      if (!d.proceeds()) return done("the current " + reason + " template stays");
      var after = (Map<String, Object>) subject.detail().get("after");
      return client.forKeyValueEntity(NoticeWritingEntity.ID).method(NoticeWritingEntity::adoptTemplate)
          .invokeAsync(new NoticeWritingEntity.AdoptTemplate(reason, String.valueOf(after.get("template")), d.eventId()))
          .thenApply(w -> "the " + reason + " template is in use");
    };
  }

  /** S5: an authorised model change switches the notice writer to the new model. */
  @SuppressWarnings("unchecked")
  public static ApplyHandler writerModel(ComponentClient client) {
    return (changeId, subject, d) -> {
      if (!d.proceeds()) return done("the notice writer keeps its model");
      var model = String.valueOf(((Map<String, Object>) subject.detail().get("after")).get("model"));
      return client.forKeyValueEntity(NoticeWritingEntity.ID).method(NoticeWritingEntity::adoptModel)
          .invokeAsync(new NoticeWritingEntity.AdoptModel(model, d.eventId()))
          .thenApply(w -> "the notice writer runs " + model);
    };
  }

  private static String proposeHalt(HumanClient human, Decision d) {
    var by = decider(d);
    return human.raise(Patterns.haltRestart("Halt the automated eligibility assessment?")
        .control(HALT_CONTROL)
        .subject(Subject.system("Halt " + EligibilityAssessor.CAPABILITY, "capability", EligibilityAssessor.CAPABILITY,
            "all-runtime-operations", false))
        .trigger(new Trigger("person", null, by + " proposed a halt on the referral drift: " + d.rationale(), List.of(),
            List.of()))
        .proposedBy(by)
        .related(d.eventId(), Relations.PROPOSED_BY)
        .dedupeKey("halt-proposed-in-" + d.eventId())
        .effect(Action.HALT, "The automated assessment stops; every application goes to an underwriter.")
        .build()).toCompletableFuture().join().eventId();
  }

  @SuppressWarnings("unchecked")
  private static Object edited(Decision d, String path) {
    if (d.edits().get("patch") instanceof List<?> patch) {
      for (var op : patch) {
        var change = (Map<String, Object>) op;
        if (path.equals(change.get("path"))) return change.get("value");
      }
    }
    return d.edits().getOrDefault("summary", "(not given)");
  }

  private static String decider(Decision d) {
    return d.deciders().isEmpty() ? "system" : d.deciders().getLast();
  }

  private static String by(Decision d) {
    return d.deciders().isEmpty() ? "" : " by " + decider(d);
  }

  private static CompletionStage<String> done(String line) {
    return CompletableFuture.completedFuture(line);
  }
}
