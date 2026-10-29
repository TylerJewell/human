package io.akka.human.local;

import akka.javasdk.client.ComponentClient;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.spi.HumanPlugins;

/**
 * How the guardrails reach the library. The runtime creates a guardrail with only its configuration,
 * so the service installs the component client and its plug-ins here from its setup's
 * {@code onStartup}; a guardrail evaluated before that fails the call.
 */
public final class HumanGuardrails {

  record Installed(ComponentClient client, HumanPlugins plugins) {
    HumanClient human() {
      return new HumanClient(client, plugins);
    }
  }

  private static volatile Installed installed;

  private HumanGuardrails() {}

  public static void install(ComponentClient client, HumanPlugins plugins) {
    installed = new Installed(client, plugins);
  }

  static Installed installed() {
    var current = installed;
    if (current == null) throw new IllegalStateException("HumanGuardrails.install has not been called");
    return current;
  }
}
