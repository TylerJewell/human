package io.akka.human.api.spi;

import java.util.Map;

/**
 * One human control from the service's bundle.
 *
 * @param humanConfig the control's Verify v2.8 {@code human_config}, as parsed YAML or JSON
 * @param haltConfig the control's {@code halt_config}, for halt-restart controls; empty otherwise
 */
public record HumanControl(
    String id,
    String name,
    String corpusRef,
    String flavor,
    int revision,
    Map<String, Object> humanConfig,
    Map<String, Object> haltConfig) {

  public HumanControl {
    humanConfig = humanConfig == null ? Map.of() : humanConfig;
    haltConfig = haltConfig == null ? Map.of() : haltConfig;
  }

  public String pattern() {
    return (String) humanConfig.get("pattern");
  }
}
