package io.akka.human.samples.contextlayer.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.human.akka.HumanEventEntity;
import io.akka.human.api.Raised;
import io.akka.human.samples.contextlayer.application.IndexEntity;
import java.util.List;

/** What the domain routes answer: the event a request raised, or why it was refused. */
final class Responses {

  /** The event a request raised: its id and state. */
  record RaisedEvent(String eventId, String state) {}

  record Refused(String error) {}

  private Responses() {}

  static HttpResponse raised(Raised raised) {
    return HttpResponses.ok(new RaisedEvent(raised.eventId(), raised.state()));
  }

  static HttpResponse answer(HumanEventEntity.Answer answer) {
    if (answer.refused()) {
      return HttpResponses.of(StatusCodes.get(answer.refusal().status()), akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
          akka.javasdk.JsonSupport.encodeToAkkaByteString(new Refused(answer.refusal().error())).toArray());
    }
    return HttpResponses.ok(new RaisedEvent(answer.event().id(), answer.event().state().name()));
  }

  static HttpResponse unprocessable(String error) {
    return HttpResponses.of(StatusCodes.UNPROCESSABLE_ENTITY, akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
        akka.javasdk.JsonSupport.encodeToAkkaByteString(new Refused(error)).toArray());
  }

  static List<String> ids(ComponentClient client, String kind) {
    return client.forKeyValueEntity(kind).method(IndexEntity::list).invoke().ids();
  }
}
