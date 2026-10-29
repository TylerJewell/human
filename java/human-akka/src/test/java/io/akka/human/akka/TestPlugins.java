package io.akka.human.akka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.api.spi.ProofLedger;
import io.akka.human.api.spi.ProofRecord;
import io.akka.human.engine.YamlCatalog;
import io.akka.human.engine.YamlOverseers;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The plug-ins the library's own tests run with, and what they record. */
final class TestPlugins {

  static final class Clock implements MovableClock {
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    @Override
    public Instant now() {
      return Instant.now().plus(offset.get());
    }

    @Override
    public Duration offset() {
      return offset.get();
    }

    @Override
    public void advance(Duration by) {
      offset.updateAndGet(o -> o.plus(by));
    }

    @Override
    public void reset() {
      offset.set(Duration.ZERO);
    }
  }

  static final class Ledger implements ProofLedger {
    final Map<String, ProofRecord> records = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> append(ProofRecord record) {
      records.putIfAbsent(record.recordId(), record);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<ProofRecord>> since(Instant since) {
      return CompletableFuture.completedFuture(records.values().stream().filter(r -> !r.recordedAt().isBefore(since)).toList());
    }
  }

  static final Clock CLOCK = new Clock();
  static final Ledger LEDGER = new Ledger();
  /** Each resumed workflow as "id:ACTION:slaOutcome". */
  static final List<String> RESUMED = new CopyOnWriteArrayList<>();
  /** Each applied flag, by event id. */
  static final List<String> EVAL_CASES = new CopyOnWriteArrayList<>();
  static final AtomicBoolean BACKLOG_DRAINED = new AtomicBoolean(false);

  static final HumanPlugins PLUGINS = HumanPlugins.builder("human-akka-test", catalog(),
          YamlOverseers.fromClasspath("spec/fixtures/overseers.yaml"))
      .environment("local")
      .clock(CLOCK)
      .ledger(LEDGER)
      .resumer("payment", (id, decision) -> {
        RESUMED.add(id + ":" + decision.action() + ":" + decision.slaOutcome());
        return CompletableFuture.completedFuture(null);
      })
      .handler("flag-feedback", (id, subject, decision) -> {
        EVAL_CASES.add(id);
        return CompletableFuture.completedFuture(decision.proceeds() ? "eval case written" : "dismissed");
      })
      .haltScope("eligibility-assessment", target ->
          CompletableFuture.completedFuture(Map.of("backlog drained", BACKLOG_DRAINED.get())))
      .devRoutes(true)
      .build();

  private TestPlugins() {}

  private static ControlCatalog catalog() {
    var fixtures = YamlCatalog.fromClasspath("spec/fixtures/controls-patterns.yaml");
    var extra = YamlCatalog.fromClasspath("test-controls.yaml");
    var all = new java.util.ArrayList<HumanControl>(fixtures.controls());
    all.addAll(extra.controls());
    var controls = List.copyOf(all);
    return new ControlCatalog() {
      @Override
      public List<HumanControl> controls() {
        return controls;
      }

      @Override
      public String digest() {
        return fixtures.digest();
      }
    };
  }

  // ---- the contract's schema

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectNode SCHEMA = load();
  private static final Map<String, JsonSchema> BY_DEF = new ConcurrentHashMap<>();
  private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  private static ObjectNode load() {
    try (InputStream in = TestPlugins.class.getClassLoader().getResourceAsStream("spec/oversight.schema.json")) {
      return (ObjectNode) MAPPER.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Fails unless the JSON conforms to {@code $defs/def} of spec/oversight.schema.json. */
  static void assertConforms(String def, JsonNode json) {
    var schema = BY_DEF.computeIfAbsent(def, d -> {
      var node = SCHEMA.deepCopy();
      node.put("$ref", "#/$defs/" + d);
      return FACTORY.getSchema(node, CONFIG);
    });
    var errors = schema.validate(json);
    assertTrue(errors.isEmpty(), () -> def + " does not conform: " + errors + "\n" + json.toPrettyString());
  }
}
