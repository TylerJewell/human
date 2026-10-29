package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * A connector pack and the versions imported of it. A version goes SHADOW or LIVE only on an approver
 * who did not import it (custom-cl0006); taking the pack out of service is an approver's decision too
 * (custom-cl0041).
 */
@Component(id = "context-connector")
public class ConnectorPackEntity extends KeyValueEntity<ConnectorPackEntity.Pack> {

  public record Version(String hash, String importedBy) {}

  /** {@code mode}: INACTIVE, SHADOW or LIVE. */
  public record Pack(String name, List<Version> versions, String mode, String activeHash, String changedBy, String reason) {
    public Pack {
      versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public Version latest() {
      return versions.isEmpty() ? null : versions.getLast();
    }
  }

  public record Import(String hash, String by) {}

  public record SetMode(String mode, String hash, String by, String reason) {}

  public Effect<Pack> importVersion(Import command) {
    var current = currentState();
    var versions = new ArrayList<Version>(current == null ? List.of() : current.versions());
    versions.removeIf(v -> v.hash().equals(command.hash()));
    versions.add(new Version(command.hash(), command.by()));
    var next = current == null
        ? new Pack(commandContext().entityId(), versions, "INACTIVE", null, command.by(), null)
        : new Pack(current.name(), versions, current.mode(), current.activeHash(), current.changedBy(), current.reason());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Pack> setMode(SetMode command) {
    var c = currentState();
    if (c == null) return effects().error("no such connector pack");
    var next = new Pack(c.name(), c.versions(), command.mode(), command.mode().equals("INACTIVE") ? null : command.hash(),
        command.by(), command.reason());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Pack> get() {
    if (currentState() == null) return effects().error("no such connector pack");
    return effects().reply(currentState());
  }
}
