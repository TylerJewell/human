package io.akka.human.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.SubjectKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The pinned Verify registries (spec/verify, copied onto the classpath at io/akka/human/verify): the
 * fourteen patterns with their defaults, the shared defaults and the decision vocabulary. Read once.
 */
public final class Verify {

  private static final String ROOT = "io/akka/human/verify/";
  private static final Verify INSTANCE = load();

  /**
   * One pattern of human-patterns.yaml.
   *
   * @param blocking {@code true}, {@code false} or {@code system}
   * @param actions the decisions the pattern allows by default, in registry order, with their labels
   * @param defaults the human_config settings that differ from the shared defaults
   */
  public record PatternDef(
      Pattern pattern,
      String name,
      String description,
      List<String> flavors,
      String blocking,
      SubjectKind subjectKind,
      Map<Action, String> actions,
      Map<String, Object> defaults) {}

  private final Map<String, Object> sharedDefaults;
  private final Map<Pattern, PatternDef> patterns;
  private final Set<Action> decisions;

  private Verify(Map<String, Object> sharedDefaults, Map<Pattern, PatternDef> patterns, Set<Action> decisions) {
    this.sharedDefaults = sharedDefaults;
    this.patterns = patterns;
    this.decisions = decisions;
  }

  public static Verify registries() {
    return INSTANCE;
  }

  public Map<String, Object> sharedDefaults() {
    return sharedDefaults;
  }

  public PatternDef pattern(Pattern pattern) {
    return patterns.get(pattern);
  }

  /** Every decision name in human-decisions.yaml. */
  public Set<Action> decisions() {
    return decisions;
  }

  @SuppressWarnings("unchecked")
  private static Verify load() {
    var patternsYaml = read("human-patterns.yaml");
    var decisionsYaml = read("human-decisions.yaml");
    var byPattern = new EnumMap<Pattern, PatternDef>(Pattern.class);
    var entries = (Map<String, Map<String, Object>>) patternsYaml.get("patterns");
    for (var entry : entries.entrySet()) {
      var pattern = Pattern.of(entry.getKey());
      var def = entry.getValue();
      var actions = new LinkedHashMap<Action, String>();
      ((Map<String, String>) def.get("actions")).forEach((name, label) -> actions.put(Action.valueOf(name), label));
      byPattern.put(pattern, new PatternDef(pattern, (String) def.get("name"), (String) def.get("description"),
          List.copyOf((List<String>) def.get("flavors")), String.valueOf(def.get("blocking")),
          SubjectKind.of((String) def.get("subject_kind")), actions,
          def.get("defaults") == null ? Map.of() : (Map<String, Object>) def.get("defaults")));
    }
    if (byPattern.size() != Pattern.values().length) {
      throw new IllegalStateException("the pattern registry names " + byPattern.size() + " patterns, not 14");
    }
    var decisions = ((Map<String, Object>) decisionsYaml.get("actions")).keySet().stream()
        .map(Action::valueOf).collect(Collectors.toUnmodifiableSet());
    return new Verify((Map<String, Object>) patternsYaml.get("defaults"), byPattern, decisions);
  }

  private static Map<String, Object> read(String file) {
    try (InputStream in = Verify.class.getClassLoader().getResourceAsStream(ROOT + file)) {
      if (in == null) throw new IllegalStateException("the pinned Verify file is not on the classpath: " + ROOT + file);
      return new ObjectMapper(new YAMLFactory()).readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
