package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * A playbook ("assistant"): its releases and its stage. A release becomes runnable only once approved
 * (custom-cl0005); a stage changes only on an approved change (custom-cl0040), and SHADOW needs a release,
 * LIVE an approved one.
 */
@Component(id = "context-assistant")
public class AssistantEntity extends KeyValueEntity<AssistantEntity.Assistant> {

  public static final String DRAFT = "DRAFT";
  public static final String SHADOW = "SHADOW";
  public static final String LIVE = "LIVE";

  /** {@code status}: PROPOSED, APPROVED or REJECTED. */
  public record Release(String releaseId, String version, String status, String proposedBy, String decidedBy,
      String eventId) {}

  public record Assistant(String id, String name, String stage, List<Release> releases, String createdBy) {

    public Assistant {
      releases = releases == null ? List.of() : List.copyOf(releases);
    }

    public Release runnableRelease() {
      return releases.stream().filter(r -> r.status().equals("APPROVED")).reduce((a, b) -> b).orElse(null);
    }

    Assistant withReleases(List<Release> next) {
      return new Assistant(id, name, stage, next, createdBy);
    }
  }

  public record Create(String name, String by) {}

  public record Propose(String releaseId, String version, String by, String eventId) {}

  public record Decided(String key, boolean approved, String by) {}

  public Effect<Assistant> create(Create command) {
    if (currentState() != null) return effects().reply(currentState());
    var created = new Assistant(commandContext().entityId(), command.name(), DRAFT, List.of(), command.by());
    return effects().updateState(created).thenReply(created);
  }

  public Effect<Assistant> proposeRelease(Propose command) {
    if (currentState() == null) return effects().error("no such playbook");
    var releases = new ArrayList<>(currentState().releases());
    releases.removeIf(r -> r.releaseId().equals(command.releaseId()));
    releases.add(new Release(command.releaseId(), command.version(), "PROPOSED", command.by(), null, command.eventId()));
    var next = currentState().withReleases(releases);
    return effects().updateState(next).thenReply(next);
  }

  /** The decision on a proposed release; {@code key} is the release id. */
  public Effect<Assistant> releaseDecided(Decided command) {
    if (currentState() == null) return effects().error("no such playbook");
    var releases = currentState().releases().stream().map(r -> r.releaseId().equals(command.key()) && r.status().equals("PROPOSED")
        ? new Release(r.releaseId(), r.version(), command.approved() ? "APPROVED" : "REJECTED", r.proposedBy(),
            command.by(), r.eventId())
        : r).toList();
    var next = currentState().withReleases(releases);
    return effects().updateState(next).thenReply(next);
  }

  /** Why the playbook cannot move to {@code stage}, or null. */
  public static String stageProblem(Assistant assistant, String stage) {
    return switch (stage) {
      case DRAFT -> null;
      case SHADOW -> assistant.releases().isEmpty() ? "SHADOW needs a release" : null;
      case LIVE -> assistant.runnableRelease() == null ? "LIVE needs an approved release" : null;
      default -> "the stage is DRAFT, SHADOW or LIVE";
    };
  }

  /** An approved stage change; {@code key} is the stage. A change no longer allowed fails. */
  public Effect<Assistant> stageDecided(Decided command) {
    if (currentState() == null) return effects().error("no such playbook");
    if (!command.approved()) return effects().reply(currentState());
    var problem = stageProblem(currentState(), command.key());
    if (problem != null) return effects().error(problem);
    var a = currentState();
    var next = new Assistant(a.id(), a.name(), command.key(), a.releases(), a.createdBy());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Assistant> get() {
    if (currentState() == null) return effects().error("no such playbook");
    return effects().reply(currentState());
  }
}
