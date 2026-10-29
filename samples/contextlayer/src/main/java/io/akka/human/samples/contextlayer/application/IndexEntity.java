package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * The ids of one kind of thing, in the order they were added, so the read routes can list them: the
 * id is the kind (assistants, actions, links, connectors, questions, cases, runs).
 */
@Component(id = "context-index")
public class IndexEntity extends KeyValueEntity<IndexEntity.Ids> {

  public record Ids(List<String> ids) {
    public Ids {
      ids = ids == null ? List.of() : List.copyOf(ids);
    }
  }

  @Override
  public Ids emptyState() {
    return new Ids(List.of());
  }

  public Effect<Ids> add(String id) {
    if (currentState().ids().contains(id)) return effects().reply(currentState());
    var next = new ArrayList<>(currentState().ids());
    next.add(id);
    return effects().updateState(new Ids(next)).thenReply(new Ids(next));
  }

  public ReadOnlyEffect<Ids> list() {
    return effects().reply(currentState());
  }
}
