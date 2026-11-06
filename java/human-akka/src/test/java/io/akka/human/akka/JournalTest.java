package io.akka.human.akka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.annotations.Migration;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.impl.serialization.JsonSerializer;
import akka.runtime.sdk.spi.BytesPayload;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.human.api.Action;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.HumanEventEvent;
import io.akka.human.engine.State;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The event entity's journal as Akka's serializer reads it. The fixture holds entries as the first
 * release wrote them, under the events' class names, with the settings as they were then.
 */
public class JournalTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURE = "journal/human-event-v0.json";

  private record Entry(String entityId, String contentType, JsonNode payload) {
    BytesPayload bytes() {
      try {
        return new BytesPayload(ByteString.fromArray(MAPPER.writeValueAsBytes(payload)), contentType);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Test
  public void everyEventHasAStableTypeName() {
    var names = new HashSet<String>();
    for (var type : HumanEventEvent.class.getPermittedSubclasses()) {
      var name = type.getAnnotation(TypeName.class);
      assertNotNull(name, type.getSimpleName() + " has no type name");
      assertTrue(name.value().startsWith("human-event-"), name.value());
      assertTrue(names.add(name.value()), name.value() + " is used twice");
      assertNotNull(type.getAnnotation(Migration.class), type.getSimpleName() + " cannot read entries under its class name");
    }
    assertEquals(19, names.size());
  }

  @Test
  public void replaysTheJournalTheFirstReleaseWrote() {
    var serializer = serializer();
    var types = new HashSet<Class<?>>();
    var events = new LinkedHashMap<String, HumanEvent>();
    for (var entry : journal()) {
      var event = assertInstanceOf(HumanEventEvent.class, serializer.fromBytes(entry.bytes()));
      assertTrue(entry.contentType().endsWith("$" + event.getClass().getSimpleName()), entry.contentType());
      types.add(event.getClass());
      events.put(entry.entityId(), event instanceof HumanEventEvent.Raised raised
          ? raised.event() : events.get(entry.entityId()).apply(event));
    }
    assertEquals(19, types.size());

    var payment = events.get("he-20261030-a1b2c3d4");
    assertEquals(State.APPLIED, payment.state());
    assertEquals(Action.APPROVE, payment.outcome().action());
    assertEquals("Pay only once PO-12 is signed", payment.outcome().conditions().getFirst().get("text"));
    assertEquals(2, payment.trigger().occurrences());
    assertEquals(1, payment.infoRequests().size());
    assertEquals(1, payment.slaExtensions().size());
    assertEquals("resumed payment pay-7731", payment.outcome().applied());
    assertEquals(10, payment.settings().minimumReviewSeconds());
    assertEquals(List.of("eu-ai-act-oversight"), payment.settings().routing().attestations());
    assertFalse(payment.settings().blockDecisions(), "the first release had no block_decisions");

    var rollout = events.get("he-20261030-b5e6f7a8");
    assertEquals(State.REOPENED, rollout.state());
    assertEquals("reopened-as", rollout.related().getLast().relation());
    assertEquals("TWO_PERSON", rollout.settings().routing().reviewMode());
    assertEquals(List.of("a.devos@example.com"), rollout.decisions().stream().map(v -> v.reviewer().subject()).toList());
    assertEquals(List.of("n.haddad@example.com"), rollout.recused());
    assertEquals("no resumer is registered for rollout", rollout.outcome().applyError());

    var standing = events.get("he-20261030-c9d0e1f2");
    assertTrue(standing.outcome().byStanding());
    assertEquals(State.DECIDED, standing.state());

    var restart = events.get("he-20261030-d3e4f5a6");
    assertEquals(State.DECIDED, restart.state());
    assertEquals(List.of(Map.of("name", "backlog drained", "passed", true)), restart.subject().detail().get("safeStateChecks"));
  }

  @Test
  public void writesUnderTheTypeNamesAndReadsItsOwnEntries() {
    var serializer = serializer();
    for (var entry : journal()) {
      var event = serializer.fromBytes(entry.bytes());
      var name = event.getClass().getAnnotation(TypeName.class).value();
      var written = serializer.toBytes(event);
      assertEquals("json.akka.io/" + name, written.contentType());
      assertEquals(event, serializer.fromBytes(written));
    }
  }

  @Test
  public void aRaisedEventReadsAfterFieldsAreAddedOrRemoved() {
    var raised = journal().stream().filter(e -> e.contentType().endsWith("$Raised")).findFirst().orElseThrow();
    var payload = raised.payload().deepCopy();
    var event = (ObjectNode) payload.get("event");
    var settings = (ObjectNode) event.get("settings");
    assertFalse(settings.has("blockDecisions"), "the fixture predates blockDecisions");
    settings.put("retiredSetting", "kept in the journal, unknown to the code");
    settings.remove("aiRecommendation");
    event.putObject("retiredBlock").put("of", "an older release");
    event.remove("requested");

    var read = assertInstanceOf(HumanEventEvent.Raised.class,
        serializer().fromBytes(new Entry(raised.entityId(), raised.contentType(), payload).bytes()));
    assertEquals("pt-00001", read.event().settings().control().rowId());
    assertEquals(State.OPEN, read.event().state());
    assertEquals(10, read.event().settings().minimumReviewSeconds());
    assertFalse(read.event().settings().blockDecisions());
    assertEquals(null, read.event().settings().aiRecommendation());
  }

  /** The serializer as the SDK sets it up for the entity: every event type registered by its type hints. */
  private static JsonSerializer serializer() {
    var serializer = new JsonSerializer();
    for (var type : HumanEventEvent.class.getPermittedSubclasses()) serializer.registerTypeHints(type);
    return serializer;
  }

  private static List<Entry> journal() {
    try (InputStream in = JournalTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
      var entries = new ArrayList<Entry>();
      for (var e : MAPPER.readTree(in).get("journal")) {
        entries.add(new Entry(e.get("entityId").asText(), e.get("contentType").asText(), e.get("payload")));
      }
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
