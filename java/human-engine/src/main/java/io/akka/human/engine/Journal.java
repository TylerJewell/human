package io.akka.human.engine;

import akka.javasdk.JsonMigration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * How the event entity's journal is read back. Every {@link HumanEventEvent} has a stable type name
 * and a migration below that names the class it was recorded under before it had one, so those
 * entries still resolve. The class names are written out, not derived, because the classes may move.
 *
 * <p>A raised event and the entity's snapshot each hold a whole {@link HumanEvent} document, with the
 * control's settings as they were when the event was raised; the journal keeps that document as
 * written, for audit. Both carry {@link #VERSION} in their content type ({@code human-event-raised#N},
 * none for version 0), and Akka passes an older document through {@link #upgrade} before binding it.
 * A change the binding reads correctly needs no step: a field the code no longer has is ignored, and a
 * field the document lacks reads as null, false or 0. Any other change (a rename, a new type, a removed
 * enum constant, a new field whose right value for older events is not that default) raises
 * {@link #VERSION} and adds the step from the version before to {@link #upgrade}.
 */
public final class Journal {

  /** Version 0 is the format of the first release. */
  public static final int VERSION = 0;

  private static final String RECORDED_AS = "io.akka.human.engine.HumanEventEvent$";

  private Journal() {}

  /** An event document written at {@code version}, brought up to {@link #VERSION}; one step per version. */
  static JsonNode upgrade(int version, JsonNode event) {
    return event;
  }

  /** {@link HumanEventEvent.Raised}: the event document it carries is upgraded. */
  public static final class RaisedFormat extends JsonMigration {

    @Override
    public int currentVersion() {
      return VERSION;
    }

    @Override
    public List<String> supportedClassNames() {
      return List.of(RECORDED_AS + "Raised");
    }

    @Override
    public JsonNode transform(int fromVersion, JsonNode json) {
      if (json instanceof ObjectNode raised && raised.has("event")) {
        raised.set("event", upgrade(fromVersion, raised.get("event")));
      }
      return json;
    }
  }

  /** The entity's snapshot: the event document itself. */
  public static final class SnapshotFormat extends JsonMigration {

    @Override
    public int currentVersion() {
      return VERSION;
    }

    @Override
    public JsonNode transform(int fromVersion, JsonNode json) {
      return upgrade(fromVersion, json);
    }
  }

  /** An event whose document has no version of its own, recorded under its class name before type names. */
  public abstract static class RecordedAs extends JsonMigration {

    private final String className;

    RecordedAs(String simpleName) {
      this.className = RECORDED_AS + simpleName;
    }

    @Override
    public int currentVersion() {
      return 0;
    }

    @Override
    public List<String> supportedClassNames() {
      return List.of(className);
    }
  }

  public static final class Repeated extends RecordedAs {
    public Repeated() {
      super("Repeated");
    }
  }

  public static final class StandingApplied extends RecordedAs {
    public StandingApplied() {
      super("StandingApplied");
    }
  }

  public static final class Claimed extends RecordedAs {
    public Claimed() {
      super("Claimed");
    }
  }

  public static final class Released extends RecordedAs {
    public Released() {
      super("Released");
    }
  }

  public static final class Voted extends RecordedAs {
    public Voted() {
      super("Voted");
    }
  }

  public static final class Decided extends RecordedAs {
    public Decided() {
      super("Decided");
    }
  }

  public static final class InfoRequested extends RecordedAs {
    public InfoRequested() {
      super("InfoRequested");
    }
  }

  public static final class Recused extends RecordedAs {
    public Recused() {
      super("Recused");
    }
  }

  public static final class Escalated extends RecordedAs {
    public Escalated() {
      super("Escalated");
    }
  }

  public static final class SentBack extends RecordedAs {
    public SentBack() {
      super("SentBack");
    }
  }

  public static final class Resubmitted extends RecordedAs {
    public Resubmitted() {
      super("Resubmitted");
    }
  }

  public static final class Extended extends RecordedAs {
    public Extended() {
      super("Extended");
    }
  }

  public static final class AtRisk extends RecordedAs {
    public AtRisk() {
      super("AtRisk");
    }
  }

  public static final class SafeStateChecked extends RecordedAs {
    public SafeStateChecked() {
      super("SafeStateChecked");
    }
  }

  public static final class Closed extends RecordedAs {
    public Closed() {
      super("Closed");
    }
  }

  public static final class Reopened extends RecordedAs {
    public Reopened() {
      super("Reopened");
    }
  }

  public static final class Applied extends RecordedAs {
    public Applied() {
      super("Applied");
    }
  }

  public static final class ApplyFailed extends RecordedAs {
    public ApplyFailed() {
      super("ApplyFailed");
    }
  }
}
