package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One conversation with the applicant support assistant: who it is about and what was said. */
@Component(id = "support-session")
public class SupportSessionEntity extends KeyValueEntity<SupportSessionEntity.Session> {

  /** @param role applicant or assistant */
  public record Line(Instant at, String role, String text) {}

  public record Session(String id, String applicationId, List<Line> transcript) {
    /** The assistant's last reply, or null before it has said anything. */
    public String latestReply() {
      return transcript.stream().filter(l -> l.role().equals("assistant")).reduce((a, b) -> b).map(Line::text).orElse(null);
    }
  }

  public record Turn(String applicationId, String asked, String replied) {}

  public Effect<Session> turn(Turn turn) {
    var now = Instant.now();
    var lines = new ArrayList<>(currentState() == null ? List.<Line>of() : currentState().transcript());
    lines.add(new Line(now, "applicant", turn.asked()));
    lines.add(new Line(now, "assistant", turn.replied()));
    var next = new Session(commandContext().entityId(), turn.applicationId(), List.copyOf(lines));
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Session> get() {
    if (currentState() == null) return effects().error("no such session");
    return effects().reply(currentState());
  }
}
